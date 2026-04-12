"""Analysis router — unused entities, import health, deprecation, batch operations."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import analysis as analysis_svc

router = APIRouter()


@router.get("/{board_id}/unused")
def find_unused(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return analysis_svc.find_unused_entities(board_svc.get_board_dir(board_id))


@router.get("/{board_id}/deprecated")
def find_deprecated(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return analysis_svc.find_deprecated(board_svc.get_board_dir(board_id))


@router.get("/{board_id}/import-health")
def check_import_health(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return analysis_svc.check_import_health(board_svc.get_board_dir(board_id))


@router.get("/{board_id}/circular-deps")
def detect_circular(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return analysis_svc.detect_circular_imports(board_svc.get_board_dir(board_id))


class BatchAnnotateBody(BaseModel):
    entity_iris: list[str]
    property_iri: str
    value: str
    language: str | None = None


@router.post("/{board_id}/batch-annotate")
def batch_annotate(board_id: str, body: BatchAnnotateBody,
                    db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    count = analysis_svc.batch_add_annotation(
        board_svc.get_board_dir(board_id), body.entity_iris, body.property_iri, body.value, body.language,
    )
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Batch annotated {count} entities")
    return {"annotated": count}
