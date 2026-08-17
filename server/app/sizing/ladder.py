"""The size ladder: a derived index over what a garment tag actually says.

Pure, no I/O, exhaustively table-driven. This is the hardest logic in the app and the rule that
governs all of it is one sentence:

    **`size_raw` is sacred.** Whatever is printed on the tag is stored verbatim, forever,
    unmodified. Everything here is a *derived index* over it, and a derived index that is wrong
    must never be able to destroy the reading.

So every function below is allowed to answer "I don't know", and answering "I don't know" is the
**designed** outcome rather than a failure. A null `size_ordinal` sends a human to the bin; a
wrong one sends them to the wrong bin twice, and the second trip is the one that makes someone
stop trusting the catalog.

## The ordinal axis

`ordinal` is a float on one shared, approximate body-size axis so a query can ask "the next size
up" across a boundary that a human would cross naturally — 4T to youth 5.

For children the axis **is approximate age in years**, which is what the systems themselves are
built around, so the numbers stay legible: `3-6M` is 0.375, `2T` is 2.0, youth `10` is 10.0. Adult
systems continue above 16. Shoes sit on their own band entirely (see `SHOE_BAND_OFFSET`), because
a shoe size is not a body size and letting "youth 8" and "kids' shoe 8" collide on one axis would
make a mixed sort quietly nonsense.

## Comparability is narrower than the axis

Within a system, ordering is **exact** and can be trusted: 6 < 6X < 7, always.

Across systems it is an approximation, and some cross-system comparisons are not merely
approximate but meaningless. A men's 32 waist and a women's 8 are not two points on one scale,
and an app that said "these jeans are the next size up from your daughter's 4T" would be worse
than one that said nothing. So systems carry a **lineage**, and [`comparable`] is what any caller
must ask before comparing two ordinals.

Note that comparability is deliberately **not transitive**: women's numeric and adult alpha are
comparable (a women's 8 really is about a medium), and adult alpha and men's waist are comparable,
but women's numeric and men's waist are not. That is a property of clothing, not a bug here.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# ── Systems ────────────────────────────────────────────────────────────────────────────────

SYSTEM_INFANT_MONTHS = "infant_months"
SYSTEM_TODDLER = "toddler"
SYSTEM_YOUTH_NUMERIC = "youth_numeric"
SYSTEM_YOUTH_ALPHA = "youth_alpha"
SYSTEM_ADULT_ALPHA = "adult_alpha"
SYSTEM_WOMENS_NUMERIC = "womens_numeric"
SYSTEM_MENS_WAIST = "mens_waist"
SYSTEM_SHOE_US_CHILD = "shoe_us_child"
SYSTEM_SHOE_US_ADULT = "shoe_us_adult"

SIZE_SYSTEMS: tuple[str, ...] = (
    SYSTEM_INFANT_MONTHS,
    SYSTEM_TODDLER,
    SYSTEM_YOUTH_NUMERIC,
    SYSTEM_YOUTH_ALPHA,
    SYSTEM_ADULT_ALPHA,
    SYSTEM_WOMENS_NUMERIC,
    SYSTEM_MENS_WAIST,
    SYSTEM_SHOE_US_CHILD,
    SYSTEM_SHOE_US_ADULT,
)

# ── Lineages ───────────────────────────────────────────────────────────────────────────────

LINEAGE_CHILDRENS = "childrens"
LINEAGE_ADULT_TOPS = "adult_tops"
LINEAGE_WOMENS = "womens"
LINEAGE_MENS = "mens"
LINEAGE_SHOES_CHILD = "shoes_child"
LINEAGE_SHOES_ADULT = "shoes_adult"

_LINEAGE_OF: dict[str, str] = {
    SYSTEM_INFANT_MONTHS: LINEAGE_CHILDRENS,
    SYSTEM_TODDLER: LINEAGE_CHILDRENS,
    SYSTEM_YOUTH_NUMERIC: LINEAGE_CHILDRENS,
    SYSTEM_YOUTH_ALPHA: LINEAGE_CHILDRENS,
    SYSTEM_ADULT_ALPHA: LINEAGE_ADULT_TOPS,
    SYSTEM_WOMENS_NUMERIC: LINEAGE_WOMENS,
    SYSTEM_MENS_WAIST: LINEAGE_MENS,
    SYSTEM_SHOE_US_CHILD: LINEAGE_SHOES_CHILD,
    SYSTEM_SHOE_US_ADULT: LINEAGE_SHOES_ADULT,
}

# Pairs of DIFFERENT lineages whose ordinals may still be compared. Everything not listed here
# is comparable only with itself.
_COMPARABLE_PAIRS: frozenset[frozenset[str]] = frozenset(
    {
        # A women's 8 really is about a medium; the mapping below is built to make that true.
        frozenset({LINEAGE_ADULT_TOPS, LINEAGE_WOMENS}),
        # Likewise a 32 waist and a small/medium.
        frozenset({LINEAGE_ADULT_TOPS, LINEAGE_MENS}),
    }
)

# ── The tables ─────────────────────────────────────────────────────────────────────────────
# Children's ordinals ARE approximate age in years. Written out rather than computed so the
# 6X row can exist at all, and so every value is reviewable against a tag.

# The axis is years of approximate body size, so 12m is 1.0 and the RANGES are the midpoints of
# the bare points either side of them: 3-6m sits at 0.375 between 3m (0.25) and 6m (0.5).
#
# The bare points used to be missing for 3, 6 and 9 months, on the reasoning that infant clothing
# is sold in ranges. Real tags and real people do not agree: found in production with four garments
# typed "6m" and parsing to nothing, which meant `fits` could not see them at all — the silent
# failure the whole ladder exists to prevent. 12m/18m/24m were already here, so the table was
# inconsistent with itself as well as with the world.
_INFANT_MONTHS: dict[str, float] = {
    "nb": 0.0,
    "0-3m": 0.125,
    "3m": 0.25,
    "3-6m": 0.375,
    "6m": 0.5,
    "6-9m": 0.625,
    "9m": 0.75,
    "9-12m": 0.875,
    "12m": 1.0,
    "15m": 1.25,
    "18m": 1.5,
    "24m": 2.0,
    # Written on some tags instead of 3T, and it lands on the same ordinal 3T does — which is
    # the point of one shared axis.
    "36m": 3.0,
}

_TODDLER: dict[str, float] = {"2t": 2.0, "3t": 3.0, "4t": 4.0, "5t": 5.0}

# 6X sorts BETWEEN 6 and 7, which is the whole reason this is a table and not `int(raw)`.
# A naive integer parse reads "6X" as 6 (or throws), and a 6X coat filed as a 6 is a coat
# someone pulls out and finds does not fit.
_YOUTH_NUMERIC: dict[str, float] = {
    "4": 4.0,
    "5": 5.0,
    "6": 6.0,
    "6x": 6.5,
    "7": 7.0,
    "8": 8.0,
    "10": 10.0,
    "12": 12.0,
    "14": 14.0,
    "16": 16.0,
}

_YOUTH_ALPHA: dict[str, float] = {
    "xs": 5.0,
    "s": 6.5,
    "m": 8.0,
    "l": 11.0,
    "xl": 14.0,
}

# Adults continue above the 0-16 children's band, in steps of 2 so the women's numeric run
# (below, in steps of 1) interleaves with it the way the real garments do.
_ADULT_ALPHA: dict[str, float] = {
    "xxs": 17.0,
    "xs": 18.0,
    "s": 20.0,
    "m": 22.0,
    "l": 24.0,
    "xl": 26.0,
    "xxl": 28.0,
    "3xl": 30.0,
}

# women's n -> 18 + n/2, so 4≈S(20), 8≈M(22), 12≈L(24). That is the standard US mapping, and
# making the arithmetic produce it is why the adult alpha steps are 2.
_WOMENS_MIN, _WOMENS_MAX = 0, 28

# men's waist in inches -> the same adult band. 32≈S/M(20), 36≈M/L(22), 40≈L/XL(24).
_MENS_WAIST_MIN, _MENS_WAIST_MAX = 26, 60
# The inseam is not indexed, but it IS validated. A tag reading "32x99" has been misread
# somewhere, and there is no reason to trust the half of a misreading that happens to look
# plausible — under-reading is this module's designed bias.
_MENS_INSEAM_MIN, _MENS_INSEAM_MAX = 20, 44

# Shoes live on their own band so a mixed sort never puts "kids' shoe 8" next to "youth 8"
# as though they measured the same thing.
SHOE_BAND_OFFSET = 100.0
_SHOE_CHILD_MIN, _SHOE_CHILD_MAX = 0.0, 13.5
_SHOE_ADULT_MIN, _SHOE_ADULT_MAX = 3.0, 20.0
_SHOE_ADULT_BAND = SHOE_BAND_OFFSET * 2

_ALPHA_ALIASES = {
    "xsmall": "xs",
    "x_small": "xs",
    "extrasmall": "xs",
    "small": "s",
    "medium": "m",
    "med": "m",
    "large": "l",
    "xlarge": "xl",
    "x_large": "xl",
    "extralarge": "xl",
    "xxlarge": "xxl",
    "2xl": "xxl",
    "xxxl": "3xl",
    "3xlarge": "3xl",
    "xxxsmall": "xxs",
    "2xs": "xxs",
}

_MONTH_ALIASES = {
    "newborn": "nb",
    "new_born": "nb",
    "preemie": "nb",
    "0_3_months": "0-3m",
    "3_6_months": "3-6m",
    "6_9_months": "6-9m",
    "9_12_months": "9-12m",
    "3_months": "3m",
    "6_months": "6m",
    "9_months": "9m",
    "12_months": "12m",
    "15_months": "15m",
    "18_months": "18m",
    "24_months": "24m",
    "36_months": "36m",
}

# Departments that make a bare number unambiguous. See `parse_size`.
_YOUTH_DEPARTMENTS = ("boys", "girls")
_WOMENS_DEPARTMENTS = ("womens",)


@dataclass(frozen=True)
class SizeReading:
    """A parsed size. `raw` is always the caller's original string, untouched."""

    raw: str
    system: str
    ordinal: float

    @property
    def lineage(self) -> str:
        return _LINEAGE_OF[self.system]


