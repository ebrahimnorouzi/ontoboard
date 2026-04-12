"""Notification service — create, query, and manage user notifications."""

from sqlalchemy.orm import Session

from app.models.notification import Notification
from app.models.user import User


# ── Core CRUD ─────────────────────────────────────────────────

def create_notification(db: Session, user_id: int, category: str,
                        title: str, message: str = "", link: str | None = None) -> Notification:
    """Create a notification for a specific user."""
    notif = Notification(
        user_id=user_id,
        category=category,
        title=title,
        message=message,
        link=link,
    )
    db.add(notif)
    db.commit()
    db.refresh(notif)
    return notif


def get_notifications(db: Session, user_id: int, unread_only: bool = False,
                      limit: int = 50) -> list[Notification]:
    """Get notifications for a user, newest first."""
    q = db.query(Notification).filter(Notification.user_id == user_id)
    if unread_only:
        q = q.filter(Notification.is_read == False)  # noqa: E712
    return q.order_by(Notification.created_at.desc()).limit(limit).all()


def count_unread(db: Session, user_id: int) -> int:
    """Count unread notifications for a user."""
    return db.query(Notification).filter(
        Notification.user_id == user_id,
        Notification.is_read == False,  # noqa: E712
    ).count()


def mark_read(db: Session, notification_id: int, user_id: int) -> bool:
    """Mark a single notification as read."""
    notif = db.query(Notification).filter(
        Notification.id == notification_id,
        Notification.user_id == user_id,
    ).first()
    if not notif:
        return False
    notif.is_read = True
    db.commit()
    return True


def mark_all_read(db: Session, user_id: int) -> int:
    """Mark all notifications as read. Returns count updated."""
    count = db.query(Notification).filter(
        Notification.user_id == user_id,
        Notification.is_read == False,  # noqa: E712
    ).update({"is_read": True})
    db.commit()
    return count


def delete_notification(db: Session, notification_id: int, user_id: int) -> bool:
    """Delete a notification."""
    notif = db.query(Notification).filter(
        Notification.id == notification_id,
        Notification.user_id == user_id,
    ).first()
    if not notif:
        return False
    db.delete(notif)
    db.commit()
    return True


# ── High-level notification helpers ──────────────────────────

def notify_admins_new_signup(db: Session, new_user: User):
    """Notify all admins that a new user has signed up and awaits approval."""
    admins = db.query(User).filter(User.role == "admin", User.is_active == True).all()  # noqa: E712
    for admin in admins:
        create_notification(
            db, admin.id,
            category="signup",
            title=f"New user signup: {new_user.username}",
            message=f"{new_user.username} ({new_user.email}) registered and is awaiting approval.",
            link="/admin/users",  # routed via /admin/:tab
        )


def notify_user_activated(db: Session, user: User):
    """Notify a user that their account has been approved."""
    create_notification(
        db, user.id,
        category="activated",
        title="Account approved",
        message="Your account has been activated. You can now create and edit boards.",
        link="/board",
    )
    # Also send a welcome notification with feedback prompt
    create_notification(
        db, user.id,
        category="welcome",
        title="Welcome to OntoBoard!",
        message=(
            "We're glad you're here! OntoBoard is an open-source collaborative ontology editor. "
            "If you run into any issues or have suggestions, click here to let us know."
        ),
        link="/feedback",
    )


def notify_board_shared(db: Session, user_id: int, board_id: str, role: str, sharer_name: str):
    """Notify a user that they've been added to a board."""
    create_notification(
        db, user_id,
        category="board_shared",
        title=f"Added to board: {board_id}",
        message=f"{sharer_name} added you as {role} on board '{board_id}'.",
        link=f"/board/{board_id}",
    )


def notify_board_editors(db: Session, board, action: str, detail: str, exclude_user_id: int | None = None):
    """Notify all editors/owner of a board about an event (except the actor)."""
    # Notify owner
    if board.owner_id != exclude_user_id:
        create_notification(
            db, board.owner_id,
            category="board_update",
            title=f"Board update: {board.board_id}",
            message=f"{action}: {detail}",
            link=f"/boards/{board.board_id}",
        )
    # Notify editors
    for member in board.members:
        if member.user_id != exclude_user_id and member.role == "editor":
            create_notification(
                db, member.user_id,
                category="board_update",
                title=f"Board update: {board.board_id}",
                message=f"{action}: {detail}",
                link=f"/boards/{board.board_id}",
            )
