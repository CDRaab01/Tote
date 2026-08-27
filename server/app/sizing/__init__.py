"""Size parsing and comparison — pure, no I/O.

`size_raw` is sacred: whatever the tag said is stored verbatim. Everything exported here is a
*derived index* over it, and every function may answer "I don't know" rather than guess.
"""

from app.sizing.ladder import (
    SIZE_SYSTEMS,
    SYSTEM_ADULT_ALPHA,
    SYSTEM_INFANT_MONTHS,
    SYSTEM_MENS_WAIST,
    SYSTEM_SHOE_US_ADULT,
    SYSTEM_SHOE_US_CHILD,
    SYSTEM_TODDLER,
    SYSTEM_WOMENS_NUMERIC,
    SYSTEM_YOUTH_ALPHA,
    SYSTEM_YOUTH_NUMERIC,
    SizeReading,
    comparable,
    exact,
    lineage_of,
    next_size_up,
    next_sizes_up,
    parse_size,
    rung_band,
    sizes_in_system,
    within_tolerance,
)

__all__ = [
    "SIZE_SYSTEMS",
    "SYSTEM_ADULT_ALPHA",
    "SYSTEM_INFANT_MONTHS",
    "SYSTEM_MENS_WAIST",
    "SYSTEM_SHOE_US_ADULT",
    "SYSTEM_SHOE_US_CHILD",
    "SYSTEM_TODDLER",
    "SYSTEM_WOMENS_NUMERIC",
    "SYSTEM_YOUTH_ALPHA",
    "SYSTEM_YOUTH_NUMERIC",
    "SizeReading",
    "comparable",
    "exact",
    "lineage_of",
    "next_size_up",
    "next_sizes_up",
    "parse_size",
    "rung_band",
    "sizes_in_system",
    "within_tolerance",
]