def lineage_of(system: str | None) -> str | None:
    return _LINEAGE_OF.get(system) if system else None


def comparable(system_a: str | None, system_b: str | None) -> bool:
    """May two ordinals be compared at all?

    Ask this before every comparison. Two sizes in the same system are exactly ordered; two in
    comparable lineages are approximately ordered and the UI must say so; anything else is not
    ordered in any useful sense, and presenting it as though it were is how this app would tell
    someone that men's jeans are the next size up from a 4T.
    """
    a, b = lineage_of(system_a), lineage_of(system_b)
    if a is None or b is None:
        return False
    if a == b:
        return True
    return frozenset({a, b}) in _COMPARABLE_PAIRS


def exact(system_a: str | None, system_b: str | None) -> bool:
    """True when a comparison is exact rather than approximate — i.e. the same system.

    The distinction the UI has to surface: within `youth_numeric`, 6 < 6X < 7 is a fact. Between
    `toddler` 4T and `youth_numeric` 4 it is an estimate, and they are not the same garment.
    """
    return bool(system_a) and system_a == system_b


def _clean(raw: str) -> str:
    """Casefold and squash punctuation, preserving the hyphen that month ranges need."""
    text = str(raw).strip().casefold()
    text = text.replace("'", "").replace("’", "")
    text = re.sub(r"\s*-\s*", "-", text)
    text = re.sub(r"[^a-z0-9\-./x]+", "_", text)
    return text.strip("_")


