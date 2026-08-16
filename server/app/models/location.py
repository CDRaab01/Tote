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
    __table_args__ = (UniqueConstraint("user_id", "name", name="uq_locations_user_name"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(80))
    parent_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("locations.id", ondelete="SET NULL"), nullable=True
    )
    sort_order: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
