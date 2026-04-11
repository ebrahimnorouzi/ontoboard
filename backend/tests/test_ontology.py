"""Tests for ontology metadata, statistics, and report endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest


# ── Sample OWL for unit tests ──────────────────────────────────
SAMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/pizza#"
         xml:base="http://example.org/pizza"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
         xmlns:xsd="http://www.w3.org/2001/XMLSchema#">

        <owl:Ontology rdf:about="http://example.org/pizza">
            <rdfs:label xml:lang="en">Pizza Ontology</rdfs:label>
            <owl:versionIRI rdf:resource="http://example.org/pizza/1.0"/>
            <owl:imports rdf:resource="http://example.org/food"/>
        </owl:Ontology>

        <owl:Class rdf:about="http://example.org/pizza#Pizza">
            <rdfs:label xml:lang="en">Pizza</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://www.w3.org/2002/07/owl#Thing"/>
        </owl:Class>

        <owl:Class rdf:about="http://example.org/pizza#Topping">
            <rdfs:label xml:lang="en">Topping</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://www.w3.org/2002/07/owl#Thing"/>
        </owl:Class>

        <owl:Class rdf:about="http://example.org/pizza#MargheritaPizza">
            <rdfs:label xml:lang="en">Margherita Pizza</rdfs:label>
            <rdfs:label xml:lang="de">Margherita-Pizza</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/pizza#Pizza"/>
        </owl:Class>

        <owl:ObjectProperty rdf:about="http://example.org/pizza#hasTopping">
            <rdfs:label xml:lang="en">has topping</rdfs:label>
            <rdfs:domain rdf:resource="http://example.org/pizza#Pizza"/>
            <rdfs:range rdf:resource="http://example.org/pizza#Topping"/>
        </owl:ObjectProperty>

        <owl:DatatypeProperty rdf:about="http://example.org/pizza#hasCalories">
            <rdfs:label xml:lang="en">has calories</rdfs:label>
        </owl:DatatypeProperty>

        <owl:NamedIndividual rdf:about="http://example.org/pizza#MyPizza">
            <rdf:type rdf:resource="http://example.org/pizza#MargheritaPizza"/>
            <rdfs:label xml:lang="en">My Pizza</rdfs:label>
        </owl:NamedIndividual>
    </rdf:RDF>
""")


def _write_owl(tmp_data_dir: Path, board_id: str, owl_content: str = SAMPLE_OWL) -> Path:
    """Helper: write OWL content to the expected location."""
    ont_dir = tmp_data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    owl_path = ont_dir / f"{board_id}.owl"
    owl_path.write_text(owl_content)
    return owl_path


# ── Unit tests: ontology service ───────────────────────────────

def test_load_graph(tmp_data_dir):
    """load_graph should parse OWL XML into an rdflib Graph."""
    from app.services.ontology import load_graph
    _write_owl(tmp_data_dir, "unit-test")
    g = load_graph(tmp_data_dir / "unit-test")
    assert len(g) > 0


def test_get_ontology_metadata(tmp_data_dir):
    from app.services.ontology import load_graph, get_ontology_metadata
    _write_owl(tmp_data_dir, "meta-test")
    g = load_graph(tmp_data_dir / "meta-test")
    meta = get_ontology_metadata(g)

    assert meta["ontology_iri"] == "http://example.org/pizza"
    assert meta["version_iri"] == "http://example.org/pizza/1.0"
    assert "http://example.org/food" in meta["imports"]
    assert len(meta["prefixes"]) > 0
    assert "en" in meta["languages"]
    assert "de" in meta["languages"]


def test_get_ontology_statistics(tmp_data_dir):
    from app.services.ontology import load_graph, get_ontology_statistics
    _write_owl(tmp_data_dir, "stats-test")
    g = load_graph(tmp_data_dir / "stats-test")
    stats = get_ontology_statistics(g)

    assert stats["classes"] == 3  # Pizza, Topping, MargheritaPizza
    assert stats["object_properties"] == 1  # hasTopping
    assert stats["data_properties"] == 1  # hasCalories
    assert stats["individuals"] == 1  # MyPizza
    assert stats["subclass_axioms"] == 3  # Pizza, Topping -> Thing, Margherita -> Pizza
    assert stats["domain_axioms"] == 1
    assert stats["range_axioms"] == 1
    assert stats["total_triples"] > 0


