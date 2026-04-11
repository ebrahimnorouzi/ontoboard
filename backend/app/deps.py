"""FastAPI dependency injection — DB sessions, auth, permissions."""

from typing import Generator

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from app.database import SessionLocal
from app.models.user import User
from app.services.auth import decode_access_token
from app.services import user as user_svc

security = HTTPBearer(auto_error=False)


def get_db() -> Generator:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_current_user_optional(
    creds: HTTPAuthorizationCredentials | None = Depends(security),
    db=Depends(get_db),
) -> User | None:
    """Return current user or None (for public endpoints)."""
    if creds is None:
        return None
    payload = decode_access_token(creds.credentials)
    if payload is None:
        return None
    user = user_svc.get_user_by_username(db, payload.get("sub", ""))
    if user is None or not user.is_active:
        return None
    return user


def get_current_user(
    user: User | None = Depends(get_current_user_optional),
) -> User:
    """Require authentication — 401 if not logged in."""
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user


def require_admin(user: User = Depends(get_current_user)) -> User:
    """Require admin role — 403 if not admin."""
    if not user.is_admin:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required",
        )
    return user
