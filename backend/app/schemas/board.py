"""Board request/response schemas."""

import datetime
from pydantic import BaseModel


class BoardCreate(BaseModel):
    display_name: str | None = None
    description: str = ""
    is_public: bool = True
    tags: str = ""
    versioning_strategy: str = "date"  # "date" or "semantic"


class BoardUpdate(BaseModel):
    display_name: str | None = None
    description: str | None = None
    is_public: bool | None = None
    allow_anonymous_view: bool | None = None
    allow_anonymous_edit: bool | None = None
    is_starred: bool | None = None
    tags: str | None = None


class BoardOut(BaseModel):
    id: int
    board_id: str
    display_name: str
    description: str
    owner_id: int
    is_public: bool
    is_starred: bool
    tags: str
    created_at: datetime.datetime
    # filesystem info
    odk_seeded: bool = False
    git_initialized: bool = False
    last_modified: str | None = None
    last_modified_by: str | None = None

    model_config = {"from_attributes": True}


class BoardDetail(BoardOut):
    """Extended view — includes owner name, member count, access settings."""
    owner_username: str = ""
    member_count: int = 0
    activity_count: int = 0
    allow_anonymous_view: bool = True
    allow_anonymous_edit: bool = False
    updated_at: datetime.datetime | None = None
    # user's permission on this board (filled per-request)
    user_role: str | None = None  # "owner" | "editor" | "viewer" | None


class BoardMemberOut(BaseModel):
    user_id: int
    username: str
    role: str
    added_at: datetime.datetime

    model_config = {"from_attributes": True}


class ShareRequest(BaseModel):
    username: str
    role: str = "editor"  # "editor" | "viewer"


class ActivityOut(BaseModel):
    id: int
    action: str
    detail: str
    user_id: int | None
    username: str | None = None
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


class AdminStats(BaseModel):
    total_users: int
    active_users: int
    total_boards: int
    public_boards: int
    private_boards: int
    total_activities: int


class CanvasGraph(BaseModel):
    """Simplified graph representation for the Tldraw canvas."""
    classes: list[dict]
    properties: list[dict]
