"""Should this item get a label pass? Pure, cheap, and deliberately generous.

The label call costs a second round trip to the vision model — measured at tens of seconds on
this host — so it is not run for every ratchet set and board game. But the gate is one-sided on
purpose:

* A **false positive** costs one wasted model call. The label pass returns `{}` for a photograph
  with no label in it, `parse_label` returns None, and nothing is written.
* A **false negative** loses the size of a garment that is now sealed in a bin in an attic, and
  the only way to recover it is to go and open the bin.

So the gate errs toward asking. It matches on the *category the model chose* first, because that
is the user's own vocabulary and the strongest signal available, and falls back to a word list
over the item name.
"""

import re

# The category names likely to be clothing in this household's vocabulary. Matched loosely
# (substring, casefolded) because categories are user-editable seeded rows, not an enum — someone
# renaming "Clothing" to "Kids clothes" must not silently switch the label pass off.
#
# "baby"/"infant" earn a place even though such a bin also holds a monitor and a bottle steriliser
# with no label between them. The asymmetry in the module note decides it: those cost one wasted
# call each, while a sleepsuit that misses its label pass costs a trip to the attic. Added when
# the "Baby" seed category shipped and matched NONE of the original hints — every baby garment
# filed under it would have skipped the size read unless its name happened to carry a listed word,
# which is the failure this gate exists to prevent.
_CLOTHING_CATEGORY_HINTS = (
    "cloth",
    "apparel",
    "garment",
    "wear",
    "shoe",
    "footwear",
    "uniform",
    "baby",
    "infant",
)

# Words that make an item worth asking a label about. Deliberately broad; see the module note.
_CLOTHING_WORDS = frozenset(
    [
        "shirt",
        "tshirt",
        "t-shirt",
        "tee",
        "blouse",
        "top",
        "tank",
        "sweater",
        "sweatshirt",
        "hoodie",
        "jumper",
        "cardigan",
        "jacket",
        "coat",
        "parka",
        "windbreaker",
        "vest",
        "fleece",
        "raincoat",
        "snowsuit",
        "pants",
        "trousers",
        "jeans",
        "leggings",
        "shorts",
        "skirt",
        "dress",
        "overalls",
        "romper",
        "onesie",
        "bodysuit",
        "pajamas",
        "pyjamas",
        "pjs",
        "sleeper",
        "sleepsuit",
        "sleepsack",
        "sleepingbag",
        "babygro",
        "babygrow",
        "swaddle",
        "bib",
        "jumpsuit",
        "playsuit",
        "dungarees",
        "leotard",
        "nightgown",
        "robe",
        "swimsuit",
        "trunks",
        "bikini",
        "socks",
        "tights",
        "underwear",
        "briefs",
        "boxers",
        "bra",
        "hat",
        "cap",
        "beanie",
        "mittens",
        "gloves",
        "scarf",
        "shoes",
        "sneakers",
        "boots",
        "sandals",
        "slippers",
        "cleats",
        "outfit",
        "clothing",
        "clothes",
        "uniform",
        "costume",
    ]
)

_WORD = re.compile(r"[a-z]+")


def looks_like_clothing(name: str | None, category: str | None = None) -> bool:
    """True when an item is worth a label pass.

    Tuned to over-ask rather than under-ask: the cost of being wrong in one direction is a wasted
    model call, and in the other it is a trip to the attic.
    """
    if category:
        folded = category.casefold()
        if any(hint in folded for hint in _CLOTHING_CATEGORY_HINTS):
            return True

    if not name:
        return False
    words = set(_WORD.findall(name.casefold()))
    if words & _CLOTHING_WORDS:
        return True
    # "4T winter coat" and "boys size 10" name the garment without naming a garment type.
    return bool(re.search(r"\b\d{1,2}t\b|\bsize\b|\b6x\b", name.casefold()))
