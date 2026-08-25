"""The printable index card, and the URI that the NFC tag and the QR both encode.

One implementation of the card, server-side, because the card is a physical object: two
renderers would eventually disagree and the disagreement would be discovered in an attic.

The QR is not decoration. NFC tags fail, get taped over, or sit on a bin someone is carrying
with both arms — a QR on the same card costs nothing, reads from across a room, and works from
any phone. It encodes **exactly the same URI** as the tag, so the two are never a fork.
"""

import datetime
import io

import qrcode
from reportlab.lib.colors import HexColor, black, white
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas

from app.config import settings
from app.models.tote import Tote
from app.services.colors import color_hex

# A standard 5x3in index card, landscape — the thing people already own and already tape to bins.
CARD_W, CARD_H = 5 * inch, 3 * inch

# The app's slate pair, in print: a charcoal ground that carries the white text, and the safety
# yellow as a RULE only — never a text ground, because white on the yellow is 1.42:1, the one
# combination the accent forbids on screen and paper alike.
_CHARCOAL = HexColor("#1E293B")
_SAFETY_YELLOW = HexColor("#F6D80B")

# Header band geometry. The band ends where the body begins; the yellow rule sits flush along
# its bottom edge, and the bin-colour spine runs the full left edge at about a hole-punch width.
_BAND_BOTTOM = CARD_H - 1.30 * inch
_RULE_H = 4
_SPINE_W = 10


def _latin1(text: str) -> str:
    """Degrade user text to what the card's fonts can actually print.

    The card is drawn with reportlab's base-14 Type1 fonts, which cover Latin-1 and nothing
    else — no emoji, no CJK. Labels, categories and locations are free text and can carry
    anything; a character the font lacks must cost a ``?``, not the whole card.
    """
    return text.encode("latin-1", "replace").decode("latin-1")


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

    The dressing serves the same reading: the header band puts the code in white on charcoal (the
    highest contrast a home printer can produce), and the spine down the left edge repeats the
    bin's own colour, so the card can be matched to its bin by sight before any text is read.
    """
    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=(CARD_W, CARD_H))

    margin = 0.3 * inch
    qr_size = 1.4 * inch

    # Grounds first, text after, so nothing white ever lands under a rect drawn later: the
    # charcoal header band, the safety-yellow rule along its bottom edge, then the spine.
    c.setFillColor(_CHARCOAL)
    c.rect(0, _BAND_BOTTOM, CARD_W, CARD_H - _BAND_BOTTOM, stroke=0, fill=1)
    c.setFillColor(_SAFETY_YELLOW)
    c.rect(0, _BAND_BOTTOM - _RULE_H, CARD_W, _RULE_H, stroke=0, fill=1)

    # The bin's colour, down the whole left edge — the same resolved hex the client's bin glyph
    # paints (services/colors.py owns the one mapping), so the card and the phone describe the
    # physical bin identically. Omitted when the colour is unset or unknown: no colour sends the
    # eyes to the code, a guessed one sends the person to the wrong bin.
    spine = color_hex(tote.color)
    if spine is not None:
        c.setFillColor(HexColor(spine))
        c.rect(0, 0, _SPINE_W, CARD_H, stroke=0, fill=1)

    # The code, as big as the card allows — white on the charcoal.
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 54)
    c.drawString(margin, CARD_H - margin - 0.55 * inch, _latin1(tote.code))

    c.setFont("Helvetica", 14)
    c.drawString(margin, CARD_H - margin - 0.95 * inch, _latin1(tote.label or "")[:38])

    if category:
        # Small caps in spirit — uppercase, small, tucked into the band's top-right corner. Text
        # only, no emoji: the card has no fonts beyond the base-14 set.
        c.setFont("Helvetica", 7)
        c.drawRightString(CARD_W - margin, CARD_H - 0.24 * inch, _latin1(category).upper()[:32])

    c.setFillColor(black)
    if location:
        c.setFont("Helvetica", 10)
        c.drawString(margin, CARD_H - margin - 1.25 * inch, _latin1(location)[:38])

    # The count is printed with the date it was true, because a printed count is a snapshot and
    # will be wrong the moment something moves. Saying when it was true is the difference between
    # a stale number and a lie.
    c.setFont("Helvetica", 9)
    y = margin + 0.12 * inch
    c.drawString(margin, y, "Scan or tap to see what is in it now")
    y += 0.18 * inch
    if tote.last_verified_at is not None:
        # Only when a human has actually checked the contents against reality. When they never
        # have, the line is omitted entirely — "Verified never" would print the card's most
        # trust-building word on exactly the bins that have earned it least.
        c.drawString(margin, y, f"Verified {tote.last_verified_at.date().isoformat()}")
        y += 0.18 * inch
    today = datetime.datetime.now(datetime.UTC).date().isoformat()
    c.drawString(
        margin,
        y,
        f"{item_count} item{'' if item_count == 1 else 's'} · updated {today}",
    )

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
