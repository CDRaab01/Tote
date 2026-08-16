"""Request/response models for people, their sizes, and the fits query."""

import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.models.person import GARMENT_TYPES
from app.schemas.catalog import ItemOut


class PersonIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    birthdate: datetime.date | None = None
    notes: str | None = None


class PersonPatch(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=80)
    birthdate: datetime.date | None = None
    notes: str | None = None


class PersonSizeIn(BaseModel):
    """A size reading for one garment type, as of a date.

    `size_raw` is what a person said or read off a tag, and it is stored verbatim exactly as it
    is for items. The system/ordinal index is derived server-side from it and is **not** settable
    here — a client that could set it could record Emma as a 4T indexed as an adult L.
    """

    garment_type: str
    size_raw: str = Field(min_length=1, max_length=32)
    # Defaults to today rather than being required: the overwhelmingly common act is "she is in
    # a 5T now", and making someone pick a date for that is friction on the only path anyone
    # will actually walk.
    effective_from: datetime.date | None = None
    notes: str | None = None

    @field_validator("garment_type")
    @classmethod
    def known_garment_type(cls, v: str) -> str:
        if v not in GARMENT_TYPES:
            raise ValueError(f"garment_type must be one of {GARMENT_TYPES}")
        return v


class PersonSizeOut(BaseModel):
    id: uuid.UUID
    person_id: uuid.UUID
    garment_type: str
    size_raw: str
    size_system: str | None
    size_ordinal: float | None
    effective_from: datetime.date
    notes: str | None

    model_config = {"from_attributes": True}


class PersonOut(BaseModel):
    id: uuid.UUID
    name: str
    birthdate: datetime.date | None
    notes: str | None
    created_at: datetime.datetime
    # The sizes in effect today, one per garment type — the answer to "what size is she now"
    # without a second request. Computed, never stored.
    current_sizes: list[PersonSizeOut] = []
    # How many items this person currently has out on loan. Surfaced here because "who has the
    # drill" is one of the two questions this table exists to answer.
    on_loan_count: int = 0

    model_config = {"from_attributes": True}


class FitsOut(BaseModel):
    """What fits, and — just as importantly — whether we could say.

    `answered` is false when the person has no indexed size for the requested garment type. A
    client MUST distinguish that from an empty `items`: "we have nothing that fits" and "we do
    not know her size" are different sentences, and only one of them is a reason to stop looking.
    """

    answered: bool
    reason: str | None = None
    garment_type: str | None = None
    tolerance: float
    matched_sizes: list[PersonSizeOut] = []
    items: list[ItemOut] = []


class OutgrownIn(BaseModel):
    """Mark a run of items outgrown and file them into a tote, in one action.

    This is the flow the ledger was built for: a size run leaves the wearing pile together, and
    recording it as fifty individual edits means nobody does it and the catalog rots.
    """

    item_ids: list[uuid.UUID] = Field(min_length=1)
    # Where they are going. Required, because "outgrown" without a destination leaves a pile on
    # the floor that the catalog claims is nowhere.
    tote_id: uuid.UUID
    note: str | None = None
