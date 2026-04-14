"""Tests for Tier 2 features.

Covers:
1. ROBOT Explain — service-level explain helpers and API endpoints
2. Import Resolution — owl:imports detection, catalog management, API endpoints
3. SWRL Rules — CRUD for SWRL rules stored as comments, API endpoints
4. Embedded Reasoner — owlready2-based consistency/inference checks, API endpoints
5. Integration — multi-step workflows combining the above features
"""

import asyncio
import textwrap
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from rdflib import Graph, URIRef, Literal, RDF, RDFS, OWL

from app.services.board import get_board_dir

# ── Shared OWL data ──────────────────────────────────────────────

RICH_OWL = '''<?xml version="1.0"?>
<rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
    xmlns:xsd="http://www.w3.org/2001/XMLSchema#">
  <owl:Ontology rdf:about="http://ex.org/t">
    <owl:imports rdf:resource="http://example.org/imported"/>
  </owl:Ontology>
  <owl:Class rdf:about="http://ex.org/t#Animal"><rdfs:label>Animal</rdfs:label></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#Cat">
    <rdfs:label>Cat</rdfs:label>
    <rdfs:subClassOf rdf:resource="http://ex.org/t#Animal"/>
  </owl:Class>
  <owl:Class rdf:about="http://ex.org/t#Dog">
    <rdfs:label>Dog</rdfs:label>
    <rdfs:subClassOf rdf:resource="http://ex.org/t#Animal"/>
  </owl:Class>
  <owl:ObjectProperty rdf:about="http://ex.org/t#hasPart"><rdfs:label>hasPart</rdfs:label></owl:ObjectProperty>
  <owl:DatatypeProperty rdf:about="http://ex.org/t#hasAge">
    <rdfs:label>hasAge</rdfs:label>
    <rdfs:range rdf:resource="http://www.w3.org/2001/XMLSchema#integer"/>
  </owl:DatatypeProperty>
</rdf:RDF>'''

CATALOG_XML = textwrap.dedent("""\
    <?xml version="1.0" encoding="UTF-8" standalone="no"?>
    <catalog prefer="public" xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
    </catalog>
""")

NS = "http://ex.org/t#"


def _setup(d, bid):
    """Write OWL file and catalog into the board directory structure."""
    p = d / bid / "src" / "ontology"
    p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(RICH_OWL)
    # Create catalog
    (p / "catalog-v001.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>'
        '<catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog" prefer="public"/>'
    )


def _reload_graph(d, bid):
    """Reload the graph from the saved OWL file on disk."""
    g = Graph()
    g.parse(str(d / bid / "src" / "ontology" / f"{bid}.owl"), format="xml")
    return g


# =========================================================================
# 1. ROBOT Explain Tests
# =========================================================================


