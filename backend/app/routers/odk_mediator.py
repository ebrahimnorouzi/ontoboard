"""ODK Mediator Router — exposes all 6 ODK workflows as SSE streaming endpoints.

Each endpoint returns a StreamingResponse with text/event-stream content type.
The frontend consumes these via EventSource or fetch + ReadableStream.
"""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse, FileResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import odk_mediator as mediator

router = APIRouter()


def _check(board_id: str, db: Session, user: User):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    return board


# ── Workflow 1: ODK Seed / Update Repo ─────────────────────────

@router.post("/{board_id}/seed")
async def odk_seed(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run ODK seed to scaffold a full standard ODK project."""
    board = _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "odk_seed", "ODK seed started")
    return StreamingResponse(mediator.stream_odk_seed(board_dir, board_id), media_type="text/event-stream")


@router.post("/{board_id}/update-repo")
async def update_repo(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run `make update_repo` to reload config from edit-file.yaml."""
    board = _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "update_repo", "Repository config update started")
    return StreamingResponse(mediator.stream_update_repo(board_dir, board_id), media_type="text/event-stream")


# ── Workflow 2: Refresh Imports ────────────────────────────────

class ImportRefreshRequest(BaseModel):
    prefixes: list[str] = []  # Optional: specific import prefixes to refresh


@router.post("/{board_id}/refresh-imports")
async def refresh_imports(board_id: str, body: ImportRefreshRequest | None = None,
                           db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run `make refresh-imports` to download/update import modules."""
    board = _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "refresh_imports", f"Refreshing imports")
    return StreamingResponse(mediator.stream_refresh_imports(board_dir), media_type="text/event-stream")


# ── Workflow 3: Reasoning / Test ───────────────────────────────

class ReasonRequest(BaseModel):
    reasoner: str = "ELK"  # ELK | HermiT | JFact | Whelk


@router.post("/{board_id}/reason")
async def run_reason(board_id: str, body: ReasonRequest | None = None,
                      db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run ROBOT reasoner (ELK/HermiT) with streaming output."""
    board = _check(board_id, db, user)
    body = body or ReasonRequest()
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "odk_reason", f"Running {body.reasoner} reasoner")
    return StreamingResponse(mediator.stream_reason(board_dir, body.reasoner), media_type="text/event-stream")


@router.post("/{board_id}/test")
async def run_test(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run `make test` — ontology test suite with SPARQL checks + reasoning."""
    board = _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "odk_test", "Running ontology test suite")
    return StreamingResponse(mediator.stream_test(board_dir), media_type="text/event-stream")


# ── Workflow 4: SPARQL Verification ────────────────────────────

class SparqlVerifyRequest(BaseModel):
    sparql_file: str  # Filename relative to src/sparql/


@router.post("/{board_id}/verify")
async def sparql_verify(board_id: str, body: SparqlVerifyRequest,
                         db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run `robot verify` with a custom SPARQL check file."""
    board = _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "sparql_verify", f"Verifying: {body.sparql_file}")
    return StreamingResponse(mediator.stream_sparql_verify(board_dir, body.sparql_file), media_type="text/event-stream")


# ── Workflow 5: Release ────────────────────────────────────────

@router.post("/{board_id}/release")
async def run_release(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run full release pipeline: test → prepare → build artifacts → publish."""
    board = _check(board_id, db, user)
    if not board_svc.can_manage(db, board, user):
        raise HTTPException(status_code=403, detail="Owner or admin required for release")
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "odk_release", "Release pipeline started")
    return StreamingResponse(mediator.stream_release(board_dir, board_id), media_type="text/event-stream")


@router.get("/{board_id}/release/artifacts")
def list_artifacts(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """List available release artifacts (download links)."""
    _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    return mediator.get_release_artifacts(board_dir)


@router.get("/{board_id}/release/download/{filename}")
def download_artifact(board_id: str, filename: str,
                       db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Download a specific release artifact."""
    _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    file_path = board_dir / "releases" / filename
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Artifact not found")
    # Prevent path traversal
    try:
        file_path.resolve().relative_to((board_dir / "releases").resolve())
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid filename")
    return FileResponse(str(file_path), filename=filename)


# ── Workflow 6: DOSDP Pattern ──────────────────────────────────

class DOSDPRequest(BaseModel):
    pattern_file: str
    data_file: str


@router.post("/{board_id}/dosdp")
async def run_dosdp(board_id: str, body: DOSDPRequest,
                     db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Run DOSDP pattern instantiation via ROBOT template."""
    board = _check(board_id, db, user)
    board_dir = DATA_DIR / board_id
    board_svc.log_activity(db, board, user, "dosdp_generate", f"Pattern: {body.pattern_file}")
    return StreamingResponse(
        mediator.stream_dosdp_generate(board_dir, body.pattern_file, body.data_file),
        media_type="text/event-stream",
    )
