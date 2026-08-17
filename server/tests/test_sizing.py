"""The size ladder, table-driven.

Two properties are being defended, and they pull in opposite directions:

1. **Within a system the ordering is exact** — 6 < 6X < 7, every time. A naive integer parse
   reads "6X" as 6 or throws, and a 6X coat filed as a 6 is a coat someone pulls out and finds
   does not fit.
2. **Nothing is ever invented.** Every case below that expects `None` is defending the trade the
   whole module exists for: a null sends a human to the bin, a wrong ordinal sends them to the
   wrong bin twice.

The second set is the one that matters more, so it is the longer table.
"""

import pytest

from app.apparel import SIZE_TYPES, normalize_enum, normalize_measurements, photo_role_rank
from app.sizing import (
    SYSTEM_ADULT_ALPHA,
    SYSTEM_INFANT_MONTHS,
    SYSTEM_MENS_WAIST,
    SYSTEM_SHOE_US_ADULT,
    SYSTEM_SHOE_US_CHILD,
    SYSTEM_TODDLER,
    SYSTEM_WOMENS_NUMERIC,
    SYSTEM_YOUTH_ALPHA,
    SYSTEM_YOUTH_NUMERIC,
    comparable,
    exact,
    next_size_up,
    parse_size,
    sizes_in_system,
    within_tolerance,
)

# ── What parses, and to what ───────────────────────────────────────────────────────────────

PARSES = [
    # raw, department, expected system
    ("NB", None, SYSTEM_INFANT_MONTHS),
    ("Newborn", None, SYSTEM_INFANT_MONTHS),
    ("0-3M", None, SYSTEM_INFANT_MONTHS),
    ("3-6 months", None, SYSTEM_INFANT_MONTHS),
    ("6-9m", None, SYSTEM_INFANT_MONTHS),
    ("9-12M", None, SYSTEM_INFANT_MONTHS),
    ("12M", None, SYSTEM_INFANT_MONTHS),
    ("18 mo", None, SYSTEM_INFANT_MONTHS),
    ("24M", None, SYSTEM_INFANT_MONTHS),
    # Bare month points. These were missing while 12M/18M/24M were present, on the reasoning that
    # infant clothing is sold in ranges — found in production with four garments typed "6m"
    # parsing to nothing, so `fits` could not see them.
    ("3m", None, SYSTEM_INFANT_MONTHS),
    ("6m", None, SYSTEM_INFANT_MONTHS),
    ("9M", None, SYSTEM_INFANT_MONTHS),
    ("6 months", None, SYSTEM_INFANT_MONTHS),
    ("15M", None, SYSTEM_INFANT_MONTHS),
    ("36 mo", None, SYSTEM_INFANT_MONTHS),
    ("2T", None, SYSTEM_TODDLER),
    ("3t", None, SYSTEM_TODDLER),
    ("4T", None, SYSTEM_TODDLER),
    (" 5T ", None, SYSTEM_TODDLER),
    # 6X is unambiguous even with no department: no other system uses that marker.
    ("6X", None, SYSTEM_YOUTH_NUMERIC),
    ("6x", None, SYSTEM_YOUTH_NUMERIC),
    ("8", "girls", SYSTEM_YOUTH_NUMERIC),
    ("10", "boys", SYSTEM_YOUTH_NUMERIC),
    ("Youth M", None, SYSTEM_YOUTH_ALPHA),
    ("boys L", None, SYSTEM_YOUTH_ALPHA),
    ("M", "girls", SYSTEM_YOUTH_ALPHA),
    ("XS", None, SYSTEM_ADULT_ALPHA),
    ("Small", None, SYSTEM_ADULT_ALPHA),
    ("Medium", None, SYSTEM_ADULT_ALPHA),
    ("XXL", None, SYSTEM_ADULT_ALPHA),
    ("2XL", None, SYSTEM_ADULT_ALPHA),
    ("3XL", None, SYSTEM_ADULT_ALPHA),
    ("adult L", None, SYSTEM_ADULT_ALPHA),
    ("W8", None, SYSTEM_WOMENS_NUMERIC),
    ("Women's 6", None, SYSTEM_WOMENS_NUMERIC),
    ("misses 12", None, SYSTEM_WOMENS_NUMERIC),
    ("8", "womens", SYSTEM_WOMENS_NUMERIC),
    ("32x30", None, SYSTEM_MENS_WAIST),
    ("34X32", None, SYSTEM_MENS_WAIST),
    ("W36 L34", None, SYSTEM_MENS_WAIST),
    ("shoe 9", None, SYSTEM_SHOE_US_ADULT),
    ("shoe 10.5", None, SYSTEM_SHOE_US_ADULT),
    ("shoe 8", "girls", SYSTEM_SHOE_US_CHILD),
]


