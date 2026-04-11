"""Tests for refactoring: rename, move, undo/redo."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph, URIRef, RDF, RDFS, OWL

from app.services.refactor import rename_iri, move_entity, undo, redo

REFACTOR_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/test#" xml:base="http://ex.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/test"/>
        <owl:Class rdf:about="http://ex.org/test#Animal"><rdfs:label>Animal</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://ex.org/test#Cat"><rdfs:label>Cat</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://ex.org/test#Animal"/>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/test#Dog"><rdfs:label>Dog</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://ex.org/test#Animal"/>
        </owl:Class>
        <owl:ObjectProperty rdf:about="http://ex.org/test#chases"><rdfs:label>chases</rdfs:label>
            <rdfs:domain rdf:resource="http://ex.org/test#Dog"/>
            <rdfs:range rdf:resource="http://ex.org/test#Cat"/>
        </owl:ObjectProperty>
    </rdf:RDF>
""")


def _setup(d, bid, owl=REFACTOR_OWL):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(owl)


def test_rename_iri_cascading(tmp_data_dir):
    _setup(tmp_data_dir, "ren-test")
    result = rename_iri(tmp_data_dir / "ren-test", "http://ex.org/test#Cat", "http://ex.org/test#Feline")
    assert result["affected_triples"] >= 3  # type, label, subClassOf + domain reference in chases

    g = Graph(); g.parse(str(tmp_data_dir / "ren-test" / "src" / "ontology" / "ren-test.owl"), format="xml")
    # Old IRI should be gone
    assert len(list(g.triples((URIRef("http://ex.org/test#Cat"), None, None)))) == 0
    # New IRI should exist
    assert (URIRef("http://ex.org/test#Feline"), RDF.type, OWL.Class) in g
    # chases range should point to new IRI
    assert (URIRef("http://ex.org/test#chases"), RDFS.range, URIRef("http://ex.org/test#Feline")) in g


def test_move_entity(tmp_data_dir):
    _setup(tmp_data_dir, "move-test")
    ok = move_entity(tmp_data_dir / "move-test", "http://ex.org/test#Cat",
                      "http://ex.org/test#Dog", "http://ex.org/test#Animal")
    assert ok
    g = Graph(); g.parse(str(tmp_data_dir / "move-test" / "src" / "ontology" / "move-test.owl"), format="xml")
    # Cat should now be subClassOf Dog, not Animal
    assert (URIRef("http://ex.org/test#Cat"), RDFS.subClassOf, URIRef("http://ex.org/test#Dog")) in g
    assert (URIRef("http://ex.org/test#Cat"), RDFS.subClassOf, URIRef("http://ex.org/test#Animal")) not in g


def test_undo(tmp_data_dir):
    _setup(tmp_data_dir, "undo-test")
    # Do a rename
    rename_iri(tmp_data_dir / "undo-test", "http://ex.org/test#Cat", "http://ex.org/test#Feline")
    # Undo it
    ok = undo(tmp_data_dir / "undo-test")
    assert ok
    # Cat should be back
    g = Graph(); g.parse(str(tmp_data_dir / "undo-test" / "src" / "ontology" / "undo-test.owl"), format="xml")
    assert (URIRef("http://ex.org/test#Cat"), RDF.type, OWL.Class) in g


def test_redo(tmp_data_dir):
    _setup(tmp_data_dir, "redo-test")
    rename_iri(tmp_data_dir / "redo-test", "http://ex.org/test#Cat", "http://ex.org/test#Feline")
    undo(tmp_data_dir / "redo-test")
    # Redo
    ok = redo(tmp_data_dir / "redo-test")
    assert ok
    g = Graph(); g.parse(str(tmp_data_dir / "redo-test" / "src" / "ontology" / "redo-test.owl"), format="xml")
    assert (URIRef("http://ex.org/test#Feline"), RDF.type, OWL.Class) in g


def test_undo_empty_stack(tmp_data_dir):
    _setup(tmp_data_dir, "undo-empty")
    ok = undo(tmp_data_dir / "undo-empty")
    assert ok is False


def test_redo_empty_stack(tmp_data_dir):
    _setup(tmp_data_dir, "redo-empty")
    ok = redo(tmp_data_dir / "redo-empty")
    assert ok is False


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_rename_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/ren-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "ren-api")

    resp = await admin_client.put("/api/refactor/ren-api/rename", json={
        "old_iri": "http://ex.org/test#Cat",
        "new_iri": "http://ex.org/test#Feline",
    })
    assert resp.status_code == 200
    assert resp.json()["affected_triples"] >= 3


@pytest.mark.asyncio
async def test_move_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/move-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "move-api")

    resp = await admin_client.put("/api/refactor/move-api/move", json={
        "entity_iri": "http://ex.org/test#Cat",
        "new_parent_iri": "http://ex.org/test#Dog",
        "old_parent_iri": "http://ex.org/test#Animal",
    })
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_undo_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/undo-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "undo-api")

    # Rename then undo
    await admin_client.put("/api/refactor/undo-api/rename", json={
        "old_iri": "http://ex.org/test#Cat", "new_iri": "http://ex.org/test#Feline",
    })
    resp = await admin_client.post("/api/refactor/undo-api/undo")
    assert resp.status_code == 200
    assert resp.json()["success"] is True
