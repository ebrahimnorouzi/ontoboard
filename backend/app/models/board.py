"""Board and BoardMember models."""

import datetime

from sqlalchemy import String, Integer, DateTime, ForeignKey, UniqueConstraint, Boolean, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Board(Base):
    __tablename__ = "boards"

    id: Mapped[int] = mapped_column(primary_key=True)
    board_id: Mapped[str] = mapped_column(String(120), unique=True, index=True)  # slug
    display_name: Mapped[str] = mapped_column(String(200), default="")
    description: Mapped[str] = mapped_column(Text, default="")
    owner_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"))

    # Access control
    is_public: Mapped[bool] = mapped_column(Boolean, default=True)
    allow_anonymous_view: Mapped[bool] = mapped_column(Boolean, default=True)
    allow_anonymous_edit: Mapped[bool] = mapped_column(Boolean, default=False)

    # Metadata
    is_starred: Mapped[bool] = mapped_column(Boolean, default=False)
    tags: Mapped[str] = mapped_column(String(500), default="")  # comma-separated
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime, server_default=func.now()
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    # Relationships
    owner = relationship("User", back_populates="owned_boards")
    members = relationship("BoardMember", back_populates="board", cascade="all, delete-orphan")
    activities = relationship("Activity", back_populates="board", cascade="all, delete-orphan")


class BoardMember(Base):
    """Explicit share: gives a user a role on a specific board."""
    __tablename__ = "board_members"
    __table_args__ = (UniqueConstraint("board_id", "user_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    board_id: Mapped[int] = mapped_column(Integer, ForeignKey("boards.id", ondelete="CASCADE"))
    user_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id", ondelete="CASCADE"))
    role: Mapped[str] = mapped_column(String(20), default="viewer")  # "editor" | "viewer"
    added_at: Mapped[datetime.datetime] = mapped_column(
        DateTime, server_default=func.now()
    )

    board = relationship("Board", back_populates="members")
    user = relationship("User", back_populates="memberships")
