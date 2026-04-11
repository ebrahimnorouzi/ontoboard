"""Search router — full-text entity search."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.search import SearchRequest, SearchResult
from app.services import board as board_svc
from app.services import search as search_svc
from app.services.ontology import load_graph

router = APIRouter()


@router.post("/{board_id}", response_model=list[SearchResult])
def search_entities(
    board_id: str, body: SearchRequest,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    try:
        g = load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file")
    results = search_svc.search_entities(g, body.query, body.entity_types, body.limit)
    return [SearchResult(**r) for r in results]
