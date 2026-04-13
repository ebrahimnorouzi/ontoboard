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

    # Remove existing annotations (rdfs:comment, rdfs:label annotations beyond the primary label)
    # Keep the primary rdfs:label but remove rdfs:comment and custom annotation properties
    _annotation_preds_to_clear = [RDFS.comment]
    for pred in _annotation_preds_to_clear:
        g.remove((entity, pred, None))

    # Parse Manchester lines
    current_section = None
    for line_num, line in enumerate(manchester_text.split("\n"), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        # Skip entity declaration headers
        if line.startswith("Class:") or line.startswith("ObjectProperty:") or \
           line.startswith("DataProperty:") or line.startswith("Individual:"):
            continue

        # Track section headers
        if line.endswith(":") and line[:-1] in ("SubClassOf", "EquivalentTo", "DisjointWith",
            "Domain", "Range", "SubPropertyOf", "InverseOf", "Annotations", "Types", "Facts",
            "SameAs", "DifferentFrom", "Characteristics", "DisjointUnionOf"):
            current_section = line[:-1]
            continue

        handled = False

        # Handle Annotations: <property> <value>
        if line.startswith("Annotations:"):
            ann_text = line[len("Annotations:"):].strip()
            _apply_annotation(g, entity, ann_text, errors, line_num)
            applied += 1
            handled = True
        elif current_section == "Annotations":
            _apply_annotation(g, entity, line, errors, line_num)
            applied += 1
            handled = True

        if not handled:
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

            # Handle bare values under a section header (e.g. indented lines under SubClassOf:)
            if not handled and current_section:
                pred_map = {
                    "SubClassOf": RDFS.subClassOf, "EquivalentTo": OWL.equivalentClass,
                    "DisjointWith": OWL.disjointWith, "Domain": RDFS.domain,
                    "Range": RDFS.range, "SubPropertyOf": RDFS.subPropertyOf,
                    "InverseOf": OWL.inverseOf,
                }
                if current_section in pred_map:
                    obj_uri = _resolve_name(g, line)
                    if obj_uri:
                        g.add((entity, pred_map[current_section], obj_uri))
                        applied += 1
                        handled = True
                    else:
                        errors.append({"line": line_num, "column": 1,
                                       "message": f"Cannot resolve '{line}'"})
                        handled = True

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
    # Known annotation predicates to display
    ann_preds = {
        str(RDFS.label): "rdfs:label",
        str(RDFS.comment): "rdfs:comment",
        str(RDFS.seeAlso): "rdfs:seeAlso",
        str(RDFS.isDefinedBy): "rdfs:isDefinedBy",
        "http://purl.org/dc/terms/creator": "dcterms:creator",
        "http://purl.org/dc/terms/description": "dcterms:description",
        "http://purl.org/dc/terms/title": "dcterms:title",
        "http://purl.org/dc/elements/1.1/creator": "dc:creator",
        "http://purl.org/dc/elements/1.1/description": "dc:description",
        "http://www.w3.org/2004/02/skos/core#prefLabel": "skos:prefLabel",
        "http://www.w3.org/2004/02/skos/core#altLabel": "skos:altLabel",
        "http://www.w3.org/2004/02/skos/core#definition": "skos:definition",
        "http://www.w3.org/2004/02/skos/core#example": "skos:example",
        "http://www.w3.org/2004/02/skos/core#note": "skos:note",
        "http://purl.obolibrary.org/obo/IAO_0000115": "obo:IAO_0000115",
    }
    skip = {str(RDF.type), str(OWL.imports), str(OWL.versionIRI), str(OWL.versionInfo)}

    for p, o in g.predicate_objects(entity):
        p_str = str(p)
        if p_str in skip:
            continue
        if p_str in ann_preds:
            compact = ann_preds[p_str]
            if isinstance(o, Literal):
                lang = f"@{o.language}" if o.language else ""
                lines.append(f'{compact} "{o}"{lang}')
        elif isinstance(o, Literal) and not p_str.startswith("http://www.w3.org/1999/02/22-rdf-syntax-ns#") and \
             not p_str.startswith("http://www.w3.org/2000/01/rdf-schema#sub") and \
             not p_str.startswith("http://www.w3.org/2002/07/owl#"):
            # Custom annotation properties
            compact = _local_name(p_str)
            lines.append(f'{compact} "{o}"')
    return lines


def _apply_annotation(g: Graph, entity: URIRef, ann_text: str,
                      errors: list, line_num: int) -> None:
    """Parse and apply an annotation line like: rdfs:comment "Some text" or rdfs:label "Name"@en"""
    ann_text = ann_text.strip()

    # Parse: <property> "<value>"[@lang]
    import re
    # Match: property_name "value"[@lang]
    m = re.match(r'^(\S+)\s+"(.*?)"(?:@(\w+))?$', ann_text)
    if m:
        prop_name, value, lang = m.group(1), m.group(2), m.group(3)
        prop_uri = _resolve_annotation_property(prop_name)
        if prop_uri:
            if lang:
                g.add((entity, prop_uri, Literal(value, lang=lang)))
            else:
                g.add((entity, prop_uri, Literal(value)))
            return

    # Match: property_name value (no quotes — treat value as literal)
    parts = ann_text.split(None, 1)
    if len(parts) == 2:
        prop_name, value = parts
        prop_uri = _resolve_annotation_property(prop_name)
        if prop_uri:
            # Strip surrounding quotes if present
            value = value.strip().strip('"')
            g.add((entity, prop_uri, Literal(value)))
            return

    errors.append({"line": line_num, "column": 1,
                   "message": f"Cannot parse annotation: '{ann_text[:60]}'"})


# Well-known prefixed names → URIs
_WELL_KNOWN = {
    "owl:Thing": str(OWL.Thing), "owl:Nothing": str(OWL.Nothing),
    "owl:Class": str(OWL.Class), "owl:ObjectProperty": str(OWL.ObjectProperty),
    "owl:DatatypeProperty": str(OWL.DatatypeProperty),
    "owl:NamedIndividual": str(OWL.NamedIndividual),
    "owl:topObjectProperty": str(OWL.topObjectProperty),
    "owl:bottomObjectProperty": str(OWL.bottomObjectProperty),
    "rdfs:Resource": str(RDFS.Resource), "rdfs:Class": str(RDFS.Class),
    "rdfs:Literal": str(RDFS.Literal),
    "xsd:string": str(XSD.string), "xsd:integer": str(XSD.integer),
    "xsd:boolean": str(XSD.boolean), "xsd:float": str(XSD.float),
    "xsd:double": str(XSD.double), "xsd:dateTime": str(XSD.dateTime),
    "xsd:date": str(XSD.date), "xsd:decimal": str(XSD.decimal),
    "xsd:anyURI": str(XSD.anyURI),
}

# Well-known annotation properties
_ANNOTATION_PROPERTIES = {
    "rdfs:label": RDFS.label,
    "rdfs:comment": RDFS.comment,
    "rdfs:seeAlso": RDFS.seeAlso,
    "rdfs:isDefinedBy": RDFS.isDefinedBy,
    "owl:deprecated": OWL.deprecated,
    "dcterms:creator": URIRef("http://purl.org/dc/terms/creator"),
    "dcterms:description": URIRef("http://purl.org/dc/terms/description"),
    "dcterms:title": URIRef("http://purl.org/dc/terms/title"),
    "dc:creator": URIRef("http://purl.org/dc/elements/1.1/creator"),
    "dc:description": URIRef("http://purl.org/dc/elements/1.1/description"),
    "skos:prefLabel": URIRef("http://www.w3.org/2004/02/skos/core#prefLabel"),
    "skos:altLabel": URIRef("http://www.w3.org/2004/02/skos/core#altLabel"),
    "skos:definition": URIRef("http://www.w3.org/2004/02/skos/core#definition"),
    "skos:example": URIRef("http://www.w3.org/2004/02/skos/core#example"),
    "skos:note": URIRef("http://www.w3.org/2004/02/skos/core#note"),
    "obo:IAO_0000115": URIRef("http://purl.obolibrary.org/obo/IAO_0000115"),  # definition
}


def _resolve_annotation_property(name: str) -> URIRef | None:
    """Resolve an annotation property name to a URIRef."""
    name = name.strip()
    if name in _ANNOTATION_PROPERTIES:
        return _ANNOTATION_PROPERTIES[name]
    if name.startswith("http://") or name.startswith("https://"):
        return URIRef(name)
    return None


def _resolve_name(g: Graph, name: str) -> URIRef | None:
    """Try to resolve a label or local name to a URIRef.

    Handles well-known OWL/RDF/RDFS/XSD terms, compact IRIs, labels, and local names.
    """
    name = name.strip()
    # Full IRI
    if name.startswith("http://") or name.startswith("https://"):
        return URIRef(name)
    # Well-known prefixed names (owl:Thing, rdfs:Resource, xsd:string, etc.)
    if name in _WELL_KNOWN:
        return URIRef(_WELL_KNOWN[name])
    # Compact IRI with graph namespace bindings
    if ":" in name and not name.startswith('"'):
        prefix, local = name.split(":", 1)
        for p, ns in g.namespaces():
            if p == prefix:
                return URIRef(str(ns) + local)
    # Search by label
    for s, _, o in g.triples((None, RDFS.label, None)):
        if isinstance(o, Literal) and str(o) == name and not isinstance(s, BNode):
            return s
    # Search by local name
    for s in g.all_nodes():
        if isinstance(s, URIRef) and _local_name(str(s)) == name:
            return s
    return None
