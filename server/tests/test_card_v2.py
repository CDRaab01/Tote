"""Index card v2: the charcoal header band, the bin-colour spine, and the verified line.

No PDF parser here on purpose — reportlab compresses its content streams, so byte-grepping the
output proves nothing and a parsing dependency would exist for one file. The assertions ride the
same seam the QR test does: what actually reaches the canvas during a real render, plus the
magic bytes that prove a card came out the other end.
"""

from reportlab.lib.colors import HexColor
from reportlab.pdfgen.canvas import Canvas


async def _tote(c, code="A14", **kw):
    r = await c.post("/totes", json={"code": code, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _card(c, tote_id) -> bytes:
    r = await c.get(f"/totes/{tote_id}/card")
    assert r.status_code == 200, r.text
    assert r.content[:5] == b"%PDF-", r.content[:40]
    return r.content


async def test_a_fully_dressed_card_is_still_a_valid_pdf(auth_client):
    loc = (await auth_client.post("/locations", json={"name": "Attic"})).json()
    cat = (await auth_client.post("/categories", json={"name": "Christmas"})).json()
    t = await _tote(
        auth_client,
        "A14",
        label="Lights and garland",
        color="green",
        location_id=loc["id"],
        category_id=cat["id"],
    )
    assert len(await _card(auth_client, t["id"])) > 1000


async def test_the_colour_spine_appears_only_when_the_colour_resolves(auth_client, monkeypatch):
    """The spine is painted from the same free-text-to-hex map as the client's bin glyph, and an
    unresolvable colour must produce no spine at all — a wrong colour sends someone to the wrong
    bin with confidence, no colour just sends their eyes to the code."""
    rects: list[tuple] = []
    original = Canvas.rect

    def capture(self, *a, **kw):
        rects.append(a)
        return original(self, *a, **kw)

    monkeypatch.setattr(Canvas, "rect", capture)

    green = await _tote(auth_client, "S01", color="green")
    await _card(auth_client, green["id"])
    with_spine = len(rects)

    rects.clear()
    unknown = await _tote(auth_client, "S02", color="taupe-ish")
    await _card(auth_client, unknown["id"])
    assert len(rects) == with_spine - 1

    rects.clear()
    unset = await _tote(auth_client, "S03")
    await _card(auth_client, unset["id"])
    assert len(rects) == with_spine - 1


async def test_the_verified_line_appears_only_after_a_human_verified(
    auth_client, raw_sql, monkeypatch
):
    """No "Verified never". The line is the card's most trust-building sentence, and printing it
    on a bin nobody has ever checked would spend that trust exactly where it was never earned."""
    drawn: list[str] = []
    original = Canvas.drawString

    def capture(self, x, y, text, *a, **kw):
        drawn.append(text)
        return original(self, x, y, text, *a, **kw)

    monkeypatch.setattr(Canvas, "drawString", capture)

    t = await _tote(auth_client, "V01")
    await _card(auth_client, t["id"])
    assert not any(s.startswith("Verified") for s in drawn), drawn

    drawn.clear()
    await raw_sql("UPDATE totes SET last_verified_at = now() WHERE id = :id", id=t["id"])
    await _card(auth_client, t["id"])
    assert any(s.startswith("Verified 2") for s in drawn), drawn


async def test_a_non_latin1_label_degrades_instead_of_500ing(auth_client):
    """Labels, categories and locations are free text; the card's fonts are base-14 Type1,
    Latin-1 only. An emoji or CJK character must cost a replacement glyph, never the card."""
    cat = (await auth_client.post("/categories", json={"name": "Décor 🎄"})).json()
    loc = (await auth_client.post("/locations", json={"name": "Grenier №2"})).json()
    t = await _tote(
        auth_client,
        "U01",
        label="Božić — décor 🎁",
        category_id=cat["id"],
        location_id=loc["id"],
    )
    assert len(await _card(auth_client, t["id"])) > 1000


async def test_the_spine_is_painted_the_glyphs_own_hex(auth_client, monkeypatch):
    """Counting rects (above) proves A spine appears for a resolvable colour; this pins WHAT
    colour fills it, so card.py can never hardcode a divergent hex or grow its own mapping —
    the card, the row and the bin agreeing is the entire point of the band."""
    fills: list = []
    painted: list = []
    orig_fill = Canvas.setFillColor
    orig_rect = Canvas.rect

    def capture_fill(self, colour, *a, **kw):
        fills.append(colour)
        return orig_fill(self, colour, *a, **kw)

    def capture_rect(self, x, y, w, h, *a, **kw):
        painted.append(((x, y, w, h), fills[-1] if fills else None))
        return orig_rect(self, x, y, w, h, *a, **kw)

    monkeypatch.setattr(Canvas, "setFillColor", capture_fill)
    monkeypatch.setattr(Canvas, "rect", capture_rect)

    green = await _tote(auth_client, "S04", color="green")
    await _card(auth_client, green["id"])

    # The spine is the only full-height rect anchored at the origin, a hole-punch width wide.
    spines = [f for (x, y, w, h), f in painted if x == 0 and y == 0 and w < 20]
    assert len(spines) == 1
    assert str(spines[0]) == str(HexColor("#2A5240"))
