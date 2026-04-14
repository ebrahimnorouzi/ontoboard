"""CSV import service — ROBOT Template Builder.

Analyzes CSV/TSV files, extracts ontology entities for mapping,
generates ROBOT template TSV files, and builds KG via ROBOT.
"""

import csv
import hashlib
import re
import uuid
from pathlib import Path

from rdflib import Graph, RDF, RDFS, OWL

from app.services.robot import run_robot, _posix_rel


# ── Directory helpers ─────────────────────────────────────────

def _kg_dir(board_dir: Path) -> Path:
    return board_dir / "kg"


def _uploads_dir(board_dir: Path) -> Path:
    d = _kg_dir(board_dir) / "uploads"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _templates_dir(board_dir: Path) -> Path:
    d = _kg_dir(board_dir) / "templates"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _output_dir(board_dir: Path) -> Path:
    d = _kg_dir(board_dir) / "output"
    d.mkdir(parents=True, exist_ok=True)
    return d


# ── 1. Analyze CSV ────────────────────────────────────────────

def analyze_csv(board_dir: Path, filename: str) -> dict:
    """Parse an uploaded CSV/TSV, detect columns, sample data.

    Returns: {filename, columns: [{name, sample_values, suggested_type}],
              row_count, delimiter}
    """
    file_path = _uploads_dir(board_dir) / filename
    if not file_path.exists():
        raise FileNotFoundError(f"File not found: {filename}")

    content = file_path.read_text(encoding="utf-8", errors="replace")
    first_line = content.split("\n")[0]
    delimiter = "\t" if "\t" in first_line else ","

    reader = csv.DictReader(content.splitlines(), delimiter=delimiter)
    rows = list(reader)

    columns = []
    for col_name in (reader.fieldnames or []):
        values = [r.get(col_name, "") for r in rows if r.get(col_name, "")]
        suggested = _suggest_directive_type(col_name, values[:50])
        columns.append({
            "name": col_name,
            "sample_values": values[:5],
            "suggested_type": suggested,
        })

    return {
        "filename": filename,
        "columns": columns,
        "row_count": len(rows),
        "delimiter": delimiter,
        "sample_rows": [dict(r) for r in rows[:5]],
    }


def _suggest_directive_type(col_name: str, values: list[str]) -> str:
    """Suggest a ROBOT directive type based on column name and values."""
    name_lower = col_name.lower().strip()

    if name_lower in ("id", "iri", "uri", "identifier"):
        return "ID"
    if name_lower in ("type", "class", "rdf:type", "rdf type"):
        return "TYPE"
    if name_lower in ("label", "name", "rdfs:label", "title"):
        return "A rdfs:label"
    if name_lower in ("comment", "description", "rdfs:comment", "definition"):
        return "A rdfs:comment"

    # Check if values look like URIs
    if values:
        uri_count = sum(1 for v in values if v.startswith("http://") or v.startswith("https://"))
        if uri_count > len(values) * 0.5:
            return "I"

    return "A"


# ── 2. Get ontology entities ─────────────────────────────────

def _get_label(g: Graph, subject) -> str:
    """Get rdfs:label for a subject, falling back to local name."""
    for o in g.objects(subject, RDFS.label):
        return str(o)
    # Fallback: extract local name from IRI
    iri = str(subject)
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def get_ontology_entities(board_dir: Path) -> dict:
    """Return all classes, object properties, data properties,
    annotation properties from the board's ontology.

    Returns: {classes: [{iri, label}], object_properties, data_properties,
              annotation_properties}
    """
    from app.services.ontology import load_graph

    try:
        g = load_graph(board_dir)
    except FileNotFoundError:
        return {
            "classes": [],
            "object_properties": [],
            "data_properties": [],
            "annotation_properties": [],
        }

    def _collect(rdf_type) -> list[dict]:
        items = []
        for s in g.subjects(RDF.type, rdf_type):
            iri = str(s)
            # Skip blank nodes and well-known OWL/RDF builtins
            if iri.startswith("http://www.w3.org/") and "/owl#" not in iri:
                continue
            if not iri.startswith("http"):
                continue
            label = _get_label(g, s)
            items.append({"iri": iri, "label": label})
        return sorted(items, key=lambda x: x["label"].lower())

    return {
        "classes": _collect(OWL.Class),
        "object_properties": _collect(OWL.ObjectProperty),
        "data_properties": _collect(OWL.DatatypeProperty),
        "annotation_properties": _collect(OWL.AnnotationProperty),
    }


