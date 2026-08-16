"""The printable index card, and the URI that the NFC tag and the QR both encode.

One implementation of the card, server-side, because the card is a physical object: two
renderers would eventually disagree and the disagreement would be discovered in an attic.

The QR is not decoration. NFC tags fail, get taped over, or sit on a bin someone is carrying
with both arms — a QR on the same card costs nothing, reads from across a room, and works from
any phone. It encodes **exactly the same URI** as the tag, so the two are never a fork.
"""

import io

import qrcode
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas

from app.config import settings
from app.models.tote import Tote

# A standard 5x3in index card, landscape — the thing people already own and already tape to bins.
CARD_W, CARD_H = 5 * inch, 3 * inch


def tote_uri(code: str, base: str | None = None) -> str:
    """The canonical URI for a tote, used by BOTH the NFC tag and the QR.

    Built from a *code*, not an opaque id, on purpose. A written tag is a physical object in an
    attic that no deploy can patch, so the resolution has to be something the server can still
    honour if ids, hosts or schemas change. A code is the one identifier that is also printed on
    the card and readable by a human.
    """
    root = (base or settings.nfc_uri_base).rstrip("/")
    return f"{root}/t/{code}"


def render_card(
    tote: Tote, *, location: str | None, category: str | None, item_count: int
) -> bytes:
    """Render one index card as a PDF.

    Layout is deliberately boring: the code enormous, everything else small. Someone reading this
    is standing in front of a stack of identical bins, at arm's length, possibly holding two of
    them — the code is the only thing that has to be legible from there.
    """
    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=(CARD_W, CARD_H))

    margin = 0.3 * inch
    qr_size = 1.4 * inch

    # The code, as big as the card allows.
    c.setFont("Helvetica-Bold", 54)
    c.drawString(margin, CARD_H - margin - 0.55 * inch, tote.code)

    c.setFont("Helvetica", 14)
    c.drawString(margin, CARD_H - margin - 0.95 * inch, (tote.label or "")[:38])

    c.setFont("Helvetica", 10)
    line = " · ".join(p for p in (category, location) if p)
    if line:
        c.drawString(margin, CARD_H - margin - 1.25 * inch, line[:56])

    # The count is printed with an explicit "as of", because a printed count is a snapshot and
    # will be wrong the moment something moves. Saying when it was true is the difference between
    # a stale number and a lie.
    c.setFont("Helvetica", 9)
    c.drawString(
        margin,
        margin + 0.30 * inch,
        f"{item_count} item{'' if item_count == 1 else 's'} when printed",
    )
    c.drawString(margin, margin + 0.12 * inch, "Scan or tap to see what is in it now")

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=1,
    )
    qr.add_data(tote_uri(tote.code))
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")

    qr_buf = io.BytesIO()
    img.save(qr_buf, format="PNG")
    qr_buf.seek(0)
    from reportlab.lib.utils import ImageReader

    c.drawImage(
        ImageReader(qr_buf),
        CARD_W - margin - qr_size,
        margin,
        width=qr_size,
        height=qr_size,
    )

    c.showPage()
    c.save()
    return buf.getvalue()
