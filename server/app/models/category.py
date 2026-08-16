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
    __table_args__ = (UniqueConstraint("user_id", "name", name="uq_categories_user_name"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(80))
    icon: Mapped[str | None] = mapped_column(String(48), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
