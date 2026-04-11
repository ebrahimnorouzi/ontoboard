"""Tests for SPARQL service and endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest

from app.services.sparql import execute_sparql, sparql_to_graph_visualization, get_common_prefixes

PIZZA_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/pizza#"
         xml:base="http://example.org/pizza"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/pizza"/>
        <owl:Class rdf:about="http://example.org/pizza#Pizza">
            <rdfs:label xml:lang="en">Pizza</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/pizza#Topping">
            <rdfs:label xml:lang="en">Topping</rdfs:label>
        </owl:Class>
        <owl:ObjectProperty rdf:about="http://example.org/pizza#hasTopping">
            <rdfs:label xml:lang="en">hasTopping</rdfs:label>
            <rdfs:domain rdf:resource="http://example.org/pizza#Pizza"/>
            <rdfs:range rdf:resource="http://example.org/pizza#Topping"/>
        </owl:ObjectProperty>
        <owl:NamedIndividual rdf:about="http://example.org/pizza#MyPizza">
            <rdf:type rdf:resource="http://example.org/pizza#Pizza"/>
            <rdfs:label xml:lang="en">My Pizza</rdfs:label>
        </owl:NamedIndividual>
    </rdf:RDF>
""")


def _setup(data_dir: Path, board_id: str, owl: str = PIZZA_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ── Unit tests ─────────────────────────────────────────────────

def test_select_query(tmp_data_dir):
    _setup(tmp_data_dir, "sq-sel")
    result = execute_sparql(tmp_data_dir / "sq-sel",
        "SELECT ?s ?label WHERE { ?s a <http://www.w3.org/2002/07/owl#Class> . ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label }")
    assert result["query_type"] == "SELECT"
    assert result["total"] >= 2
    assert "s" in result["columns"]
    assert "label" in result["columns"]
    labels = [r["label"] for r in result["rows"]]
    assert "Pizza" in labels
    assert "Topping" in labels


def test_ask_query(tmp_data_dir):
    _setup(tmp_data_dir, "sq-ask")
    result = execute_sparql(tmp_data_dir / "sq-ask",
        "ASK { <http://example.org/pizza#Pizza> a <http://www.w3.org/2002/07/owl#Class> }")
    assert result["query_type"] == "ASK"
    assert result["rows"][0]["result"] == "True"


def test_construct_query(tmp_data_dir):
    _setup(tmp_data_dir, "sq-con")
    result = execute_sparql(tmp_data_dir / "sq-con",
        "CONSTRUCT { ?s ?p ?o } WHERE { ?s a <http://www.w3.org/2002/07/owl#Class> . ?s ?p ?o }")
    assert result["query_type"] == "CONSTRUCT"
    assert result["total"] > 0
    assert "subject" in result["columns"]


def test_invalid_query(tmp_data_dir):
    _setup(tmp_data_dir, "sq-bad")
    result = execute_sparql(tmp_data_dir / "sq-bad", "THIS IS NOT SPARQL")
    assert result["total"] == 0 or len(result["rows"]) > 0
    # Should contain error info
    if result["rows"]:
        assert "error" in result["rows"][0] or result["total"] == 0


def test_prefixed_select(tmp_data_dir):
    _setup(tmp_data_dir, "sq-pfx")
    result = execute_sparql(tmp_data_dir / "sq-pfx",
        "PREFIX owl: <http://www.w3.org/2002/07/owl#>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nSELECT ?c ?label WHERE { ?c a owl:Class . ?c rdfs:label ?label }")
    assert result["query_type"] == "SELECT"
    assert result["total"] >= 2


def test_graph_visualization(tmp_data_dir):
    _setup(tmp_data_dir, "sq-viz")
    result = sparql_to_graph_visualization(tmp_data_dir / "sq-viz",
        "CONSTRUCT { ?s ?p ?o } WHERE { ?s a <http://www.w3.org/2002/07/owl#Class> . ?s ?p ?o }")
    assert len(result["nodes"]) >= 2
    assert len(result["edges"]) >= 1
    labels = [n["label"] for n in result["nodes"]]
    assert "Pizza" in labels or "Topping" in labels


def test_common_prefixes(tmp_data_dir):
    _setup(tmp_data_dir, "sq-ns")
    prefixes = get_common_prefixes(tmp_data_dir / "sq-ns")
    ns_list = [p["namespace"] for p in prefixes]
    assert any("owl" in ns for ns in ns_list)


def test_execution_time(tmp_data_dir):
    _setup(tmp_data_dir, "sq-time")
    result = execute_sparql(tmp_data_dir / "sq-time",
        "SELECT ?s WHERE { ?s ?p ?o } LIMIT 10")
    assert result["execution_time_ms"] >= 0


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_query_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/sparql-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "sparql-api")

    resp = await admin_client.post("/api/sparql/sparql-api/query", json={
        "query": "SELECT ?s ?label WHERE { ?s a <http://www.w3.org/2002/07/owl#Class> . ?s <http://www.w3.org/2000/01/rdf-schema#label> ?label }",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] >= 2
    assert "Pizza" in [r["label"] for r in body["rows"]]


@pytest.mark.asyncio
async def test_visualize_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/sparql-viz")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "sparql-viz")

    resp = await admin_client.post("/api/sparql/sparql-viz/visualize", json={
        "query": "CONSTRUCT { ?s ?p ?o } WHERE { ?s a <http://www.w3.org/2002/07/owl#Class> . ?s ?p ?o }",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["nodes"]) >= 2
    assert len(body["edges"]) >= 1


@pytest.mark.asyncio
async def test_prefixes_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/sparql-pfx")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "sparql-pfx")

    resp = await admin_client.get("/api/sparql/sparql-pfx/prefixes")
    assert resp.status_code == 200
    assert len(resp.json()) > 0


@pytest.mark.asyncio
async def test_query_public_board_anonymous(admin_client, tmp_data_dir):
    """Public boards allow anonymous SPARQL queries."""
    await admin_client.post("/api/boards/sparql-pub")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "sparql-pub")

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/sparql/sparql-pub/query", json={
            "query": "SELECT ?s WHERE { ?s ?p ?o } LIMIT 5",
        })
        assert resp.status_code == 200
        assert resp.json()["total"] <= 5
