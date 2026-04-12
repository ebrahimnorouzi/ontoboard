"""Refactoring router — rename IRI, move entity, undo/redo."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.refactor import RenameRequest, MoveRequest
from app.services import board as board_svc
from app.services import refactor as refactor_svc

router = APIRouter()


@router.put("/{board_id}/rename")
def rename_iri(
    board_id: str, body: RenameRequest,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = refactor_svc.rename_iri(board_svc.get_board_dir(board_id), body.old_iri, body.new_iri)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Renamed {body.old_iri} → {body.new_iri}")
    board_svc.log_activity(db, board, user, "renamed", f"{body.old_iri} → {body.new_iri}")
    return result


@router.put("/{board_id}/move")
def move_entity(
    board_id: str, body: MoveRequest,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = refactor_svc.move_entity(board_svc.get_board_dir(board_id), body.entity_iri, body.new_parent_iri, body.old_parent_iri)
    if not ok:
        raise HTTPException(status_code=400, detail="Move failed")
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Moved {body.entity_iri} to parent {body.new_parent_iri}")
    return {"success": True}


@router.post("/{board_id}/undo")
def undo(
    board_id: str,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = refactor_svc.undo(board_svc.get_board_dir(board_id))
    if not ok:
        return {"success": False, "detail": "Nothing to undo"}
    board_svc.git_commit(board_svc.get_board_dir(board_id), "Undo")
    return {"success": True}


@router.post("/{board_id}/redo")
def redo(
    board_id: str,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = refactor_svc.redo(board_svc.get_board_dir(board_id))
    if not ok:
        return {"success": False, "detail": "Nothing to redo"}
    board_svc.git_commit(board_svc.get_board_dir(board_id), "Redo")
    return {"success": True}
