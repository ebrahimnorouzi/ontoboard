"""Reasoning service — run reasoner, diff for inferences, parse errors, suggest fixes.

Supports two engines:
  - "robot"    : subprocess-based via ROBOT/ODK Docker (default)
  - "embedded" : in-process via owlready2 (HermiT / Pellet, no Docker needed)
"""

import logging
import re
import time
from pathlib import Path

from rdflib import Graph, URIRef, BNode, RDF, RDFS, OWL

from app.config import DATA_DIR
from app.services.robot import run_robot, robot_reason, robot_diff
from app.services.odk import find_owl_file
from app.services.ontology import load_graph

# Attempt to import owlready2; flag availability
try:
    import owlready2
    from owlready2 import (
        get_ontology,
        sync_reasoner_hermit,
        sync_reasoner_pellet,
        default_world,
        Thing,
    )
    OWLREADY2_AVAILABLE = True
except ImportError:
    OWLREADY2_AVAILABLE = False

logger = logging.getLogger("ontoboard.reasoning")

# Cache last reasoning result per board
_reasoning_cache: dict[str, dict] = {}

SUPPORTED_REASONERS = {"ELK", "HermiT", "JFact", "Whelk"}
EMBEDDED_REASONERS = {"hermit", "pellet"}


async def run_reasoning(board_dir: Path, reasoner: str = "ELK") -> dict:
    """Run a reasoner and return inferences + errors."""
    t0 = time.time()
    board_id = board_dir.name

    if reasoner not in SUPPORTED_REASONERS:
        return _error_result(reasoner, f"Unsupported reasoner: {reasoner}")

    owl_file = find_owl_file(board_dir)
    if not owl_file:
        return _error_result(reasoner, "No OWL file found")

    inferred_path = board_dir / "src" / "ontology" / "tmp_inferred.owl"
    result = await robot_reason(board_dir, owl_file, inferred_path, reasoner=reasoner)

    duration = time.time() - t0

    if result.exit_code == -1:
        return _error_result(reasoner, f"Docker/ODK unavailable: {result.stderr}",
                             duration=duration, docker_missing=True)

    # Combine stdout + stderr for complete logs
    combined_logs = ""
    if result.stdout:
        combined_logs += "=== STDOUT ===\n" + result.stdout[-2000:] + "\n"
    if result.stderr:
        combined_logs += "=== STDERR ===\n" + result.stderr[-2000:]
    if not combined_logs.strip():
        combined_logs = "(no output)"

    if not result.success:
        # Parse reasoning errors from stderr
        errors = _parse_reasoning_errors(result.stderr, board_dir)
        fixes = _suggest_fixes(errors, board_dir)

        # Auto-run robot explain for justification tree
        explanation = ""
        try:
            from app.services.robot import run_robot
            explain_result = await run_robot(
                board_dir,
                f"robot explain -i /work/src/ontology/{owl_file.name} --reasoner {reasoner}",
            )
            if explain_result.stdout:
                explanation = explain_result.stdout[-3000:]
            elif explain_result.stderr:
                explanation = explain_result.stderr[-3000:]
        except Exception as exc:
            explanation = f"Could not generate explanation: {exc}"

        output = {
            "success": False,
            "consistent": False,
            "reasoner": reasoner,
            "inferences": [],
            "errors": errors,
            "fixes": fixes,
            "logs": combined_logs,
            "explanation": explanation,
            "duration_seconds": duration,
        }
        _reasoning_cache[board_id] = output
        return output

    # Success — diff original vs inferred to extract new inferences
    inferences = await _extract_inferences(board_dir, owl_file, inferred_path)

    # Clean up
    inferred_path.unlink(missing_ok=True)

    output = {
        "success": True,
        "consistent": True,
        "reasoner": reasoner,
        "inferences": inferences,
        "errors": [],
        "fixes": [],
        "logs": combined_logs,
        "duration_seconds": duration,
    }
    _reasoning_cache[board_id] = output
    return output


def get_cached_inferences(board_id: str) -> list[dict]:
    """Return inferences from the last reasoning run."""
    cached = _reasoning_cache.get(board_id)
    if cached:
        return cached.get("inferences", [])
    return []


# ---------------------------------------------------------------------------
# Embedded reasoning via owlready2
# ---------------------------------------------------------------------------

