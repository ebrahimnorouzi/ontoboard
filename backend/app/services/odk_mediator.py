"""ODK Mediator Service — bridges ODK/ROBOT CLI tools to the OntoBoard GUI.

All 6 ODK workflows execute inside the obolibrary/odkfull Docker container
when available. Falls back to local execution when Docker is not running.

Results stream via SSE generators that yield `data: {...}\n\n` lines.
"""

import asyncio
import json
import logging
import shutil
import subprocess
import time
from pathlib import Path
from typing import AsyncGenerator

try:
    import docker
    from docker.errors import ImageNotFound, DockerException
    HAS_DOCKER_LIB = True
except ImportError:
    HAS_DOCKER_LIB = False

from app.config import ODK_IMAGE

logger = logging.getLogger("ontoboard.odk_mediator")


def _docker_mount_path(host_path: Path) -> str:
    """Convert a host path to a Docker-compatible mount path (Windows → /c/...)."""
    p = str(host_path).replace("\\", "/")
    if len(p) >= 2 and p[1] == ":":
        p = "/" + p[0].lower() + p[2:]
    return p


def _sse(event_type: str, message: str, progress: float = 0) -> str:
    """Format a Server-Sent Event line."""
    return f"data: {json.dumps({'type': event_type, 'message': message, 'progress': progress, 'ts': time.time()})}\n\n"


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
    # Prefer the canonical name
    edit_owl = ont_dir / f"{board_id}-edit.owl"
    if edit_owl.exists():
        return edit_owl.name
    # Fall back to any -edit.owl
    for f in ont_dir.glob("*-edit.owl"):
        return f.name
    # Fall back to any .owl
    for f in ont_dir.glob("*.owl"):
        if f.name != "catalog-v001.xml":
            return f.name
    return None


def _docker_available() -> bool:
    """Check if Docker is available and the ODK image exists."""
    if not HAS_DOCKER_LIB:
        return False
    try:
        client = docker.from_env()
        client.images.get(ODK_IMAGE)
        return True
    except Exception:
        return False


def _run_container_streaming(board_dir: Path, command: str, working_dir: str = "/work/src/ontology"):
    """Run a command in odkfull container and yield stdout/stderr lines."""
    if not HAS_DOCKER_LIB:
        yield _sse("error", "Docker SDK not installed. Install with: pip install docker", 0)
        return

    try:
        client = docker.from_env()
        client.images.get(ODK_IMAGE)
    except ImageNotFound:
        yield _sse("error", f"ODK image '{ODK_IMAGE}' not found. Run: docker pull {ODK_IMAGE}", 0)
        return
    except (DockerException, Exception) as exc:
        yield _sse("error", f"Docker unavailable: {exc}", 0)
        return

    yield _sse("start", f"Starting: {command}", 5)

    try:
        # Use sh -c with double quotes to avoid nested quoting issues
        container = client.containers.run(
            image=ODK_IMAGE,
            command=["sh", "-c", command],
            volumes={_docker_mount_path(board_dir): {"bind": "/work", "mode": "rw"}},
            working_dir=working_dir,
            detach=True,
            stdout=True,
            stderr=True,
        )
    except Exception as exc:
        yield _sse("error", f"Failed to start container: {exc}", 0)
        return

    try:
        for chunk in container.logs(stream=True, follow=True):
            line = chunk.decode("utf-8", errors="replace").rstrip()
            if line:
                yield _sse("log", line, 50)

        result = container.wait(timeout=600)
        exit_code = result.get("StatusCode", -1)

        if exit_code == 0:
            yield _sse("success", f"Command completed successfully (exit 0)", 100)
        else:
            stderr = container.logs(stdout=False, stderr=True).decode("utf-8", errors="replace")
            yield _sse("error", f"Command failed (exit {exit_code}): {stderr[-300:]}", 100)
    except Exception as exc:
        yield _sse("error", f"Execution error: {exc}", 100)
    finally:
        try:
            container.remove(force=True)
        except Exception:
            pass