# ── 3. Generate ROBOT template ───────────────────────────────

def generate_robot_template(
    board_dir: Path,
    filename: str,
    column_mappings: list[dict],
    iri_strategy: str,
    base_iri: str,
    template_name: str | None = None,
) -> dict:
    """Generate a ROBOT template TSV from the user's column mappings.

    Each mapping: {column_name, directive_type, property_iri, split_char, ref_type}
    Directive types: ID, TYPE, A rdfs:label, A rdfs:comment,
                     A <annotation_prop>, I <object_prop>, I <data_prop>, IGNORE

    IRI strategy options for the ID column:
      auto_sequential, auto_uuid, auto_hash, from_column, custom_pattern

    If any mapping has a non-empty `ref_type`, row expansion is performed via
    expand_template_rows() to create additional individuals for referenced entities.

    Saves the template TSV to kg/templates/.
    Returns: {template_path, preview_rows}
    """
    source_path = _uploads_dir(board_dir) / filename
    if not source_path.exists():
        raise FileNotFoundError(f"Source file not found: {filename}")

    content = source_path.read_text(encoding="utf-8", errors="replace")
    first_line = content.split("\n")[0]
    delimiter = "\t" if "\t" in first_line else ","
    reader = csv.DictReader(content.splitlines(), delimiter=delimiter)
    source_rows = list(reader)

    # Build column order: first the ID column, then the rest
    # Filter out IGNORE mappings
    active_mappings = [m for m in column_mappings if m.get("directive_type") != "IGNORE"]

    # Separate ID mapping — we always need one
    id_mapping = next((m for m in active_mappings if m.get("directive_type") == "ID"), None)
    non_id_mappings = [m for m in active_mappings if m.get("directive_type") != "ID"]

    # Check if any mapping has ref_type — triggers expansion
    has_ref_type = any(m.get("ref_type", "") for m in column_mappings)

    # Row 1: comment headers (human-readable column names)
    header_row = ["#"]
    # Row 2: ROBOT directives
    directive_row = ["ID"]

    for m in non_id_mappings:
        header_row.append(m.get("column_name", ""))
        directive = _build_directive(m)
        directive_row.append(directive)

    if has_ref_type:
        # Use expand_template_rows for expansion
        expanded = expand_template_rows(
            board_dir, source_rows, column_mappings, iri_strategy, base_iri,
        )

        # Ensure "A rdfs:label" is in the header/directive row if not already
        if "A rdfs:label" not in directive_row:
            header_row.append("Label")
            directive_row.append("A rdfs:label")

        # Ensure "TYPE" is in the directive row if not already
        if "TYPE" not in directive_row:
            header_row.insert(1, "TYPE")
            directive_row.insert(1, "TYPE")

        # Build data rows from expanded dicts
        data_rows = []
        for row_dict in expanded:
            data_row = []
            for d in directive_row:
                if d == "#":
                    continue
                data_row.append(row_dict.get(d, ""))
            data_rows.append(data_row)
    else:
        # Original logic — no expansion needed
        data_rows = []
        from_column_name = None
        pattern_template = None

        if iri_strategy == "from_column" and id_mapping:
            from_column_name = id_mapping.get("column_name")
        elif iri_strategy == "custom_pattern":
            pattern_template = base_iri  # base_iri holds the pattern in this case

        for i, row in enumerate(source_rows):
            # Generate the IRI for this row
            iri = _generate_iri(
                strategy=iri_strategy,
                row=row,
                index=i,
                base_iri=base_iri,
                from_column=from_column_name,
                pattern=pattern_template,
            )
            data_row = [iri]
            for m in non_id_mappings:
                col = m.get("column_name", "")
                data_row.append(row.get(col, ""))
            data_rows.append(data_row)

    # Determine template filename
    if not template_name:
        stem = Path(filename).stem
        template_name = f"{stem}-template.tsv"
    if not template_name.endswith(".tsv"):
        template_name += ".tsv"

    template_path = _templates_dir(board_dir) / template_name

    # Write TSV
    with open(template_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, delimiter="\t", quoting=csv.QUOTE_MINIMAL)
        writer.writerow(header_row)
        writer.writerow(directive_row)
        for dr in data_rows:
            writer.writerow(dr)

    # Build preview (directives + first 3 data rows)
    preview_rows = [header_row, directive_row] + data_rows[:3]

    return {
        "template_path": str(template_path),
        "template_name": template_name,
        "preview_rows": preview_rows,
        "total_data_rows": len(data_rows),
    }