async def run_reasoning_embedded(board_dir: Path, reasoner: str = "hermit") -> dict:
    """Run reasoning in-process using owlready2 (HermiT or Pellet).

    Returns the same result format as ``run_reasoning()`` so the two engines
    are interchangeable from the caller's perspective.
    """
    t0 = time.time()
    board_id = board_dir.name
    reasoner_lower = reasoner.lower()

    if not OWLREADY2_AVAILABLE:
        return _error_result(
            reasoner,
            "owlready2 is not installed. Install it with: pip install owlready2",
            duration=time.time() - t0,
        )

    if reasoner_lower not in EMBEDDED_REASONERS:
        return _error_result(
            reasoner,
            f"Unsupported embedded reasoner: {reasoner}. Choose 'hermit' or 'pellet'.",
            duration=time.time() - t0,
        )

    owl_file = find_owl_file(board_dir)
    if not owl_file:
        return _error_result(reasoner, "No OWL file found", duration=time.time() - t0)

    errors: list[dict] = []
    inferences: list[dict] = []
    consistent = True
    logs = ""

    try:
        # Use a fresh isolated world so concurrent boards don't collide
        world = owlready2.World()
        onto = world.get_ontology(str(owl_file)).load()

        # Snapshot classes before reasoning to detect new inferences
        pre_parents: dict[str, set[str]] = {}
        pre_types: dict[str, set[str]] = {}
        for cls in onto.classes():
            iri = cls.iri
            pre_parents[iri] = {str(p.iri) for p in cls.is_a if hasattr(p, "iri")}
        for ind in onto.individuals():
            iri = ind.iri
            pre_types[iri] = {str(c.iri) for c in ind.is_a if hasattr(c, "iri")}

        # Run the reasoner
        try:
            if reasoner_lower == "hermit":
                sync_reasoner_hermit(world, infer_property_values=True, infer_data_property_values=True)
            else:
                sync_reasoner_pellet(world, infer_property_values=True, infer_data_property_values=True)
            logs = f"Embedded {reasoner} reasoning completed successfully."
        except owlready2.OwlReadyInconsistentOntologyError:
            consistent = False
            logs = f"Embedded {reasoner}: ontology is inconsistent."

        # Check for inconsistent / unsatisfiable classes
        inconsistent_classes = list(onto.inconsistent_classes())
        if inconsistent_classes:
            consistent = False
            for cls in inconsistent_classes:
                cls_iri = str(cls.iri) if hasattr(cls, "iri") else str(cls)
                cls_label = _local_name(cls_iri) if cls_iri else str(cls)
                errors.append({
                    "entity_iri": cls_iri,
                    "entity_label": cls_label,
                    "axiom": "",
                    "message": f"Unsatisfiable/inconsistent class: {cls_label}",
                    "severity": "error",
                })

        # Extract new inferences by comparing post-reasoning state
        if consistent:
            for cls in onto.classes():
                iri = cls.iri
                post_parents = {str(p.iri) for p in cls.is_a if hasattr(p, "iri")}
                new_parents = post_parents - pre_parents.get(iri, set())
                for parent_iri in new_parents:
                    inferences.append({
                        "inference_type": "SubClassOf",
                        "subject": iri,
                        "subject_label": _local_name(iri),
                        "predicate": str(RDFS.subClassOf),
                        "object": parent_iri,
                        "object_label": _local_name(parent_iri),
                    })

            for ind in onto.individuals():
                iri = ind.iri
                post_types = {str(c.iri) for c in ind.is_a if hasattr(c, "iri")}
                new_types = post_types - pre_types.get(iri, set())
                for type_iri in new_types:
                    inferences.append({
                        "inference_type": "ClassAssertion",
                        "subject": iri,
                        "subject_label": _local_name(iri),
                        "predicate": str(RDF.type),
                        "object": type_iri,
                        "object_label": _local_name(type_iri),
                    })

    except Exception as exc:
        logger.exception("Embedded reasoning failed: %s", exc)
        duration = time.time() - t0
        return _error_result(reasoner, f"Embedded reasoning error: {exc}", duration=duration)

    duration = time.time() - t0
    fixes = _suggest_fixes(errors, board_dir) if errors else []

    output = {
        "success": consistent,
        "consistent": consistent,
        "reasoner": f"{reasoner} (embedded)",
        "inferences": inferences,
        "errors": errors,
        "fixes": fixes,
        "logs": logs,
        "duration_seconds": duration,
    }
    _reasoning_cache[board_id] = output
    return output


