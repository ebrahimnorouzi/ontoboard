"""Tests for tree browser service and endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph

from app.services.tree import (
    get_class_tree, get_object_property_tree, get_individuals_by_class,
    get_entity_detail, create_entity, delete_entity,
)

RICH_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/zoo#"
         xml:base="http://example.org/zoo"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">

        <owl:Ontology rdf:about="http://example.org/zoo"/>

        <owl:Class rdf:about="http://example.org/zoo#Animal">
            <rdfs:label xml:lang="en">Animal</rdfs:label>
            <rdfs:comment xml:lang="en">A living creature</rdfs:comment>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/zoo#Mammal">
            <rdfs:label xml:lang="en">Mammal</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/zoo#Animal"/>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/zoo#Cat">
            <rdfs:label xml:lang="en">Cat</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/zoo#Mammal"/>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/zoo#Dog">
            <rdfs:label xml:lang="en">Dog</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/zoo#Mammal"/>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/zoo#Bird">
            <rdfs:label xml:lang="en">Bird</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/zoo#Animal"/>
        </owl:Class>

        <owl:ObjectProperty rdf:about="http://example.org/zoo#eats">
            <rdfs:label xml:lang="en">eats</rdfs:label>
            <rdfs:domain rdf:resource="http://example.org/zoo#Animal"/>
            <rdfs:range rdf:resource="http://example.org/zoo#Animal"/>
        </owl:ObjectProperty>

        <owl:DatatypeProperty rdf:about="http://example.org/zoo#hasName">
            <rdfs:label xml:lang="en">has name</rdfs:label>
        </owl:DatatypeProperty>

        <owl:NamedIndividual rdf:about="http://example.org/zoo#Felix">
            <rdf:type rdf:resource="http://example.org/zoo#Cat"/>
            <rdfs:label xml:lang="en">Felix</rdfs:label>
        </owl:NamedIndividual>
        <owl:NamedIndividual rdf:about="http://example.org/zoo#Rex">
            <rdf:type rdf:resource="http://example.org/zoo#Dog"/>
            <rdfs:label xml:lang="en">Rex</rdfs:label>
        </owl:NamedIndividual>
    </rdf:RDF>
""")


def _load():
    g = Graph()
    g.parse(data=RICH_OWL, format="xml")
    return g


def _write_owl(data_dir: Path, board_id: str, content: str = RICH_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(content)


# ── Unit tests: tree builders ──────────────────────────────────

def test_class_tree_hierarchy():
    g = _load()
    tree = get_class_tree(g)
    # Root should be Animal (all others are subclasses)
    root_labels = [n.label for n in tree]
    assert "Animal" in root_labels

    # Find Animal and check children
    animal = next(n for n in tree if n.label == "Animal")
    child_labels = {c.label for c in animal.children}
    assert "Mammal" in child_labels
    assert "Bird" in child_labels

    # Mammal should have Cat, Dog as children
    mammal = next(c for c in animal.children if c.label == "Mammal")
    grandchild_labels = {c.label for c in mammal.children}
    assert "Cat" in grandchild_labels
    assert "Dog" in grandchild_labels


def test_object_property_tree():
    g = _load()
    tree = get_object_property_tree(g)
    labels = [n.label for n in tree]
    assert "eats" in labels


def test_individuals_by_class():
    g = _load()
    groups = get_individuals_by_class(g)
    assert "Cat" in groups
    assert "Dog" in groups
    cat_names = [n.label for n in groups["Cat"]]
    assert "Felix" in cat_names
    dog_names = [n.label for n in groups["Dog"]]
    assert "Rex" in dog_names


def test_entity_detail_class():
    g = _load()
    detail = get_entity_detail(g, "http://example.org/zoo#Animal")
    assert detail.label == "Animal"
    assert detail.entity_type == "class"
    assert any(a.property_label == "label" for a in detail.annotations)
    assert any(a.property_label == "comment" for a in detail.annotations)
    # Usages: Mammal SubClassOf Animal, Bird SubClassOf Animal, eats domain/range
    assert len(detail.usages) >= 2


def test_entity_detail_individual():
    g = _load()
    detail = get_entity_detail(g, "http://example.org/zoo#Felix")
    assert detail.label == "Felix"
    assert detail.entity_type == "individual"


# ── Unit tests: CRUD ───────────────────────────────────────────

def test_create_entity(tmp_data_dir):
    _write_owl(tmp_data_dir, "crud-test")
    ok = create_entity(
        tmp_data_dir / "crud-test", "class",
        "http://example.org/zoo#Fish", "Fish",
        "http://example.org/zoo#Animal",
    )
    assert ok

    # Verify it was saved
    g = Graph()
    g.parse(str(tmp_data_dir / "crud-test" / "src" / "ontology" / "crud-test.owl"), format="xml")
    tree = get_class_tree(g)
    all_labels = _collect_labels(tree)
    assert "Fish" in all_labels


def test_delete_entity(tmp_data_dir):
    _write_owl(tmp_data_dir, "del-test")
    delete_entity(tmp_data_dir / "del-test", "http://example.org/zoo#Cat")

    g = Graph()
    g.parse(str(tmp_data_dir / "del-test" / "src" / "ontology" / "del-test.owl"), format="xml")
    tree = get_class_tree(g)
    all_labels = _collect_labels(tree)
    assert "Cat" not in all_labels


def _collect_labels(nodes, acc=None):
    if acc is None:
        acc = set()
    for n in nodes:
        acc.add(n.label)
        _collect_labels(n.children, acc)
    return acc


# ── Integration tests: API ─────────────────────────────────────

@pytest.mark.asyncio
async def test_classes_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-cls")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-cls")

    resp = await admin_client.get("/api/tree/tree-cls/classes")
    assert resp.status_code == 200
    tree = resp.json()
    root_labels = [n["label"] for n in tree]
    assert "Animal" in root_labels
    # Check nested structure
    animal = next(n for n in tree if n["label"] == "Animal")
    assert len(animal["children"]) >= 2


