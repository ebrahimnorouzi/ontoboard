"""Ontology metadata service — full annotation management.

Handles:
- Ontology IRI and Version IRI (editable)
- All annotation properties on the ontology node (dcterms, bibo, vann, doap, etc.)
- Both literal and IRI-valued annotations
- Prefix management (add/edit/delete)
- Prefix resolution: compact IRI ↔ full IRI
"""

from pathlib import Path

from rdflib import Graph, URIRef, Literal, Namespace, BNode, RDF, RDFS, OWL
from rdflib.namespace import DC, DCTERMS, SKOS

from app.services.ontology import load_graph


# ═══════════════════════════════════════════════════════════════
# Ontology IRI & Version Management
# ═══════════════════════════════════════════════════════════════

def get_ontology_identity(g: Graph) -> dict:
    """Get the ontology IRI, version IRI, and version info."""
    for s in g.subjects(RDF.type, OWL.Ontology):
        version_iri = None
        version_info = None
        prior_version = None
        for o in g.objects(s, OWL.versionIRI):
            version_iri = str(o)
        for o in g.objects(s, OWL.versionInfo):
            version_info = str(o)
        for o in g.objects(s, OWL.priorVersion):
            prior_version = str(o)
        return {
            "ontology_iri": str(s),
            "version_iri": version_iri,
            "version_info": version_info,
            "prior_version": prior_version,
        }
    return {"ontology_iri": "", "version_iri": None, "version_info": None, "prior_version": None}


def set_ontology_identity(board_dir: Path, ontology_iri: str | None = None,
                           version_iri: str | None = None, version_info: str | None = None) -> dict:
    """Update the ontology IRI and/or version IRI."""
    g = load_graph(board_dir)
    ont = _ont_node(g)
    if not ont:
        return get_ontology_identity(g)

    if version_iri is not None:
        g.remove((ont, OWL.versionIRI, None))
        if version_iri:
            g.add((ont, OWL.versionIRI, URIRef(version_iri)))

    if version_info is not None:
        g.remove((ont, OWL.versionInfo, None))
        if version_info:
            g.add((ont, OWL.versionInfo, Literal(version_info)))

    _save(g, board_dir)
    return get_ontology_identity(g)


# ═══════════════════════════════════════════════════════════════
# Full Ontology Annotations (read ALL annotations on the ontology node)
# ═══════════════════════════════════════════════════════════════

def get_all_ontology_annotations(g: Graph) -> list[dict]:
    """Read ALL annotation triples on the ontology node.

    Returns list of:
    {
        "property": "http://purl.org/dc/terms/creator",
        "property_compact": "dcterms:creator",
        "value": "https://orcid.org/0000-0001-7192-7143",
        "value_type": "iri",     # "iri" | "literal"
        "language": null,        # e.g. "en"
        "datatype": null,        # e.g. "xsd:string"
    }
    """
    ont = _ont_node(g)
    if not ont:
        return []

    annotations = []
    skip_preds = {str(RDF.type), str(OWL.imports)}  # Not annotations

    for p, o in g.predicate_objects(ont):
        pred_str = str(p)
        if pred_str in skip_preds:
            continue

        ann = {
            "property": pred_str,
            "property_compact": _compact(g, pred_str),
        }

        if isinstance(o, URIRef):
            ann["value"] = str(o)
            ann["value_type"] = "iri"
            ann["language"] = None
            ann["datatype"] = None
        elif isinstance(o, Literal):
            ann["value"] = str(o)
            ann["value_type"] = "literal"
            ann["language"] = o.language
            ann["datatype"] = str(o.datatype) if o.datatype else None
        else:
            continue

        annotations.append(ann)

    return annotations


def add_ontology_annotation(board_dir: Path, property_iri: str, value: str,
                             value_type: str = "literal", language: str | None = None) -> bool:
    """Add an annotation to the ontology node.

    value_type: "literal" or "iri"
    """
    g = load_graph(board_dir)
    ont = _ont_node(g)
    if not ont:
        return False

    prop = URIRef(_expand(g, property_iri))

    if value_type == "iri":
        g.add((ont, prop, URIRef(_expand(g, value))))
    else:
        if language:
            g.add((ont, prop, Literal(value, lang=language)))
        else:
            g.add((ont, prop, Literal(value)))

    _save(g, board_dir)
    return True


