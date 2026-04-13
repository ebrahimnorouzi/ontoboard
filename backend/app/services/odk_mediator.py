"""ODK Mediator Service — bridges ODK/ROBOT CLI tools to the OntoBoard GUI.

ROBOT is installed directly in the backend image (no Docker-in-Docker).
ODK seed uses a manual scaffold (the full ODK seed requires the odkfull image
which is optional — install it separately if needed for advanced ODK workflows).

Results stream via SSE generators that yield `data: {...}\n\n` lines.
"""

import asyncio
import json
import logging
import subprocess
from pathlib import Path
from typing import AsyncGenerator

logger = logging.getLogger("ontoboard.odk_mediator")


def _sse(event_type: str, message: str, progress: float = 0) -> str:
    """Format a Server-Sent Event line."""
    payload = json.dumps({"type": event_type, "message": message, "progress": progress})
    return f"data: {payload}\n\n"


def _ensure_scaffold(board_dir: Path, board_id: str):
    """Ensure the ODK scaffold exists. Create it if missing."""
    ont_dir = board_dir / "src" / "ontology"
    makefile = ont_dir / "Makefile"
    edit_owl = ont_dir / f"{board_id}-edit.owl"

    if makefile.exists() and edit_owl.exists():
        return  # Already scaffolded

    from app.services.odk_setup import _create_manual_scaffold
    logger.info("Auto-scaffolding ODK structure for board '%s'", board_id)
    _create_manual_scaffold(board_dir, board_id, board_id)


def _find_edit_owl(board_dir: Path, board_id: str) -> str | None:
    """Find the -edit.owl file for a board, preferring {board_id}-edit.owl."""
    ont_dir = board_dir / "src" / "ontology"
    edit_owl = ont_dir / f"{board_id}-edit.owl"
    if edit_owl.exists():
        return edit_owl.name
    for f in ont_dir.glob("*-edit.owl"):
        return f.name
    for f in ont_dir.glob("*.owl"):
        if f.name != "catalog-v001.xml":
            return f.name
    return None


def _run_command(board_dir: Path, command: str, cwd: Path | None = None):
    """Run a command locally and yield SSE lines."""
    work_dir = cwd or (board_dir / "src" / "ontology")
    work_dir.mkdir(parents=True, exist_ok=True)

    yield _sse("start", f"Running: {command}", 5)

    try:
        result = subprocess.run(
            command, shell=True, cwd=str(work_dir),
            capture_output=True, text=True, timeout=600,
        )
        for line in result.stdout.splitlines():
            yield _sse("log", line, 50)
        for line in result.stderr.splitlines():
            yield _sse("log", line, 50)

        if result.returncode == 0:
            yield _sse("success", "Command completed successfully", 100)
        else:
            yield _sse("error", f"Command failed (exit {result.returncode})", 100)
    except subprocess.TimeoutExpired:
        yield _sse("error", "Command timed out after 600 seconds", 100)
    except FileNotFoundError as exc:
        yield _sse("error", f"Command not found: {exc}", 100)
    except Exception as exc:
        yield _sse("error", f"Execution error: {exc}", 100)


# ═══════════════════════════════════════════════════════════════
# Workflow 1: ODK Seed / Update Repo
# ═══════════════════════════════════════════════════════════════

async def stream_odk_seed(board_dir: Path, board_id: str) -> AsyncGenerator[str, None]:
    """Scaffold an ODK project structure."""
    yield _sse("info", "Creating ODK project scaffold...", 10)
    _ensure_scaffold(board_dir, board_id)
    yield _sse("success", f"ODK scaffold created for '{board_id}'", 100)
    ont_dir = board_dir / "src" / "ontology"
    if ont_dir.exists():
        files = [str(f.relative_to(board_dir)) for f in board_dir.rglob("*") if f.is_file() and ".git" not in str(f)]
        yield _sse("info", f"Created {len(files)} files: Makefile, {board_id}-edit.owl, release.sh, ...", 100)


async def stream_update_repo(board_dir: Path, board_id: str) -> AsyncGenerator[str, None]:
    """Run `make update_repo` to reload config from odk.yaml."""
    _ensure_scaffold(board_dir, board_id)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_command(board_dir, "make update_repo"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 2: Refresh Imports
# ═══════════════════════════════════════════════════════════════

async def stream_refresh_imports(board_dir: Path, board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `make refresh-imports` to download/update import modules."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", "Refreshing imports — this may download ontologies from the web...", 5)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_command(board_dir, "make refresh-imports"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 3: Reasoning / Test
# ═══════════════════════════════════════════════════════════════

async def stream_reason(board_dir: Path, reasoner: str = "ELK", board_id: str = "") -> AsyncGenerator[str, None]:
    """Run reasoning via `robot reason`."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", f"Running {reasoner} reasoner...", 5)
    loop = asyncio.get_event_loop()

    owl = _find_edit_owl(board_dir, board_id)
    if not owl:
        yield _sse("error", "No OWL file found in src/ontology/. Run 'ODK Seed' first.", 100)
        return

    output_name = owl.replace("-edit.owl", ".owl") if "-edit" in owl else owl
    cmd = f"robot reason -r {reasoner} -i {owl} -o {output_name}"
    for line in await loop.run_in_executor(None, lambda: list(_run_command(board_dir, cmd))):
        yield line


async def stream_test(board_dir: Path, board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `make test` — SPARQL checks + reasoning tests."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", "Running tests...", 5)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_command(board_dir, "make test"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 4: Build (make all / specific targets)
# ═══════════════════════════════════════════════════════════════

async def stream_build(board_dir: Path, target: str = "all", board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `make <target>` for the ODK build pipeline."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", f"Building target: {target}", 5)
    loop = asyncio.get_event_loop()

    # Split multi-step builds
    steps = target.split(",")
    for step in steps:
        step = step.strip()
        yield _sse("info", f"Running: make {step}", 10)
        for line in await loop.run_in_executor(None, lambda s=step: list(_run_command(board_dir, f"make {s}"))):
            yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 5: ROBOT Report
# ═══════════════════════════════════════════════════════════════

async def stream_robot_report(board_dir: Path, board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `robot report` to check ontology quality."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", "Running ROBOT report...", 5)
    loop = asyncio.get_event_loop()

    owl = _find_edit_owl(board_dir, board_id)
    if not owl:
        yield _sse("error", "No OWL file found.", 100)
        return

    cmd = f"robot report -i {owl} --output report.tsv --format tsv"
    for line in await loop.run_in_executor(None, lambda: list(_run_command(board_dir, cmd))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 6: Release / Publish
# ═══════════════════════════════════════════════════════════════

async def stream_release(board_dir: Path, board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `make prepare_release` to build a release candidate."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", "Preparing release...", 5)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_command(board_dir, "make prepare_release"))):
        yield line
