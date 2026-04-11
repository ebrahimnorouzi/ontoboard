"""Task management schemas."""

import datetime
from pydantic import BaseModel


class TaskCreate(BaseModel):
    title: str
    description: str = ""
    assignee_username: str | None = None
    entity_iri: str = ""
    priority: str = "medium"
    due_date: datetime.datetime | None = None


class TaskUpdate(BaseModel):
    title: str | None = None
    description: str | None = None
    status: str | None = None
    priority: str | None = None
    assignee_username: str | None = None
    entity_iri: str | None = None
    due_date: datetime.datetime | None = None


class TaskOut(BaseModel):
    id: int
    title: str
    description: str
    status: str
    priority: str
    assignee_username: str | None = None
    assignee_id: int | None = None
    created_by_username: str = ""
    entity_iri: str = ""
    github_issue_url: str = ""
    github_issue_number: int | None = None
    comments_count: int = 0
    due_date: datetime.datetime | None = None
    created_at: datetime.datetime
    updated_at: datetime.datetime

    model_config = {"from_attributes": True}


class TaskCommentCreate(BaseModel):
    text: str


class TaskCommentOut(BaseModel):
    id: int
    text: str
    username: str
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


class GitHubIssueRequest(BaseModel):
    repo_owner: str
    repo_name: str
    github_token: str
    labels: list[str] = []