def _build_directive(mapping: dict) -> str:
    """Build a ROBOT directive string from a column mapping."""
    dtype = mapping.get("directive_type", "A")
    prop_iri = mapping.get("property_iri", "")
    split_char = mapping.get("split_char", "")

    if dtype == "TYPE":
        directive = "TYPE"
    elif dtype == "A rdfs:label":
        directive = "A rdfs:label"
    elif dtype == "A rdfs:comment":
        directive = "A rdfs:comment"
    elif dtype.startswith("A"):
        # Annotation property: A <property_iri>
        if prop_iri:
            directive = f"A {prop_iri}"
        else:
            directive = dtype
    elif dtype.startswith("I"):
        # Individual property: I <property_iri>
        if prop_iri:
            directive = f"I {prop_iri}"
        else:
            directive = dtype
    else:
        directive = dtype

    # Append SPLIT if specified
    if split_char:
        directive += f" SPLIT={split_char}"

    return directive


def _generate_iri(
    strategy: str,
    row: dict,
    index: int,
    base_iri: str,
    from_column: str | None = None,
    pattern: str | None = None,
) -> str:
    """Generate IRI for a data row based on the chosen strategy."""
    base = base_iri.rstrip("/")

    if strategy == "auto_sequential":
        return f"{base}/{index + 1}"
    elif strategy == "auto_uuid":
        return f"{base}/{uuid.uuid4()}"
    elif strategy == "auto_hash":
        content = "|".join(str(v) for v in row.values())
        h = hashlib.sha256(content.encode()).hexdigest()[:12]
        return f"{base}/{h}"
    elif strategy == "from_column" and from_column:
        val = row.get(from_column, "")
        if val.startswith("http://") or val.startswith("https://"):
            return val
        safe = re.sub(r"[^a-zA-Z0-9_.-]", "_", val)
        return f"{base}/{safe}"
    elif strategy == "custom_pattern" and pattern:
        iri = pattern
        for key, val in row.items():
            safe_val = re.sub(r"[^a-zA-Z0-9_.-]", "_", str(val))
            iri = iri.replace(f"{{{key}}}", safe_val)
        return iri
    else:
        # Default: sequential
        return f"{base}/{index + 1}"


# ── 3b. Expand template rows ────────────────────────────────

