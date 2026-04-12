"""Ontology metadata, statistics, and ROBOT report router."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user, get_current_user_optional
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

    # Build a detailed summary that includes stdout/stderr on failure
    summary = parsed["summary"]
    if result.exit_code != 0:
        details = []
        if result.stderr:
            details.append(f"stderr: {result.stderr[:500]}")
        if result.stdout:
            details.append(f"stdout: {result.stdout[:500]}")
        if details:
            summary = f"{summary} | {' | '.join(details)}"
        if not summary:
            summary = f"ROBOT failed with exit code {result.exit_code}"

    return RobotReportResult(
        exit_code=result.exit_code,
        violations=[dict_to_violation(v) for v in parsed["violations"]],
        summary=summary,
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


# ── Metadata CRUD (Phase 17) ──────────────────────────────────
from app.services import metadata as meta_svc
from pydantic import BaseModel


class MetadataUpdateBody(BaseModel):
    fields: dict  # {title: "...", creator: "...", license: "...", ...}


class PrefixCreateBody(BaseModel):
    prefix: str
    namespace: str


class FindReplaceBody(BaseModel):
    find: str
    replace: str
    property_iri: str | None = None


@router.put("/{board_id}/metadata")
def update_metadata(
    board_id: str, body: MetadataUpdateBody,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Update DC/DCTERMS metadata on the ontology."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = meta_svc.update_metadata(DATA_DIR / board_id, body.fields)
    board_svc.git_commit(DATA_DIR / board_id, "Updated ontology metadata")
    board_svc.log_activity(db, board, user, "metadata_updated", str(list(body.fields.keys())))
    return result


@router.post("/{board_id}/prefixes", status_code=201)
def add_prefix(
    board_id: str, body: PrefixCreateBody,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Add a namespace prefix binding."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    meta_svc.add_prefix(DATA_DIR / board_id, body.prefix, body.namespace)
    board_svc.git_commit(DATA_DIR / board_id, f"Added prefix {body.prefix}")
    return {"success": True}


@router.delete("/{board_id}/prefixes/{prefix}")
def remove_prefix(
    board_id: str, prefix: str,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Remove a namespace prefix binding."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    meta_svc.remove_prefix(DATA_DIR / board_id, prefix)
    return {"success": True}


@router.post("/{board_id}/find-replace")
def find_replace(
    board_id: str, body: FindReplaceBody,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Find and replace text across annotation values."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    count = meta_svc.find_replace_annotations(DATA_DIR / board_id, body.find, body.replace, body.property_iri)
    if count > 0:
        board_svc.git_commit(DATA_DIR / board_id, f"Find/replace: '{body.find}' → '{body.replace}' ({count} changes)")
    return {"replaced": count}


# ── Ontology Identity (IRI + Version) ──────────────────────────

class IdentityUpdateBody(BaseModel):
    version_iri: str | None = None
    version_info: str | None = None


@router.get("/{board_id}/identity")
def get_identity(
    board_id: str,
    db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional),
):
    """Get ontology IRI, version IRI, and version info."""
    _require_board_view(board_id, db, user)
    try:
        g = ont_svc.load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file")
    return meta_svc.get_ontology_identity(g)


@router.put("/{board_id}/identity")
def set_identity(
    board_id: str, body: IdentityUpdateBody,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Update ontology version IRI and/or version info."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    result = meta_svc.set_ontology_identity(DATA_DIR / board_id, version_iri=body.version_iri, version_info=body.version_info)
    board_svc.git_commit(DATA_DIR / board_id, "Updated ontology identity")
    return result


# ── Full Ontology Annotations ──────────────────────────────────

@router.get("/{board_id}/annotations")
def get_annotations(
    board_id: str,
    db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional),
):
    """Get ALL annotations on the ontology node (dcterms, bibo, vann, owl, rdfs, etc.)."""
    _require_board_view(board_id, db, user)
    try:
        g = ont_svc.load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file")
    return meta_svc.get_all_ontology_annotations(g)


class AnnotationAddBody(BaseModel):
    property_iri: str    # e.g. "dcterms:creator" or full IRI
    value: str           # e.g. "https://orcid.org/..." or "My Ontology"
    value_type: str = "literal"  # "literal" or "iri"
    language: str | None = None  # e.g. "en"


@router.post("/{board_id}/annotations", status_code=201)
def add_annotation(
    board_id: str, body: AnnotationAddBody,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Add an annotation to the ontology node."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = meta_svc.add_ontology_annotation(DATA_DIR / board_id, body.property_iri, body.value, body.value_type, body.language)
    if ok:
        board_svc.git_commit(DATA_DIR / board_id, f"Added annotation: {body.property_iri}")
    return {"success": ok}


class AnnotationRemoveBody(BaseModel):
    property_iri: str
    value: str


@router.delete("/{board_id}/annotations")
def remove_annotation(
    board_id: str, body: AnnotationRemoveBody,
    db: Session = Depends(get_db), user: User = Depends(get_current_user),
):
    """Remove a specific annotation from the ontology node."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board or not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    ok = meta_svc.remove_ontology_annotation(DATA_DIR / board_id, body.property_iri, body.value)
    if ok:
        board_svc.git_commit(DATA_DIR / board_id, f"Removed annotation: {body.property_iri}")
    return {"success": ok}


# ── Prefix Resolution ─────────────────────────────────────────

@router.get("/{board_id}/resolve/{compact_iri:path}")
def resolve_iri(
    board_id: str, compact_iri: str,
    db: Session = Depends(get_db), user: User | None = Depends(get_current_user_optional),
):
    """Resolve a compact IRI (e.g., prov:Activity) to its full IRI."""
    _require_board_view(board_id, db, user)
    try:
        g = ont_svc.load_graph(DATA_DIR / board_id)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No OWL file")
    full = meta_svc.resolve_compact_iri(g, compact_iri)
    return {"compact": compact_iri, "full": full}
