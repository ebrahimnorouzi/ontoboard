"""Notification router — user notification inbox."""

import datetime
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import notification as notif_svc

router = APIRouter()


class NotificationOut(BaseModel):
    id: int
    category: str
    title: str
    message: str
    link: str | None
    is_read: bool
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


class NotificationSummary(BaseModel):
    unread_count: int
    notifications: list[NotificationOut]


@router.get("/", response_model=NotificationSummary)
def get_notifications(
    unread_only: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Get notifications for the current user."""
    notifications = notif_svc.get_notifications(db, user.id, unread_only=unread_only)
    unread = notif_svc.count_unread(db, user.id)
    return NotificationSummary(
        unread_count=unread,
        notifications=[NotificationOut.model_validate(n) for n in notifications],
    )


@router.get("/count")
def get_unread_count(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Get the unread notification count (lightweight poll endpoint)."""
    return {"unread_count": notif_svc.count_unread(db, user.id)}


@router.post("/{notification_id}/read")
def mark_read(
    notification_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Mark a notification as read."""
    if not notif_svc.mark_read(db, notification_id, user.id):
        raise HTTPException(status_code=404, detail="Notification not found")
    return {"success": True}


@router.post("/read-all")
def mark_all_read(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Mark all notifications as read."""
    count = notif_svc.mark_all_read(db, user.id)
    return {"marked_read": count}


@router.delete("/{notification_id}")
def delete_notification(
    notification_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Delete a notification."""
    if not notif_svc.delete_notification(db, notification_id, user.id):
        raise HTTPException(status_code=404, detail="Notification not found")
    return {"success": True}
