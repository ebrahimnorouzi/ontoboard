"""Comments router — board and entity-level comments with @mention notifications."""

import re
import datetime
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.models.board import Board
from app.models.comment import Comment
from app.services import notification as notif_svc

router = APIRouter()


class CommentCreate(BaseModel):
    text: str
    entity_iri: str | None = None


class CommentOut(BaseModel):
    id: int
    board_id: int
    entity_iri: str | None
    user_id: int
    username: str
    text: str
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


def _parse_mentions(text: str) -> list[str]:
    """Extract @username patterns from comment text."""
    return re.findall(r"@(\w+)", text)


def _resolve_board(db: Session, board_id: int) -> Board:
    board = db.query(Board).filter(Board.id == board_id).first()
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return board


@router.get("/{board_id}", response_model=list[CommentOut])
def list_comments(
    board_id: int,
    entity_iri: str | None = Query(None),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """List all comments for a board, optionally filtered by entity IRI."""
    _resolve_board(db, board_id)
    q = db.query(Comment).filter(Comment.board_id == board_id)
    if entity_iri is not None:
        q = q.filter(Comment.entity_iri == entity_iri)
    comments = q.order_by(Comment.created_at.asc()).all()
    return [
        CommentOut(
            id=c.id,
            board_id=c.board_id,
            entity_iri=c.entity_iri,
            user_id=c.user_id,
            username=c.user.username if c.user else "unknown",
            text=c.text,
            created_at=c.created_at,
        )
        for c in comments
    ]


@router.post("/{board_id}", response_model=CommentOut, status_code=201)
def create_comment(
    board_id: int,
    body: CommentCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Create a comment. Parses @mentions and creates notifications."""
    board = _resolve_board(db, board_id)

    comment = Comment(
        board_id=board.id,
        entity_iri=body.entity_iri,
        user_id=user.id,
        text=body.text,
    )
    db.add(comment)
    db.commit()
    db.refresh(comment)

    # Parse @mentions and notify mentioned users
    mentioned_usernames = _parse_mentions(body.text)
    for username in set(mentioned_usernames):
        mentioned_user = db.query(User).filter(User.username == username).first()
        if mentioned_user and mentioned_user.id != user.id:
            entity_label = body.entity_iri or "board"
            notif_svc.create_notification(
                db,
                mentioned_user.id,
                category="mention",
                title=f"{user.username} mentioned you in a comment",
                message=f"On {entity_label}: {body.text[:200]}",
                link=f"/boards/{board.board_id}",
            )

    return CommentOut(
        id=comment.id,
        board_id=comment.board_id,
        entity_iri=comment.entity_iri,
        user_id=comment.user_id,
        username=user.username,
        text=comment.text,
        created_at=comment.created_at,
    )


@router.delete("/{board_id}/{comment_id}")
def delete_comment(
    board_id: int,
    comment_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Delete own comment."""
    comment = db.query(Comment).filter(
        Comment.id == comment_id,
        Comment.board_id == board_id,
    ).first()
    if not comment:
        raise HTTPException(status_code=404, detail="Comment not found")
    if comment.user_id != user.id:
        raise HTTPException(status_code=403, detail="Can only delete your own comments")
    db.delete(comment)
    db.commit()
    return {"success": True}
