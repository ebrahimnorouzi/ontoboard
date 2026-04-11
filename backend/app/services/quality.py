"""Quality evaluation service — OOPS!, OQuaRE metrics, SHACL validation."""

import json
import logging
import urllib.request
import urllib.error
from pathlib import Path

from rdflib import Graph, RDF, RDFS, OWL

from app.services.ontology import load_graph, get_ontology_statistics

logger = logging.getLogger("ontoboard.quality")


def run_oops_scan(board_dir: Path) -> dict:
    """Submit ontology to OOPS! pitfall scanner REST API."""
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        return {"error": "No OWL file", "pitfalls": []}

    owl_content = g.serialize(format="xml")

    # OOPS! REST API
    try:
        xml_request = f"""<?xml version="1.0" encoding="UTF-8"?>
<OOPSRequest>
<OntologyURI></OntologyURI>
<OntologyContent><![CDATA[{owl_content}]]></OntologyContent>
<Pitfalls></Pitfalls>
<OutputFormat>XML</OutputFormat>
</OOPSRequest>"""

        req = urllib.request.Request(
            "https://oops.linkeddata.es/rest",
            data=xml_request.encode("utf-8"),
            headers={"Content-Type": "application/xml"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = resp.read().decode("utf-8", errors="replace")
            pitfalls = _parse_oops_xml(result)
            return {"pitfalls": pitfalls, "total": len(pitfalls)}
    except Exception as exc:
        logger.warning("OOPS! scan failed: %s", exc)
        return {"error": str(exc), "pitfalls": []}


def calculate_oquare(board_dir: Path) -> dict:
    """Calculate OQuaRE-inspired quality metrics from ontology statistics."""
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        return {"error": "No OWL file"}

    stats = get_ontology_statistics(g)
    total_entities = stats["classes"] + stats["object_properties"] + stats["data_properties"] + stats["individuals"]

    # Simplified OQuaRE metrics
    structural = min(100, (stats["total_axioms"] / max(total_entities, 1)) * 25) if total_entities > 0 else 0
    functional = min(100, stats["classes"] * 10) if stats["classes"] > 0 else 0
    usability = _annotation_coverage(g) * 100

    overall = (structural + functional + usability) / 3

    return {
        "structural_score": round(structural, 1),
        "functional_score": round(functional, 1),
        "usability_score": round(usability, 1),
        "overall_score": round(overall, 1),
        "entity_count": total_entities,
        "axiom_count": stats["total_axioms"],
        "triple_count": stats["total_triples"],
    }


def check_registry_compliance(board_dir: Path, registry: str) -> list[dict]:
    """Check if ontology metadata meets registry requirements."""
    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        return [{"field": "owl_file", "status": "missing", "message": "No OWL file found"}]

    checks = []
    ont = None
    for s in g.subjects(RDF.type, OWL.Ontology):
        ont = s

    if not ont:
        return [{"field": "ontology", "status": "missing", "message": "No owl:Ontology declaration"}]

    # Common requirements
    _check_field(g, ont, RDFS.label, "label", checks)
    _check_field(g, ont, RDFS.comment, "description", checks)

    from rdflib.namespace import DC, DCTERMS
    _check_field(g, ont, DC.creator, "creator", checks)
    _check_field(g, ont, DCTERMS.license, "license", checks)

    if registry == "bioportal":
        _check_field(g, ont, OWL.versionInfo, "version", checks)
    elif registry == "ols":
        _check_field(g, ont, OWL.versionIRI, "versionIRI", checks)

    return checks


def _check_field(g, ont, pred, name, checks):
    values = list(g.objects(ont, pred))
    if values:
        checks.append({"field": name, "status": "ok", "message": str(values[0])[:60]})
    else:
        checks.append({"field": name, "status": "missing", "message": f"No {name} annotation found"})


def _annotation_coverage(g: Graph) -> float:
    """Fraction of named entities that have rdfs:label."""
    total = 0
    labeled = 0
    for rdf_type in (OWL.Class, OWL.ObjectProperty, OWL.DatatypeProperty):
        for s in g.subjects(RDF.type, rdf_type):
            from rdflib import BNode
            if isinstance(s, BNode):
                continue
            total += 1
            if list(g.objects(s, RDFS.label)):
                labeled += 1
    return labeled / max(total, 1)


def _parse_oops_xml(xml_text: str) -> list[dict]:
    """Parse OOPS! XML response into pitfall list."""
    pitfalls = []
    import re
    for match in re.finditer(r'<oops:hasName>(.*?)</oops:hasName>.*?<oops:hasDescription>(.*?)</oops:hasDescription>.*?<oops:hasImportanceLevel>(.*?)</oops:hasImportanceLevel>', xml_text, re.DOTALL):
        pitfalls.append({
            "name": match.group(1).strip(),
            "description": match.group(2).strip()[:200],
            "severity": match.group(3).strip(),
        })
    return pitfalls
