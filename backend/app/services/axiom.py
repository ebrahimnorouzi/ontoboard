"""Axiom service — extract, generate, and update axioms for ontology entities.

Uses rdflib to inspect and modify the OWL graph.
Manchester Syntax generation is approximated from RDF triples.
"""

import logging
from pathlib import Path

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL, XSD

from app.services.ontology import load_graph

logger = logging.getLogger("ontoboard.axiom")

# ── Manchester Syntax keywords for each axiom type ─────────────
_AXIOM_TYPE_MAP = {
    str(RDFS.subClassOf): "SubClassOf",
    str(OWL.equivalentClass): "EquivalentTo",
    str(OWL.disjointWith): "DisjointWith",
    str(RDFS.domain): "Domain",
    str(RDFS.range): "Range",
    str(RDFS.subPropertyOf): "SubPropertyOf",
    str(OWL.inverseOf): "InverseOf",
    str(OWL.equivalentProperty): "EquivalentProperty",
}


def get_axioms_for_entity(g: Graph, entity_iri: str) -> list[dict]:
    """Extract all axioms involving the given entity as subject."""
    entity = URIRef(entity_iri)
    axioms = []

    # Determine entity type
    entity_type = _get_entity_type(g, entity)

    # SubClassOf / EquivalentClass / DisjointWith (class axioms)
    for pred_uri, axiom_type in _AXIOM_TYPE_MAP.items():
        pred = URIRef(pred_uri)
        for o in g.objects(entity, pred):
            obj_str = _render_object(g, o)
            axioms.append({
                "axiom_type": axiom_type,
                "subject": entity_iri,
                "predicate": pred_uri,
                "object": str(o) if not isinstance(o, BNode) else obj_str,
                "manchester": f"{axiom_type}: {obj_str}",
            })

    # Also check where entity is used as object (usages)
    for s, p, _ in g.triples((None, None, entity)):
        if isinstance(s, BNode):
            continue
        pred_str = str(p)
        if pred_str in _AXIOM_TYPE_MAP:
            axiom_type = _AXIOM_TYPE_MAP[pred_str]
            s_label = _get_label(g, s) or _local_name(str(s))
            axioms.append({
                "axiom_type": f"UsedIn {axiom_type}",
                "subject": str(s),
                "predicate": pred_str,
                "object": entity_iri,
                "manchester": f"[{s_label}] {axiom_type}: {_get_label(g, entity) or _local_name(entity_iri)}",
            })

    return axioms


def get_manchester_for_entity(g: Graph, entity_iri: str) -> str:
    """Generate Manchester Syntax representation for an entity."""
    entity = URIRef(entity_iri)
    entity_type = _get_entity_type(g, entity)
    label = _get_label(g, entity) or _local_name(entity_iri)

    lines = []

    if entity_type == "class":
        lines.append(f"Class: {label}")
        # Annotations
        for ann_line in _get_annotation_lines(g, entity):
            lines.append(f"    Annotations: {ann_line}")
        # SubClassOf
        for o in g.objects(entity, RDFS.subClassOf):
            obj_str = _render_object(g, o)
            lines.append(f"    SubClassOf: {obj_str}")
        # EquivalentTo
        for o in g.objects(entity, OWL.equivalentClass):
            obj_str = _render_object(g, o)
            lines.append(f"    EquivalentTo: {obj_str}")
        # DisjointWith
        for o in g.objects(entity, OWL.disjointWith):
            obj_str = _render_object(g, o)
            lines.append(f"    DisjointWith: {obj_str}")

    elif entity_type == "object_property":
        lines.append(f"ObjectProperty: {label}")
        for ann_line in _get_annotation_lines(g, entity):
            lines.append(f"    Annotations: {ann_line}")
        for o in g.objects(entity, RDFS.domain):
            lines.append(f"    Domain: {_render_object(g, o)}")
        for o in g.objects(entity, RDFS.range):
            lines.append(f"    Range: {_render_object(g, o)}")
        for o in g.objects(entity, RDFS.subPropertyOf):
            lines.append(f"    SubPropertyOf: {_render_object(g, o)}")
        for o in g.objects(entity, OWL.inverseOf):
            lines.append(f"    InverseOf: {_render_object(g, o)}")

    elif entity_type == "data_property":
        lines.append(f"DataProperty: {label}")
        for ann_line in _get_annotation_lines(g, entity):
            lines.append(f"    Annotations: {ann_line}")
        for o in g.objects(entity, RDFS.domain):
            lines.append(f"    Domain: {_render_object(g, o)}")
        for o in g.objects(entity, RDFS.range):
            lines.append(f"    Range: {_render_object(g, o)}")

    elif entity_type == "individual":
        lines.append(f"Individual: {label}")
        for ann_line in _get_annotation_lines(g, entity):
            lines.append(f"    Annotations: {ann_line}")
        # Types
        for o in g.objects(entity, RDF.type):
            o_str = str(o)
            if o_str != str(OWL.NamedIndividual):
                lines.append(f"    Types: {_render_object(g, o)}")
        # Facts (data/object property assertions)
        for p, o in g.predicate_objects(entity):
            p_str = str(p)
            if p_str not in (str(RDF.type), str(RDFS.label), str(RDFS.comment)):
                if p_str.startswith("http://www.w3.org/") and not p_str.startswith("http://www.w3.org/2002/07/owl"):
                    continue
                lines.append(f"    Facts: {_get_label(g, p) or _local_name(p_str)} {_render_object(g, o)}")
    else:
        lines.append(f"# Unknown entity type for {entity_iri}")

    return "\n".join(lines)