def test_get_prefixes(tmp_data_dir):
    from app.services.ontology import load_graph, get_prefixes
    _write_owl(tmp_data_dir, "prefix-test")
    g = load_graph(tmp_data_dir / "prefix-test")
    prefixes = get_prefixes(g)
    ns_list = [p["namespace"] for p in prefixes]
    assert any("owl" in ns.lower() for ns in ns_list)


def test_parse_robot_report_tsv(tmp_path):
    from app.services.ontology import parse_robot_report_tsv
    report = tmp_path / "report.tsv"
    report.write_text(
        "Level\tRule Name\tSubject\tProperty\tMessage\n"
        "ERROR\tmissing-label\thttp://ex.org/A\trdfs:label\tEntity has no label\n"
        "WARN\tduplicate-label\thttp://ex.org/B\trdfs:label\tDuplicate label found\n"
        "INFO\tcheck-ok\thttp://ex.org/C\t\tAll good\n"
    )
    result = parse_robot_report_tsv(report)
    assert len(result["violations"]) == 3
    assert "3 violations" in result["summary"]
    assert "1 errors" in result["summary"]
    assert "1 warnings" in result["summary"]

    assert result["violations"][0]["severity"] == "ERROR"
    assert result["violations"][0]["subject"] == "http://ex.org/A"
    assert result["violations"][1]["severity"] == "WARN"


def test_parse_robot_report_empty(tmp_path):
    from app.services.ontology import parse_robot_report_tsv
    report = tmp_path / "report.tsv"
    report.write_text("Level\tRule Name\tSubject\tProperty\tMessage\n")
    result = parse_robot_report_tsv(report)
    assert len(result["violations"]) == 0


def test_parse_robot_report_missing_file(tmp_path):
    from app.services.ontology import parse_robot_report_tsv
    result = parse_robot_report_tsv(tmp_path / "nonexistent.tsv")
    assert len(result["violations"]) == 0


# ── Integration tests: API endpoints ──────────────────────────

@pytest.mark.asyncio
async def test_metadata_endpoint(admin_client, tmp_data_dir):
    """Create board with rich OWL, then fetch metadata via API."""
    await admin_client.post("/api/boards/onto-meta")
    await asyncio.sleep(0.1)
    # Overwrite the scaffold OWL with our sample
    _write_owl(tmp_data_dir, "onto-meta")

    resp = await admin_client.get("/api/ontology/onto-meta/metadata")
    assert resp.status_code == 200
    body = resp.json()
    assert body["ontology_iri"] == "http://example.org/pizza"
    assert body["version_iri"] == "http://example.org/pizza/1.0"
    assert "http://example.org/food" in body["imports"]
    assert "en" in body["languages"]
    assert "de" in body["languages"]
    assert len(body["prefixes"]) > 0


@pytest.mark.asyncio
async def test_statistics_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/onto-stats")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "onto-stats")

    resp = await admin_client.get("/api/ontology/onto-stats/statistics")
    assert resp.status_code == 200
    body = resp.json()
    assert body["classes"] == 3
    assert body["object_properties"] == 1
    assert body["data_properties"] == 1
    assert body["individuals"] == 1
    assert body["subclass_axioms"] == 3
    assert body["total_triples"] > 0


@pytest.mark.asyncio
async def test_dashboard_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/onto-dash")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "onto-dash")

    resp = await admin_client.get("/api/ontology/onto-dash/dashboard")
    assert resp.status_code == 200
    body = resp.json()
    assert "metadata" in body
    assert "statistics" in body
    assert body["metadata"]["ontology_iri"] == "http://example.org/pizza"
    assert body["statistics"]["classes"] == 3


@pytest.mark.asyncio
async def test_report_endpoint_without_docker(admin_client, tmp_data_dir):
    """Report endpoint should handle missing Docker gracefully."""
    await admin_client.post("/api/boards/onto-rpt")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "onto-rpt")

    resp = await admin_client.post("/api/ontology/onto-rpt/report")
    assert resp.status_code == 200
    body = resp.json()
    # Docker is mocked, so exit_code will be -1 and no violations parsed
    assert "exit_code" in body
    assert "violations" in body


@pytest.mark.asyncio
async def test_metadata_board_not_found(admin_client):
    resp = await admin_client.get("/api/ontology/nonexistent/metadata")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_statistics_unauthenticated_public_board(admin_client, tmp_data_dir):
    """Public boards should allow anonymous access to statistics."""
    await admin_client.post("/api/boards/pub-stats")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "pub-stats")

    # Anonymous request
    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.get("/api/ontology/pub-stats/statistics")
        assert resp.status_code == 200
        assert resp.json()["classes"] == 3
