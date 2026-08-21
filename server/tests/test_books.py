"""The book scanner: ISBN validation, the lookup's tri-state contract, and the filing endpoint.

The service tests run against `httpx.MockTransport` — real request/response plumbing, no
network. The router tests monkeypatch the lookup the way `test_scan.py` monkeypatches
`identify_item`: the endpoint's job is filing, and the lookup's job is already proven above it.
"""

import uuid

import httpx
import pytest
from sqlalchemy import select

from app.models.item import Item
from app.models.movement import Movement
from app.services.books import (
    BookMetadata,
    LookupUnavailable,
    description_for,
    fetch_cover,
    is_valid_isbn13,
    lookup_isbn,
)

FANTASTIC_MR_FOX = "9780140328721"

OPENLIBRARY_HIT = {
    f"ISBN:{FANTASTIC_MR_FOX}": {
        "title": "Fantastic Mr. Fox",
        "authors": [{"name": "Roald Dahl"}],
        "publishers": [{"name": "Puffin"}],
        "publish_date": "October 1, 1988",
        "cover": {
            "small": "https://covers.openlibrary.org/b/id/8739161-S.jpg",
            "medium": "https://covers.openlibrary.org/b/id/8739161-M.jpg",
            "large": "https://covers.openlibrary.org/b/id/8739161-L.jpg",
        },
    }
}


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


# ── ISBN validation ────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "code,ok",
    [
        (FANTASTIC_MR_FOX, True),
        ("978-0-14-032872-1", True),  # hyphens are how humans type them
        ("9791234567896", True),  # 979 Bookland
        ("9780140328722", False),  # bad checksum
        ("9771234567898", False),  # 977 = periodicals, not a book
        ("5012345678900", False),  # ordinary product EAN — the soup can
        ("978014032872", False),  # 12 digits
        ("", False),
        ("not a number", False),
    ],
)
def test_is_valid_isbn13(code, ok):
    assert is_valid_isbn13(code) is ok


def test_description_skips_absent_parts():
    full = BookMetadata(isbn="x", title="T", authors=("A", "B"), publisher="P", publish_year="1988")
    assert description_for(full) == "by A, B · P, 1988"
    bare = BookMetadata(isbn="x", title="T")
    assert description_for(bare) is None
    no_imprint = BookMetadata(isbn="x", title="T", authors=("A",))
    assert description_for(no_imprint) == "by A"


# ── The lookup's tri-state contract ────────────────────────────────────────────────────


async def test_a_hit_parses_title_authors_and_the_largest_cover():
    async with _client(lambda req: httpx.Response(200, json=OPENLIBRARY_HIT)) as c:
        meta = await lookup_isbn(FANTASTIC_MR_FOX, client=c)
    assert meta is not None
    assert meta.title == "Fantastic Mr. Fox"
    assert meta.authors == ("Roald Dahl",)
    assert meta.publisher == "Puffin"
    assert meta.publish_year == "1988"
    assert meta.cover_url.endswith("-L.jpg"), "the cover becomes the item's one photo — largest"
    assert meta.source == "openlibrary"


async def test_an_empty_object_is_a_definitive_miss_not_a_failure():
    """OpenLibrary's actual 'not found' is a 200 with an empty JSON object. That is an ANSWER —
    it must return None (→ Review draft), never raise (→ 503 + retry)."""
    async with _client(lambda req: httpx.Response(200, json={})) as c:
        assert await lookup_isbn(FANTASTIC_MR_FOX, client=c) is None


async def test_one_transport_failure_is_retried_and_recovered():
    """The measured failure mode: an SSL reset on rapid consecutive calls — exactly what a
    shelf-scanning session produces. One retry recovers it."""
    calls = 0

    def handler(req):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise httpx.ConnectError("reset by peer")
        return httpx.Response(200, json=OPENLIBRARY_HIT)

    async with _client(handler) as c:
        meta = await lookup_isbn(FANTASTIC_MR_FOX, client=c)
    assert meta is not None and calls == 2


async def test_two_transport_failures_raise_unavailable_not_none():
    """The load-bearing edge: a dead network must NOT read as 'this book does not exist'.
    None here would mint a junk draft per Wi-Fi hiccup."""

    def handler(req):
        raise httpx.ConnectError("no route")

    async with _client(handler) as c:
        with pytest.raises(LookupUnavailable):
            await lookup_isbn(FANTASTIC_MR_FOX, client=c)


