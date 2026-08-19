"""Settings -> Household: the one sharing surface.

Sharing in Tote is all-or-nothing by design. Cookbook layers a per-recipe `shared` opt-in on top
of its household; there is no equivalent here and there should not be. A half-shared catalogue
answers "where is the ratchet set" with "somewhere you cannot see", which is worse than not
sharing at all — the app exists to end that sentence.

So membership is the only switch, and everything below is about making the one irreversible
moment in it (accepting an invite, which merges two catalogues) something a person can see
coming.
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.household import (
    HouseholdMemberOut,
    HouseholdOut,
    InviteOut,
    InviteRequest,
    MergePreview,
    PendingInviteOut,
)
from app.security import CurrentUser
from app.services import household_service as hh

router = APIRouter(prefix="/household", tags=["household"])

Db = Annotated[AsyncSession, Depends(get_db)]


async def _snapshot(db: AsyncSession, user) -> HouseholdOut:
    household = await hh.household_of(db, user.id)
    people = await hh.members(db, household.id)
    invited = await hh.pending_invites(db, household.id)
    return HouseholdOut(
        household_id=household.id,
        members=[
            HouseholdMemberOut(
                user_id=m.id,
                name=m.name,
                email=m.email,
                is_owner=(m.id == household.owner_user_id),
            )
            for m in people
        ],
        pending=[PendingInviteOut(user_id=p.id, name=p.name, email=p.email) for p in invited],
        you_are_owner=(household.owner_user_id == user.id),
        # Members only. An outstanding invitation shares nothing, so a household with one member
        # and one invitation is not a shared catalogue and must not start showing "who moved it".
        shared=len(people) > 1,
    )


@router.get("", response_model=HouseholdOut)
async def my_household(user: CurrentUser, db: Db):
    return await _snapshot(db, user)


@router.post("/members", response_model=HouseholdOut, status_code=status.HTTP_201_CREATED)
async def invite(body: InviteRequest, user: CurrentUser, db: Db):
    """Invite another Tote user by email. Nothing is shared until they accept."""
    await hh.invite_by_email(db, user, body.email)
    return await _snapshot(db, user)


@router.get("/invite", response_model=InviteOut | None)
async def my_invite(user: CurrentUser, db: Db):
    """The invitation waiting for the caller, with its merge preview — or null.

    The preview is computed on every read rather than stored at invite time, because both
    catalogues keep changing between the offer and the answer. A conflict list captured when the
    invite was sent would go stale in exactly the case it matters: someone renames bin A14 to
    clear the block, and a cached preview would still refuse.
    """
    invite = await hh.invite_for(db, user.id)
    if invite is None:
        return None
    source = await hh.household_of(db, user.id)
    inviter = await db.get(User, invite.invited_by_user_id)
    counts = await hh.merge_preview(db, source.id)
    conflicts = await hh.merge_conflicts(db, source.id, invite.household_id, user.id)
    return InviteOut(
        household_id=invite.household_id,
        invited_by_name=inviter.name if inviter else "",
        invited_by_email=inviter.email if inviter else "",
        preview=MergePreview(**counts, conflicts=conflicts),
    )


@router.post("/accept", response_model=HouseholdOut)
async def accept(user: CurrentUser, db: Db):
    """Merge your catalogue into theirs. **Irreversible** — 409s with the blocking codes if both
    households use the same bin code or NFC tag."""
    await hh.accept_invite(db, user)
    return await _snapshot(db, user)


@router.post("/decline", status_code=status.HTTP_204_NO_CONTENT)
async def decline(user: CurrentUser, db: Db):
    await hh.decline_invite(db, user.id)


@router.delete("/invites/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(user_id: uuid.UUID, user: CurrentUser, db: Db):
    """Withdraw an invitation you sent. Distinct from removing a member, which they are not."""
    await hh.revoke_invite(db, user, user_id)


@router.post("/transfer/{user_id}", response_model=HouseholdOut)
async def transfer(user_id: uuid.UUID, user: CurrentUser, db: Db):
    """Hand ownership to another member. The only way an owner can then leave."""
    await hh.transfer_ownership(db, user, user_id)
    return await _snapshot(db, user)


@router.delete("/members/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove(user_id: uuid.UUID, user: CurrentUser, db: Db):
    """Remove a member. They keep their account and get an empty catalogue; the bins stay here."""
    await hh.remove_member(db, user, user_id)


@router.post("/leave", status_code=status.HTTP_204_NO_CONTENT)
async def leave(user: CurrentUser, db: Db):
    """Leave, forfeiting access to the shared catalogue. See `leave_household`."""
    await hh.leave_household(db, user)
