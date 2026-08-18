import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Container(Base):
    """A bag, box or pouch **inside** a tote — one level of grouping within a bin.

    A real bin is not a flat pile. A tote of baby clothes is three zip bags and a loose blanket,
    and "which bag is the 3-6M one" is a question the catalogue could not answer at all.

    ## It is a label, not a location

    The container belongs to exactly one tote and **does not carry whereabouts**. An item's
    location is `items.current_tote_id`, full stop — `container_id` only says which bag inside
    that tote it sits in. That is deliberate and it is the whole design:

    A movable container would need its own `tote_id`, which the item's `current_tote_id` could
    then contradict, and nothing would fail loudly when they drifted. Two sources of truth for
    where a thing is, in the one app whose entire promise is answering that. So a bag does not
    move as a unit; its items move, one movement row each, through the single writer that has
    always owned this.

    Taking an item out of a tote therefore clears its `container_id` (see `services/movement.py`)
    — an item that is out of the bin is not in a bag inside it.

    ## Notes earn their place

    `notes` exists because a bag is often *approximately* catalogued: "mostly 3-6M onesies, some
    vests". That is worth recording even when the individual garments are not, and it is the
    difference between knowing what a bag is for and having to open it.
    """

    __tablename__ = "containers"

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
    # CASCADE, not SET NULL: a bag has no meaning outside the bin it is in. Its ITEMS survive —
    # `items.container_id` is SET NULL — so deleting a bin loses the grouping and never the
    # contents.
    tote_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("totes.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(80))
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