async def test_google_books_fires_only_with_a_key(monkeypatch):
    hosts: list[str] = []

    def handler(req):
        hosts.append(req.url.host)
        if "openlibrary" in req.url.host:
            return httpx.Response(200, json={})
        return httpx.Response(
            200,
            json={
                "items": [
                    {
                        "volumeInfo": {
                            "title": "Found On Google",
                            "authors": ["Somebody"],
                            "imageLinks": {"thumbnail": "http://books.google.com/x.jpg"},
                        }
                    }
                ]
            },
        )

    from app.config import settings

    # Unkeyed: an OpenLibrary miss stays a miss — no second host is ever asked.
    monkeypatch.setattr(settings, "google_books_api_key", None)
    async with _client(handler) as c:
        assert await lookup_isbn(FANTASTIC_MR_FOX, client=c) is None
    assert all("openlibrary" in h for h in hosts)

    # Keyed: the fallback answers, and its http:// cover is upgraded to https.
    hosts.clear()
    monkeypatch.setattr(settings, "google_books_api_key", "k")
    async with _client(handler) as c:
        meta = await lookup_isbn(FANTASTIC_MR_FOX, client=c)
    assert meta is not None and meta.source == "google_books"
    assert meta.cover_url.startswith("https://")
    assert any("googleapis" in h for h in hosts)


async def test_a_cover_fetch_failure_degrades_to_none():
    def handler(req):
        raise httpx.ConnectError("no route")

    async with _client(handler) as c:
        assert await fetch_cover("https://covers.example/x.jpg", client=c) is None
    # And a non-image answer (an error page) must not become the item's photograph.
    async with _client(lambda r: httpx.Response(200, text="<html>oops</html>")) as c:
        assert await fetch_cover("https://covers.example/x.jpg", client=c) is None


# ── The endpoint ───────────────────────────────────────────────────────────────────────


async def _tote(client, code):
    r = await client.post("/totes", json={"code": code})
    assert r.status_code == 201, r.text
    return r.json()


def _scan_body(tote_id=None, isbn=FANTASTIC_MR_FOX, capture_id=None):
    return {
        "isbn": isbn,
        "tote_id": tote_id,
        "capture_id": capture_id or str(uuid.uuid4()),
    }


FOUND = BookMetadata(
    isbn=FANTASTIC_MR_FOX,
    title="Fantastic Mr. Fox",
    authors=("Roald Dahl",),
    publisher="Puffin",
    publish_year="1988",
    cover_url="https://covers.openlibrary.org/b/id/8739161-L.jpg",
)


@pytest.fixture
def found_book(monkeypatch):
    import app.routers.scan as scan_router

    async def fake_lookup(isbn):
        return FOUND

    async def fake_cover(url):
        return (b"\xff\xd8fakejpeg", "image/jpeg")

    monkeypatch.setattr(scan_router, "lookup_isbn", fake_lookup)
    monkeypatch.setattr(scan_router, "fetch_cover", fake_cover)


async def test_a_found_book_files_straight_into_the_bin(auth_client, db, found_book):
    tote = await _tote(auth_client, "BK1")

    r = await auth_client.post("/items/scan-isbn", json=_scan_body(tote["id"]))
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["found"] is True
    item = body["item"]
    assert item["is_draft"] is False, "a found book is a real item, not a draft"
    assert item["name"] == "Fantastic Mr. Fox"
    assert item["description"] == "by Roald Dahl · Puffin, 1988"
    assert item["current_tote_id"] == tote["id"]
    assert item["photo_count"] == 1, "the cover is photo 0"

    moves = (
        (await db.execute(select(Movement).where(Movement.item_id == uuid.UUID(item["id"]))))
        .scalars()
        .all()
    )
    assert [m.reason for m in moves] == ["initial"]
    assert moves[0].moved_by_user_id == auth_client.user_id


async def test_a_found_book_lands_in_the_books_category_case_insensitively(auth_client, found_book):
    # The test fixture seeds only "Tools" (real accounts get the full DEFAULT_CATEGORIES).
    # Created here as "BOOKS" precisely so the lookup has to match case-insensitively — the
    # user may have retyped the name, and "books" typed by hand is the same vocabulary word.
    books_cat = (await auth_client.post("/categories", json={"name": "BOOKS"})).json()
    tote = await _tote(auth_client, "BK2")

    r = await auth_client.post("/items/scan-isbn", json=_scan_body(tote["id"]))
    assert r.json()["item"]["category_id"] == books_cat["id"]


