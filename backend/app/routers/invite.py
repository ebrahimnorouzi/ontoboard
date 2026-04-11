"""Invite router — create, list, revoke, accept, and info for invite links."""

import os

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.invite import (
    InviteCreateRequest, InviteCreateResponse,
    InviteLinkOut, InviteInfoResponse, InviteAcceptResponse,
)
from app.services import board as board_svc
from app.services import invite as invite_svc

FRONTEND_URL = os.getenv("FRONTEND_URL", "http://localhost:3000")

router = APIRouter()


# ── Create invite link ────────────────────────────────────────
@router.post("/{board_id}/create", response_model=InviteCreateResponse, status_code=201)
def create_invite(
    board_id: str,
    body: InviteCreateRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can create invite links")
    if body.role not in ("editor", "viewer"):
        raise HTTPException(status_code=400, detail="Role must be 'editor' or 'viewer'")

    invite = invite_svc.create_invite(
        db, board, user,
        role=body.role,
        expires_hours=body.expires_hours,
        max_uses=body.max_uses,
    )
    return InviteCreateResponse(
        token=invite.token,
        url=f"{FRONTEND_URL}/invite/{invite.token}",
        role=invite.role,
        expires_at=invite.expires_at,
    )


# ── List active invite links ─────────────────────────────────
@router.get("/{board_id}/links", response_model=list[InviteLinkOut])
def list_invites(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can list invite links")

    invites = invite_svc.list_active_invites(db, board)
    return [
        InviteLinkOut(
            id=inv.id,
            token=inv.token,
            role=inv.role,
            created_by=inv.created_by,
            created_at=inv.created_at,
            expires_at=inv.expires_at,
            max_uses=inv.max_uses,
            use_count=inv.use_count,
            is_active=inv.is_active,
        )
        for inv in invites
    ]


# ── Revoke invite link ───────────────────────────────────────
@router.delete("/{board_id}/{token}")
def revoke_invite(
    board_id: str,
    token: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can revoke invite links")

    invite = invite_svc.get_invite_by_token(db, token)
    if not invite or invite.board_id != board.id:
        raise HTTPException(status_code=404, detail="Invite link not found")

    invite_svc.revoke_invite(db, invite)
    return {"detail": "Invite link revoked"}


# ── Accept invite ─────────────────────────────────────────────
@router.post("/accept/{token}", response_model=InviteAcceptResponse)
def accept_invite(
    token: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    invite = invite_svc.get_invite_by_token(db, token)
    if not invite:
        raise HTTPException(status_code=404, detail="Invite link not found")
    if not invite_svc.is_valid(invite):
        raise HTTPException(status_code=410, detail="Invite link is expired or used up")

    board = invite_svc.accept_invite(db, invite, user)
    return InviteAcceptResponse(
        board_id=board.board_id,
        board_name=board.display_name,
        role=invite.role,
    )


# ── Invite info (public, no auth) ────────────────────────────
@router.get("/info/{token}", response_model=InviteInfoResponse)
def invite_info(
    token: str,
    db: Session = Depends(get_db),
):
    invite = invite_svc.get_invite_by_token(db, token)
    if not invite:
        raise HTTPException(status_code=404, detail="Invite link not found")

    valid = invite_svc.is_valid(invite)
    return InviteInfoResponse(
        board_name=invite.board.display_name,
        role=invite.role,
        created_by=invite.creator.username,
        expires_at=invite.expires_at,
        valid=valid,
    )
