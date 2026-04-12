"""SPARQL router — query, visualize, prefixes."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user_optional
from app.models.user import User
from app.schemas.sparql import SparqlQuery, SparqlResult, GraphVisualization
from app.services import board as board_svc
from app.services import sparql as sparql_svc

router = APIRouter()


def _check_access(board_id: str, db: Session, user):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return board


@router.post("/{board_id}/query", response_model=SparqlResult)
def run_query(
    board_id: str,
    body: SparqlQuery,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Execute a SPARQL query against the board's ontology + KG data."""
    _check_access(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    result = sparql_svc.execute_sparql(board_dir, body.query)
    return SparqlResult(**result)


@router.post("/{board_id}/visualize", response_model=GraphVisualization)
def visualize_query(
    board_id: str,
    body: SparqlQuery,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Execute a CONSTRUCT/DESCRIBE query and return a graph visualization."""
    _check_access(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    result = sparql_svc.sparql_to_graph_visualization(board_dir, body.query)
    return GraphVisualization(**result)


@router.get("/{board_id}/prefixes")
def get_prefixes(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Return prefix bindings for SPARQL autocomplete."""
    _check_access(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    return sparql_svc.get_common_prefixes(board_dir)
