"""Job queue schemas."""

import datetime
from pydantic import BaseModel


class JobSubmission(BaseModel):
    job_type: str  # reason | publish | build_docs | build_kg | robot_report
    params: dict = {}


class JobStatus(BaseModel):
    job_id: str
    job_type: str = ""
    board_id: str = ""
    status: str = "pending"  # pending | running | completed | failed
    progress: float = 0
    result: dict | None = None
    error: str | None = None
    created_at: str | None = None
    completed_at: str | None = None
