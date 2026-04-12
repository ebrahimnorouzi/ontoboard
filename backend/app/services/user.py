"""User service — CRUD operations."""

from sqlalchemy.orm import Session

from app.models.user import User
from app.services.auth import hash_password


def get_user_by_username(db: Session, username: str) -> User | None:
    return db.query(User).filter(User.username == username).first()


def get_user_by_id(db: Session, user_id: int) -> User | None:
    return db.query(User).filter(User.id == user_id).first()


def get_user_by_email(db: Session, email: str) -> User | None:
    return db.query(User).filter(User.email == email).first()


def list_users(db: Session, skip: int = 0, limit: int = 100) -> list[User]:
    return db.query(User).offset(skip).limit(limit).all()


def create_user(
    db: Session,
    username: str,
    email: str,
    password: str,
    role: str = "user",
    display_name: str | None = None,
) -> User:
    user = User(
        username=username,
        email=email,
        hashed_password=hash_password(password),
        role=role,
        display_name=display_name or username,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def update_user(db: Session, user: User, **fields) -> User:
    if "password" in fields and fields["password"]:
        user.hashed_password = hash_password(fields.pop("password"))
    else:
        fields.pop("password", None)

    for key, val in fields.items():
        if val is not None and hasattr(user, key):
            setattr(user, key, val)

    db.commit()
    db.refresh(user)
    return user


def ensure_admin(db: Session, username: str, email: str, password: str) -> User:
    """Create the admin user if it doesn't exist; update password if it does."""
    admin = get_user_by_username(db, username)
    if admin is None:
        return create_user(db, username, email, password, role="admin", display_name="Admin")
    # Update password if env changed
    admin.hashed_password = hash_password(password)
    admin.role = "admin"
    db.commit()
    db.refresh(admin)
    return admin


def count_users(db: Session, active_only: bool = False) -> int:
    q = db.query(User)
    if active_only:
        q = q.filter(User.is_active.is_(True))
    return q.count()


def delete_user_permanently(db: Session, user: User) -> None:
    """Permanently delete a user and their notifications/memberships."""
    from app.models.notification import Notification
    from app.models.board import BoardMember
    db.query(Notification).filter(Notification.user_id == user.id).delete()
    db.query(BoardMember).filter(BoardMember.user_id == user.id).delete()
    db.delete(user)
    db.commit()
