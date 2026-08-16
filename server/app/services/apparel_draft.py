"""Turn a label reading into an `item_apparel` row. Pure decision logic, one caller.

Kept out of `scan_pipeline` so the rules below are testable without a database, a model or a
photograph — they are the rules most likely to be quietly "improved" later, and the tests are
what make that a failing build rather than a wrong bin.
"""

import logging

from app.apparel import DEPARTMENTS, SIZE_TYPES, normalize_enum
from app.models.item import ItemApparel
from app.services.ai.label_prompts import LabelDraft
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
    parse_size,
)

logger = logging.getLogger(__name__)

# The age band a size system implies. DERIVED, never asked of the model: a sewn-in label prints
# "4T", not "toddler", so asking would be inviting exactly the inference this app refuses. A
# lookup over the parsed system is deterministic and reviewable instead.
SIZE_TYPE_OF_SYSTEM: dict[str, str] = {
    SYSTEM_INFANT_MONTHS: "infant",
    SYSTEM_TODDLER: "toddler",
    SYSTEM_YOUTH_NUMERIC: "youth",
    SYSTEM_YOUTH_ALPHA: "youth",
    SYSTEM_SHOE_US_CHILD: "youth",
    SYSTEM_ADULT_ALPHA: "adult",
    SYSTEM_WOMENS_NUMERIC: "adult",
    SYSTEM_MENS_WAIST: "adult",
    SYSTEM_SHOE_US_ADULT: "adult",
}


def apparel_from_label(item_id, label: LabelDraft) -> ItemApparel | None:
    """Build the apparel row a label reading justifies, or None when it justifies none.

    The contract, in order of how easy each is to get wrong:

    1. **`size_raw` is whatever the label said, verbatim.** Even when nothing below can parse it.
       A row carrying only `size_raw="M/L"` is a *good* outcome — a human reads it in two seconds
       and the reading was not thrown away.
    2. **`size_system`/`size_ordinal` are null when the string does not parse.** Never guessed,
       never approximated from the department, never defaulted to the middle of a run.
    3. **`size_type` is derived from the parsed system**, so it is null exactly when the size is
       unparsed. It is not an independent claim that could disagree with the ordinal.
    4. **Department is only used as evidence**, to disambiguate a bare number — `8` under a
       `girls` label is a youth 8. With no department a bare number stays unparsed, because youth
       8 and women's 8 are different garments for different people.
    """
    if label is None or label.is_empty():
        return None

    department = normalize_enum(label.department, DEPARTMENTS)
    reading = parse_size(label.size, department=department)

    size_type = None
    if reading is not None:
        size_type = normalize_enum(SIZE_TYPE_OF_SYSTEM.get(reading.system), SIZE_TYPES)

    if label.size and reading is None:
        # Worth a log line: this is the designed outcome, not an error, but a run of them means
        # the ladder is missing a real-world spelling and that is only visible in aggregate.
        logger.info("size %r not on the ladder (department=%r) - kept raw", label.size, department)

    return ItemApparel(
        item_id=item_id,
        size_raw=label.size,
        size_system=reading.system if reading else None,
        size_ordinal=reading.ordinal if reading else None,
        size_type=size_type,
        department=department,
        material=label.material,
    )
