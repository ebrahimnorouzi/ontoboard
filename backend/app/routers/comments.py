"""Comments router — board and entity-level comments with @mention notifications and replies."""

import re
import datetime
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.models.comment import Comment
from app.services import board as board_svc
from app.services import notification as notif_svc

router = APIRouter()


class CommentCreate(BaseModel):
    text: str
    entity_iri: str | None = None
    parent_id: int | None = None  # reply to another comment


class CommentOut(BaseModel):
    id: int
    board_id: int
    entity_iri: str | None
    user_id: int
    username: str
    text: str
    created_at: datetime.datetime
    parent_id: int | None = None
    replies: list["CommentOut"] = []

    model_config = {"from_attributes": True}


def _parse_mentions(text: str) -> list[str]:
    """Extract @username patterns from comment text."""
    return re.findall(r"@(\w+)", text)


@router.get("/{board_id}", response_model=list[CommentOut])
def list_comments(
    board_id: str,
    entity_iri: str | None = Query(None),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """List all comments for a board, optionally filtered by entity IRI.

    Returns threaded comments — top-level comments with nested replies.
    """
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")

    q = db.query(Comment).filter(Comment.board_id == board.id)
    if entity_iri is not None:
        q = q.filter(Comment.entity_iri == entity_iri)
    all_comments = q.order_by(Comment.created_at.asc()).all()

    # Build threaded structure
    comment_map: dict[int, CommentOut] = {}
    top_level: list[CommentOut] = []

    for c in all_comments:
        out = CommentOut(
            id=c.id,
            board_id=c.board_id,
            entity_iri=c.entity_iri,
            user_id=c.user_id,
            username=c.user.username if c.user else "unknown",
            text=c.text,
            created_at=c.created_at,
            parent_id=c.parent_id,
            replies=[],
        )
        comment_map[c.id] = out

    for c in all_comments:
        out = comment_map[c.id]
        if c.parent_id and c.parent_id in comment_map:
            comment_map[c.parent_id].replies.append(out)
        else:
            top_level.append(out)

    return top_level


@router.post("/{board_id}", response_model=CommentOut, status_code=201)
def create_comment(
    board_id: str,
    body: CommentCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Create a comment. Parses @mentions and creates notifications."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")

    comment = Comment(
        board_id=board.id,
        entity_iri=body.entity_iri,
        user_id=user.id,
        text=body.text,
        parent_id=body.parent_id,
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
                link=f"/board/{board.board_id}",
            )

    # If replying, notify the parent comment author
    if body.parent_id:
        parent = db.query(Comment).filter(Comment.id == body.parent_id).first()
        if parent and parent.user_id != user.id:
            notif_svc.create_notification(
                db,
                parent.user_id,
                category="mention",
                title=f"{user.username} replied to your comment",
                message=body.text[:200],
                link=f"/board/{board.board_id}",
            )

    return CommentOut(
        id=comment.id,
        board_id=comment.board_id,
        entity_iri=comment.entity_iri,
        user_id=comment.user_id,
        username=user.username,
        text=comment.text,
        created_at=comment.created_at,
        parent_id=comment.parent_id,
        replies=[],
    )


@router.delete("/{board_id}/{comment_id}")
def delete_comment(
    board_id: str,
    comment_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Delete own comment."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    comment = db.query(Comment).filter(
        Comment.id == comment_id,
        Comment.board_id == board.id,
    ).first()
    if not comment:
        raise HTTPException(status_code=404, detail="Comment not found")
    if comment.user_id != user.id:
        raise HTTPException(status_code=403, detail="Can only delete your own comments")
    db.delete(comment)
    db.commit()
    return {"success": True}
