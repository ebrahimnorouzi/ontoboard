"""Ontology Design Pattern (ODP) library service.

Storage layout:
  data/patterns/odpa/{pattern-id}/
    metadata.json   — name, description, category, references, etc.
    pattern.owl     — OWL/RDF ontology file (or .ttl)

  data/patterns/user/{username}/{pattern-id}/
    metadata.json   — same format as ODPA
    pattern.owl     — uploaded ontology file
"""

import json
import logging
from pathlib import Path

from app.config import DATA_DIR

logger = logging.getLogger("ontoboard.patterns")


def _odpa_dir() -> Path:
    return DATA_DIR / "patterns" / "odpa"


def _user_dir() -> Path:
    return DATA_DIR / "patterns" / "user"

_patterns: list[dict] | None = None


SEED_DIR = Path(__file__).parent.parent.parent / "seed" / "patterns"


def _ensure_dirs() -> None:
    """Create pattern directories and seed ODPA patterns if empty."""
    _odpa_dir().mkdir(parents=True, exist_ok=True)
    _user_dir().mkdir(parents=True, exist_ok=True)

    # Seed ODPA patterns from the bundled seed directory (first run only)
    if SEED_DIR.exists() and not any(_odpa_dir().iterdir()):
        import shutil
        for src in SEED_DIR.iterdir():
            if src.is_dir():
                dst = _odpa_dir() / src.name
                if not dst.exists():
                    shutil.copytree(src, dst)
        logger.info("Seeded %d ODPA patterns from %s", len(list(_odpa_dir().iterdir())), SEED_DIR)


def _parse_owl_file(owl_path: Path) -> tuple[list[dict], list[dict]]:
    """Parse an OWL/TTL file and extract classes + properties."""
    classes = []
    properties = []
    try:
        from rdflib import Graph, RDF, OWL, RDFS
        g = Graph()
        ext = owl_path.suffix.lower()
        fmt = "turtle" if ext in (".ttl", ".n3") else "xml"
        g.parse(str(owl_path), format=fmt)

        for s in g.subjects(RDF.type, OWL.Class):
            from rdflib import BNode
            if isinstance(s, BNode):
                continue
            label = str(s).split("#")[-1].split("/")[-1]
            for _, _, o in g.triples((s, RDFS.label, None)):
                label = str(o)
                break
            classes.append({"iri": str(s), "label": label})

        for s in g.subjects(RDF.type, OWL.ObjectProperty):
            from rdflib import BNode
            if isinstance(s, BNode):
                continue
            label = str(s).split("#")[-1].split("/")[-1]
            for _, _, o in g.triples((s, RDFS.label, None)):
                label = str(o)
                break
            source, target = "", ""
            for _, _, o in g.triples((s, RDFS.domain, None)):
                source = str(o)
            for _, _, o in g.triples((s, RDFS.range, None)):
                target = str(o)
            properties.append({
                "iri": str(s), "label": label,
                "source": source, "target": target, "type": "object",
            })

        for s in g.subjects(RDF.type, OWL.DatatypeProperty):
            from rdflib import BNode
            if isinstance(s, BNode):
                continue
            label = str(s).split("#")[-1].split("/")[-1]
            for _, _, o in g.triples((s, RDFS.label, None)):
                label = str(o)
                break
            source, target = "", ""
            for _, _, o in g.triples((s, RDFS.domain, None)):
                source = str(o)
            for _, _, o in g.triples((s, RDFS.range, None)):
                target = str(o)
            properties.append({
                "iri": str(s), "label": label,
                "source": source, "target": target, "type": "data",
            })
    except Exception as exc:
        logger.warning("Failed to parse OWL file %s: %s", owl_path, exc)

    return classes, properties


