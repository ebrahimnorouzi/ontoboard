"""Comprehensive tests for OWL 2 features: HasSelf, ObjectOneOf, Datatype Facet
Restrictions, and native SWRL implementation.

Tests cover parsing, rendering, round-tripping, and integration with the axiom
editor and API endpoints.
"""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph, URIRef, BNode, Literal, RDF, RDFS, OWL, XSD, Namespace
from rdflib.collection import Collection

from app.services.manchester_parser import (
    parse_class_expression,
    render_class_expression,
    resolve_name,
    _make_rdf_list,
)
from app.services.swrl import (
    get_swrl_rules,
    add_swrl_rule,
    delete_swrl_rule,
    add_rule,
    list_rules,
    _parse_atom,
    _parse_atoms,
    _rule_to_dict,
    _stable_id,
)

SWRL = Namespace("http://www.w3.org/2003/11/swrl#")

NS = "http://ex.org/t#"

# ── OWL content for API integration tests ─────────────────────────

OWL2_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/t#"
         xml:base="http://ex.org/t"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
         xmlns:xsd="http://www.w3.org/2001/XMLSchema#">

        <owl:Ontology rdf:about="http://ex.org/t"/>

        <owl:Class rdf:about="http://ex.org/t#Animal">
            <rdfs:label>Animal</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Person">
            <rdfs:label>Person</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Adult">
            <rdfs:label>Adult</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Cat">
            <rdfs:label>Cat</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Dog">
            <rdfs:label>Dog</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Male">
            <rdfs:label>Male</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Female">
            <rdfs:label>Female</rdfs:label>
        </owl:Class>

        <owl:ObjectProperty rdf:about="http://ex.org/t#hasPart">
            <rdfs:label>hasPart</rdfs:label>
        </owl:ObjectProperty>
        <owl:ObjectProperty rdf:about="http://ex.org/t#likes">
            <rdfs:label>likes</rdfs:label>
        </owl:ObjectProperty>
        <owl:ObjectProperty rdf:about="http://ex.org/t#knows">
            <rdfs:label>knows</rdfs:label>
        </owl:ObjectProperty>
        <owl:ObjectProperty rdf:about="http://ex.org/t#hasChild">
            <rdfs:label>hasChild</rdfs:label>
        </owl:ObjectProperty>

        <owl:DatatypeProperty rdf:about="http://ex.org/t#hasAge">
            <rdfs:label>hasAge</rdfs:label>
        </owl:DatatypeProperty>
        <owl:DatatypeProperty rdf:about="http://ex.org/t#hasName">
            <rdfs:label>hasName</rdfs:label>
        </owl:DatatypeProperty>

        <owl:NamedIndividual rdf:about="http://ex.org/t#john">
            <rdf:type rdf:resource="http://ex.org/t#Person"/>
            <rdfs:label>john</rdfs:label>
        </owl:NamedIndividual>
        <owl:NamedIndividual rdf:about="http://ex.org/t#jane">
            <rdf:type rdf:resource="http://ex.org/t#Person"/>
            <rdfs:label>jane</rdfs:label>
        </owl:NamedIndividual>
        <owl:NamedIndividual rdf:about="http://ex.org/t#bob">
            <rdf:type rdf:resource="http://ex.org/t#Person"/>
            <rdfs:label>bob</rdfs:label>
        </owl:NamedIndividual>
    </rdf:RDF>
