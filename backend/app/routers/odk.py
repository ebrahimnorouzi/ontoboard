"""ODK pipeline router — run builds, stream output."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services.odk import stream_build

router = APIRouter()


@router.post("/{board_id}/build")
def run_odk_build(
    board_id: str,
    target: str = "all",
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required to run builds")

    board_dir = DATA_DIR / board_id
    ont_dir = board_dir / "src" / "ontology"
    if not ont_dir.exists():
        raise HTTPException(status_code=400, detail="Board has no ODK ontology directory")

    board_svc.log_activity(db, board, user, "build", f"make {target}")

    return StreamingResponse(
        stream_build(board_dir, target),
        media_type="text/event-stream",
    )