def _norm_months(text: str) -> str:
    """`18 mo`, `18months`, `3-6 Months` -> the table's `18m` / `3-6m`.

    No LEADING word boundary on the unit, deliberately. After the underscores are squashed the
    string is `18mo`, and `\bmos?\b` never matches there because a digit and a letter are both
    word characters — which silently dropped every tag written `18 mo`, a common one.
    """
    text = text.replace("_", "")
    return re.sub(r"mo(?:nth)?s?\b", "m", text)


def parse_size(raw: str | None, department: str | None = None) -> SizeReading | None:
    """Map a raw tag string onto the ladder, or return None.

    `department` (from `app.apparel`: mens/womens/unisex/boys/girls) is used only to disambiguate
    a **bare number**, and only where it genuinely disambiguates. That is evidence, not a guess.

    ## Why a bare number alone returns None

    A tag reading `8` is a youth 8 or a women's 8, and they are different garments for different
    people. There is no way to tell from the string, so with no department this returns None and
    `size_raw` keeps the `8` for a human to read. This is the module's designed trade, restated
    because it is the rule most likely to be "improved" away: under-reading is the point. A null
    sends someone to the bin; a wrong ordinal sends them to the wrong bin twice.

    Ambiguity that a marker resolves is parsed happily: `W8`, `Women's 8`, `Girls 8`.
    """
    if raw is None:
        return None
    original = str(raw)
    text = _clean(original)
    if not text:
        return None

    dept = (department or "").strip().casefold() or None

    for parser in (
        _parse_infant,
        _parse_toddler,
        _parse_shoe,
        _parse_mens_waist,
        _parse_marked_womens,
        _parse_alpha,
        _parse_bare_number,
    ):
        reading = parser(original, text, dept)
        if reading is not None:
            return reading
    return None


