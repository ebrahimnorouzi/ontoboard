"""CSV import service — analyze, IRI generation, KG building with rdflib."""

import csv
import hashlib
import re
import uuid
from pathlib import Path

from rdflib import Graph, URIRef, Literal, Namespace, RDF, RDFS, OWL, XSD

from app.config import DATA_DIR


# ── CSV analysis ───────────────────────────────────────────────

def analyze_csv(file_path: Path) -> dict:
    """Analyze a CSV/TSV file: detect separator, columns, types, samples."""
    content = file_path.read_text(encoding="utf-8", errors="replace")
    first_line = content.split("\n")[0]
    sep = "\t" if "\t" in first_line else ","

    reader = csv.DictReader(content.splitlines(), delimiter=sep)
    rows = list(reader)

    columns = []
    for col_name in (reader.fieldnames or []):
        values = [r.get(col_name, "") for r in rows if r.get(col_name, "")]
        inferred = _infer_type(values[:50])
        unique = len(set(values))
        columns.append({
            "name": col_name,
            "inferred_type": inferred,
            "unique_count": unique,
            "sample_values": values[:5],
        })

    sample_rows = [dict(r) for r in rows[:5]]

    return {
        "filename": file_path.name,
        "separator": sep,
        "row_count": len(rows),
        "columns": columns,
        "sample_rows": sample_rows,
    }


def _infer_type(values: list[str]) -> str:
    """Infer the most likely type from sample values."""
    if not values:
        return "string"
    int_count = sum(1 for v in values if re.match(r'^-?\d+$', v))
    float_count = sum(1 for v in values if re.match(r'^-?\d+\.\d+$', v))
    uri_count = sum(1 for v in values if v.startswith("http://") or v.startswith("https://"))
    date_count = sum(1 for v in values if re.match(r'^\d{4}-\d{2}-\d{2}', v))

    total = len(values)
    if uri_count > total * 0.5:
        return "uri"
    if date_count > total * 0.5:
        return "date"
    if int_count > total * 0.7:
        return "integer"
    if float_count > total * 0.5:
        return "float"
    return "string"


# ── IRI generation ─────────────────────────────────────────────

def generate_iri(strategy: str, row: dict, index: int, base_iri: str, pattern: str | None = None) -> str:
    """Generate a unique IRI for an instance."""
    if strategy == "uuid":
        return f"{base_iri}/{uuid.uuid4()}"
    elif strategy == "hash":
        content = "|".join(str(v) for v in row.values())
        h = hashlib.sha256(content.encode()).hexdigest()[:12]
        return f"{base_iri}/{h}"
    elif strategy == "pattern" and pattern:
        iri = pattern
        for key, val in row.items():
            safe_val = re.sub(r'[^a-zA-Z0-9_-]', '_', str(val))
            iri = iri.replace(f"{{{key}}}", safe_val)
        return iri
    else:  # sequential
        return f"{base_iri}/ind_{index}"


# ── Preview KG triples ────────────────────────────────────────

def preview_kg(csv_path: Path, mappings: list[dict], iri_strategy: str,
               iri_pattern: str | None, base_iri: str, limit: int = 5) -> list[dict]:
    """Preview the first N triples that would be generated."""
    content = csv_path.read_text(encoding="utf-8", errors="replace")
    first_line = content.split("\n")[0]
    sep = "\t" if "\t" in first_line else ","
    reader = csv.DictReader(content.splitlines(), delimiter=sep)

    triples = []
    for i, row in enumerate(reader):
        if i >= limit:
            break
        ind_iri = generate_iri(iri_strategy, row, i, base_iri, iri_pattern)
        for m in mappings:
            col = m["column"]
            value = row.get(col, "")
            if not value:
                continue
            if m["mapping_type"] == "class_assertion":
                triples.append({"subject": ind_iri, "predicate": "rdf:type", "object": m["target_iri"]})
            elif m["mapping_type"] == "data_property":
                triples.append({"subject": ind_iri, "predicate": m["target_iri"], "object": f'"{value}"'})
            elif m["mapping_type"] == "object_property":
                triples.append({"subject": ind_iri, "predicate": m["target_iri"], "object": value})
            elif m["mapping_type"] == "annotation":
                triples.append({"subject": ind_iri, "predicate": m["target_iri"], "object": f'"{value}"'})

    return triples


