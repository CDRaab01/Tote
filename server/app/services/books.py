"""ISBN lookup: turn a scanned barcode into a book's identity.

The one external dependency in the app that leaves the tailnet, and it was measured before it
was designed: OpenLibrary answers in 1.6 s warm, 8.8-11.8 s cold, and resets the connection
outright on a third rapid consecutive call — exactly the pattern a shelf-scanning session
produces. So the whole module is built as a *flaky* dependency, on `ntfy.py`'s shape: httpx
with a `client=` injection seam, bounded timeouts, and graceful degradation.

## The tri-state contract, and why it is load-bearing

`lookup_isbn` has three outcomes and they are three different facts:

* **`BookMetadata`** — the book is known. File it.
* **`None`** — the database answered and does not know this ISBN. A *definitive* miss: the
  caller makes a draft for the Review tab, where a human names the book.
* **`raises LookupUnavailable`** — the database could not be reached. Not an answer at all:
  the caller 503s, commits nothing, and the client offers Retry.

Collapsing the last two is the failure this module must never have: a network flake minting a
"this book does not exist" draft erodes the whole no-review promise, and a junk draft per
Wi-Fi hiccup is how the Review tab becomes noise.

## This is not the AI rule being bent

Nothing here is model output. An ISBN lookup returns database rows keyed by the number printed
on the object — deterministic, reproducible, and owner-confirmed exempt from the
nothing-AI-generated-auto-commits rule, which stands untouched for everything vision produces.
"""

import asyncio
import logging
from dataclasses import dataclass

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# The whole lookup — retries, fallback, cover — must finish inside this, because the client's
# interceptor gives the endpoint 45 s and a chain of cold calls could otherwise breach it. A
# breach would manufacture a FAILED row over a filing that succeeded: capture_id makes the
# retry safe, but the session list would lie.
OVERALL_BUDGET_SECONDS = 30

OPENLIBRARY_URL = "https://openlibrary.org/api/books"
GOOGLE_BOOKS_URL = "https://www.googleapis.com/books/v1/volumes"


class LookupUnavailable(Exception):
    """The book databases could not be reached. NOT 'the book does not exist'."""


@dataclass(frozen=True)
class BookMetadata:
    """What a barcode resolves to. `isbn` is always the caller's original scan."""

    isbn: str
    title: str
    authors: tuple[str, ...] = ()
    publisher: str | None = None
    publish_year: str | None = None
    cover_url: str | None = None
    source: str = "openlibrary"


def is_valid_isbn13(code: str) -> bool:
    """A real book barcode: 13 digits, Bookland prefix, valid EAN-13 checksum.

    978/979 ("Bookland") is what distinguishes a book from the soup can next to it — every
    other EAN-13 is a product code, and looking one up would return nothing at best and a
    wrong-but-plausible record at worst. The client filters these before calling; this is the
    server's backstop.
    """
    code = code.strip().replace("-", "").replace(" ", "")
    if len(code) != 13 or not code.isdigit():
        return False
    if not code.startswith(("978", "979")):
        return False
    total = sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(code[:12]))
    return (10 - total % 10) % 10 == int(code[12])


def description_for(meta: BookMetadata) -> str | None:
    """The one-line description a filed book carries: "by {authors} · {publisher}, {year}".

    Written into `items.description`, which `search_vector` covers — this line is what makes a
    book findable by its author. Absent parts are skipped rather than placeholdered; a book
    with no known publisher should not read "by Roald Dahl · None".
    """
    parts = []
    if meta.authors:
        parts.append("by " + ", ".join(meta.authors))
    imprint = ", ".join(p for p in (meta.publisher, meta.publish_year) if p)
    if imprint:
        parts.append(imprint)
    return " · ".join(parts) or None


