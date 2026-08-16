"""Request/response models for the catalog.

Two conventions worth stating once:

* **PATCH bodies use `exclude_unset`, never `exclude_none`.** Kotlinx clients send explicit
  nulls, and a null here is a real instruction ("clear the location"), not an absence. Using
  `exclude_none` would make clearing a field impossible — Crate hit exactly this.
* **Computed fields are computed here, not on the client.** `item_count`, `is_overdue` and the
  like are delivered ready to display, per the suite rule that clients present and the server
  decides.
"""

import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.models.item import ITEM_CONDITIONS
from app.models.movement import MOVEMENT_REASONS


class LocationIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    parent_id: uuid.UUID | None = None
    sort_order: int = 0


class LocationPatch(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=80)
    parent_id: uuid.UUID | None = None
    sort_order: int | None = None


class LocationOut(BaseModel):
    id: uuid.UUID
    name: str
    parent_id: uuid.UUID | None
    sort_order: int

    model_config = {"from_attributes": True}


class CategoryIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    icon: str | None = Field(default=None, max_length=48)
    sort_order: int = 0


class CategoryPatch(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=80)
    icon: str | None = Field(default=None, max_length=48)
    sort_order: int | None = None


class CategoryOut(BaseModel):
    id: uuid.UUID
    name: str
    icon: str | None
    sort_order: int

    model_config = {"from_attributes": True}


class ToteIn(BaseModel):
    code: str = Field(min_length=1, max_length=16)
    label: str | None = Field(default=None, max_length=120)
    category_id: uuid.UUID | None = None
    location_id: uuid.UUID | None = None
    notes: str | None = None
    bin_kind: str | None = Field(default=None, max_length=48)
    color: str | None = Field(default=None, max_length=32)

    @field_validator("code")
    @classmethod
    def code_has_no_surrounding_space(cls, v: str) -> str:
        """A code is handwritten onto a card and typed back in later. Leading/trailing space is
        invisible on the card and would defeat the case-insensitive uniqueness index, producing
        two bins that look identically labelled."""
        stripped = v.strip()
        if not stripped:
            raise ValueError("code cannot be blank")
        return stripped


class TotePatch(BaseModel):
    code: str | None = Field(default=None, min_length=1, max_length=16)
    label: str | None = Field(default=None, max_length=120)
    category_id: uuid.UUID | None = None
    location_id: uuid.UUID | None = None
    notes: str | None = None
    bin_kind: str | None = Field(default=None, max_length=48)
    color: str | None = Field(default=None, max_length=32)
    archived: bool | None = None


class ToteOut(BaseModel):
    id: uuid.UUID
    code: str
    label: str | None
    category_id: uuid.UUID | None
    location_id: uuid.UUID | None
    notes: str | None
    bin_kind: str | None
    color: str | None
    archived: bool
    nfc_tag_uid: str | None
    nfc_written_at: datetime.datetime | None
    # So the app can surface the bins that have never been labelled — the ones that will be a
    # mystery in six months.
    card_printed_at: datetime.datetime | None
    created_at: datetime.datetime
    # Computed, never stored: a denormalised count is the first thing to drift, and here the
    # drift would be printed on an index card and written into an NFC tag.
    item_count: int = 0
    # Items whose last movement left THIS tote and have not come back. Surfaced as a first-class
    # number because it is the answer to "I thought the lights were in here".
    out_count: int = 0

    model_config = {"from_attributes": True}


class ItemIn(BaseModel):
    name: str = Field(min_length=1, max_length=160)
    description: str | None = None
    notes: str | None = None
    category_id: uuid.UUID | None = None
    quantity: int = Field(default=1, ge=1)
    condition: str | None = None
    # Where it goes on creation. Optional: an item can exist before it is filed, which is what
    # "I emptied this bag onto the table" looks like.
    tote_id: uuid.UUID | None = None
    value_est: float | None = Field(default=None, ge=0)
    acquired_at: datetime.date | None = None

    @field_validator("condition")
    @classmethod
    def known_condition(cls, v: str | None) -> str | None:
        if v is not None and v not in ITEM_CONDITIONS:
            raise ValueError(f"condition must be one of {ITEM_CONDITIONS}")
        return v


class ItemPatch(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=160)
    description: str | None = None
    notes: str | None = None
    category_id: uuid.UUID | None = None
    quantity: int | None = Field(default=None, ge=1)
    condition: str | None = None
    value_est: float | None = Field(default=None, ge=0)
    acquired_at: datetime.date | None = None

    @field_validator("condition")
    @classmethod
    def known_condition(cls, v: str | None) -> str | None:
        if v is not None and v not in ITEM_CONDITIONS:
            raise ValueError(f"condition must be one of {ITEM_CONDITIONS}")
        return v


class ItemOut(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None
    notes: str | None
    category_id: uuid.UUID | None
    quantity: int
    condition: str | None
    status: str
    current_tote_id: uuid.UUID | None
    out_reason: str | None
    out_since: datetime.datetime | None
    expected_back: datetime.date | None
    value_est: float | None
    acquired_at: datetime.date | None
    created_at: datetime.datetime
    # Denormalised for display so a list of search hits does not need one request per row to
    # answer the only question that matters: which bin, and where is it.
    tote_code: str | None = None
    location_name: str | None = None
    # Computed server-side (clients display, never compute) so "overdue" means the same thing
    # everywhere, including in a notification composed without a UI.
    is_overdue: bool = False

    model_config = {"from_attributes": True}


class MoveIn(BaseModel):
    reason: str
    to_tote_id: uuid.UUID | None = None
    person_id: uuid.UUID | None = None
    note: str | None = None
    expected_back: datetime.date | None = None
    moved_at: datetime.datetime | None = None

    @field_validator("reason")
    @classmethod
    def known_reason(cls, v: str) -> str:
        if v not in MOVEMENT_REASONS:
            raise ValueError(f"reason must be one of {MOVEMENT_REASONS}")
        return v


class BulkMoveIn(BaseModel):
    """Selection for unpack/repack. `null` means "everything applicable" — an empty list does
    NOT, and is treated as an explicit selection of nothing, so an accidental empty selection
    cannot silently unpack a whole bin."""

    item_ids: list[uuid.UUID] | None = None
    note: str | None = None


class MovementOut(BaseModel):
    id: uuid.UUID
    item_id: uuid.UUID
    from_tote_id: uuid.UUID | None
    to_tote_id: uuid.UUID | None
    quantity: int
    reason: str
    person_id: uuid.UUID | None
    note: str | None
    moved_at: datetime.datetime

    model_config = {"from_attributes": True}


class SearchHit(BaseModel):
    item: ItemOut
    rank: float


class ToteDetail(ToteOut):
    items: list[ItemOut] = []
    # Items that left this tote and have not returned. Shown rather than hidden: the "it should
    # be in here" gap is the single most common reason to distrust a catalog.
    items_out: list[ItemOut] = []


class NfcWriteIn(BaseModel):
    """Recorded after the client has successfully written a physical tag.

    `tag_uid` is the tag's HARDWARE uid, not anything we chose. Storing it is what lets Tote say
    "this tag belongs to A14, but you are holding B03's card" instead of silently trusting
    whatever a tag claims — a tag is a physical object anyone could have rewritten.
    """

    tag_uid: str = Field(min_length=4, max_length=32)


class NfcResolveOut(BaseModel):
    """What the app gets when it resolves a tapped tag."""

    tote_id: uuid.UUID | None
    code: str
    # True when a tote with this code exists but its stored uid is a DIFFERENT tag. The tap is
    # still resolved — refusing would be useless in an attic — but the app says so.
    tag_mismatch: bool = False