async def test_no_books_category_means_null_and_still_files(auth_client, found_book):
    """The fixture household has no "Books" at all — the same state as a user who deleted it.
    A book with no category label is a smaller wrong than resurrecting a name they removed."""
    tote = await _tote(auth_client, "BK3")

    r = await auth_client.post("/items/scan-isbn", json=_scan_body(tote["id"]))
    assert r.status_code == 201
    assert r.json()["item"]["category_id"] is None


async def test_a_book_is_findable_by_author_and_isbn(auth_client, found_book):
    """`search_vector` covers name+description+notes — the entire reason the metadata lands in
    those three columns."""
    tote = await _tote(auth_client, "BK4")
    await auth_client.post("/items/scan-isbn", json=_scan_body(tote["id"]))

    for q in ("dahl", FANTASTIC_MR_FOX):
        hits = (await auth_client.get("/search", params={"q": q})).json()
        assert [h["item"]["name"] for h in hits] == ["Fantastic Mr. Fox"], f"not found by {q!r}"


async def test_no_bin_files_as_catalogued(auth_client, db, found_book):
    r = await auth_client.post("/items/scan-isbn", json=_scan_body(tote_id=None))
    assert r.status_code == 201
    item = r.json()["item"]
    assert item["current_tote_id"] is None
    moves = (
        (await db.execute(select(Movement).where(Movement.item_id == uuid.UUID(item["id"]))))
        .scalars()
        .all()
    )
    assert [m.reason for m in moves] == ["catalogued"]


async def test_a_failed_cover_still_files_the_book(auth_client, monkeypatch):
    import app.routers.scan as scan_router

    async def fake_lookup(isbn):
        return FOUND

    async def no_cover(url):
        return None

    monkeypatch.setattr(scan_router, "lookup_isbn", fake_lookup)
    monkeypatch.setattr(scan_router, "fetch_cover", no_cover)
    tote = await _tote(auth_client, "BK5")

    r = await auth_client.post("/items/scan-isbn", json=_scan_body(tote["id"]))
    assert r.status_code == 201
    assert r.json()["item"]["photo_count"] == 0


async def test_a_miss_becomes_a_review_draft_not_an_item(auth_client, monkeypatch):
    import app.routers.scan as scan_router

    async def fake_lookup(isbn):
        return None

    monkeypatch.setattr(scan_router, "lookup_isbn", fake_lookup)
    tote = await _tote(auth_client, "BK6")

    r = await auth_client.post("/items/scan-isbn", json=_scan_body(tote["id"]))
    assert r.status_code == 201
    body = r.json()
    assert body["found"] is False
    assert body["item"]["is_draft"] is True
    assert body["item"]["scan_error"] == "isbn_not_found"
    assert f"ISBN {FANTASTIC_MR_FOX}" in body["item"]["notes"]

    drafts = (await auth_client.get("/drafts")).json()
    assert body["item"]["id"] in [d["id"] for d in drafts], "the miss must reach the Review tab"


async def test_unavailable_is_503_and_commits_nothing(auth_client, db, monkeypatch):
    import app.routers.scan as scan_router

    async def dead_lookup(isbn):
        raise LookupUnavailable("no route")

    monkeypatch.setattr(scan_router, "lookup_isbn", dead_lookup)
    before = len((await db.execute(select(Item))).scalars().all())

    r = await auth_client.post("/items/scan-isbn", json=_scan_body())
    assert r.status_code == 503
    after = len((await db.execute(select(Item))).scalars().all())
    assert after == before, "a network flake must not mint any row"


async def test_a_replayed_capture_returns_the_same_book(auth_client, found_book):
    tote = await _tote(auth_client, "BK7")
    body = _scan_body(tote["id"])

    first = await auth_client.post("/items/scan-isbn", json=body)
    second = await auth_client.post("/items/scan-isbn", json=body)

    assert first.status_code == second.status_code == 201
    assert first.json()["item"]["id"] == second.json()["item"]["id"]
    items = (await auth_client.get("/items")).json()
    assert sum(1 for i in items if i["name"] == "Fantastic Mr. Fox") == 1


@pytest.mark.parametrize("isbn", ["9780140328722", "9771234567898", "12345", "abc"])
async def test_a_non_book_barcode_is_422(auth_client, isbn):
    r = await auth_client.post("/items/scan-isbn", json=_scan_body(isbn=isbn))
    assert r.status_code == 422


async def test_another_households_tote_is_404(auth_client, other_client, found_book):
    theirs = await _tote(other_client, "BK8")
    r = await auth_client.post("/items/scan-isbn", json=_scan_body(theirs["id"]))
    assert r.status_code == 404


async def test_needs_a_session(client):
    r = await client.post("/items/scan-isbn", json=_scan_body())
    assert r.status_code == 401