def expand_template_rows(
    board_dir: Path,
    csv_data: list[dict],
    column_mappings: list[dict],
    iri_strategy: str,
    base_iri: str,
) -> list[dict]:
    """Expand CSV rows into full ROBOT template data with referenced individuals.

    For each cell that has a SPLIT character or references another entity type,
    create additional rows for those entities.

    column_mappings includes:
    - ref_type: the TYPE (class IRI/label) for referenced entities in this column
      e.g., "academic discipline" for the disciplines column.
      If empty, the cell value is used as-is (literal/IRI).

    Returns a list of expanded row dicts ready to write as ROBOT template data.
    Each dict has keys matching directive column headers:
      'ID', 'TYPE', and directive strings for each mapped column.
    """
    # Filter active (non-IGNORE, non-ID) mappings
    active_mappings = [m for m in column_mappings if m.get("directive_type") not in ("IGNORE", "ID")]

    # Build directive key for each mapping
    def _directive_key(m: dict) -> str:
        return _build_directive(m)

    # Counter for sequential IRI generation across main + expanded rows
    iri_counter = 0

    # Collect all rows: main rows first, then expanded rows
    main_rows = []
    expanded_rows = []

    for csv_row in csv_data:
        # Generate IRI for main row
        main_iri = _generate_iri(
            strategy=iri_strategy,
            row=csv_row,
            index=iri_counter,
            base_iri=base_iri,
        )
        iri_counter += 1

        row_dict = {"ID": main_iri, "TYPE": ""}

        # Find TYPE mapping
        type_mapping = next(
            (m for m in column_mappings if m.get("directive_type") == "TYPE"), None
        )
        if type_mapping:
            row_dict["TYPE"] = csv_row.get(type_mapping.get("column_name", ""), "")

        for m in active_mappings:
            if m.get("directive_type") == "TYPE":
                continue
            col_name = m.get("column_name", "")
            ref_type = m.get("ref_type", "")
            split_char = m.get("split_char", "")
            dkey = _directive_key(m)
            cell_value = csv_row.get(col_name, "")

            if ref_type and cell_value:
                # This column references another entity type — expand
                if split_char and split_char in cell_value:
                    values = [v.strip() for v in cell_value.split(split_char)]
                else:
                    values = [cell_value.strip()] if cell_value.strip() else []

                ref_iris = []
                for val in values:
                    ref_iri = _generate_iri(
                        strategy=iri_strategy,
                        row={"_ref_label": val},
                        index=iri_counter,
                        base_iri=base_iri,
                    )
                    iri_counter += 1
                    ref_iris.append(ref_iri)

                    # Create the expanded row for this referenced entity
                    exp_row = {"ID": ref_iri, "TYPE": ref_type}
                    # Fill all directive columns with empty string
                    for m2 in active_mappings:
                        if m2.get("directive_type") == "TYPE":
                            continue
                        exp_row[_directive_key(m2)] = ""
                    # Set label column (A rdfs:label) to the value
                    exp_row["A rdfs:label"] = val
                    expanded_rows.append(exp_row)

                # Replace cell with pipe-separated IRIs
                row_dict[dkey] = "|".join(ref_iris)
            else:
                row_dict[dkey] = cell_value

        # Ensure all directive keys exist in main row
        for m in active_mappings:
            if m.get("directive_type") == "TYPE":
                continue
            dkey = _directive_key(m)
            if dkey not in row_dict:
                row_dict[dkey] = ""

        main_rows.append(row_dict)

    return main_rows + expanded_rows


# ── 4. Build KG from template ────────────────────────────────

async def build_kg_from_template(board_dir: Path, template_path: str) -> dict:
    """Build a KG from a ROBOT template, merging with the board's ontology.

    Pipeline:
    1. robot merge --include-annotations true -i <ontology.owl> \\
           template --merge-before --template <template.tsv> \\
           --output <output.owl> -vvv
    2. robot explain --reasoner hermit -i <output.owl> \\
           -M inconsistency --explanation <explanation.md>

    Returns: {success, output_path, triples_count, error, logs, consistency_check}
    """
    tpl = Path(template_path)
    if not tpl.is_absolute():
        tpl = board_dir / template_path
    if not tpl.exists():
        return {"success": False, "output_path": "", "triples_count": 0,
                "error": f"Template not found: {template_path}",
                "logs": [], "consistency_check": None}

    # Find the board's ontology file for merge
    from app.services.odk import find_owl_file
    ont_file = find_owl_file(board_dir)

    stem = tpl.stem.replace("-template", "")
    output_path = _output_dir(board_dir) / f"{stem}-kg.owl"
    explanation_path = _output_dir(board_dir) / f"{stem}-consistency.md"

    rel_t = _posix_rel(tpl, board_dir)
    rel_o = _posix_rel(output_path, board_dir)

    logs = []

    # Step 1: Merge ontology + template → KG
    if ont_file:
        rel_ont = _posix_rel(ont_file, board_dir)
        cmd = (
            f"robot merge --include-annotations true -i {rel_ont} "
            f"template --merge-before --template {rel_t} "
            f"--output {rel_o} -vvv"
        )
        logs.append({"step": "merge+template", "command": cmd, "status": "running"})
    else:
        cmd = f"robot template --template {rel_t} -o {rel_o} -vvv"
        logs.append({"step": "template", "command": cmd, "status": "running",
                      "note": "No ontology file found — building without merge"})

    result = await run_robot(board_dir, cmd)

    logs[-1]["status"] = "success" if result.success else "failed"
    logs[-1]["stdout"] = result.stdout[-500:] if result.stdout else ""
    logs[-1]["stderr"] = result.stderr[-500:] if result.stderr else ""

    if not result.success:
        return {
            "success": False, "output_path": "", "triples_count": 0,
            "error": result.stderr or "ROBOT merge+template failed",
            "logs": logs, "consistency_check": None,
        }

    # Count triples
    triples_count = 0
    if output_path.exists():
        try:
            g = Graph()
            for fmt in ("xml", "turtle", "n3"):
                try:
                    g.parse(str(output_path), format=fmt)
                    break
                except Exception:
                    continue
            triples_count = len(g)
        except Exception:
            pass

    # Step 2: Consistency check with ROBOT explain
    consistency_check = None
    rel_expl = _posix_rel(explanation_path, board_dir)
    explain_cmd = (
        f"robot explain --reasoner hermit -i {rel_o} "
        f"-M inconsistency --explanation {rel_expl}"
    )
    logs.append({"step": "consistency_check", "command": explain_cmd, "status": "running"})

    explain_result = await run_robot(board_dir, explain_cmd)

    explanation_text = ""
    if explanation_path.exists():
        explanation_text = explanation_path.read_text(encoding="utf-8", errors="replace")

    if explain_result.success:
        is_consistent = "inconsistent" not in (explain_result.stdout + explain_result.stderr).lower()
        consistency_check = {
            "consistent": is_consistent,
            "explanation": explanation_text,
            "status": "consistent" if is_consistent else "INCONSISTENT",
        }
        logs[-1]["status"] = "success"
    else:
        # explain failure doesn't mean KG build failed
        has_inconsistency = "inconsistent" in (explain_result.stderr or "").lower()
        consistency_check = {
            "consistent": not has_inconsistency,
            "explanation": explanation_text or explain_result.stderr[:300],
            "status": "INCONSISTENT" if has_inconsistency else "check_failed",
        }
        logs[-1]["status"] = "inconsistent" if has_inconsistency else "check_failed"
        logs[-1]["stderr"] = explain_result.stderr[-300:] if explain_result.stderr else ""

    return {
        "success": True,
        "output_path": str(output_path),
        "triples_count": triples_count,
        "error": None,
        "logs": logs,
        "consistency_check": consistency_check,
    }


