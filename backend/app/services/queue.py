"""Job queue service — enqueue, status, progress streaming via Redis.

Jobs are submitted to a Redis list. The worker service polls this list,
executes jobs, and publishes progress to a Redis pub/sub channel.
"""

import asyncio
import datetime
import json
import logging
import uuid

import redis

from app.config import REDIS_URL

logger = logging.getLogger("ontoboard.queue")

JOB_QUEUE_KEY = "ontoboard:jobs"
JOB_PREFIX = "ontoboard:job:"
JOB_PROGRESS_CHANNEL = "ontoboard:progress:"
JOB_TTL = 3600  # 1 hour


def _get_redis() -> redis.Redis:
    return redis.from_url(REDIS_URL, decode_responses=True)


def enqueue_job(board_id: str, job_type: str, params: dict | None = None) -> str:
    """Submit a job to the queue. Returns job_id."""
    r = _get_redis()
    job_id = str(uuid.uuid4())[:12]
    job_data = {
        "job_id": job_id,
        "board_id": board_id,
        "job_type": job_type,
        "params": params or {},
        "status": "pending",
        "progress": 0,
        "result": None,
        "error": None,
        "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "completed_at": None,
    }

    r.set(f"{JOB_PREFIX}{job_id}", json.dumps(job_data), ex=JOB_TTL)
    r.rpush(JOB_QUEUE_KEY, json.dumps({"job_id": job_id, "board_id": board_id, "job_type": job_type, "params": params or {}}))

    logger.info("Enqueued job %s: %s for board %s", job_id, job_type, board_id)
    return job_id


def get_job_status(job_id: str) -> dict | None:
    """Get the current status of a job."""
    r = _get_redis()
    data = r.get(f"{JOB_PREFIX}{job_id}")
    if data:
        return json.loads(data)
    return None


def update_job(job_id: str, **updates):
    """Update job status (called by the worker)."""
    r = _get_redis()
    key = f"{JOB_PREFIX}{job_id}"
    data = r.get(key)
    if not data:
        return
    job = json.loads(data)
    job.update(updates)
    r.set(key, json.dumps(job), ex=JOB_TTL)

    # Publish progress update for SSE subscribers
    r.publish(f"{JOB_PROGRESS_CHANNEL}{job_id}", json.dumps(updates))


def complete_job(job_id: str, result: dict | None = None, error: str | None = None):
    """Mark a job as completed or failed."""
    status = "failed" if error else "completed"
    update_job(
        job_id,
        status=status,
        progress=100,
        result=result,
        error=error,
        completed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    )


async def subscribe_job_progress(job_id: str):
    """Async generator yielding SSE events for job progress."""
    r = _get_redis()
    pubsub = r.pubsub()
    channel = f"{JOB_PROGRESS_CHANNEL}{job_id}"
    pubsub.subscribe(channel)

    try:
        # First yield current status
        current = get_job_status(job_id)
        if current:
            yield f"data: {json.dumps(current)}\n\n"
            if current["status"] in ("completed", "failed"):
                return

        # Then listen for updates
        while True:
            message = pubsub.get_message(timeout=1.0)
            if message and message["type"] == "message":
                yield f"data: {message['data']}\n\n"
                try:
                    update = json.loads(message["data"])
                    if update.get("status") in ("completed", "failed"):
                        return
                except json.JSONDecodeError:
                    pass

            # Check if job is done (in case we missed the message)
            current = get_job_status(job_id)
            if current and current["status"] in ("completed", "failed"):
                yield f"data: {json.dumps(current)}\n\n"
                return

            await asyncio.sleep(0.5)
    finally:
        pubsub.unsubscribe(channel)
        pubsub.close()


def list_board_jobs(board_id: str, limit: int = 20) -> list[dict]:
    """List recent jobs for a board by scanning keys."""
    r = _get_redis()
    jobs = []
    for key in r.scan_iter(f"{JOB_PREFIX}*", count=100):
        data = r.get(key)
        if data:
            job = json.loads(data)
            if job.get("board_id") == board_id:
                jobs.append(job)
    jobs.sort(key=lambda j: j.get("created_at", ""), reverse=True)
    return jobs[:limit]
