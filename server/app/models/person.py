import datetime
import uuid

from sqlalchemy import Date, DateTime, ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

GARMENT_TYPES = ("tops", "bottoms", "shoes", "outerwear")


class Person(Base):
    """Who a size belongs to, or who borrowed something. Household members and lendees share
    one table: both answer "where did this go and whose is it"."""

    __tablename__ = "people"

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
    birthdate: Mapped[datetime.date | None] = mapped_column(Date, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class PersonSize(Base):
    """A person's size in one garment type, as of a date.

    A HISTORY, not a current value. A child's size is a moving target, and last winter's answer
    is exactly what tells you which bin to open next winter — so rows accumulate and the newest
    `effective_from` wins rather than being overwritten.
    """

    __tablename__ = "person_sizes"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    person_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("people.id", ondelete="CASCADE"), index=True
    )
    garment_type: Mapped[str] = mapped_column(String(16))
    # size_raw is what a human said or read; system/ordinal are the derived index over it and
    # are nullable because an unparseable size must still be storable (app/sizing, Phase 5).
    size_raw: Mapped[str] = mapped_column(String(32))
    size_system: Mapped[str | None] = mapped_column(String(24), nullable=True)
    size_ordinal: Mapped[float | None] = mapped_column(nullable=True)
    effective_from: Mapped[datetime.date] = mapped_column(Date)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
