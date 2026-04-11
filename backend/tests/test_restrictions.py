"""Tests for OWL restriction service and endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph

from app.services.restrictions import (
    get_restrictions_for_entity, add_restriction, remove_restriction, add_complex_expression,
)

RESTRICTION_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
         xmlns:xsd="http://www.w3.org/2001/XMLSchema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#Pizza">
            <rdfs:label xml:lang="en">Pizza</rdfs:label>
            <rdfs:subClassOf>
                <owl:Restriction>
                    <owl:onProperty rdf:resource="http://example.org/test#hasTopping"/>
                    <owl:someValuesFrom rdf:resource="http://example.org/test#Topping"/>
                </owl:Restriction>
            </rdfs:subClassOf>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#Topping">
            <rdfs:label xml:lang="en">Topping</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#Food">
            <rdfs:label xml:lang="en">Food</rdfs:label>
        </owl:Class>
        <owl:ObjectProperty rdf:about="http://example.org/test#hasTopping">
            <rdfs:label xml:lang="en">hasTopping</rdfs:label>
        </owl:ObjectProperty>
        <owl:ObjectProperty rdf:about="http://example.org/test#hasBase">
            <rdfs:label xml:lang="en">hasBase</rdfs:label>
        </owl:ObjectProperty>
    </rdf:RDF>
""")


