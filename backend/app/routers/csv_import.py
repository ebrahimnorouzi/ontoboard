"""CSV import router — upload, analyze, preview, build KG."""

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.csv_import import (
    CsvAnalysis, ColumnInfo, KgPreviewRequest, KgBuildRequest, KgBuildResult,
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


@router.post("/{board_id}/upload", response_model=CsvAnalysis)
async def upload_csv(
    board_id: str,
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Upload a CSV/TSV file and return analysis."""
    board = _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)
    upload_dir = board_dir / "uploads"
    upload_dir.mkdir(exist_ok=True)

    dest = upload_dir / file.filename
    contents = await file.read()
    dest.write_bytes(contents)

    analysis = csv_svc.analyze_csv(dest)
    board_svc.log_activity(db, board, user, "csv_uploaded", f"{file.filename}: {analysis['row_count']} rows")

    return CsvAnalysis(
        filename=analysis["filename"],
        separator=analysis["separator"],
        row_count=analysis["row_count"],
        columns=[ColumnInfo(**c) for c in analysis["columns"]],
        sample_rows=analysis["sample_rows"],
    )


@router.get("/{board_id}/files", response_model=list[str])
def list_files(
    board_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """List uploaded CSV files for this board."""
    _get_board_edit(board_id, db, user)
    return csv_svc.list_csv_files(board_svc.get_board_dir(board_id))


@router.post("/{board_id}/preview")
def preview_kg(
    board_id: str,
    body: KgPreviewRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Preview generated triples without building the full KG."""
    _get_board_edit(board_id, db, user)
    csv_path = board_svc.get_board_dir(board_id) / "uploads" / body.csv_file
    if not csv_path.exists():
        raise HTTPException(status_code=404, detail="CSV file not found")

    triples = csv_svc.preview_kg(
        csv_path,
        [m.model_dump() for m in body.mappings],
        body.iri_strategy,
        body.iri_pattern,
        body.base_iri,
        body.limit,
    )
    return {"triples": triples, "count": len(triples)}


@router.post("/{board_id}/build", response_model=KgBuildResult)
def build_kg(
    board_id: str,
    body: KgBuildRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Build a full knowledge graph from CSV + mappings."""
    board = _get_board_edit(board_id, db, user)
    board_dir = board_svc.get_board_dir(board_id)

    try:
        result = csv_svc.build_knowledge_graph(
            board_dir,
            body.csv_file,
            [m.model_dump() for m in body.mappings],
            body.iri_strategy,
            body.iri_pattern,
            body.base_iri,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))

    board_svc.git_commit(board_dir, f"Built KG from {body.csv_file}")
    board_svc.log_activity(db, board, user, "kg_built",
                           f"{result['individuals_count']} individuals, {result['triples_count']} triples")

    return KgBuildResult(**result)
