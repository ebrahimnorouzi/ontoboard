"""
Harvest ODP patterns from the cloned ODPA patterns-repository.

For each directory containing an .owl file:
  1. Parse the OWL file with rdflib to extract classes, properties, and metadata
  2. Read the .md file for description, scenarios, domain info
  3. Generate a metadata.json with enriched fields
  4. Copy the OWL file as pattern.owl to the seed directory

Usage:
  python harvest_patterns.py
"""

import json
import re
import shutil
import sys
from pathlib import Path

try:
    from rdflib import Graph, Namespace, RDF, RDFS, OWL, URIRef, BNode, Literal
except ImportError:
    print("rdflib not installed. Install with: pip install rdflib")
    sys.exit(1)

REPO_DIR = Path(__file__).parent / "patterns-repository"
SEED_DIR = Path(__file__).parent / "backend" / "seed" / "patterns"

# ODPA annotation schema namespace
CPANN = Namespace("http://www.ontologydesignpatterns.org/schemas/cpannotationschema.owl#")

# Existing patterns to skip (already curated)
EXISTING = {d.name for d in SEED_DIR.iterdir() if d.is_dir()} if SEED_DIR.exists() else set()

# Category inference from directory name and content
DOMAIN_KEYWORDS = {
    "bio": "biology",
    "species": "biology",
    "aquatic": "biology",
    "habitat": "biology",
    "taxon": "biology",
    "food": "food-science",
    "recipe": "food-science",
    "smart": "iot",
    "sensor": "iot",
    "actuat": "iot",
    "chess": "games",
    "airline": "transportation",
    "transport": "transportation",
    "vessel": "fisheries",
    "gear": "fisheries",
    "catch": "fisheries",
    "fish": "fisheries",
    "pollution": "environment",
    "climat": "environment",
    "weather": "environment",
    "privacy": "legal",
    "policy": "legal",
    "invoice": "commerce",
    "price": "commerce",
    "pharma": "healthcare",
    "hazard": "safety",
    "news": "media",
    "report": "media",
    "microblog": "social-media",
    "digital": "digital-media",
    "video": "digital-media",
    "music": "music",
    "score": "music",
    "computer": "computing",
    "algorithm": "computing",
    "software": "computing",
    "metric": "computing",
    "material": "engineering",
    "provenance": "data-management",
    "tagging": "data-management",
    "archive": "information-science",
    "born_digital": "information-science",
    "person": "social",
    "community": "social",
    "ethnic": "social",
    "resident": "social",
    "logistics": "logistics",
}

CATEGORY_KEYWORDS = {
    "part": "structural",
    "compon": "structural",
    "constitu": "structural",
    "collect": "structural",
    "bag": "structural",
    "list": "structural",
    "set": "structural",
    "sequence": "structural",
    "region": "structural",
    "partition": "structural",
    "role": "behavioral",
    "agent": "behavioral",
    "particip": "behavioral",
    "action": "behavioral",
    "event": "behavioral",
    "plan": "behavioral",
    "task": "behavioral",
    "transition": "behavioral",
    "controlflow": "behavioral",
    "process": "behavioral",
    "move": "behavioral",
    "execution": "behavioral",
    "situation": "conceptual",
    "description": "conceptual",
    "classif": "conceptual",
    "concept": "conceptual",
    "intension": "conceptual",
    "topic": "conceptual",
    "template": "conceptual",
    "criterion": "conceptual",
    "parameter": "conceptual",
    "type": "conceptual",
    "time": "temporal",
    "temporal": "temporal",
    "periodic": "temporal",
    "interval": "temporal",
    "recurrent": "temporal",
    "spatial": "spatial",
    "place": "spatial",
    "trajectory": "spatial",
    "observation": "observation",
    "sensing": "observation",
    "experience": "observation",
    "detect": "observation",
    "information": "information",
    "represent": "information",
    "communic": "information",
    "realization": "information",
}


def slugify(name: str) -> str:
    """Convert a directory name to a kebab-case ID."""
    s = re.sub(r'[^a-zA-Z0-9]+', '-', name).strip('-').lower()
    s = re.sub(r'-+', '-', s)
    return s


def infer_domain(dirname: str, description: str) -> str:
    """Infer domain from directory name and description."""
    text = (dirname + " " + description).lower()
    for kw, domain in DOMAIN_KEYWORDS.items():
        if kw in text:
            return domain
    return "general"


def infer_category(dirname: str, description: str) -> str:
    """Infer category from directory name and description."""
    text = (dirname + " " + description).lower()
    for kw, cat in CATEGORY_KEYWORDS.items():
        if kw in text:
            return cat
    return "structural"


