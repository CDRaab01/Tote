"""The one free-text-bin-colour to paintable-hex mapping.

`totes.color` is deliberately free text ("27gal clear" lives in bin_kind, "green" lives here) —
enumerating bin colours would be inventing a vocabulary nobody asked for. But two consumers need
an actual colour out of it: the client's bin glyph (the swatch that matches a row to a physical
bin by sight) and the index card's colour band. Both read THIS mapping, so the swatch on the
phone and the band on the card can never disagree.

Rules, in the same spirit as the size ladder:

- The raw text is never modified; this is a derived read over it.
- Unknown text resolves to None and the consumer falls back to a neutral swatch — a wrong colour
  sends someone to the wrong bin with confidence, no colour just sends their eyes to the code.
- Hues are the muted "bin plastic" register rather than saturated primaries: these sit beside
  the theme's channel colours (rose attention, safety yellow) and must never compete with them.
"""

from __future__ import annotations

import re

# Muted, dark-glyph-friendly fills. White text clears 4.5:1 on every one of these except the
# deliberately light pair (clear, white, yellow, tan) which the client inks instead — the glyph
# component owns that choice; this module only answers "what colour is the plastic".
_NAMED: dict[str, str] = {
    "grey": "#4A5462",
    "gray": "#4A5462",
    "charcoal": "#333A45",
    "black": "#2A2F38",
    "clear": "#8C97A6",
    "white": "#AEB6C2",
    "red": "#7A2E35",
    "green": "#2A5240",
    "blue": "#2E4A66",
    "navy": "#24384D",
    "yellow": "#8A6D00",
    "orange": "#8A4B24",
    "purple": "#4A3A6B",
    "violet": "#4A3A6B",
    "pink": "#7A3A52",
    "brown": "#5C452F",
    "tan": "#8A7354",
    "beige": "#8A7354",
    "teal": "#2A5252",
}

_HEX = re.compile(r"^#?([0-9a-fA-F]{6})$")


def color_hex(raw: str | None) -> str | None:
    """Resolve a free-text bin colour to a #RRGGBB hex, or None when it cannot say.

    Accepts a bare colour word (case-insensitive), a phrase whose FIRST known colour word wins
    ("dark green lid" -> green — first match, not best match, so the rule stays predictable),
    or a literal hex the user typed. Anything else is None, never a guess.
    """
    if raw is None:
        return None
    text = raw.strip()
    if not text:
        return None
    m = _HEX.match(text)
    if m:
        return f"#{m.group(1).upper()}"
    for word in re.split(r"[^a-zA-Z]+", text.lower()):
        if word in _NAMED:
            return _NAMED[word]
    return None
