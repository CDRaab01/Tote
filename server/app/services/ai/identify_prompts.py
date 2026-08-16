"""The one vision prompt, and the parser that has to survive whatever comes back.

Tote's identify job is narrower than a selling app's: name the thing, say roughly what it is,
and put it in one of the user's OWN categories. It is not describing an item for a buyer, it is
labelling a box for someone who will read it in an attic in six months.

Two rules carried over from Crate's measured work, both of which cost real effort to learn:

* **Never invent.** A null sends a human to look in the bin; a confident wrong answer sends them
  to the wrong bin twice, and they stop trusting the catalog after the second time. Every field
  is optional and "unsure" is an accepted, expected answer.
* **One job per prompt.** Pushing an omnibus prompt to try harder measurably produced *no* recall
  gain and a reproducible wrong answer. If a second thing needs reading (a clothing tag, in
  Phase 5), it gets its own narrow prompt, not another paragraph in this one.
"""

from dataclasses import dataclass, field

from app.models.item import ITEM_CONDITIONS
from app.services.ai.json_salvage import clean_str, strip_fences, widest_object_span

# The vocabulary the model is allowed to answer with for condition. Anything else is dropped
# rather than coerced — a guessed condition is worse than none, because "good" reads as a human
# judgement someone else can rely on.
_CONDITIONS = set(ITEM_CONDITIONS)
_CONFIDENCE = {"high", "medium", "low"}


@dataclass
class IdentifyDraft:
    """What the model thought, before a human confirms it. Every field may be absent."""

    name: str | None = None
    description: str | None = None
    # The NAME of one of the user's categories, matched case-insensitively by the caller. Never
    # an id: the model is not shown ids, and inventing one would be unfalsifiable.
    category: str | None = None
    condition: str | None = None
    confidence: str = "low"
    # Only set when the photo shows several obviously identical things ("4 boxes"). Left null for
    # anything ambiguous, because a wrong count silently changes what the catalog claims you own.
    quantity: int | None = None
    notes: list[str] = field(default_factory=list)


_SYSTEM = """You identify household objects from photographs so they can be catalogued before \
being packed into a storage bin.

Answer ONLY with a JSON object. No prose, no code fences.

{
  "name": "short specific name, 2-6 words",
  "description": "one short sentence, or null",
  "category": "exactly one of the provided categories, or null",
  "condition": "new | like_new | good | fair | poor, or null",
  "quantity": integer, or null,
  "confidence": "high | medium | low"
}

Rules:
- If you are not sure, use null. Do not guess. A null is a useful answer here.
- "name" should be what a person would write on a list: "Cordless drill", "Board game — Catan",
  "Toddler winter coat". Not a sentence, not a sales description.
- "category" MUST be copied exactly from the list you are given, or be null. Never invent one.
- "quantity" only when the photo clearly shows several identical items. Otherwise null.
- "condition" only if the photo actually shows wear or newness. Otherwise null.
- Report low confidence freely. It is better than a confident mistake."""


def build_identify_messages(
    image_data_urls: list[str], categories: list[str] | None = None
) -> list[dict]:
    """The chat payload for one item's photos.

    The user's own categories are listed in the prompt rather than left to the model's
    imagination, because the whole point is filing into *their* vocabulary — a model-invented
    "Home & Garden" would have to be dropped on the way back anyway, so it is cheaper to
    constrain it up front and it measurably improves the hit rate.
    """
    lines = ["Identify this item."]
    if categories:
        lines.append("")
        lines.append("Choose the category from exactly this list, or answer null:")
        lines.extend(f"- {c}" for c in categories)

    content: list[dict] = [{"type": "text", "text": "\n".join(lines)}]
    content.extend({"type": "image_url", "image_url": {"url": u}} for u in image_data_urls)

    return [
        {"role": "system", "content": _SYSTEM},
        {"role": "user", "content": content},
    ]


def parse_identify(raw_text: str, categories: list[str] | None = None) -> IdentifyDraft | None:
    """Model text → a draft, or None when nothing usable came back.

    Never raises. Everything is optional and every unknown value is dropped rather than
    coerced — this is the vision write path, where output degrades. A human `PATCH` of the same
    fields rejects with a 422 instead, because a value a person typed is a claim and a value a
    model produced is a suggestion.
    """
    import json

    text = strip_fences(raw_text or "")
    data = None
    for candidate in (text, widest_object_span(text)):
        if not candidate:
            continue
        try:
            parsed = json.loads(candidate)
        except (ValueError, TypeError):
            continue
        if isinstance(parsed, dict):
            data = parsed
            break
    if data is None:
        return None

    condition = clean_str(data.get("condition"), 16)
    if condition is not None:
        condition = condition.lower().replace(" ", "_").replace("-", "_")
        if condition not in _CONDITIONS:
            condition = None

    confidence = (clean_str(data.get("confidence"), 8) or "low").lower()
    if confidence not in _CONFIDENCE:
        confidence = "low"

    category = clean_str(data.get("category"), 80)
    if category is not None and categories:
        # Matched against the user's real list, case-insensitively. A near-miss is dropped, not
        # fuzzy-matched: filing something into the wrong category is exactly the kind of quiet
        # error that makes a catalog untrustworthy.
        lookup = {c.casefold(): c for c in categories}
        category = lookup.get(category.casefold())

    quantity = data.get("quantity")
    if not isinstance(quantity, int) or isinstance(quantity, bool) or not 1 <= quantity <= 999:
        quantity = None

    return IdentifyDraft(
        name=clean_str(data.get("name"), 160),
        description=clean_str(data.get("description"), 500),
        category=category,
        condition=condition,
        confidence=confidence,
        quantity=quantity,
    )
