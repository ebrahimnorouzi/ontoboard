"""ODK Import Workflow Router — guided 6-step import pipeline."""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services import odk_imports as imp_svc

router = APIRouter()


def _check(board_id, db, user):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    return board


# ── List all imports ───────────────────────────────────────────

@router.get("/{board_id}")
def list_imports(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """List all declared imports with their status."""
    _check(board_id, db, user)
    return imp_svc.list_declared_imports(board_svc.get_board_dir(board_id), board_id)


# ── Step 1: Declare import ─────────────────────────────────────

class DeclareImportBody(BaseModel):
    id: str                        # e.g. "ro", "bfo", "iao"
    mirror_from: str = ""          # URL for non-OBO ontologies
    module_type: str = "custom"    # mirror | custom | slme
    module_type_slme: str = ""     # TOP | BOT | STAR | SUBSET
    slme_individuals: str = ""     # exclude | include
    use_base: bool = False


@router.post("/{board_id}/declare", status_code=201)
def declare_import(board_id: str, body: DeclareImportBody,
                    db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Step 1: Declare an import in odk.yaml."""
    board = _check(board_id, db, user)
    result = imp_svc.declare_import(board_svc.get_board_dir(board_id), board_id, body.model_dump())
    if not result["success"]:
        raise HTTPException(status_code=400, detail=result.get("error", "Failed"))
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Declared import: {body.id}")
    board_svc.log_activity(db, board, user, "import_declared", body.id)
    return result


# ── Step 2: Check Makefile ─────────────────────────────────────

@router.get("/{board_id}/check-makefile")
def check_makefile(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Step 2: Read the Makefile to verify import targets."""
    _check(board_id, db, user)
    return imp_svc.check_makefile(board_svc.get_board_dir(board_id), board_id)


# ── Step 3: Add terms ─────────────────────────────────────────

class AddTermsBody(BaseModel):
    import_id: str
    term_iris: list[str]


@router.post("/{board_id}/add-terms")
def add_terms(board_id: str, body: AddTermsBody,
               db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Step 3: Add term IRIs to the import's terms.txt file."""
    board = _check(board_id, db, user)
    result = imp_svc.add_import_terms(board_svc.get_board_dir(board_id), body.import_id, body.term_iris)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Added {result['new_terms_added']} terms to {body.import_id}")
    return result


@router.get("/{board_id}/terms/{import_id}")
def get_terms(board_id: str, import_id: str,
               db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Get current terms for an import."""
    _check(board_id, db, user)
    return imp_svc.get_import_terms(board_svc.get_board_dir(board_id), import_id)


# ── Step 4: Register import in edit.owl + catalog ──────────────

class RegisterImportBody(BaseModel):
    import_id: str
    import_iri: str = ""  # Auto-derived from OBO if empty


@router.post("/{board_id}/register")
def register_import(board_id: str, body: RegisterImportBody,
                     db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Step 4: Add import URI to edit.owl and catalog-v001.xml."""
    board = _check(board_id, db, user)
    result = imp_svc.register_import(board_svc.get_board_dir(board_id), board_id, body.import_id, body.import_iri)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Registered import: {body.import_id}")
    return result


# ── Step 5: Add to custom Makefile ─────────────────────────────

@router.post("/{board_id}/add-makefile-target/{import_id}")
def add_makefile_target(board_id: str, import_id: str,
                         db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Step 5: Add import schema to the custom .Makefile."""
    board = _check(board_id, db, user)
    result = imp_svc.add_import_to_custom_makefile(board_svc.get_board_dir(board_id), board_id, import_id)
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Added {import_id} to custom Makefile")
    return result


# ── Step 6: Configure module_type ──────────────────────────────

class ConfigureBody(BaseModel):
    import_id: str
    module_type: str = "custom"  # mirror | custom | slme


@router.post("/{board_id}/configure")
def configure_import(board_id: str, body: ConfigureBody,
                      db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Step 6: Set module_type in odk.yaml."""
    board = _check(board_id, db, user)
    result = imp_svc.configure_import(board_svc.get_board_dir(board_id), board_id, body.import_id, body.module_type)
    if not result["success"]:
        raise HTTPException(status_code=400, detail=result.get("error", "Failed"))
    board_svc.git_commit(board_svc.get_board_dir(board_id), f"Configured {body.import_id} as {body.module_type}")
    return result
