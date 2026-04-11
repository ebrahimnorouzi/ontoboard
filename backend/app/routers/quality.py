"""Quality evaluation and registry compliance router."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import quality as quality_svc
from app.services import registry as registry_svc

router = APIRouter()


@router.post("/{board_id}/oops")
def run_oops(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    board_svc.log_activity(db, board, user, "oops_scan", "OOPS! scan started")
    return quality_svc.run_oops_scan(DATA_DIR / board_id)


@router.get("/{board_id}/oquare")
def get_oquare(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return quality_svc.calculate_oquare(DATA_DIR / board_id)


@router.get("/{board_id}/compliance/{registry}")
def check_compliance(board_id: str, registry: str,
                      db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return quality_svc.check_registry_compliance(DATA_DIR / board_id, registry)


class RegistrySubmitBody(BaseModel):
    registry: str  # bioportal | ols | lov
    api_key: str = ""


@router.post("/{board_id}/registry/submit")
def submit_to_registry(board_id: str, body: RegistrySubmitBody,
                        db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Owner or admin required")

    if body.registry == "bioportal":
        result = registry_svc.submit_to_bioportal(DATA_DIR / board_id, body.api_key)
    elif body.registry == "ols":
        result = {"config": registry_svc.generate_ols_config(DATA_DIR / board_id)}
    elif body.registry == "lov":
        result = registry_svc.generate_lov_metadata(DATA_DIR / board_id)
    else:
        raise HTTPException(status_code=400, detail=f"Unknown registry: {body.registry}")

    board_svc.log_activity(db, board, user, "registry_submit", f"Registry: {body.registry}")
    return result
