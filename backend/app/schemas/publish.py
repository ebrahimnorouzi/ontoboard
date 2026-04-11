"""Publish pipeline schemas."""

import datetime
from pydantic import BaseModel


class CheckResult(BaseModel):
    name: str
    passed: bool
    message: str
    severity: str = "info"  # "info" | "warning" | "error"


class PublishRequest(BaseModel):
    steps: list[str] = ["test", "prepare_release", "publish"]
    skip_checks: bool = False


class PublishStatus(BaseModel):
    last_published: datetime.datetime | None = None
    version: str | None = None
    artifacts: list[str] = []
    checks_passed: bool | None = None