@pytest.mark.parametrize("raw,dept,system", PARSES)
def test_parses_to_expected_system(raw, dept, system):
    reading = parse_size(raw, department=dept)
    assert reading is not None, f"{raw!r} should parse"
    assert reading.system == system
    # size_raw is sacred: whatever came in comes back untouched, including whitespace.
    assert reading.raw == raw


# ── What must NOT parse ────────────────────────────────────────────────────────────────────

REFUSES = [
    # A bare number is genuinely ambiguous — youth 8 and women's 8 are different garments for
    # different people, and there is nothing in the string to tell them apart.
    ("8", None, "a bare number with no department"),
    ("10", None, "a bare number with no department"),
    ("8", "unisex", "unisex does not disambiguate a bare number"),
    ("8", "mens", "men's tops are not sized on a bare numeric run"),
    # A tag that hedges between two sizes has not told us one.
    ("M/L", None, "a two-size tag"),
    ("S-M", None, "a two-size tag"),
    ("XS S M L XL", None, "a full size run"),
    # Not sizes at all.
    ("", None, "empty"),
    ("   ", None, "whitespace"),
    (None, None, "None"),
    ("Heather Grey", None, "a colour"),
    ("100% cotton", None, "a fibre content"),
    ("Made in Vietnam", None, "an origin"),
    ("Hanes", None, "a brand"),
    # Out of range: a real tag does not say these, so a match would be a misread.
    ("99", "girls", "a youth size that does not exist"),
    ("7", "womens", "women's numeric runs even"),
    ("32x99", None, "an implausible inseam still needs a plausible waist"),
    ("99x30", None, "an implausible waist"),
    # A bare number is not a shoe size. Guessing one files a sweater by foot length.
    ("9", None, "a bare number is not a shoe"),
    ("9", "womens", "an odd bare number under womens is not a women's size"),
]


@pytest.mark.parametrize("raw,dept,why", REFUSES)
def test_refuses_to_guess(raw, dept, why):
    assert parse_size(raw, department=dept) is None, f"{raw!r} must not parse: {why}"


# ── Ordering ───────────────────────────────────────────────────────────────────────────────


def test_6x_sorts_between_6_and_7():
    """The case a naive integer parse gets wrong, which is why this is a table."""
    six = parse_size("6", department="girls")
    six_x = parse_size("6X")
    seven = parse_size("7", department="girls")
    assert six.ordinal < six_x.ordinal < seven.ordinal


@pytest.mark.parametrize(
    "system",
    [
        SYSTEM_INFANT_MONTHS,
        SYSTEM_TODDLER,
        SYSTEM_YOUTH_NUMERIC,
        SYSTEM_YOUTH_ALPHA,
        SYSTEM_ADULT_ALPHA,
    ],
)
def test_every_rung_in_a_system_is_strictly_ordered(system):
    rungs = sizes_in_system(system)
    assert rungs, f"{system} has no rungs"
    ordinals = [ordinal for _, ordinal in rungs]
    assert ordinals == sorted(ordinals)
    assert len(set(ordinals)) == len(ordinals), f"{system} has two rungs at the same ordinal"


def test_the_childrens_ladder_is_monotonic_across_systems():
    """The point of one shared axis: a query can cross 4T -> youth 5 the way a parent does."""
    ladder = [
        parse_size("NB"),
        parse_size("6-9M"),
        parse_size("18M"),
        parse_size("2T"),
        parse_size("4T"),
        parse_size("5T"),
        parse_size("6X"),
        parse_size("8", department="boys"),
        parse_size("14", department="boys"),
    ]
    ordinals = [r.ordinal for r in ladder]
    assert ordinals == sorted(ordinals), ordinals