def apply_manchester_edit(board_dir: Path, entity_iri: str, manchester_text: str) -> dict:
    """Parse Manchester Syntax edits and apply to the OWL file.

    This is a simplified parser that handles:
    - SubClassOf: <expression>
    - EquivalentTo: <expression>
    - DisjointWith: <expression>
    - Domain: <expression>
    - Range: <expression>
    """
    g = load_graph(board_dir)
    entity = URIRef(entity_iri)
    errors = []
    warnings = []
    applied = 0

    # Remove existing structural axioms for this entity (we'll re-add them)
    removable_preds = [RDFS.subClassOf, OWL.equivalentClass, OWL.disjointWith,
                       RDFS.domain, RDFS.range, RDFS.subPropertyOf, OWL.inverseOf]
    for pred in removable_preds:
        g.remove((entity, pred, None))

    # Parse Manchester lines
    for line_num, line in enumerate(manchester_text.split("\n"), 1):
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("Class:") or \
           line.startswith("ObjectProperty:") or line.startswith("DataProperty:") or \
           line.startswith("Individual:") or line.startswith("Annotations:"):
            continue

        handled = False
        for keyword, pred in [
            ("SubClassOf:", RDFS.subClassOf),
            ("EquivalentTo:", OWL.equivalentClass),
            ("DisjointWith:", OWL.disjointWith),
            ("Domain:", RDFS.domain),
            ("Range:", RDFS.range),
            ("SubPropertyOf:", RDFS.subPropertyOf),
            ("InverseOf:", OWL.inverseOf),
        ]:
            if line.startswith(keyword):
                obj_str = line[len(keyword):].strip()
                obj_uri = _resolve_name(g, obj_str)
                if obj_uri:
                    g.add((entity, pred, obj_uri))
                    applied += 1
                else:
                    errors.append({"line": line_num, "column": len(keyword) + 1,
                                   "message": f"Cannot resolve '{obj_str}'"})
                handled = True
                break

        if not handled and not line.startswith("Types:") and not line.startswith("Facts:"):
            warnings.append(f"Line {line_num}: Unrecognized syntax '{line[:40]}...'")

    # Save back
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")

    return {
        "success": len(errors) == 0,
        "applied": applied,
        "errors": errors,
        "warnings": warnings,
    }


