"""OWL restriction service — create, read, delete restrictions using rdflib.

Supports: someValuesFrom, allValuesFrom, hasValue, min/max/exact cardinality,
qualified cardinality, unionOf, intersectionOf, complementOf.
"""

from pathlib import Path

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL, XSD
from rdflib.collection import Collection

from app.services.ontology import load_graph


# ── Read restrictions ──────────────────────────────────────────

def get_restrictions_for_entity(g: Graph, entity_iri: str) -> list[dict]:
    """Extract all restrictions that appear as SuperClass of the entity."""
    entity = URIRef(entity_iri)
    restrictions = []

    for _, _, superclass in g.triples((entity, RDFS.subClassOf, None)):
        if isinstance(superclass, BNode):
            r = _parse_restriction_bnode(g, superclass)
            if r:
                restrictions.append(r)

    return restrictions


def _parse_restriction_bnode(g: Graph, bnode: BNode) -> dict | None:
    """Parse a restriction BNode into a structured dict."""
    # Check if it's an owl:Restriction
    if (bnode, RDF.type, OWL.Restriction) in g:
        on_prop = _single_obj(g, bnode, OWL.onProperty)
        if not on_prop:
            return None

        prop_label = _get_label(g, on_prop) or _local(str(on_prop))

        # someValuesFrom
        filler = _single_obj(g, bnode, OWL.someValuesFrom)
        if filler:
            filler_label = _get_label(g, filler) or _local(str(filler)) if isinstance(filler, URIRef) else str(filler)
            return {"restriction_type": "someValuesFrom", "on_property": str(on_prop),
                    "on_property_label": prop_label, "filler": str(filler), "filler_label": filler_label,
                    "manchester": f"{prop_label} some {filler_label}"}

        # allValuesFrom
        filler = _single_obj(g, bnode, OWL.allValuesFrom)
        if filler:
            filler_label = _get_label(g, filler) or _local(str(filler)) if isinstance(filler, URIRef) else str(filler)
            return {"restriction_type": "allValuesFrom", "on_property": str(on_prop),
                    "on_property_label": prop_label, "filler": str(filler), "filler_label": filler_label,
                    "manchester": f"{prop_label} only {filler_label}"}

        # hasValue
        val = _single_obj(g, bnode, OWL.hasValue)
        if val:
            val_str = str(val)
            return {"restriction_type": "hasValue", "on_property": str(on_prop),
                    "on_property_label": prop_label, "filler": val_str, "filler_label": val_str,
                    "manchester": f"{prop_label} value {val_str}"}

        # Cardinality
        for pred, rtype, keyword in [
            (OWL.minCardinality, "minCardinality", "min"),
            (OWL.maxCardinality, "maxCardinality", "max"),
            (OWL.cardinality, "exactCardinality", "exactly"),
            (OWL.minQualifiedCardinality, "minCardinality", "min"),
            (OWL.maxQualifiedCardinality, "maxCardinality", "max"),
            (OWL.qualifiedCardinality, "exactCardinality", "exactly"),
        ]:
            card = _single_obj(g, bnode, pred)
            if card:
                card_val = int(str(card))
                qual = _single_obj(g, bnode, OWL.onClass)
                qual_label = ""
                if qual and isinstance(qual, URIRef):
                    qual_label = _get_label(g, qual) or _local(str(qual))
                manchester = f"{prop_label} {keyword} {card_val}"
                if qual_label:
                    manchester += f" {qual_label}"
                return {"restriction_type": rtype, "on_property": str(on_prop),
                        "on_property_label": prop_label, "cardinality": card_val,
                        "qualified_class": str(qual) if qual else None,
                        "filler": "", "filler_label": qual_label,
                        "manchester": manchester}

    # Check for set operations (unionOf, intersectionOf, complementOf)
    for pred, etype in [(OWL.unionOf, "unionOf"), (OWL.intersectionOf, "intersectionOf")]:
        list_node = _single_obj(g, bnode, pred)
        if list_node:
            members = list(Collection(g, list_node))
            member_labels = [_get_label(g, m) or _local(str(m)) for m in members if isinstance(m, URIRef)]
            joiner = " or " if etype == "unionOf" else " and "
            return {"restriction_type": etype, "on_property": "",
                    "on_property_label": "", "filler": str(list_node),
                    "filler_label": joiner.join(member_labels),
                    "manchester": joiner.join(member_labels)}

    comp = _single_obj(g, bnode, OWL.complementOf)
    if comp:
        label = _get_label(g, comp) or _local(str(comp)) if isinstance(comp, URIRef) else str(comp)
        return {"restriction_type": "complementOf", "on_property": "",
                "on_property_label": "", "filler": str(comp), "filler_label": label,
                "manchester": f"not {label}"}

    return None


# ── Create restrictions ────────────────────────────────────────

