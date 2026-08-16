"""Prompt + parser for reading a garment's care/size label. One job, deliberately.

Why this is a second call rather than more words in `identify_prompts`. That prompt asks for
roughly a dozen things at once — name, description, category, condition, quantity — and size is
what falls out. Measured in Crate over three runs against eight real tag photographs, the omnibus
prompt read about 1 in 6 legible sizes; asked the same question in isolation ("what size is
printed on this label?"), the same model on the same images read 3 of 4 and correctly returned
nothing for both labels that had no size on them.

The tempting fix — telling the omnibus prompt to try harder — was **measured and rejected**: no
recall gain, plus a reproducible *wrong* answer, confidently reporting "M" for a label whose "S"
is circled, three runs out of three. That is precisely the failure the never-infer rule exists to
prevent, so the wording below is carried across rather than softened. In Crate a wrong size ships
the wrong garment to a buyer; here it files a coat in the wrong bin and sends someone into the
attic twice.

Three further inherited rules, each of which cost a measurement to learn:

* Read the **original** photo, never the cleaned one. Background removal is unpredictable on
  labels — it once decided a woven brand tab was "the subject" and cropped the shirt away, and a
  cropped photo cannot be un-cropped for the model.
* **Do not** retry the cleaned copy when the original returns null. Measured: it recovers the
  failing image two runs in three and answers a *wrong size* the third.
* The call needs **its own `except`** at the call site. A 503 here reaching the outer handler
  would rewrite a perfectly good identification as `identify_unavailable`.

## Where this differs from Crate, on purpose

Crate asks the label for `size_type` from its merchandising axis (regular/petite/plus/…). Tote's
`SIZE_TYPES` is the age band (infant/toddler/youth/adult), and **a sewn-in label almost never
prints that** — it prints "4T" or "10" or "M". Asking a model for it would be inviting exactly
the inference this module refuses. So Tote asks for what a label *does* carry (size, department,
material) and **derives** the age band from the parsed size system, which is deterministic and
reviewable rather than guessed.
"""

import json

from pydantic import BaseModel

from app.apparel import DEPARTMENTS, normalize_enum
from app.services.ai.json_salvage import clean_str, strip_fences, widest_object_span

LABEL_SYSTEM_PROMPT = (
    "You transcribe clothing care and size labels. You are given close-up photos of a "
    "garment's sewn-in label and you report only what is actually printed or woven on it, "
    "character for character. You are not identifying the garment and you are not estimating "
    "anything. You only output JSON — never prose, never Markdown, never an explanation."
)

LABEL_USER_PROMPT = (
    "These photos show a clothing label. Respond with ONLY a JSON object, no prose and no "
    "code fences, shaped exactly like this:\n"
    "{"
    '"size": string or null (EXACTLY as printed, e.g. "4T", "M", "X-LARGE", "32x34", "6X", '
    '"18 MONTHS"), '
    '"department": one of "mens"|"womens"|"unisex"|"boys"|"girls" or null (only if the label '
    "actually says so), "
    '"material": string or null (the fabric content as printed, e.g. "100% Cotton", '
    '"60% Cotton 40% Polyester")'
    "}\n"
    "Transcribe, do not interpret. Copy the characters you can actually see. Do not convert "
    "between sizing systems, do not expand or abbreviate, and do not translate. If the label "
    'says "6X", report "6X" — not "6".\n'
    "If a value is not printed on the label, or you cannot read it clearly, use null for that "
    "field. NEVER infer a garment's size from how it looks — a wrong size sends someone into "
    "the attic for the wrong bin; a null just means a human reads the tag while it is still in "
    "reach. A label showing only a brand name, or only laundry-care symbols, has no size: "
    "return null.\n"
    'If the label shows a full size run (for example "XS S M L XL") with exactly one option '
    "circled, boxed, ticked or otherwise marked, report the marked one. If several are marked, "
    "or none is, set size to null — reporting the middle of a run is a guess.\n"
    "If none of these three values is readable, respond with exactly {} and nothing else."
)


class LabelDraft(BaseModel):
    """What a label actually said.

    Every field optional — absent means "not printed, or not legible", which is a meaningful
    answer here rather than a failure.
    """

    size: str | None = None
    department: str | None = None
    material: str | None = None

    def is_empty(self) -> bool:
        return self.size is None and self.department is None and self.material is None


def build_label_messages(image_data_urls: list[str]) -> list[dict]:
    content: list[dict] = [{"type": "text", "text": LABEL_USER_PROMPT}]
    content += [{"type": "image_url", "image_url": {"url": url}} for url in image_data_urls]
    return [
        {"role": "system", "content": LABEL_SYSTEM_PROMPT},
        {"role": "user", "content": content},
    ]


def parse_label(raw_text: str) -> LabelDraft | None:
    """Best-effort parse of the label reply. Returns None (never raises) when nothing usable came
    back, so the caller can leave the item exactly as identification left it.

    Same salvage contract as `parse_identify`: fences stripped, widest `{...}` span as a fallback,
    an unrecognised department dropped rather than rejected. Field caps match the column widths in
    `models/item.py` so a chatty model cannot overflow a write.
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

    draft = LabelDraft(
        size=clean_str(data.get("size"), 32),
        department=normalize_enum(data.get("department"), DEPARTMENTS),
        material=clean_str(data.get("material"), 96),
    )
    # An all-null draft is indistinguishable from not having asked, and returning None lets the
    # caller skip the merge entirely.
    return None if draft.is_empty() else draft
