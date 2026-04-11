"""Bidirectional conversion between rdflib Graph and CanvasState.

owl_to_canvas: read OWL → produce layout for the editor
canvas_to_owl: read editor state → produce RDF graph
"""

import math
from pathlib import Path

from rdflib import Graph, Namespace, URIRef, Literal, RDF, RDFS, OWL, BNode

from app.schemas.canvas import CanvasClass, CanvasProperty, CanvasIndividual, CanvasState


# ── OWL → Canvas ──────────────────────────────────────────────

def owl_to_canvas(g: Graph) -> CanvasState:
    """Convert an rdflib Graph into a CanvasState with auto-layout."""
    classes = _extract_classes(g)
    properties = _extract_properties(g, classes)
    individuals = _extract_individuals(g, classes)

    # Auto-layout classes in a grid
    _auto_layout(classes)

    return CanvasState(classes=classes, properties=properties, individuals=individuals)


def _extract_classes(g: Graph) -> list[CanvasClass]:
    """Extract all named owl:Class entities."""
    classes = []
    seen = set()
    for s in g.subjects(RDF.type, OWL.Class):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        if iri in seen:
            continue
        seen.add(iri)
        label = _get_label(g, s) or _local_name(iri)
        classes.append(CanvasClass(id=iri, iri=iri, label=label))
    return classes


def _extract_properties(g: Graph, classes: list[CanvasClass]) -> list[CanvasProperty]:
    """Extract object properties with domain/range as connections."""
    class_iris = {c.iri for c in classes}
    properties = []
    seen = set()

    # Object properties
    for s in g.subjects(RDF.type, OWL.ObjectProperty):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        if iri in seen:
            continue
        seen.add(iri)
        label = _get_label(g, s) or _local_name(iri)
        domain = _get_single_object(g, s, RDFS.domain)
        range_ = _get_single_object(g, s, RDFS.range)
        if domain and range_ and domain in class_iris and range_ in class_iris:
            properties.append(CanvasProperty(
                id=iri, iri=iri, label=label,
                source_id=domain, target_id=range_,
                property_type="object",
            ))

    # Also extract subClassOf relationships as connections
    for s, _, o in g.triples((None, RDFS.subClassOf, None)):
        if isinstance(s, BNode) or isinstance(o, BNode):
            continue
        s_iri, o_iri = str(s), str(o)
        if s_iri in class_iris and o_iri in class_iris:
            edge_id = f"subClassOf_{s_iri}_{o_iri}"
            if edge_id not in seen:
                seen.add(edge_id)
                properties.append(CanvasProperty(
                    id=edge_id, iri="rdfs:subClassOf", label="subClassOf",
                    source_id=s_iri, target_id=o_iri,
                    property_type="annotation",
                ))

    return properties


def _extract_individuals(g: Graph, classes: list[CanvasClass]) -> list[CanvasIndividual]:
    """Extract named individuals."""
    class_iris = {c.iri for c in classes}
    individuals = []
    seen = set()
    for s in g.subjects(RDF.type, OWL.NamedIndividual):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        if iri in seen:
            continue
        seen.add(iri)
        label = _get_label(g, s) or _local_name(iri)
        # Find the class type (excluding owl:NamedIndividual itself)
        class_iri = ""
        for _, _, o in g.triples((s, RDF.type, None)):
            o_str = str(o)
            if o_str != str(OWL.NamedIndividual) and o_str in class_iris:
                class_iri = o_str
                break
        individuals.append(CanvasIndividual(id=iri, iri=iri, label=label, class_iri=class_iri))
    return individuals


def _auto_layout(classes: list[CanvasClass], cols: int = 4, gap_x: float = 220, gap_y: float = 120):
    """Simple grid layout for classes."""
    for i, cls in enumerate(classes):
        cls.x = 80 + (i % cols) * gap_x
        cls.y = 80 + (i // cols) * gap_y


# ── Canvas → OWL ──────────────────────────────────────────────

def canvas_to_owl(state: CanvasState, base_iri: str) -> Graph:
    """Convert a CanvasState into an rdflib Graph."""
    g = Graph()
    g.bind("owl", OWL)
    g.bind("rdfs", RDFS)

    ns = Namespace(base_iri + "#")
    g.bind("", ns)

    # Ontology declaration
    ont = URIRef(base_iri)
    g.add((ont, RDF.type, OWL.Ontology))
    g.add((ont, RDFS.label, Literal(base_iri.split("/")[-1])))

    # Classes
    for cls in state.classes:
        c = URIRef(cls.iri)
        g.add((c, RDF.type, OWL.Class))
        g.add((c, RDFS.label, Literal(cls.label, lang="en")))

    # Properties (object properties + subClassOf)
    for prop in state.properties:
        if prop.iri == "rdfs:subClassOf":
            g.add((URIRef(prop.source_id), RDFS.subClassOf, URIRef(prop.target_id)))
        elif prop.property_type == "object":
            p = URIRef(prop.iri)
            g.add((p, RDF.type, OWL.ObjectProperty))
            g.add((p, RDFS.label, Literal(prop.label, lang="en")))
            g.add((p, RDFS.domain, URIRef(prop.source_id)))
            g.add((p, RDFS.range, URIRef(prop.target_id)))
        elif prop.property_type == "data":
            p = URIRef(prop.iri)
            g.add((p, RDF.type, OWL.DatatypeProperty))
            g.add((p, RDFS.label, Literal(prop.label, lang="en")))

    # Individuals
    for ind in state.individuals:
        i = URIRef(ind.iri)
        g.add((i, RDF.type, OWL.NamedIndividual))
        g.add((i, RDFS.label, Literal(ind.label, lang="en")))
        if ind.class_iri:
            g.add((i, RDF.type, URIRef(ind.class_iri)))

    return g


def canvas_state_to_owl_xml(state: CanvasState, base_iri: str) -> str:
    """Serialize CanvasState to OWL/XML string."""
    g = canvas_to_owl(state, base_iri)
    return g.serialize(format="xml")


# ── Helpers ────────────────────────────────────────────────────

def _get_label(g: Graph, subject) -> str | None:
    for o in g.objects(subject, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local_name(iri: str) -> str:
    """Extract local name from IRI (after # or last /)."""
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def _get_single_object(g: Graph, subject, predicate) -> str | None:
    for o in g.objects(subject, predicate):
        if not isinstance(o, BNode):
            return str(o)
    return None
