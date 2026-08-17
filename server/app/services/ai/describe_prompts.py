"""The narrow describe call: you say what it is, the model says what THIS one looks like.

The third single-job prompt in this package, and the reason there are three rather than one
omnibus is measured, not aesthetic: Crate's label pass took size reading from 3/18 to 12/18 by
splitting the question out, while pushing the combined prompt harder gained no recall and
produced a reproducible wrong answer.

This one exists because the description is not decoration. `items.search_vector` is a generated
column over **name, description and notes** — so "the one with the ducks on it" only ever finds
anything if something wrote "ducks". A photographed item with a bare name is close to unfindable
by any words except its name.

Which is also the reason for every restriction below. A description that feeds a search index
must not contain things the photo does not show: a hallucinated detail is not a cosmetic blemish,
it is a false hit on a search someone trusts. So the prompt is told the item's name, forbidden to
re-identify it, and told to say nothing rather than pad.
"""

import json

from pydantic import BaseModel

from app.services.ai.json_salvage import clean_str, strip_fences, widest_object_span

DESCRIBE_SYSTEM_PROMPT = (
    "You describe a household object that has ALREADY been identified for you, so that its "
    "owner can recognise this particular one later and find it by searching. You never guess "
    "what the object is — you are told. You report only what is plainly visible in the "
    "photographs. You only output JSON — never prose, never Markdown, never an explanation."
)


def build_describe_messages(image_data_urls: list[str], name: str) -> list[dict]:
    user = (
        f'These photos show: "{name}". That identification is correct and is not yours to '
        "revise.\n"
        "Respond with ONLY a JSON object, no prose and no code fences, shaped exactly like "
        "this:\n"
        '{"description": string or null}\n'
        "Describe what distinguishes THIS one from another of the same thing: colour, pattern, "
        "printed characters or graphics, obvious wear or damage, and anything included with it. "
        "One short sentence, at most about 20 words.\n"
        "Only what you can actually see. Do not state the brand unless it is legibly printed in "
        "the photo. Do not guess material, age, size or value. Do not repeat the name back.\n"
        "This text is added to a search index the owner will trust, so a detail you invented "
        "becomes a result that is simply wrong. If nothing distinguishing is visible — a plain "
        "object, a dark photo, a close crop — set description to null. A null costs nothing; a "
        "guess costs someone a search that lies to them."
    )
    content: list[dict] = [{"type": "text", "text": user}]
    content += [{"type": "image_url", "image_url": {"url": url}} for url in image_data_urls]
    return [
        {"role": "system", "content": DESCRIBE_SYSTEM_PROMPT},
        {"role": "user", "content": content},
    ]


class DescribeDraft(BaseModel):
    """What the photo shows about this particular one. Null is a real answer, not a failure."""

    description: str | None = None


def parse_describe(raw_text: str) -> DescribeDraft | None:
    """Best-effort parse. Returns None (never raises) when nothing usable came back.

    Same salvage contract as the other two: fences stripped, widest `{...}` span as a fallback,
    and the field capped to the column width in `models/item.py` so a chatty model cannot
    overflow a write.
    """
    stripped = strip_fences(raw_text)
    data = None
    for candidate in (stripped, widest_object_span(stripped)):
        if not candidate:
            continue
        try:
            parsed = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(parsed, dict):
            data = parsed
            break
    if not isinstance(data, dict) or not data:
        return None
    return DescribeDraft(description=clean_str(data.get("description"), 400))
