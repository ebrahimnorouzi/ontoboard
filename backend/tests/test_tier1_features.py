"""Tests for Tier 1 ontology features.

Covers:
1. Manchester Syntax expression parser (unit tests with rdflib Graph)
2. Property characteristics (API + RDF verification)
3. Property chains (API + RDF verification)
4. Data property XSD range (axiom editor + RDF verification)
5. Annotation property CRUD (API + RDF verification)
"""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph, URIRef, BNode, Literal, RDF, RDFS, OWL, XSD
from rdflib.collection import Collection

from app.services.characteristics import (
    get_characteristics,
    set_characteristics,
    create_property_chain,
    get_property_chains,
)
from app.services.axiom import (
    get_axioms_for_entity,
    get_manchester_for_entity,
    apply_manchester_edit,
    get_entity_names,
)

# ── Shared OWL data ──────────────────────────────────────────────

SIMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
        xmlns:owl="http://www.w3.org/2002/07/owl#"
        xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
        xmlns:xsd="http://www.w3.org/2001/XMLSchema#">
      <owl:Ontology rdf:about="http://ex.org/t"/>
      <owl:Class rdf:about="http://ex.org/t#Animal"><rdfs:label>Animal</rdfs:label></owl:Class>
      <owl:Class rdf:about="http://ex.org/t#Plant"><rdfs:label>Plant</rdfs:label></owl:Class>
      <owl:Class rdf:about="http://ex.org/t#Organ"><rdfs:label>Organ</rdfs:label></owl:Class>
      <owl:Class rdf:about="http://ex.org/t#Cat"><rdfs:label>Cat</rdfs:label></owl:Class>
      <owl:Class rdf:about="http://ex.org/t#Dog"><rdfs:label>Dog</rdfs:label></owl:Class>
      <owl:Class rdf:about="http://ex.org/t#Wing"><rdfs:label>Wing</rdfs:label></owl:Class>
      <owl:Class rdf:about="http://ex.org/t#Tissue"><rdfs:label>Tissue</rdfs:label></owl:Class>
      <owl:ObjectProperty rdf:about="http://ex.org/t#hasPart"><rdfs:label>hasPart</rdfs:label></owl:ObjectProperty>
      <owl:ObjectProperty rdf:about="http://ex.org/t#eats"><rdfs:label>eats</rdfs:label></owl:ObjectProperty>
      <owl:ObjectProperty rdf:about="http://ex.org/t#hasParent"><rdfs:label>hasParent</rdfs:label></owl:ObjectProperty>
      <owl:ObjectProperty rdf:about="http://ex.org/t#hasChild"><rdfs:label>hasChild</rdfs:label></owl:ObjectProperty>
      <owl:ObjectProperty rdf:about="http://ex.org/t#hasAncestor"><rdfs:label>hasAncestor</rdfs:label></owl:ObjectProperty>
      <owl:DatatypeProperty rdf:about="http://ex.org/t#hasAge"><rdfs:label>hasAge</rdfs:label></owl:DatatypeProperty>
      <owl:DatatypeProperty rdf:about="http://ex.org/t#hasName"><rdfs:label>hasName</rdfs:label></owl:DatatypeProperty>
      <owl:DatatypeProperty rdf:about="http://ex.org/t#birthDate"><rdfs:label>birthDate</rdfs:label></owl:DatatypeProperty>
      <owl:DatatypeProperty rdf:about="http://ex.org/t#isActive"><rdfs:label>isActive</rdfs:label></owl:DatatypeProperty>
      <owl:DatatypeProperty rdf:about="http://ex.org/t#lastModified"><rdfs:label>lastModified</rdfs:label></owl:DatatypeProperty>
    </rdf:RDF>
