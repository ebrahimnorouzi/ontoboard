"""ID ranges router."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

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
    return idrange_svc.parse_idranges(board_svc.get_board_dir(board_id))


class AllocateBody(BaseModel):
    prefix: str = ""
    lower: str = ""
    upper: str = ""


@router.post("/{board_id}/allocate", status_code=201)
def allocate_range(board_id: str, body: AllocateBody,
                    db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    result = idrange_svc.allocate_range(board_svc.get_board_dir(board_id), user.username, body.prefix)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Allocated ID range for {user.username}")
    return result


class UpdateRangeBody(BaseModel):
    prefix: str | None = None
    lower: str | None = None
    upper: str | None = None


@router.put("/{board_id}/{owner}")
def update_range(board_id: str, owner: str, body: UpdateRangeBody,
                  db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Update an existing ID range (prefix, bounds)."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    result = idrange_svc.update_range(
        board_svc.get_board_dir(board_id), owner,
        prefix=body.prefix, lower=body.lower, upper=body.upper,
    )
    if not result:
        raise HTTPException(status_code=404, detail="Range not found for user")
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Updated ID range for {owner}")
    return result


@router.delete("/{board_id}/{owner}")
def delete_range(board_id: str, owner: str,
                  db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Delete an ID range."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    deleted = idrange_svc.delete_range(board_svc.get_board_dir(board_id), owner)
    if not deleted:
        raise HTTPException(status_code=404, detail="Range not found for user")
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Deleted ID range for {owner}")
    return {"deleted": True}


@router.post("/{board_id}/reserve")
def reserve_id(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    iri = idrange_svc.reserve_next_id(board_svc.get_board_dir(board_id), user.username)
    if not iri:
        raise HTTPException(status_code=409, detail="No available IDs in range")
    return {"iri": iri}
