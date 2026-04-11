"""Invite link request/response schemas."""

import datetime
from pydantic import BaseModel


class InviteCreateRequest(BaseModel):
    role: str = "viewer"  # "editor" | "viewer"
    expires_hours: int | None = None
    max_uses: int = 0  # 0 = unlimited


class InviteCreateResponse(BaseModel):
    token: str
    url: str
    role: str
    expires_at: datetime.datetime | None

    model_config = {"from_attributes": True}


class InviteLinkOut(BaseModel):
    id: int
    token: str
    role: str
    created_by: int
    created_at: datetime.datetime
    expires_at: datetime.datetime | None
    max_uses: int
    use_count: int
    is_active: bool

    model_config = {"from_attributes": True}


class InviteInfoResponse(BaseModel):
    board_name: str
    role: str
    created_by: str
    expires_at: datetime.datetime | None
    valid: bool


class InviteAcceptResponse(BaseModel):
    board_id: str
    board_name: str
    role: str
