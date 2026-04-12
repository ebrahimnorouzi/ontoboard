"""ODK Mediator Service — bridges ODK/ROBOT CLI tools to the OntoBoard GUI.

All 6 ODK workflows execute inside the obolibrary/odkfull Docker container.
Results stream via SSE generators that yield `data: {...}\n\n` lines.
"""

import asyncio
import json
import logging
import time
from pathlib import Path
from typing import AsyncGenerator

import docker
from docker.errors import ImageNotFound, DockerException

from app.config import DATA_DIR, ODK_IMAGE

logger = logging.getLogger("ontoboard.odk_mediator")


def _sse(event_type: str, message: str, progress: float = 0) -> str:
    """Format a Server-Sent Event line."""
    return f"data: {json.dumps({'type': event_type, 'message': message, 'progress': progress, 'ts': time.time()})}\n\n"


def _run_container_streaming(board_dir: Path, command: str, working_dir: str = "/work/src/ontology"):
    """Run a command in odkfull container and yield stdout/stderr lines.

    This is a synchronous generator — call from async via run_in_executor
    or wrap with async generator.
    """
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
        container = client.containers.run(
            image=ODK_IMAGE,
            command=f"sh -c '{command}'",
            volumes={str(board_dir): {"bind": "/work", "mode": "rw"}},
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


# ═══════════════════════════════════════════════════════════════
# Workflow 1: ODK Seed / Update Repo
# ═══════════════════════════════════════════════════════════════

async def stream_odk_seed(board_dir: Path, board_id: str) -> AsyncGenerator[str, None]:
    """Run `odk seed` to scaffold a full ODK project."""
    loop = asyncio.get_event_loop()
    cmd = f"seed -n {board_id} -t my-ont -d 'Ontology {board_id}' -u https://example.org/{board_id}"
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd, "/work"))):
        yield line


async def stream_update_repo(board_dir: Path, board_id: str) -> AsyncGenerator[str, None]:
    """Run `make update_repo` to reload config from odk.yaml."""
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, "make update_repo"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 2: Refresh Imports
# ═══════════════════════════════════════════════════════════════

async def stream_refresh_imports(board_dir: Path) -> AsyncGenerator[str, None]:
    """Run `make refresh-imports` to download/update import modules."""
    yield _sse("info", "Refreshing imports — this may download ontologies from the web...", 5)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, "make refresh-imports"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 3: Reasoning / Test
# ═══════════════════════════════════════════════════════════════

async def stream_reason(board_dir: Path, reasoner: str = "ELK") -> AsyncGenerator[str, None]:
    """Run reasoning via `make reason` or `robot reason`."""
    yield _sse("info", f"Running {reasoner} reasoner...", 5)
    loop = asyncio.get_event_loop()
    # Use ROBOT directly for more control
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if not owl_files:
        yield _sse("error", "No OWL file found in src/ontology/", 100)
        return
    owl = owl_files[0].name
    cmd = f"robot reason -r {reasoner} -i {owl} -o {owl}"
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd))):
        yield line


async def stream_test(board_dir: Path) -> AsyncGenerator[str, None]:
    """Run `make test` — SPARQL checks + reasoning tests."""
    yield _sse("info", "Running ontology test suite...", 5)
    loop = asyncio.get_event_loop()
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, "make test"))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 4: SPARQL Verification
# ═══════════════════════════════════════════════════════════════

async def stream_sparql_verify(board_dir: Path, sparql_file: str) -> AsyncGenerator[str, None]:
    """Run `robot verify` with a custom SPARQL check."""
    yield _sse("info", f"Running SPARQL verification: {sparql_file}", 5)
    loop = asyncio.get_event_loop()
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if not owl_files:
        yield _sse("error", "No OWL file found", 100)
        return
    owl = owl_files[0].name
    cmd = f"robot verify -i {owl} --queries /work/src/sparql/{sparql_file} -o /work/verify_results.tsv"
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd))):
        yield line


# ═══════════════════════════════════════════════════════════════
# Workflow 5: Release
# ═══════════════════════════════════════════════════════════════

async def stream_release(board_dir: Path, board_id: str,
                         version: str | None = None) -> AsyncGenerator[str, None]:
    """Run the full release pipeline: test → prepare → release + multi-format export.

    If a release.sh exists (generated based on versioning strategy), it is used
    for the prepare step. Otherwise falls back to Makefile targets.
    """
    from app.services.odk_setup import get_versioning_strategy

    strategy = get_versioning_strategy(board_dir)
    yield _sse("info", f"Starting release pipeline (versioning: {strategy})...", 2)

    loop = asyncio.get_event_loop()

    # Step 1: Test
    yield _sse("step", "Step 1/4: Running tests...", 10)
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, "make test"))):
        yield line

    # Step 2: Prepare release via release.sh or Makefile
    release_sh = board_dir / "src" / "ontology" / "release.sh"
    if release_sh.exists():
        if version:
            release_cmd = f"sh release.sh {version}"
        elif strategy == "date":
            release_cmd = "sh release.sh"  # defaults to today's date
        else:
            release_cmd = "sh release.sh 0.1.0"  # semantic default
        yield _sse("step", f"Step 2/4: Running release.sh ({strategy} versioning)...", 30)
        for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, release_cmd))):
            yield line
    else:
        yield _sse("step", "Step 2/4: Preparing release...", 30)
        for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, "make prepare_release"))):
            yield line

    # Step 3: Build release (multi-format export)
    yield _sse("step", "Step 3/4: Building release artifacts...", 50)
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        owl = owl_files[0].name
        base = owl.replace(".owl", "")
        # Create releases directory
        releases_dir = board_dir / "releases"
        releases_dir.mkdir(exist_ok=True)

        for fmt, ext in [("turtle", "ttl"), ("obo", "obo"), ("json", "jsonld")]:
            yield _sse("log", f"Converting to {ext}...", 60)
            cmd = f"robot convert -i {owl} --format {fmt} -o /work/releases/{base}.{ext}"
            for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd))):
                yield line

    # Step 4: Make release
    yield _sse("step", "Step 4/4: Finalizing release...", 85)
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, "make publish"))):
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
    for line in await loop.run_in_executor(None, lambda: list(_run_container_streaming(board_dir, cmd))):
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
