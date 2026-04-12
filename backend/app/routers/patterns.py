"""ODP Pattern Library router."""

from fastapi import APIRouter, Depends, HTTPException, Form, UploadFile, File
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


@router.post("/upload", status_code=201)
async def upload_pattern(
    name: str = Form(...),
    description: str = Form(""),
    category: str = Form("structural"),
    scope: str = Form(""),
    competency_questions: str = Form(""),
    references: str = Form(""),
    pattern_iri: str = Form(""),
    file: UploadFile | None = File(None),
    user: User = Depends(get_current_user),
):
    """Upload a new ODP pattern with metadata."""
    pattern_id = name.lower().replace(" ", "-").replace("/", "-")
    # Check for duplicate
    if pattern_svc.get_pattern(pattern_id):
        raise HTTPException(status_code=409, detail="Pattern with this name already exists")

    file_content = None
    if file:
        file_content = (await file.read()).decode("utf-8", errors="replace")

    new_pattern = pattern_svc.add_pattern(
        pattern_id=pattern_id,
        name=name,
        description=description,
        category=category,
        scope=scope,
        competency_questions=competency_questions,
        references=references,
        pattern_iri=pattern_iri,
        owl_content=file_content,
    )
    return new_pattern
