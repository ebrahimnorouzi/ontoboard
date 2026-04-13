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
    individuals = _extract_individuals(g, classes)
    properties = _extract_properties(g, classes, individuals)

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


def _extract_properties(g: Graph, classes: list[CanvasClass], individuals: list[CanvasIndividual] | None = None) -> list[CanvasProperty]:
    """Extract object/data properties and rdf:type edges as connections."""
    class_iris = {c.iri for c in classes}
    ind_iris = {i.iri for i in (individuals or [])}
    known_iris = class_iris | ind_iris
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
        if domain and range_ and (domain in known_iris or range_ in known_iris):
            properties.append(CanvasProperty(
                id=iri, iri=iri, label=label,
                source_id=domain, target_id=range_,
                property_type="object",
            ))

    # Data properties
    for s in g.subjects(RDF.type, OWL.DatatypeProperty):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        if iri in seen:
            continue
        seen.add(iri)
        label = _get_label(g, s) or _local_name(iri)
        domain = _get_single_object(g, s, RDFS.domain)
        range_ = _get_single_object(g, s, RDFS.range)
        source = domain if domain and domain in known_iris else None
        if source:
            properties.append(CanvasProperty(
                id=iri, iri=iri, label=label,
                source_id=source, target_id=range_ or "",
                property_type="data",
            ))
        else:
            properties.append(CanvasProperty(
                id=iri, iri=iri, label=label,
                source_id=domain or "", target_id=range_ or "",
                property_type="data",
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

    # Extract rdf:type relationships between individuals and classes
    for ind_iri in ind_iris:
        for _, _, o in g.triples((URIRef(ind_iri), RDF.type, None)):
            o_str = str(o)
            if o_str == str(OWL.NamedIndividual):
                continue
            if o_str in class_iris:
                edge_id = f"rdfType_{ind_iri}_{o_str}"
                if edge_id not in seen:
                    seen.add(edge_id)
                    properties.append(CanvasProperty(
                        id=edge_id, iri="rdf:type", label="rdf:type",
                        source_id=ind_iri, target_id=o_str,
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


def _auto_layout(classes: list[CanvasClass], cols: int = 0, gap_x: float = 200, gap_y: float = 100):
    """Tree-aware grid layout — positions children near parents.

    Uses a square grid that scales with ontology size.
    For large ontologies (200+) uses tighter spacing to fit on screen.
    """
    n = len(classes)
    if n == 0:
        return

    import math
    # Calculate grid dimensions for roughly square layout
    if cols == 0:
        cols = max(3, int(math.ceil(math.sqrt(n) * 1.3)))

    # Scale gaps based on count
    if n > 300:
        gap_x = 170
        gap_y = 75
    elif n > 100:
        gap_x = 190
        gap_y = 90

    for i, cls in enumerate(classes):
        cls.x = 60 + (i % cols) * gap_x
        cls.y = 60 + (i // cols) * gap_y


# ── Canvas → OWL ──────────────────────────────────────────────

def canvas_to_owl(state: CanvasState, base_iri: str) -> Graph:
    """Convert a CanvasState into an rdflib Graph.

    If provenance tracking is enabled and target includes 'ontology',
    embeds PROV-O and Dublin Core provenance annotations on each entity.
    """
    g = Graph()
    g.bind("owl", OWL)
    g.bind("rdfs", RDFS)

    ns = Namespace(base_iri + "#")
    g.bind("", ns)

    # Provenance namespaces
    PROV = Namespace("http://www.w3.org/ns/prov#")
    DC = Namespace("http://purl.org/dc/terms/")
    FOAF = Namespace("http://xmlns.com/foaf/0.1/")
    g.bind("prov", PROV)
    g.bind("dcterms", DC)
    g.bind("foaf", FOAF)

    embed_prov = state.track_provenance and state.provenance_target in ("ontology", "both")

    # Ontology declaration
    ont = URIRef(base_iri)
    g.add((ont, RDF.type, OWL.Ontology))
    g.add((ont, RDFS.label, Literal(base_iri.split("/")[-1])))

    XSD = Namespace("http://www.w3.org/2001/XMLSchema#")

    def _add_provenance(subject, created_by: str, created_at: str,
                        modified_by: str, modified_at: str):
        """Add PROV-O / Dublin Core provenance annotations to an entity.

        Uses proper semantic web ontologies:
        - PROV-O (W3C): prov:wasAttributedTo, prov:generatedAtTime, prov:wasGeneratedBy
        - Dublin Core Terms: dcterms:creator, dcterms:created, dcterms:modified, dcterms:contributor
        - FOAF: foaf:name for agent identification
        """
        if not embed_prov:
            return

        # Create a PROV Agent for the creator
        if created_by:
            agent_uri = URIRef(base_iri + "#agent-" + created_by.replace(" ", "-").replace("@", "-"))
            g.add((agent_uri, RDF.type, PROV.Agent))
            g.add((agent_uri, FOAF.name, Literal(created_by)))
            g.add((subject, PROV.wasAttributedTo, agent_uri))
            g.add((subject, DC.creator, Literal(created_by)))

        if created_at:
            g.add((subject, DC.created, Literal(created_at, datatype=XSD.dateTime)))
            g.add((subject, PROV.generatedAtTime, Literal(created_at, datatype=XSD.dateTime)))

        if modified_by:
            g.add((subject, DC.contributor, Literal(modified_by)))

        if modified_at:
            g.add((subject, DC.modified, Literal(modified_at, datatype=XSD.dateTime)))

    # Classes
    for cls in state.classes:
        c = URIRef(cls.iri)
        g.add((c, RDF.type, OWL.Class))
        g.add((c, RDFS.label, Literal(cls.label, lang="en")))
        _add_provenance(c, cls.created_by, cls.created_at,
                        cls.modified_by, cls.modified_at)

    # Properties (object properties, data properties, subClassOf, rdf:type)
    for prop in state.properties:
        if prop.iri == "rdfs:subClassOf":
            g.add((URIRef(prop.source_id), RDFS.subClassOf, URIRef(prop.target_id)))
        elif prop.iri == "rdf:type":
            # rdf:type edge between individual and class
            if prop.source_id and prop.target_id:
                g.add((URIRef(prop.source_id), RDF.type, URIRef(prop.target_id)))
        elif prop.property_type == "object":
            p = URIRef(prop.iri)
            g.add((p, RDF.type, OWL.ObjectProperty))
            g.add((p, RDFS.label, Literal(prop.label, lang="en")))
            if prop.source_id:
                g.add((p, RDFS.domain, URIRef(prop.source_id)))
            if prop.target_id:
                g.add((p, RDFS.range, URIRef(prop.target_id)))
            _add_provenance(p, prop.created_by, prop.created_at,
                            prop.modified_by, prop.modified_at)
        elif prop.property_type == "data":
            p = URIRef(prop.iri)
            g.add((p, RDF.type, OWL.DatatypeProperty))
            g.add((p, RDFS.label, Literal(prop.label, lang="en")))
            if prop.source_id:
                g.add((p, RDFS.domain, URIRef(prop.source_id)))
            if prop.target_id:
                g.add((p, RDFS.range, URIRef(prop.target_id)))
            _add_provenance(p, prop.created_by, prop.created_at,
                            prop.modified_by, prop.modified_at)
        elif prop.property_type == "annotation" and prop.iri not in ("rdfs:subClassOf", "rdf:type"):
            # Annotation properties
            p = URIRef(prop.iri)
            g.add((p, RDF.type, OWL.AnnotationProperty))
            g.add((p, RDFS.label, Literal(prop.label, lang="en")))
            _add_provenance(p, prop.created_by, prop.created_at,
                            prop.modified_by, prop.modified_at)

    # Individuals
    for ind in state.individuals:
        i = URIRef(ind.iri)
        g.add((i, RDF.type, OWL.NamedIndividual))
        g.add((i, RDFS.label, Literal(ind.label, lang="en")))
        if ind.class_iri:
            g.add((i, RDF.type, URIRef(ind.class_iri)))
        _add_provenance(i, ind.created_by, ind.created_at,
                        ind.modified_by, ind.modified_at)

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
