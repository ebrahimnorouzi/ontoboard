"""Tree browser router — hierarchical entity navigation, detail, CRUD."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.tree import TreeNode, EntityDetail, AnnotationUpdate, EntityCreateRequest
from app.services import board as board_svc
from app.services import tree as tree_svc
from app.services.ontology import load_graph

router = APIRouter()


def _load(board_id, db, user, need_edit=False):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if need_edit and not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    if not need_edit and not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    try:
        g = load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    return board, g


@router.get("/{board_id}/classes", response_model=list[TreeNode])
def get_classes(board_id: str, db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional)):
    _, g = _load(board_id, db, user)
    return tree_svc.get_class_tree(g)


@router.get("/{board_id}/object-properties", response_model=list[TreeNode])
def get_object_properties(board_id: str, db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional)):
    _, g = _load(board_id, db, user)
    return tree_svc.get_object_property_tree(g)


@router.get("/{board_id}/data-properties", response_model=list[TreeNode])
def get_data_properties(board_id: str, db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional)):
    _, g = _load(board_id, db, user)
    return tree_svc.get_data_property_tree(g)


@router.get("/{board_id}/annotation-properties", response_model=list[TreeNode])
def get_annotation_properties(board_id: str, db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional)):
    _, g = _load(board_id, db, user)
    return tree_svc.get_annotation_property_tree(g)


@router.get("/{board_id}/individuals")
def get_individuals(board_id: str, db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional)):
    _, g = _load(board_id, db, user)
    return tree_svc.get_individuals_by_class(g)


@router.get("/{board_id}/entity/{entity_iri:path}", response_model=EntityDetail)
def get_entity_detail(board_id: str, entity_iri: str, db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional)):
    _, g = _load(board_id, db, user)
    return tree_svc.get_entity_detail(g, entity_iri)


@router.put("/{board_id}/entity/{entity_iri:path}/annotations")
def update_annotations(
    board_id: str, entity_iri: str,
    updates: list[AnnotationUpdate],
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board, _ = _load(board_id, db, user, need_edit=True)
    board_dir = DATA_DIR / board_id
    tree_svc.update_entity_annotations(board_dir, entity_iri, [u.model_dump() for u in updates])
    board_svc.git_commit(board_dir, f"Updated annotations for {entity_iri}")
    board_svc.log_activity(db, board, user, "annotation_edit", f"Entity: {entity_iri}")
    return {"success": True}


@router.post("/{board_id}/entity", status_code=201)
def create_entity(
    board_id: str, body: EntityCreateRequest,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board, _ = _load(board_id, db, user, need_edit=True)
    board_dir = DATA_DIR / board_id
    ok = tree_svc.create_entity(board_dir, body.entity_type, body.iri, body.label, body.parent_iri)
    if not ok:
        raise HTTPException(status_code=400, detail="Invalid entity type")
    board_svc.git_commit(board_dir, f"Created {body.entity_type}: {body.label}")
    board_svc.log_activity(db, board, user, "entity_created", f"{body.entity_type}: {body.label}")
    return {"success": True, "iri": body.iri}


@router.delete("/{board_id}/entity/{entity_iri:path}")
def delete_entity(
    board_id: str, entity_iri: str,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board, _ = _load(board_id, db, user, need_edit=True)
    board_dir = DATA_DIR / board_id
    tree_svc.delete_entity(board_dir, entity_iri)
    board_svc.git_commit(board_dir, f"Deleted entity: {entity_iri}")
    board_svc.log_activity(db, board, user, "entity_deleted", f"Entity: {entity_iri}")
    return {"success": True}