def extract_owl_metadata(owl_path: Path) -> dict:
    """Parse an OWL file and extract classes, properties, and annotations."""
    g = Graph()
    try:
        # Try RDF/XML first, then Turtle
        try:
            g.parse(str(owl_path), format="xml")
        except Exception:
            g.parse(str(owl_path), format="turtle")
    except Exception as e:
        return {"error": str(e), "classes": [], "properties": []}

    # Extract ontology-level annotations
    intent = ""
    scenarios = ""
    cqs = ""
    ont_comment = ""

    for s in g.subjects(RDF.type, OWL.Ontology):
        for _, _, o in g.triples((s, CPANN.hasIntent, None)):
            intent = str(o).strip()
        for _, _, o in g.triples((s, CPANN.scenarios, None)):
            scenarios += str(o).strip() + " "
        for _, _, o in g.triples((s, CPANN.coversRequirements, None)):
            cqs += str(o).strip() + "; "
        for _, _, o in g.triples((s, RDFS.comment, None)):
            ont_comment = str(o).strip()
        # Also check hasConsequences, etc.
        for _, _, o in g.triples((s, CPANN.hasConsequences, None)):
            if not scenarios:
                scenarios = str(o).strip()

    # Extract classes
    classes = []
    for s in g.subjects(RDF.type, OWL.Class):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        label = iri.split("#")[-1].split("/")[-1]
        for _, _, o in g.triples((s, RDFS.label, None)):
            label = str(o)
            break
        classes.append({"iri": iri, "label": label})

    # Extract object properties
    properties = []
    for s in g.subjects(RDF.type, OWL.ObjectProperty):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        label = iri.split("#")[-1].split("/")[-1]
        for _, _, o in g.triples((s, RDFS.label, None)):
            label = str(o)
            break
        source, target = "", ""
        for _, _, o in g.triples((s, RDFS.domain, None)):
            if not isinstance(o, BNode):
                source = str(o)
        for _, _, o in g.triples((s, RDFS.range, None)):
            if not isinstance(o, BNode):
                target = str(o)
        properties.append({
            "iri": iri, "label": label,
            "source": source, "target": target, "type": "object",
        })

    # Extract datatype properties
    for s in g.subjects(RDF.type, OWL.DatatypeProperty):
        if isinstance(s, BNode):
            continue
        iri = str(s)
        label = iri.split("#")[-1].split("/")[-1]
        for _, _, o in g.triples((s, RDFS.label, None)):
            label = str(o)
            break
        source, target = "", ""
        for _, _, o in g.triples((s, RDFS.domain, None)):
            if not isinstance(o, BNode):
                source = str(o)
        for _, _, o in g.triples((s, RDFS.range, None)):
            if not isinstance(o, BNode):
                target = str(o)
        properties.append({
            "iri": iri, "label": label,
            "source": source, "target": target, "type": "data",
        })

    return {
        "intent": intent,
        "scenarios": scenarios.strip(),
        "competency_questions": cqs.strip().rstrip(";"),
        "comment": ont_comment,
        "classes": classes,
        "properties": properties,
        "triple_count": len(g),
    }


def read_md_description(pattern_dir: Path) -> str:
    """Read the .md file(s) in a pattern dir for description text."""
    for md in sorted(pattern_dir.glob("*.md")):
        if md.name == "index.md":
            continue
        try:
            text = md.read_text(encoding="utf-8", errors="replace")
            # Extract first meaningful paragraph (skip YAML frontmatter)
            lines = text.split("\n")
            body = []
            in_frontmatter = False
            for line in lines:
                if line.strip() == "---":
                    in_frontmatter = not in_frontmatter
                    continue
                if in_frontmatter:
                    continue
                if line.strip().startswith("#"):
                    continue
                if line.strip():
                    body.append(line.strip())
            desc = " ".join(body[:10])  # First ~10 lines
            # Clean up markdown artifacts
            desc = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', desc)
            desc = re.sub(r'[*_`]', '', desc)
            desc = re.sub(r'\s+', ' ', desc).strip()
            if len(desc) > 500:
                desc = desc[:497] + "..."
            return desc
        except Exception:
            continue
    return ""


def find_main_owl(pattern_dir: Path) -> Path | None:
    """Find the main OWL file in a pattern directory (skip imports like DUL.owl)."""
    owl_files = list(pattern_dir.glob("*.owl"))
    if not owl_files:
        return None

    # Prefer files that match the directory name
    dirname_lower = pattern_dir.name.lower().replace("_", "").replace("-", "")
    for f in owl_files:
        fname_lower = f.stem.lower().replace("_", "").replace("-", "")
        if fname_lower == dirname_lower or dirname_lower.startswith(fname_lower):
            return f

    # Skip known imports
    skip_names = {"dul.owl", "cpannotationschema.owl", "ex1.owl"}
    candidates = [f for f in owl_files if f.name.lower() not in skip_names]
    if candidates:
        # Return the largest one (likely the main pattern)
        return max(candidates, key=lambda f: f.stat().st_size)

    return owl_files[0]


