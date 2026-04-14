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
    """Load the board's OWL files into a single rdflib Graph.

    Loads ALL parseable OWL files in src/ontology/ (edit + release),
    merging them into one graph. This ensures metadata, annotations,
    prefixes, and class definitions are all available.

    Prefers -edit.owl for entity definitions, but also loads the release
    file for annotations and metadata that may only be in the full ontology.
    """
    ont_dir = board_dir / "src" / "ontology"
    owl_files = list(ont_dir.glob("*.owl")) if ont_dir.exists() else []
    if not owl_files:
        raise FileNotFoundError(f"No .owl file in {ont_dir}")

    g = Graph()
    loaded_any = False

    # Sort: -edit.owl first (primary), then others
    def _sort_key(f):
        if "-edit.owl" in f.name:
            return 0
        if "-idranges" in f.name:
            return 2  # load last (or skip)
        return 1

    for owl_file in sorted(owl_files, key=_sort_key):
        if "-idranges" in owl_file.name or owl_file.name.startswith("_"):
            continue
        parsed = False
        for fmt in ("xml", "turtle", "n3", "nt", "json-ld"):
            try:
                g.parse(str(owl_file), format=fmt)
                parsed = True
                loaded_any = True
                break
            except Exception:
                continue

        if not parsed:
            # Try ROBOT conversion for OWL Functional Syntax
            _try_robot_convert(owl_file, g)
            if len(g) > 0:
                loaded_any = True

    if not loaded_any:
        raise ValueError(f"Cannot parse any OWL file in {ont_dir}")

    return g


def _try_robot_convert(owl_file: Path, g: Graph) -> bool:
    """Try to convert an OWL file using ROBOT and parse the result."""
    import shutil
    import subprocess
    if not shutil.which("robot"):
        return False
    converted = owl_file.parent / f"_converted_{owl_file.stem}.owl"
    try:
        result = subprocess.run(
            ["robot", "convert", "-i", str(owl_file), "-o", str(converted), "--format", "owl"],
            capture_output=True, text=True, timeout=120,
            cwd=str(owl_file.parent),
        )
        if result.returncode == 0 and converted.exists():
            g.parse(str(converted), format="xml")
            converted.replace(owl_file)
            logger.info("Converted %s via ROBOT", owl_file.name)
            return True
    except Exception as exc:
        logger.warning("ROBOT convert error for %s: %s", owl_file.name, exc)
    finally:
        converted.unlink(missing_ok=True)
    return False


def load_full_graph(board_dir: Path) -> Graph:
    """Load all RDF files in the board (OWL + Turtle KGs)."""
    g = Graph()
    for ext in ("*.owl", "*.ttl", "*.rdf", "*.nt"):
        for f in board_dir.rglob(ext):
            if ".git" in str(f) or "tmp_" in f.name or f.name.startswith("_converted_"):
                continue
            parsed = False
            for fmt in ("xml", "turtle", "n3", "nt"):
                try:
                    g.parse(str(f), format=fmt)
                    parsed = True
                    break
                except Exception:
                    continue
            if not parsed:
                logger.warning("Failed to parse %s (unsupported format)", f)
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
