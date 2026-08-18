import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class User(Base):
    """SSO find-or-create by email (Magpie/Crate precedent). No password hash column — Tote is
    SSO-only and the suite identity server owns credentials."""

    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255))
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    # `lazy="selectin"` for the same reason `Item.apparel` has it: under asyncio a lazy load
    # raises MissingGreenlet, and `user.household_id` is read on essentially every request. One
    # extra small query on the auth path, and no way for a new endpoint to forget an `.options()`.
    membership: Mapped["HouseholdMember | None"] = relationship(  # noqa: F821
        "HouseholdMember", lazy="selectin", uselist=False, viewonly=True
    )

    @property
    def household_id(self) -> uuid.UUID:
        """The catalogue this user may see. **The access key for every query in the app.**

        Not optional and deliberately not defensive: a household of one is created at first
        login and migration 0006 back-filled every account that predates it, so a user without
        one is a broken invariant rather than a state to handle. Returning `None` here would
        turn that into a silent empty catalogue — the single worst failure this app has, because
        "your attic is empty" reads as data loss.
        """
        if self.membership is None:
            raise RuntimeError(
                f"user {self.id} has no household membership — "
                "every user gets a household of one at first login (see services/suite_auth.py)"
            )
        return self.membership.household_id


class UserSettings(Base):
    """Per-user preferences, seeded on first login so every reader has a value to read."""

    __tablename__ = "user_settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    default_location_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("locations.id", ondelete="SET NULL"), nullable=True
    )
    # `nfc_uri_base` used to live here and now lives on `households` (migration 0006). It was
    # never read, which is the only reason moving it was cheap — but per-user it was wrong by
    # construction: two members writing different bases produce bins that open for one person
    # and not the other, discoverable only by walking to the attic.
    ntfy_topic: Mapped[str | None] = mapped_column(String(128), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