class TestRobotExplain:
    """Tests for ROBOT explain service and endpoints."""

    def test_explain_service_exists(self):
        """The explain functions should be importable from reasoning service."""
        from app.services.reasoning import explain_unsatisfiable, explain_inconsistency
        assert callable(explain_unsatisfiable)
        assert callable(explain_inconsistency)

    @pytest.mark.asyncio
    async def test_explain_unsatisfiable_no_robot(self, tmp_data_dir):
        """explain_unsatisfiable should return explanation structure even without Docker."""
        _setup(tmp_data_dir, "expl-unsat")
        from app.services.reasoning import explain_unsatisfiable

        result = await explain_unsatisfiable(
            tmp_data_dir / "expl-unsat",
            "http://ex.org/t#Cat",
            reasoner="ELK",
        )
        assert isinstance(result, dict)
        assert "entity" in result
        assert result["entity"] == "http://ex.org/t#Cat"
        assert "explanation_text" in result
        assert "justification_axioms" in result
        assert isinstance(result["justification_axioms"], list)
        assert "suggested_fixes" in result
        assert isinstance(result["suggested_fixes"], list)

    @pytest.mark.asyncio
    async def test_explain_inconsistency_returns_dict(self, tmp_data_dir):
        """explain_inconsistency should return a dict with the proper format."""
        _setup(tmp_data_dir, "expl-incon")
        from app.services.reasoning import explain_inconsistency

        result = await explain_inconsistency(
            tmp_data_dir / "expl-incon",
            reasoner="ELK",
        )
        assert isinstance(result, dict)
        assert "entity" in result
        assert "explanation_text" in result
        assert "justification_axioms" in result
        assert "suggested_fixes" in result

    @pytest.mark.asyncio
    async def test_explain_endpoint_exists(self, admin_client, tmp_data_dir):
        """POST /{board_id}/explain should return 200 or a known error."""
        await admin_client.post("/api/boards/expl-ep")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "expl-ep")

        resp = await admin_client.post(
            "/api/reasoning/expl-ep/explain",
            json={"reasoner": "ELK"},
        )
        # Should be 200 (explain returns gracefully even without Docker)
        assert resp.status_code == 200
        body = resp.json()
        assert "explanation_text" in body

    @pytest.mark.asyncio
    async def test_explain_entity_endpoint(self, admin_client, tmp_data_dir):
        """POST /{board_id}/explain/{entity_iri} should return explanation for entity."""
        await admin_client.post("/api/boards/expl-ent")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "expl-ent")

        resp = await admin_client.post(
            "/api/reasoning/expl-ent/explain/http://ex.org/t%23Cat",
            json={"reasoner": "ELK"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert "entity" in body
        assert "explanation_text" in body

    @pytest.mark.asyncio
    async def test_explain_nonexistent_board(self, admin_client, tmp_data_dir):
        """POST explain on a nonexistent board should return 404."""
        resp = await admin_client.post(
            "/api/reasoning/no-such-board-9999/explain",
            json={"reasoner": "ELK"},
        )
        assert resp.status_code == 404


# =========================================================================
# 2. Import Resolution Tests
# =========================================================================


class TestImportResolution:
    """Tests for import resolution, catalog management, and API endpoints."""

    def test_resolve_imports_finds_owl_imports(self, tmp_data_dir):
        """list_imports should detect owl:imports in the graph."""
        _setup(tmp_data_dir, "imp-find")
        from app.services.imports import list_imports

        imports = list_imports(tmp_data_dir / "imp-find")
        assert len(imports) >= 1
        assert any("http://example.org/imported" in i["iri"] for i in imports)

    def test_resolve_imports_checks_catalog(self, tmp_data_dir):
        """list_imports should report whether an import is mirrored locally."""
        _setup(tmp_data_dir, "imp-cat")
        from app.services.imports import list_imports

        imports = list_imports(tmp_data_dir / "imp-cat")
        # The "http://example.org/imported" has no local mirror file
        for imp in imports:
            if "imported" in imp["iri"]:
                assert imp["mirrored"] is False

    def test_resolve_imports_missing_status(self, tmp_data_dir):
        """An import without a local file should have mirrored=False."""
        _setup(tmp_data_dir, "imp-miss")
        from app.services.imports import list_imports

        imports = list_imports(tmp_data_dir / "imp-miss")
        assert len(imports) >= 1
        for imp in imports:
            # No local imports/ directory exists → mirrored must be False
            assert imp["mirrored"] is False

    def test_update_catalog_creates_entry(self, tmp_data_dir):
        """add_import should create a new catalog entry."""
        _setup(tmp_data_dir, "imp-catcr")
        from app.services.imports import add_import

        ok = add_import(
            tmp_data_dir / "imp-catcr",
            "http://purl.obolibrary.org/obo/bfo.owl",
            "bfo",
        )
        assert ok

        # Read catalog and check for the new entry
        catalog_path = tmp_data_dir / "imp-catcr" / "src" / "ontology" / "catalog-v001.xml"
        catalog_text = catalog_path.read_text()
        assert "bfo.owl" in catalog_text or "bfo" in catalog_text

    def test_update_catalog_updates_existing(self, tmp_data_dir):
        """Adding the same import twice should not duplicate entries, or should update."""
        _setup(tmp_data_dir, "imp-catup")
        from app.services.imports import add_import, list_imports

        add_import(tmp_data_dir / "imp-catup", "http://example.org/new.owl", "new")
        add_import(tmp_data_dir / "imp-catup", "http://example.org/new.owl", "new")

        imports = list_imports(tmp_data_dir / "imp-catup")
        # The OWL graph should contain the import at least once
        new_imports = [i for i in imports if "new.owl" in i["iri"]]
        assert len(new_imports) >= 1

    def test_remove_import_from_graph(self, tmp_data_dir):
        """remove_import should remove the owl:imports triple from the graph."""
        _setup(tmp_data_dir, "imp-rem")
        from app.services.imports import remove_import, list_imports

        ok = remove_import(tmp_data_dir / "imp-rem", "http://example.org/imported")
        assert ok

        imports = list_imports(tmp_data_dir / "imp-rem")
        assert not any("imported" in i["iri"] for i in imports)

    @pytest.mark.asyncio
    async def test_resolve_imports_endpoint(self, admin_client, tmp_data_dir):
        """GET /{board_id} should return imports list."""
        await admin_client.post("/api/boards/imp-resep")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "imp-resep")

        resp = await admin_client.get("/api/imports/imp-resep")
        assert resp.status_code == 200
        body = resp.json()
        assert isinstance(body, list)
        assert len(body) >= 1
        assert any("imported" in i["iri"] for i in body)

    @pytest.mark.asyncio
    async def test_add_import_endpoint(self, admin_client, tmp_data_dir):
        """POST /{board_id} should add a new import."""
        await admin_client.post("/api/boards/imp-addep")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "imp-addep")

        resp = await admin_client.post("/api/imports/imp-addep", json={
            "iri": "http://purl.obolibrary.org/obo/go.owl",
            "prefix": "go",
        })
        assert resp.status_code == 201

        # Verify the import was added
        resp2 = await admin_client.get("/api/imports/imp-addep")
        assert resp2.status_code == 200
        assert any("go.owl" in i["iri"] for i in resp2.json())


