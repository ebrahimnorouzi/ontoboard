"""Job queue router — submit, status, stream, list."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.queue import JobSubmission, JobStatus
from app.services import board as board_svc
from app.services import queue as queue_svc

router = APIRouter()

ALLOWED_JOB_TYPES = {"reason", "publish", "build_docs", "build_kg", "robot_report"}


@router.post("/{board_id}/submit", response_model=JobStatus)
def submit_job(
    board_id: str,
    body: JobSubmission,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Submit a background job to the worker queue."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    if body.job_type not in ALLOWED_JOB_TYPES:
        raise HTTPException(status_code=400, detail=f"Invalid job type. Allowed: {ALLOWED_JOB_TYPES}")

    try:
        job_id = queue_svc.enqueue_job(board_id, body.job_type, body.params)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Queue unavailable: {exc}")

    board_svc.log_activity(db, board, user, "job_submitted", f"{body.job_type} (job: {job_id})")

    status = queue_svc.get_job_status(job_id)
    return JobStatus(**(status or {"job_id": job_id, "status": "pending"}))


@router.get("/status/{job_id}", response_model=JobStatus)
def get_job_status(
    job_id: str,
    user: User = Depends(get_current_user),
):
    """Get current status of a job."""
    try:
        status = queue_svc.get_job_status(job_id)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Queue unavailable: {exc}")

    if not status:
        raise HTTPException(status_code=404, detail="Job not found")
    return JobStatus(**status)


@router.get("/stream/{job_id}")
async def stream_job(
    job_id: str,
    user: User = Depends(get_current_user),
):
    """Stream job progress as SSE."""
    try:
        status = queue_svc.get_job_status(job_id)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Queue unavailable: {exc}")

    if not status:
        raise HTTPException(status_code=404, detail="Job not found")

    return StreamingResponse(
        queue_svc.subscribe_job_progress(job_id),
        media_type="text/event-stream",
    )


@router.get("/{board_id}/list")
def list_jobs(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """List recent jobs for a board."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    try:
        jobs = queue_svc.list_board_jobs(board_id)
    except Exception:
        jobs = []

    return [JobStatus(**j) for j in jobs]
