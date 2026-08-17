import datetime
import uuid

from sqlalchemy import (
    JSON,
    Boolean,
    Computed,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import TSVECTOR
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base

# Where the thing is, conceptually. `stored` is the only status that may carry a
# current_tote_id; the movement service enforces that and tests assert it.
ITEM_STATUSES = ("stored", "out", "loaned", "disposed")
ITEM_CONDITIONS = ("new", "like_new", "good", "fair", "poor")
# Why it left the tote. Kept separate from `status` because "out for the holidays" and
# "outgrown, waiting to be handed down" are the same status and completely different questions.
OUT_REASONS = ("unpacked", "outgrown", "loaned", "in_use", "unfiled", "other")


class Item(Base):
    __tablename__ = "items"
    # Per user, not global: capture ids are generated on the phone, and one household's client
    # must never be able to collide with another's. Postgres treats NULLs as distinct in a
    # unique constraint, so every manually-added item (capture_id NULL) is unaffected.
    __table_args__ = (UniqueConstraint("user_id", "capture_id", name="uq_items_user_capture"),)

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

    # Which bag inside the tote, if any. NOT whereabouts — that is `current_tote_id`, and a
    # container carrying its own would give this app two answers to the one question it exists
    # to answer. Cleared whenever the item leaves the tote; see services/movement.py.
    container_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("containers.id", ondelete="SET NULL"), nullable=True, index=True
    )
    out_reason: Mapped[str | None] = mapped_column(String(16), nullable=True)
    out_since: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    expected_back: Mapped[datetime.date | None] = mapped_column(Date, nullable=True)

    # --- Draft state (migration 0002) -----------------------------------------------------
    # A scanned item is a DRAFT until a human confirms it. Excluded from search and from a
    # tote's contents until then: the house rule is that nothing AI-generated enters the catalog
    # without explicit approval, and a draft appearing in search would be exactly that.
    is_draft: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    # Where the draft is HEADED, not where it is. Applied only on confirmation, which is what
    # writes the `initial` movement row.
    draft_tote_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("totes.id", ondelete="SET NULL"), nullable=True
    )
    # "identify_unavailable" when the model could not be reached. Deliberately distinct from a
    # low-confidence draft: one means the photo was hard, the other means the server is
    # misconfigured, and they need different responses from a human.
    scan_error: Mapped[str | None] = mapped_column(String(32), nullable=True)
    scan_confidence: Mapped[str | None] = mapped_column(String(8), nullable=True)
    # The client's queue-row id, carried so a REPLAYED upload resolves to the draft it already
    # made instead of filing the object a second time (migration 0003).
    #
    # `/items/scan` runs for tens of seconds and commits before it answers, so a client that
    # loses the connection cannot tell a lost request from a lost response — and the capture
    # queue's stranded-row recovery re-sends. Measured in production 2026-08-16: one photograph
    # became four drafts, and duplicates in a catalog are indistinguishable from two real
    # objects. Null for anything not scanned (manual items), which is why the uniqueness lives
    # in a constraint that tolerates nulls rather than on the column.
    capture_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    processed_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

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

    # `lazy="selectin"`, not the default, and this is load-bearing rather than a tuning choice.
    #
    # Under asyncio a lazy load raises MissingGreenlet, and Pydantic's `from_attributes` touches
    # every field — so the moment this relationship existed, `DraftOut.model_validate(item)` blew
    # up on a path that never mentions apparel at all. Eager-loading at the relationship means a
    # read path cannot forget: one extra query per result set (this is one-to-one and small), and
    # no way to reintroduce the fault by adding an endpoint that omits an `.options()`.
    #
    # `delete-orphan` matters too — clearing an item's clothing specifics must actually remove the
    # row, not leave an orphan the next read would resurrect.
    apparel: Mapped["ItemApparel | None"] = relationship(
        back_populates="item",
        uselist=False,
        lazy="selectin",
        cascade="all, delete-orphan",
        passive_deletes=True,
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
    item: Mapped["Item"] = relationship(back_populates="apparel")
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