def _setup(data_dir: Path, board_id: str, owl: str = RESTRICTION_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ── Unit tests ─────────────────────────────────────────────────

def test_read_existing_restriction(tmp_data_dir):
    _setup(tmp_data_dir, "r-read")
    g = Graph()
    g.parse(data=RESTRICTION_OWL, format="xml")
    restrictions = get_restrictions_for_entity(g, "http://example.org/test#Pizza")
    assert len(restrictions) >= 1
    assert restrictions[0]["restriction_type"] == "someValuesFrom"
    assert "hasTopping" in restrictions[0]["on_property_label"]
    assert "some" in restrictions[0]["manchester"]


def test_add_some_values_from(tmp_data_dir):
    _setup(tmp_data_dir, "r-some")
    ok = add_restriction(tmp_data_dir / "r-some", "http://example.org/test#Pizza", {
        "restriction_type": "someValuesFrom",
        "on_property": "http://example.org/test#hasBase",
        "filler": "http://example.org/test#Food",
    })
    assert ok
    g = Graph()
    g.parse(str(tmp_data_dir / "r-some" / "src" / "ontology" / "r-some.owl"), format="xml")
    restrictions = get_restrictions_for_entity(g, "http://example.org/test#Pizza")
    types = [r["restriction_type"] for r in restrictions]
    assert types.count("someValuesFrom") == 2


def test_add_all_values_from(tmp_data_dir):
    _setup(tmp_data_dir, "r-all")
    ok = add_restriction(tmp_data_dir / "r-all", "http://example.org/test#Pizza", {
        "restriction_type": "allValuesFrom",
        "on_property": "http://example.org/test#hasTopping",
        "filler": "http://example.org/test#Topping",
    })
    assert ok
    g = Graph()
    g.parse(str(tmp_data_dir / "r-all" / "src" / "ontology" / "r-all.owl"), format="xml")
    restrictions = get_restrictions_for_entity(g, "http://example.org/test#Pizza")
    types = [r["restriction_type"] for r in restrictions]
    assert "allValuesFrom" in types


def test_add_min_cardinality(tmp_data_dir):
    _setup(tmp_data_dir, "r-min")
    ok = add_restriction(tmp_data_dir / "r-min", "http://example.org/test#Pizza", {
        "restriction_type": "minCardinality",
        "on_property": "http://example.org/test#hasTopping",
        "cardinality": 1,
    })
    assert ok
    g = Graph()
    g.parse(str(tmp_data_dir / "r-min" / "src" / "ontology" / "r-min.owl"), format="xml")
    restrictions = get_restrictions_for_entity(g, "http://example.org/test#Pizza")
    card_restrictions = [r for r in restrictions if r["restriction_type"] == "minCardinality"]
    assert len(card_restrictions) == 1
    assert card_restrictions[0]["cardinality"] == 1


def test_add_max_cardinality(tmp_data_dir):
    _setup(tmp_data_dir, "r-max")
    ok = add_restriction(tmp_data_dir / "r-max", "http://example.org/test#Pizza", {
        "restriction_type": "maxCardinality",
        "on_property": "http://example.org/test#hasTopping",
        "cardinality": 10,
    })
    assert ok


def test_add_exact_cardinality(tmp_data_dir):
    _setup(tmp_data_dir, "r-exact")
    ok = add_restriction(tmp_data_dir / "r-exact", "http://example.org/test#Pizza", {
        "restriction_type": "exactCardinality",
        "on_property": "http://example.org/test#hasTopping",
        "cardinality": 3,
        "qualified_class": "http://example.org/test#Topping",
    })
    assert ok


def test_add_has_value(tmp_data_dir):
    _setup(tmp_data_dir, "r-val")
    ok = add_restriction(tmp_data_dir / "r-val", "http://example.org/test#Pizza", {
        "restriction_type": "hasValue",
        "on_property": "http://example.org/test#hasTopping",
        "filler": "http://example.org/test#Mozzarella",
    })
    assert ok


def test_add_union_of(tmp_data_dir):
    _setup(tmp_data_dir, "r-union")
    ok = add_complex_expression(tmp_data_dir / "r-union", "http://example.org/test#Pizza", {
        "expression_type": "unionOf",
        "operands": ["http://example.org/test#Topping", "http://example.org/test#Food"],
    })
    assert ok


def test_add_intersection_of(tmp_data_dir):
    _setup(tmp_data_dir, "r-inter")
    ok = add_complex_expression(tmp_data_dir / "r-inter", "http://example.org/test#Pizza", {
        "expression_type": "intersectionOf",
        "operands": ["http://example.org/test#Topping", "http://example.org/test#Food"],
    })
    assert ok


def test_add_complement_of(tmp_data_dir):
    _setup(tmp_data_dir, "r-comp")
    ok = add_complex_expression(tmp_data_dir / "r-comp", "http://example.org/test#Pizza", {
        "expression_type": "complementOf",
        "operands": ["http://example.org/test#Food"],
    })
    assert ok


def test_remove_restriction(tmp_data_dir):
    _setup(tmp_data_dir, "r-rem")
    g = Graph()
    g.parse(str(tmp_data_dir / "r-rem" / "src" / "ontology" / "r-rem.owl"), format="xml")
    before = len(get_restrictions_for_entity(g, "http://example.org/test#Pizza"))

    ok = remove_restriction(tmp_data_dir / "r-rem", "http://example.org/test#Pizza", {
        "restriction_type": "someValuesFrom",
        "on_property": "http://example.org/test#hasTopping",
        "filler": "http://example.org/test#Topping",
    })
    assert ok

    g2 = Graph()
    g2.parse(str(tmp_data_dir / "r-rem" / "src" / "ontology" / "r-rem.owl"), format="xml")
    after = len(get_restrictions_for_entity(g2, "http://example.org/test#Pizza"))
    assert after < before


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_get_restrictions_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/restr-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "restr-api")

    iri = "http://example.org/test%23Pizza"
    resp = await admin_client.get(f"/api/restrictions/restr-api/entity/{iri}")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1
    assert resp.json()[0]["restriction_type"] == "someValuesFrom"


@pytest.mark.asyncio
async def test_add_restriction_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/restr-add")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "restr-add")

    iri = "http://example.org/test%23Pizza"
    resp = await admin_client.post(f"/api/restrictions/restr-add/entity/{iri}", json={
        "restriction_type": "allValuesFrom",
        "on_property": "http://example.org/test#hasBase",
        "filler": "http://example.org/test#Food",
    })
    assert resp.status_code == 201
    assert resp.json()["success"] is True

    # Verify
    resp2 = await admin_client.get(f"/api/restrictions/restr-add/entity/{iri}")
    types = [r["restriction_type"] for r in resp2.json()]
    assert "allValuesFrom" in types


@pytest.mark.asyncio
async def test_add_complex_expression_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/restr-complex")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "restr-complex")

    iri = "http://example.org/test%23Pizza"
    resp = await admin_client.post(f"/api/restrictions/restr-complex/complex/{iri}", json={
        "expression_type": "unionOf",
        "operands": ["http://example.org/test#Topping", "http://example.org/test#Food"],
    })
    assert resp.status_code == 201