def test_children_sit_below_adults():
    assert parse_size("16", department="boys").ordinal < parse_size("XS").ordinal


def test_womens_numeric_lines_up_with_adult_alpha():
    """W4~S, W8~M, W12~L is the standard US mapping, and the tables are built to produce it."""
    assert parse_size("W4").ordinal == parse_size("S").ordinal
    assert parse_size("W8").ordinal == parse_size("M").ordinal
    assert parse_size("W12").ordinal == parse_size("L").ordinal


def test_mens_waist_indexes_the_waist_not_the_inseam():
    """A 32x30 and a 32x34 fit the same waist and belong beside each other in a bin.

    The inseam is not lost — it is in size_raw, which is why that column is kept verbatim.
    """
    assert parse_size("32x30").ordinal == parse_size("32x34").ordinal
    assert parse_size("32x30").ordinal < parse_size("36x30").ordinal


def test_shoes_do_not_collide_with_body_sizes():
    """A shoe size is not a body size; letting 'youth 8' and 'kids shoe 8' share an ordinal
    would make any mixed sort quietly nonsense."""
    youth_8 = parse_size("8", department="boys")
    shoe_8 = parse_size("shoe 8", department="girls")
    assert youth_8.ordinal != shoe_8.ordinal
    assert not comparable(youth_8.system, shoe_8.system)


# ── Comparability ──────────────────────────────────────────────────────────────────────────


def test_same_system_is_comparable_and_exact():
    assert comparable(SYSTEM_TODDLER, SYSTEM_TODDLER)
    assert exact(SYSTEM_TODDLER, SYSTEM_TODDLER)


def test_within_the_childrens_lineage_is_comparable_but_not_exact():
    assert comparable(SYSTEM_TODDLER, SYSTEM_YOUTH_NUMERIC)
    # 4T and youth 4 are not the same garment, and the app must never assert they are.
    assert not exact(SYSTEM_TODDLER, SYSTEM_YOUTH_NUMERIC)


def test_mens_and_womens_are_not_comparable_to_each_other():
    """The comparison that would produce a genuinely absurd answer."""
    assert not comparable(SYSTEM_MENS_WAIST, SYSTEM_WOMENS_NUMERIC)


def test_comparability_is_deliberately_not_transitive():
    """A property of clothing, not a bug: both map to adult alpha, neither maps to the other."""
    assert comparable(SYSTEM_WOMENS_NUMERIC, SYSTEM_ADULT_ALPHA)
    assert comparable(SYSTEM_MENS_WAIST, SYSTEM_ADULT_ALPHA)
    assert not comparable(SYSTEM_WOMENS_NUMERIC, SYSTEM_MENS_WAIST)


def test_children_are_not_comparable_to_adult_systems():
    assert not comparable(SYSTEM_TODDLER, SYSTEM_ADULT_ALPHA)
    assert not comparable(SYSTEM_YOUTH_NUMERIC, SYSTEM_WOMENS_NUMERIC)


def test_an_unparsed_size_is_comparable_to_nothing():
    assert not comparable(None, SYSTEM_TODDLER)
    assert not comparable(None, None)


# ── next_size_up ───────────────────────────────────────────────────────────────────────────


def test_next_size_up_stays_inside_its_system():
    assert next_size_up(parse_size("2T")).raw == "3T"
    assert next_size_up(parse_size("6X")).raw == "7"


def test_next_size_up_returns_none_at_the_top_rather_than_crossing():
    """5T is the top of toddler. The tempting answer is youth 5, and it is a different cut for
    a taller child — crossing is the caller's decision to make explicitly."""
    assert next_size_up(parse_size("5T")) is None
    assert next_size_up(parse_size("3XL")) is None


# ── within_tolerance ───────────────────────────────────────────────────────────────────────


def test_within_tolerance_matches_a_neighbouring_size():
    a = parse_size("4T")
    b = parse_size("5T")
    assert within_tolerance(a.system, a.ordinal, b.system, b.ordinal, tolerance=1.0) is True


