"""Version management router."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.version import VersionInfo, VersionUpdate
from app.services import board as board_svc
from app.services import version as version_svc

router = APIRouter()


@router.get("/{board_id}", response_model=VersionInfo)
def get_version(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    try:
        return VersionInfo(**version_svc.get_version_info(DATA_DIR / board_id))
    except FileNotFoundError:
        return VersionInfo()


@router.put("/{board_id}", response_model=VersionInfo)
def set_version(board_id: str, body: VersionUpdate, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = version_svc.set_version(DATA_DIR / board_id, body.version_iri, body.version, body.bump)
    board_svc.git_commit(DATA_DIR / board_id, f"Updated version: {result.get('version', '')}")
    board_svc.log_activity(db, board, user, "version_updated", str(result.get("version", "")))
    return VersionInfo(**result)