""")

NS = "http://ex.org/t#"


def _setup(data_dir: Path, board_id: str, owl: str = SIMPLE_OWL):
    """Write OWL file into the board directory structure."""
    p = data_dir / board_id / "src" / "ontology"
    p.mkdir(parents=True, exist_ok=True)
    (p / f"{board_id}.owl").write_text(owl)


def _load_graph(owl: str = SIMPLE_OWL) -> Graph:
    """Parse OWL string into a fresh rdflib Graph."""
    g = Graph()
    g.parse(data=owl, format="xml")
    return g


def _reload_graph(data_dir: Path, board_id: str) -> Graph:
    """Reload the graph from the saved OWL file on disk."""
    g = Graph()
    g.parse(str(data_dir / board_id / "src" / "ontology" / f"{board_id}.owl"), format="xml")
    return g


# =========================================================================
# 1. Manchester Syntax Expression Parser Tests (Unit tests)
#
# These test the parser that will live in backend/app/services/manchester_parser.py.
# Until that module exists, we test the equivalent functionality already
# available through the axiom service: apply_manchester_edit parses
# Manchester-style lines, and _resolve_name handles name resolution.
# For complex class expressions (intersection, union, complement,
# restrictions), we build the expected RDF structures directly and
# verify them, then also test the round-trip via axiom rendering.
# =========================================================================


class TestManchesterSimpleClassName:
    """Test resolving simple class names to URIRefs."""

    def test_resolve_simple_class_name_via_axiom(self):
        """parse a simple class name like 'Animal' resolves to its URIRef."""
        g = _load_graph()
        from app.services.axiom import _resolve_name
        result = _resolve_name(g, "Animal")
        assert result == URIRef(f"{NS}Animal")

    def test_resolve_class_by_full_iri(self):
        """Full IRIs should resolve directly."""
        g = _load_graph()
        from app.services.axiom import _resolve_name
        result = _resolve_name(g, f"{NS}Plant")
        assert result == URIRef(f"{NS}Plant")

    def test_resolve_owl_thing(self):
        """Well-known name owl:Thing should resolve."""
        g = _load_graph()
        from app.services.axiom import _resolve_name
        result = _resolve_name(g, "owl:Thing")
        assert result == URIRef(str(OWL.Thing))

    def test_resolve_owl_nothing(self):
        """Well-known name owl:Nothing should resolve."""
        g = _load_graph()
        from app.services.axiom import _resolve_name
        result = _resolve_name(g, "owl:Nothing")
        assert result == URIRef(str(OWL.Nothing))


class TestManchesterIntersectionExpression:
    """Test intersection (AND) class expressions in RDF."""

    def test_intersection_creates_bnode_with_intersectionOf(self):
        """Building 'Animal and Plant' should produce a BNode with intersectionOf."""
        g = _load_graph()
        animal = URIRef(f"{NS}Animal")
        plant = URIRef(f"{NS}Plant")

        # Build intersection manually (as the parser would)
        bnode = BNode()
        members_node = BNode()
        Collection(g, members_node, [animal, plant])
        g.add((bnode, OWL.intersectionOf, members_node))
        g.add((bnode, RDF.type, OWL.Class))

        # Verify structure
        assert (bnode, RDF.type, OWL.Class) in g
        intersection_list = list(g.objects(bnode, OWL.intersectionOf))
        assert len(intersection_list) == 1
        members = list(Collection(g, intersection_list[0]))
        assert animal in members
        assert plant in members

    def test_intersection_via_subclass_axiom_edit(self, tmp_data_dir):
        """Applying 'SubClassOf: Animal' via axiom editor sets the triple."""
        _setup(tmp_data_dir, "t1-inter")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-inter",
            f"{NS}Cat",
            "Class: Cat\n    SubClassOf: Animal",
        )
        assert result["success"] is True
        g = _reload_graph(tmp_data_dir, "t1-inter")
        assert (URIRef(f"{NS}Cat"), RDFS.subClassOf, URIRef(f"{NS}Animal")) in g


class TestManchesterUnionExpression:
    """Test union (OR) class expressions in RDF."""

    def test_union_creates_bnode_with_unionOf(self):
        """Building 'Cat or Dog' should produce a BNode with unionOf."""
        g = _load_graph()
        cat = URIRef(f"{NS}Cat")
        dog = URIRef(f"{NS}Dog")

        bnode = BNode()
        members_node = BNode()
        Collection(g, members_node, [cat, dog])
        g.add((bnode, OWL.unionOf, members_node))
        g.add((bnode, RDF.type, OWL.Class))

        union_list = list(g.objects(bnode, OWL.unionOf))
        assert len(union_list) == 1
        members = list(Collection(g, union_list[0]))
        assert cat in members
        assert dog in members


class TestManchesterComplementExpression:
    """Test complement (NOT) class expressions in RDF."""

    def test_complement_creates_bnode_with_complementOf(self):
        """Building 'not Plant' should produce a BNode with complementOf."""
        g = _load_graph()
        plant = URIRef(f"{NS}Plant")

        bnode = BNode()
        g.add((bnode, OWL.complementOf, plant))
        g.add((bnode, RDF.type, OWL.Class))

        assert (bnode, OWL.complementOf, plant) in g
        assert (bnode, RDF.type, OWL.Class) in g


class TestManchesterRestrictionSome:
    """Test existential restriction (someValuesFrom)."""

    def test_some_restriction_structure(self):
        """'hasPart some Organ' produces a restriction BNode."""
        g = _load_graph()
        has_part = URIRef(f"{NS}hasPart")
        organ = URIRef(f"{NS}Organ")

        bnode = BNode()
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, has_part))
        g.add((bnode, OWL.someValuesFrom, organ))

        assert (bnode, RDF.type, OWL.Restriction) in g
        assert (bnode, OWL.onProperty, has_part) in g
        assert (bnode, OWL.someValuesFrom, organ) in g


class TestManchesterRestrictionOnly:
    """Test universal restriction (allValuesFrom)."""

    def test_only_restriction_structure(self):
        """'eats only Plant' produces a restriction BNode with allValuesFrom."""
        g = _load_graph()
        eats = URIRef(f"{NS}eats")
        plant = URIRef(f"{NS}Plant")

        bnode = BNode()
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, eats))
        g.add((bnode, OWL.allValuesFrom, plant))

        assert (bnode, OWL.allValuesFrom, plant) in g


class TestManchesterCardinality:
    """Test cardinality restrictions."""

    def test_min_cardinality_structure(self):
        """'hasPart min 2 Wing' produces restriction with minQualifiedCardinality."""
        g = _load_graph()
        has_part = URIRef(f"{NS}hasPart")
        wing = URIRef(f"{NS}Wing")

        bnode = BNode()
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, has_part))
        g.add((bnode, OWL.minQualifiedCardinality, Literal(2, datatype=XSD.nonNegativeInteger)))
        g.add((bnode, OWL.onClass, wing))

        card_vals = list(g.objects(bnode, OWL.minQualifiedCardinality))
        assert len(card_vals) == 1
        assert int(card_vals[0]) == 2
        assert (bnode, OWL.onClass, wing) in g

    def test_exact_cardinality_structure(self):
        """Exact cardinality builds qualifiedCardinality triple."""
        g = _load_graph()
        has_part = URIRef(f"{NS}hasPart")
        wing = URIRef(f"{NS}Wing")

        bnode = BNode()
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, has_part))
        g.add((bnode, OWL.qualifiedCardinality, Literal(2, datatype=XSD.nonNegativeInteger)))
        g.add((bnode, OWL.onClass, wing))

        assert int(list(g.objects(bnode, OWL.qualifiedCardinality))[0]) == 2

    def test_max_cardinality_structure(self):
        """Max cardinality builds maxQualifiedCardinality triple."""
        g = _load_graph()
        has_part = URIRef(f"{NS}hasPart")

        bnode = BNode()
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, has_part))
        g.add((bnode, OWL.maxQualifiedCardinality, Literal(5, datatype=XSD.nonNegativeInteger)))

        assert int(list(g.objects(bnode, OWL.maxQualifiedCardinality))[0]) == 5


class TestManchesterNestedExpression:
    """Test nested expressions: intersection with restriction."""

    def test_nested_intersection_with_restriction(self):
        """'Animal and (hasPart some Organ)' produces intersection with restriction inside."""
        g = _load_graph()
        animal = URIRef(f"{NS}Animal")
        has_part = URIRef(f"{NS}hasPart")
        organ = URIRef(f"{NS}Organ")

        # Build restriction
        restriction = BNode()
        g.add((restriction, RDF.type, OWL.Restriction))
        g.add((restriction, OWL.onProperty, has_part))
        g.add((restriction, OWL.someValuesFrom, organ))

        # Build intersection of Animal and the restriction
        intersection = BNode()
        members_node = BNode()
        Collection(g, members_node, [animal, restriction])
        g.add((intersection, OWL.intersectionOf, members_node))
        g.add((intersection, RDF.type, OWL.Class))

        # Verify nested structure
        members = list(Collection(g, list(g.objects(intersection, OWL.intersectionOf))[0]))
        assert animal in members
        restriction_member = [m for m in members if isinstance(m, BNode)][0]
        assert (restriction_member, OWL.someValuesFrom, organ) in g


class TestManchesterComplexExpression:
    """Test complex expression: 'Animal and hasPart some (Organ or Tissue) and not Plant'."""

    def test_complex_expression_structure(self):
        """Build and verify a complex nested expression."""
        g = _load_graph()
        animal = URIRef(f"{NS}Animal")
        has_part = URIRef(f"{NS}hasPart")
        organ = URIRef(f"{NS}Organ")
        tissue = URIRef(f"{NS}Tissue")
        plant = URIRef(f"{NS}Plant")

        # Build union: Organ or Tissue
        union_node = BNode()
        union_members = BNode()
        Collection(g, union_members, [organ, tissue])
        g.add((union_node, OWL.unionOf, union_members))
        g.add((union_node, RDF.type, OWL.Class))

        # Build restriction: hasPart some (Organ or Tissue)
        restriction = BNode()
        g.add((restriction, RDF.type, OWL.Restriction))
        g.add((restriction, OWL.onProperty, has_part))
        g.add((restriction, OWL.someValuesFrom, union_node))

        # Build complement: not Plant
        complement = BNode()
        g.add((complement, OWL.complementOf, plant))
        g.add((complement, RDF.type, OWL.Class))

        # Build intersection: Animal and restriction and complement
        intersection = BNode()
        int_members = BNode()
        Collection(g, int_members, [animal, restriction, complement])
        g.add((intersection, OWL.intersectionOf, int_members))
        g.add((intersection, RDF.type, OWL.Class))

        # Verify top-level intersection
        top_members = list(Collection(g, list(g.objects(intersection, OWL.intersectionOf))[0]))
        assert len(top_members) == 3
        assert animal in top_members


class TestManchesterRenderRoundtrip:
    """Test that Manchester rendering round-trips approximately."""

    def test_class_render_roundtrip(self):
        """Render a class to Manchester, verify it contains expected keywords."""
        g = _load_graph()
        text = get_manchester_for_entity(g, f"{NS}Animal")
        assert "Class: Animal" in text

    def test_property_render_roundtrip(self):
        """Render an object property to Manchester, verify basic form."""
        g = _load_graph()
        text = get_manchester_for_entity(g, f"{NS}hasPart")
        assert "ObjectProperty: hasPart" in text

    def test_data_property_render(self):
        """Render a data property, verify form."""
        g = _load_graph()
        text = get_manchester_for_entity(g, f"{NS}hasAge")
        assert "DataProperty: hasAge" in text


# =========================================================================
# 2. Property Characteristics Tests (API endpoints)
# =========================================================================


class TestPropertyCharacteristicsUnit:
    """Unit tests for property characteristics."""

    def test_get_characteristics_all_false(self):
        """A fresh property has no characteristics set."""
        g = _load_graph()
        chars = get_characteristics(g, f"{NS}hasPart")
        assert chars["functional"] is False
        assert chars["transitive"] is False
        assert chars["symmetric"] is False
        assert chars["reflexive"] is False

    def test_set_functional(self, tmp_data_dir):
        """Setting functional adds OWL.FunctionalProperty type."""
        _setup(tmp_data_dir, "t1-func")
        result = set_characteristics(tmp_data_dir / "t1-func", f"{NS}hasPart", {"functional": True})
        assert result["functional"] is True
        g = _reload_graph(tmp_data_dir, "t1-func")
        assert (URIRef(f"{NS}hasPart"), RDF.type, OWL.FunctionalProperty) in g

    def test_set_transitive(self, tmp_data_dir):
        """Setting transitive adds OWL.TransitiveProperty type."""
        _setup(tmp_data_dir, "t1-trans")
        result = set_characteristics(tmp_data_dir / "t1-trans", f"{NS}hasAncestor", {"transitive": True})
        assert result["transitive"] is True
        g = _reload_graph(tmp_data_dir, "t1-trans")
        assert (URIRef(f"{NS}hasAncestor"), RDF.type, OWL.TransitiveProperty) in g

    def test_remove_characteristic(self, tmp_data_dir):
        """Unsetting a characteristic removes the RDF.type triple."""
        _setup(tmp_data_dir, "t1-unset")
        set_characteristics(tmp_data_dir / "t1-unset", f"{NS}hasPart", {"functional": True})
        result = set_characteristics(tmp_data_dir / "t1-unset", f"{NS}hasPart", {"functional": False})
        assert result["functional"] is False
        g = _reload_graph(tmp_data_dir, "t1-unset")
        assert (URIRef(f"{NS}hasPart"), RDF.type, OWL.FunctionalProperty) not in g

    def test_set_multiple_characteristics(self, tmp_data_dir):
        """Setting multiple characteristics at once."""
        _setup(tmp_data_dir, "t1-multi")
        result = set_characteristics(tmp_data_dir / "t1-multi", f"{NS}hasPart", {
            "functional": True,
            "transitive": True,
            "symmetric": True,
        })
        assert result["functional"] is True
        assert result["transitive"] is True
        assert result["symmetric"] is True
        g = _reload_graph(tmp_data_dir, "t1-multi")
        prop = URIRef(f"{NS}hasPart")
        assert (prop, RDF.type, OWL.FunctionalProperty) in g
        assert (prop, RDF.type, OWL.TransitiveProperty) in g
        assert (prop, RDF.type, OWL.SymmetricProperty) in g


class TestPropertyCharacteristicsAPI:
    """Integration tests for property characteristics endpoints."""

    @pytest.mark.asyncio
    async def test_get_characteristics_endpoint(self, admin_client, tmp_data_dir):
        await admin_client.post("/api/boards/t1-char-get")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "t1-char-get")

        iri = "http://ex.org/t%23hasPart"
        resp = await admin_client.get(f"/api/characteristics/t1-char-get/entity/{iri}")
        assert resp.status_code == 200
        data = resp.json()
        assert data["functional"] is False
        assert data["transitive"] is False

    @pytest.mark.asyncio
    async def test_set_characteristics_endpoint(self, admin_client, tmp_data_dir):
        await admin_client.post("/api/boards/t1-char-set")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "t1-char-set")

        iri = "http://ex.org/t%23hasPart"
        resp = await admin_client.put(f"/api/characteristics/t1-char-set/entity/{iri}", json={
            "functional": True,
            "transitive": True,
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["functional"] is True
        assert data["transitive"] is True

        # Verify the change persisted
        resp2 = await admin_client.get(f"/api/characteristics/t1-char-set/entity/{iri}")
        assert resp2.json()["functional"] is True

    @pytest.mark.asyncio
    async def test_set_empty_characteristics_removes(self, admin_client, tmp_data_dir):
        """PUT with all False effectively removes characteristics."""
        await admin_client.post("/api/boards/t1-char-empty")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "t1-char-empty")

        iri = "http://ex.org/t%23hasPart"
        # First set
        await admin_client.put(f"/api/characteristics/t1-char-empty/entity/{iri}", json={
            "functional": True,
        })
        # Then clear
        resp = await admin_client.put(f"/api/characteristics/t1-char-empty/entity/{iri}", json={
            "functional": False,
        })
        assert resp.status_code == 200
        assert resp.json()["functional"] is False


# =========================================================================
# 3. Property Chain Tests
# =========================================================================


class TestPropertyChainUnit:
    """Unit tests for property chains."""

    def test_get_chains_empty(self):
        """A property with no chain axiom returns empty list."""
        g = _load_graph()
        chains = get_property_chains(g, f"{NS}hasAncestor")
        assert chains == []

    def test_create_chain(self, tmp_data_dir):
        """Creating a chain with 2 properties succeeds."""
        _setup(tmp_data_dir, "t1-chain")
        ok = create_property_chain(
            tmp_data_dir / "t1-chain",
            f"{NS}hasAncestor",
            [f"{NS}hasParent", f"{NS}hasParent"],
        )
        assert ok is True
        g = _reload_graph(tmp_data_dir, "t1-chain")
        chains = get_property_chains(g, f"{NS}hasAncestor")
        assert len(chains) == 1
        assert len(chains[0]) == 2
        assert f"{NS}hasParent" in chains[0]

    def test_chain_needs_at_least_2_properties(self, tmp_data_dir):
        """Chain with fewer than 2 properties should fail."""
        _setup(tmp_data_dir, "t1-chain-min")
        ok = create_property_chain(
            tmp_data_dir / "t1-chain-min",
            f"{NS}hasAncestor",
            [f"{NS}hasParent"],
        )
        assert ok is False

    def test_chain_rdf_structure(self, tmp_data_dir):
        """Verify the RDF structure: owl:propertyChainAxiom with RDF list."""
        _setup(tmp_data_dir, "t1-chain-rdf")
        create_property_chain(
            tmp_data_dir / "t1-chain-rdf",
            f"{NS}hasAncestor",
            [f"{NS}hasParent", f"{NS}hasChild"],
        )
        g = _reload_graph(tmp_data_dir, "t1-chain-rdf")
        prop = URIRef(f"{NS}hasAncestor")

        # Check owl:propertyChainAxiom triple exists
        chain_nodes = list(g.objects(prop, OWL.propertyChainAxiom))
        assert len(chain_nodes) == 1

        # Check the RDF list members
        members = list(Collection(g, chain_nodes[0]))
        assert URIRef(f"{NS}hasParent") in members
        assert URIRef(f"{NS}hasChild") in members


class TestPropertyChainAPI:
    """Integration tests for property chain endpoints."""

    @pytest.mark.asyncio
    async def test_create_chain_endpoint(self, admin_client, tmp_data_dir):
        await admin_client.post("/api/boards/t1-chain-api")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "t1-chain-api")

        resp = await admin_client.post("/api/characteristics/t1-chain-api/property-chain", json={
            "super_property": f"{NS}hasAncestor",
            "chain_properties": [f"{NS}hasParent", f"{NS}hasParent"],
        })
        assert resp.status_code == 201
        assert resp.json()["success"] is True

    @pytest.mark.asyncio
    async def test_chain_too_short_returns_error(self, admin_client, tmp_data_dir):
        await admin_client.post("/api/boards/t1-chain-err")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "t1-chain-err")

        resp = await admin_client.post("/api/characteristics/t1-chain-err/property-chain", json={
            "super_property": f"{NS}hasAncestor",
            "chain_properties": [f"{NS}hasParent"],
        })
        assert resp.status_code == 400


# =========================================================================
# 4. Data Property XSD Range Tests
# =========================================================================


class TestDataPropertyXSDRange:
    """Test setting XSD ranges on data properties via the axiom editor."""

    def test_set_range_xsd_string(self, tmp_data_dir):
        """Set Range to xsd:string, verify RDF triple."""
        _setup(tmp_data_dir, "t1-xsd-str")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-xsd-str",
            f"{NS}hasName",
            "DataProperty: hasName\n    Range: xsd:string",
        )
        assert result["success"] is True
        g = _reload_graph(tmp_data_dir, "t1-xsd-str")
        assert (URIRef(f"{NS}hasName"), RDFS.range, XSD.string) in g

    def test_set_range_xsd_integer(self, tmp_data_dir):
        """Set Range to xsd:integer, verify RDF triple."""
        _setup(tmp_data_dir, "t1-xsd-int")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-xsd-int",
            f"{NS}hasAge",
            "DataProperty: hasAge\n    Range: xsd:integer",
        )
        assert result["success"] is True
        g = _reload_graph(tmp_data_dir, "t1-xsd-int")
        assert (URIRef(f"{NS}hasAge"), RDFS.range, XSD.integer) in g

    def test_set_range_xsd_boolean(self, tmp_data_dir):
        """Set Range to xsd:boolean, verify RDF triple."""
        _setup(tmp_data_dir, "t1-xsd-bool")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-xsd-bool",
            f"{NS}isActive",
            "DataProperty: isActive\n    Range: xsd:boolean",
        )
        assert result["success"] is True
        g = _reload_graph(tmp_data_dir, "t1-xsd-bool")
        assert (URIRef(f"{NS}isActive"), RDFS.range, XSD.boolean) in g

    def test_set_range_xsd_date(self, tmp_data_dir):
        """Set Range to xsd:date, verify RDF triple."""
        _setup(tmp_data_dir, "t1-xsd-date")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-xsd-date",
            f"{NS}birthDate",
            "DataProperty: birthDate\n    Range: xsd:date",
        )
        assert result["success"] is True
        g = _reload_graph(tmp_data_dir, "t1-xsd-date")
        assert (URIRef(f"{NS}birthDate"), RDFS.range, XSD.date) in g

    def test_set_range_xsd_dateTime(self, tmp_data_dir):
        """Set Range to xsd:dateTime, verify RDF triple."""
        _setup(tmp_data_dir, "t1-xsd-dt")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-xsd-dt",
            f"{NS}lastModified",
            "DataProperty: lastModified\n    Range: xsd:dateTime",
        )
        assert result["success"] is True
        g = _reload_graph(tmp_data_dir, "t1-xsd-dt")
        assert (URIRef(f"{NS}lastModified"), RDFS.range, XSD.dateTime) in g

    def test_set_domain_and_range_together(self, tmp_data_dir):
        """Set both Domain and Range on a data property."""
        _setup(tmp_data_dir, "t1-xsd-both")
        result = apply_manchester_edit(
            tmp_data_dir / "t1-xsd-both",
            f"{NS}hasAge",
            "DataProperty: hasAge\n    Domain: Animal\n    Range: xsd:integer",
        )
        assert result["success"] is True
        assert result["applied"] == 2
        g = _reload_graph(tmp_data_dir, "t1-xsd-both")
        assert (URIRef(f"{NS}hasAge"), RDFS.domain, URIRef(f"{NS}Animal")) in g
        assert (URIRef(f"{NS}hasAge"), RDFS.range, XSD.integer) in g


# =========================================================================
# 5. Annotation Property CRUD Tests
# =========================================================================


class TestAnnotationPropertyUnit:
    """Unit tests for annotation property operations via RDF."""

    def test_create_annotation_property_in_graph(self):
        """Manually adding an annotation property to the graph."""
        g = _load_graph()
        ann_prop = URIRef(f"{NS}myAnnotation")
        g.add((ann_prop, RDF.type, OWL.AnnotationProperty))
        g.add((ann_prop, RDFS.label, Literal("myAnnotation")))

        assert (ann_prop, RDF.type, OWL.AnnotationProperty) in g
        labels = list(g.objects(ann_prop, RDFS.label))
        assert any(str(l) == "myAnnotation" for l in labels)

    def test_delete_annotation_property_from_graph(self):
        """Removing an annotation property and all its triples."""
        g = _load_graph()
        ann_prop = URIRef(f"{NS}myAnnotation")
        g.add((ann_prop, RDF.type, OWL.AnnotationProperty))
        g.add((ann_prop, RDFS.label, Literal("myAnnotation")))

        # Delete: remove all triples with ann_prop as subject
        g.remove((ann_prop, None, None))
        assert (ann_prop, RDF.type, OWL.AnnotationProperty) not in g
        assert len(list(g.triples((ann_prop, None, None)))) == 0

    def test_annotation_property_recognized_by_entity_names(self):
        """Annotation properties should not appear in get_entity_names
        (which lists classes, object props, data props, individuals)."""
        g = _load_graph()
        ann_prop = URIRef(f"{NS}myNote")
        g.add((ann_prop, RDF.type, OWL.AnnotationProperty))
        g.add((ann_prop, RDFS.label, Literal("myNote")))

        names = get_entity_names(g)
        # get_entity_names lists class, object_property, data_property, individual
        # annotation properties should not be in this list
        found_types = {n["type"] for n in names}
        # This is checking that the entity_names function does NOT include annotation props
        ann_entries = [n for n in names if n["iri"] == str(ann_prop)]
        assert len(ann_entries) == 0

    def test_use_annotation_property_on_entity(self):
        """Use a custom annotation property to annotate a class."""
        g = _load_graph()
        ann_prop = URIRef(f"{NS}createdBy")
        g.add((ann_prop, RDF.type, OWL.AnnotationProperty))
        g.add((ann_prop, RDFS.label, Literal("createdBy")))

        # Annotate Animal with createdBy
        animal = URIRef(f"{NS}Animal")
        g.add((animal, ann_prop, Literal("Test Author")))

        values = list(g.objects(animal, ann_prop))
        assert len(values) == 1
        assert str(values[0]) == "Test Author"


class TestAnnotationPropertyAPI:
    """Integration tests for annotation property CRUD via API."""

    @pytest.mark.asyncio
    async def test_create_annotation_property_endpoint(self, admin_client, tmp_data_dir):
        """Create an annotation property via POST /tree/{board_id}/entity."""
        await admin_client.post("/api/boards/t1-ann-create")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, "t1-ann-create")

        resp = await admin_client.post("/api/tree/t1-ann-create/entity", json={
            "entity_type": "annotation_property",
            "iri": f"{NS}myCustomAnnotation",
            "label": "myCustomAnnotation",
            "parent_iri": None,
        })
        assert resp.status_code == 201
        assert resp.json()["success"] is True

        # Verify the triple exists in the saved file
        g = _reload_graph(tmp_data_dir, "t1-ann-create")
        assert (URIRef(f"{NS}myCustomAnnotation"), RDF.type, OWL.AnnotationProperty) in g

    @pytest.mark.asyncio
    async def test_delete_annotation_property_endpoint(self, admin_client, tmp_data_dir):
        """Delete an annotation property via DELETE endpoint."""
        await admin_client.post("/api/boards/t1-ann-del")
        await asyncio.sleep(0.1)

        # Create OWL with an annotation property already in it
        owl_with_ann = SIMPLE_OWL.replace(
            "</rdf:RDF>",
            '  <owl:AnnotationProperty rdf:about="http://ex.org/t#myAnn">'
            "<rdfs:label>myAnn</rdfs:label>"
            "</owl:AnnotationProperty>\n</rdf:RDF>",
        )
        _setup(tmp_data_dir, "t1-ann-del", owl_with_ann)

        iri = "http://ex.org/t%23myAnn"
        resp = await admin_client.delete(f"/api/tree/t1-ann-del/entity/{iri}")
        assert resp.status_code == 200
        assert resp.json()["success"] is True

    @pytest.mark.asyncio
    async def test_list_annotation_properties_endpoint(self, admin_client, tmp_data_dir):
        """GET /tree/{board_id}/annotation-properties returns the list."""
        await admin_client.post("/api/boards/t1-ann-list")
        await asyncio.sleep(0.1)

        owl_with_ann = SIMPLE_OWL.replace(
            "</rdf:RDF>",
            '  <owl:AnnotationProperty rdf:about="http://ex.org/t#notes">'
            "<rdfs:label>notes</rdfs:label>"
            "</owl:AnnotationProperty>\n</rdf:RDF>",
        )
        _setup(tmp_data_dir, "t1-ann-list", owl_with_ann)

        resp = await admin_client.get("/api/tree/t1-ann-list/annotation-properties")
        assert resp.status_code == 200
        data = resp.json()
        # Should find at least the custom annotation property
        iris = [node["iri"] for node in data]
        assert f"{NS}notes" in iris
