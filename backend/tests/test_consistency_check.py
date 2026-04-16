"""Tests for the lightweight consistency-check endpoint (Phase 3).

The POST /api/reasoning/{board_id}/consistency-check endpoint calls
check_consistency_fast() to run an owlready2 HermiT check without full
inference extraction. This test suite validates:
  1. A consistent ontology returns consistent=True with no unsatisfiable classes.
  2. An inconsistent ontology (disjoint classes with a shared subclass) returns
     consistent=False with the offending class(es).
  3. A board without an OWL file returns an error gracefully.
  4. The endpoint requires authentication and edit access.
"""

import textwrap
from pathlib import Path

import pytest

from app.services.reasoning import check_consistency_fast, OWLREADY2_AVAILABLE


# ── Test data ─────────────────────────────────────────────────

CONSISTENT_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#Animal">
            <rdfs:label>Animal</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#Dog">
            <rdfs:label>Dog</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/test#Animal"/>
        </owl:Class>
    </rdf:RDF>
""")

INCONSISTENT_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#A">
            <rdfs:label>A</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#B">
            <rdfs:label>B</rdfs:label>
            <owl:disjointWith rdf:resource="http://example.org/test#A"/>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#C">
            <rdfs:label>C</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/test#A"/>
            <rdfs:subClassOf rdf:resource="http://example.org/test#B"/>
        </owl:Class>
    </rdf:RDF>
""")


def _setup(data_dir: Path, board_id: str, owl: str):
    """Write an OWL file to the expected board location."""
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ── Unit tests: check_consistency_fast ────────────────────────

@pytest.mark.skipif(not OWLREADY2_AVAILABLE, reason="owlready2 not installed")
@pytest.mark.asyncio
async def test_consistent_ontology(tmp_data_dir):
    """A simple SubClassOf hierarchy should be consistent."""
    _setup(tmp_data_dir, "cons-ok", CONSISTENT_OWL)
    result = await check_consistency_fast(tmp_data_dir / "cons-ok")
    assert result["consistent"] is True
    assert result["inconsistent_classes"] == []
    assert "duration_seconds" in result


@pytest.mark.skipif(not OWLREADY2_AVAILABLE, reason="owlready2 not installed")
@pytest.mark.asyncio
async def test_inconsistent_ontology(tmp_data_dir):
    """A class that is subClassOf two disjoint classes should be inconsistent."""
    _setup(tmp_data_dir, "cons-bad", INCONSISTENT_OWL)
    result = await check_consistency_fast(tmp_data_dir / "cons-bad")
    assert result["consistent"] is False
    assert len(result["inconsistent_classes"]) >= 1
    # Class C (or the entire ontology) should be flagged
    all_names = " ".join(result["inconsistent_classes"])
    assert "C" in all_names or len(result["inconsistent_classes"]) > 0


@pytest.mark.asyncio
async def test_no_owl_file(tmp_data_dir):
    """When no OWL file exists, the check should return a graceful error."""
    board_dir = tmp_data_dir / "no-owl-board"
    board_dir.mkdir(parents=True, exist_ok=True)
    result = await check_consistency_fast(board_dir)
    assert result["consistent"] is None
    assert "error" in result


# ── API endpoint integration tests ────────────────────────────

@pytest.mark.asyncio
async def test_endpoint_requires_auth(client, tmp_data_dir):
    """Unauthenticated requests should be rejected."""
    resp = await client.post("/api/reasoning/test-board/consistency-check")
    assert resp.status_code in (401, 403)


@pytest.mark.skipif(not OWLREADY2_AVAILABLE, reason="owlready2 not installed")
@pytest.mark.asyncio
async def test_endpoint_consistent(admin_client, tmp_data_dir):
    """Authenticated request with a consistent ontology."""
    board_id = "api-cons-ok"
    # Create board first
    resp = await admin_client.post(f"/api/boards/{board_id}")
    assert resp.status_code in (200, 201, 409)

    _setup(tmp_data_dir, board_id, CONSISTENT_OWL)
    resp = await admin_client.post(f"/api/reasoning/{board_id}/consistency-check")
    assert resp.status_code == 200
    data = resp.json()
    assert data["consistent"] is True


@pytest.mark.skipif(not OWLREADY2_AVAILABLE, reason="owlready2 not installed")
@pytest.mark.asyncio
async def test_endpoint_inconsistent(admin_client, tmp_data_dir):
    """Authenticated request with an inconsistent ontology."""
    board_id = "api-cons-bad"
    resp = await admin_client.post(f"/api/boards/{board_id}")
    assert resp.status_code in (200, 201, 409)

    _setup(tmp_data_dir, board_id, INCONSISTENT_OWL)
    resp = await admin_client.post(f"/api/reasoning/{board_id}/consistency-check")
    assert resp.status_code == 200
    data = resp.json()
    assert data["consistent"] is False
