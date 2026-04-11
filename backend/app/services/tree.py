"""Tree service — hierarchical entity browsing, detail view, annotation CRUD.

Builds Protege-like class/property trees from the OWL graph.
"""

from pathlib import Path

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL

from app.schemas.tree import TreeNode, AnnotationValue, UsageInfo, EntityDetail
from app.services.ontology import load_graph


# ── Tree builders ──────────────────────────────────────────────

def get_class_tree(g: Graph) -> list[TreeNode]:
    """Build class hierarchy rooted at owl:Thing."""
    classes = _collect_named(g, OWL.Class)
    children_map = _build_children_map(g, classes, RDFS.subClassOf)
    roots = _find_roots(classes, children_map)
    return [_build_tree_node(g, iri, "class", children_map, classes) for iri in roots]


def get_object_property_tree(g: Graph) -> list[TreeNode]:
    props = _collect_named(g, OWL.ObjectProperty)
    children_map = _build_children_map(g, props, RDFS.subPropertyOf)
    roots = _find_roots(props, children_map)
    return [_build_tree_node(g, iri, "object_property", children_map, props) for iri in roots]


def get_data_property_tree(g: Graph) -> list[TreeNode]:
    props = _collect_named(g, OWL.DatatypeProperty)
    children_map = _build_children_map(g, props, RDFS.subPropertyOf)
    roots = _find_roots(props, children_map)
    return [_build_tree_node(g, iri, "data_property", children_map, props) for iri in roots]


def get_annotation_property_tree(g: Graph) -> list[TreeNode]:
    props = _collect_named(g, OWL.AnnotationProperty)
    return [TreeNode(
        iri=iri, label=_get_label(g, URIRef(iri)) or _local_name(iri),
        entity_type="annotation_property", annotation_count=0,
    ) for iri in sorted(props)]


def get_individuals_by_class(g: Graph) -> dict[str, list[TreeNode]]:
    """Group individuals by their rdf:type (excluding owl:NamedIndividual)."""
    result: dict[str, list[TreeNode]] = {}
    for s in g.subjects(RDF.type, OWL.NamedIndividual):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        label = _get_label(g, s) or _local_name(iri)
        for _, _, o in g.triples((s, RDF.type, None)):
            o_str = str(o)
            if o_str != str(OWL.NamedIndividual) and not isinstance(o, BNode):
                class_label = _get_label(g, o) or _local_name(o_str)
                result.setdefault(class_label, []).append(
                    TreeNode(iri=iri, label=label, entity_type="individual")
                )
    # If no specific type found, put under "Untyped"
    for s in g.subjects(RDF.type, OWL.NamedIndividual):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        types = [str(o) for o in g.objects(s, RDF.type) if str(o) != str(OWL.NamedIndividual)]
        if not types:
            label = _get_label(g, s) or _local_name(iri)
            result.setdefault("Untyped", []).append(
                TreeNode(iri=iri, label=label, entity_type="individual")
            )
    return result


# ── Entity detail ──────────────────────────────────────────────

def get_entity_detail(g: Graph, iri: str) -> EntityDetail:
    """Full detail view for one entity."""
    entity = URIRef(iri)
    label = _get_label(g, entity) or _local_name(iri)
    entity_type = _detect_type(g, entity)

    # Annotations
    annotations = _get_annotations(g, entity)

    # Count axioms (structural triples where entity is subject)
    axiom_preds = {RDFS.subClassOf, OWL.equivalentClass, OWL.disjointWith,
                   RDFS.domain, RDFS.range, RDFS.subPropertyOf, OWL.inverseOf}
    axiom_count = sum(1 for p in axiom_preds for _ in g.objects(entity, p))

    # Usages (where entity appears as object)
    usages = []
    for s, p, _ in g.triples((None, None, entity)):
        if isinstance(s, BNode):
            continue
        s_label = _get_label(g, s) or _local_name(str(s))
        usages.append(UsageInfo(
            subject_iri=str(s), subject_label=s_label,
            predicate=_local_name(str(p)), role="object",
        ))

    return EntityDetail(
        iri=iri, label=label, entity_type=entity_type,
        annotations=annotations, axiom_count=axiom_count, usages=usages[:50],
    )


# ── Annotation CRUD ────────────────────────────────────────────

def update_entity_annotations(board_dir: Path, iri: str, updates: list[dict]) -> bool:
    """Add, edit, or delete annotations on an entity."""
    g = load_graph(board_dir)
    entity = URIRef(iri)

    for upd in updates:
        prop = URIRef(upd["property_iri"])
        lang = upd.get("language")
        value = upd["value"]
        action = upd.get("action", "add")

        if action == "delete":
            # Remove all values of this annotation property
            g.remove((entity, prop, None))
        elif action == "add":
            lit = Literal(value, lang=lang) if lang else Literal(value)
            g.add((entity, prop, lit))
        elif action == "edit":
            # Remove old, add new
            g.remove((entity, prop, None))
            lit = Literal(value, lang=lang) if lang else Literal(value)
            g.add((entity, prop, lit))

    # Save
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
    return True


