"""Notification model — persistent notifications for users."""

import datetime

from sqlalchemy import String, Boolean, DateTime, Integer, Text, ForeignKey, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Notification(Base):
    __tablename__ = "notifications"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    category: Mapped[str] = mapped_column(String(30))
    # Categories:
    #   "signup"       — new user registered (admin only)
    #   "activated"    — your account was approved
    #   "board_shared" — you were added to a board
    #   "board_update" — board you're on was updated (import, axiom, release, etc.)
    #   "mention"      — someone mentioned you
    #   "system"       — system announcements
    title: Mapped[str] = mapped_column(String(200))
    message: Mapped[str] = mapped_column(Text, default="")
    link: Mapped[str | None] = mapped_column(String(500), nullable=True)  # e.g. "/boards/myonto"
    is_read: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime, server_default=func.now()
    )

    user = relationship("User", lazy="selectin")
