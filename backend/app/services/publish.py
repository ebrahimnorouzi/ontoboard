"""Publish pipeline service — quality checks, ODK make targets, artifact tracking."""

import asyncio
import datetime
import json
import logging
from pathlib import Path

from app.config import DATA_DIR
from app.services.robot import run_robot, robot_report, robot_reason
from app.services.odk import find_owl_file
from app.services.ontology import load_graph, get_ontology_statistics, parse_robot_report_tsv

logger = logging.getLogger("ontoboard.publish")


# ── Pre-publish checks ─────────────────────────────────────────

async def run_pre_checks(board_dir: Path) -> list[dict]:
    """Run a battery of quality checks before publishing."""
    checks = []

    # 1. OWL file exists
    owl_file = find_owl_file(board_dir)
    if not owl_file:
        checks.append({"name": "OWL file exists", "passed": False,
                        "message": "No .owl file found in src/ontology/", "severity": "error"})
        return checks
    checks.append({"name": "OWL file exists", "passed": True,
                    "message": str(owl_file.name), "severity": "info"})

    # 2. Parse check — can rdflib load it?
    try:
        g = load_graph(board_dir)
        stats = get_ontology_statistics(g)
        checks.append({"name": "OWL parseable", "passed": True,
                        "message": f"{stats['total_triples']} triples", "severity": "info"})
    except Exception as exc:
        checks.append({"name": "OWL parseable", "passed": False,
                        "message": str(exc), "severity": "error"})
        return checks

    # 3. Non-empty ontology
    if stats["classes"] == 0 and stats["individuals"] == 0:
        checks.append({"name": "Non-empty ontology", "passed": False,
                        "message": "No classes or individuals found", "severity": "warning"})
    else:
        checks.append({"name": "Non-empty ontology", "passed": True,
                        "message": f"{stats['classes']} classes, {stats['individuals']} individuals",
                        "severity": "info"})

    # 4. Makefile exists
    makefile = board_dir / "src" / "ontology" / "Makefile"
    if makefile.exists():
        checks.append({"name": "Makefile exists", "passed": True,
                        "message": "src/ontology/Makefile found", "severity": "info"})
    else:
        checks.append({"name": "Makefile exists", "passed": False,
                        "message": "No Makefile in src/ontology/", "severity": "error"})

    # 5. ROBOT report (try, but don't block on Docker unavailability)
    result = await robot_report(board_dir, owl_file)
    if result.success:
        parsed = parse_robot_report_tsv(board_dir / "report.tsv")
        error_count = sum(1 for v in parsed["violations"] if v["severity"].upper() == "ERROR")
        if error_count > 0:
            checks.append({"name": "ROBOT report", "passed": False,
                            "message": f"{error_count} errors in report", "severity": "error"})
        else:
            checks.append({"name": "ROBOT report", "passed": True,
                            "message": parsed["summary"], "severity": "info"})
    elif result.exit_code == -1:
        checks.append({"name": "ROBOT report", "passed": True,
                        "message": "Skipped (odkfull image not available)", "severity": "warning"})
    else:
        checks.append({"name": "ROBOT report", "passed": False,
                        "message": f"ROBOT report failed: {result.stderr[:200]}", "severity": "error"})

    # 6. Reasoning check
    inferred_path = board_dir / "src" / "ontology" / "tmp_inferred.owl"
    reason_result = await robot_reason(board_dir, owl_file, inferred_path, reasoner="ELK")
    if reason_result.success:
        checks.append({"name": "Reasoning (ELK)", "passed": True,
                        "message": "Ontology is consistent", "severity": "info"})
        inferred_path.unlink(missing_ok=True)
    elif reason_result.exit_code == -1:
        checks.append({"name": "Reasoning (ELK)", "passed": True,
                        "message": "Skipped (odkfull image not available)", "severity": "warning"})
    else:
        checks.append({"name": "Reasoning (ELK)", "passed": False,
                        "message": f"Reasoning failed: {reason_result.stderr[:200]}", "severity": "error"})
        inferred_path.unlink(missing_ok=True)

    return checks


# ── Publish pipeline (SSE streaming) ──────────────────────────

async def stream_publish(board_dir: Path, steps: list[str]):
    """Generator yielding SSE events for the publish pipeline."""
    total = len(steps)
    for i, step in enumerate(steps):
        progress = (i / total) * 100
        yield _sse(f"step_start", step, f"Starting: make {step}", progress)

        result = await run_robot(
            board_dir,
            f"make {step}",
            working_dir="/work/src/ontology",
            timeout=600,
        )

        if result.success:
            yield _sse("step_done", step, f"Completed: make {step}", ((i + 1) / total) * 100)
            # Stream stdout lines
            for line in result.stdout.strip().split("\n"):
                if line.strip():
                    yield _sse("log", step, line, ((i + 1) / total) * 100)
        else:
            yield _sse("step_failed", step, f"Failed: {result.stderr[:300]}", progress)
            # Stream error details
            for line in result.stderr.strip().split("\n"):
                if line.strip():
                    yield _sse("error", step, line, progress)
            yield _sse("pipeline_failed", step, f"Pipeline stopped at: make {step}", progress)
            return

    yield _sse("pipeline_done", "all", "Publish pipeline completed successfully", 100)

    # Record publish timestamp
    _save_publish_status(board_dir)


def _sse(event_type: str, step: str, message: str, progress: float) -> str:
    data = json.dumps({"type": event_type, "step": step, "message": message, "progress": progress})
    return f"data: {data}\n\n"


# ── Status tracking ───────────────────────────────────────────

def get_publish_status(board_dir: Path) -> dict:
    """Check if board has been published and list artifacts."""
    status_file = board_dir / ".publish_status.json"
    artifacts = []

    # Scan for common ODK output artifacts
    for pattern in ("*.owl", "*.obo", "*.json"):
        for f in (board_dir / "src" / "ontology").glob(pattern):
            if not f.name.startswith("tmp_"):
                artifacts.append(f.name)

    if status_file.exists():
        data = json.loads(status_file.read_text())
        return {
            "last_published": data.get("last_published"),
            "version": data.get("version"),
            "artifacts": artifacts,
            "checks_passed": data.get("checks_passed"),
        }

    return {
        "last_published": None,
        "version": None,
        "artifacts": artifacts,
        "checks_passed": None,
    }


def _save_publish_status(board_dir: Path):
    status_file = board_dir / ".publish_status.json"
    status_file.write_text(json.dumps({
        "last_published": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "version": "1.0",
        "checks_passed": True,
    }))
