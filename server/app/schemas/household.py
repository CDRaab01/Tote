import uuid

from pydantic import BaseModel


class HouseholdMemberOut(BaseModel):
    user_id: uuid.UUID
    name: str
    email: str
    is_owner: bool


class HouseholdOut(BaseModel):
    household_id: uuid.UUID
    members: list[HouseholdMemberOut]
    you_are_owner: bool
    # More than one member — the catalogue is actually being shared. The client uses this to
    # decide whether "who moved it" is worth showing at all: in a household of one the answer is
    # always "you", and a column of your own name is noise.
    shared: bool


class InviteRequest(BaseModel):
    email: str


class MergePreview(BaseModel):
    """What accepting would move, and what stops it.

    Both halves travel together because they answer one question — "what happens if I tap
    accept" — and a preview that showed the size without the blockers would be a preview of an
    operation that is not going to run.
    """

    totes: int
    items: int
    people: int
    # Empty when the merge can proceed. Keyed by kind (`tote_codes`, `nfc_tags`, `capture_ids`)
    # so the client can name the physical thing the person has to go and fix.
    conflicts: dict[str, list[str]] = {}


class InviteOut(BaseModel):
    """An invitation awaiting the caller's answer (null when there is none)."""

    household_id: uuid.UUID
    invited_by_name: str
    invited_by_email: str
    # What the caller would hand over. Counted against the CALLER's catalogue, because they are
    # the one giving something up — accepting merges their bins into the inviter's household.
    preview: MergePreview
