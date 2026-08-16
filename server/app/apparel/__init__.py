"""Controlled vocabularies + normalizers for apparel attributes — pure, table-tested.

Copied from Crate (CLAUDE.md §5) and then deliberately trimmed. Two differences matter:

* **`completeness.py` is not here.** Crate scores an item against what eBay needs to accept a
  listing. Tote never lists anything, so importing that module would mean carrying a model of a
  marketplace this app has no relationship with.
* **`SIZE_TYPES` is a different axis.** Crate's is an eBay merchandising axis
  (regular/petite/plus/big_tall/juniors/maternity). Tote's question is "who in this house does
  this fit", so its axis is the age band — see `attributes.py`. Copying Crate's straight across
  would have looked correct and quietly answered a different question.

What did carry over unchanged is the write-path asymmetry these normalizers encode: **vision
output degrades (an unrecognised value becomes null) while a hand `PATCH` of the same field
rejects with a 422.** A model that half-read a tag should cost a null; a person who typed
something is making a claim.
"""

from app.apparel.attributes import (
    DEPARTMENTS,
    FITS,
    MEASUREMENT_KEYS,
    PHOTO_ROLES,
    SEASONS,
    SIZE_TYPES,
    SLEEVE_LENGTHS,
    normalize_enum,
    normalize_measurements,
    photo_role_rank,
)

__all__ = [
    "DEPARTMENTS",
    "FITS",
    "MEASUREMENT_KEYS",
    "PHOTO_ROLES",
    "SEASONS",
    "SIZE_TYPES",
    "SLEEVE_LENGTHS",
    "normalize_enum",
    "normalize_measurements",
    "photo_role_rank",
]
