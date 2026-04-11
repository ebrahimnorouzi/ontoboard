"""Property characteristics, chains, and disjointness router."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.characteristics import (
    CharacteristicsUpdate, CharacteristicsInfo, PropertyChainCreate,
    AllDisjointRequest, DisjointPropertiesRequest,
)
from app.services import board as board_svc
from app.services import characteristics as char_svc
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


@router.get("/{board_id}/entity/{entity_iri:path}", response_model=CharacteristicsInfo)
def get_characteristics(
    board_id: str, entity_iri: str,
    db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional),
):
    _check(board_id, db, user)
    try:
        g = load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file")
    return CharacteristicsInfo(**char_svc.get_characteristics(g, entity_iri))


@router.put("/{board_id}/entity/{entity_iri:path}", response_model=CharacteristicsInfo)
def set_characteristics(
    board_id: str, entity_iri: str, body: CharacteristicsUpdate,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = _check(board_id, db, user, need_edit=True)
    result = char_svc.set_characteristics(DATA_DIR / board_id, entity_iri, body.model_dump(exclude_unset=True))
    board_svc.git_commit(DATA_DIR / board_id, f"Updated characteristics for {entity_iri}")
    board_svc.log_activity(db, board, user, "characteristics_updated", entity_iri)
    return CharacteristicsInfo(**result)


@router.post("/{board_id}/property-chain", status_code=201)
def create_chain(
    board_id: str, body: PropertyChainCreate,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = _check(board_id, db, user, need_edit=True)
    ok = char_svc.create_property_chain(DATA_DIR / board_id, body.super_property, body.chain_properties)
    if not ok:
        raise HTTPException(status_code=400, detail="Chain needs at least 2 properties")
    board_svc.git_commit(DATA_DIR / board_id, f"Created property chain for {body.super_property}")
    return {"success": True}


@router.post("/{board_id}/all-disjoint", status_code=201)
def create_all_disjoint(
    board_id: str, body: AllDisjointRequest,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = _check(board_id, db, user, need_edit=True)
    ok = char_svc.create_all_disjoint_classes(DATA_DIR / board_id, body.class_iris)
    if not ok:
        raise HTTPException(status_code=400, detail="Need at least 2 classes")
    board_svc.git_commit(DATA_DIR / board_id, f"Created AllDisjointClasses ({len(body.class_iris)} classes)")
    return {"success": True}


@router.post("/{board_id}/disjoint-properties", status_code=201)
def create_disjoint_props(
    board_id: str, body: DisjointPropertiesRequest,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = _check(board_id, db, user, need_edit=True)
    ok = char_svc.create_disjoint_properties(DATA_DIR / board_id, body.property_iris)
    if not ok:
        raise HTTPException(status_code=400, detail="Need at least 2 properties")
    board_svc.git_commit(DATA_DIR / board_id, f"Created disjoint properties")
    return {"success": True}
