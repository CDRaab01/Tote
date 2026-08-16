import datetime
import uuid

from sqlalchemy import (
    JSON,
    Computed,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import TSVECTOR
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

# Where the thing is, conceptually. `stored` is the only status that may carry a
# current_tote_id; the movement service enforces that and tests assert it.
ITEM_STATUSES = ("stored", "out", "loaned", "disposed")
ITEM_CONDITIONS = ("new", "like_new", "good", "fair", "poor")
# Why it left the tote. Kept separate from `status` because "out for the holidays" and
# "outgrown, waiting to be handed down" are the same status and completely different questions.
OUT_REASONS = ("unpacked", "outgrown", "loaned", "in_use", "other")


class Item(Base):
    __tablename__ = "items"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(160))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    category_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("categories.id", ondelete="SET NULL"), nullable=True, index=True
    )
    # A tote holds "4x ornament box". Forcing four rows is worse than a count: it makes every
    # move a four-row operation and the UI a list of identical lines.
    quantity: Mapped[int] = mapped_column(Integer, default=1, server_default="1")
    condition: Mapped[str | None] = mapped_column(String(16), nullable=True)

    # --- Derived state: written ONLY by app/services/movement.py --------------------------
    status: Mapped[str] = mapped_column(String(16), default="stored", server_default="stored")
    current_tote_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("totes.id", ondelete="SET NULL"), nullable=True, index=True
    )
    out_reason: Mapped[str | None] = mapped_column(String(16), nullable=True)
    out_since: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    expected_back: Mapped[datetime.date | None] = mapped_column(Date, nullable=True)

    value_est: Mapped[float | None] = mapped_column(Numeric(10, 2), nullable=True)
    acquired_at: Mapped[datetime.date | None] = mapped_column(Date, nullable=True)

    # Postgres full-text, the app's primary query path ("where is the ratchet set"). A STORED
    # generated column rather than a trigger: to_tsvector with a literal regconfig is immutable,
    # so Postgres maintains it, and Computed() means both alembic and metadata.create_all agree
    # on the definition. It covers this row's own text only — category name lives in another
    # table and a generated column cannot join, so category is a filter, not a search term.
    search_vector: Mapped[str | None] = mapped_column(
        TSVECTOR,
        Computed(
            "to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, '')"
            " || ' ' || coalesce(notes, ''))",
            persisted=True,
        ),
        nullable=True,
    )

    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class ItemApparel(Base):
    """Clothing specifics, a one-to-one nullable extension of `items`.

    A separate table rather than eleven mostly-null columns on every ratchet set and board game.
    The vocabularies come from app/apparel (copied from Crate at Phase 5); `size_raw` is what the
    tag literally said and is never rewritten, while system/ordinal are a derived index that is
    allowed to be null when the tag is unparseable.
    """

    __tablename__ = "item_apparel"

    item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("items.id", ondelete="CASCADE"), primary_key=True
    )
    size_raw: Mapped[str | None] = mapped_column(String(32), nullable=True)
    size_system: Mapped[str | None] = mapped_column(String(24), nullable=True)
    size_ordinal: Mapped[float | None] = mapped_column(nullable=True)
    department: Mapped[str | None] = mapped_column(String(16), nullable=True)
    size_type: Mapped[str | None] = mapped_column(String(16), nullable=True)
    color: Mapped[str | None] = mapped_column(String(48), nullable=True)
    material: Mapped[str | None] = mapped_column(String(96), nullable=True)
    style: Mapped[str | None] = mapped_column(String(48), nullable=True)
    fit: Mapped[str | None] = mapped_column(String(16), nullable=True)
    sleeve_length: Mapped[str | None] = mapped_column(String(16), nullable=True)
    # Inches, garment laid flat. Inches because that is what tape measures and garment tags in
    # this house read in — the raw reading is the canonical value, converted at the edges.
    measurements_in: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    season: Mapped[str | None] = mapped_column(String(8), nullable=True)


class ItemPhoto(Base):
    __tablename__ = "item_photos"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("items.id", ondelete="CASCADE"), index=True
    )
    # NEVER renumbered: photo_store derives on-disk filenames from this integer, so rewriting it
    # orphans files (Crate's rule, inherited deliberately). Presentation order is a sort at read
    # time, not a mutation.
    order: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    original_path: Mapped[str] = mapped_column(String(255))
    cleaned_path: Mapped[str | None] = mapped_column(String(255), nullable=True)
    role: Mapped[str | None] = mapped_column(String(16), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
