"""Version management service — versionIRI, version.txt, semantic versioning."""

import re
from pathlib import Path

from rdflib import Graph, URIRef, Literal, RDF, OWL

from app.services.ontology import load_graph


def get_version_info(board_dir: Path) -> dict:
    """Read version info from ontology + version.txt."""
    g = load_graph(board_dir)
    version_iri = None
    version = None
    prior_version = None

    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, OWL.versionIRI):
            version_iri = str(o)
        for o in g.objects(s, OWL.versionInfo):
            version = str(o)
        for o in g.objects(s, OWL.priorVersion):
            prior_version = str(o)

    # Also check version.txt
    vfile = board_dir / "version.txt"
    if vfile.exists() and not version:
        version = vfile.read_text().strip()

    return {"version_iri": version_iri, "version": version, "prior_version": prior_version}


def set_version(board_dir: Path, version_iri: str | None = None, version: str | None = None,
                bump: str | None = None) -> dict:
    """Update version info. If bump is set, auto-increment."""
    g = load_graph(board_dir)

    ont_node = None
    for s in g.subjects(RDF.type, OWL.Ontology):
        ont_node = s
        break
    if not ont_node:
        return get_version_info(board_dir)

    # Handle bump
    if bump and bump in ("major", "minor", "patch"):
        current = None
        for o in g.objects(ont_node, OWL.versionInfo):
            current = str(o)
        if not current:
            vfile = board_dir / "version.txt"
            if vfile.exists():
                current = vfile.read_text().strip()
        if not current:
            current = "0.0.0"
        version = _bump_version(current, bump)

    if version_iri:
        g.remove((ont_node, OWL.versionIRI, None))
        g.add((ont_node, OWL.versionIRI, URIRef(version_iri)))

    if version:
        g.remove((ont_node, OWL.versionInfo, None))
        g.add((ont_node, OWL.versionInfo, Literal(version)))
        # Also update version.txt
        (board_dir / "version.txt").write_text(version)

    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")

    return get_version_info(board_dir)


def _bump_version(current: str, bump_type: str) -> str:
    """Semantic version bump."""
    match = re.match(r"(\d+)\.(\d+)\.(\d+)", current)
    if not match:
        return "0.1.0"
    major, minor, patch = int(match.group(1)), int(match.group(2)), int(match.group(3))
    if bump_type == "major":
        return f"{major + 1}.0.0"
    elif bump_type == "minor":
        return f"{major}.{minor + 1}.0"
    else:
        return f"{major}.{minor}.{patch + 1}"