async def check_consistency_fast(board_dir: Path) -> dict:
    """Quick consistency check via owlready2 without full inference extraction.

    Returns a lightweight result with consistency status and any
    inconsistent class names.
    """
    t0 = time.time()

    if not OWLREADY2_AVAILABLE:
        return {
            "consistent": None,
            "inconsistent_classes": [],
            "duration_seconds": time.time() - t0,
            "error": "owlready2 is not installed. Install it with: pip install owlready2",
        }

    owl_file = find_owl_file(board_dir)
    if not owl_file:
        return {
            "consistent": None,
            "inconsistent_classes": [],
            "duration_seconds": time.time() - t0,
            "error": "No OWL file found",
        }

    try:
        world = owlready2.World()
        onto = world.get_ontology(str(owl_file)).load()

        consistent = True
        try:
            sync_reasoner_hermit(world, infer_property_values=False, infer_data_property_values=False)
        except owlready2.OwlReadyInconsistentOntologyError:
            consistent = False

        inconsistent = [
            _local_name(str(cls.iri)) if hasattr(cls, "iri") else str(cls)
            for cls in onto.inconsistent_classes()
        ]
        if inconsistent:
            consistent = False

        return {
            "consistent": consistent,
            "inconsistent_classes": inconsistent,
            "duration_seconds": time.time() - t0,
        }

    except Exception as exc:
        logger.exception("Fast consistency check failed: %s", exc)
        return {
            "consistent": None,
            "inconsistent_classes": [],
            "duration_seconds": time.time() - t0,
            "error": str(exc),
        }


async def _extract_inferences(board_dir: Path, original: Path, inferred: Path) -> list[dict]:
    """Compare original and inferred OWL to find new axioms."""
    inferences = []
    try:
        g_orig = Graph()
        g_orig.parse(str(original), format="xml")

        g_inf = Graph()
        g_inf.parse(str(inferred), format="xml")

        # Find triples in inferred but not in original
        orig_triples = set(g_orig)
        for s, p, o in g_inf:
            if (s, p, o) not in orig_triples:
                if isinstance(s, BNode) or isinstance(o, BNode):
                    continue
                inf_type = _classify_inference(p)
                if inf_type:
                    s_label = _get_label(g_inf, s) or _local_name(str(s))
                    o_label = _get_label(g_inf, o) or (str(o) if not isinstance(o, URIRef) else _local_name(str(o)))
                    inferences.append({
                        "inference_type": inf_type,
                        "subject": str(s),
                        "subject_label": s_label,
                        "predicate": str(p),
                        "object": str(o),
                        "object_label": o_label,
                    })
    except Exception as exc:
        logger.warning("Failed to extract inferences: %s", exc)

    # Also try ROBOT diff if available
    diff_path = board_dir / "src" / "ontology" / "tmp_diff.txt"
    diff_result = await robot_diff(board_dir, original, inferred, diff_path)
    if diff_result.success and diff_path.exists():
        diff_inferences = _parse_diff_output(diff_path.read_text(), board_dir)
        # Merge, dedup by (subject, predicate, object)
        existing = {(i["subject"], i["predicate"], i["object"]) for i in inferences}
        for di in diff_inferences:
            key = (di["subject"], di["predicate"], di["object"])
            if key not in existing:
                inferences.append(di)
        diff_path.unlink(missing_ok=True)

    return inferences


def _classify_inference(predicate) -> str | None:
    """Map predicate URI to inference type name."""
    p = str(predicate)
    if p == str(RDFS.subClassOf):
        return "SubClassOf"
    if p == str(OWL.equivalentClass):
        return "EquivalentClass"
    if p == str(RDF.type):
        return "ClassAssertion"
    if p == str(OWL.sameAs):
        return "SameAs"
    if p == str(OWL.differentFrom):
        return "DifferentFrom"
    return None


def _parse_reasoning_errors(stderr: str, board_dir: Path) -> list[dict]:
    """Parse ROBOT/reasoner error output to find problematic axioms."""
    errors = []

    # Pattern: "Inconsistent class: <IRI>"
    for match in re.finditer(r'(?:inconsistent|unsatisfiable)\s+(?:class|entity)[:\s]+<?([^\s>]+)>?', stderr, re.IGNORECASE):
        iri = match.group(1)
        label = ""
        try:
            g = load_graph(board_dir)
            label = _get_label(g, URIRef(iri)) or _local_name(iri)
        except Exception:
            label = _local_name(iri)
        errors.append({
            "entity_iri": iri,
            "entity_label": label,
            "axiom": "",
            "message": f"Unsatisfiable/inconsistent class: {label or iri}",
            "severity": "error",
        })

    # Pattern: general error lines
    for line in stderr.split("\n"):
        line = line.strip()
        if not line:
            continue
        if any(kw in line.lower() for kw in ("error", "exception", "inconsistent", "unsatisfiable")):
            if not any(e["message"] in line for e in errors):
                errors.append({
                    "entity_iri": "",
                    "entity_label": "",
                    "axiom": "",
                    "message": line[:300],
                    "severity": "error",
                })

    return errors[:50]  # Cap at 50


