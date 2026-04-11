"""DL Query and SWRL router."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import dl_query as dl_svc
from app.services import swrl as swrl_svc
from app.services.ontology import load_graph

router = APIRouter()


class DLQueryRequest(BaseModel):
    query: str
    query_type: str = "subclasses"  # subclasses | superclasses | instances | equivalents


class SwrlRuleCreate(BaseModel):
    rule: str


@router.post("/{board_id}/dl-query")
def run_dl_query(board_id: str, body: DLQueryRequest,
                  db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    try:
        g = load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file")
    return dl_svc.execute_dl_query(g, body.query, body.query_type)


@router.get("/{board_id}/swrl")
def list_swrl(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return swrl_svc.list_rules(DATA_DIR / board_id)


@router.post("/{board_id}/swrl", status_code=201)
def add_swrl(board_id: str, body: SwrlRuleCreate,
              db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = swrl_svc.add_rule(DATA_DIR / board_id, body.rule)
    board_svc.git_commit(DATA_DIR / board_id, f"Added SWRL rule")
    return result


@router.delete("/{board_id}/swrl")
def delete_swrl(board_id: str, body: SwrlRuleCreate,
                 db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = swrl_svc.delete_rule(DATA_DIR / board_id, body.rule)
    if not ok:
        raise HTTPException(status_code=404, detail="Rule not found")
    board_svc.git_commit(DATA_DIR / board_id, f"Deleted SWRL rule")
    return {"success": True}
