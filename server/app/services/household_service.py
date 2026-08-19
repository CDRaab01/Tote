"""Household membership, and the merge that happens when someone accepts an invite.

The membership half of this file is unremarkable and mirrors Cookbook's. The merge is the part
worth reading, because it is where Tote's data model stops resembling Cookbook's.

## Why accepting an invite is a merge and not a flag

Every user owns a household from their first login, so an invitee is never joining *from
nowhere* — they arrive with a catalogue of their own, however small. Accepting therefore moves
their bins, items, people and ledger into the inviting household and deletes the one they came
from. There is no undo: a merged catalogue has no seam along which to split it again, because
after the merge the two people have been moving each other's things.

## Two kinds of collision, two very different answers

**Names merge silently.** Two "Attic" locations, or the "Clothing" category both accounts got
from `DEFAULT_CATEGORIES` at first login, are the *same real thing* recorded twice. Keeping both
would put two identical chips in a filter row and split "everything in the attic" across two
groups. So the source row is folded into the target's and everything pointing at it repointed.

**Physical identity blocks.** A tote `code` is written on a card in a bin; an `nfc_tag_uid` is a
sticker. If both households have a bin "A14", no rule this code could apply is right — renaming
one silently makes a printed card lie, and merging them claims two real bins are one. A person
has to walk to the attic. So the merge refuses, and says which codes.

That asymmetry is the whole design: **merge what is a duplicate record, refuse what is a
duplicate object.**
"""

import uuid

from fastapi import HTTPException, status
from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.category import Category
from app.models.container import Container
from app.models.household import Household, HouseholdInvite, HouseholdMember
from app.models.item import Item
from app.models.location import Location
from app.models.person import Person
from app.models.tote import Tote
from app.models.user import User, UserSettings

# Re-parented wholesale once name collisions are resolved. Locations and categories are absent
# because they need the fold-by-name pass first.
_REPARENTED = (Tote, Item, Person, Container)


async def create_household(db: AsyncSession, user_id: uuid.UUID) -> Household:
    """A household of one. Called at first login so `user.household_id` is never absent."""
    household = Household(owner_user_id=user_id)
    db.add(household)
    await db.flush()
    db.add(HouseholdMember(household_id=household.id, user_id=user_id))
    await db.flush()
    return household


async def household_of(db: AsyncSession, user_id: uuid.UUID) -> Household:
    household = (
        await db.execute(
            select(Household)
            .join(HouseholdMember, HouseholdMember.household_id == Household.id)
            .where(HouseholdMember.user_id == user_id)
        )
    ).scalar_one_or_none()
    if household is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No household")
    return household


async def members(db: AsyncSession, household_id: uuid.UUID) -> list[User]:
    return list(
        (
            await db.execute(
                select(User)
                .join(HouseholdMember, HouseholdMember.user_id == User.id)
                .where(HouseholdMember.household_id == household_id)
                .order_by(User.created_at)
            )
        )
        .scalars()
        .all()
    )


async def member_ids(db: AsyncSession, household_id: uuid.UUID) -> list[uuid.UUID]:
    return list(
        (
            await db.execute(
                select(HouseholdMember.user_id).where(HouseholdMember.household_id == household_id)
            )
        )
        .scalars()
        .all()
    )


# --- Invites -----------------------------------------------------------------------------


async def invite_by_email(db: AsyncSession, inviter: User, email: str) -> User:
    """Owner offers to merge another Tote user's catalogue into theirs.

    They must have signed into Tote at least once. That is not a technical limitation to
    apologise for — it is what makes the invite land on a real account with a real (possibly
    empty) catalogue, which is what the merge preview is computed against.
    """
    household = await household_of(db, inviter.id)
    if household.owner_user_id != inviter.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the household owner can invite")

    target = (
        await db.execute(select(User).where(func.lower(User.email) == email.strip().lower()))
    ).scalar_one_or_none()
    if target is None:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            "No Tote account for that email — they need to sign in to Tote once first",
        )
    if target.id == inviter.id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "That's your own account")

    if target.id in await member_ids(db, household.id):
        raise HTTPException(status.HTTP_409_CONFLICT, "They're already in your household")

    existing = (
        await db.execute(
            select(HouseholdInvite).where(HouseholdInvite.invited_user_id == target.id)
        )
    ).scalar_one_or_none()
    if existing is not None:
        if existing.household_id == household.id:
            return target  # idempotent re-invite
        raise HTTPException(
            status.HTTP_409_CONFLICT, "They already have an invite from another household"
        )

    db.add(
        HouseholdInvite(
            household_id=household.id,
            invited_user_id=target.id,
            invited_by_user_id=inviter.id,
        )
    )
    await db.commit()
    return target


async def pending_invites(db: AsyncSession, household_id: uuid.UUID) -> list[User]:
    """Everyone this household has invited who has not answered.

    Its own query rather than a flag on the member list, because a pending invitee is **not** a
    member — they share nothing until they accept, and a shape that makes them look like one is
    how a roster starts claiming somebody is in a household they have not joined.
    """
    return list(
        (
            await db.execute(
                select(User)
                .join(HouseholdInvite, HouseholdInvite.invited_user_id == User.id)
                .where(HouseholdInvite.household_id == household_id)
                .order_by(HouseholdInvite.created_at)
            )
        )
        .scalars()
        .all()
    )


