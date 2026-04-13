"""Ontology Design Pattern (ODP) library service.

Loads patterns from two directories:
- data/patterns/odpa/  — curated patterns from ontologydesignpatterns.org (ODPA)
- data/patterns/user/  — user-uploaded patterns (persisted to disk)
"""

import json
import logging
from pathlib import Path

from app.config import DATA_DIR

logger = logging.getLogger("ontoboard.patterns")

ODPA_DIR = DATA_DIR / "patterns" / "odpa"
USER_DIR = DATA_DIR / "patterns" / "user"

# In-memory cache — loaded on first access
_patterns: list[dict] | None = None


def _ensure_dirs() -> None:
    """Create pattern directories if they don't exist."""
    ODPA_DIR.mkdir(parents=True, exist_ok=True)
    USER_DIR.mkdir(parents=True, exist_ok=True)


def _load_patterns_from_dir(directory: Path, source: str) -> list[dict]:
    """Load all JSON pattern files from a directory (including subdirectories)."""
    patterns = []
    if not directory.exists():
        return patterns
    for f in sorted(directory.rglob("*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
            data["source"] = source  # "odpa" or "user"
            data["file"] = str(f)
            # For user patterns, extract the username from the subdirectory
            if source == "user" and f.parent != directory:
                data["uploaded_by"] = f.parent.name
            patterns.append(data)
        except Exception as exc:
            logger.warning("Failed to load pattern %s: %s", f, exc)
    return patterns


def _load_all() -> list[dict]:
    """Load patterns from both directories."""
    global _patterns
    _ensure_dirs()
    odpa = _load_patterns_from_dir(ODPA_DIR, "odpa")
    user = _load_patterns_from_dir(USER_DIR, "user")
    _patterns = odpa + user
    return _patterns


def _get_patterns() -> list[dict]:
    """Return cached patterns or load from disk."""
    if _patterns is None:
        return _load_all()
    return _patterns


def reload_patterns() -> int:
    """Force reload patterns from disk. Returns count."""
    patterns = _load_all()
    return len(patterns)


def list_patterns() -> list[dict]:
    """Return pattern metadata (without full structure)."""
    return [{"id": p["id"], "name": p["name"], "description": p["description"],
             "category": p["category"], "class_count": len(p.get("classes", [])),
             "property_count": len(p.get("properties", [])),
             "source": p.get("source", "odpa"),
             "uploaded_by": p.get("uploaded_by", "")} for p in _get_patterns()]


def get_pattern(pattern_id: str) -> dict | None:
    """Get full pattern structure."""
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
    """Add a user-defined pattern — saved to data/patterns/user/{username}/ as JSON.

    If owl_content is provided, try to extract classes/properties from it.
    Otherwise create an empty pattern structure.
    """
    classes = []
    properties = []

    if owl_content:
        try:
            from rdflib import Graph, RDF, OWL, RDFS
            g = Graph()
            for fmt in ("xml", "turtle", "n3"):
                try:
                    g.parse(data=owl_content, format=fmt)
                    break
                except Exception:
                    continue

            for s in g.subjects(RDF.type, OWL.Class):
                label = str(s).split("#")[-1].split("/")[-1]
                for _, _, o in g.triples((s, RDFS.label, None)):
                    label = str(o)
                    break
                classes.append({"iri": str(s), "label": label})

            for s in g.subjects(RDF.type, OWL.ObjectProperty):
                label = str(s).split("#")[-1].split("/")[-1]
                for _, _, o in g.triples((s, RDFS.label, None)):
                    label = str(o)
                    break
                source = ""
                target = ""
                for _, _, o in g.triples((s, RDFS.domain, None)):
                    source = str(o)
                for _, _, o in g.triples((s, RDFS.range, None)):
                    target = str(o)
                properties.append({
                    "iri": str(s), "label": label,
                    "source": source, "target": target, "type": "object",
                })
        except Exception:
            pass

    new_pattern = {
        "id": pattern_id,
        "name": name,
        "description": description,
        "category": category,
        "scope": scope,
        "competency_questions": competency_questions,
        "references": references,
        "pattern_iri": pattern_iri,
        "classes": classes,
        "properties": properties,
    }

    # Save to disk in per-user subdirectory
    _ensure_dirs()
    user_dir = USER_DIR / username
    user_dir.mkdir(parents=True, exist_ok=True)
    dest = user_dir / f"{pattern_id}.json"
    dest.write_text(json.dumps(new_pattern, indent=2, ensure_ascii=False), encoding="utf-8")

    # Add to in-memory cache
    new_pattern["source"] = "user"
    new_pattern["uploaded_by"] = username
    new_pattern["file"] = str(dest)
    patterns = _get_patterns()
    patterns.append(new_pattern)

    return {
        "id": pattern_id, "name": name, "description": description,
        "category": category, "class_count": len(classes),
        "property_count": len(properties), "source": "user",
    }


def delete_pattern(pattern_id: str) -> bool:
    """Delete a user-added pattern (ODPA patterns cannot be deleted)."""
    patterns = _get_patterns()
    for p in patterns:
        if p["id"] == pattern_id:
            if p.get("source") == "odpa":
                return False  # Cannot delete ODPA patterns
            # Remove file
            file_path = Path(p.get("file", ""))
            if file_path.exists():
                file_path.unlink()
            patterns.remove(p)
            return True
    return False
