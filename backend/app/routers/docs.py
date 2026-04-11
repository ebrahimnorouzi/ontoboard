"""Documentation router — build, status, serve."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse, PlainTextResponse, HTMLResponse
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.docs import DocsStatus
from app.services import board as board_svc
from app.services import docs as docs_svc

router = APIRouter()


@router.post("/{board_id}/build")
async def build_docs(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Generate documentation. Streams progress as SSE."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "docs_build", "Documentation generation started")

    return StreamingResponse(
        docs_svc.generate_docs(board_dir),
        media_type="text/event-stream",
    )


@router.get("/{board_id}/status", response_model=DocsStatus)
def get_status(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Get documentation build status."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return DocsStatus(**docs_svc.get_docs_status(DATA_DIR / board_id))


@router.get("/{board_id}/files")
def list_files(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """List generated documentation files."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return docs_svc.list_docs_files(DATA_DIR / board_id)


@router.get("/{board_id}/serve/{file_path:path}")
def serve_file(
    board_id: str,
    file_path: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Serve a generated documentation file."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    content = docs_svc.get_docs_content(DATA_DIR / board_id, file_path)
    if content is None:
        raise HTTPException(status_code=404, detail="File not found")

    if file_path.endswith(".html"):
        return HTMLResponse(content)
    return PlainTextResponse(content)