# =========================================================================
# 3. SWRL Tests
# =========================================================================


class TestSwrl:
    """Tests for SWRL rule CRUD and API endpoints."""

    def test_get_swrl_rules_empty(self, tmp_data_dir):
        """An ontology with no SWRL rules should return an empty list."""
        _setup(tmp_data_dir, "swrl-empty")
        from app.services.swrl import list_rules

        rules = list_rules(tmp_data_dir / "swrl-empty")
        assert rules == []

    def test_add_swrl_rule(self, tmp_data_dir):
        """add_rule should store a SWRL rule and return it."""
        _setup(tmp_data_dir, "swrl-add")
        from app.services.swrl import add_rule, list_rules

        result = add_rule(tmp_data_dir / "swrl-add", "Animal(?x) ^ hasAge(?x, ?a) -> Cat(?x)")
        assert "id" in result
        assert "id" in result and "label" in result
        assert result["label"] == "Animal(?x) ^ hasAge(?x, ?a) -> Cat(?x)"

        # Verify it's in the list
        rules = list_rules(tmp_data_dir / "swrl-add")
        assert len(rules) == 1
        assert rules[0]["label"] == "Animal(?x) ^ hasAge(?x, ?a) -> Cat(?x)"

    def test_delete_swrl_rule(self, tmp_data_dir):
        """delete_rule should remove a SWRL rule from the graph."""
        _setup(tmp_data_dir, "swrl-del")
        from app.services.swrl import add_rule, delete_rule, list_rules

        add_rule(tmp_data_dir / "swrl-del", "Cat(?x) -> Animal(?x)")
        assert len(list_rules(tmp_data_dir / "swrl-del")) == 1

        ok = delete_rule(tmp_data_dir / "swrl-del", "Cat(?x) -> Animal(?x)")
        assert ok is True

        rules = list_rules(tmp_data_dir / "swrl-del")
        assert len(rules) == 0

    @pytest.mark.asyncio
    async def test_swrl_endpoint_list(self, admin_client, tmp_data_dir):
        """GET /{board_id}/swrl should return rules list."""
        await admin_client.post("/api/boards/swrl-list")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "swrl-list")

        resp = await admin_client.get("/api/dlquery/swrl-list/swrl")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    @pytest.mark.asyncio
    async def test_swrl_endpoint_create(self, admin_client, tmp_data_dir):
        """POST /{board_id}/swrl should create a new SWRL rule."""
        await admin_client.post("/api/boards/swrl-crt")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "swrl-crt")

        resp = await admin_client.post(
            "/api/dlquery/swrl-crt/swrl",
            json={"rule": "Dog(?x) -> Animal(?x)"},
        )
        assert resp.status_code == 201
        body = resp.json()
        assert "id" in body
        assert body["label"] == "Dog(?x) -> Animal(?x)"

    @pytest.mark.asyncio
    async def test_swrl_endpoint_delete(self, admin_client, tmp_data_dir):
        """DELETE /{board_id}/swrl should remove a SWRL rule."""
        await admin_client.post("/api/boards/swrl-dlep")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "swrl-dlep")

        # First create a rule
        await admin_client.post(
            "/api/dlquery/swrl-dlep/swrl",
            json={"rule": "Cat(?x) -> Animal(?x)"},
        )

        # Delete it
        resp = await admin_client.request(
            "DELETE",
            "/api/dlquery/swrl-dlep/swrl",
            json={"rule": "Cat(?x) -> Animal(?x)"},
        )
        assert resp.status_code == 200
        assert resp.json()["success"] is True

        # Verify it's gone
        resp2 = await admin_client.get("/api/dlquery/swrl-dlep/swrl")
        assert len(resp2.json()) == 0