# ── 5. Merge KG files ────────────────────────────────────────

async def merge_kg_files(board_dir: Path, file_paths: list[str]) -> dict:
    """Run `robot merge` on multiple OWL files.

    Returns: {success, output_path, triples_count, error}
    """
    if not file_paths:
        return {"success": False, "output_path": "", "triples_count": 0,
                "error": "No files provided"}

    output_path = _output_dir(board_dir) / "merged-kg.owl"
    rel_o = _posix_rel(output_path, board_dir)

    # Build merge command: robot merge -i file1.owl -i file2.owl ... -o merged.owl
    input_parts = []
    for fp in file_paths:
        p = Path(fp)
        if not p.is_absolute():
            p = board_dir / fp
        if p.exists():
            input_parts.append(f"-i {_posix_rel(p, board_dir)}")

    if not input_parts:
        return {"success": False, "output_path": "", "triples_count": 0,
                "error": "None of the specified files exist"}

    cmd = f"robot merge {' '.join(input_parts)} -o {rel_o}"
    result = await run_robot(board_dir, cmd)

    if not result.success:
        return {
            "success": False,
            "output_path": "",
            "triples_count": 0,
            "error": result.stderr or "ROBOT merge command failed",
        }

    triples_count = 0
    if output_path.exists():
        try:
            g = Graph()
            for fmt in ("xml", "turtle", "n3"):
                try:
                    g.parse(str(output_path), format=fmt)
                    break
                except Exception:
                    continue
            triples_count = len(g)
        except Exception:
            pass

    return {
        "success": True,
        "output_path": str(output_path),
        "triples_count": triples_count,
        "error": None,
    }


# ── 6. List KG files ─────────────────────────────────────────

def list_kg_files(board_dir: Path) -> dict:
    """List all files in the board's kg/ directory tree.

    Returns: {uploads: [{name, size, modified}],
              templates: [{name, size, modified}],
              output: [{name, size, modified}]}
    """
    def _list_dir(d: Path) -> list[dict]:
        if not d.exists():
            return []
        items = []
        for f in sorted(d.iterdir()):
            if f.is_file() and not f.name.startswith("."):
                items.append({
                    "name": f.name,
                    "size": f.stat().st_size,
                    "path": str(f.relative_to(board_dir)).replace("\\", "/"),
                })
        return items

    return {
        "uploads": _list_dir(_uploads_dir(board_dir)),
        "templates": _list_dir(_templates_dir(board_dir)),
        "output": _list_dir(_output_dir(board_dir)),
    }