async def revoke_invite(db: AsyncSession, requester: User, invited_user_id: uuid.UUID) -> None:
    """Take back an invitation you sent.

    Only the invitee could end one before this, by declining. An email address is free text
    matched against accounts, so a typo sent a real, standing invitation to whoever owns that
    address — with no way for the sender to withdraw it.
    """
    household = await household_of(db, requester.id)
    if household.owner_user_id != requester.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the household owner can invite")
    invite = (
        await db.execute(
            select(HouseholdInvite).where(
                HouseholdInvite.invited_user_id == invited_user_id,
                # Scoped to the caller's household: an owner may withdraw their own offer and
                # not somebody else's.
                HouseholdInvite.household_id == household.id,
            )
        )
    ).scalar_one_or_none()
    if invite is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No invitation to that person")
    await db.delete(invite)
    await db.commit()


async def invite_for(db: AsyncSession, user_id: uuid.UUID) -> HouseholdInvite | None:
    return (
        await db.execute(select(HouseholdInvite).where(HouseholdInvite.invited_user_id == user_id))
    ).scalar_one_or_none()


async def decline_invite(db: AsyncSession, user_id: uuid.UUID) -> None:
    invite = await invite_for(db, user_id)
    if invite is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No pending invite")
    await db.delete(invite)
    await db.commit()


# --- The merge ---------------------------------------------------------------------------


async def merge_conflicts(
    db: AsyncSession, source_id: uuid.UUID, target_id: uuid.UUID, joiner_id: uuid.UUID
) -> dict[str, list[str]]:
    """What a human has to resolve before these two catalogues can become one.

    Two unrelated kinds of blocker, both reported here because both have the same consequence —
    the merge cannot run and a person has to go and do something first:

    * **Physical identity.** Duplicate tote codes and NFC tags. Duplicate location and category
      *names* are deliberately absent: they are the same real thing recorded twice, and the merge
      folds them.
    * **You are not alone in your own household.** See ``_members_you_would_strand``.
    """
    codes = await _overlap(db, Tote, Tote.code, source_id, target_id, lower=True)
    tags = await _overlap(db, Tote, Tote.nfc_tag_uid, source_id, target_id)
    # Client-generated UUIDs, so this should be unreachable. It is checked anyway because the
    # alternative to a clear 409 is a unique-violation 500 halfway through re-parenting a
    # catalogue, and this is the one operation in the app with no undo.
    captures = await _overlap(db, Item, Item.capture_id, source_id, target_id)
    stranded = await _members_you_would_strand(db, source_id, joiner_id)

    conflicts: dict[str, list[str]] = {}
    if codes:
        conflicts["tote_codes"] = codes
    if tags:
        conflicts["nfc_tags"] = tags
    if captures:
        conflicts["capture_ids"] = captures
    if stranded:
        conflicts["household_members"] = stranded
    return conflicts


async def _members_you_would_strand(
    db: AsyncSession, source_id: uuid.UUID, joiner_id: uuid.UUID
) -> list[str]:
    """Everyone else in the household the joiner is about to leave, by name.

    **The bug this exists to prevent.** A merge re-parents the source household's data and then
    deletes the source row, which CASCADEs ``household_members`` — so anybody still in it loses
    their membership entirely. They are not merely dropped from a catalogue: ``User.household_id``
    is deliberately non-defensive, so it raises, and *every endpoint 500s for them, permanently*.
    Nor can they sign their way out of it, because ``suite_login`` only creates a household on the
    branch that handles a new account, and they already exist.

    So a person may only join another household when theirs is just them. That is the same rule
    that already stops an owner leaving a populated household, reached from the other side: a
    household is never left ownerless, and never left memberless either.

    Named rather than counted, because "leave the household you share with Alex first" is
    actionable and "you have 1 other member" is a puzzle.
    """
    return sorted(
        (
            await db.execute(
                select(User.name)
                .join(HouseholdMember, HouseholdMember.user_id == User.id)
                .where(
                    HouseholdMember.household_id == source_id,
                    User.id != joiner_id,
                )
            )
        )
        .scalars()
        .all()
    )


async def _overlap(
    db: AsyncSession,
    model,
    column,
    source_id: uuid.UUID,
    target_id: uuid.UUID,
    *,
    lower: bool = False,
) -> list[str]:
    expr = func.lower(column) if lower else column

    def values_for(household_id: uuid.UUID):
        return select(expr).where(model.household_id == household_id, column.is_not(None))

    source = set((await db.execute(values_for(source_id))).scalars().all())
    target = set((await db.execute(values_for(target_id))).scalars().all())
    return sorted(str(v) for v in source & target)


async def merge_preview(db: AsyncSession, source_id: uuid.UUID) -> dict[str, int]:
    """What the invitee is about to hand over, counted.

    Shown before they can accept: nobody should discover the size of an irreversible operation
    after committing to it.
    """
    out: dict[str, int] = {}
    for model, key in ((Tote, "totes"), (Item, "items"), (Person, "people")):
        out[key] = (
            await db.execute(
                select(func.count()).select_from(model).where(model.household_id == source_id)
            )
        ).scalar_one()
    return out


