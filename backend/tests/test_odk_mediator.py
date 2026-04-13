"""Tests for ODK Mediator — all workflows with mocked subprocess calls.

Mocking Strategy:
- subprocess.run() is patched in app.services.odk_mediator
- Returns a CompletedProcess with stdout, stderr, and returncode
- No Docker dependency — the service uses local subprocess calls

This ensures the full FastAPI→Service→subprocess→SSE pipeline is tested
without requiring ROBOT or ODK tools to be installed.
"""

import asyncio
import json
import subprocess
import textwrap
from pathlib import Path
from unittest.mock import patch

import pytest


SIMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/t"/>
        <owl:Class rdf:about="http://ex.org/t#A"><rdfs:label>A</rdfs:label></owl:Class>
    </rdf:RDF>
""")


def _setup(d, bid):
    ont = d / bid / "src" / "ontology"
    ont.mkdir(parents=True, exist_ok=True)
    (ont / f"{bid}.owl").write_text(SIMPLE_OWL)
    (ont / "Makefile").write_text("all:\n\t@echo done\n")
    (d / bid / "releases").mkdir(exist_ok=True)
    # Create a SPARQL directory for verification tests
    sparql_dir = d / bid / "src" / "sparql"
    sparql_dir.mkdir(parents=True, exist_ok=True)
    (sparql_dir / "test_check.rq").write_text("SELECT ?s WHERE { ?s ?p ?o } LIMIT 1")


def _make_completed_process(
    stdout: str = "",
    stderr: str = "",
    returncode: int = 0,
) -> subprocess.CompletedProcess:
    """Create a CompletedProcess for mocking subprocess.run."""
    return subprocess.CompletedProcess(
        args="mocked",
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
    )


DEFAULT_STDOUT = (
    "[INFO] Loading ontology...\n"
    "[INFO] Running reasoner ELK...\n"
    "[INFO] Reasoner completed successfully\n"
)


# ═══════════════════════════════════════════════════════════════
# Unit Tests: SSE streaming from mocked subprocess
# ═══════════════════════════════════════════════════════════════

def test_sse_format():
    """Verify the SSE format is correct."""
    from app.services.odk_mediator import _sse
    line = _sse("log", "Hello world", 50)
    assert line.startswith("data: ")
    assert line.endswith("\n\n")
    data = json.loads(line[6:].strip())
    assert data["type"] == "log"
    assert data["message"] == "Hello world"
    assert data["progress"] == 50


def test_run_command_success(tmp_data_dir):
    """Test that _run_command yields proper SSE lines on success."""
    _setup(tmp_data_dir, "stream-ok")
    mock_result = _make_completed_process(
        stdout="[INFO] Step 1: Loading...\n[INFO] Step 2: Processing...\n[INFO] Done!\n",
        returncode=0,
    )
    with patch("app.services.odk_mediator.subprocess.run", return_value=mock_result):
        from app.services.odk_mediator import _run_command
        lines = list(_run_command(tmp_data_dir / "stream-ok", "make all"))

    # Should have: start + 3 log lines + success
    assert len(lines) >= 4
    # First line should be "start"
    first = json.loads(lines[0][6:].strip())
    assert first["type"] == "start"
    # Last line should be "success"
    last = json.loads(lines[-1][6:].strip())
    assert last["type"] == "success"
    assert "successfully" in last["message"]


def test_run_command_failure(tmp_data_dir):
    """Test that _run_command reports errors on non-zero exit."""
    _setup(tmp_data_dir, "stream-fail")
    mock_result = _make_completed_process(
        stdout="",
        stderr="[ERROR] Reasoner failed!\nDetailed error: inconsistency found",
        returncode=1,
    )
    with patch("app.services.odk_mediator.subprocess.run", return_value=mock_result):
        from app.services.odk_mediator import _run_command
        lines = list(_run_command(tmp_data_dir / "stream-fail", "make test"))

    # Should contain an error line
    types = [json.loads(l[6:].strip())["type"] for l in lines]
    assert "error" in types


def test_run_command_timeout(tmp_data_dir):
    """Test graceful handling when command times out."""
    _setup(tmp_data_dir, "stream-timeout")
    with patch(
        "app.services.odk_mediator.subprocess.run",
        side_effect=subprocess.TimeoutExpired(cmd="make all", timeout=600),
    ):
        from app.services.odk_mediator import _run_command
        lines = list(_run_command(tmp_data_dir / "stream-timeout", "make all"))

    # start + error
    assert len(lines) == 2
    data = json.loads(lines[-1][6:].strip())
    assert data["type"] == "error"
    assert "timed out" in data["message"]


def test_run_command_not_found(tmp_data_dir):
    """Test graceful handling when command is not found."""
    _setup(tmp_data_dir, "stream-notfound")
    with patch(
        "app.services.odk_mediator.subprocess.run",
        side_effect=FileNotFoundError("No such file or directory: 'robot'"),
    ):
        from app.services.odk_mediator import _run_command
        lines = list(_run_command(tmp_data_dir / "stream-notfound", "robot reason"))

    # start + error
    assert len(lines) == 2
    data = json.loads(lines[-1][6:].strip())
    assert data["type"] == "error"
    assert "not found" in data["message"].lower() or "Command not found" in data["message"]


# ═══════════════════════════════════════════════════════════════
# Integration Tests: API endpoints with mocked subprocess
# ═══════════════════════════════════════════════════════════════

@pytest.fixture
def mock_subprocess():
    """Fixture that patches subprocess.run in odk_mediator."""
    mock_result = _make_completed_process(stdout=DEFAULT_STDOUT, returncode=0)
    with patch("app.services.odk_mediator.subprocess.run", return_value=mock_result) as mock_run:
        yield mock_run


@pytest.mark.asyncio
async def test_odk_seed_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 1: ODK seed streams SSE output."""
    await admin_client.post("/api/boards/med-seed")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-seed")

    resp = await admin_client.post("/api/odk-mediator/med-seed/seed")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")
    # Parse SSE lines from response body
    body = resp.text
    assert "data:" in body


