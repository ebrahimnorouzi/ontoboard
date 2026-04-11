"""Tests for multi-format conversion and board creation from files."""

import asyncio
import io
import textwrap

import pytest
from rdflib import Graph, URIRef, RDF, RDFS, OWL

from app.services.conversion import (
    detect_format, parse_any_format, convert_graph, graph_preserves_axioms,
)


# ── Sample ontologies in different formats ─────────────────────

TURTLE_ONT = textwrap.dedent("""\
    @prefix owl: <http://www.w3.org/2002/07/owl#> .
    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    @prefix : <http://example.org/test#> .

    <http://example.org/test> a owl:Ontology .
    :Animal a owl:Class ; rdfs:label "Animal" .
    :Cat a owl:Class ; rdfs:label "Cat" ; rdfs:subClassOf :Animal .
    :hasName a owl:DatatypeProperty ; rdfs:label "hasName" .
""")

XML_ONT = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#" xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#Animal">
            <rdfs:label>Animal</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#Cat">
            <rdfs:label>Cat</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/test#Animal"/>
        </owl:Class>
    </rdf:RDF>
""")

JSONLD_ONT = """{
  "@context": {
    "owl": "http://www.w3.org/2002/07/owl#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  },
  "@graph": [
    {"@id": "http://example.org/test", "@type": "owl:Ontology"},
    {"@id": "http://example.org/test#Dog", "@type": "owl:Class", "rdfs:label": "Dog"}
  ]
}"""


# ── Format detection ───────────────────────────────────────────

def test_detect_owl_extension():
    assert detect_format("onto.owl") == "xml"

def test_detect_ttl_extension():
    assert detect_format("onto.ttl") == "turtle"

def test_detect_jsonld_extension():
    assert detect_format("onto.jsonld") == "json-ld"

def test_detect_obo_extension():
    assert detect_format("onto.obo") == "obo"

def test_detect_xml_content():
    assert detect_format("unknown.dat", "<?xml version") == "xml"

def test_detect_turtle_content():
    assert detect_format("unknown.dat", "@prefix owl:") == "turtle"

def test_detect_jsonld_content():
    assert detect_format("unknown.dat", '{"@context":') == "json-ld"


# ── Parsing ────────────────────────────────────────────────────

def test_parse_turtle():
    g = parse_any_format(TURTLE_ONT, "test.ttl")
    classes = set(str(s) for s in g.subjects(RDF.type, OWL.Class))
    assert "http://example.org/test#Animal" in classes
    assert "http://example.org/test#Cat" in classes
    # Verify subClassOf preserved
    assert (URIRef("http://example.org/test#Cat"), RDFS.subClassOf,
            URIRef("http://example.org/test#Animal")) in g


def test_parse_xml():
    g = parse_any_format(XML_ONT, "test.owl")
    classes = set(str(s) for s in g.subjects(RDF.type, OWL.Class))
    assert "http://example.org/test#Animal" in classes
    assert "http://example.org/test#Cat" in classes


def test_parse_jsonld():
    g = parse_any_format(JSONLD_ONT, "test.jsonld")
    assert len(g) > 0


def test_parse_with_fallback():
    """XML content but wrong extension should still parse via fallback."""
    g = parse_any_format(XML_ONT, "test.ttl")  # Wrong extension
    assert len(g) > 0


def test_parse_invalid_raises():
    with pytest.raises(ValueError):
        parse_any_format("this is not rdf at all!!!", "test.owl")


# ── Conversion round-trip ──────────────────────────────────────

def test_xml_to_turtle_preserves_axioms():
    g1 = parse_any_format(XML_ONT, "test.owl")
    ttl = convert_graph(g1, "turtle")
    g2 = Graph()
    g2.parse(data=ttl, format="turtle")
    result = graph_preserves_axioms(g1, g2)
    assert result["preserved"] is True


def test_turtle_to_xml_preserves_axioms():
    g1 = parse_any_format(TURTLE_ONT, "test.ttl")
    xml = convert_graph(g1, "xml")
    g2 = Graph()
    g2.parse(data=xml, format="xml")
    result = graph_preserves_axioms(g1, g2)
    assert result["preserved"] is True


def test_xml_to_nt_preserves_axioms():
    g1 = parse_any_format(XML_ONT, "test.owl")
    nt = convert_graph(g1, "nt")
    g2 = Graph()
    g2.parse(data=nt, format="nt")
    result = graph_preserves_axioms(g1, g2)
    assert result["preserved"] is True


# ── Integration: board creation from file ──────────────────────

@pytest.mark.asyncio
async def test_create_board_from_turtle(admin_client, tmp_data_dir):
    resp = await admin_client.post(
        "/api/boards/ttl-board/from-file",
        files={"file": ("onto.ttl", io.BytesIO(TURTLE_ONT.encode()), "text/turtle")},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["board_id"] == "ttl-board"
    assert body["odk_seeded"] is True

    # Verify the OWL file was created with correct content
    owl_path = tmp_data_dir / "ttl-board" / "src" / "ontology" / "ttl-board.owl"
    assert owl_path.exists()
    g = Graph()
    g.parse(str(owl_path), format="xml")
    classes = set(str(s) for s in g.subjects(RDF.type, OWL.Class))
    assert "http://example.org/test#Animal" in classes
    assert "http://example.org/test#Cat" in classes
    # SubClassOf axiom preserved through TTL→XML conversion
    assert (URIRef("http://example.org/test#Cat"), RDFS.subClassOf,
            URIRef("http://example.org/test#Animal")) in g


@pytest.mark.asyncio
async def test_create_board_from_xml(admin_client, tmp_data_dir):
    resp = await admin_client.post(
        "/api/boards/xml-board/from-file",
        files={"file": ("onto.owl", io.BytesIO(XML_ONT.encode()), "application/rdf+xml")},
    )
    assert resp.status_code == 201


@pytest.mark.asyncio
async def test_create_board_from_jsonld(admin_client, tmp_data_dir):
    resp = await admin_client.post(
        "/api/boards/jld-board/from-file",
        files={"file": ("onto.jsonld", io.BytesIO(JSONLD_ONT.encode()), "application/ld+json")},
    )
    assert resp.status_code == 201
