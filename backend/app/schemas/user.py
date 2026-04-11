"""User request/response schemas."""

import datetime
from pydantic import BaseModel, EmailStr


class UserCreate(BaseModel):
    username: str
    email: str
    password: str
    role: str = "user"
    display_name: str | None = None


class UserUpdate(BaseModel):
    email: str | None = None
    display_name: str | None = None
    role: str | None = None
    is_active: bool | None = None
    password: str | None = None


class UserOut(BaseModel):
    id: int
    username: str
    email: str
    role: str
    is_active: bool
    display_name: str | None
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


class UserDetail(UserOut):
    """Extended view for admins — includes board count & last activity."""
    board_count: int = 0
    membership_count: int = 0
    updated_at: datetime.datetime | None = None
