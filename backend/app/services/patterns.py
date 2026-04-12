"""Ontology Design Pattern (ODP) library service."""

from pathlib import Path


# Built-in patterns (minimal set, expandable)
BUILTIN_PATTERNS = [
    {
        "id": "part-of",
        "name": "Part-Of Pattern",
        "description": "Models mereological part-whole relationships",
        "category": "structural",
        "classes": [
            {"iri": "Whole", "label": "Whole"},
            {"iri": "Part", "label": "Part"},
        ],
        "properties": [
            {"iri": "hasPart", "label": "hasPart", "source": "Whole", "target": "Part", "type": "object"},
            {"iri": "isPartOf", "label": "isPartOf", "source": "Part", "target": "Whole", "type": "object"},
        ],
    },
    {
        "id": "quality-pattern",
        "name": "Quality/Attribute Pattern",
        "description": "Attaches quality/attribute to a bearer",
        "category": "structural",
        "classes": [
            {"iri": "Bearer", "label": "Bearer"},
            {"iri": "Quality", "label": "Quality"},
        ],
        "properties": [
            {"iri": "hasQuality", "label": "hasQuality", "source": "Bearer", "target": "Quality", "type": "object"},
        ],
    },
    {
        "id": "participation",
        "name": "Participation Pattern",
        "description": "Agent participates in a process/event",
        "category": "behavioral",
        "classes": [
            {"iri": "Agent", "label": "Agent"},
            {"iri": "Process", "label": "Process"},
            {"iri": "Role", "label": "Role"},
        ],
        "properties": [
            {"iri": "participatesIn", "label": "participatesIn", "source": "Agent", "target": "Process", "type": "object"},
            {"iri": "hasRole", "label": "hasRole", "source": "Agent", "target": "Role", "type": "object"},
        ],
    },
    {
        "id": "classification",
        "name": "Classification Pattern",
        "description": "Classifies entities into categories",
        "category": "structural",
        "classes": [
            {"iri": "Entity", "label": "Entity"},
            {"iri": "Category", "label": "Category"},
        ],
        "properties": [
            {"iri": "hasCategory", "label": "hasCategory", "source": "Entity", "target": "Category", "type": "object"},
        ],
    },
    {
        "id": "information-entity",
        "name": "Information Entity Pattern",
        "description": "Links information to its subject matter",
        "category": "information",
        "classes": [
            {"iri": "InformationEntity", "label": "Information Entity"},
            {"iri": "AboutEntity", "label": "About Entity"},
        ],
        "properties": [
            {"iri": "isAbout", "label": "isAbout", "source": "InformationEntity", "target": "AboutEntity", "type": "object"},
        ],
    },
]


def list_patterns() -> list[dict]:
    """Return pattern metadata (without full structure)."""
    return [{"id": p["id"], "name": p["name"], "description": p["description"],
             "category": p["category"], "class_count": len(p["classes"]),
             "property_count": len(p["properties"])} for p in BUILTIN_PATTERNS]


def get_pattern(pattern_id: str) -> dict | None:
    """Get full pattern structure."""
    for p in BUILTIN_PATTERNS:
        if p["id"] == pattern_id:
            return p
    return None


def apply_pattern(board_dir: Path, pattern_id: str, base_iri: str, x: float = 100, y: float = 100) -> dict:
    """Convert a pattern into a CanvasState with positioned nodes."""
    pattern = get_pattern(pattern_id)
    if not pattern:
        return {"classes": [], "properties": [], "individuals": []}

    classes = []
    for i, cls in enumerate(pattern["classes"]):
        classes.append({
            "id": f"{base_iri}#{cls['iri']}",
            "iri": f"{base_iri}#{cls['iri']}",
            "label": cls["label"],
            "x": x + (i % 3) * 220,
            "y": y + (i // 3) * 140,
            "w": 160, "h": 60, "color": "violet",
        })

    properties = []
    for prop in pattern["properties"]:
        properties.append({
            "id": f"{base_iri}#{prop['iri']}",
            "iri": f"{base_iri}#{prop['iri']}",
            "label": prop["label"],
            "source_id": f"{base_iri}#{prop['source']}",
            "target_id": f"{base_iri}#{prop['target']}",
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
                pattern_iri: str = "", owl_content: str | None = None) -> dict:
    """Add a user-defined pattern to the in-memory library.

    If owl_content is provided, try to extract classes/properties from it.
    Otherwise create an empty pattern structure.
    """
    classes = []
    properties = []

    if owl_content:
        # Try to parse OWL and extract classes/properties
        try:
            from rdflib import Graph, RDF, OWL, RDFS
            g = Graph()
            # Try multiple formats
            for fmt in ("xml", "turtle", "n3"):
                try:
                    g.parse(data=owl_content, format=fmt)
                    break
                except Exception:
                    continue

            # Extract classes
            for s in g.subjects(RDF.type, OWL.Class):
                label = str(s).split("#")[-1].split("/")[-1]
                for _, _, o in g.triples((s, RDFS.label, None)):
                    label = str(o)
                    break
                classes.append({"iri": str(s).split("#")[-1], "label": label})

            # Extract object properties
            for s in g.subjects(RDF.type, OWL.ObjectProperty):
                label = str(s).split("#")[-1].split("/")[-1]
                for _, _, o in g.triples((s, RDFS.label, None)):
                    label = str(o)
                    break
                source = ""
                target = ""
                for _, _, o in g.triples((s, RDFS.domain, None)):
                    source = str(o).split("#")[-1]
                for _, _, o in g.triples((s, RDFS.range, None)):
                    target = str(o).split("#")[-1]
                properties.append({
                    "iri": str(s).split("#")[-1], "label": label,
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
    BUILTIN_PATTERNS.append(new_pattern)

    return {
        "id": pattern_id, "name": name, "description": description,
        "category": category, "class_count": len(classes),
        "property_count": len(properties),
    }