async def accept_invite(db: AsyncSession, user: User) -> Household:
    """Merge the caller's catalogue into the inviting household. Irreversible."""
    invite = await invite_for(db, user.id)
    if invite is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No pending invite")

    source = await household_of(db, user.id)
    target_id = invite.household_id
    if source.id == target_id:
        raise HTTPException(status.HTTP_409_CONFLICT, "You're already in that household")

    conflicts = await merge_conflicts(db, source.id, target_id, user.id)
    if conflicts:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            {
                "message": (
                    "This merge can't run yet — see `conflicts` for what has to be resolved first."
                ),
                "conflicts": conflicts,
            },
        )

    await _fold_by_name(db, Location, source.id, target_id)
    await _fold_by_name(db, Category, source.id, target_id)
    for model in _REPARENTED:
        await db.execute(
            update(model).where(model.household_id == source.id).values(household_id=target_id)
        )

    await db.execute(
        update(HouseholdMember)
        .where(HouseholdMember.user_id == user.id)
        .values(household_id=target_id)
    )
    await db.delete(invite)
    # Safe now and only now: everything that hung off it has been re-parented, so the CASCADE
    # this would otherwise fire has nothing left to take.
    await db.execute(delete(Household).where(Household.id == source.id))
    await db.commit()
    return await household_of(db, user.id)


# Which foreign keys point at a location / category, so a folded row leaves nothing dangling.
# Explicit rather than reflected: a new FK to either table has to be a deliberate addition here,
# and a reflection-driven version would silently do nothing when someone added one.
_REFERENCES = {
    Location: (
        (Tote, "location_id"),
        (Location, "parent_id"),
        (UserSettings, "default_location_id"),
    ),
    Category: ((Tote, "category_id"), (Item, "category_id")),
}


async def _fold_by_name(db: AsyncSession, model, source_id: uuid.UUID, target_id: uuid.UUID):
    """Merge same-named rows into the target's, then re-parent the survivors.

    Case-insensitive, because "attic" and "Attic" are one place. The target's row wins — the
    inviting household's spelling is the one already written on its cards.
    """
    target_by_name = {
        name.lower(): row_id
        for row_id, name in (
            await db.execute(select(model.id, model.name).where(model.household_id == target_id))
        ).all()
    }
    source_rows = (
        await db.execute(select(model.id, model.name).where(model.household_id == source_id))
    ).all()

    for row_id, name in source_rows:
        winner = target_by_name.get(name.lower())
        if winner is None:
            continue
        for ref_model, column in _REFERENCES[model]:
            await db.execute(
                update(ref_model)
                .where(getattr(ref_model, column) == row_id)
                .values(**{column: winner})
            )
        await db.execute(delete(model).where(model.id == row_id))

    await db.execute(
        update(model).where(model.household_id == source_id).values(household_id=target_id)
    )


# --- Leaving -----------------------------------------------------------------------------


async def remove_member(db: AsyncSession, requester: User, target_id: uuid.UUID) -> None:
    household = await household_of(db, requester.id)
    if requester.id != household.owner_user_id and requester.id != target_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner can remove someone else")
    if target_id == household.owner_user_id:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "The owner can't be removed — transfer ownership first",
        )
    if target_id not in await member_ids(db, household.id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not a member of your household")
    await _detach(db, target_id)


async def leave_household(db: AsyncSession, user: User) -> None:
    """Leave, and **leave the catalogue behind**.

    The opposite of Cookbook, where leaving costs you nothing because recipes were always yours.
    Here the household owns the bins, so walking out means walking out of the attic: the leaver
    gets a fresh empty household and nothing is copied back. That is the honest model for
    physical property in a shared house, and it is why the client says so out loud before
    calling this.
    """
    household = await household_of(db, user.id)
    if household.owner_user_id == user.id:
        remaining = [m for m in await member_ids(db, household.id) if m != user.id]
        if remaining:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "Transfer ownership before leaving — a household cannot be left ownerless",
            )
        return  # Solo: leaving your own household of one is a no-op, not a way to wipe it.
    await _detach(db, user.id)


async def _detach(db: AsyncSession, user_id: uuid.UUID) -> None:
    """Give a departing user their own empty household, so `user.household_id` still resolves.

    Deliberately not `create_household()`: that inserts a membership row, and this user already
    has one (`user_id` is unique). The row is *moved*, not replaced.
    """
    fresh = Household(owner_user_id=user_id)
    db.add(fresh)
    await db.flush()
    await db.execute(
        update(HouseholdMember)
        .where(HouseholdMember.user_id == user_id)
        .values(household_id=fresh.id)
    )
    await db.commit()


async def transfer_ownership(db: AsyncSession, requester: User, target_id: uuid.UUID) -> None:
    household = await household_of(db, requester.id)
    if household.owner_user_id != requester.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner can transfer ownership")
    if target_id not in await member_ids(db, household.id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not a member of your household")
    household.owner_user_id = target_id
    await db.commit()
