"""Ontology metadata service — DC/DCTERMS, prefix CRUD, find/replace."""

from pathlib import Path

from rdflib import Graph, URIRef, Literal, Namespace, RDF, RDFS, OWL
from rdflib.namespace import DC, DCTERMS, SKOS

from app.services.ontology import load_graph

# Standard metadata properties we support
_META_PROPS = {
    "title": DC.title,
    "creator": DC.creator,
    "description": DC.description,
    "date": DC.date,
    "rights": DC.rights,
    "license": DCTERMS.license,
    "created": DCTERMS.created,
    "modified": DCTERMS.modified,
    "publisher": DCTERMS.publisher,
    "contributor": DCTERMS.contributor,
    "subject": DCTERMS.subject,
}


def get_metadata(g: Graph) -> dict:
    """Read all DC/DCTERMS metadata from the ontology node."""
    ont = _ont_node(g)
    if not ont:
        return {}
    result = {}
    for name, prop in _META_PROPS.items():
        values = [str(o) for o in g.objects(ont, prop)]
        result[name] = values[0] if len(values) == 1 else (values if values else "")
    return result


def update_metadata(board_dir: Path, updates: dict) -> dict:
    """Update DC/DCTERMS metadata on the ontology node."""
    g = load_graph(board_dir)
    ont = _ont_node(g)
    if not ont:
        return {}
    for name, value in updates.items():
        prop = _META_PROPS.get(name)
        if not prop:
            continue
        g.remove((ont, prop, None))
        if value:
            if name == "license" and (str(value).startswith("http://") or str(value).startswith("https://")):
                g.add((ont, prop, URIRef(value)))
            else:
                g.add((ont, prop, Literal(value)))
    _save(g, board_dir)
    return get_metadata(g)


def get_prefixes(g: Graph) -> list[dict]:
    """List all namespace prefix bindings."""
    return [{"prefix": p or "(default)", "namespace": str(ns)} for p, ns in g.namespaces()]


def add_prefix(board_dir: Path, prefix: str, namespace: str) -> bool:
    """Add a prefix binding."""
    g = load_graph(board_dir)
    g.bind(prefix, Namespace(namespace), override=True)
    _save(g, board_dir)
    return True


def remove_prefix(board_dir: Path, prefix: str) -> bool:
    """Remove a prefix binding (limited in rdflib — rebind to empty)."""
    g = load_graph(board_dir)
    # rdflib doesn't support true prefix removal, but we can rebind
    try:
        g.bind(prefix, Namespace("urn:removed:"), override=True)
        _save(g, board_dir)
        return True
    except Exception:
        return False


def find_replace_annotations(board_dir: Path, find: str, replace: str,
                              property_iri: str | None = None) -> int:
    """Find and replace text in annotation values."""
    g = load_graph(board_dir)
    preds = [URIRef(property_iri)] if property_iri else [RDFS.label, RDFS.comment, SKOS.prefLabel, SKOS.altLabel, SKOS.definition]
    count = 0

    for pred in preds:
        for s, _, o in list(g.triples((None, pred, None))):
            if isinstance(o, Literal) and find in str(o):
                new_val = str(o).replace(find, replace)
                g.remove((s, pred, o))
                g.add((s, pred, Literal(new_val, lang=o.language, datatype=o.datatype)))
                count += 1

    _save(g, board_dir)
    return count


def _ont_node(g):
    for s in g.subjects(RDF.type, OWL.Ontology):
        return s
    return None


def _save(g, board_dir):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
