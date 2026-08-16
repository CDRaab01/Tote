"""The one unauthenticated surface: what a phone sees when it taps a tag or scans a QR.

This route is reachable by anything on the tailnet with no credentials, so the security property
matters more than the page does: **it must not leak contents.** A tag is a physical object on a
bin; anyone who can read it already knows the bin exists. What they must not learn from it is
what is inside.

So the page says the code and nothing else. Not the label, not the location, not the count —
"A14, open it in Tote" is a useful dead end; "A14 — Christmas decor, Attic, 37 items" is an
inventory disclosure printed on the outside of the box.
"""

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse

from app.config import settings
from app.limiter import limiter

router = APIRouter(tags=["public"])

_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tote {code}</title>
<style>
  :root {{ color-scheme: dark light; }}
  body {{
    margin: 0; min-height: 100dvh; display: grid; place-items: center;
    background: #0B0D10; color: #F4F6F8;
    font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  }}
  .card {{
    background: linear-gradient(135deg, #1E293B, #475569);
    border-radius: 18px; padding: 28px 32px; max-width: 22rem; margin: 24px;
  }}
  .code {{ font-size: 3rem; font-weight: 700; letter-spacing: .02em; margin: 0; }}
  .rule {{ height: 3px; background: #F6D80B; border-radius: 2px; margin: 14px 0 16px; }}
  p {{ margin: 0 0 8px; opacity: .85; }}
  .muted {{ font-size: .85rem; opacity: .6; }}
</style>
</head>
<body>
  <div class="card">
    <p class="code">{code}</p>
    <div class="rule"></div>
    <p>Open this tote in the Tote app to see what is in it.</p>
    <p class="muted">Contents are not shown here.</p>
  </div>
</body>
</html>
"""


@router.get("/t/{code}", response_class=HTMLResponse, include_in_schema=False)
@limiter.limit("60/minute")
async def tag_landing(request: Request, code: str):
    """Where a tapped NFC tag or a scanned QR lands.

    On a phone with Tote installed this is usually never rendered: the app's NDEF_DISCOVERED
    intent filter matches the URI first and opens the tote directly. This page is the fallback —
    a different phone, a guest, a dead app install — and its whole job is to not be a dead end
    while also not being an inventory listing.

    The code is echoed back escaped and truncated. It arrives from a physical tag that anyone
    could have written, so it is untrusted input that lands in HTML.
    """
    import html

    safe = html.escape(code[:16])
    return HTMLResponse(_PAGE.format(code=safe))


@router.get("/t/", include_in_schema=False)
async def tag_landing_bare():
    """A tag written with a truncated payload lands here rather than 404ing into nothing."""
    return HTMLResponse(
        _PAGE.format(code="?"),
        status_code=404,
    )


@router.get("/nfc/base", include_in_schema=False)
async def nfc_base() -> dict:
    """The URI base the client should write into tags.

    Served rather than compiled in: a tag is a physical object no deploy can patch, so the value
    being written must be changeable from one place. The client asks at write time.
    """
    return {"base": settings.nfc_uri_base}