def _run_local_command(board_dir: Path, command: str, working_dir: Path | None = None):
    """Run a command locally (fallback when Docker is unavailable)."""
    cwd = working_dir or (board_dir / "src" / "ontology")
    cwd.mkdir(parents=True, exist_ok=True)

    yield _sse("start", f"Running locally: {command}", 5)

    try:
        result = subprocess.run(
            command, shell=True, cwd=str(cwd),
            capture_output=True, text=True, timeout=300,
        )
        for line in result.stdout.splitlines():
            yield _sse("log", line, 50)
        for line in result.stderr.splitlines():
            yield _sse("log", f"[stderr] {line}", 50)

        if result.returncode == 0:
            yield _sse("success", "Command completed successfully", 100)
        else:
            yield _sse("error", f"Command failed (exit {result.returncode})", 100)
    except subprocess.TimeoutExpired:
        yield _sse("error", "Command timed out after 300 seconds", 100)
    except FileNotFoundError as exc:
        yield _sse("error", f"Command not found: {exc}", 100)
    except Exception as exc:
        yield _sse("error", f"Execution error: {exc}", 100)


def _run_command_streaming(board_dir: Path, command: str,
                           working_dir: str = "/work/src/ontology",
                           local_cwd: Path | None = None):
    """Run a command via Docker if available, otherwise locally."""
    if _docker_available():
        yield from _run_container_streaming(board_dir, command, working_dir)
    else:
        cwd = local_cwd or (board_dir / "src" / "ontology")
        yield from _run_local_command(board_dir, command, cwd)


# ═══════════════════════════════════════════════════════════════
# Workflow 1: ODK Seed / Update Repo
# ═══════════════════════════════════════════════════════════════

async def stream_odk_seed(board_dir: Path, board_id: str) -> AsyncGenerator[str, None]:
    """Run `odk seed` to scaffold a full ODK project."""
    loop = asyncio.get_event_loop()

    if _docker_available():
        cmd = f'/tools/odk.py seed --gitname "OntoBoard" --gitemail "ontoboard@local" -n {board_id} -t {board_id} -d "Ontology {board_id}" -u https://example.org/{board_id}'
        for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd, "/work"))):
            yield line
    else:
        # Fall back to manual scaffold
        yield _sse("info", "Docker not available — creating ODK scaffold manually...", 10)
        _ensure_scaffold(board_dir, board_id)
        yield _sse("success", f"ODK scaffold created for '{board_id}' (manual mode)", 100)
        # List created files
        ont_dir = board_dir / "src" / "ontology"
        if ont_dir.exists():
            files = [str(f.relative_to(board_dir)) for f in board_dir.rglob("*") if f.is_file() and ".git" not in str(f)]
            yield _sse("info", f"Created {len(files)} files: Makefile, {board_id}-edit.owl, release.sh, ...", 100)


async def stream_update_repo(board_dir: Path, board_id: str) -> AsyncGenerator[str, None]:
    """Run `make update_repo` to reload config from odk.yaml."""
    _ensure_scaffold(board_dir, board_id)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, "make update_repo"))):
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
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, "make refresh-imports"))):
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

    # Find the edit OWL file
    owl = _find_edit_owl(board_dir, board_id)
    if not owl:
        yield _sse("error", f"No OWL file found in src/ontology/. Run 'ODK Seed' first to create the project structure.", 100)
        return

    output_name = owl.replace("-edit.owl", ".owl") if "-edit" in owl else owl
    cmd = f"robot reason -r {reasoner} -i {owl} -o {output_name}"
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, cmd))):
        yield line


async def stream_test(board_dir: Path, board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `make test` — SPARQL checks + reasoning tests."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", "Running ontology test suite...", 5)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, "make test"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 4: SPARQL Verification
# ═══════════════════════════════════════════════════════════════

async def stream_sparql_verify(board_dir: Path, sparql_file: str, board_id: str = "") -> AsyncGenerator[str, None]:
    """Run `robot verify` with a custom SPARQL check."""
    if board_id:
        _ensure_scaffold(board_dir, board_id)
    yield _sse("info", f"Running SPARQL verification: {sparql_file}", 5)
    loop = asyncio.get_event_loop()

    owl = _find_edit_owl(board_dir, board_id)
    if not owl:
        yield _sse("error", "No OWL file found. Run 'ODK Seed' first.", 100)
        return

    cmd = f"robot verify -i {owl} --queries /work/src/sparql/{sparql_file} -o /work/verify_results.tsv"
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, cmd))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 5: Release
# ═══════════════════════════════════════════════════════════════