# =========================================================================
# 4. Embedded Reasoner Tests
# =========================================================================

try:
    import owlready2
    HAS_OWLREADY2 = True
except ImportError:
    HAS_OWLREADY2 = False


class TestEmbeddedReasoner:
    """Tests for embedded reasoning via owlready2 (skip if not installed)."""

    @pytest.mark.skipif(not HAS_OWLREADY2, reason="owlready2 not installed")
    def test_embedded_reasoner_import(self):
        """owlready2 should be importable."""
        import owlready2
        assert hasattr(owlready2, "get_ontology")

    @pytest.mark.skipif(not HAS_OWLREADY2, reason="owlready2 not installed")
    def test_consistency_check_consistent(self, tmp_data_dir):
        """A consistent ontology should pass the consistency check."""
        # Use simple OWL without imports (owlready2 tries to download imports)
        simple = '''<?xml version="1.0"?>
<rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <owl:Ontology rdf:about="http://ex.org/t"/>
  <owl:Class rdf:about="http://ex.org/t#Cat"><rdfs:subClassOf rdf:resource="http://ex.org/t#Animal"/></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#Animal"/>
</rdf:RDF>'''
        p = tmp_data_dir / "embed-con" / "src" / "ontology"
        p.mkdir(parents=True, exist_ok=True)
        owl_path = p / "embed-con.owl"
        owl_path.write_text(simple)

        import owlready2
        world = owlready2.World()
        onto = world.get_ontology(str(owl_path.resolve())).load()
        try:
            with onto:
                owlready2.sync_reasoner(world, infer_property_values=False)
            consistent = True
        except owlready2.OwlReadyInconsistentOntologyError:
            consistent = False

        assert consistent is True

    @pytest.mark.skipif(not HAS_OWLREADY2, reason="owlready2 not installed")
    def test_consistency_check_inconsistent(self, tmp_data_dir):
        """An ontology with a class that is both subclass of two disjoint classes
        should be detected as inconsistent."""
        inconsistent_owl = '''<?xml version="1.0"?>
<rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <owl:Ontology rdf:about="http://ex.org/t"/>
  <owl:Class rdf:about="http://ex.org/t#A"><rdfs:label>A</rdfs:label></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#B">
    <rdfs:label>B</rdfs:label>
    <owl:disjointWith rdf:resource="http://ex.org/t#A"/>
  </owl:Class>
  <owl:Class rdf:about="http://ex.org/t#C">
    <rdfs:label>C</rdfs:label>
    <rdfs:subClassOf rdf:resource="http://ex.org/t#A"/>
    <rdfs:subClassOf rdf:resource="http://ex.org/t#B"/>
  </owl:Class>
  <owl:NamedIndividual rdf:about="http://ex.org/t#c1">
    <rdf:type rdf:resource="http://ex.org/t#C"/>
  </owl:NamedIndividual>
</rdf:RDF>'''
        p = tmp_data_dir / "embed-incon" / "src" / "ontology"
        p.mkdir(parents=True, exist_ok=True)
        (p / "embed-incon.owl").write_text(inconsistent_owl)

        owl_path = p / "embed-incon.owl"
        import owlready2
        world = owlready2.World()
        onto = world.get_ontology(str(owl_path.resolve())).load()
        consistent = True
        try:
            with onto:
                owlready2.sync_reasoner(world, infer_property_values=False)
        except owlready2.OwlReadyInconsistentOntologyError:
            consistent = False

        assert consistent is False

    @pytest.mark.skipif(not HAS_OWLREADY2, reason="owlready2 not installed")
    def test_embedded_reasoning_inferences(self, tmp_data_dir):
        """Embedded reasoner should find SubClassOf inferences (e.g., Cat SubClassOf owl:Thing)."""
        simple = '''<?xml version="1.0"?>
<rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <owl:Ontology rdf:about="http://ex.org/t"/>
  <owl:Class rdf:about="http://ex.org/t#Animal"><rdfs:label>Animal</rdfs:label></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#Cat"><rdfs:label>Cat</rdfs:label><rdfs:subClassOf rdf:resource="http://ex.org/t#Animal"/></owl:Class>
</rdf:RDF>'''
        p = tmp_data_dir / "embed-inf" / "src" / "ontology"
        p.mkdir(parents=True, exist_ok=True)
        owl_path = p / "embed-inf.owl"
        owl_path.write_text(simple)

        import owlready2
        world = owlready2.World()
        onto = world.get_ontology(str(owl_path.resolve())).load()
        with onto:
            owlready2.sync_reasoner(world, infer_property_values=False)

        # Cat is subclass of Animal, so the reasoner should know this
        cat_cls = onto.search_one(iri="http://ex.org/t#Cat")
        animal_cls = onto.search_one(iri="http://ex.org/t#Animal")
        assert cat_cls is not None
        assert animal_cls is not None
        # Cat's ancestors should include Animal
        assert animal_cls in cat_cls.ancestors()

    @pytest.mark.asyncio
    async def test_reasoning_endpoint_with_engine(self, admin_client, tmp_data_dir):
        """POST /{board_id}/run should accept a reasoner parameter."""
        await admin_client.post("/api/boards/embed-eng")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "embed-eng")

        resp = await admin_client.post(
            "/api/reasoning/embed-eng/run",
            json={"reasoner": "ELK"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["reasoner"] == "ELK"
        assert "inferences" in body
        assert "errors" in body

    @pytest.mark.asyncio
    async def test_consistency_check_endpoint(self, admin_client, tmp_data_dir):
        """POST /{board_id}/run acts as a consistency check endpoint."""
        await admin_client.post("/api/boards/embed-cc")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "embed-cc")

        resp = await admin_client.post(
            "/api/reasoning/embed-cc/run",
            json={"reasoner": "HermiT"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert "consistent" in body
        assert "reasoner" in body
        assert body["reasoner"] == "HermiT"


# =========================================================================
# 5. Integration Tests
# =========================================================================


class TestIntegration:
    """Multi-step workflow tests combining Tier 2 features."""

    @pytest.mark.asyncio
    async def test_explain_after_reasoning_failure(self, admin_client, tmp_data_dir):
        """Run reasoning (expect graceful failure without Docker), then call explain."""
        await admin_client.post("/api/boards/integ-expl")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "integ-expl")

        # Step 1: Run reasoning
        resp1 = await admin_client.post(
            "/api/reasoning/integ-expl/run",
            json={"reasoner": "ELK"},
        )
        assert resp1.status_code == 200
        result1 = resp1.json()
        assert "errors" in result1

        # Step 2: Call explain
        resp2 = await admin_client.post(
            "/api/reasoning/integ-expl/explain",
            json={"reasoner": "ELK"},
        )
        assert resp2.status_code == 200
        result2 = resp2.json()
        assert "explanation_text" in result2

    @pytest.mark.asyncio
    async def test_import_download_and_resolve(self, tmp_data_dir):
        """Mock HTTP download of an import and verify resolution."""
        _setup(tmp_data_dir, "integ-dl")
        from app.services.imports import add_import, list_imports

        # Add a new import
        ok = add_import(
            tmp_data_dir / "integ-dl",
            "http://example.org/downloaded.owl",
            "downloaded",
        )
        assert ok

        # Verify import is listed
        imports = list_imports(tmp_data_dir / "integ-dl")
        assert any("downloaded.owl" in i["iri"] for i in imports)

        # Simulate download by creating the mirror file
        imports_dir = tmp_data_dir / "integ-dl" / "imports"
        imports_dir.mkdir(parents=True, exist_ok=True)
        (imports_dir / "downloaded.owl").write_text(
            '<?xml version="1.0"?>'
            '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"'
            ' xmlns:owl="http://www.w3.org/2002/07/owl#">'
            '<owl:Ontology rdf:about="http://example.org/downloaded"/>'
            '</rdf:RDF>'
        )

        # Re-check: the import should now be mirrored
        imports_after = list_imports(tmp_data_dir / "integ-dl")
        downloaded = [i for i in imports_after if "downloaded.owl" in i["iri"]]
        assert len(downloaded) >= 1
        assert downloaded[0]["mirrored"] is True

    @pytest.mark.asyncio
    async def test_full_reasoning_pipeline(self, admin_client, tmp_data_dir):
        """Load ontology -> run reasoning -> get explanation -> apply fix."""
        await admin_client.post("/api/boards/integ-pipe")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "integ-pipe")

        # Step 1: Run reasoning
        resp1 = await admin_client.post(
            "/api/reasoning/integ-pipe/run",
            json={"reasoner": "ELK"},
        )
        assert resp1.status_code == 200

        # Step 2: Get explanation
        resp2 = await admin_client.post(
            "/api/reasoning/integ-pipe/explain",
            json={"reasoner": "ELK"},
        )
        assert resp2.status_code == 200
        assert "explanation_text" in resp2.json()

        # Step 3: Apply a fix (remove SubClassOf from Cat)
        resp3 = await admin_client.post(
            "/api/reasoning/integ-pipe/apply-fix",
            json={
                "action": "remove_axiom",
                "target_entity": "http://ex.org/t#Cat",
                "target_axiom": "SubClassOf",
            },
        )
        assert resp3.status_code == 200
        assert resp3.json()["success"] is True

        # Step 4: Verify the axiom was removed
        g = _reload_graph(tmp_data_dir, "integ-pipe")
        cat = URIRef(f"{NS}Cat")
        subclass_triples = list(g.triples((cat, RDFS.subClassOf, None)))
        assert len(subclass_triples) == 0

    @pytest.mark.asyncio
    async def test_swrl_with_reasoning(self, admin_client, tmp_data_dir):
        """Add a SWRL rule, then run reasoning, then verify both exist."""
        await admin_client.post("/api/boards/integ-swrl")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "integ-swrl")

        # Step 1: Add a SWRL rule
        resp1 = await admin_client.post(
            "/api/dlquery/integ-swrl/swrl",
            json={"rule": "Animal(?x) ^ hasAge(?x, ?a) -> Cat(?x)"},
        )
        assert resp1.status_code == 201

        # Step 2: Verify rule is persisted
        resp2 = await admin_client.get("/api/dlquery/integ-swrl/swrl")
        assert resp2.status_code == 200
        rules = resp2.json()
        assert len(rules) == 1
        assert "Animal(?x)" in rules[0]["label"]

        # Step 3: Run reasoning (should not break despite SWRL comment)
        resp3 = await admin_client.post(
            "/api/reasoning/integ-swrl/run",
            json={"reasoner": "ELK"},
        )
        assert resp3.status_code == 200
        result3 = resp3.json()
        assert "reasoner" in result3
        assert isinstance(result3["inferences"], list)
        assert isinstance(result3["errors"], list)

        # Step 4: SWRL rule should still be present after reasoning
        resp4 = await admin_client.get("/api/dlquery/integ-swrl/swrl")
        assert resp4.status_code == 200
        assert len(resp4.json()) == 1
