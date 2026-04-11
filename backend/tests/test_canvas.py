"""Tests for canvas service — OWL↔Canvas round-trips."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph, OWL, RDF, RDFS, URIRef, Literal

from app.services.canvas import owl_to_canvas, canvas_to_owl, canvas_state_to_owl_xml
from app.schemas.canvas import CanvasState, CanvasClass, CanvasProperty, CanvasIndividual


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
            <rdfs:label xml:lang="en">has topping</rdfs:label>
            <rdfs:domain rdf:resource="http://example.org/pizza#Pizza"/>
            <rdfs:range rdf:resource="http://example.org/pizza#Topping"/>
        </owl:ObjectProperty>

        <owl:NamedIndividual rdf:about="http://example.org/pizza#MyPizza">
            <rdf:type rdf:resource="http://example.org/pizza#Pizza"/>
            <rdfs:label xml:lang="en">My Pizza</rdfs:label>
        </owl:NamedIndividual>
    </rdf:RDF>
""")


# ── Unit tests: OWL → Canvas ──────────────────────────────────

def test_owl_to_canvas_classes():
    g = Graph()
    g.parse(data=PIZZA_OWL, format="xml")
    state = owl_to_canvas(g)

    assert len(state.classes) == 3
    labels = {c.label for c in state.classes}
    assert "Pizza" in labels
    assert "Food" in labels
    assert "Topping" in labels


def test_owl_to_canvas_properties():
    g = Graph()
    g.parse(data=PIZZA_OWL, format="xml")
    state = owl_to_canvas(g)

    # Should have hasTopping (object prop) + subClassOf (Pizza→Food)
    obj_props = [p for p in state.properties if p.property_type == "object"]
    sub_props = [p for p in state.properties if p.iri == "rdfs:subClassOf"]
    assert len(obj_props) == 1
    assert obj_props[0].label == "has topping"
    assert len(sub_props) == 1
    assert "Pizza" in sub_props[0].source_id


def test_owl_to_canvas_individuals():
    g = Graph()
    g.parse(data=PIZZA_OWL, format="xml")
    state = owl_to_canvas(g)

    assert len(state.individuals) == 1
    assert state.individuals[0].label == "My Pizza"
    assert "Pizza" in state.individuals[0].class_iri


def test_owl_to_canvas_auto_layout():
    g = Graph()
    g.parse(data=PIZZA_OWL, format="xml")
    state = owl_to_canvas(g)

    # All classes should have been positioned (non-zero x/y)
    for cls in state.classes:
        assert cls.x >= 80
        assert cls.y >= 80
    # No two classes at the exact same position
    positions = [(c.x, c.y) for c in state.classes]
    assert len(positions) == len(set(positions))


# ── Unit tests: Canvas → OWL ──────────────────────────────────

def test_canvas_to_owl_classes():
    state = CanvasState(
        classes=[
            CanvasClass(id="http://ex.org/A", iri="http://ex.org/A", label="ClassA"),
            CanvasClass(id="http://ex.org/B", iri="http://ex.org/B", label="ClassB"),
        ],
    )
    g = canvas_to_owl(state, "http://ex.org/test")

    classes = set(g.subjects(RDF.type, OWL.Class))
    assert URIRef("http://ex.org/A") in classes
    assert URIRef("http://ex.org/B") in classes

    # Check labels
    label_a = list(g.objects(URIRef("http://ex.org/A"), RDFS.label))
    assert any(str(l) == "ClassA" for l in label_a)


def test_canvas_to_owl_properties():
    state = CanvasState(
        classes=[
            CanvasClass(id="http://ex.org/A", iri="http://ex.org/A", label="A"),
            CanvasClass(id="http://ex.org/B", iri="http://ex.org/B", label="B"),
        ],
        properties=[
            CanvasProperty(
                id="http://ex.org/rel", iri="http://ex.org/rel", label="relates",
                source_id="http://ex.org/A", target_id="http://ex.org/B",
                property_type="object",
            ),
        ],
    )
    g = canvas_to_owl(state, "http://ex.org/test")

    assert (URIRef("http://ex.org/rel"), RDF.type, OWL.ObjectProperty) in g
    assert (URIRef("http://ex.org/rel"), RDFS.domain, URIRef("http://ex.org/A")) in g
    assert (URIRef("http://ex.org/rel"), RDFS.range, URIRef("http://ex.org/B")) in g


def test_canvas_to_owl_subclass():
    state = CanvasState(
        classes=[
            CanvasClass(id="http://ex.org/A", iri="http://ex.org/A", label="A"),
            CanvasClass(id="http://ex.org/B", iri="http://ex.org/B", label="B"),
        ],
        properties=[
            CanvasProperty(
                id="sub_1", iri="rdfs:subClassOf", label="subClassOf",
                source_id="http://ex.org/A", target_id="http://ex.org/B",
                property_type="annotation",
            ),
        ],
    )
    g = canvas_to_owl(state, "http://ex.org/test")
    assert (URIRef("http://ex.org/A"), RDFS.subClassOf, URIRef("http://ex.org/B")) in g


