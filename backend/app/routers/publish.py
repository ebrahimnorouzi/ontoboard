"""Publish pipeline router — quality checks, publish execution, status."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.publish import CheckResult, PublishRequest, PublishStatus
from app.services import board as board_svc
from app.services import publish as publish_svc

router = APIRouter()


@router.post("/{board_id}/check", response_model=list[CheckResult])
async def run_checks(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Run pre-publish quality checks."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    board_dir = board_svc.get_board_dir(board_id)
    checks = await publish_svc.run_pre_checks(board_dir)
    board_svc.log_activity(db, board, user, "publish_check",
                           f"{sum(1 for c in checks if c['passed'])}/{len(checks)} passed")
    return [CheckResult(**c) for c in checks]


@router.post("/{board_id}/run")
async def run_publish(
    board_id: str,
    body: PublishRequest | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Execute the publish pipeline. Streams progress as SSE."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Owner or admin required to publish")

    board_dir = board_svc.get_board_dir(board_id)
    ont_dir = board_dir / "src" / "ontology"
    if not ont_dir.exists():
        raise HTTPException(status_code=400, detail="No ontology directory")

    body = body or PublishRequest()
    board_svc.log_activity(db, board, user, "publish_start",
                           f"Steps: {', '.join(body.steps)}")

    return StreamingResponse(
        publish_svc.stream_publish(board_dir, body.steps),
        media_type="text/event-stream",
    )


@router.get("/{board_id}/status", response_model=PublishStatus)
def get_status(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Get publish status and artifacts."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    board_dir = board_svc.get_board_dir(board_id)
    return PublishStatus(**publish_svc.get_publish_status(board_dir))
