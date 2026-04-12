"""Version management router."""

from pydantic import BaseModel
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.version import VersionInfo, VersionUpdate
from app.services import board as board_svc
from app.services import version as version_svc
from app.services import odk_setup

router = APIRouter()


@router.get("/{board_id}", response_model=VersionInfo)
def get_version(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    try:
        return VersionInfo(**version_svc.get_version_info(board_svc.get_board_dir(board_id)))
    except FileNotFoundError:
        return VersionInfo()


@router.put("/{board_id}", response_model=VersionInfo)
def set_version(board_id: str, body: VersionUpdate, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = version_svc.set_version(board_svc.get_board_dir(board_id), body.version_iri, body.version, body.bump)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Updated version: {result.get('version', '')}")
    board_svc.log_activity(db, board, user, "version_updated", str(result.get("version", "")))
    return VersionInfo(**result)


# ── Versioning Strategy ──────────────────────────────────────

class StrategyBody(BaseModel):
    strategy: str  # "date" or "semantic"


@router.get("/{board_id}/strategy")
def get_strategy(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Get the versioning strategy for a board (date or semantic)."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    strategy = odk_setup.get_versioning_strategy(board_svc.get_board_dir(board_id))
    return {"strategy": strategy}


@router.put("/{board_id}/strategy")
def set_strategy(board_id: str, body: StrategyBody, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Update the versioning strategy and regenerate release.sh."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    if body.strategy not in ("date", "semantic"):
        raise HTTPException(status_code=400, detail="Strategy must be 'date' or 'semantic'")
    result = odk_setup.set_versioning_strategy(board_svc.get_board_dir(board_id), body.strategy, board_id)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Changed versioning strategy to {body.strategy}")
    board_svc.log_activity(db, board, user, "strategy_updated", body.strategy)
    return {"strategy": result}