def _suggest_fixes(errors: list[dict], board_dir: Path) -> list[dict]:
    """Generate fix suggestions for reasoning errors."""
    fixes = []
    for i, err in enumerate(errors):
        if err["entity_iri"]:
            # Suggest removing the problematic class's axioms
            fixes.append({
                "error_index": i,
                "description": f"Remove all SubClassOf axioms from {err['entity_label'] or err['entity_iri']}",
                "action": "remove_axiom",
                "target_axiom": "SubClassOf",
                "target_entity": err["entity_iri"],
            })
            fixes.append({
                "error_index": i,
                "description": f"Check DisjointWith axioms involving {err['entity_label'] or err['entity_iri']}",
                "action": "weaken_axiom",
                "target_axiom": "DisjointWith",
                "target_entity": err["entity_iri"],
            })
    return fixes


def _parse_diff_output(diff_text: str, board_dir: Path) -> list[dict]:
    """Parse ROBOT diff output for additional inferences."""
    inferences = []
    for line in diff_text.split("\n"):
        line = line.strip()
        if line.startswith("+") and "SubClassOf" in line:
            inferences.append({
                "inference_type": "SubClassOf",
                "subject": "", "subject_label": "",
                "predicate": str(RDFS.subClassOf),
                "object": "", "object_label": "",
            })
    return inferences


def _error_result(reasoner: str, message: str, duration: float = 0, docker_missing: bool = False) -> dict:
    return {
        "success": False,
        "consistent": None,  # None = unknown (reasoner did not run)
        "reasoner": reasoner,
        "inferences": [],
        "errors": [{"entity_iri": "", "entity_label": "", "axiom": "", "message": message,
                     "severity": "warning" if docker_missing else "error"}],
        "fixes": [],
        "logs": f"Docker/ODK unavailable: {message}" if docker_missing else message,
        "duration_seconds": duration,
        "docker_missing": docker_missing,
    }