def validate_manchester(text: str) -> list[dict]:
    """Basic syntax validation without applying changes."""
    errors = []
    valid_prefixes = ("Class:", "ObjectProperty:", "DataProperty:", "Individual:",
                      "SubClassOf:", "EquivalentTo:", "DisjointWith:", "Domain:",
                      "Range:", "SubPropertyOf:", "InverseOf:", "Annotations:",
                      "Types:", "Facts:", "#")

    for line_num, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        if not stripped:
            continue
        if not any(stripped.startswith(p) for p in valid_prefixes):
            errors.append({
                "line": line_num,
                "column": 1,
                "message": f"Expected a Manchester Syntax keyword, got '{stripped[:30]}...'",
            })

    return errors


def get_entity_names(g: Graph) -> list[dict]:
    """Return all named entities with labels for autocomplete."""
    entities = []
    for entity_type, rdf_type in [
        ("class", OWL.Class), ("object_property", OWL.ObjectProperty),
        ("data_property", OWL.DatatypeProperty), ("individual", OWL.NamedIndividual),
    ]:
        for s in g.subjects(RDF.type, rdf_type):
            if isinstance(s, BNode):
                continue
            label = _get_label(g, s) or _local_name(str(s))
            entities.append({"iri": str(s), "label": label, "type": entity_type})
    return entities


# ── Helpers ────────────────────────────────────────────────────

def _get_entity_type(g: Graph, entity: URIRef) -> str:
    types = set(str(t) for t in g.objects(entity, RDF.type))
    if str(OWL.Class) in types:
        return "class"
    if str(OWL.ObjectProperty) in types:
        return "object_property"
    if str(OWL.DatatypeProperty) in types:
        return "data_property"
    if str(OWL.NamedIndividual) in types:
        return "individual"
    if str(OWL.AnnotationProperty) in types:
        return "annotation_property"
    return "unknown"


def _get_label(g: Graph, subject) -> str | None:
    for o in g.objects(subject, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local_name(iri: str) -> str:
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def _render_object(g: Graph, obj) -> str:
    """Render an RDF object as a Manchester-style string."""
    if isinstance(obj, Literal):
        return f'"{obj}"'
    if isinstance(obj, BNode):
        # Try to render restriction expressions
        return _render_restriction(g, obj)
    label = _get_label(g, obj)
    if label:
        return label
    return _local_name(str(obj))


def _render_restriction(g: Graph, bnode: BNode) -> str:
    """Attempt to render an OWL restriction as Manchester Syntax."""
    on_prop = None
    for o in g.objects(bnode, OWL.onProperty):
        on_prop = _get_label(g, o) or _local_name(str(o))

    some_cls = None
    for o in g.objects(bnode, OWL.someValuesFrom):
        some_cls = _get_label(g, o) or _local_name(str(o))

    all_cls = None
    for o in g.objects(bnode, OWL.allValuesFrom):
        all_cls = _get_label(g, o) or _local_name(str(o))

    if on_prop and some_cls:
        return f"{on_prop} some {some_cls}"
    if on_prop and all_cls:
        return f"{on_prop} only {all_cls}"
    if on_prop:
        return f"{on_prop} some Thing"
    return "(anonymous expression)"


def _get_annotation_lines(g: Graph, entity: URIRef) -> list[str]:
    """Get annotation property values for display."""
    lines = []
    for p, o in g.predicate_objects(entity):
        if str(p) == str(RDFS.label):
            if isinstance(o, Literal) and o.language:
                lines.append(f'rdfs:label "{o}"@{o.language}')
        elif str(p) == str(RDFS.comment):
            if isinstance(o, Literal):
                lines.append(f'rdfs:comment "{o}"')
    return lines


def _resolve_name(g: Graph, name: str) -> URIRef | None:
    """Try to resolve a label or local name to a URIRef."""
    name = name.strip()
    # If it looks like a full IRI
    if name.startswith("http://") or name.startswith("https://"):
        return URIRef(name)
    # Search by label
    for s, _, o in g.triples((None, RDFS.label, None)):
        if isinstance(o, Literal) and str(o) == name and not isinstance(s, BNode):
            return s
    # Search by local name
    for s in g.all_nodes():
        if isinstance(s, URIRef) and _local_name(str(s)) == name:
            return s
    return None