def test_canvas_to_owl_individuals():
    state = CanvasState(
        classes=[
            CanvasClass(id="http://ex.org/Person", iri="http://ex.org/Person", label="Person"),
        ],
        individuals=[
            CanvasIndividual(
                id="http://ex.org/alice", iri="http://ex.org/alice",
                label="Alice", class_iri="http://ex.org/Person",
            ),
        ],
    )
    g = canvas_to_owl(state, "http://ex.org/test")
    assert (URIRef("http://ex.org/alice"), RDF.type, OWL.NamedIndividual) in g
    assert (URIRef("http://ex.org/alice"), RDF.type, URIRef("http://ex.org/Person")) in g


def test_canvas_to_owl_xml_serialization():
    state = CanvasState(
        classes=[CanvasClass(id="http://ex.org/A", iri="http://ex.org/A", label="A")],
    )
    xml = canvas_state_to_owl_xml(state, "http://ex.org/test")
    assert "owl:Class" in xml or "Class" in xml
    assert "http://ex.org/A" in xml


# ── Round-trip test ────────────────────────────────────────────

def test_round_trip_owl_canvas_owl():
    """OWL → Canvas → OWL should preserve classes, properties, individuals."""
    g1 = Graph()
    g1.parse(data=PIZZA_OWL, format="xml")
    state = owl_to_canvas(g1)

    # Canvas → OWL
    g2 = canvas_to_owl(state, "http://example.org/pizza")

    # Verify key entities survived the round-trip
    g2_classes = set(str(s) for s in g2.subjects(RDF.type, OWL.Class))
    assert "http://example.org/pizza#Pizza" in g2_classes
    assert "http://example.org/pizza#Food" in g2_classes
    assert "http://example.org/pizza#Topping" in g2_classes

    g2_props = set(str(s) for s in g2.subjects(RDF.type, OWL.ObjectProperty))
    assert "http://example.org/pizza#hasTopping" in g2_props

    g2_inds = set(str(s) for s in g2.subjects(RDF.type, OWL.NamedIndividual))
    assert "http://example.org/pizza#MyPizza" in g2_inds


# ── Integration tests: API endpoints ──────────────────────────

@pytest.mark.asyncio
async def test_load_endpoint(admin_client, tmp_data_dir):
    """Load canvas state from a board with real OWL content."""
    await admin_client.post("/api/boards/canvas-load")
    await asyncio.sleep(0.1)

    # Write richer OWL content
    ont_dir = tmp_data_dir / "canvas-load" / "src" / "ontology"
    (ont_dir / "canvas-load.owl").write_text(PIZZA_OWL)

    resp = await admin_client.get("/api/owl/canvas-load/load")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["classes"]) == 3
    assert len(body["properties"]) >= 1
    assert len(body["individuals"]) == 1


@pytest.mark.asyncio
async def test_save_endpoint(admin_client, tmp_data_dir):
    """Save canvas state to OWL file."""
    await admin_client.post("/api/boards/canvas-save")
    await asyncio.sleep(0.1)

    state = {
        "classes": [
            {"id": "http://ex.org/Cat", "iri": "http://ex.org/Cat", "label": "Cat", "x": 0, "y": 0, "w": 160, "h": 60, "color": "violet"},
            {"id": "http://ex.org/Dog", "iri": "http://ex.org/Dog", "label": "Dog", "x": 200, "y": 0, "w": 160, "h": 60, "color": "violet"},
        ],
        "properties": [
            {"id": "http://ex.org/chases", "iri": "http://ex.org/chases", "label": "chases",
             "source_id": "http://ex.org/Dog", "target_id": "http://ex.org/Cat", "property_type": "object"},
        ],
        "individuals": [],
    }

    resp = await admin_client.post("/api/owl/canvas-save/save", json=state)
    assert resp.status_code == 200

    # Verify the OWL file was written
    owl_path = tmp_data_dir / "canvas-save" / "src" / "ontology" / "canvas-save.owl"
    assert owl_path.exists()
    content = owl_path.read_text()
    assert "Cat" in content
    assert "Dog" in content
    assert "chases" in content


@pytest.mark.asyncio
async def test_load_save_round_trip(admin_client, tmp_data_dir):
    """Load → Save → Load should preserve entities."""
    await admin_client.post("/api/boards/rt-test")
    await asyncio.sleep(0.1)

    ont_dir = tmp_data_dir / "rt-test" / "src" / "ontology"
    (ont_dir / "rt-test.owl").write_text(PIZZA_OWL)

    # Load
    resp1 = await admin_client.get("/api/owl/rt-test/load")
    state = resp1.json()

    # Save
    await admin_client.post("/api/owl/rt-test/save", json=state)

    # Load again
    resp2 = await admin_client.get("/api/owl/rt-test/load")
    state2 = resp2.json()

    assert len(state2["classes"]) == len(state["classes"])
    assert len(state2["individuals"]) == len(state["individuals"])