def _get_label(g: Graph, subject) -> str | None:
    from rdflib import Literal
    for o in g.objects(subject, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local_name(iri: str) -> str:
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


# ── ROBOT explain helpers ────────────────────────────────────


async def explain_unsatisfiable(
    board_dir: Path,
    entity_iri: str,
    reasoner: str = "ELK",
) -> dict:
    """Explain why a specific entity is unsatisfiable using ROBOT explain."""
    owl_file = find_owl_file(board_dir)
    if not owl_file:
        return {
            "entity": entity_iri,
            "explanation_text": "No OWL file found in this board.",
            "justification_axioms": [],
            "suggested_fixes": [],
        }

    ont_dir = board_dir / "src" / "ontology"
    explanation_path = ont_dir / "explanation.md"
    # Clean up any previous explanation file
    explanation_path.unlink(missing_ok=True)

    rel_owl = owl_file.name if owl_file.parent == ont_dir else str(owl_file.relative_to(ont_dir)).replace("\\", "/")

    cmd = (
        f"robot explain --reasoner {reasoner} "
        f"-i {rel_owl} "
        f"--unsatisfiable \"{entity_iri}\" "
        f"--explanation explanation.md"
    )

    result = await run_robot(board_dir, cmd, working_dir="src/ontology")

    explanation_text = ""
    justification_axioms: list[str] = []
    suggested_fixes: list[str] = []

    # Try to read the generated markdown file first
    if explanation_path.exists():
        explanation_text = explanation_path.read_text(encoding="utf-8").strip()
        justification_axioms = _parse_explanation_axioms(explanation_text)
        explanation_path.unlink(missing_ok=True)
    elif result.stdout:
        explanation_text = result.stdout.strip()
        justification_axioms = _parse_explanation_axioms(explanation_text)
    elif result.stderr:
        explanation_text = result.stderr.strip()

    if not explanation_text:
        explanation_text = f"ROBOT explain did not produce output (exit code {result.exit_code})."

    # Generate suggested fixes from the justification axioms
    suggested_fixes = _derive_fixes_from_axioms(justification_axioms, entity_iri)

    return {
        "entity": entity_iri,
        "explanation_text": explanation_text,
        "justification_axioms": justification_axioms,
        "suggested_fixes": suggested_fixes,
    }


async def explain_inconsistency(
    board_dir: Path,
    reasoner: str = "ELK",
) -> dict:
    """Explain overall ontology inconsistency using ROBOT explain."""
    owl_file = find_owl_file(board_dir)
    if not owl_file:
        return {
            "entity": "",
            "explanation_text": "No OWL file found in this board.",
            "justification_axioms": [],
            "suggested_fixes": [],
        }

    ont_dir = board_dir / "src" / "ontology"
    explanation_path = ont_dir / "explanation.md"
    explanation_path.unlink(missing_ok=True)

    rel_owl = owl_file.name if owl_file.parent == ont_dir else str(owl_file.relative_to(ont_dir)).replace("\\", "/")

    cmd = (
        f"robot explain --reasoner {reasoner} "
        f"-i {rel_owl} "
        f"--explanation explanation.md"
    )

    result = await run_robot(board_dir, cmd, working_dir="src/ontology")

    explanation_text = ""
    justification_axioms: list[str] = []
    suggested_fixes: list[str] = []

    if explanation_path.exists():
        explanation_text = explanation_path.read_text(encoding="utf-8").strip()
        justification_axioms = _parse_explanation_axioms(explanation_text)
        explanation_path.unlink(missing_ok=True)
    elif result.stdout:
        explanation_text = result.stdout.strip()
        justification_axioms = _parse_explanation_axioms(explanation_text)
    elif result.stderr:
        explanation_text = result.stderr.strip()

    if not explanation_text:
        explanation_text = f"ROBOT explain did not produce output (exit code {result.exit_code})."

    suggested_fixes = _derive_fixes_from_axioms(justification_axioms, "")

    return {
        "entity": "",
        "explanation_text": explanation_text,
        "justification_axioms": justification_axioms,
        "suggested_fixes": suggested_fixes,
    }


def _parse_explanation_axioms(explanation_md: str) -> list[str]:
    """Extract axiom lines from a ROBOT explain markdown output.

    ROBOT explain produces markdown with axioms as list items or indented
    lines. We extract meaningful axiom-like lines.
    """
    axioms: list[str] = []
    for line in explanation_md.split("\n"):
        stripped = line.strip()
        if not stripped:
            continue
        # Skip markdown headers and decorative lines
        if stripped.startswith("#") or stripped.startswith("---"):
            continue
        # List items (- or *) often represent axioms in ROBOT explain output
        if stripped.startswith("- ") or stripped.startswith("* "):
            axiom = stripped[2:].strip()
            if axiom:
                axioms.append(axiom)
        # Numbered items
        elif re.match(r"^\d+\.\s+", stripped):
            axiom = re.sub(r"^\d+\.\s+", "", stripped).strip()
            if axiom:
                axioms.append(axiom)
        # Lines containing OWL keywords are likely axiom representations
        elif any(kw in stripped for kw in (
            "SubClassOf", "EquivalentTo", "DisjointWith", "ObjectSomeValuesFrom",
            "ObjectAllValuesFrom", "ObjectIntersectionOf", "ObjectUnionOf",
            "owl:Nothing", "owl:Thing", "SubClassOf(", "EquivalentClasses(",
            "DisjointClasses(", "ClassAssertion(",
        )):
            axioms.append(stripped)

    return axioms


def _derive_fixes_from_axioms(axioms: list[str], entity_iri: str) -> list[str]:
    """Suggest fixes based on justification axioms."""
    fixes: list[str] = []
    entity_label = _local_name(entity_iri) if entity_iri else "the entity"

    has_disjoint = any("DisjointWith" in a or "DisjointClasses" in a for a in axioms)
    has_subclass = any("SubClassOf" in a for a in axioms)
    has_equivalent = any("EquivalentTo" in a or "EquivalentClasses" in a for a in axioms)

    if has_disjoint and has_subclass:
        fixes.append(
            f"Review DisjointWith declarations: {entity_label} is a subclass of "
            f"disjoint classes. Remove one of the SubClassOf or DisjointWith axioms."
        )
    if has_disjoint:
        fixes.append(
            f"Check if the DisjointWith axiom is too strong. "
            f"Consider removing the disjointness constraint."
        )
    if has_equivalent:
        fixes.append(
            f"Review EquivalentClass definitions for {entity_label}. "
            f"The equivalence may create a contradiction with other axioms."
        )
    if has_subclass:
        fixes.append(
            f"Check SubClassOf axioms for {entity_label}. "
            f"It may be subsumed under contradictory parent classes."
        )
    if not fixes:
        fixes.append(
            f"Inspect the justification axioms above and remove or weaken "
            f"one of them to resolve the inconsistency."
        )

    return fixes