# ── Individual parsers ─────────────────────────────────────────────────────────────────────
# Each returns a SizeReading or None. Order matters only where two could match; the sequence in
# parse_size puts the unambiguous, heavily-marked forms first.


def _parse_infant(original: str, text: str, dept: str | None) -> SizeReading | None:
    key = _MONTH_ALIASES.get(text.replace("-", "_"), _norm_months(text))
    if key in _INFANT_MONTHS:
        return SizeReading(original, SYSTEM_INFANT_MONTHS, _INFANT_MONTHS[key])
    return None


def _parse_toddler(original: str, text: str, dept: str | None) -> SizeReading | None:
    key = text.replace("_", "")
    if key in _TODDLER:
        return SizeReading(original, SYSTEM_TODDLER, _TODDLER[key])
    return None


def _parse_shoe(original: str, text: str, dept: str | None) -> SizeReading | None:
    """Only with an explicit shoe marker.

    A bare `9` on a garment tag is not a shoe size, and guessing one would file a sweater by
    foot length. The tag has to say so.
    """
    match = re.fullmatch(
        r"(?:us_?)?(?:shoe_?|sz_?)?(\d{1,2}(?:\.5)?)_?(c|y|k|kids|toddler|w|m|mens|womens)?", text
    )
    marked = re.search(r"shoe|footwear", text) is not None
    if not marked:
        return None
    if match is None:
        match = re.search(r"(\d{1,2}(?:\.5)?)", text)
        if match is None:
            return None
        number = float(match.group(1))
        suffix = None
    else:
        number = float(match.group(1))
        suffix = match.group(2)

    childish = suffix in ("c", "y", "k", "kids", "toddler") or dept in _YOUTH_DEPARTMENTS
    if childish:
        if _SHOE_CHILD_MIN <= number <= _SHOE_CHILD_MAX:
            return SizeReading(original, SYSTEM_SHOE_US_CHILD, SHOE_BAND_OFFSET + number)
        return None
    if _SHOE_ADULT_MIN <= number <= _SHOE_ADULT_MAX:
        return SizeReading(original, SYSTEM_SHOE_US_ADULT, _SHOE_ADULT_BAND + number)
    return None


def _parse_mens_waist(original: str, text: str, dept: str | None) -> SizeReading | None:
    """`32x30`, `w32_l30`, `32/30` — waist and inseam. The ordinal indexes the WAIST only.

    Inseam is leg length, not body size: a 32x30 and a 32x34 fit the same waist and belong beside
    each other in a bin. The inseam is not lost — it is in `size_raw`, which is the whole point of
    keeping that column verbatim.
    """
    match = re.fullmatch(r"w?(\d{2})[x/_]l?(\d{2})", text)
    if match is None:
        return None
    waist = int(match.group(1))
    inseam = int(match.group(2))
    if not (_MENS_WAIST_MIN <= waist <= _MENS_WAIST_MAX):
        return None
    if not (_MENS_INSEAM_MIN <= inseam <= _MENS_INSEAM_MAX):
        return None
    return SizeReading(original, SYSTEM_MENS_WAIST, _mens_ordinal(waist))


def _mens_ordinal(waist: int) -> float:
    # 32 -> 20 (about a small/medium), 36 -> 22, 40 -> 24. Same band as adult alpha.
    return 20.0 + (waist - 32) / 2.0


def _parse_marked_womens(original: str, text: str, dept: str | None) -> SizeReading | None:
    """A number that says it is a women's size: `w8`, `womens_8`, `misses_8`."""
    match = re.fullmatch(r"(?:w|womens|misses|ladies)_?(\d{1,2})", text)
    if match is None:
        return None
    number = int(match.group(1))
    return _womens_reading(original, number)


def _womens_reading(original: str, number: int) -> SizeReading | None:
    if not (_WOMENS_MIN <= number <= _WOMENS_MAX) or number % 2 != 0:
        # Women's numeric runs even. An odd number here is a junior's size or a misread, and
        # either way it is not this system.
        return None
    return SizeReading(original, SYSTEM_WOMENS_NUMERIC, 18.0 + number / 2.0)


