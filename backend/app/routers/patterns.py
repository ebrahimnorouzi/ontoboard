"""ODP Pattern Library router."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import patterns as pattern_svc

router = APIRouter()


@router.get("/")
def list_patterns():
    """List all available patterns."""
    return pattern_svc.list_patterns()


@router.get("/{pattern_id}")
def get_pattern(pattern_id: str):
    """Get full pattern structure."""
    p = pattern_svc.get_pattern(pattern_id)
    if not p:
        raise HTTPException(status_code=404, detail="Pattern not found")
    return p


class ApplyBody(BaseModel):
    base_iri: str = "http://example.org/ontology"
    x: float = 100
    y: float = 100


@router.post("/{board_id}/apply/{pattern_id}", status_code=201)
def apply_pattern(board_id: str, pattern_id: str, body: ApplyBody,
                   db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Apply a pattern to a board (creates entities, returns CanvasState)."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = pattern_svc.apply_pattern(DATA_DIR / board_id, pattern_id, body.base_iri, body.x, body.y)
    board_svc.log_activity(db, board, user, "pattern_applied", f"Pattern: {pattern_id}")
    return result
