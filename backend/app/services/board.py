"""Board service — CRUD, membership, access control, filesystem ops."""

import asyncio
import logging
import os
import shutil
import textwrap
from pathlib import Path

import docker
from docker.errors import ImageNotFound, DockerException
from dulwich.repo import Repo as DulwichRepo
from sqlalchemy.orm import Session

from app.config import DATA_DIR, ODK_IMAGE
from app.models.board import Board, BoardMember
from app.models.activity import Activity
from app.models.user import User

logger = logging.getLogger("ontoboard.board")

_seed_tasks: dict[str, asyncio.Task] = {}


# ── Queries ────────────────────────────────────────────────────
def get_board_by_slug(db: Session, board_id: str) -> Board | None:
    return db.query(Board).filter(Board.board_id == board_id).first()


def list_boards_for_user(db: Session, user: User | None) -> list[Board]:
    """Return boards visible to the user.
    - Admin sees everything
    - Authenticated user sees: own boards + public boards + boards shared with them
    - Anonymous sees only public boards
    """
    if user and user.is_admin:
        return db.query(Board).order_by(Board.updated_at.desc()).all()

    if user:
        own = db.query(Board).filter(Board.owner_id == user.id)
        shared_ids = (
            db.query(BoardMember.board_id)
            .filter(BoardMember.user_id == user.id)
            .subquery()
        )
        shared = db.query(Board).filter(Board.id.in_(shared_ids))
        public = db.query(Board).filter(Board.is_public.is_(True))
        return own.union(shared).union(public).order_by(Board.updated_at.desc()).all()

    return db.query(Board).filter(Board.is_public.is_(True)).order_by(Board.updated_at.desc()).all()


def list_all_boards(db: Session) -> list[Board]:
    return db.query(Board).order_by(Board.updated_at.desc()).all()


def count_boards(db: Session, public_only: bool = False) -> int:
    q = db.query(Board)
    if public_only:
        q = q.filter(Board.is_public.is_(True))
    return q.count()


# ── Create ─────────────────────────────────────────────────────
def create_board(
    db: Session,
    board_id: str,
    owner: User,
    display_name: str = "",
    description: str = "",
    is_public: bool = True,
    tags: str = "",
) -> Board:
    board = Board(
        board_id=board_id,
        display_name=display_name or board_id,
        description=description,
        owner_id=owner.id,
        is_public=is_public,
        tags=tags,
    )
    db.add(board)
    db.commit()
    db.refresh(board)

    log_activity(db, board, owner, "created", f"Board '{board_id}' created")
    return board


def update_board(db: Session, board: Board, user: User, **fields) -> Board:
    changed = []
    for key, val in fields.items():
        if val is not None and hasattr(board, key):
            old = getattr(board, key)
            if old != val:
                setattr(board, key, val)
                changed.append(key)
    db.commit()
    db.refresh(board)
    if changed:
        log_activity(db, board, user, "updated", f"Changed: {', '.join(changed)}")
    return board


def delete_board(db: Session, board: Board, user: User) -> None:
    slug = board.board_id
    # Cancel background seed
    if slug in _seed_tasks and not _seed_tasks[slug].done():
        _seed_tasks[slug].cancel()
    _seed_tasks.pop(slug, None)
    # Remove filesystem
    board_dir = DATA_DIR / slug
    if board_dir.exists():
        shutil.rmtree(board_dir)
    db.delete(board)
    db.commit()


# ── Membership / sharing ──────────────────────────────────────
def get_member(db: Session, board: Board, user: User) -> BoardMember | None:
    return (
        db.query(BoardMember)
        .filter(BoardMember.board_id == board.id, BoardMember.user_id == user.id)
        .first()
    )


def add_member(db: Session, board: Board, user: User, role: str, actor: User) -> BoardMember:
    existing = get_member(db, board, user)
    if existing:
        existing.role = role
        db.commit()
        db.refresh(existing)
        log_activity(db, board, actor, "shared", f"Updated {user.username} to {role}")
        return existing
    member = BoardMember(board_id=board.id, user_id=user.id, role=role)
    db.add(member)
    db.commit()
    db.refresh(member)
    log_activity(db, board, actor, "shared", f"Added {user.username} as {role}")
    return member


def remove_member(db: Session, board: Board, user: User, actor: User) -> None:
    member = get_member(db, board, user)
    if member:
        db.delete(member)
        db.commit()
        log_activity(db, board, actor, "unshared", f"Removed {user.username}")


def list_members(db: Session, board: Board) -> list[BoardMember]:
    return db.query(BoardMember).filter(BoardMember.board_id == board.id).all()


# ── Access checks ──────────────────────────────────────────────
def user_role_on_board(db: Session, board: Board, user: User | None) -> str | None:
    """Return the effective role: 'owner', 'editor', 'viewer', or None."""
    if user is None:
        if board.is_public and board.allow_anonymous_view:
            return "viewer"
        return None
    if user.is_admin:
        return "owner"
    if board.owner_id == user.id:
        return "owner"
    member = get_member(db, board, user)
    if member:
        return member.role
    if board.is_public:
        return "viewer"
    return None


def can_view(db: Session, board: Board, user: User | None) -> bool:
    role = user_role_on_board(db, board, user)
    return role is not None


