"""Tests for axiom service and endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph

from app.services.axiom import (
    get_axioms_for_entity,
    get_manchester_for_entity,
    validate_manchester,
    get_entity_names,
)

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
            <rdfs:subClassOf rdf:resource="http://example.org/pizza#Food"/>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/pizza#Food">
            <rdfs:label xml:lang="en">Food</rdfs:label>
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


def _write_owl(tmp_data_dir: Path, board_id: str, content: str = PIZZA_OWL) -> Path:
    ont_dir = tmp_data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    owl_path = ont_dir / f"{board_id}.owl"
    owl_path.write_text(content)
    return owl_path


def _load():
    g = Graph()
    g.parse(data=PIZZA_OWL, format="xml")
    return g


# ── Unit tests ─────────────────────────────────────────────────

def test_get_axioms_for_class():
    g = _load()
    axioms = get_axioms_for_entity(g, "http://example.org/pizza#Pizza")
    types = [a["axiom_type"] for a in axioms]
    assert "SubClassOf" in types


def test_get_axioms_for_property():
    g = _load()
    axioms = get_axioms_for_entity(g, "http://example.org/pizza#hasTopping")
    types = [a["axiom_type"] for a in axioms]
    assert "Domain" in types
    assert "Range" in types


def test_get_manchester_class():
    g = _load()
    text = get_manchester_for_entity(g, "http://example.org/pizza#Pizza")
    assert "Class: Pizza" in text
    assert "SubClassOf: Food" in text


def test_get_manchester_property():
    g = _load()
    text = get_manchester_for_entity(g, "http://example.org/pizza#hasTopping")
    assert "ObjectProperty: hasTopping" in text
    assert "Domain: Pizza" in text
    assert "Range: Topping" in text


def test_get_manchester_individual():
    g = _load()
    text = get_manchester_for_entity(g, "http://example.org/pizza#MyPizza")
    assert "Individual: My Pizza" in text
    assert "Types:" in text


def test_validate_valid_manchester():
    text = "Class: Pizza\n    SubClassOf: Food\n    DisjointWith: Drink"
    errors = validate_manchester(text)
    assert len(errors) == 0


def test_validate_invalid_manchester():
    text = "Class: Pizza\nthis is not valid\n    SubClassOf: Food"
    errors = validate_manchester(text)
    assert len(errors) == 1
    assert errors[0]["line"] == 2


def test_get_entity_names():
    g = _load()
    names = get_entity_names(g)
    labels = [n["label"] for n in names]
    assert "Pizza" in labels
    assert "hasTopping" in labels
    assert "My Pizza" in labels
    types = {n["label"]: n["type"] for n in names}
    assert types["Pizza"] == "class"
    assert types["hasTopping"] == "object_property"
    assert types["My Pizza"] == "individual"


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_axiom_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/ax-test")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "ax-test")

    resp = await admin_client.get("/api/axiom/ax-test/axioms/http://example.org/pizza%23Pizza")
    assert resp.status_code == 200
    axioms = resp.json()
    types = [a["axiom_type"] for a in axioms]
    assert "SubClassOf" in types


@pytest.mark.asyncio
async def test_manchester_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/man-test")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "man-test")

    resp = await admin_client.get("/api/axiom/man-test/manchester/http://example.org/pizza%23Pizza")
    assert resp.status_code == 200
    assert "Class: Pizza" in resp.json()["manchester"]
    assert "SubClassOf" in resp.json()["manchester"]


@pytest.mark.asyncio
async def test_entity_names_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/names-test")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "names-test")

    resp = await admin_client.get("/api/axiom/names-test/entity-names")
    assert resp.status_code == 200
    labels = [e["label"] for e in resp.json()]
    assert "Pizza" in labels


@pytest.mark.asyncio
async def test_validate_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/val-test")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "val-test")

    resp = await admin_client.post("/api/axiom/val-test/validate", json={
        "manchester_text": "Class: Pizza\nbad syntax here\n    SubClassOf: Food",
    })
    assert resp.status_code == 200
    errors = resp.json()
    assert len(errors) == 1
    assert errors[0]["line"] == 2


@pytest.mark.asyncio
async def test_update_axioms_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/upd-ax")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "upd-ax")

    resp = await admin_client.put(
        "/api/axiom/upd-ax/axioms/http://example.org/pizza%23Pizza",
        json={"manchester_text": "Class: Pizza\n    SubClassOf: Food\n    SubClassOf: Topping"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["applied"] == 2

    # Verify the change was saved
    resp2 = await admin_client.get("/api/axiom/upd-ax/manchester/http://example.org/pizza%23Pizza")
    text = resp2.json()["manchester"]
    assert "SubClassOf: Food" in text
    assert "SubClassOf: Topping" in text
