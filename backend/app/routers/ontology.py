"""Ontology metadata, statistics, and ROBOT report router."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user_optional
from app.models.user import User
from app.schemas.ontology import (
    OntologyMetadata, OntologyStats, RobotReportResult, DashboardData, PrefixEntry,
)
from app.services import board as board_svc
from app.services import ontology as ont_svc
from app.services.odk import find_owl_file
from app.services.robot import robot_report

router = APIRouter()


def _require_board_view(board_id: str, db: Session, user: User | None):
    """Validate board exists and user can view it."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return board


@router.get("/{board_id}/metadata", response_model=OntologyMetadata)
def get_metadata(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Parse the board's OWL file and return ontology IRI, prefixes, imports, languages."""
    _require_board_view(board_id, db, user)
    board_dir = DATA_DIR / board_id
    try:
        g = ont_svc.load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    meta = ont_svc.get_ontology_metadata(g)
    return OntologyMetadata(
        ontology_iri=meta["ontology_iri"],
        version_iri=meta["version_iri"],
        imports=meta["imports"],
        prefixes=[PrefixEntry(**p) for p in meta["prefixes"]],
        languages=meta["languages"],
    )


@router.get("/{board_id}/statistics", response_model=OntologyStats)
def get_statistics(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Count classes, properties, individuals, and axioms in the ontology."""
    _require_board_view(board_id, db, user)
    board_dir = DATA_DIR / board_id
    try:
        g = ont_svc.load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")
    return OntologyStats(**ont_svc.get_ontology_statistics(g))


@router.post("/{board_id}/report", response_model=RobotReportResult)
async def run_report(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Run ROBOT report on the ontology. Requires odkfull Docker image."""
    _require_board_view(board_id, db, user)
    board_dir = DATA_DIR / board_id
    owl_file = find_owl_file(board_dir)
    if not owl_file:
        raise HTTPException(status_code=404, detail="No OWL file found")

    result = await robot_report(board_dir, owl_file)
    report_path = board_dir / "report.tsv"
    parsed = ont_svc.parse_robot_report_tsv(report_path)

    return RobotReportResult(
        exit_code=result.exit_code,
        violations=[dict_to_violation(v) for v in parsed["violations"]],
        summary=parsed["summary"],
    )


@router.get("/{board_id}/dashboard", response_model=DashboardData)
def get_dashboard(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Combined endpoint: metadata + statistics (report must be triggered separately)."""
    _require_board_view(board_id, db, user)
    board_dir = DATA_DIR / board_id
    try:
        g = ont_svc.load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file found")

    meta = ont_svc.get_ontology_metadata(g)
    stats = ont_svc.get_ontology_statistics(g)

    # Check if a cached report exists
    report = None
    report_path = board_dir / "report.tsv"
    if report_path.exists():
        parsed = ont_svc.parse_robot_report_tsv(report_path)
        report = RobotReportResult(
            exit_code=0,
            violations=[dict_to_violation(v) for v in parsed["violations"]],
            summary=parsed["summary"],
        )

    return DashboardData(
        metadata=OntologyMetadata(
            ontology_iri=meta["ontology_iri"],
            version_iri=meta["version_iri"],
            imports=meta["imports"],
            prefixes=[PrefixEntry(**p) for p in meta["prefixes"]],
            languages=meta["languages"],
        ),
        statistics=OntologyStats(**stats),
        report=report,
    )


def dict_to_violation(d: dict):
    from app.schemas.ontology import ReportViolation
    return ReportViolation(**d)
