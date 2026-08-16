import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

# Why an item moved. `corrected` is for fixing a mis-entry without pretending it never happened:
# the ledger is append-only, so a correction is a row, not an edit.
MOVEMENT_REASONS = (
    "initial",
    "moved",
    "unpacked",
    "repacked",
    "outgrown",
    "loaned",
    "returned",
    "disposed",
    "corrected",
)


class Movement(Base):
    """The ledger, and the reason this app is not a spreadsheet.

    Every change of an item's whereabouts is a row here, and `items.current_tote_id` / `status`
    are DERIVED from the latest row by app/services/movement.py. Nothing else writes them.

    Append-only: rows are never updated or deleted, because the question this table exists to
    answer is "where was this last year", and an edit-in-place model cannot answer it.
    """

    __tablename__ = "movements"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("items.id", ondelete="CASCADE"), index=True
    )
    # Both nullable: null `from` is the item's first appearance, null `to` means it left every
    # tote (out for the holidays, lent, disposed).
    from_tote_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("totes.id", ondelete="SET NULL"), nullable=True
    )
    to_tote_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("totes.id", ondelete="SET NULL"), nullable=True
    )
    quantity: Mapped[int] = mapped_column(Integer, default=1, server_default="1")
    reason: Mapped[str] = mapped_column(String(16))
    # Who: the lendee, or the child who outgrew it.
    person_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("people.id", ondelete="SET NULL"), nullable=True
    )
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Separate from created_at on purpose: you catalogue the Christmas unpack in January, and
    # the ledger should say when it happened, not when you got round to recording it.
    moved_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True
    )
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