def process_pattern(pattern_dir: Path) -> dict | None:
    """Process a single pattern directory into OntoBoard format."""
    owl_file = find_main_owl(pattern_dir)
    if not owl_file:
        return None

    dirname = pattern_dir.name
    pattern_id = slugify(dirname)

    # Skip if already exists in seed
    if pattern_id in EXISTING:
        return None

    # Extract OWL metadata
    owl_meta = extract_owl_metadata(owl_file)
    if owl_meta.get("error"):
        # Try other OWL files in the directory
        for alt in pattern_dir.glob("*.owl"):
            if alt != owl_file:
                owl_meta = extract_owl_metadata(alt)
                if not owl_meta.get("error"):
                    owl_file = alt
                    break

    # Skip patterns with no classes or properties (likely not useful)
    if not owl_meta.get("classes") and not owl_meta.get("properties"):
        return None

    # Read markdown description
    md_desc = read_md_description(pattern_dir)

    # Build description: prefer OWL intent > OWL comment > markdown
    description = owl_meta.get("intent") or owl_meta.get("comment") or md_desc
    if not description:
        description = f"Ontology design pattern for {dirname.replace('_', ' ').replace('-', ' ').lower()}."

    # Human-readable name
    name = dirname.replace("_", " ").replace("-", " ")
    name = re.sub(r'\s+', ' ', name).strip()
    # Title-case but keep acronyms
    words = name.split()
    name = " ".join(w if w.isupper() and len(w) > 1 else w.capitalize() for w in words)
    if not name.lower().endswith("pattern"):
        name += " Pattern"

    # Infer domain and category
    full_text = f"{dirname} {description} {owl_meta.get('scenarios', '')} {md_desc}"
    domain = infer_domain(dirname, full_text)
    category = infer_category(dirname, full_text)

    # Build competency questions
    cqs = owl_meta.get("competency_questions", "")

    # Build source URL
    source_url = f"https://ontologydesignpatterns.org/wiki/Submissions:{dirname}"
    pattern_iri = ""
    # Try to find the ontology IRI from the OWL file
    try:
        g = Graph()
        try:
            g.parse(str(owl_file), format="xml")
        except Exception:
            g.parse(str(owl_file), format="turtle")
        for s in g.subjects(RDF.type, OWL.Ontology):
            pattern_iri = str(s)
            break
    except Exception:
        pass

    metadata = {
        "id": pattern_id,
        "name": name,
        "description": description,
        "category": category,
        "domain": domain,
        "scenarios": owl_meta.get("scenarios", ""),
        "competency_questions": cqs,
        "pattern_iri": pattern_iri,
        "source": source_url,
        "references": source_url,
        "class_count": len(owl_meta.get("classes", [])),
        "property_count": len(owl_meta.get("properties", [])),
    }

    return {
        "metadata": metadata,
        "owl_file": owl_file,
        "pattern_id": pattern_id,
    }


def main():
    if not REPO_DIR.exists():
        print(f"ERROR: patterns-repository not found at {REPO_DIR}")
        sys.exit(1)

    SEED_DIR.mkdir(parents=True, exist_ok=True)

    # Find all pattern directories with OWL files
    candidates = []
    for d in sorted(REPO_DIR.iterdir()):
        if not d.is_dir():
            continue
        if d.name.startswith((".", "_")):
            continue
        if list(d.glob("*.owl")):
            candidates.append(d)

    print(f"Found {len(candidates)} directories with OWL files")
    print(f"Existing patterns to skip: {len(EXISTING)} ({', '.join(sorted(EXISTING))})")

    harvested = 0
    skipped = 0
    errors = 0

    for pattern_dir in candidates:
        try:
            result = process_pattern(pattern_dir)
            if result is None:
                skipped += 1
                continue

            pid = result["pattern_id"]
            dest = SEED_DIR / pid
            dest.mkdir(parents=True, exist_ok=True)

            # Write metadata.json
            meta_path = dest / "metadata.json"
            meta_path.write_text(
                json.dumps(result["metadata"], indent=2, ensure_ascii=False),
                encoding="utf-8",
            )

            # Copy OWL file as pattern.owl
            shutil.copy2(result["owl_file"], dest / "pattern.owl")

            harvested += 1
            print(f"  + {pid} ({result['metadata']['class_count']}C, {result['metadata']['property_count']}P, domain={result['metadata']['domain']})")

        except Exception as e:
            errors += 1
            print(f"  ! ERROR processing {pattern_dir.name}: {e}")

    print(f"\nDone: {harvested} harvested, {skipped} skipped, {errors} errors")
    print(f"Total patterns in seed: {len(list(SEED_DIR.iterdir()))}")


if __name__ == "__main__":
    main()