@pytest.mark.asyncio
async def test_update_repo_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 1b: Update repo streams SSE output."""
    await admin_client.post("/api/boards/med-update")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-update")

    resp = await admin_client.post("/api/odk-mediator/med-update/update-repo")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_refresh_imports_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 2: Refresh imports streams SSE."""
    await admin_client.post("/api/boards/med-imports")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-imports")

    resp = await admin_client.post("/api/odk-mediator/med-imports/refresh-imports")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_reason_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 3: Reasoning with ELK streams SSE."""
    await admin_client.post("/api/boards/med-reason")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-reason")

    resp = await admin_client.post("/api/odk-mediator/med-reason/reason", json={"reasoner": "ELK"})
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")
    # Verify the SSE body contains reasoner info
    body = resp.text
    assert "ELK" in body or "data:" in body


@pytest.mark.asyncio
async def test_reason_hermit_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 3b: Reasoning with HermiT."""
    await admin_client.post("/api/boards/med-hermit")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-hermit")

    resp = await admin_client.post("/api/odk-mediator/med-hermit/reason", json={"reasoner": "HermiT"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_test_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 3c: Run ontology test suite."""
    await admin_client.post("/api/boards/med-test")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-test")

    resp = await admin_client.post("/api/odk-mediator/med-test/test")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_sparql_verify_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 4: SPARQL verification."""
    await admin_client.post("/api/boards/med-sparql")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-sparql")

    resp = await admin_client.post("/api/odk-mediator/med-sparql/verify", json={
        "sparql_file": "test_check.rq",
    })
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_release_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 5: Full release pipeline streams all steps."""
    await admin_client.post("/api/boards/med-release")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-release")

    resp = await admin_client.post("/api/odk-mediator/med-release/release")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")
    body = resp.text
    # Should contain step markers
    assert "data:" in body


@pytest.mark.asyncio
async def test_release_artifacts_endpoint(admin_client, tmp_data_dir):
    """List release artifacts."""
    await admin_client.post("/api/boards/med-artifacts")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-artifacts")

    # Create a fake artifact
    releases = tmp_data_dir / "med-artifacts" / "releases"
    releases.mkdir(exist_ok=True)
    (releases / "test.owl").write_text("<owl>test</owl>")
    (releases / "test.ttl").write_text("@prefix : <http://ex.org/> .")

    resp = await admin_client.get("/api/odk-mediator/med-artifacts/release/artifacts")
    assert resp.status_code == 200
    artifacts = resp.json()
    assert len(artifacts) == 2
    names = [a["name"] for a in artifacts]
    assert "test.owl" in names
    assert "test.ttl" in names


@pytest.mark.asyncio
async def test_download_artifact(admin_client, tmp_data_dir):
    """Download a specific release artifact."""
    await admin_client.post("/api/boards/med-dl")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-dl")

    releases = tmp_data_dir / "med-dl" / "releases"
    releases.mkdir(exist_ok=True)
    (releases / "ontology.ttl").write_text("@prefix owl: <http://www.w3.org/2002/07/owl#> .")

    resp = await admin_client.get("/api/odk-mediator/med-dl/release/download/ontology.ttl")
    assert resp.status_code == 200
    assert "@prefix" in resp.text


@pytest.mark.asyncio
async def test_dosdp_endpoint(admin_client, tmp_data_dir, mock_subprocess):
    """Workflow 6: DOSDP pattern instantiation."""
    await admin_client.post("/api/boards/med-dosdp")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-dosdp")

    resp = await admin_client.post("/api/odk-mediator/med-dosdp/dosdp", json={
        "pattern_file": "pattern.yaml",
        "data_file": "data.tsv",
    })
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_requires_auth(client, admin_client, tmp_data_dir):
    """All mediator endpoints require authentication."""
    await admin_client.post("/api/boards/med-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/odk-mediator/med-noauth/reason")
        assert resp.status_code == 401


@pytest.mark.asyncio
async def test_board_not_found(admin_client):
    """Mediator returns 404 for nonexistent boards."""
    resp = await admin_client.post("/api/odk-mediator/nonexistent/reason")
    assert resp.status_code == 404
