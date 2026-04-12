"""ODK configuration and workflow router."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import odk_config as odk_cfg

router = APIRouter()


@router.get("/{board_id}/config")
def get_config(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return odk_cfg.get_config(board_svc.get_board_dir(board_id)) or {}


class ConfigBody(BaseModel):
    config: dict


@router.put("/{board_id}/config")
def save_config(board_id: str, body: ConfigBody, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    odk_cfg.save_config(board_svc.get_board_dir(board_id), body.config)
    board_svc.git_commit(board_svc.get_board_dir(board_id), "Updated ODK config")
    return {"success": True}


@router.get("/{board_id}/targets")
def list_targets(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return odk_cfg.list_makefile_targets(board_svc.get_board_dir(board_id))


@router.get("/{board_id}/changelog")
def get_changelog(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return {"changelog": odk_cfg.generate_changelog(board_svc.get_board_dir(board_id))}


@router.get("/{board_id}/ci-yaml")
def get_ci_yaml(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return {"yaml": odk_cfg.generate_ci_yaml(board_id)}
