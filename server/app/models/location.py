import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Location(Base):
    """Where a tote physically is: "Attic", "Garage rack B", "Basement closet".

    A table rather than free text on the tote, because "show me everything in the attic" is a
    primary browse entry point and free text fragments into attic/Attic/the attic. One level of
    nesting (parent_id) is enough for a house; deeper trees are a UI problem, not a data one.
    """

    __tablename__ = "locations"
    # Household-scoped: there is one attic, however many people put things in it.
    __table_args__ = (UniqueConstraint("household_id", "name", name="uq_locations_household_name"),)

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
    name: Mapped[str] = mapped_column(String(80))
    parent_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("locations.id", ondelete="SET NULL"), nullable=True
    )
    sort_order: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    # Server-derived path of the one optional photo of the place itself (the shelf, the rack) —
    # set/replaced via POST /locations/{id}/photo. The DB stores the path, the volume the bytes,
    # exactly like item photographs.
    photo_path: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