@pytest.mark.asyncio
async def test_object_properties_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-op")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-op")

    resp = await admin_client.get("/api/tree/tree-op/object-properties")
    assert resp.status_code == 200
    labels = [n["label"] for n in resp.json()]
    assert "eats" in labels


@pytest.mark.asyncio
async def test_individuals_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-ind")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-ind")

    resp = await admin_client.get("/api/tree/tree-ind/individuals")
    assert resp.status_code == 200
    groups = resp.json()
    assert "Cat" in groups
    assert any(n["label"] == "Felix" for n in groups["Cat"])


@pytest.mark.asyncio
async def test_entity_detail_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-det")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-det")

    iri = "http://example.org/zoo%23Animal"
    resp = await admin_client.get(f"/api/tree/tree-det/entity/{iri}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["label"] == "Animal"
    assert body["entity_type"] == "class"
    assert len(body["annotations"]) >= 1
    assert len(body["usages"]) >= 2


@pytest.mark.asyncio
async def test_create_entity_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-crt")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-crt")

    resp = await admin_client.post("/api/tree/tree-crt/entity", json={
        "entity_type": "class",
        "iri": "http://example.org/zoo#Reptile",
        "label": "Reptile",
        "parent_iri": "http://example.org/zoo#Animal",
    })
    assert resp.status_code == 201
    assert resp.json()["success"] is True

    # Verify in tree
    tree_resp = await admin_client.get("/api/tree/tree-crt/classes")
    animal = next(n for n in tree_resp.json() if n["label"] == "Animal")
    child_labels = [c["label"] for c in animal["children"]]
    assert "Reptile" in child_labels


@pytest.mark.asyncio
async def test_delete_entity_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-del")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-del")

    iri = "http://example.org/zoo%23Bird"
    resp = await admin_client.delete(f"/api/tree/tree-del/entity/{iri}")
    assert resp.status_code == 200

    tree_resp = await admin_client.get("/api/tree/tree-del/classes")
    all_labels = []
    def collect(nodes):
        for n in nodes:
            all_labels.append(n["label"])
            collect(n.get("children", []))
    collect(tree_resp.json())
    assert "Bird" not in all_labels


@pytest.mark.asyncio
async def test_update_annotations_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tree-ann")
    await asyncio.sleep(0.1)
    _write_owl(tmp_data_dir, "tree-ann")

    iri = "http://example.org/zoo%23Cat"
    resp = await admin_client.put(f"/api/tree/tree-ann/entity/{iri}/annotations", json=[
        {"property_iri": "http://www.w3.org/2000/01/rdf-schema#comment",
         "value": "A small feline", "language": "en", "action": "add"},
    ])
    assert resp.status_code == 200

    # Verify
    detail = await admin_client.get(f"/api/tree/tree-ann/entity/{iri}")
    annotations = detail.json()["annotations"]
    assert any(a["value"] == "A small feline" for a in annotations)
