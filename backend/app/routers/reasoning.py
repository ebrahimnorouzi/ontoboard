"""Reasoning router — run reasoner, get inferences, apply fixes."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user, get_current_user_optional
from app.models.user import User
from app.schemas.reasoning import ReasoningRequest, ReasoningResult, Inference
from app.services import board as board_svc
from app.services import reasoning as reasoning_svc
from app.services import axiom as axiom_svc
from app.services.ontology import load_graph

router = APIRouter()


@router.post("/{board_id}/run", response_model=ReasoningResult)
async def run_reasoning(
    board_id: str,
    body: ReasoningRequest | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Run a reasoner on the board's ontology."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    body = body or ReasoningRequest()
    board_dir = DATA_DIR / board_id
    result = await reasoning_svc.run_reasoning(board_dir, body.reasoner)

    board_svc.log_activity(
        db, board, user, "reasoning",
        f"{body.reasoner}: {'consistent' if result['consistent'] else 'INCONSISTENT'}, "
        f"{len(result['inferences'])} inferences, {len(result['errors'])} errors",
    )

    return ReasoningResult(**result)


@router.get("/{board_id}/inferences", response_model=list[Inference])
def get_inferences(
    board_id: str,
    db: Session = Depends(get_db),
    user: User | None = Depends(get_current_user_optional),
):
    """Get cached inferences from the last reasoning run."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    inferences = reasoning_svc.get_cached_inferences(board_id)
    return [Inference(**i) for i in inferences]


@router.post("/{board_id}/apply-fix")
def apply_fix(
    board_id: str,
    body: dict,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Apply a suggested fix by removing/weakening an axiom."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    action = body.get("action", "")
    target_entity = body.get("target_entity", "")
    target_axiom = body.get("target_axiom", "")

    if not target_entity:
        raise HTTPException(status_code=400, detail="target_entity is required")

    board_dir = DATA_DIR / board_id

    if action == "remove_axiom":
        # Remove all axioms of the given type from the entity
        try:
            g = load_graph(board_dir)
            from rdflib import URIRef, RDFS, OWL
            entity = URIRef(target_entity)
            pred_map = {
                "SubClassOf": RDFS.subClassOf,
                "EquivalentClass": OWL.equivalentClass,
                "DisjointWith": OWL.disjointWith,
            }
            pred = pred_map.get(target_axiom)
            if pred:
                g.remove((entity, pred, None))
                owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
                if owl_files:
                    g.serialize(str(owl_files[0]), format="xml")
                board_svc.git_commit(board_dir, f"Removed {target_axiom} axioms from {target_entity}")
                board_svc.log_activity(db, board, user, "fix_applied",
                                       f"Removed {target_axiom} from {target_entity}")
                return {"success": True, "detail": f"Removed {target_axiom} axioms"}
        except Exception as exc:
            raise HTTPException(status_code=500, detail=str(exc))

    return {"success": False, "detail": f"Unknown action: {action}"}
