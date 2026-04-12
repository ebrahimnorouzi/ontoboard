"""OWL load/save router — convert between .owl files and canvas JSON."""

import json
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.canvas import CanvasState, CanvasStickyNote, CanvasFrame
from app.services import board as board_svc
from app.services import odk as odk_svc
from app.services import canvas as canvas_svc
from app.services.ontology import load_graph

router = APIRouter()


# ── Load: OWL → CanvasState (rdflib, no Docker needed) ────────
@router.get("/{board_id}/load", response_model=CanvasState)
def load_owl(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    board_dir = DATA_DIR / board_id
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="No .owl file found")

    state = canvas_svc.owl_to_canvas(g)

    # Merge sticky notes and frames from sidecar file (not part of OWL)
    meta_file = board_dir / "canvas_meta.json"
    if meta_file.exists():
        try:
            meta = json.loads(meta_file.read_text())
            state.sticky_notes = [
                CanvasStickyNote(**n)
                for n in meta.get("sticky_notes", [])
            ]
            state.frames = [
                CanvasFrame(**f)
                for f in meta.get("frames", [])
            ]
        except Exception:
            pass

    return state


# ── Save: CanvasState → OWL (rdflib serialization, no Docker) ─
@router.post("/{board_id}/save")
def save_owl(
    board_id: str,
    state: CanvasState,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    board_dir = DATA_DIR / board_id

    # Ensure ODK scaffold exists (Makefile, edit.owl, release.sh, etc.)
    from app.services.odk_setup import _create_manual_scaffold
    ont_dir = board_dir / "src" / "ontology"
    if not (ont_dir / "Makefile").exists():
        _create_manual_scaffold(board_dir, board_id, board_id)

    output_owl = ont_dir / f"{board_id}.owl"
    edit_owl = ont_dir / f"{board_id}-edit.owl"

    # Determine base IRI from the board's current ontology, or use default
    base_iri = f"http://example.org/{board_id}"
    try:
        g = load_graph(board_dir)
        from app.services.ontology import get_ontology_iri
        detected = get_ontology_iri(g)
        if detected:
            base_iri = detected
    except FileNotFoundError:
        pass

    owl_xml = canvas_svc.canvas_state_to_owl_xml(state, base_iri)
    output_owl.parent.mkdir(parents=True, exist_ok=True)
    output_owl.write_text(owl_xml)
    # Also write to the -edit.owl file so ROBOT/ODK commands find it
    edit_owl.write_text(owl_xml)

    # Persist sticky notes and frames in a sidecar JSON (not part of OWL)
    meta_file = board_dir / "canvas_meta.json"
    meta = {}
    if meta_file.exists():
        try:
            meta = json.loads(meta_file.read_text())
        except Exception:
            pass

    # Update sticky notes
    if state.sticky_notes:
        meta["sticky_notes"] = [n.model_dump() for n in state.sticky_notes]
    else:
        meta.pop("sticky_notes", None)

    # Update frames
    if state.frames:
        meta["frames"] = [f.model_dump() for f in state.frames]
    else:
        meta.pop("frames", None)

    # Write or remove sidecar file
    if meta:
        meta_file.write_text(json.dumps(meta, indent=2))
    elif meta_file.exists():
        try:
            meta_file.unlink()
        except Exception:
            pass

    username = user.display_name or user.username if user else "anonymous"
    board_svc.git_commit(board_dir, f"Update ontology by {username}")
    board_svc.log_activity(db, board, user, "saved", f"{len(state.classes)} classes, {len(state.properties)} properties by {username}")

    return {"detail": "Saved", "owl_path": str(output_owl)}


# ── Upload CSV for KG generation ──────────────────────────────
@router.post("/{board_id}/upload-csv")
async def upload_csv(
    board_id: str,
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    board_dir = DATA_DIR / board_id
    upload_dir = board_dir / "uploads"
    upload_dir.mkdir(exist_ok=True)

    dest = upload_dir / file.filename
    contents = await file.read()
    dest.write_bytes(contents)

    first_line = contents.decode("utf-8", errors="replace").split("\n")[0]
    sep = "\t" if "\t" in first_line else ","
    columns = [c.strip().strip('"') for c in first_line.split(sep)]

    board_svc.log_activity(db, board, user, "uploaded", f"CSV: {file.filename}")
    return {"filename": file.filename, "columns": columns}


# ── Build KG from mapping ─────────────────────────────────────
@router.post("/{board_id}/build-kg")
async def build_kg(
    board_id: str,
    mapping: dict,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    board_dir = DATA_DIR / board_id
    csv_file = board_dir / "uploads" / mapping["csv_file"]
    if not csv_file.exists():
        raise HTTPException(status_code=404, detail="CSV file not found")

    template_path = board_dir / "kg_template.csv"
    output_path = board_dir / "knowledge_graph.ttl"

    odk_svc.generate_kg_template(mapping["mappings"], csv_file, template_path)
    await odk_svc.robot_template(board_dir, template_path, output_path)

    board_svc.log_activity(db, board, user, "kg_built", f"From {mapping['csv_file']}")
    return {"detail": "KG built", "ttl_path": str(output_path)}
