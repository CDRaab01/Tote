"""Controlled vocabularies + normalizers for apparel attributes.

Free-text fields (size, color, material, style) are deliberately NOT enumerated — real tags say
"Heather Grey", "60% cotton / 40% poly", "M/L". Constraining them would lose what a human read
off the garment, and for a garment now sealed in a bin in an attic that reading cannot be
re-derived. `size` in particular stays verbatim in `size_raw`; `app.sizing` builds a derived
index over it and is allowed to fail.
"""

import re

# Sized enums: these are the fields where a controlled value is genuinely knowable from the
# tag or the garment, and where free text would fragment the registry.
DEPARTMENTS = ("mens", "womens", "unisex", "boys", "girls")

# The AGE BAND, not a merchandising cut.
#
# Crate's SIZE_TYPES here is (regular, petite, plus, big_tall, juniors, maternity) — an eBay
# merchandising axis, and copying it across would have looked right while answering a question
# Tote never asks. Tote's question is "who in this house does this fit", so the axis that earns
# a column is the one the size ladder is built around. It is deliberately coarse: the precise
# answer lives in size_system/size_ordinal, and this is the one word that goes on a bin label.
SIZE_TYPES = ("infant", "toddler", "youth", "adult")
SLEEVE_LENGTHS = ("sleeveless", "short", "three_quarter", "long")
FITS = ("slim", "regular", "relaxed", "oversized")

# What a photo is a photo OF, set by the guided capture flow. Not an apparel *attribute*,
# but it lives here for the same reason the others do: it is a controlled vocabulary that
# needs normalize_enum, and the alternative is a second normalizer somewhere else.
#
# ORDER IS SEMANTIC — this tuple is the eBay listing order (see photo_role_rank), so the
# first entry is the gallery/hero image and the last is the least presentable. Reordering
# it changes what buyers see first. "tag" is deliberately last: a care label is genuine
# size proof worth including, but it is nobody's idea of a cover photo.
PHOTO_ROLES = ("front", "back", "detail", "tag")

# The listing order, with the slot for "no role known" written out explicitly rather than
# implied by arithmetic on PHOTO_ROLES. test_photo_roles.py asserts the two stay in sync,
# so adding a role without deciding where it appears in a listing fails the suite.
_LISTING_ORDER: tuple[str | None, ...] = ("front", "back", "detail", None, "tag")
_UNKNOWN_ROLE_RANK = _LISTING_ORDER.index(None)

# Tape-measure fields, inches, garment laid flat. Tops use chest/length/sleeve/shoulder;
# bottoms use waist/inseam/rise. One union keeps the JSON shape stable across garment types
# — an absent key just means "not measured", never "zero".
MEASUREMENT_KEYS = ("chest", "length", "sleeve", "shoulder", "waist", "inseam", "rise")

# Whether a garment is worth pulling out of the attic in a given month, which is one of the two
# reasons anyone opens a clothing bin. Nullable: most items genuinely are all-season and forcing
# a choice would fill the column with noise.
SEASONS = ("winter", "summer", "all")

# A garment measurement above this is a typo or a unit mix-up (cm entered as inches), not a
# real tape reading — 90" is longer than any wearable single garment dimension.
_MAX_MEASUREMENT_IN = 90.0


def photo_role_rank(role: str | None) -> int:
    """Sort key for presenting an item's photos, lowest first.

    The first photo is the one that represents the item everywhere it appears in a list, and
    without an ordering that is simply whichever photo happened to be taken first — so a
    tag-first shoot puts a close-up of a care label where the garment should be.

    Two deliberate choices:

    * A photo with **no role** sits ahead of "tag" but behind anything explicit. It is more
      likely a garment shot than a label, and this is what keeps the ordering invisible for
      items captured before roles existed: every one of their photos gets the same rank, so a
      stable sort leaves them in exactly their original order.
    * An **unrecognised** role is treated as unknown rather than raising. The column is
      nullable free-form text at the DB level, and a future client sending a role this version
      has never heard of must not be able to break reading an item.

    This is a presentation-time ordering only. It must never be written back to
    ItemPhoto.order: photo_store derives the on-disk filenames from that integer, so
    renumbering would orphan every file.
    """
    try:
        return _LISTING_ORDER.index(role)
    except ValueError:
        return _UNKNOWN_ROLE_RANK


def normalize_enum(value: object, allowed: tuple[str, ...]) -> str | None:
    """Casefold + snake-case a vocabulary value, or None when it isn't in `allowed`.

    Forgiving on shape ("Three-Quarter", "Mens", "All Season") because vision output and hand
    entry both drift; strict on membership, because an unrecognised value silently stored is a
    filter that quietly stops matching the thing you are looking for.
    """
    if value is None:
        return None
    text = str(value).strip().casefold()
    # Apostrophes are DELETED, not squashed to an underscore. A label reading "WOMEN'S" or
    # "Kid's" is ordinary, and the mechanical squash turned it into `women_s`, which matched
    # nothing and silently dropped the one field that disambiguates a bare numeric size. (Crate's
    # copy still does this; it never noticed because its labels are asked for a different axis.)
    text = text.replace("'", "").replace("’", "")
    text = re.sub(r"[^a-z0-9]+", "_", text).strip("_")
    if not text:
        return None
    if text in allowed:
        return text
    # Common tag phrasings that don't survive the mechanical squash above.
    aliases = {
        "men": "mens",
        "boy": "boys",
        "girl": "girls",
        "baby": "infant",
        "infants": "infant",
        "newborn": "infant",
        "toddlers": "toddler",
        "kids": "youth",
        "child": "youth",
        "children": "youth",
        "adults": "adult",
        "all_season": "all",
        "all_seasons": "all",
        "year_round": "all",
        "man": "mens",
        "male": "mens",
        "women": "womens",
        "woman": "womens",
        "female": "womens",
        "big_and_tall": "big_tall",
        "big_tall_": "big_tall",
        "3_4": "three_quarter",
        "three_quarters": "three_quarter",
        "quarter": "three_quarter",
        "short_sleeve": "short",
        "long_sleeve": "long",
        "loose": "relaxed",
        "standard": "regular",
    }
    resolved = aliases.get(text)
    return resolved if resolved in allowed else None


def normalize_measurements(value: object) -> dict | None:
    """Coerce a measurements payload to {key: float inches} over MEASUREMENT_KEYS.

    Unknown keys are dropped, non-numeric and out-of-range values are dropped, and an empty
    result is None — "no measurements" must read as absent, not as an empty dict that looks
    like someone already did the work.
    """
    if not isinstance(value, dict):
        return None
    out: dict[str, float] = {}
    for key in MEASUREMENT_KEYS:
        raw = value.get(key)
        if raw is None:
            continue
        try:
            number = float(str(raw).strip())
        except (TypeError, ValueError):
            continue
        if 0 < number <= _MAX_MEASUREMENT_IN:
            out[key] = round(number, 2)
    return out or None
