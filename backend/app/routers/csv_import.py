"""CSV import router — ROBOT Template Builder endpoints."""

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.csv_import import (
    CsvAnalysis, ColumnInfo, OntologyEntities, OntologyEntity,
    TemplateRequest, TemplateResult,
    BuildRequest, BuildResult,
    MergeRequest, MergeResult,
    KgFiles, KgFileInfo,
)
from app.services import board as board_svc
from app.services import csv_import as csv_svc

router = APIRouter()


def _get_board_edit(board_id: str, db: Session, user: User):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    return board


# ── Upload CSV/TSV ────────────────────────────────────────────

@router.post("/{board_id}/upload", response_model=CsvAnalysis)
async def upload_csv(
    board_id: str,
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Upload a CSV/TSV file, save to kg/uploads/, return analysis."""
    board = _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)

    # Save to kg/uploads/
    upload_dir = board_dir / "kg" / "uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)
    dest = upload_dir / file.filename
    contents = await file.read()
    dest.write_bytes(contents)

    try:
        analysis = csv_svc.analyze_csv(board_dir, file.filename)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    board_svc.log_activity(
        db, board, user, "csv_uploaded",
        f"{file.filename}: {analysis['row_count']} rows",
    )

    return CsvAnalysis(
        filename=analysis["filename"],
        columns=[ColumnInfo(**c) for c in analysis["columns"]],
        row_count=analysis["row_count"],
        delimiter=analysis["delimiter"],
        sample_rows=analysis["sample_rows"],
    )


# ── Ontology entities for dropdowns ──────────────────────────

@router.get("/{board_id}/entities", response_model=OntologyEntities)
def get_entities(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Get ontology entities (classes, properties) for mapping dropdowns."""
    _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    data = csv_svc.get_ontology_entities(board_dir)
    return OntologyEntities(
        classes=[OntologyEntity(**e) for e in data["classes"]],
        object_properties=[OntologyEntity(**e) for e in data["object_properties"]],
        data_properties=[OntologyEntity(**e) for e in data["data_properties"]],
        annotation_properties=[OntologyEntity(**e) for e in data["annotation_properties"]],
    )


# ── Generate ROBOT template ─────────────────────────────────

@router.post("/{board_id}/template", response_model=TemplateResult)
def generate_template(
    board_id: str,
    body: TemplateRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Generate a ROBOT template TSV from column mappings."""
    board = _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)

    try:
        result = csv_svc.generate_robot_template(
            board_dir,
            body.filename,
            [m.model_dump() for m in body.column_mappings],
            body.iri_strategy,
            body.base_iri,
            body.template_name,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))

    board_svc.log_activity(
        db, board, user, "template_generated",
        f"{result['template_name']}: {result['total_data_rows']} rows",
    )

    return TemplateResult(**result)


# ── Build KG from template ──────────────────────────────────

@router.post("/{board_id}/build", response_model=BuildResult)
async def build_kg(
    board_id: str,
    body: BuildRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Build KG from a ROBOT template using the robot template command."""
    board = _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)

    result = await csv_svc.build_kg_from_template(board_dir, body.template_path)

    if result["success"]:
        board_svc.git_commit(board_dir, f"Built KG from {body.template_path}")
        board_svc.log_activity(
            db, board, user, "kg_built",
            f"{result['triples_count']} triples",
        )

    return BuildResult(**result)


# ── Merge KG files ───────────────────────────────────────────

@router.post("/{board_id}/merge", response_model=MergeResult)
async def merge_kgs(
    board_id: str,
    body: MergeRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Merge multiple KG OWL files into one."""
    board = _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)

    result = await csv_svc.merge_kg_files(board_dir, body.file_paths)

    if result["success"]:
        board_svc.git_commit(board_dir, "Merged KG files")
        board_svc.log_activity(
            db, board, user, "kg_merged",
            f"{result['triples_count']} triples",
        )

    return MergeResult(**result)


# ── List KG files ────────────────────────────────────────────

@router.get("/{board_id}/files", response_model=KgFiles)
def list_files(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """List all KG files (uploads, templates, outputs)."""
    _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    data = csv_svc.list_kg_files(board_dir)
    return KgFiles(
        uploads=[KgFileInfo(**f) for f in data["uploads"]],
        templates=[KgFileInfo(**f) for f in data["templates"]],
        output=[KgFileInfo(**f) for f in data["output"]],
    )


# ── Download a KG file ──────────────────────────────────────

@router.get("/{board_id}/download/{file_path:path}")
def download_file(
    board_id: str,
    file_path: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Download a KG file (upload, template, or output)."""
    _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)

    # Security: ensure the path stays within the board's kg/ directory
    full_path = (board_dir / file_path).resolve()
    kg_root = (board_dir / "kg").resolve()
    if not str(full_path).startswith(str(kg_root)):
        raise HTTPException(status_code=403, detail="Access denied")
    if not full_path.exists():
        raise HTTPException(status_code=404, detail="File not found")

    return FileResponse(
        path=str(full_path),
        filename=full_path.name,
        media_type="application/octet-stream",
    )
