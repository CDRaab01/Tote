import datetime
import uuid

from sqlalchemy import Boolean, DateTime, ForeignKey, String, Text, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Tote(Base):
    """A physical storage bin.

    `code` is the short label written on the index card and encoded in the NFC tag ("A14"). It
    is unique **per household** and compared case-insensitively, because a duplicate is not a
    database inconvenience — it is two bins in an attic that claim to be the same bin. The
    uniqueness is enforced on lower(code) by migration 0001 (rescoped by 0006), not by this
    class, so "a14" and "A14" collide.

    `item_count` is deliberately absent: it is computed on read. A stored count is the first
    thing to drift, and here the drift is visible on a printed card and a written tag.
    """

    __tablename__ = "totes"
    # Household-scoped: one physical sticker belongs to one bin. Per-user, two members could
    # each claim the same tag and a tap would have two answers.
    __table_args__ = (
        UniqueConstraint("household_id", "nfc_tag_uid", name="uq_totes_household_tag"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    # WHO MAY SEE THIS. The access check everywhere is `household_id == user.household_id`.
    household_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("households.id", ondelete="CASCADE"), index=True
    )
    # WHO CREATED THIS — provenance only, never access. Nullable + SET NULL: a shared catalogue
    # must survive the deletion of whichever member happened to enter the row.
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True
    )
    code: Mapped[str] = mapped_column(String(16))
    label: Mapped[str | None] = mapped_column(String(120), nullable=True)
    category_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("categories.id", ondelete="SET NULL"), nullable=True
    )
    location_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("locations.id", ondelete="SET NULL"), nullable=True, index=True
    )
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Free text on purpose: "27gal clear", "banker box", "under-bed". Enumerating bin shapes
    # would be inventing a vocabulary nobody asked for.
    bin_kind: Mapped[str | None] = mapped_column(String(48), nullable=True)
    color: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # The tag's HARDWARE uid, captured at write time. This is what lets Tote say "this tag
    # belongs to A14, but you are holding B03's card" instead of trusting whatever the tag
    # claims. Unique per household: one physical tag cannot belong to two bins.
    nfc_tag_uid: Mapped[str | None] = mapped_column(String(32), nullable=True)
    nfc_written_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    card_printed_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # When a human last stood in front of this bin and confirmed the catalogue against what is
    # physically inside (the Verify flow). Null = never. The ledger records every *intentional*
    # move; this is the one defence against the moves nobody recorded.
    last_verified_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    archived: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