def test_within_tolerance_rejects_a_distant_size():
    a = parse_size("2T")
    b = parse_size("12", department="boys")
    assert within_tolerance(a.system, a.ordinal, b.system, b.ordinal, tolerance=1.0) is False


def test_unknown_is_none_not_false():
    """None is NOT False, and a caller that conflates them hides every item whose tag could not
    be read — the opposite of what someone standing in front of fourteen bins needs."""
    a = parse_size("4T")
    assert within_tolerance(a.system, a.ordinal, None, None) is None
    # Not comparable is also "cannot say", not "does not fit".
    b = parse_size("32x30")
    assert within_tolerance(a.system, a.ordinal, b.system, b.ordinal) is None


# ── The apparel vocabularies ───────────────────────────────────────────────────────────────


def test_size_types_is_totes_age_band_not_crates_merchandising_axis():
    """Guarded because copying Crate's straight across looks right and answers a different
    question — CLAUDE.md §5 calls this out specifically."""
    assert SIZE_TYPES == ("infant", "toddler", "youth", "adult")
    assert "petite" not in SIZE_TYPES
    assert "maternity" not in SIZE_TYPES


@pytest.mark.parametrize(
    "value,expected",
    [
        ("Toddler", "toddler"),
        ("KIDS", "youth"),
        ("children", "youth"),
        ("Baby", "infant"),
        ("adults", "adult"),
        ("petite", None),  # Crate's vocabulary must not leak back in
        (None, None),
        ("", None),
    ],
)
def test_normalize_enum_on_size_types(value, expected):
    assert normalize_enum(value, SIZE_TYPES) == expected


@pytest.mark.parametrize(
    "value,expected",
    [
        ("Women's", "womens"),
        ("WOMEN’S", "womens"),
        ("Men's", "mens"),
        ("Girls", "girls"),
        ("Boy", "boys"),
        ("spacesuit", None),
    ],
)
def test_normalize_enum_handles_apostrophes(value, expected):
    """A label reading "WOMEN'S" is ordinary. The mechanical squash turned it into `women_s`,
    which matched nothing — and department is the field that disambiguates a bare numeric size,
    so losing it silently downgraded every women's tag to unparsed."""
    from app.apparel import DEPARTMENTS

    assert normalize_enum(value, DEPARTMENTS) == expected


def test_normalize_measurements_drops_the_implausible():
    out = normalize_measurements(
        {"chest": "20.5", "waist": 0, "inseam": 200, "nonsense": 5, "length": 28}
    )
    assert out == {"chest": 20.5, "length": 28.0}


def test_photo_role_rank_keeps_the_tag_last():
    """A care label is genuine size proof and nobody's idea of a cover photo."""
    assert photo_role_rank("front") < photo_role_rank("detail") < photo_role_rank("tag")
    # An unknown role sorts ahead of the tag, so pre-roles items keep their original order.
    assert photo_role_rank(None) < photo_role_rank("tag")
    assert photo_role_rank("something-new") == photo_role_rank(None)


def test_a_bare_month_sits_between_the_ranges_either_side_of_it():
    """The ranges were already the midpoints of points that did not exist.

    3-6m is 0.375 because it sits between 3m (0.25) and 6m (0.5). Adding the bare points had to
    preserve that or an item typed "6m" would sort on the wrong side of one typed "3-6m", which
    is worse than not parsing at all — a wrong ordinal sends someone to the wrong bin twice.
    """
    order = ["NB", "0-3m", "3m", "3-6m", "6m", "6-9m", "9m", "9-12m", "12m", "15m", "18m", "24m"]
    ordinals = [parse_size(raw).ordinal for raw in order]
    assert ordinals == sorted(ordinals), ordinals
    assert len(set(ordinals)) == len(ordinals), "every rung is distinct"


def test_36_months_lands_where_3T_does():
    """One shared axis is the whole point: the same body gets the same ordinal whichever system
    the tag happens to use."""
    assert parse_size("36m").ordinal == parse_size("3T").ordinal
    # But the SYSTEM is still reported honestly — they are not the same garment vocabulary.
    assert parse_size("36m").system == SYSTEM_INFANT_MONTHS
    assert parse_size("3T").system == SYSTEM_TODDLER