def remove_ontology_annotation(board_dir: Path, property_iri: str, value: str) -> bool:
    """Remove a specific annotation from the ontology node."""
    g = load_graph(board_dir)
    ont = _ont_node(g)
    if not ont:
        return False

    prop = URIRef(_expand(g, property_iri))
    # Try removing as URIRef first, then as Literal
    removed = False
    for s, p, o in list(g.triples((ont, prop, None))):
        if str(o) == value or str(o) == _expand(g, value):
            g.remove((s, p, o))
            removed = True
            break

    if removed:
        _save(g, board_dir)
    return removed


# ═══════════════════════════════════════════════════════════════
# Prefix Management
# ═══════════════════════════════════════════════════════════════

def get_prefixes(g: Graph) -> list[dict]:
    """List all namespace prefix bindings."""
    return [{"prefix": p or "(default)", "namespace": str(ns)} for p, ns in g.namespaces()]


def add_prefix(board_dir: Path, prefix: str, namespace: str) -> bool:
    """Add a prefix binding to the ontology."""
    g = load_graph(board_dir)
    g.bind(prefix, Namespace(namespace), override=True)
    _save(g, board_dir)
    return True


def remove_prefix(board_dir: Path, prefix: str) -> bool:
    """Remove a prefix binding (rebind to placeholder)."""
    g = load_graph(board_dir)
    g.bind(prefix, Namespace("urn:removed:"), override=True)
    _save(g, board_dir)
    return True


def resolve_compact_iri(g: Graph, compact_iri: str) -> str:
    """Resolve a compact IRI like 'prov:Activity' to its full IRI.

    Uses the ontology's prefix bindings.
    """
    if compact_iri.startswith("http://") or compact_iri.startswith("https://"):
        return compact_iri

    if ":" in compact_iri:
        prefix, local = compact_iri.split(":", 1)
        for p, ns in g.namespaces():
            if p == prefix:
                return str(ns) + local

    # Try well-known prefixes
    return _expand(g, compact_iri)


def compact_iri(g: Graph, full_iri: str) -> str:
    """Compact a full IRI to prefix:localName using the ontology's bindings."""
    return _compact(g, full_iri)


# ═══════════════════════════════════════════════════════════════
# Find/Replace in Annotations
# ═══════════════════════════════════════════════════════════════

def find_replace_annotations(board_dir: Path, find: str, replace: str,
                              property_iri: str | None = None) -> int:
    """Find and replace text in annotation values across all entities."""
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


# Keep backward compat
def get_metadata(g: Graph) -> dict:
    """Read DC/DCTERMS metadata from the ontology node (legacy)."""
    ont = _ont_node(g)
    if not ont:
        return {}
    result = {}
    for name, prop in {
        "title": DC.title, "creator": DC.creator, "description": DC.description,
        "license": DCTERMS.license, "created": DCTERMS.created,
    }.items():
        values = [str(o) for o in g.objects(ont, prop)]
        result[name] = values[0] if len(values) == 1 else (values if values else "")
    return result


def update_metadata(board_dir: Path, updates: dict) -> dict:
    """Update DC/DCTERMS metadata (legacy)."""
    g = load_graph(board_dir)
    ont = _ont_node(g)
    if not ont:
        return {}
    prop_map = {
        "title": DC.title, "creator": DC.creator, "description": DC.description,
        "license": DCTERMS.license, "created": DCTERMS.created,
    }
    for name, value in updates.items():
        prop = prop_map.get(name)
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


# ═══════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════

def _ont_node(g):
    for s in g.subjects(RDF.type, OWL.Ontology):
        return s
    return None


def _save(g, board_dir):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")


def _compact(g: Graph, iri: str) -> str:
    """Compact an IRI using the graph's namespace bindings."""
    for p, ns in g.namespaces():
        ns_str = str(ns)
        if iri.startswith(ns_str) and p:
            return f"{p}:{iri[len(ns_str):]}"
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def _expand(g: Graph, compact_or_iri: str) -> str:
    """Expand a compact IRI to full IRI using the graph's namespace bindings."""
    if compact_or_iri.startswith("http://") or compact_or_iri.startswith("https://"):
        return compact_or_iri
    if ":" in compact_or_iri:
        prefix, local = compact_or_iri.split(":", 1)
        for p, ns in g.namespaces():
            if p == prefix:
                return str(ns) + local
    return compact_or_iri