""")


def _make_graph():
    """Create a test graph with sample entities."""
    g = Graph()
    g.bind("owl", OWL); g.bind("rdfs", RDFS); g.bind("xsd", XSD)
    ns = NS
    # Classes
    for name in ["Animal", "Person", "Adult", "Cat", "Dog", "Male", "Female"]:
        g.add((URIRef(ns + name), RDF.type, OWL.Class))
        g.add((URIRef(ns + name), RDFS.label, Literal(name)))
    # Object properties
    for name in ["hasPart", "likes", "knows", "hasChild"]:
        g.add((URIRef(ns + name), RDF.type, OWL.ObjectProperty))
        g.add((URIRef(ns + name), RDFS.label, Literal(name)))
    # Data properties
    for name in ["hasAge", "hasName"]:
        g.add((URIRef(ns + name), RDF.type, OWL.DatatypeProperty))
        g.add((URIRef(ns + name), RDFS.label, Literal(name)))
    # Individuals
    for name in ["john", "jane", "bob"]:
        g.add((URIRef(ns + name), RDF.type, OWL.NamedIndividual))
        g.add((URIRef(ns + name), RDFS.label, Literal(name)))
    return g


def _setup(data_dir: Path, board_id: str, owl: str = OWL2_OWL):
    """Write an OWL file into the standard board directory layout."""
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ═══════════════════════════════════════════════════════════════════
# HasSelf (5 tests)
# ═══════════════════════════════════════════════════════════════════


def _build_has_self(g, prop_uri):
    """Manually build a HasSelf restriction BNode in the graph."""
    bnode = BNode()
    g.add((bnode, RDF.type, OWL.Restriction))
    g.add((bnode, OWL.onProperty, prop_uri))
    g.add((bnode, OWL.hasSelf, Literal(True, datatype=XSD.boolean)))
    return bnode


class TestHasSelf:
    """Tests for owl:hasSelf (reflexive local restriction)."""

    def test_parse_has_self(self):
        """Parsing 'likes Self' produces a BNode with owl:hasSelf true."""
        g = _make_graph()
        node = parse_class_expression("likes Self", g)
        assert isinstance(node, BNode)
        # Must be typed as owl:Restriction
        assert (node, RDF.type, OWL.Restriction) in g
        # Must have owl:onProperty pointing to likes
        prop = None
        for o in g.objects(node, OWL.onProperty):
            prop = o
        assert prop is not None
        assert str(prop).endswith("likes")
        # Must have owl:hasSelf true
        has_self_val = None
        for o in g.objects(node, OWL.hasSelf):
            has_self_val = o
        assert has_self_val is not None
        assert has_self_val.toPython() is True

    def test_render_has_self(self):
        """Rendering a hasSelf restriction produces 'likes Self'."""
        g = _make_graph()
        likes = URIRef(NS + "likes")
        bnode = _build_has_self(g, likes)
        rendered = render_class_expression(g, bnode)
        assert "likes" in rendered
        assert "Self" in rendered

    def test_has_self_roundtrip(self):
        """parse -> render -> result matches original expression."""
        g = _make_graph()
        node = parse_class_expression("likes Self", g)
        rendered = render_class_expression(g, node)
        # Normalise whitespace for comparison
        assert rendered.strip() == "likes Self"

    def test_has_self_in_intersection(self):
        """'Animal and likes Self' produces intersection with hasSelf."""
        g = _make_graph()
        node = parse_class_expression("Animal and likes Self", g)
        assert isinstance(node, BNode)
        # Should have owl:intersectionOf
        list_head = None
        for o in g.objects(node, OWL.intersectionOf):
            list_head = o
        assert list_head is not None
        # Collect list items
        items = []
        current = list_head
        while current and current != RDF.nil:
            first = None
            for o in g.objects(current, RDF.first):
                first = o
            if first is not None:
                items.append(first)
            rest = None
            for o in g.objects(current, RDF.rest):
                rest = o
            current = rest
        assert len(items) == 2
        # One item is Animal (URIRef), one is a BNode (hasSelf restriction)
        uris = [i for i in items if isinstance(i, URIRef)]
        bnodes = [i for i in items if isinstance(i, BNode)]
        assert len(uris) == 1
        assert str(uris[0]).endswith("Animal")
        assert len(bnodes) == 1
        # The BNode should be a hasSelf restriction
        assert (bnodes[0], RDF.type, OWL.Restriction) in g
        has_self_found = False
        for o in g.objects(bnodes[0], OWL.hasSelf):
            has_self_found = True
        assert has_self_found

    def test_has_self_in_subclassof(self):
        """HasSelf restriction can be used in SubClassOf axiom context."""
        g = _make_graph()
        animal = URIRef(NS + "Animal")
        # Parse the restriction and add it as a SubClassOf
        node = parse_class_expression("likes Self", g)
        g.add((animal, RDFS.subClassOf, node))
        # Verify it was added
        subclass_objects = list(g.objects(animal, RDFS.subClassOf))
        assert len(subclass_objects) >= 1
        # Find the hasSelf restriction among subclass targets
        has_self_target = None
        for sc in subclass_objects:
            if isinstance(sc, BNode) and (sc, OWL.hasSelf, None) in g:
                has_self_target = sc
        assert has_self_target is not None


# ═══════════════════════════════════════════════════════════════════
# ObjectOneOf (5 tests)
# ═══════════════════════════════════════════════════════════════════


def _build_one_of(g, individuals):
    """Manually build an owl:oneOf BNode in the graph."""
    bnode = BNode()
    head = _make_rdf_list(g, [URIRef(i) if isinstance(i, str) else i for i in individuals])
    g.add((bnode, OWL.oneOf, head))
    return bnode


def _collect_rdf_list(g, head):
    """Collect items from an RDF list."""
    items = []
    current = head
    while current and current != RDF.nil:
        first = None
        for o in g.objects(current, RDF.first):
            first = o
        if first is not None:
            items.append(first)
        rest = None
        for o in g.objects(current, RDF.rest):
            rest = o
        current = rest
    return items


class TestObjectOneOf:
    """Tests for owl:oneOf (enumerated classes)."""

    def test_parse_object_one_of_single(self):
        """'{john}' produces an owl:oneOf with 1 individual."""
        g = _make_graph()
        node = parse_class_expression("{john}", g)
        assert isinstance(node, BNode)
        list_head = None
        for o in g.objects(node, OWL.oneOf):
            list_head = o
        assert list_head is not None
        items = _collect_rdf_list(g, list_head)
        assert len(items) == 1
        assert str(items[0]).endswith("john")

    def test_parse_object_one_of_multiple(self):
        """'{john, jane, bob}' produces an owl:oneOf with 3 individuals."""
        g = _make_graph()
        node = parse_class_expression("{john, jane, bob}", g)
        assert isinstance(node, BNode)
        list_head = None
        for o in g.objects(node, OWL.oneOf):
            list_head = o
        assert list_head is not None
        items = _collect_rdf_list(g, list_head)
        assert len(items) == 3
        names = {str(i).split("#")[-1] for i in items}
        assert names == {"john", "jane", "bob"}

    def test_render_object_one_of(self):
        """Rendering an owl:oneOf produces '{john, jane, bob}'."""
        g = _make_graph()
        # Use parser to create the BNode (ensures all triples exist)
        bnode = parse_class_expression("{john, jane, bob}", g)
        rendered = render_class_expression(g, bnode)
        assert "{" in rendered
        assert "}" in rendered
        assert "john" in rendered
        assert "jane" in rendered
        assert "bob" in rendered

    def test_object_one_of_in_restriction(self):
        """'knows some {john, jane}' produces a someValuesFrom with oneOf filler."""
        g = _make_graph()
        node = parse_class_expression("knows some {john, jane}", g)
        assert isinstance(node, BNode)
        # Should be a restriction
        assert (node, RDF.type, OWL.Restriction) in g
        # On property knows
        prop = None
        for o in g.objects(node, OWL.onProperty):
            prop = o
        assert prop is not None
        assert str(prop).endswith("knows")
        # someValuesFrom should be a oneOf BNode
        filler = None
        for o in g.objects(node, OWL.someValuesFrom):
            filler = o
        assert filler is not None
        assert isinstance(filler, BNode)
        list_head = None
        for o in g.objects(filler, OWL.oneOf):
            list_head = o
        assert list_head is not None
        items = _collect_rdf_list(g, list_head)
        assert len(items) == 2

    def test_object_one_of_roundtrip(self):
        """parse -> render -> result contains the enumeration braces."""
        g = _make_graph()
        node = parse_class_expression("{john, jane, bob}", g)
        rendered = render_class_expression(g, node)
        assert "{" in rendered
        assert "}" in rendered
        # All individual names should appear
        for name in ["john", "jane", "bob"]:
            assert name in rendered


# ═══════════════════════════════════════════════════════════════════
# Datatype Facet Restrictions (6 tests)
# ═══════════════════════════════════════════════════════════════════


def _build_datatype_restriction(g, datatype, facets):
    """Manually build a DatatypeRestriction BNode.

    facets is a list of (facet_uri, value) tuples.
    """
    bnode = BNode()
    g.add((bnode, RDF.type, RDFS.Datatype))
    g.add((bnode, OWL.onDatatype, datatype))
    facet_bnodes = []
    for facet_uri, value in facets:
        fb = BNode()
        g.add((fb, facet_uri, value))
        facet_bnodes.append(fb)
    head = _make_rdf_list(g, facet_bnodes)
    g.add((bnode, OWL.withRestrictions, head))
    return bnode


class TestDatatypeFacetRestrictions:
    """Tests for datatype restrictions with facets (e.g. xsd:integer[>= 0])."""

    def test_parse_datatype_min_inclusive(self):
        """'xsd:integer[>= 0]' produces a DatatypeRestriction with minInclusive."""
        g = _make_graph()
        node = parse_class_expression("xsd:integer[>= 0]", g)
        assert isinstance(node, BNode)
        # Should reference xsd:integer as the base datatype
        on_dt = None
        for o in g.objects(node, OWL.onDatatype):
            on_dt = o
        assert on_dt is not None
        assert str(on_dt) == str(XSD.integer)
        # Should have withRestrictions containing minInclusive
        wr_head = None
        for o in g.objects(node, OWL.withRestrictions):
            wr_head = o
        assert wr_head is not None
        facet_items = _collect_rdf_list(g, wr_head)
        assert len(facet_items) >= 1
        # Check that at least one facet node has xsd:minInclusive
        found_min = False
        for fi in facet_items:
            for o in g.objects(fi, XSD.minInclusive):
                found_min = True
                assert int(str(o)) == 0
        assert found_min

    def test_parse_datatype_max_inclusive(self):
        """'xsd:integer[<= 100]' produces a DatatypeRestriction with maxInclusive."""
        g = _make_graph()
        node = parse_class_expression("xsd:integer[<= 100]", g)
        assert isinstance(node, BNode)
        on_dt = None
        for o in g.objects(node, OWL.onDatatype):
            on_dt = o
        assert str(on_dt) == str(XSD.integer)
        wr_head = None
        for o in g.objects(node, OWL.withRestrictions):
            wr_head = o
        assert wr_head is not None
        facet_items = _collect_rdf_list(g, wr_head)
        found_max = False
        for fi in facet_items:
            for o in g.objects(fi, XSD.maxInclusive):
                found_max = True
                assert int(str(o)) == 100
        assert found_max

    def test_parse_datatype_range(self):
        """'xsd:integer[>= 0, <= 100]' produces two facets."""
        g = _make_graph()
        node = parse_class_expression("xsd:integer[>= 0, <= 100]", g)
        assert isinstance(node, BNode)
        wr_head = None
        for o in g.objects(node, OWL.withRestrictions):
            wr_head = o
        assert wr_head is not None
        facet_items = _collect_rdf_list(g, wr_head)
        assert len(facet_items) == 2
        # Verify both facets are present
        found_min = False
        found_max = False
        for fi in facet_items:
            for o in g.objects(fi, XSD.minInclusive):
                found_min = True
            for o in g.objects(fi, XSD.maxInclusive):
                found_max = True
        assert found_min
        assert found_max

    def test_parse_datatype_min_exclusive(self):
        """'xsd:integer[> 5]' produces a DatatypeRestriction with minExclusive."""
        g = _make_graph()
        node = parse_class_expression("xsd:integer[> 5]", g)
        assert isinstance(node, BNode)
        wr_head = None
        for o in g.objects(node, OWL.withRestrictions):
            wr_head = o
        assert wr_head is not None
        facet_items = _collect_rdf_list(g, wr_head)
        found_excl = False
        for fi in facet_items:
            for o in g.objects(fi, XSD.minExclusive):
                found_excl = True
                assert int(str(o)) == 5
        assert found_excl

    def test_render_datatype_restriction(self):
        """Rendering a DatatypeRestriction produces Manchester with brackets."""
        g = _make_graph()
        bnode = _build_datatype_restriction(g, XSD.integer, [
            (XSD.minInclusive, Literal(0, datatype=XSD.integer)),
            (XSD.maxInclusive, Literal(100, datatype=XSD.integer)),
        ])
        rendered = render_class_expression(g, bnode)
        # Should contain the datatype name and bracket notation
        assert "integer" in rendered
        assert ">=" in rendered or "minInclusive" in rendered.lower() or "[" in rendered

    def test_datatype_in_data_range(self):
        """A datatype restriction can be used as a Range for a data property."""
        g = _make_graph()
        has_age = URIRef(NS + "hasAge")
        bnode = _build_datatype_restriction(g, XSD.integer, [
            (XSD.minInclusive, Literal(0, datatype=XSD.integer)),
            (XSD.maxInclusive, Literal(150, datatype=XSD.integer)),
        ])
        g.add((has_age, RDFS.range, bnode))
        # Verify the range was set
        range_node = None
        for o in g.objects(has_age, RDFS.range):
            range_node = o
        assert range_node is not None
        assert isinstance(range_node, BNode)
        # Verify the restriction structure
        on_dt = None
        for o in g.objects(range_node, OWL.onDatatype):
            on_dt = o
        assert str(on_dt) == str(XSD.integer)


# ═══════════════════════════════════════════════════════════════════
# Native SWRL (10 tests)
# ═══════════════════════════════════════════════════════════════════


class TestNativeSWRL:
    """Tests for SWRL rule service (annotation-based storage)."""

    def test_add_native_swrl_rule(self, tmp_data_dir):
        """Adding a rule produces a [SWRL] annotation on the ontology node."""
        _setup(tmp_data_dir, "swrl-add")
        board_dir = tmp_data_dir / "swrl-add"
        result = add_swrl_rule(board_dir, "Person(?x)", "Adult(?x)")
        assert result["id"]
        assert "Person(?x)" in result["label"]
        assert "Adult(?x)" in result["label"]
        assert result["antecedent"] == "Person(?x)"
        assert result["consequent"] == "Adult(?x)"
        # Verify it is in the graph (native swrl:Imp or annotation)
        from app.services.ontology import load_graph
        g = load_graph(board_dir)
        SWRL_NS = Namespace("http://www.w3.org/2003/11/swrl#")
        native_found = len(list(g.triples((None, RDF.type, SWRL_NS.Imp)))) > 0
        annot_found = any("[SWRL]" in str(o) and "Person(?x)" in str(o)
                         for s in g.subjects(RDF.type, OWL.Ontology)
                         for o in g.objects(s, RDFS.comment))
        assert native_found or annot_found, "Rule not found in graph (neither native nor annotation)"

    def test_list_native_swrl_rules(self, tmp_data_dir):
        """Adding 2 rules and listing returns both."""
        _setup(tmp_data_dir, "swrl-list")
        board_dir = tmp_data_dir / "swrl-list"
        add_swrl_rule(board_dir, "Person(?x)", "Adult(?x)")
        add_swrl_rule(board_dir, "Cat(?x)", "Animal(?x)")
        rules = get_swrl_rules(board_dir)
        assert len(rules) == 2
        labels = {r["label"] for r in rules}
        assert any("Person" in l for l in labels)
        assert any("Cat" in l for l in labels)

    def test_delete_native_swrl_rule(self, tmp_data_dir):
        """Adding then deleting a rule removes it."""
        _setup(tmp_data_dir, "swrl-del")
        board_dir = tmp_data_dir / "swrl-del"
        result = add_swrl_rule(board_dir, "Person(?x)", "Adult(?x)")
        rule_id = result["id"]
        assert len(get_swrl_rules(board_dir)) == 1
        ok = delete_swrl_rule(board_dir, rule_id)
        assert ok is True
        assert len(get_swrl_rules(board_dir)) == 0

    def test_swrl_class_atom(self):
        """'Person(?x)' is parsed as a ClassAtom."""
        atom = _parse_atom("Person(?x)")
        assert atom["type"] == "ClassAtom"
        assert atom["predicate"] == "Person"
        assert atom["arguments"] == ["?x"]

    def test_swrl_property_atom(self):
        """'hasAge(?x, ?a)' is parsed as a PropertyAtom."""
        atom = _parse_atom("hasAge(?x, ?a)")
        assert atom["type"] == "PropertyAtom"
        assert atom["predicate"] == "hasAge"
        assert atom["arguments"] == ["?x", "?a"]

    def test_swrl_builtin_atom(self):
        """'greaterThan(?a, 18)' is parsed as a BuiltinAtom."""
        atom = _parse_atom("greaterThan(?a, 18)")
        assert atom["type"] == "BuiltinAtom"
        assert atom["predicate"] == "greaterThan"
        assert atom["arguments"] == ["?a", "18"]

    def test_swrl_variables_created(self):
        """Variables ?x and ?a appear in parsed atoms."""
        atoms = _parse_atoms("Person(?x) ^ hasAge(?x, ?a)")
        all_args = []
        for a in atoms:
            all_args.extend(a.get("arguments", []))
        assert "?x" in all_args
        assert "?a" in all_args

    def test_swrl_backward_compat(self, tmp_data_dir):
        """Annotation-based rules are readable via the legacy list_rules alias."""
        _setup(tmp_data_dir, "swrl-compat")
        board_dir = tmp_data_dir / "swrl-compat"
        add_swrl_rule(board_dir, "Person(?x)", "Adult(?x)")
        # list_rules is the legacy alias for get_swrl_rules
        rules = list_rules(board_dir)
        assert len(rules) == 1
        assert rules[0]["antecedent"] == "Person(?x)"

    def test_swrl_roundtrip(self, tmp_data_dir):
        """add -> list -> rendered text matches original."""
        _setup(tmp_data_dir, "swrl-rt")
        board_dir = tmp_data_dir / "swrl-rt"
        original = "Person(?x) ^ hasAge(?x, ?a) -> Adult(?x)"
        add_rule(board_dir, original)
        rules = get_swrl_rules(board_dir)
        assert len(rules) == 1
        # The label should contain the full rule text
        assert "Person(?x)" in rules[0]["label"]
        assert "Adult(?x)" in rules[0]["label"]
        assert "->" in rules[0]["label"]
        assert rules[0]["antecedent"] == "Person(?x) ^ hasAge(?x, ?a)"
        assert rules[0]["consequent"] == "Adult(?x)"

    def test_swrl_complex_rule(self, tmp_data_dir):
        """Complex rule: Person(?x) ^ hasAge(?x, ?a) ^ greaterThan(?a, 18) -> Adult(?x)."""
        _setup(tmp_data_dir, "swrl-complex")
        board_dir = tmp_data_dir / "swrl-complex"
        rule_text = "Person(?x) ^ hasAge(?x, ?a) ^ greaterThan(?a, 18) -> Adult(?x)"
        result = add_rule(board_dir, rule_text)
        assert result["id"]
        assert len(result["antecedent_atoms"]) == 3
        assert len(result["consequent_atoms"]) == 1
        # Verify atom types
        ant_types = [a["type"] for a in result["antecedent_atoms"]]
        assert "ClassAtom" in ant_types
        assert "DatavaluedPropertyAtom" in ant_types or "IndividualPropertyAtom" in ant_types or "PropertyAtom" in ant_types
        assert "BuiltinAtom" in ant_types
        # Consequent should be a ClassAtom
        assert result["consequent_atoms"][0]["type"] == "ClassAtom"
        assert result["consequent_atoms"][0]["predicate"] == "Adult"
        # Verify persistence
        rules = get_swrl_rules(board_dir)
        assert len(rules) == 1
        assert rules[0]["id"] == result["id"]


# ═══════════════════════════════════════════════════════════════════
# Integration with axiom editor (4 tests)
# ═══════════════════════════════════════════════════════════════════


class TestAxiomEditorIntegration:
    """Integration tests using the API endpoints."""

    @pytest.mark.asyncio
    async def test_subclassof_has_self_endpoint(self, admin_client, tmp_data_dir):
        """SubClassOf: likes Self saved via axiom API."""
        board_id = "owl2-self"
        await admin_client.post(f"/api/boards/{board_id}")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, board_id)

        entity_iri = f"http://ex.org/t%23Animal"
        resp = await admin_client.put(
            f"/api/axiom/{board_id}/axioms/{entity_iri}",
            json={"manchester_text": "Class: Animal\n    SubClassOf: likes Self"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        assert body["applied"] >= 1

    @pytest.mark.asyncio
    async def test_subclassof_one_of_endpoint(self, admin_client, tmp_data_dir):
        """SubClassOf: {john, jane} saved via axiom API."""
        board_id = "owl2-oneof"
        await admin_client.post(f"/api/boards/{board_id}")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, board_id)

        entity_iri = f"http://ex.org/t%23Cat"
        resp = await admin_client.put(
            f"/api/axiom/{board_id}/axioms/{entity_iri}",
            json={"manchester_text": "Class: Cat\n    SubClassOf: knows some {john, jane}"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        assert body["applied"] >= 1

    @pytest.mark.asyncio
    async def test_swrl_api_create(self, admin_client, tmp_data_dir):
        """POST /api/reasoning/{board_id}/swrl creates a SWRL rule."""
        board_id = "owl2-swrl-create"
        await admin_client.post(f"/api/boards/{board_id}")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, board_id)

        resp = await admin_client.post(
            f"/api/reasoning/{board_id}/swrl",
            json={
                "antecedent": "Person(?x) ^ hasAge(?x, ?a) ^ greaterThan(?a, 18)",
                "consequent": "Adult(?x)",
            },
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["id"]
        assert "Person(?x)" in body["label"]
        assert "Adult(?x)" in body["label"]
        assert len(body["antecedent_atoms"]) == 3
        assert len(body["consequent_atoms"]) == 1

    @pytest.mark.asyncio
    async def test_swrl_api_list_and_delete(self, admin_client, tmp_data_dir):
        """GET /api/reasoning/{board_id}/swrl lists rules; DELETE removes them."""
        board_id = "owl2-swrl-crud"
        await admin_client.post(f"/api/boards/{board_id}")
        await asyncio.sleep(0.1)
        _setup(tmp_data_dir, board_id)

        # Create two rules
        await admin_client.post(
            f"/api/reasoning/{board_id}/swrl",
            json={"antecedent": "Person(?x)", "consequent": "Adult(?x)"},
        )
        await admin_client.post(
            f"/api/reasoning/{board_id}/swrl",
            json={"antecedent": "Cat(?x)", "consequent": "Animal(?x)"},
        )

        # List
        resp = await admin_client.get(f"/api/reasoning/{board_id}/swrl")
        assert resp.status_code == 200
        rules = resp.json()
        assert len(rules) == 2

        # Delete first rule
        rule_id = rules[0]["id"]
        resp = await admin_client.delete(f"/api/reasoning/{board_id}/swrl/{rule_id}")
        assert resp.status_code == 200
        assert resp.json()["success"] is True

        # Verify only one remains
        resp = await admin_client.get(f"/api/reasoning/{board_id}/swrl")
        assert resp.status_code == 200
        remaining = resp.json()
        assert len(remaining) == 1
