"""Restrictions router — create, read, delete OWL restrictions on entities."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.restrictions import (
    RestrictionCreate, ComplexExpressionCreate, RestrictionInfo, RestrictionDeleteRequest,
)
from app.services import board as board_svc
from app.services import restrictions as restr_svc
from app.services.ontology import load_graph

router = APIRouter()


def _check(board_id, db, user, need_edit=False):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if need_edit and not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    if not need_edit and not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return board


@router.get("/{board_id}/entity/{entity_iri:path}", response_model=list[RestrictionInfo])
def get_restrictions(
    board_id: str, entity_iri: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """List all restrictions on an entity."""
    _check(board_id, db, user)
    try:
        g = load_graph(board_svc.get_board_dir(board_id))
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    restrictions = restr_svc.get_restrictions_for_entity(g, entity_iri)
    return [RestrictionInfo(**r) for r in restrictions]


@router.post("/{board_id}/entity/{entity_iri:path}", status_code=201)
def add_restriction(
    board_id: str, entity_iri: str,
    body: RestrictionCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Add a restriction to an entity."""
    board = _check(board_id, db, user, need_edit=True)
    board_dir = board_svc.get_board_dir(board_id)
    ok = restr_svc.add_restriction(board_dir, entity_iri, body.model_dump())
    if not ok:
        raise HTTPException(status_code=400, detail="Invalid restriction type")
    board_svc.git_commit(board_dir, f"Added {body.restriction_type} restriction to {entity_iri}")
    board_svc.log_activity(db, board, user, "restriction_added",
                           f"{body.restriction_type} on {entity_iri}")
    return {"success": True}


@router.delete("/{board_id}/entity/{entity_iri:path}")
def delete_restriction(
    board_id: str, entity_iri: str,
    body: RestrictionDeleteRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Remove a restriction from an entity."""
    board = _check(board_id, db, user, need_edit=True)
    board_dir = board_svc.get_board_dir(board_id)
    ok = restr_svc.remove_restriction(board_dir, entity_iri, body.model_dump())
    if not ok:
        raise HTTPException(status_code=404, detail="Restriction not found")
    board_svc.git_commit(board_dir, f"Removed restriction from {entity_iri}")
    return {"success": True}


@router.post("/{board_id}/complex/{entity_iri:path}", status_code=201)
def add_complex_expression(
    board_id: str, entity_iri: str,
    body: ComplexExpressionCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Add a complex class expression (union/intersection/complement)."""
    board = _check(board_id, db, user, need_edit=True)
    board_dir = board_svc.get_board_dir(board_id)
    ok = restr_svc.add_complex_expression(board_dir, entity_iri, body.model_dump())
    if not ok:
        raise HTTPException(status_code=400, detail="Invalid expression")
    board_svc.git_commit(board_dir, f"Added {body.expression_type} to {entity_iri}")
    return {"success": True}
