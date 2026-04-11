"""ID ranges router."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import idranges as idrange_svc

router = APIRouter()


@router.get("/{board_id}")
def list_ranges(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return idrange_svc.parse_idranges(DATA_DIR / board_id)


class AllocateBody(BaseModel):
    prefix: str = ""


@router.post("/{board_id}/allocate", status_code=201)
def allocate_range(board_id: str, body: AllocateBody,
                    db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    result = idrange_svc.allocate_range(DATA_DIR / board_id, user.username, body.prefix)
    board_svc.git_commit(DATA_DIR / board_id, f"Allocated ID range for {user.username}")
    return result


@router.post("/{board_id}/reserve")
def reserve_id(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    iri = idrange_svc.reserve_next_id(DATA_DIR / board_id, user.username)
    if not iri:
        raise HTTPException(status_code=409, detail="No available IDs in range")
    return {"iri": iri}
