import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class User(Base):
    """SSO find-or-create by email (Magpie/Crate precedent). No password hash column — Tote is
    SSO-only and the suite identity server owns credentials."""

    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255))
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class UserSettings(Base):
    """Per-user preferences, seeded on first login so every reader has a value to read."""

    __tablename__ = "user_settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    default_location_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("locations.id", ondelete="SET NULL"), nullable=True
    )
    # Overrides the deployment-wide NFC_URI_BASE. A written tag is a physical object in an
    # attic that no deploy can patch, so the value baked into tags must be changeable per user
    # without a code change.
    nfc_uri_base: Mapped[str | None] = mapped_column(String(255), nullable=True)
    ntfy_topic: Mapped[str | None] = mapped_column(String(128), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
