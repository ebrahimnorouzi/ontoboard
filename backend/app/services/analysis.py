"""Analysis service — unused entities, import health, deprecation, circular deps."""

import urllib.request
from pathlib import Path

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL

from app.services.ontology import load_graph


def find_unused_entities(board_dir: Path) -> list[dict]:
    """Find entities that are not referenced by any other entity."""
    g = load_graph(board_dir)
    unused = []

    for rdf_type, etype in [(OWL.Class, "class"), (OWL.ObjectProperty, "object_property"),
                             (OWL.DatatypeProperty, "data_property")]:
        for s in g.subjects(RDF.type, rdf_type):
            if isinstance(s, BNode):
                continue
            iri = str(s)
            # Check if anything references this entity as object (excluding its own triples)
            refs = [t for t in g.triples((None, None, s)) if t[0] != s]
            if not refs:
                label = _get_label(g, s) or _local(iri)
                unused.append({"iri": iri, "label": label, "entity_type": etype})

    return unused


def find_deprecated(board_dir: Path) -> list[dict]:
    """Find entities marked as owl:deprecated."""
    g = load_graph(board_dir)
    deprecated = []
    for s, _, o in g.triples((None, OWL.deprecated, None)):
        if isinstance(s, BNode):
            continue
        if str(o).lower() == "true":
            label = _get_label(g, s) or _local(str(s))
            deprecated.append({"iri": str(s), "label": label})
    return deprecated


def check_import_health(board_dir: Path) -> list[dict]:
    """Check if import URIs resolve (HTTP HEAD)."""
    g = load_graph(board_dir)
    results = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.imports):
            iri = str(o)
            try:
                req = urllib.request.Request(iri, method="HEAD")
                with urllib.request.urlopen(req, timeout=5) as resp:
                    results.append({"iri": iri, "status": "ok", "code": resp.status})
            except Exception as exc:
                results.append({"iri": iri, "status": "error", "code": 0, "message": str(exc)[:100]})
    return results


def detect_circular_imports(board_dir: Path) -> list[list[str]]:
    """Detect circular import dependencies (simplified — checks direct self-import)."""
    g = load_graph(board_dir)
    cycles = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        ont_iri = str(s)
        for o in g.objects(s, OWL.imports):
            if str(o) == ont_iri:
                cycles.append([ont_iri, str(o)])
    return cycles


def batch_add_annotation(board_dir: Path, entity_iris: list[str], property_iri: str,
                          value: str, language: str | None = None) -> int:
    """Add an annotation to multiple entities at once."""
    g = load_graph(board_dir)
    count = 0
    prop = URIRef(property_iri)
    for iri in entity_iris:
        entity = URIRef(iri)
        lit = Literal(value, lang=language) if language else Literal(value)
        g.add((entity, prop, lit))
        count += 1

    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
    return count


def _get_label(g, s):
    for o in g.objects(s, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local(iri):
    return iri.split("#")[-1] if "#" in iri else iri.rsplit("/", 1)[-1]
