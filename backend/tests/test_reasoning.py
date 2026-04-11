"""Tests for reasoning service and endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest

from app.services.reasoning import (
    run_reasoning, get_cached_inferences,
    _parse_reasoning_errors, _suggest_fixes,
)

SIMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#A">
            <rdfs:label xml:lang="en">ClassA</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/test#B"/>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#B">
            <rdfs:label xml:lang="en">ClassB</rdfs:label>
        </owl:Class>
    </rdf:RDF>
""")


def _setup(data_dir: Path, board_id: str, owl: str = SIMPLE_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ── Unit tests ─────────────────────────────────────────────────

def test_parse_reasoning_errors_inconsistent(tmp_data_dir):
    _setup(tmp_data_dir, "err-test")
    stderr = "ERROR: Inconsistent class: http://example.org/test#A\nSome other error line"
    errors = _parse_reasoning_errors(stderr, tmp_data_dir / "err-test")
    assert len(errors) >= 1
    assert any("ClassA" in e["entity_label"] or "test#A" in e["entity_iri"] for e in errors)


def test_parse_reasoning_errors_unsatisfiable(tmp_data_dir):
    _setup(tmp_data_dir, "unsat-test")
    stderr = "Unsatisfiable entity: <http://example.org/test#B>"
    errors = _parse_reasoning_errors(stderr, tmp_data_dir / "unsat-test")
    assert len(errors) >= 1


def test_parse_reasoning_errors_empty():
    errors = _parse_reasoning_errors("All good\nNo issues", Path("/tmp/fake"))
    assert len(errors) == 0


def test_suggest_fixes():
    errors = [
        {"entity_iri": "http://ex.org/A", "entity_label": "ClassA",
         "axiom": "", "message": "Inconsistent", "severity": "error"},
    ]
    fixes = _suggest_fixes(errors, Path("/tmp/fake"))
    assert len(fixes) >= 1
    assert any("SubClassOf" in f["target_axiom"] for f in fixes)
    assert all(f["error_index"] == 0 for f in fixes)


# ── Integration: run_reasoning with mocked Docker ──────────────

@pytest.mark.asyncio
async def test_run_reasoning_no_docker(tmp_data_dir):
    """Without Docker, reasoning should gracefully report unavailability."""
    _setup(tmp_data_dir, "reason-nodock")
    result = await run_reasoning(tmp_data_dir / "reason-nodock", "ELK")
    # Should succeed (graceful) with no inferences
    assert "reasoner" in result
    assert result["reasoner"] == "ELK"
    assert isinstance(result["inferences"], list)
    assert isinstance(result["errors"], list)


@pytest.mark.asyncio
async def test_run_reasoning_no_owl(tmp_data_dir):
    (tmp_data_dir / "no-owl" / "src" / "ontology").mkdir(parents=True)
    result = await run_reasoning(tmp_data_dir / "no-owl", "ELK")
    assert result["success"] is False or "No OWL" in result.get("logs", "")


@pytest.mark.asyncio
async def test_run_reasoning_unsupported_reasoner(tmp_data_dir):
    _setup(tmp_data_dir, "bad-reasoner")
    result = await run_reasoning(tmp_data_dir / "bad-reasoner", "FakeReasoner")
    assert "Unsupported" in result.get("logs", "")


def test_cached_inferences_empty():
    result = get_cached_inferences("nonexistent-board")
    assert result == []


# ── API endpoint tests ─────────────────────────────────────────

@pytest.mark.asyncio
async def test_reasoning_run_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/reason-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "reason-api")

    resp = await admin_client.post("/api/reasoning/reason-api/run", json={"reasoner": "ELK"})
    assert resp.status_code == 200
    body = resp.json()
    assert "reasoner" in body
    assert body["reasoner"] == "ELK"
    assert "inferences" in body
    assert "errors" in body


@pytest.mark.asyncio
async def test_reasoning_inferences_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/inf-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "inf-api")

    resp = await admin_client.get("/api/reasoning/inf-api/inferences")
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


@pytest.mark.asyncio
async def test_reasoning_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/reason-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/reasoning/reason-noauth/run")
        assert resp.status_code == 401


@pytest.mark.asyncio
async def test_apply_fix_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/fix-test")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "fix-test")

    resp = await admin_client.post("/api/reasoning/fix-test/apply-fix", json={
        "action": "remove_axiom",
        "target_entity": "http://example.org/test#A",
        "target_axiom": "SubClassOf",
    })
    assert resp.status_code == 200
    assert resp.json()["success"] is True

    # Verify the SubClassOf axiom was removed
    from app.services.ontology import load_graph, get_ontology_statistics
    g = load_graph(tmp_data_dir / "fix-test")
    stats = get_ontology_statistics(g)
    assert stats["subclass_axioms"] == 0
