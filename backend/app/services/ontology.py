"""Ontology introspection service — rdflib-based OWL parsing.

Provides metadata extraction, statistics, and prefix enumeration
without needing Docker/ROBOT for read operations.
"""

import csv
import logging
from pathlib import Path

from rdflib import Graph, Namespace, URIRef, Literal, RDF, RDFS, OWL, XSD
from rdflib.namespace import SKOS, DCTERMS, DC

from app.config import DATA_DIR

logger = logging.getLogger("ontoboard.ontology")

# Common OWL namespaces for detection
_WELL_KNOWN_NS = {
    "owl": str(OWL),
    "rdf": str(RDF),
    "rdfs": str(RDFS),
    "xsd": str(XSD),
    "skos": str(SKOS),
    "dcterms": str(DCTERMS),
    "dc": str(DC),
}


def load_graph(board_dir: Path) -> Graph:
    """Load the board's primary OWL file into an rdflib Graph."""
    ont_dir = board_dir / "src" / "ontology"
    owl_files = list(ont_dir.glob("*.owl")) if ont_dir.exists() else []
    if not owl_files:
        raise FileNotFoundError(f"No .owl file in {ont_dir}")

    g = Graph()
    g.parse(str(owl_files[0]), format="xml")
    return g


def load_full_graph(board_dir: Path) -> Graph:
    """Load all RDF files in the board (OWL + Turtle KGs)."""
    g = Graph()
    for ext in ("*.owl", "*.ttl", "*.rdf", "*.nt"):
        for f in board_dir.rglob(ext):
            if ".git" in str(f) or "tmp_" in f.name:
                continue
            try:
                fmt = {"owl": "xml", "ttl": "turtle", "rdf": "xml", "nt": "nt"}[f.suffix[1:]]
                g.parse(str(f), format=fmt)
            except Exception as exc:
                logger.warning("Failed to parse %s: %s", f, exc)
    return g


def get_ontology_iri(g: Graph) -> str | None:
    """Extract the ontology IRI from an owl:Ontology declaration."""
    for s in g.subjects(RDF.type, OWL.Ontology):
        return str(s)
    return None


def get_version_iri(g: Graph) -> str | None:
    """Extract owl:versionIRI if present."""
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.versionIRI):
            return str(o)
    return None


def get_imports(g: Graph) -> list[str]:
    """Extract owl:imports."""
    result = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.imports):
            result.append(str(o))
    return sorted(result)


def get_prefixes(g: Graph) -> list[dict]:
    """Enumerate namespace prefix bindings."""
    result = []
    for prefix, ns in g.namespaces():
        result.append({"prefix": prefix or "(default)", "namespace": str(ns)})
    return sorted(result, key=lambda p: p["prefix"])


def get_languages(g: Graph) -> list[str]:
    """Collect all language tags used in the ontology."""
    langs = set()
    for _, _, o in g:
        if isinstance(o, Literal) and o.language:
            langs.add(o.language)
    return sorted(langs) if langs else ["en"]


def get_ontology_metadata(g: Graph) -> dict:
    """Return combined metadata dictionary."""
    return {
        "ontology_iri": get_ontology_iri(g) or "",
        "version_iri": get_version_iri(g),
        "imports": get_imports(g),
        "prefixes": get_prefixes(g),
        "languages": get_languages(g),
    }


def get_ontology_statistics(g: Graph) -> dict:
    """Count entities and axioms in the ontology."""
    def _count(rdf_type):
        return len(set(g.subjects(RDF.type, rdf_type)))

    classes = _count(OWL.Class)
    object_properties = _count(OWL.ObjectProperty)
    data_properties = _count(OWL.DatatypeProperty)
    annotation_properties = _count(OWL.AnnotationProperty)
    individuals = _count(OWL.NamedIndividual)

    # Count axiom types
    subclass_axioms = len(list(g.triples((None, RDFS.subClassOf, None))))
    equivalent_axioms = len(list(g.triples((None, OWL.equivalentClass, None))))
    disjoint_axioms = len(list(g.triples((None, OWL.disjointWith, None))))
    domain_axioms = len(list(g.triples((None, RDFS.domain, None))))
    range_axioms = len(list(g.triples((None, RDFS.range, None))))

    total_axioms = subclass_axioms + equivalent_axioms + disjoint_axioms + domain_axioms + range_axioms

    return {
        "classes": classes,
        "object_properties": object_properties,
        "data_properties": data_properties,
        "annotation_properties": annotation_properties,
        "individuals": individuals,
        "total_axioms": total_axioms,
        "subclass_axioms": subclass_axioms,
        "equivalent_axioms": equivalent_axioms,
        "disjoint_axioms": disjoint_axioms,
        "domain_axioms": domain_axioms,
        "range_axioms": range_axioms,
        "total_triples": len(g),
    }


def parse_robot_report_tsv(report_path: Path) -> dict:
    """Parse ROBOT report TSV output into structured violations."""
    violations = []
    if not report_path.exists():
        return {"violations": [], "summary": "No report generated"}

    content = report_path.read_text()
    lines = content.strip().split("\n")
    if len(lines) <= 1:
        return {"violations": [], "summary": "No violations found"}

    reader = csv.DictReader(lines, delimiter="\t")
    for row in reader:
        violations.append({
            "subject": row.get("Subject", row.get("subject", "")),
            "property": row.get("Property", row.get("property", "")),
            "severity": row.get("Level", row.get("level", "INFO")),
            "message": row.get("Message", row.get("message", "")),
            "rule": row.get("Rule Name", row.get("rule", "")),
        })

    error_count = sum(1 for v in violations if v["severity"].upper() == "ERROR")
    warn_count = sum(1 for v in violations if v["severity"].upper() in ("WARN", "WARNING"))
    info_count = len(violations) - error_count - warn_count

    summary = f"{len(violations)} violations: {error_count} errors, {warn_count} warnings, {info_count} info"
    return {"violations": violations, "summary": summary}
