"""`GET /items` as a paging contract, because a client assembles its whole offline cache from it.

These are not tests about a list endpoint's ergonomics. The Android client walks this endpoint
with `offset` and writes the result into Room as the catalogue it searches when the Wi-Fi in the
garage is bad — so a page that silently drops a row produces an item that exists on the server,
is invisible on the phone, and says nothing about it. That is the failure this file exists to
prevent, and it is the failure that actually happened: production held 578 items, the client
asked for one unpaged page, and 378 of them were missing from every count and every offline
search for weeks.
"""

import uuid


async def _tote(c, code="A14"):
    r = await c.post("/totes", json={"code": code})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name, tote_id=None):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id})
    assert r.status_code == 201, r.text
    return r.json()


async def _page(c, **params):
    r = await c.get("/items", params=params)
    assert r.status_code == 200, r.text
    return r.json()


async def test_paging_is_stable_across_identical_names(auth_client):
    """Twelve items with ONE name, paged four at a time: every id exactly once.

    The ordering is `name` first, and twelve equal names means twelve ties. Postgres is free to
    order tied rows differently between two queries, so with `offset` alone a row can appear on
    two pages while another appears on none — the client's snapshot then holds a duplicate and
    is missing something, with nothing anywhere reporting it.

    Six rows reading "Shirt" is not hypothetical here; it is what prompted #36. `Item.id` as a
    final sort key makes the order total, so this holds by construction rather than by luck.
    """
    tote = await _tote(auth_client, "P01")
    created = {
        (await _item(auth_client, "Toddler Bed Comforter", tote["id"]))["id"] for _ in range(12)
    }
    assert len(created) == 12

    seen: list[str] = []
    for offset in range(0, 12, 4):
        page = await _page(auth_client, limit=4, offset=offset)
        seen += [row["id"] for row in page]

    assert len(seen) == len(set(seen)), "a row was returned on two different pages"
    assert set(seen) == created, "a row was returned on no page at all"


async def test_every_item_is_reachable_by_paging(auth_client):
    """Past the default page size, a walk still sees everything — the production shape.

    250 items against a 200 default is the smallest case that reproduces what happened: the
    first page looks complete and correct, and the fifty rows behind it are simply not there.
    """
    tote = await _tote(auth_client, "P02")
    created = {(await _item(auth_client, f"Item {i:03d}", tote["id"]))["id"] for i in range(250)}

    first = await _page(auth_client, limit=200, offset=0)
    second = await _page(auth_client, limit=200, offset=200)

    assert len(first) == 200
    assert len(second) == 50, "the short page is how the client knows to stop"
    assert {row["id"] for row in first + second} == created


async def test_the_default_page_size_is_unchanged(auth_client):
    """Pins the decision NOT to raise the default.

    The fix is that the client pages, not that the server hands over more at once. Raising this
    would paper over an unpaged client until the next threshold, at which point the same bug
    returns with a bigger number and no test to catch it.
    """
    tote = await _tote(auth_client, "P03")
    for i in range(205):
        await _item(auth_client, f"Item {i:03d}", tote["id"])

    assert len(await _page(auth_client)) == 200


async def test_the_page_cap_is_five_hundred(auth_client):
    """Pins the constant the client's own page size is derived from.

    `CATALOGUE_PAGE_SIZE` in CatalogRepository is 500 because of this `le`. Lowering the cap
    without changing the client would make every refresh 422 — this fails in CI instead of on a
    phone in an attic.
    """
    assert (await auth_client.get("/items", params={"limit": 501})).status_code == 422
    assert (await auth_client.get("/items", params={"limit": 500})).status_code == 200


async def test_a_page_of_a_filtered_list_is_paged_too(auth_client):
    """The category screen walks the same endpoint with `category_id`, and 200 caps that too.

    The visible symptom is a contradiction one tap apart: the Find tab's chip carries an
    uncapped server count while the screen it opens listed at most 200 rows.
    """
    tote = await _tote(auth_client, "P04")
    category = (await auth_client.post("/categories", json={"name": "Paged"})).json()
    ids = set()
    for i in range(205):
        r = await auth_client.post(
            "/items",
            json={"name": f"C {i:03d}", "tote_id": tote["id"], "category_id": category["id"]},
        )
        assert r.status_code == 201, r.text
        ids.add(r.json()["id"])

    walked = set()
    for offset in (0, 200):
        page = await _page(auth_client, category_id=category["id"], limit=200, offset=offset)
        walked |= {row["id"] for row in page}

    assert walked == ids
    assert uuid.UUID(next(iter(walked)))