# ── Entity CRUD ────────────────────────────────────────────────

def create_entity(board_dir: Path, entity_type: str, iri: str, label: str, parent_iri: str | None) -> bool:
    """Add a new class, property, or individual to the ontology."""
    g = load_graph(board_dir)
    entity = URIRef(iri)

    type_map = {
        "class": OWL.Class,
        "object_property": OWL.ObjectProperty,
        "data_property": OWL.DatatypeProperty,
        "annotation_property": OWL.AnnotationProperty,
        "individual": OWL.NamedIndividual,
    }
    rdf_type = type_map.get(entity_type)
    if not rdf_type:
        return False

    g.add((entity, RDF.type, rdf_type))
    g.add((entity, RDFS.label, Literal(label, lang="en")))
    # Default annotation: empty comment (Protege-style)
    g.add((entity, RDFS.comment, Literal("", lang="en")))

    if parent_iri:
        parent = URIRef(parent_iri)
        if entity_type == "class":
            g.add((entity, RDFS.subClassOf, parent))
        elif entity_type in ("object_property", "data_property"):
            g.add((entity, RDFS.subPropertyOf, parent))
        elif entity_type == "individual":
            g.add((entity, RDF.type, parent))

    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
    return True


def delete_entity(board_dir: Path, iri: str) -> bool:
    """Remove an entity and all its triples from the ontology."""
    g = load_graph(board_dir)
    entity = URIRef(iri)

    # Remove all triples where entity is subject or object
    g.remove((entity, None, None))
    g.remove((None, None, entity))

    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
    return True


# ── Helpers ────────────────────────────────────────────────────

def _collect_named(g: Graph, rdf_type) -> set[str]:
    """Collect all named (non-blank) entities of a given type."""
    return {str(s) for s in g.subjects(RDF.type, rdf_type) if not isinstance(s, BNode)}


def _build_children_map(g: Graph, entities: set[str], sub_pred) -> dict[str, list[str]]:
    """Build parent→children mapping from subClassOf/subPropertyOf triples."""
    children: dict[str, list[str]] = {e: [] for e in entities}
    for s, _, o in g.triples((None, sub_pred, None)):
        s_str, o_str = str(s), str(o)
        if s_str in entities and o_str in entities:
            children.setdefault(o_str, []).append(s_str)
    return children


def _find_roots(entities: set[str], children_map: dict[str, list[str]]) -> list[str]:
    """Find entities that are not children of any other entity."""
    all_children = set()
    for kids in children_map.values():
        all_children.update(kids)
    roots = [e for e in entities if e not in all_children]
    return sorted(roots, key=lambda x: _local_name(x))


def _build_tree_node(g: Graph, iri: str, entity_type: str,
                     children_map: dict[str, list[str]], all_entities: set[str]) -> TreeNode:
    kids = sorted(children_map.get(iri, []), key=lambda x: _local_name(x))
    return TreeNode(
        iri=iri,
        label=_get_label(g, URIRef(iri)) or _local_name(iri),
        entity_type=entity_type,
        children=[_build_tree_node(g, k, entity_type, children_map, all_entities) for k in kids],
        annotation_count=_count_annotations(g, URIRef(iri)),
    )


def _get_label(g: Graph, subject) -> str | None:
    for o in g.objects(subject, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local_name(iri: str) -> str:
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def _detect_type(g: Graph, entity: URIRef) -> str:
    types = {str(t) for t in g.objects(entity, RDF.type)}
    if str(OWL.Class) in types:
        return "class"
    if str(OWL.ObjectProperty) in types:
        return "object_property"
    if str(OWL.DatatypeProperty) in types:
        return "data_property"
    if str(OWL.AnnotationProperty) in types:
        return "annotation_property"
    if str(OWL.NamedIndividual) in types:
        return "individual"
    return "unknown"


def _get_annotations(g: Graph, entity: URIRef) -> list[AnnotationValue]:
    """Get all annotation property values."""
    annotations = []
    ann_preds = {RDFS.label, RDFS.comment, RDFS.seeAlso, RDFS.isDefinedBy}
    # Also detect custom annotation properties
    for ap in g.subjects(RDF.type, OWL.AnnotationProperty):
        ann_preds.add(ap)

    for p in ann_preds:
        for o in g.objects(entity, p):
            if isinstance(o, Literal):
                annotations.append(AnnotationValue(
                    property_iri=str(p),
                    property_label=_get_label(g, p) or _local_name(str(p)),
                    value=str(o),
                    language=o.language,
                    datatype=str(o.datatype) if o.datatype else None,
                ))
    return annotations


def _count_annotations(g: Graph, entity: URIRef) -> int:
    count = 0
    for p in (RDFS.label, RDFS.comment, RDFS.seeAlso):
        count += len(list(g.objects(entity, p)))
    return count
