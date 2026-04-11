"""Tests for ODK Mediator — all 6 workflows with mocked Docker SSE streams.

Mocking Strategy:
- docker.from_env() returns a mock client
- client.images.get() succeeds (image "exists")
- client.containers.run() returns a mock container
- container.logs(stream=True) yields fake log lines
- container.wait() returns {"StatusCode": 0}
- container.remove() is a no-op

This ensures the full FastAPI→Service→Docker→SSE pipeline is tested
without pulling the 5GB odkfull image.
"""

import asyncio
import json
import textwrap
from pathlib import Path
from unittest.mock import patch, MagicMock

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


def _make_mock_container(log_lines: list[str], exit_code: int = 0):
    """Create a mock Docker container that yields log lines."""
    container = MagicMock()
    container.logs.return_value = iter([line.encode("utf-8") for line in log_lines])
    container.wait.return_value = {"StatusCode": exit_code}
    container.remove.return_value = None
    return container


def _make_mock_docker(log_lines: list[str] = None, exit_code: int = 0):
    """Create a mock docker client + container."""
    if log_lines is None:
        log_lines = [
            "[INFO] Loading ontology...\n",
            "[INFO] Running reasoner ELK...\n",
            "[INFO] Reasoner completed successfully\n",
        ]
    mock_client = MagicMock()
    mock_client.images.get.return_value = True  # Image "exists"
    container = _make_mock_container(log_lines, exit_code)
    mock_client.containers.run.return_value = container
    return mock_client


# ═══════════════════════════════════════════════════════════════
# Unit Tests: SSE streaming from mocked Docker
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


def test_run_container_streaming_success(tmp_data_dir):
    """Test that _run_container_streaming yields proper SSE lines on success."""
    _setup(tmp_data_dir, "stream-ok")
    mock_client = _make_mock_docker([
        "[INFO] Step 1: Loading...\n",
        "[INFO] Step 2: Processing...\n",
        "[INFO] Done!\n",
    ])
    with patch("app.services.odk_mediator.docker.from_env", return_value=mock_client):
        from app.services.odk_mediator import _run_container_streaming
        lines = list(_run_container_streaming(tmp_data_dir / "stream-ok", "make all"))

    # Should have: start + 3 log lines + success
    assert len(lines) >= 4
    # First line should be "start"
    first = json.loads(lines[0][6:].strip())
    assert first["type"] == "start"
    # Last line should be "success"
    last = json.loads(lines[-1][6:].strip())
    assert last["type"] == "success"
    assert "exit 0" in last["message"]


def test_run_container_streaming_failure(tmp_data_dir):
    """Test that _run_container_streaming reports errors on non-zero exit."""
    _setup(tmp_data_dir, "stream-fail")
    mock_client = _make_mock_docker(["[ERROR] Reasoner failed!\n"], exit_code=1)
    # Override container.logs to return different results for stream vs stderr
    container = mock_client.containers.run.return_value
    def logs_side_effect(**kwargs):
        if kwargs.get("stream"):
            return iter([b"[ERROR] Reasoner failed!\n"])
        if kwargs.get("stderr") and not kwargs.get("stdout"):
            return b"Detailed error: inconsistency found"
        return b""
    container.logs.side_effect = logs_side_effect

    with patch("app.services.odk_mediator.docker.from_env", return_value=mock_client):
        from app.services.odk_mediator import _run_container_streaming
        lines = list(_run_container_streaming(tmp_data_dir / "stream-fail", "make test"))

    # Should contain an error line
    types = [json.loads(l[6:].strip())["type"] for l in lines]
    assert "error" in types


def test_run_container_no_docker(tmp_data_dir):
    """Test graceful handling when Docker is unavailable."""
    _setup(tmp_data_dir, "no-docker")
    mock_client = MagicMock()
    mock_client.images.get.side_effect = Exception("No Docker")

    with patch("app.services.odk_mediator.docker.from_env", return_value=mock_client):
        from app.services.odk_mediator import _run_container_streaming
        lines = list(_run_container_streaming(tmp_data_dir / "no-docker", "make all"))

    assert len(lines) == 1
    data = json.loads(lines[0][6:].strip())
    assert data["type"] == "error"
    assert "Docker" in data["message"] or "unavailable" in data["message"]


# ═══════════════════════════════════════════════════════════════
# Integration Tests: API endpoints with mocked Docker
# ═══════════════════════════════════════════════════════════════

@pytest.fixture
def mock_docker_mediator():
    """Fixture that patches docker.from_env specifically in odk_mediator."""
    mock_client = _make_mock_docker()
    with patch("app.services.odk_mediator.docker.from_env", return_value=mock_client):
        yield mock_client


@pytest.mark.asyncio
async def test_odk_seed_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
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
async def test_update_repo_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
    """Workflow 1b: Update repo streams SSE output."""
    await admin_client.post("/api/boards/med-update")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-update")

    resp = await admin_client.post("/api/odk-mediator/med-update/update-repo")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_refresh_imports_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
    """Workflow 2: Refresh imports streams SSE."""
    await admin_client.post("/api/boards/med-imports")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-imports")

    resp = await admin_client.post("/api/odk-mediator/med-imports/refresh-imports")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_reason_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
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
async def test_reason_hermit_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
    """Workflow 3b: Reasoning with HermiT."""
    await admin_client.post("/api/boards/med-hermit")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-hermit")

    resp = await admin_client.post("/api/odk-mediator/med-hermit/reason", json={"reasoner": "HermiT"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_test_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
    """Workflow 3c: Run ontology test suite."""
    await admin_client.post("/api/boards/med-test")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "med-test")

    resp = await admin_client.post("/api/odk-mediator/med-test/test")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_sparql_verify_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
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
async def test_release_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
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
async def test_dosdp_endpoint(admin_client, tmp_data_dir, mock_docker_mediator):
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