async def lookup_isbn(isbn: str, *, client: httpx.AsyncClient | None = None) -> BookMetadata | None:
    """Resolve an ISBN, or return None on a definitive miss. Raises LookupUnavailable.

    OpenLibrary first (no key needed), **retried once** on transport failure — the measured
    failure mode is a connection reset on rapid consecutive calls, and one retry recovers it.
    Google Books only when a key is configured (`GOOGLE_BOOKS_API_KEY`; unkeyed requests 429,
    measured), and only as a fallback: a definitive miss from OpenLibrary still tries Google
    when available, because their catalogues genuinely differ.
    """
    isbn = isbn.strip().replace("-", "").replace(" ", "")

    async def _run(c: httpx.AsyncClient) -> BookMetadata | None:
        openlibrary_error: Exception | None = None
        miss = False
        for attempt in range(2):
            try:
                found = await _openlibrary(c, isbn)
            except (httpx.HTTPError, ValueError) as e:
                openlibrary_error = e
                logger.warning("openlibrary attempt %d for %s failed: %s", attempt + 1, isbn, e)
                continue
            if found is not None:
                return found
            miss = True
            break

        if settings.google_books_api_key:
            try:
                found = await _google_books(c, isbn)
            except (httpx.HTTPError, ValueError) as e:
                logger.warning("google books for %s failed: %s", isbn, e)
            else:
                if found is not None:
                    return found
                miss = True

        if miss:
            return None
        raise LookupUnavailable(str(openlibrary_error))

    try:
        async with asyncio.timeout(OVERALL_BUDGET_SECONDS):
            if client is not None:
                return await _run(client)
            async with httpx.AsyncClient(
                timeout=settings.openlibrary_timeout_seconds, follow_redirects=True
            ) as c:
                return await _run(c)
    except TimeoutError as e:
        raise LookupUnavailable("lookup exceeded the overall budget") from e


async def _openlibrary(client: httpx.AsyncClient, isbn: str) -> BookMetadata | None:
    resp = await client.get(
        OPENLIBRARY_URL,
        params={"bibkeys": f"ISBN:{isbn}", "format": "json", "jscmd": "data"},
    )
    resp.raise_for_status()
    payload = resp.json()
    book = payload.get(f"ISBN:{isbn}")
    if not book:
        # An empty object is OpenLibrary's actual "not found" — the request SUCCEEDED.
        return None
    title = (book.get("title") or "").strip()
    if not title:
        return None
    cover = book.get("cover") or {}
    return BookMetadata(
        isbn=isbn,
        title=title,
        authors=tuple(
            a["name"].strip() for a in book.get("authors", []) if a.get("name", "").strip()
        ),
        publisher=next(
            (p["name"].strip() for p in book.get("publishers", []) if p.get("name", "").strip()),
            None,
        ),
        publish_year=_year_of(book.get("publish_date")),
        # Largest first: this becomes the item's one photograph.
        cover_url=cover.get("large") or cover.get("medium") or cover.get("small"),
        source="openlibrary",
    )


async def _google_books(client: httpx.AsyncClient, isbn: str) -> BookMetadata | None:
    resp = await client.get(
        GOOGLE_BOOKS_URL,
        params={"q": f"isbn:{isbn}", "key": settings.google_books_api_key},
    )
    resp.raise_for_status()
    items = resp.json().get("items") or []
    if not items:
        return None
    info = items[0].get("volumeInfo") or {}
    title = (info.get("title") or "").strip()
    if not title:
        return None
    links = info.get("imageLinks") or {}
    cover = links.get("thumbnail") or links.get("smallThumbnail")
    return BookMetadata(
        isbn=isbn,
        title=title,
        authors=tuple(a.strip() for a in info.get("authors", []) if a.strip()),
        publisher=(info.get("publisher") or "").strip() or None,
        publish_year=_year_of(info.get("publishedDate")),
        # Google serves covers over http:// by default; the photo fetch requires https.
        cover_url=cover.replace("http://", "https://") if cover else None,
        source="google_books",
    )


def _year_of(date: str | None) -> str | None:
    """Just the year. Publish dates arrive as anything from "1988" to "October 1, 1988"."""
    if not date:
        return None
    for token in str(date).replace(",", " ").split():
        if token.isdigit() and len(token) == 4:
            return token
    return None


async def fetch_cover(
    url: str | None, *, client: httpx.AsyncClient | None = None
) -> tuple[bytes, str] | None:
    """Download a cover image, or None. Never raises — a coverless book still files.

    The cover is a nicety on top of a filing that already succeeded in every way that matters;
    an exception here taking the filing down with it would be the label-pass mistake all over
    again (a 503 rewriting a good identification).
    """
    if not url:
        return None
    try:
        if client is not None:
            resp = await client.get(url, follow_redirects=True)
        else:
            async with httpx.AsyncClient(
                timeout=settings.openlibrary_timeout_seconds, follow_redirects=True
            ) as c:
                resp = await c.get(url)
        resp.raise_for_status()
        content_type = resp.headers.get("content-type", "").split(";")[0].strip()
        if content_type not in ("image/jpeg", "image/png"):
            return None
        if not resp.content:
            return None
        return resp.content, content_type
    except httpx.HTTPError as e:
        logger.warning("cover fetch failed for %s: %s", url, e)
        return None
