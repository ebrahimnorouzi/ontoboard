"""Reasoning service — run reasoner, diff for inferences, parse errors, suggest fixes."""

import logging
import re
import time
from pathlib import Path

from rdflib import Graph, URIRef, BNode, RDF, RDFS, OWL

from app.config import DATA_DIR
from app.services.robot import run_robot, robot_reason, robot_diff
from app.services.odk import find_owl_file
from app.services.ontology import load_graph

logger = logging.getLogger("ontoboard.reasoning")

# Cache last reasoning result per board
_reasoning_cache: dict[str, dict] = {}

SUPPORTED_REASONERS = {"ELK", "HermiT", "JFact", "Whelk"}


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
        "success": not docker_missing,
        "consistent": True if docker_missing else False,
        "reasoner": reasoner,
        "inferences": [],
        "errors": [{"entity_iri": "", "entity_label": "", "axiom": "", "message": message, "severity": "warning" if docker_missing else "error"}] if not docker_missing else [],
        "fixes": [],
        "logs": message,
        "duration_seconds": duration,
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