def _load_pattern_dir(pattern_dir: Path, source: str, uploaded_by: str = "") -> dict | None:
    """Load a single pattern from its directory (metadata.json + pattern.owl/ttl)."""
    meta_file = pattern_dir / "metadata.json"
    if not meta_file.exists():
        return None

    try:
        meta = json.loads(meta_file.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("Failed to read metadata %s: %s", meta_file, exc)
        return None

    # Find the ontology file
    owl_file = None
    for ext in ("*.owl", "*.ttl", "*.rdf", "*.xml"):
        files = list(pattern_dir.glob(ext))
        if files:
            owl_file = files[0]
            break

    # Parse classes/properties from the OWL file
    classes, properties = [], []
    if owl_file:
        classes, properties = _parse_owl_file(owl_file)
        meta["ontology_file"] = owl_file.name

    meta["classes"] = classes
    meta["properties"] = properties
    meta["source"] = source
    meta["dir"] = str(pattern_dir)
    if uploaded_by:
        meta["uploaded_by"] = uploaded_by
    if "id" not in meta:
        meta["id"] = pattern_dir.name

    return meta


def _load_patterns_from_dir(directory: Path, source: str) -> list[dict]:
    """Load all patterns from a directory (each subdirectory is a pattern)."""
    patterns = []
    if not directory.exists():
        return patterns

    for sub in sorted(directory.iterdir()):
        if not sub.is_dir():
            continue

        if source == "user":
            # User dir: data/patterns/user/{username}/{pattern-id}/
            for pattern_dir in sorted(sub.iterdir()):
                if pattern_dir.is_dir():
                    p = _load_pattern_dir(pattern_dir, source, uploaded_by=sub.name)
                    if p:
                        patterns.append(p)
        else:
            # ODPA dir: data/patterns/odpa/{pattern-id}/
            p = _load_pattern_dir(sub, source)
            if p:
                patterns.append(p)

    return patterns


def _load_all() -> list[dict]:
    global _patterns
    _ensure_dirs()
    odpa = _load_patterns_from_dir(_odpa_dir(), "odpa")
    user = _load_patterns_from_dir(_user_dir(), "user")
    _patterns = odpa + user
    return _patterns


def _get_patterns() -> list[dict]:
    if _patterns is None:
        return _load_all()
    return _patterns


def reload_patterns() -> int:
    return len(_load_all())


def list_patterns() -> list[dict]:
    return [{"id": p["id"], "name": p["name"], "description": p.get("description", ""),
             "category": p.get("category", "structural"),
             "class_count": len(p.get("classes", [])),
             "property_count": len(p.get("properties", [])),
             "source": p.get("source", "odpa"),
             "uploaded_by": p.get("uploaded_by", ""),
             "ontology_file": p.get("ontology_file", "")} for p in _get_patterns()]


def get_pattern(pattern_id: str) -> dict | None:
    for p in _get_patterns():
        if p["id"] == pattern_id:
            return p
    return None


def apply_pattern(board_dir: Path, pattern_id: str, base_iri: str, x: float = 100, y: float = 100) -> dict:
    """Convert a pattern into a CanvasState with positioned nodes."""
    pattern = get_pattern(pattern_id)
    if not pattern:
        return {"classes": [], "properties": [], "individuals": []}

    classes = []
    for i, cls in enumerate(pattern.get("classes", [])):
        local_name = cls["iri"].split("#")[-1].split("/")[-1]
        classes.append({
            "id": f"{base_iri}#{local_name}",
            "iri": f"{base_iri}#{local_name}",
            "label": cls["label"],
            "x": x + (i % 3) * 220,
            "y": y + (i // 3) * 140,
            "w": 160, "h": 60, "color": "violet",
        })

    properties = []
    for prop in pattern.get("properties", []):
        local_name = prop["iri"].split("#")[-1].split("/")[-1]
        source_local = prop["source"].split("#")[-1].split("/")[-1] if prop.get("source") else ""
        target_local = prop["target"].split("#")[-1].split("/")[-1] if prop.get("target") else ""
        properties.append({
            "id": f"{base_iri}#{local_name}",
            "iri": f"{base_iri}#{local_name}",
            "label": prop["label"],
            "source_id": f"{base_iri}#{source_local}" if source_local else "",
            "target_id": f"{base_iri}#{target_local}" if target_local else "",
            "property_type": prop.get("type", "object"),
        })

    return {
        "classes": classes,
        "properties": properties,
        "individuals": [],
        "pattern_id": pattern_id,
    }


def add_pattern(*, pattern_id: str, name: str, description: str = "",
                category: str = "structural", scope: str = "",
                competency_questions: str = "", references: str = "",
                pattern_iri: str = "", owl_content: str | None = None,
                username: str = "anonymous") -> dict:
    """Add a user-defined pattern — saves to data/patterns/user/{username}/{pattern-id}/

    Always saves both metadata.json and pattern.owl (or .ttl).
    """
    _ensure_dirs()
    pattern_dir = _user_dir() / username / pattern_id
    pattern_dir.mkdir(parents=True, exist_ok=True)

    # Save metadata
    meta = {
        "id": pattern_id,
        "name": name,
        "description": description,
        "category": category,
        "scope": scope,
        "competency_questions": competency_questions,
        "references": references,
        "pattern_iri": pattern_iri,
    }
    (pattern_dir / "metadata.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")

    # Save ontology file
    classes, properties = [], []
    if owl_content:
        # Detect format from content
        ext = ".owl"
        if owl_content.strip().startswith("@prefix") or owl_content.strip().startswith("@base"):
            ext = ".ttl"
        owl_file = pattern_dir / f"pattern{ext}"
        owl_file.write_text(owl_content, encoding="utf-8")
        classes, properties = _parse_owl_file(owl_file)
        meta["ontology_file"] = owl_file.name
    else:
        # Generate a minimal OWL file from metadata
        owl_iri = pattern_iri or f"http://example.org/patterns/{pattern_id}"
        owl_content = (
            f'<?xml version="1.0"?>\n'
            f'<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"\n'
            f'         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"\n'
            f'         xmlns:owl="http://www.w3.org/2002/07/owl#"\n'
            f'         xml:base="{owl_iri}">\n'
            f'  <owl:Ontology rdf:about="{owl_iri}">\n'
            f'    <rdfs:label>{name}</rdfs:label>\n'
            f'    <rdfs:comment>{description}</rdfs:comment>\n'
            f'  </owl:Ontology>\n'
            f'</rdf:RDF>\n'
        )
        (pattern_dir / "pattern.owl").write_text(owl_content, encoding="utf-8")
        meta["ontology_file"] = "pattern.owl"

    # Add to in-memory cache
    full = {**meta, "classes": classes, "properties": properties,
            "source": "user", "uploaded_by": username, "dir": str(pattern_dir)}
    patterns = _get_patterns()
    patterns.append(full)

    return {
        "id": pattern_id, "name": name, "description": description,
        "category": category, "class_count": len(classes),
        "property_count": len(properties), "source": "user",
        "uploaded_by": username, "ontology_file": meta.get("ontology_file", ""),
    }


def delete_pattern(pattern_id: str) -> bool:
    """Delete a user-added pattern (ODPA patterns cannot be deleted)."""
    import shutil
    patterns = _get_patterns()
    for p in patterns:
        if p["id"] == pattern_id:
            if p.get("source") == "odpa":
                return False
            # Remove directory
            pattern_dir = Path(p.get("dir", ""))
            if pattern_dir.exists() and pattern_dir.is_dir():
                shutil.rmtree(pattern_dir)
            patterns.remove(p)
            return True
    return False
