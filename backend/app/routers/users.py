"""User management router — admin only."""

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_db, require_admin
from app.models.user import User
from app.schemas.user import UserCreate, UserUpdate, UserOut, UserDetail
from app.services import user as user_svc

router = APIRouter()


@router.get("/", response_model=list[UserDetail])
def list_users(db: Session = Depends(get_db), _admin: User = Depends(require_admin)):
    users = user_svc.list_users(db)
    result = []
    for u in users:
        result.append(UserDetail(
            id=u.id,
            username=u.username,
            email=u.email,
            role=u.role,
            is_active=u.is_active,
            display_name=u.display_name,
            created_at=u.created_at,
            updated_at=u.updated_at,
            board_count=len(u.owned_boards),
            membership_count=len(u.memberships),
        ))
    return result


@router.post("/", response_model=UserOut, status_code=201)
def create_user(body: UserCreate, db: Session = Depends(get_db), _admin: User = Depends(require_admin)):
    if user_svc.get_user_by_username(db, body.username):
        raise HTTPException(status_code=409, detail="Username already taken")
    if user_svc.get_user_by_email(db, body.email):
        raise HTTPException(status_code=409, detail="Email already registered")
    return user_svc.create_user(
        db,
        username=body.username,
        email=body.email,
        password=body.password,
        role=body.role,
        display_name=body.display_name,
    )


@router.get("/{user_id}", response_model=UserDetail)
def get_user(user_id: int, db: Session = Depends(get_db), _admin: User = Depends(require_admin)):
    user = user_svc.get_user_by_id(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return UserDetail(
        id=user.id,
        username=user.username,
        email=user.email,
        role=user.role,
        is_active=user.is_active,
        display_name=user.display_name,
        created_at=user.created_at,
        updated_at=user.updated_at,
        board_count=len(user.owned_boards),
        membership_count=len(user.memberships),
    )


@router.patch("/{user_id}", response_model=UserOut)
def update_user(
    user_id: int,
    body: UserUpdate,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    user = user_svc.get_user_by_id(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user_svc.update_user(db, user, **body.model_dump(exclude_unset=True))


@router.delete("/{user_id}")
def delete_user(user_id: int, db: Session = Depends(get_db), admin: User = Depends(require_admin)):
    user = user_svc.get_user_by_id(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    if user.id == admin.id:
        raise HTTPException(status_code=400, detail="Cannot delete yourself")
    user_svc.update_user(db, user, is_active=False)
    return {"detail": f"User '{user.username}' deactivated"}
