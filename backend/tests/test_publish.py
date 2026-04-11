"""Tests for publish pipeline service and endpoints."""

import asyncio
import json
import textwrap
from pathlib import Path

import pytest

from app.services.publish import run_pre_checks, get_publish_status

SIMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#A">
            <rdfs:label xml:lang="en">A</rdfs:label>
        </owl:Class>
    </rdf:RDF>
""")


def _setup_board(data_dir: Path, board_id: str, owl: str = SIMPLE_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)
    (ont_dir / "Makefile").write_text("all:\n\t@echo done\n")
    (data_dir / board_id / "uploads").mkdir(exist_ok=True)


# ── Unit tests ─────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_pre_checks_all_pass(tmp_data_dir):
    _setup_board(tmp_data_dir, "pub-unit")
    checks = await run_pre_checks(tmp_data_dir / "pub-unit")
    names = [c["name"] for c in checks]
    assert "OWL file exists" in names
    assert "OWL parseable" in names
    assert "Non-empty ontology" in names
    assert "Makefile exists" in names
    # OWL file + parseable + non-empty + makefile should pass
    core = [c for c in checks if c["name"] in ("OWL file exists", "OWL parseable", "Makefile exists")]
    assert all(c["passed"] for c in core)


@pytest.mark.asyncio
async def test_pre_checks_no_owl(tmp_data_dir):
    (tmp_data_dir / "empty-board" / "src" / "ontology").mkdir(parents=True)
    checks = await run_pre_checks(tmp_data_dir / "empty-board")
    assert checks[0]["name"] == "OWL file exists"
    assert checks[0]["passed"] is False


@pytest.mark.asyncio
async def test_pre_checks_empty_ontology(tmp_data_dir):
    empty_owl = textwrap.dedent("""\
        <?xml version="1.0"?>
        <rdf:RDF xmlns:owl="http://www.w3.org/2002/07/owl#"
             xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <owl:Ontology rdf:about="http://example.org/empty"/>
        </rdf:RDF>
    """)
    _setup_board(tmp_data_dir, "empty-ont", empty_owl)
    checks = await run_pre_checks(tmp_data_dir / "empty-ont")
    non_empty = next(c for c in checks if c["name"] == "Non-empty ontology")
    assert non_empty["passed"] is False
    assert non_empty["severity"] == "warning"


def test_publish_status_no_publish(tmp_data_dir):
    _setup_board(tmp_data_dir, "stat-test")
    status = get_publish_status(tmp_data_dir / "stat-test")
    assert status["last_published"] is None
    assert len(status["artifacts"]) >= 1  # The .owl file


def test_publish_status_with_publish(tmp_data_dir):
    _setup_board(tmp_data_dir, "stat-pub")
    status_file = tmp_data_dir / "stat-pub" / ".publish_status.json"
    status_file.write_text(json.dumps({
        "last_published": "2026-01-01T00:00:00+00:00",
        "version": "1.0",
        "checks_passed": True,
    }))
    status = get_publish_status(tmp_data_dir / "stat-pub")
    assert status["last_published"] is not None
    assert status["version"] == "1.0"
    assert status["checks_passed"] is True


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_check_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/pub-chk")
    await asyncio.sleep(0.1)
    _setup_board(tmp_data_dir, "pub-chk")

    resp = await admin_client.post("/api/publish/pub-chk/check")
    assert resp.status_code == 200
    checks = resp.json()
    assert len(checks) >= 4
    names = [c["name"] for c in checks]
    assert "OWL file exists" in names
    assert "OWL parseable" in names


@pytest.mark.asyncio
async def test_check_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/pub-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/publish/pub-noauth/check")
        assert resp.status_code == 401


@pytest.mark.asyncio
async def test_status_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/pub-stat")
    await asyncio.sleep(0.1)
    _setup_board(tmp_data_dir, "pub-stat")

    resp = await admin_client.get("/api/publish/pub-stat/status")
    assert resp.status_code == 200
    body = resp.json()
    assert "artifacts" in body
    assert "last_published" in body


@pytest.mark.asyncio
async def test_run_publish_endpoint(admin_client, tmp_data_dir):
    """Publish endpoint should return SSE stream (even with mocked Docker)."""
    await admin_client.post("/api/boards/pub-run")
    await asyncio.sleep(0.1)
    _setup_board(tmp_data_dir, "pub-run")

    resp = await admin_client.post("/api/publish/pub-run/run", json={
        "steps": ["test"],
    })
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")