async def stream_release(board_dir: Path, board_id: str,
                         version: str | None = None) -> AsyncGenerator[str, None]:
    """Run the full release pipeline: test → prepare → release + multi-format export."""
    from app.services.odk_setup import get_versioning_strategy

    _ensure_scaffold(board_dir, board_id)
    strategy = get_versioning_strategy(board_dir)
    yield _sse("info", f"Starting release pipeline (versioning: {strategy})...", 2)

    loop = asyncio.get_event_loop()

    # Step 1: Test
    yield _sse("step", "Step 1/4: Running tests...", 10)
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, "make test"))):
        yield line

    # Step 2: Prepare release via release.sh or Makefile
    release_sh = board_dir / "src" / "ontology" / "release.sh"
    if release_sh.exists():
        if version:
            release_cmd = f"sh release.sh {version}"
        elif strategy == "date":
            release_cmd = "sh release.sh"
        else:
            release_cmd = "sh release.sh 0.1.0"
        yield _sse("step", f"Step 2/4: Running release.sh ({strategy} versioning)...", 30)
        for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, release_cmd))):
            yield line
    else:
        yield _sse("step", "Step 2/4: Preparing release...", 30)
        for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, "make prepare_release"))):
            yield line

    # Step 3: Build release (multi-format export)
    yield _sse("step", "Step 3/4: Building release artifacts...", 50)
    owl = _find_edit_owl(board_dir, board_id)
    if owl:
        base = owl.replace("-edit.owl", "").replace(".owl", "")
        releases_dir = board_dir / "releases"
        releases_dir.mkdir(exist_ok=True)

        # Copy the OWL file to releases
        src_owl = board_dir / "src" / "ontology" / owl
        if src_owl.exists():
            shutil.copy2(str(src_owl), str(releases_dir / f"{base}.owl"))
            yield _sse("log", f"Copied {owl} to releases/", 55)

        if _docker_available():
            for fmt, ext in [("turtle", "ttl"), ("obo", "obo"), ("json", "jsonld")]:
                yield _sse("log", f"Converting to {ext}...", 60)
                cmd = f"robot convert -i {owl} --format {fmt} -o /work/releases/{base}.{ext}"
                for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd))):
                    yield line
        else:
            yield _sse("info", "Docker not available — skipping multi-format conversion (requires ROBOT)", 70)
    else:
        yield _sse("error", "No OWL file found for release artifacts", 60)

    # Step 4: Publish
    yield _sse("step", "Step 4/4: Finalizing release...", 85)
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, "make publish"))):
        yield line

    # List artifacts
    releases_dir = board_dir / "releases"
    if releases_dir.exists():
        artifacts = [f.name for f in releases_dir.iterdir() if f.is_file()]
        yield _sse("artifacts", json.dumps(artifacts), 100)

    yield _sse("done", "Release pipeline completed", 100)


# ═══════════════════════════════════════════════════════════════
# Workflow 6: DOSDP Pattern Instantiation
# ═══════════════════════════════════════════════════════════════

async def stream_dosdp_generate(board_dir: Path, pattern_file: str, data_file: str) -> AsyncGenerator[str, None]:
    """Run DOSDP pattern instantiation via ROBOT template."""
    yield _sse("info", f"Generating from DOSDP pattern: {pattern_file}", 5)
    loop = asyncio.get_event_loop()
    cmd = f"robot template --template /work/{data_file} -o /work/src/ontology/pattern_output.owl"
    for line in await loop.run_in_executor(None, lambda: list(_run_command_streaming(board_dir, cmd))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Utility: Get release artifacts
# ═══════════════════════════════════════════════════════════════

def get_release_artifacts(board_dir: Path) -> list[dict]:
    """List available release artifacts."""
    releases_dir = board_dir / "releases"
    if not releases_dir.exists():
        return []
    return [
        {"name": f.name, "size": f.stat().st_size, "path": str(f.relative_to(board_dir))}
        for f in sorted(releases_dir.iterdir()) if f.is_file()
    ]
