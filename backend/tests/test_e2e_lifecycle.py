"""E2E Lifecycle Test — verifies the complete ontology development workflow.

Sequences:
1. Board initialization from a Turtle file
2. Fetching parsed logic + verifying axioms
3. Adding tree relationships (subClassOf)
4. Running background reasoning
5. Extracting/re-exporting data intact
"""

import asyncio
import io
import textwrap

import pytest
from rdflib import Graph, URIRef, RDF, RDFS, OWL


LIFECYCLE_TTL = textwrap.dedent("""\
    @prefix owl: <http://www.w3.org/2002/07/owl#> .
    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    @prefix : <http://example.org/lifecycle#> .

    <http://example.org/lifecycle> a owl:Ontology ;
        rdfs:label "Lifecycle Test Ontology" .

    :LivingThing a owl:Class ; rdfs:label "Living Thing" .
    :Animal a owl:Class ; rdfs:label "Animal" ; rdfs:subClassOf :LivingThing .
    :Plant a owl:Class ; rdfs:label "Plant" ; rdfs:subClassOf :LivingThing .
    :Mammal a owl:Class ; rdfs:label "Mammal" ; rdfs:subClassOf :Animal .

    :eats a owl:ObjectProperty ; rdfs:label "eats" ;
        rdfs:domain :Animal ; rdfs:range :LivingThing .
    :hasName a owl:DatatypeProperty ; rdfs:label "has name" .

    :Felix a owl:NamedIndividual, :Mammal ; rdfs:label "Felix" .
""")


@pytest.mark.asyncio
async def test_full_lifecycle(admin_client, tmp_data_dir):
    """Complete ontology development lifecycle test."""

    # ═══════════════════════════════════════════════════════════
    # STEP 1: Board initialization from Turtle file
    # ═══════════════════════════════════════════════════════════
    resp = await admin_client.post(
        "/api/boards/lifecycle/from-file",
        files={"file": ("lifecycle.ttl", io.BytesIO(LIFECYCLE_TTL.encode()), "text/turtle")},
    )
    assert resp.status_code == 201, f"Board creation failed: {resp.json()}"
    board = resp.json()
    assert board["board_id"] == "lifecycle"
    await asyncio.sleep(0.2)

    # ═══════════════════════════════════════════════════════════
    # STEP 2: Fetch parsed logic — verify ALL axioms survived conversion
    # ═══════════════════════════════════════════════════════════
    # 2a: Dashboard metadata
    dash = await admin_client.get("/api/ontology/lifecycle/dashboard")
    assert dash.status_code == 200
    stats = dash.json()["statistics"]
    assert stats["classes"] >= 4, f"Expected 4+ classes, got {stats['classes']}"
    assert stats["object_properties"] >= 1
    assert stats["individuals"] >= 1
    assert stats["subclass_axioms"] >= 3

    # 2b: Load canvas — verify class/property structure
    canvas = await admin_client.get("/api/owl/lifecycle/load")
    assert canvas.status_code == 200
    canvas_data = canvas.json()
    class_iris = [c["iri"] for c in canvas_data["classes"]]
    assert "http://example.org/lifecycle#Animal" in class_iris
    assert "http://example.org/lifecycle#Mammal" in class_iris

    # 2c: Tree hierarchy — verify subClassOf structure
    tree = await admin_client.get("/api/tree/lifecycle/classes")
    assert tree.status_code == 200
    tree_data = tree.json()

    def collect_labels(nodes, acc=None):
        if acc is None: acc = set()
        for n in nodes:
            acc.add(n["label"])
            collect_labels(n.get("children", []), acc)
        return acc

    all_labels = collect_labels(tree_data)
    assert "Living Thing" in all_labels
    assert "Animal" in all_labels
    assert "Mammal" in all_labels

    # 2d: Axiom verification — Mammal SubClassOf Animal
    mammal_iri = "http://example.org/lifecycle%23Mammal"
    axioms = await admin_client.get(f"/api/axiom/lifecycle/axioms/{mammal_iri}")
    assert axioms.status_code == 200
    axiom_types = [a["axiom_type"] for a in axioms.json()]
    assert "SubClassOf" in axiom_types

    # ═══════════════════════════════════════════════════════════
    # STEP 3: Mutate — add new entity via tree, add subClassOf
    # ═══════════════════════════════════════════════════════════
    # 3a: Create new class "Cat" under "Mammal"
    create_resp = await admin_client.post("/api/tree/lifecycle/entity", json={
        "entity_type": "class",
        "iri": "http://example.org/lifecycle#Cat",
        "label": "Cat",
        "parent_iri": "http://example.org/lifecycle#Mammal",
    })
    assert create_resp.status_code == 201

    # 3b: Verify tree updated
    tree2 = await admin_client.get("/api/tree/lifecycle/classes")
    all_labels2 = collect_labels(tree2.json())
    assert "Cat" in all_labels2

    # 3c: Verify the canvas also reflects the change
    canvas2 = await admin_client.get("/api/owl/lifecycle/load")
    class_iris2 = [c["iri"] for c in canvas2.json()["classes"]]
    assert "http://example.org/lifecycle#Cat" in class_iris2

    # 3d: Search for the new entity
    search_resp = await admin_client.post("/api/search/lifecycle", json={"query": "Cat"})
    assert search_resp.status_code == 200
    assert any("Cat" in r["label"] for r in search_resp.json())

    # ═══════════════════════════════════════════════════════════
    # STEP 4: Reasoning — run reasoner, check inferences
    # ═══════════════════════════════════════════════════════════
    reason_resp = await admin_client.post("/api/reasoning/lifecycle/run", json={"reasoner": "ELK"})
    assert reason_resp.status_code == 200
    reason_data = reason_resp.json()
    assert "reasoner" in reason_data
    assert reason_data["reasoner"] == "ELK"

    # 4b: Publish pre-checks
    checks = await admin_client.post("/api/publish/lifecycle/check")
    assert checks.status_code == 200
    check_results = checks.json()
    assert any(c["name"] == "OWL file exists" and c["passed"] for c in check_results)
    assert any(c["name"] == "OWL parseable" and c["passed"] for c in check_results)
    assert any(c["name"] == "Non-empty ontology" and c["passed"] for c in check_results)

    # ═══════════════════════════════════════════════════════════
    # STEP 5: Export — re-export data, verify integrity
    # ═══════════════════════════════════════════════════════════
    # 5a: Save canvas state back to OWL
    save_resp = await admin_client.post("/api/owl/lifecycle/save", json=canvas2.json())
    assert save_resp.status_code == 200

    # 5b: Reload and verify round-trip
    canvas3 = await admin_client.get("/api/owl/lifecycle/load")
    assert canvas3.status_code == 200
    final_classes = [c["iri"] for c in canvas3.json()["classes"]]
    assert "http://example.org/lifecycle#Animal" in final_classes
    assert "http://example.org/lifecycle#Cat" in final_classes

    # 5c: SPARQL query to verify data integrity
    sparql_resp = await admin_client.post("/api/sparql/lifecycle/query", json={
        "query": "PREFIX owl: <http://www.w3.org/2002/07/owl#>\nSELECT (COUNT(?c) as ?count) WHERE { ?c a owl:Class }",
    })
    assert sparql_resp.status_code == 200
    assert int(sparql_resp.json()["rows"][0]["count"]) >= 5  # 4 original + Cat

    # 5d: Generate documentation
    docs_resp = await admin_client.post("/api/docs/lifecycle/build")
    assert docs_resp.status_code == 200

    # 5e: Quality check
    quality_resp = await admin_client.get("/api/quality/lifecycle/oquare")
    assert quality_resp.status_code == 200
    assert quality_resp.json()["overall_score"] > 0

    # 5f: Version bump
    ver_resp = await admin_client.put("/api/version/lifecycle", json={"bump": "minor"})
    assert ver_resp.status_code == 200
    assert ver_resp.json()["version"] is not None


