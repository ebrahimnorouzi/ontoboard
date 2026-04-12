"""Board router — CRUD, sharing, access control, activity."""

import asyncio

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, status
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user, require_admin
from app.models.user import User
from app.schemas.board import (
    BoardCreate, BoardUpdate, BoardOut, BoardDetail,
    BoardMemberOut, ShareRequest, ActivityOut, AdminStats,
)
from app.services import board as board_svc
from app.services import user as user_svc
from app.services import notification as notif_svc

router = APIRouter()


def _sanitize_id(board_id: str) -> str:
    safe = "".join(c for c in board_id if c.isalnum() or c in "-_")
    if not safe:
        raise HTTPException(status_code=400, detail="Invalid board id")
    return safe


def _enrich(board, db, user=None) -> BoardDetail:
    info = board_svc.board_dir_info(board.board_id)
    return BoardDetail(
        id=board.id,
        board_id=board.board_id,
        display_name=board.display_name,
        description=board.description,
        owner_id=board.owner_id,
        is_public=board.is_public,
        is_starred=board.is_starred,
        tags=board.tags,
        created_at=board.created_at,
        updated_at=board.updated_at,
        allow_anonymous_view=board.allow_anonymous_view,
        allow_anonymous_edit=board.allow_anonymous_edit,
        owner_username=board.owner.username if board.owner else "",
        member_count=len(board.members),
        activity_count=len(board.activities),
        user_role=board_svc.user_role_on_board(db, board, user),
        **info,
    )


# ── List boards ────────────────────────────────────────────────
@router.get("/", response_model=list[BoardDetail])
def list_boards(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    boards = board_svc.list_boards_for_user(db, user)
    return [_enrich(b, db, user) for b in boards]


# ── Get board ──────────────────────────────────────────────────
@router.get("/{board_id}", response_model=BoardDetail)
def get_board(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return _enrich(board, db, user)


# ── Create board ───────────────────────────────────────────────
@router.post("/{board_id}", response_model=BoardDetail, status_code=201)
async def create_board(
    board_id: str,
    body: BoardCreate | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    slug = _sanitize_id(board_id)
    if board_svc.get_board_by_slug(db, slug):
        raise HTTPException(status_code=409, detail="Board already exists")

    body = body or BoardCreate()
    board = board_svc.create_board(
        db, slug, user,
        display_name=body.display_name or slug,
        description=body.description,
        is_public=body.is_public,
        tags=body.tags,
    )

    # Provision filesystem
    loop = asyncio.get_event_loop()
    board_dir = await loop.run_in_executor(None, board_svc.provision_directory, slug)
    await loop.run_in_executor(None, board_svc.git_init, board_dir)

    # Background ODK seed (passes versioning strategy)
    task = asyncio.create_task(board_svc.try_odk_seed_background(slug, body.versioning_strategy))
    board_svc.register_seed_task(slug, task)

    return _enrich(board, db, user)


# ── Create board from file upload ──────────────────────────────
@router.post("/{board_id}/from-file", response_model=BoardDetail, status_code=201)
async def create_board_from_file(
    board_id: str,
    file: UploadFile = File(...),
    is_public: bool = True,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Create a board by uploading an OWL/TTL/OBO/JSONLD/RDF file."""
    slug = _sanitize_id(board_id)
    if board_svc.get_board_by_slug(db, slug):
        raise HTTPException(status_code=409, detail="Board already exists")

    board = board_svc.create_board(db, slug, user, display_name=slug, is_public=is_public)

    # Provision filesystem
    loop = asyncio.get_event_loop()
    board_dir = await loop.run_in_executor(None, board_svc.provision_directory, slug)
    await loop.run_in_executor(None, board_svc.git_init, board_dir)

    # Parse uploaded file and overwrite the scaffold OWL
    contents = await file.read()
    content_str = contents.decode("utf-8", errors="replace")

    from app.services.conversion import parse_any_format
    try:
        g = parse_any_format(content_str, file.filename or "upload.owl")
        if len(g) > 0:
            owl_path = board_dir / "src" / "ontology" / f"{slug}.owl"
            g.serialize(str(owl_path), format="xml")
            board_svc.git_commit(board_dir, f"Imported from {file.filename}")
    except Exception as exc:
        # Board was created but file parsing failed — board still usable with empty scaffold
        board_svc.log_activity(db, board, user, "import_error", str(exc)[:200])

    return _enrich(board, db, user)


# ── Update board ───────────────────────────────────────────────
@router.patch("/{board_id}", response_model=BoardDetail)
def update_board(
    board_id: str,
    body: BoardUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can update settings")
    board_svc.update_board(db, board, user, **body.model_dump(exclude_unset=True))
    return _enrich(board, db, user)


# ── Delete board ───────────────────────────────────────────────
@router.delete("/{board_id}")
def delete_board(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can delete")
    board_svc.delete_board(db, board, user)
    return {"detail": f"Board '{board_id}' deleted"}


# ── Share / Members ────────────────────────────────────────────
@router.get("/{board_id}/members", response_model=list[BoardMemberOut])
def list_members(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    members = board_svc.list_members(db, board)
    return [
        BoardMemberOut(user_id=m.user_id, username=m.user.username, role=m.role, added_at=m.added_at)
        for m in members
    ]


@router.post("/{board_id}/share", status_code=201)
def share_board(
    board_id: str,
    body: ShareRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can share")
    if body.role not in ("editor", "viewer"):
        raise HTTPException(status_code=400, detail="Role must be 'editor' or 'viewer'")
    target = user_svc.get_user_by_username(db, body.username)
    if not target:
        raise HTTPException(status_code=404, detail="User not found")
    board_svc.add_member(db, board, target, body.role, user)
    notif_svc.notify_board_shared(db, target.id, board_id, body.role, user.username)
    return {"detail": f"Shared with {body.username} as {body.role}"}


@router.delete("/{board_id}/share/{username}")
def unshare_board(
    board_id: str,
    username: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Only the owner or admin can manage sharing")
    target = user_svc.get_user_by_username(db, username)
    if not target:
        raise HTTPException(status_code=404, detail="User not found")
    board_svc.remove_member(db, board, target, user)
    return {"detail": f"Removed {username} from board"}


# ── Activity log ───────────────────────────────────────────────
@router.get("/{board_id}/activity", response_model=list[ActivityOut])
def board_activity(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, _sanitize_id(board_id))
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    activities = board_svc.get_activities(db, board)
    return [
        ActivityOut(
            id=a.id, action=a.action, detail=a.detail,
            user_id=a.user_id,
            username=a.user.username if a.user else None,
            created_at=a.created_at,
        )
        for a in activities
    ]


# ── Admin stats ────────────────────────────────────────────────
@router.get("/admin/stats", response_model=AdminStats)
def admin_stats(db: Session = Depends(get_db), _admin: User = Depends(require_admin)):
    total_boards = board_svc.count_boards(db)
    public_boards = board_svc.count_boards(db, public_only=True)
    return AdminStats(
        total_users=user_svc.count_users(db),
        active_users=user_svc.count_users(db, active_only=True),
        total_boards=total_boards,
        public_boards=public_boards,
        private_boards=total_boards - public_boards,
        total_activities=board_svc.count_activities(db),
    )
