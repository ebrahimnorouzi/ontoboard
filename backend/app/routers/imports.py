"""Import management router."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.imports import ImportAdd, ImportInfo
from app.services import board as board_svc
from app.services import imports as imports_svc

router = APIRouter()


@router.get("/{board_id}", response_model=list[ImportInfo])
def list_imports(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    try:
        return [ImportInfo(**i) for i in imports_svc.list_imports(board_svc.get_board_dir(board_id))]
    except FileNotFoundError:
        return []


@router.post("/{board_id}", status_code=201)
def add_import(board_id: str, body: ImportAdd, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = imports_svc.add_import(board_svc.get_board_dir(board_id), body.iri, body.prefix)
    if not ok:
        raise HTTPException(status_code=400, detail="Failed to add import")
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Added import: {body.iri}")
    board_svc.log_activity(db, board, user, "import_added", body.iri)
    return {"success": True}


@router.delete("/{board_id}/{import_iri:path}")
def remove_import(board_id: str, import_iri: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = imports_svc.remove_import(board_svc.get_board_dir(board_id), import_iri)
    if not ok:
        raise HTTPException(status_code=400, detail="Failed to remove import")
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Removed import: {import_iri}")
    return {"success": True}