# ── Build full KG ──────────────────────────────────────────────

def build_knowledge_graph(
    board_dir: Path,
    csv_file: str,
    mappings: list[dict],
    iri_strategy: str,
    iri_pattern: str | None,
    base_iri: str,
) -> dict:
    """Build a full knowledge graph from CSV data and mappings using rdflib."""
    csv_path = board_dir / "uploads" / csv_file
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV file not found: {csv_file}")

    content = csv_path.read_text(encoding="utf-8", errors="replace")
    first_line = content.split("\n")[0]
    sep = "\t" if "\t" in first_line else ","
    reader = csv.DictReader(content.splitlines(), delimiter=sep)

    g = Graph()
    g.bind("owl", OWL)
    g.bind("rdfs", RDFS)

    individuals_count = 0
    rows = list(reader)

    # Find class_assertion mappings to determine types
    class_mappings = [m for m in mappings if m["mapping_type"] == "class_assertion"]

    for i, row in enumerate(rows):
        ind_iri = URIRef(generate_iri(iri_strategy, row, i, base_iri, iri_pattern))
        g.add((ind_iri, RDF.type, OWL.NamedIndividual))
        individuals_count += 1

        for m in mappings:
            col = m["column"]
            value = row.get(col, "")
            if not value:
                continue

            target = URIRef(m["target_iri"])

            if m["mapping_type"] == "class_assertion":
                g.add((ind_iri, RDF.type, target))
            elif m["mapping_type"] == "data_property":
                datatype = _guess_datatype(value)
                g.add((ind_iri, target, Literal(value, datatype=datatype)))
            elif m["mapping_type"] == "object_property":
                # Value should be a URI or we create one
                if value.startswith("http://") or value.startswith("https://"):
                    g.add((ind_iri, target, URIRef(value)))
                else:
                    safe = re.sub(r'[^a-zA-Z0-9_-]', '_', value)
                    g.add((ind_iri, target, URIRef(f"{base_iri}/{safe}")))
            elif m["mapping_type"] == "annotation":
                g.add((ind_iri, target, Literal(value, lang="en")))

        # Auto-label from first text column
        label_value = None
        for m in mappings:
            if m["mapping_type"] in ("data_property", "annotation"):
                label_value = row.get(m["column"], "")
                if label_value:
                    break
        if label_value:
            g.add((ind_iri, RDFS.label, Literal(label_value, lang="en")))

    # Serialize to Turtle
    output_path = board_dir / "knowledge_graph.ttl"
    g.serialize(str(output_path), format="turtle")

    return {
        "triples_count": len(g),
        "individuals_count": individuals_count,
        "output_path": str(output_path),
        "preview": preview_kg(
            csv_path, mappings, iri_strategy, iri_pattern, base_iri, limit=3,
        ),
    }


def list_csv_files(board_dir: Path) -> list[str]:
    """List uploaded CSV/TSV files."""
    upload_dir = board_dir / "uploads"
    if not upload_dir.exists():
        return []
    return sorted(f.name for f in upload_dir.iterdir() if f.suffix in (".csv", ".tsv", ".txt"))


def _guess_datatype(value: str):
    """Guess XSD datatype for a literal value."""
    if re.match(r'^-?\d+$', value):
        return XSD.integer
    if re.match(r'^-?\d+\.\d+$', value):
        return XSD.float
    if re.match(r'^\d{4}-\d{2}-\d{2}', value):
        return XSD.date
    if value.lower() in ("true", "false"):
        return XSD.boolean
    return XSD.string
