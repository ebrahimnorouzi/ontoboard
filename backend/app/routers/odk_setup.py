"""ODK Setup Router — create ODK-compliant boards, manage files, edit YAML."""

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import odk_setup

router = APIRouter()


class OdkBoardCreate(BaseModel):
    ont_id: str
    title: str = ""
    mode: str = "odk"  # "odk" (full seed) | "blank" (minimal)
    yaml_config: str = ""  # Optional: pre-filled YAML content
    versioning_strategy: str = "date"  # "date" or "semantic"


@router.post("/create-board", status_code=201)
async def create_odk_board(
    body: OdkBoardCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Create a new board with full ODK setup (seed + YAML + git)."""
    ont_id = body.ont_id.strip().replace(" ", "-").lower()
    if board_svc.get_board_by_slug(db, ont_id):
        raise HTTPException(status_code=409, detail="Board already exists")

    # Create board in DB
    board = board_svc.create_board(db, ont_id, user, display_name=body.title or ont_id)
    board_dir = DATA_DIR / ont_id

    if body.mode == "odk":
        # Run full ODK seed
        result = odk_setup.run_odk_seed(board_dir, ont_id, body.title, body.versioning_strategy)
    else:
        # Blank board with minimal scaffold
        board_dir.mkdir(parents=True, exist_ok=True)
        odk_setup._create_manual_scaffold(board_dir, ont_id, body.title or ont_id, body.versioning_strategy)
        result = {"success": True, "files": [], "edit_owl": f"src/ontology/{ont_id}-edit.owl",
                   "yaml_path": f"src/ontology/{ont_id}-odk.yaml", "ont_id": ont_id}

    # Save custom YAML if provided
    if body.yaml_config:
        odk_setup.save_odk_yaml(board_dir, ont_id, body.yaml_config)

    # Git init
    import asyncio
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, board_svc.git_init, board_dir)

    board_svc.log_activity(db, board, user, "odk_setup", f"Created {body.mode} board: {ont_id}")

    return {
        "board_id": ont_id,
        "mode": body.mode,
        **result,
    }


class GithubImport(BaseModel):
    url: str
    ont_id: str
    title: str = ""


@router.post("/import-zip", status_code=201)
async def import_zip(
    file: UploadFile = File(...),
    ont_id: str = Form(...),
    title: str = Form(""),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Import a board from an uploaded ZIP file containing an ODK repository."""
    ont_id = ont_id.strip().replace(" ", "-").lower()
    if board_svc.get_board_by_slug(db, ont_id):
        raise HTTPException(status_code=409, detail="Board already exists")

    board = board_svc.create_board(db, ont_id, user, display_name=title or ont_id)
    board_dir = DATA_DIR / ont_id

    try:
        zip_content = await file.read()
        files = odk_setup.import_from_zip(board_dir, zip_content)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"ZIP extraction failed: {exc}")

    # Git init + commit
    import asyncio
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, board_svc.git_init, board_dir)

    board_svc.log_activity(db, board, user, "import_zip", f"Imported board from ZIP: {ont_id}")

    return {
        "board_id": ont_id,
        "mode": "import-zip",
        "success": True,
        "files": files,
    }


@router.post("/import-github", status_code=201)
async def import_github(
    body: GithubImport,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Import a board by cloning a GitHub repository."""
    ont_id = body.ont_id.strip().replace(" ", "-").lower()
    if board_svc.get_board_by_slug(db, ont_id):
        raise HTTPException(status_code=409, detail="Board already exists")

    board = board_svc.create_board(db, ont_id, user, display_name=body.title or ont_id)
    board_dir = DATA_DIR / ont_id

    try:
        files = odk_setup.import_from_github(board_dir, body.url)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"GitHub clone failed: {exc}")

    board_svc.log_activity(db, board, user, "import_github", f"Imported board from GitHub: {body.url}")

    return {
        "board_id": ont_id,
        "mode": "import-github",
        "success": True,
        "files": files,
    }


@router.get("/{board_id}/yaml")
def get_yaml(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Get the ODK YAML configuration file content."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    content = odk_setup.get_odk_yaml(DATA_DIR / board_id, board_id)
    return PlainTextResponse(content or "# No ODK YAML config found")


class YamlSave(BaseModel):
    content: str


@router.put("/{board_id}/yaml")
def save_yaml(board_id: str, body: YamlSave, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Save edited ODK YAML configuration."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    odk_setup.save_odk_yaml(DATA_DIR / board_id, board_id, body.content)
    board_svc.git_commit(DATA_DIR / board_id, "Updated ODK YAML config")
    return {"success": True}


@router.get("/{board_id}/files")
def list_files(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """List all files in the board directory."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    return odk_setup.list_board_files(DATA_DIR / board_id)


@router.get("/{board_id}/file/{file_path:path}")
def read_file(board_id: str, file_path: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Read a specific file from the board."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    content = odk_setup.read_board_file(DATA_DIR / board_id, file_path)
    if content is None:
        raise HTTPException(status_code=404, detail="File not found")
    return PlainTextResponse(content)


class FileWrite(BaseModel):
    content: str


@router.put("/{board_id}/file/{file_path:path}")
def write_file(board_id: str, file_path: str, body: FileWrite,
                db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Write/edit a file in the board directory."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = odk_setup.write_board_file(DATA_DIR / board_id, file_path, body.content)
    if not ok:
        raise HTTPException(status_code=400, detail="Invalid file path")
    board_svc.git_commit(DATA_DIR / board_id, f"Edited: {file_path}")
    return {"success": True}