def add_restriction(board_dir: Path, entity_iri: str, restriction: dict) -> bool:
    """Add a restriction as SubClassOf axiom to the entity."""
    g = load_graph(board_dir)
    entity = URIRef(entity_iri)
    rtype = restriction["restriction_type"]
    bnode = BNode()

    if rtype in ("someValuesFrom", "allValuesFrom"):
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, URIRef(restriction["on_property"])))
        pred = OWL.someValuesFrom if rtype == "someValuesFrom" else OWL.allValuesFrom
        g.add((bnode, pred, URIRef(restriction["filler"])))
        g.add((entity, RDFS.subClassOf, bnode))

    elif rtype == "hasValue":
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, URIRef(restriction["on_property"])))
        val = restriction["filler"]
        # Try to detect if it's a URI or literal
        if val.startswith("http://") or val.startswith("https://"):
            g.add((bnode, OWL.hasValue, URIRef(val)))
        else:
            g.add((bnode, OWL.hasValue, Literal(val)))
        g.add((entity, RDFS.subClassOf, bnode))

    elif rtype in ("minCardinality", "maxCardinality", "exactCardinality"):
        g.add((bnode, RDF.type, OWL.Restriction))
        g.add((bnode, OWL.onProperty, URIRef(restriction["on_property"])))
        card = restriction.get("cardinality", 0)
        qual = restriction.get("qualified_class")
        if qual:
            pred_map = {"minCardinality": OWL.minQualifiedCardinality,
                        "maxCardinality": OWL.maxQualifiedCardinality,
                        "exactCardinality": OWL.qualifiedCardinality}
            g.add((bnode, pred_map[rtype], Literal(card, datatype=XSD.nonNegativeInteger)))
            g.add((bnode, OWL.onClass, URIRef(qual)))
        else:
            pred_map = {"minCardinality": OWL.minCardinality,
                        "maxCardinality": OWL.maxCardinality,
                        "exactCardinality": OWL.cardinality}
            g.add((bnode, pred_map[rtype], Literal(card, datatype=XSD.nonNegativeInteger)))
        g.add((entity, RDFS.subClassOf, bnode))

    elif rtype == "unionOf":
        members = [URIRef(iri) for iri in restriction.get("operands", [])]
        if members:
            col = BNode()
            Collection(g, col, members)
            g.add((bnode, OWL.unionOf, col))
            g.add((entity, RDFS.subClassOf, bnode))

    elif rtype == "intersectionOf":
        members = [URIRef(iri) for iri in restriction.get("operands", [])]
        if members:
            col = BNode()
            Collection(g, col, members)
            g.add((bnode, OWL.intersectionOf, col))
            g.add((entity, RDFS.subClassOf, bnode))

    elif rtype == "complementOf":
        comp_class = restriction.get("filler", "")
        if comp_class:
            g.add((bnode, OWL.complementOf, URIRef(comp_class)))
            g.add((entity, RDFS.subClassOf, bnode))
    else:
        return False

    _save_graph(g, board_dir)
    return True


def remove_restriction(board_dir: Path, entity_iri: str, restriction: dict) -> bool:
    """Remove a matching restriction from the entity's SubClassOf axioms."""
    g = load_graph(board_dir)
    entity = URIRef(entity_iri)

    for _, _, superclass in list(g.triples((entity, RDFS.subClassOf, None))):
        if isinstance(superclass, BNode):
            parsed = _parse_restriction_bnode(g, superclass)
            if parsed and _restrictions_match(parsed, restriction):
                # Remove the SubClassOf triple and all triples about the BNode
                g.remove((entity, RDFS.subClassOf, superclass))
                _remove_bnode_recursive(g, superclass)
                _save_graph(g, board_dir)
                return True
    return False


def add_complex_expression(board_dir: Path, entity_iri: str, expr: dict) -> bool:
    """Add a complex class expression (union/intersection/complement) as equivalent or subclass."""
    restriction = {
        "restriction_type": expr["expression_type"],
        "operands": expr.get("operands", []),
        "filler": expr.get("operands", [""])[0] if expr["expression_type"] == "complementOf" else "",
    }
    return add_restriction(board_dir, entity_iri, restriction)


# ── Helpers ────────────────────────────────────────────────────

def _single_obj(g: Graph, subj, pred):
    for o in g.objects(subj, pred):
        return o
    return None


def _get_label(g, node) -> str | None:
    if not isinstance(node, URIRef):
        return None
    for o in g.objects(node, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local(iri: str) -> str:
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def _restrictions_match(parsed: dict, target: dict) -> bool:
    if parsed.get("restriction_type") != target.get("restriction_type"):
        return False
    if target.get("on_property") and parsed.get("on_property") != target["on_property"]:
        return False
    if target.get("filler") and parsed.get("filler") != target["filler"]:
        return False
    if target.get("cardinality") is not None and parsed.get("cardinality") != target["cardinality"]:
        return False
    return True


def _remove_bnode_recursive(g: Graph, bnode: BNode):
    """Remove all triples involving a BNode and its nested BNodes."""
    for _, _, o in list(g.triples((bnode, None, None))):
        if isinstance(o, BNode):
            _remove_bnode_recursive(g, o)
    g.remove((bnode, None, None))
    g.remove((None, None, bnode))


def _save_graph(g: Graph, board_dir: Path):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
