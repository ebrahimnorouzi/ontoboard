"""Invite link service — create, validate, accept, revoke."""

import secrets
import datetime

from sqlalchemy.orm import Session

from app.models.invite import InviteLink
from app.models.board import Board, BoardMember
from app.models.user import User


def create_invite(
    db: Session,
    board: Board,
    creator: User,
    role: str = "viewer",
    expires_hours: int | None = None,
    max_uses: int = 0,
) -> InviteLink:
    token = secrets.token_urlsafe(24)
    expires_at = None
    if expires_hours is not None and expires_hours > 0:
        expires_at = datetime.datetime.utcnow() + datetime.timedelta(hours=expires_hours)

    invite = InviteLink(
        board_id=board.id,
        token=token,
        role=role,
        created_by=creator.id,
        expires_at=expires_at,
        max_uses=max_uses,
        use_count=0,
        is_active=True,
    )
    db.add(invite)
    db.commit()
    db.refresh(invite)
    return invite


def get_invite_by_token(db: Session, token: str) -> InviteLink | None:
    return db.query(InviteLink).filter(InviteLink.token == token).first()


def list_active_invites(db: Session, board: Board) -> list[InviteLink]:
    return (
        db.query(InviteLink)
        .filter(InviteLink.board_id == board.id, InviteLink.is_active.is_(True))
        .order_by(InviteLink.created_at.desc())
        .all()
    )


def revoke_invite(db: Session, invite: InviteLink) -> None:
    invite.is_active = False
    db.commit()


def is_valid(invite: InviteLink) -> bool:
    """Check if an invite link is still valid."""
    if not invite.is_active:
        return False
    if invite.expires_at is not None and datetime.datetime.utcnow() > invite.expires_at:
        return False
    if invite.max_uses > 0 and invite.use_count >= invite.max_uses:
        return False
    return True


def accept_invite(db: Session, invite: InviteLink, user: User) -> Board:
    """Accept an invite — add user to the board, increment use_count."""
    board = invite.board

    # Check if already a member
    existing = (
        db.query(BoardMember)
        .filter(BoardMember.board_id == board.id, BoardMember.user_id == user.id)
        .first()
    )
    if not existing:
        member = BoardMember(board_id=board.id, user_id=user.id, role=invite.role)
        db.add(member)

    invite.use_count += 1
    db.commit()
    db.refresh(board)
    return board