@pytest.mark.asyncio
async def test_turtle_axiom_preservation(admin_client, tmp_data_dir):
    """Verify that Turtle→XML conversion never drops logical constraints."""
    resp = await admin_client.post(
        "/api/boards/axiom-check/from-file",
        files={"file": ("test.ttl", io.BytesIO(LIFECYCLE_TTL.encode()), "text/turtle")},
    )
    assert resp.status_code == 201
    await asyncio.sleep(0.1)

    # Read the converted OWL file and verify all axioms
    from app.services.board import get_board_dir
    owl_path = get_board_dir("axiom-check") / "src" / "ontology" / "axiom-check.owl"
    assert owl_path.exists()

    g = Graph()
    g.parse(str(owl_path), format="xml")

    # Verify classes
    classes = set(str(s) for s in g.subjects(RDF.type, OWL.Class))
    assert "http://example.org/lifecycle#LivingThing" in classes
    assert "http://example.org/lifecycle#Animal" in classes
    assert "http://example.org/lifecycle#Mammal" in classes
    assert "http://example.org/lifecycle#Plant" in classes

    # Verify subClassOf axioms survived
    assert (URIRef("http://example.org/lifecycle#Animal"),
            RDFS.subClassOf,
            URIRef("http://example.org/lifecycle#LivingThing")) in g
    assert (URIRef("http://example.org/lifecycle#Mammal"),
            RDFS.subClassOf,
            URIRef("http://example.org/lifecycle#Animal")) in g

    # Verify object property with domain/range
    assert (URIRef("http://example.org/lifecycle#eats"),
            RDF.type, OWL.ObjectProperty) in g
    assert (URIRef("http://example.org/lifecycle#eats"),
            RDFS.domain,
            URIRef("http://example.org/lifecycle#Animal")) in g
    assert (URIRef("http://example.org/lifecycle#eats"),
            RDFS.range,
            URIRef("http://example.org/lifecycle#LivingThing")) in g

    # Verify individual
    assert (URIRef("http://example.org/lifecycle#Felix"),
            RDF.type, OWL.NamedIndividual) in g
    assert (URIRef("http://example.org/lifecycle#Felix"),
            RDF.type,
            URIRef("http://example.org/lifecycle#Mammal")) in g
