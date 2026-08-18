import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

# Seeded on first login. Rows, not a Python enum: this vocabulary is the user's and will change
# (they will add "camping gear" and never touch "documents"). A code-level enum would make an
# ordinary edit a deploy.
DEFAULT_CATEGORIES = (
    "Christmas / seasonal decor",
    "Clothing",
    # Its own domain rather than a corner of Clothing: a household's baby things are cot sheets,
    # a monitor, bottles and a bouncer as much as they are sleepsuits, and they leave the house
    # together when they leave at all.
    "Baby",
    "Electronics",
    "Vintage games",
    "Tools",
    "Kitchen",
    "Books",
    "Documents",
    "Toys",
    "Sporting goods",
    "Craft / hobby",
)


class Category(Base):
    __tablename__ = "categories"
    # Scoped to the household, not the creator: the vocabulary is the household's, and two
    # "Christmas / seasonal decor" rows would appear the moment a second member's first login
    # seeded DEFAULT_CATEGORIES.
    __table_args__ = (
        UniqueConstraint("household_id", "name", name="uq_categories_household_name"),
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
    name: Mapped[str] = mapped_column(String(80))
    icon: Mapped[str | None] = mapped_column(String(48), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
