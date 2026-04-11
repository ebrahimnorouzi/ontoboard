"""Tests for property characteristics, chains, and disjointness."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph, URIRef, RDF, OWL

from app.services.characteristics import (
    get_characteristics, set_characteristics,
    create_property_chain, get_property_chains,
    create_all_disjoint_classes, create_disjoint_properties,
)

PROP_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#A"><rdfs:label>A</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://example.org/test#B"><rdfs:label>B</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://example.org/test#C"><rdfs:label>C</rdfs:label></owl:Class>
        <owl:ObjectProperty rdf:about="http://example.org/test#hasParent">
            <rdfs:label>hasParent</rdfs:label>
        </owl:ObjectProperty>
        <owl:ObjectProperty rdf:about="http://example.org/test#hasChild">
            <rdfs:label>hasChild</rdfs:label>
        </owl:ObjectProperty>
        <owl:ObjectProperty rdf:about="http://example.org/test#hasAncestor">
            <rdfs:label>hasAncestor</rdfs:label>
        </owl:ObjectProperty>
    </rdf:RDF>
""")


def _setup(data_dir, board_id, owl=PROP_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ── Unit tests ─────────────────────────────────────────────────

def test_get_characteristics_default(tmp_data_dir):
    _setup(tmp_data_dir, "char-get")
    g = Graph(); g.parse(data=PROP_OWL, format="xml")
    chars = get_characteristics(g, "http://example.org/test#hasParent")
    assert chars["functional"] is False
    assert chars["transitive"] is False


def test_set_functional(tmp_data_dir):
    _setup(tmp_data_dir, "char-func")
    result = set_characteristics(tmp_data_dir / "char-func", "http://example.org/test#hasParent", {"functional": True})
    assert result["functional"] is True
    # Verify in graph
    g = Graph(); g.parse(str(tmp_data_dir / "char-func" / "src" / "ontology" / "char-func.owl"), format="xml")
    assert (URIRef("http://example.org/test#hasParent"), RDF.type, OWL.FunctionalProperty) in g


def test_set_transitive(tmp_data_dir):
    _setup(tmp_data_dir, "char-trans")
    result = set_characteristics(tmp_data_dir / "char-trans", "http://example.org/test#hasAncestor", {"transitive": True})
    assert result["transitive"] is True


def test_set_symmetric(tmp_data_dir):
    _setup(tmp_data_dir, "char-sym")
    result = set_characteristics(tmp_data_dir / "char-sym", "http://example.org/test#hasParent", {"symmetric": True})
    assert result["symmetric"] is True


def test_unset_characteristic(tmp_data_dir):
    _setup(tmp_data_dir, "char-unset")
    set_characteristics(tmp_data_dir / "char-unset", "http://example.org/test#hasParent", {"functional": True})
    result = set_characteristics(tmp_data_dir / "char-unset", "http://example.org/test#hasParent", {"functional": False})
    assert result["functional"] is False


def test_create_property_chain(tmp_data_dir):
    _setup(tmp_data_dir, "chain-create")
    ok = create_property_chain(tmp_data_dir / "chain-create", "http://example.org/test#hasAncestor",
                                ["http://example.org/test#hasParent", "http://example.org/test#hasParent"])
    assert ok
    g = Graph(); g.parse(str(tmp_data_dir / "chain-create" / "src" / "ontology" / "chain-create.owl"), format="xml")
    chains = get_property_chains(g, "http://example.org/test#hasAncestor")
    assert len(chains) == 1
    assert len(chains[0]) == 2


def test_property_chain_needs_2(tmp_data_dir):
    _setup(tmp_data_dir, "chain-min")
    ok = create_property_chain(tmp_data_dir / "chain-min", "http://example.org/test#hasAncestor",
                                ["http://example.org/test#hasParent"])
    assert ok is False


def test_all_disjoint_classes(tmp_data_dir):
    _setup(tmp_data_dir, "disj-all")
    ok = create_all_disjoint_classes(tmp_data_dir / "disj-all",
                                     ["http://example.org/test#A", "http://example.org/test#B", "http://example.org/test#C"])
    assert ok
    g = Graph(); g.parse(str(tmp_data_dir / "disj-all" / "src" / "ontology" / "disj-all.owl"), format="xml")
    # Should have AllDisjointClasses node
    disjoint_nodes = list(g.subjects(RDF.type, OWL.AllDisjointClasses))
    assert len(disjoint_nodes) == 1


def test_disjoint_properties(tmp_data_dir):
    _setup(tmp_data_dir, "disj-prop")
    ok = create_disjoint_properties(tmp_data_dir / "disj-prop",
                                     ["http://example.org/test#hasParent", "http://example.org/test#hasChild"])
    assert ok


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_get_characteristics_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/char-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "char-api")

    iri = "http://example.org/test%23hasParent"
    resp = await admin_client.get(f"/api/characteristics/char-api/entity/{iri}")
    assert resp.status_code == 200
    assert resp.json()["functional"] is False


@pytest.mark.asyncio
async def test_set_characteristics_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/char-set")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "char-set")

    iri = "http://example.org/test%23hasParent"
    resp = await admin_client.put(f"/api/characteristics/char-set/entity/{iri}", json={
        "functional": True, "transitive": True,
    })
    assert resp.status_code == 200
    assert resp.json()["functional"] is True
    assert resp.json()["transitive"] is True
