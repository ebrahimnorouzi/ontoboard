"""Axiom router — get/edit axioms for ontology entities."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.axiom import AxiomInfo, AxiomEditRequest, AxiomEditResult, ValidationError, EntityName
from app.services import board as board_svc
from app.services import axiom as axiom_svc
from app.services.ontology import load_graph

router = APIRouter()


def _require_board(board_id: str, db: Session, user, need_edit: bool = False):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if need_edit and not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    if not need_edit and not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return board


@router.get("/{board_id}/entity-names", response_model=list[EntityName])
def get_entity_names(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """List all named entities (for autocomplete)."""
    _require_board(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    return axiom_svc.get_entity_names(g)


@router.get("/{board_id}/axioms/{entity_iri:path}", response_model=list[AxiomInfo])
def get_axioms(
    board_id: str,
    entity_iri: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Get all axioms involving the given entity."""
    _require_board(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    return axiom_svc.get_axioms_for_entity(g, entity_iri)


@router.get("/{board_id}/manchester/{entity_iri:path}")
def get_manchester(
    board_id: str,
    entity_iri: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Get Manchester Syntax representation of an entity."""
    _require_board(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    text = axiom_svc.get_manchester_for_entity(g, entity_iri)
    return {"entity_iri": entity_iri, "manchester": text}


@router.put("/{board_id}/axioms/{entity_iri:path}", response_model=AxiomEditResult)
def update_axioms(
    board_id: str,
    entity_iri: str,
    body: AxiomEditRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Update axioms for an entity from Manchester Syntax text."""
    board = _require_board(board_id, db, user, need_edit=True)
    board_dir = board_svc.get_board_dir(board_id)
    result = axiom_svc.apply_manchester_edit(board_dir, entity_iri, body.manchester_text)
    if result["success"]:
        board_svc.git_commit(board_dir, f"Updated axioms for {entity_iri}")
        board_svc.log_activity(db, board, user, "axiom_edit", f"Entity: {entity_iri}")
    return AxiomEditResult(**result)


@router.post("/{board_id}/validate", response_model=list[ValidationError])
def validate(
    board_id: str,
    body: AxiomEditRequest,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Validate Manchester Syntax text without applying."""
    _require_board(board_id, db, user)
    errors = axiom_svc.validate_manchester(body.manchester_text)
    return [ValidationError(**e) for e in errors]
