"""The sharing unit: one household, one catalogue, two (or more) people.

Tote is a catalogue of *physical* objects, and that is the whole reason this looks different
from Cookbook's household — the app it was otherwise copied from.

## Why the household OWNS the data, rather than widening reads over it

Cookbook shares by widening: a recipe stays owned by its creator and co-members are simply
allowed to read it. That works there because two "Groceries" lists are a nuisance and nothing
more. Here every namespace is attached to something you can hold:

* a tote `code` is written on an index card and encoded in an NFC tag,
* an `nfc_tag_uid` **is** a specific sticker on a specific bin,
* a `Location` is a place in the house — there is one attic.

Widening reads while leaving those unique *per user* would let two people own a bin "A14" and a
tag claim two totes. That is not a database inconvenience; it is two bins in an attic insisting
they are the same bin, which is precisely the failure `models/tote.py` was written to prevent.

So the household is the owning scope. Every catalogue table carries `household_id`, every
uniqueness constraint is scoped to it, and the access check is a single-column equality — the
same shape and the same query plan the per-user check had.

## `user_id` still exists, and means something narrower now

On every catalogue table `user_id` is **who created this row**, never **who may see it**. It is
nullable with `ON DELETE SET NULL`: removing a person from the world must not delete the bins
they happened to be the one to photograph. Read `user_id` for provenance; never for access.

## Every user has a household, always

A household of one is created at first login (`services/suite_auth.py`), so there is no "solo"
special case anywhere: the filter is always `household_id == ...`. Joining someone else's
household is therefore a **merge** of two populated households, not a flag flip — see
`services/household_service.py`, which is where the interesting part lives.
"""

import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Household(Base):
    """One shared catalogue. Never deleted while it holds anything.

    ``owner_user_id`` is ``RESTRICT`` rather than ``CASCADE`` on purpose, and it is the single
    most important word in this file: the catalogue hangs off this row, so a cascade from a
    user deletion would take an entire household inventory with it. Cookbook can afford to
    delete a household on owner-leave because its data is creator-owned and merely stops being
    reachable. Here the row *is* the owner of the data. An owner who leaves transfers ownership
    (see ``leave_household``); nothing disbands a populated household.
    """

    __tablename__ = "households"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    owner_user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="RESTRICT"), index=True
    )
    # Moved here from `user_settings`, where it was never read. A tag is a physical object in an
    # attic that no deploy can patch, so the URI baked into it must not vary by *which phone
    # wrote it* — two members writing different bases would produce bins that open for one
    # person and not the other, discoverable only by walking to the attic.
    nfc_uri_base: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class HouseholdMember(Base):
    """Membership. One row per user, always — there is no pending state here.

    Cookbook models an unaccepted invite as a member row with ``status="pending"``. It cannot do
    that here: the invitee is already the active member of their *own* household, and
    ``user_id`` is unique. Consent therefore lives in its own table (``HouseholdInvite``), which
    also gives it somewhere to hang the thing an invite needs and a member row does not — the
    merge preview.
    """

    __tablename__ = "household_members"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    household_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("households.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True
    )
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class HouseholdInvite(Base):
    """An outstanding "join my household" offer. Nothing is shared until it is accepted.

    One per invitee (``invited_user_id`` unique), so a person cannot be pulled at once toward two
    catalogues. Accepting **merges and is irreversible** — the invitee's bins, items and ledger
    move into the inviting household and their own empty household is deleted. That is why the
    invite is a real object a person answers rather than a side effect of being added.
    """

    __tablename__ = "household_invites"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    household_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("households.id", ondelete="CASCADE"), index=True
    )
    invited_user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True
    )
    invited_by_user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE")
    )
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
