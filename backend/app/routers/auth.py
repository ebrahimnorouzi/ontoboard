"""Auth router — login, token refresh, current user info."""

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.auth import LoginRequest, TokenResponse
from app.schemas.user import UserOut
from app.schemas.auth import SignupRequest
from app.services.auth import verify_password, create_access_token
from app.services.user import get_user_by_username, get_user_by_email, create_user

router = APIRouter()


@router.post("/login", response_model=TokenResponse)
def login(body: LoginRequest, db: Session = Depends(get_db)):
    user = get_user_by_username(db, body.username)
    if not user or not verify_password(body.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password",
        )
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account disabled",
        )
    token = create_access_token({"sub": user.username, "role": user.role, "user_id": user.id})
    return TokenResponse(access_token=token)


@router.post("/signup", response_model=UserOut, status_code=201)
def signup(body: SignupRequest, db: Session = Depends(get_db)):
    """Self-registration. Account is created as inactive — admin must approve."""
    if get_user_by_username(db, body.username):
        raise HTTPException(status_code=409, detail="Username already taken")
    if get_user_by_email(db, body.email):
        raise HTTPException(status_code=409, detail="Email already registered")
    user = create_user(
        db, username=body.username, email=body.email,
        password=body.password, role="user",
        display_name=body.display_name or body.username,
    )
    # Mark as inactive until admin approves
    user.is_active = False
    db.commit()
    db.refresh(user)
    return user


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(get_current_user)):
    return user