def _parse_alpha(original: str, text: str, dept: str | None) -> SizeReading | None:
    """XS/S/M/L/XL and friends, youth or adult.

    A youth marker (`youth_m`, `boys_l`, or a boys/girls department) picks the youth table;
    otherwise adult. `M/L` and other multi-size tags return None: a tag that hedges between two
    sizes has not told us one.
    """
    body = text
    youthish = dept in _YOUTH_DEPARTMENTS
    marker = re.match(r"^(youth|jr|junior|boys|girls|kids|big_kids?)_(.+)$", body)
    if marker:
        youthish = True
        body = marker.group(2)
    else:
        adult_marker = re.match(r"^(adult|mens|womens|unisex)_(.+)$", body)
        if adult_marker:
            youthish = False
            body = adult_marker.group(2)

    key = body.replace("_", "").replace("-", "")
    key = _ALPHA_ALIASES.get(key, key)
    if key not in _ADULT_ALPHA and key not in _YOUTH_ALPHA:
        return None

    if youthish:
        if key in _YOUTH_ALPHA:
            return SizeReading(original, SYSTEM_YOUTH_ALPHA, _YOUTH_ALPHA[key])
        # A youth garment marked XXL has no row in the youth table. Rather than inventing one,
        # fall through to adult — the ordinal is approximate either way and the raw is intact.
        return SizeReading(original, SYSTEM_ADULT_ALPHA, _ADULT_ALPHA[key])
    if key in _ADULT_ALPHA:
        return SizeReading(original, SYSTEM_ADULT_ALPHA, _ADULT_ALPHA[key])
    return None


def _parse_bare_number(original: str, text: str, dept: str | None) -> SizeReading | None:
    """A number with no system marker. Resolvable ONLY when the department says which ladder.

    See `parse_size`'s docstring: with no department this is genuinely ambiguous and returns
    None on purpose.
    """
    key = text.replace("_", "")
    if not re.fullmatch(r"\d{1,2}x?", key):
        return None

    if dept in _WOMENS_DEPARTMENTS:
        if key.endswith("x"):
            return None
        return _womens_reading(original, int(key))

    if dept in _YOUTH_DEPARTMENTS and key in _YOUTH_NUMERIC:
        return SizeReading(original, SYSTEM_YOUTH_NUMERIC, _YOUTH_NUMERIC[key])

    # `6X` is unambiguous even bare: no other system uses it, so the marker IS the system.
    if key == "6x":
        return SizeReading(original, SYSTEM_YOUTH_NUMERIC, _YOUTH_NUMERIC["6x"])

    return None


# ── Queries over the ladder ────────────────────────────────────────────────────────────────


def sizes_in_system(system: str) -> list[tuple[str, float]]:
    """Every rung of a system, ascending. The source of truth for a size picker."""
    table = {
        SYSTEM_INFANT_MONTHS: _INFANT_MONTHS,
        SYSTEM_TODDLER: _TODDLER,
        SYSTEM_YOUTH_NUMERIC: _YOUTH_NUMERIC,
        SYSTEM_YOUTH_ALPHA: _YOUTH_ALPHA,
        SYSTEM_ADULT_ALPHA: _ADULT_ALPHA,
    }.get(system)
    if table is None:
        return []
    return sorted(table.items(), key=lambda kv: kv[1])


def next_size_up(reading: SizeReading) -> SizeReading | None:
    """The next rung within the SAME system, or None at the top.

    Deliberately does not cross systems even though the axis would allow it. "The next size up
    from 5T" has an obvious answer inside toddler sizing (there isn't one — 5T is the top) and a
    misleading one across it (youth 5, which is a different cut for a taller child). Crossing is
    the caller's decision to make explicitly, with `comparable` and the approximate label.
    """
    rungs = sizes_in_system(reading.system)
    for label, ordinal in rungs:
        if ordinal > reading.ordinal:
            return SizeReading(label.upper() if label != "6x" else "6X", reading.system, ordinal)
    return None


def within_tolerance(
    a_system: str | None,
    a_ordinal: float | None,
    b_system: str | None,
    b_ordinal: float | None,
    tolerance: float = 1.0,
) -> bool | None:
    """Are two sizes close enough to be worth a trip to the attic?

    Returns None — meaning "cannot say" — when either side is unparsed or the systems are not
    comparable. **None is not False.** A caller that treats them the same will quietly hide every
    item whose tag could not be read, which is the opposite of what someone standing in front of
    fourteen bins needs.
    """
    if a_ordinal is None or b_ordinal is None:
        return None
    if not comparable(a_system, b_system):
        return None
    return abs(a_ordinal - b_ordinal) <= tolerance