def can_edit(db: Session, board: Board, user: User | None) -> bool:
    if user is None:
        return board.is_public and board.allow_anonymous_edit
    role = user_role_on_board(db, board, user)
    return role in ("owner", "editor")


def can_manage(db: Session, board: Board, user: User) -> bool:
    return user.is_admin or board.owner_id == user.id


# ── Activity log ───────────────────────────────────────────────
def log_activity(db: Session, board: Board, user: User | None, action: str, detail: str = "") -> Activity:
    act = Activity(
        board_id=board.id,
        user_id=user.id if user else None,
        action=action,
        detail=detail,
    )
    db.add(act)
    db.commit()
    return act


def get_activities(db: Session, board: Board, limit: int = 50) -> list[Activity]:
    return (
        db.query(Activity)
        .filter(Activity.board_id == board.id)
        .order_by(Activity.created_at.desc())
        .limit(limit)
        .all()
    )


def count_activities(db: Session) -> int:
    return db.query(Activity).count()


# ── Filesystem provisioning ───────────────────────────────────
def provision_directory(board_id: str) -> Path:
    """Create board directory with minimal ODK scaffold. Returns the path."""
    board_dir = DATA_DIR / board_id
    board_dir.mkdir(parents=True, exist_ok=True)

    ont_dir = board_dir / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)

    owl_content = textwrap.dedent(f"""\
        <?xml version="1.0"?>
        <rdf:RDF xmlns="http://example.org/{board_id}#"
             xml:base="http://example.org/{board_id}"
             xmlns:owl="http://www.w3.org/2002/07/owl#"
             xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
             xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
            <owl:Ontology rdf:about="http://example.org/{board_id}">
                <rdfs:label>{board_id}</rdfs:label>
            </owl:Ontology>
        </rdf:RDF>
    """)
    (ont_dir / f"{board_id}.owl").write_text(owl_content)

    makefile = textwrap.dedent(f"""\
        ONT_ID := {board_id}
        .PHONY: all
        all:
        \t@echo "Ontology: $(ONT_ID)"
        \t@echo "Run ODK seed to generate full Makefile"
    """)
    (ont_dir / "Makefile").write_text(makefile)

    catalog = textwrap.dedent(f"""\
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <catalog prefer="public" xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
            <uri id="User Coverage" name="http://example.org/{board_id}.owl" uri="{board_id}.owl"/>
        </catalog>
    """)
    (ont_dir / "catalog-v001.xml").write_text(catalog)
    (board_dir / "uploads").mkdir(exist_ok=True)

    return board_dir


def git_init(board_dir: Path) -> None:
    repo = DulwichRepo.init(str(board_dir))
    _stage_all(repo, board_dir)
    repo.do_commit(
        b"Initial board scaffold",
        committer=b"OntoBoard <ontoboard@local>",
        author=b"OntoBoard <ontoboard@local>",
    )


def git_commit(board_dir: Path, message: str) -> None:
    repo = DulwichRepo(str(board_dir))
    _stage_all(repo, board_dir)
    repo.do_commit(
        message.encode(),
        committer=b"OntoBoard <ontoboard@local>",
        author=b"OntoBoard <ontoboard@local>",
    )


def _stage_all(repo: DulwichRepo, board_dir: Path) -> None:
    for root, _dirs, files in os.walk(str(board_dir)):
        for fname in files:
            fpath = os.path.join(root, fname)
            rel = os.path.relpath(fpath, str(board_dir))
            if rel.startswith(".git"):
                continue
            repo.stage([rel.encode()])


async def try_odk_seed_background(board_id: str) -> None:
    """Attempt ODK seed in background. Fails silently if odkfull unavailable."""
    board_dir = DATA_DIR / board_id
    loop = asyncio.get_event_loop()
    try:
        await loop.run_in_executor(None, _odk_seed_sync, board_id, board_dir)
        await loop.run_in_executor(None, git_commit, board_dir, "ODK seed complete")
        logger.info("ODK seed completed for '%s'", board_id)
    except ImageNotFound:
        logger.warning("ODK image '%s' not found — skipping seed for '%s'", ODK_IMAGE, board_id)
    except DockerException as exc:
        logger.warning("Docker unavailable for ODK seed on '%s': %s", board_id, exc)
    except Exception as exc:
        logger.error("ODK seed failed for '%s': %s", board_id, exc)


def _odk_seed_sync(board_id: str, board_dir: Path) -> None:
    client = docker.from_env()
    client.images.get(ODK_IMAGE)
    client.containers.run(
        image=ODK_IMAGE,
        command=f"seed -n {board_id} -t my-ont -d 'Ontology created by OntoBoard' -u https://example.org/{board_id}",
        volumes={str(board_dir): {"bind": "/work", "mode": "rw"}},
        working_dir="/work",
        remove=True,
        stdout=True,
        stderr=True,
    )


def board_dir_info(board_id: str) -> dict:
    """Return filesystem info about a board."""
    board_dir = DATA_DIR / board_id
    return {
        "odk_seeded": (board_dir / "src" / "ontology").is_dir(),
        "git_initialized": (board_dir / ".git").is_dir(),
    }


def register_seed_task(board_id: str, task: asyncio.Task) -> None:
    _seed_tasks[board_id] = task


def is_seed_running(board_id: str) -> bool:
    return board_id in _seed_tasks and not _seed_tasks[board_id].done()
