"""Tests for ROBOT template row expansion and related CSV import features."""

import asyncio
import csv
import io
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

from app.services.csv_import import (
    analyze_csv,
    expand_template_rows,
    generate_robot_template,
    get_ontology_entities,
    list_kg_files,
    _generate_iri,
    _build_directive,
)


SIMPLE_OWL = '''<?xml version="1.0"?>
<rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
    xmlns:owl="http://www.w3.org/2002/07/owl#"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <owl:Ontology rdf:about="http://ex.org/t"/>
  <owl:Class rdf:about="http://ex.org/t#Person"><rdfs:label>person</rdfs:label></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#Discipline"><rdfs:label>academic discipline</rdfs:label></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#Project"><rdfs:label>project</rdfs:label></owl:Class>
  <owl:Class rdf:about="http://ex.org/t#GivenName"><rdfs:label>given name</rdfs:label></owl:Class>
  <owl:ObjectProperty rdf:about="http://ex.org/t#hasSubjectArea"><rdfs:label>has subject area</rdfs:label></owl:ObjectProperty>
  <owl:ObjectProperty rdf:about="http://ex.org/t#participatesIn"><rdfs:label>participates in</rdfs:label></owl:ObjectProperty>
  <owl:ObjectProperty rdf:about="http://ex.org/t#isAbout"><rdfs:label>is about</rdfs:label></owl:ObjectProperty>
  <owl:DatatypeProperty rdf:about="http://ex.org/t#hasValue"><rdfs:label>has value</rdfs:label></owl:DatatypeProperty>
</rdf:RDF>'''


def _setup(data_dir: Path, board_id: str = "tpl-test") -> Path:
    """Create board directory structure and return board_dir."""
    board_dir = data_dir / board_id
    board_dir.mkdir(parents=True, exist_ok=True)
    return board_dir


def _write_upload(board_dir: Path, filename: str, content: str) -> Path:
    """Write a file into kg/uploads/ within a board directory."""
    upload_dir = board_dir / "kg" / "uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)
    path = upload_dir / filename
    path.write_text(content, encoding="utf-8")
    return path


def _write_ontology(board_dir: Path, owl_content: str = SIMPLE_OWL) -> Path:
    """Write an OWL ontology file into the board directory."""
    ont_dir = board_dir / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    owl_path = ont_dir / f"{board_dir.name}.owl"
    owl_path.write_text(owl_content, encoding="utf-8")
    return owl_path


# ── 1. test_analyze_csv_basic ────────────────────────────────

def test_analyze_csv_basic(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "analyze-basic")
    _write_upload(board_dir, "people.csv",
                  "Name,Age,City\nAlice,30,Berlin\nBob,25,London\nCarol,35,Paris\n")

    result = analyze_csv(board_dir, "people.csv")
    assert result["filename"] == "people.csv"
    assert result["row_count"] == 3
    assert len(result["columns"]) == 3
    assert result["columns"][0]["name"] == "Name"
    assert "Alice" in result["columns"][0]["sample_values"]
    assert result["delimiter"] == ","


# ── 2. test_analyze_csv_tsv_delimiter ────────────────────────

def test_analyze_csv_tsv_delimiter(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "analyze-tsv")
    _write_upload(board_dir, "data.tsv",
                  "ID\tValue\tLabel\n1\t3.14\tAlpha\n2\t2.71\tBeta\n")

    result = analyze_csv(board_dir, "data.tsv")
    assert result["delimiter"] == "\t"
    assert result["row_count"] == 2
    assert result["columns"][0]["name"] == "ID"


# ── 3. test_get_ontology_entities ────────────────────────────

def test_get_ontology_entities(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "entities-test")
    _write_ontology(board_dir)

    entities = get_ontology_entities(board_dir)

    assert len(entities["classes"]) >= 3
    class_labels = {e["label"] for e in entities["classes"]}
    assert "person" in class_labels
    assert "academic discipline" in class_labels
    assert "project" in class_labels

    assert len(entities["object_properties"]) >= 2
    op_labels = {e["label"] for e in entities["object_properties"]}
    assert "has subject area" in op_labels
    assert "participates in" in op_labels

    assert len(entities["data_properties"]) >= 1
    dp_labels = {e["label"] for e in entities["data_properties"]}
    assert "has value" in dp_labels


# ── 4. test_generate_simple_template ─────────────────────────

def test_generate_simple_template(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "gen-simple")
    _write_upload(board_dir, "people.csv",
                  "Name,Age\nAlice,30\nBob,25\n")

    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": ""},
        {"column_name": "Age", "directive_type": "A", "property_iri": "http://ex.org/hasAge", "split_char": ""},
    ]

    result = generate_robot_template(
        board_dir, "people.csv", mappings,
        "auto_sequential", "https://example.org/resource",
    )

    assert result["total_data_rows"] == 2
    assert result["template_name"] == "people-template.tsv"
    assert Path(result["template_path"]).exists()

    # Read TSV and verify structure
    content = Path(result["template_path"]).read_text(encoding="utf-8")
    lines = content.strip().split("\n")
    assert len(lines) == 4  # header + directive + 2 data rows
    assert "A rdfs:label" in lines[1]


# ── 5. test_generate_template_with_split ─────────────────────

def test_generate_template_with_split(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "gen-split")
    _write_upload(board_dir, "data.csv",
                  "Name,Topics\nAlice,\"Math,Physics\"\n")

    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": ""},
        {"column_name": "Topics", "directive_type": "I", "property_iri": "http://ex.org/hasTopic",
         "split_char": ","},
    ]

    result = generate_robot_template(
        board_dir, "data.csv", mappings,
        "auto_sequential", "https://example.org/resource",
    )

    # Read TSV and verify SPLIT directive
    content = Path(result["template_path"]).read_text(encoding="utf-8")
    assert "SPLIT=," in content


# ── 6. test_expand_rows_no_ref_type ──────────────────────────

def test_expand_rows_no_ref_type(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "expand-no-ref")
    csv_data = [
        {"Name": "Alice", "Topic": "Math"},
        {"Name": "Bob", "Topic": "Physics"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Topic", "directive_type": "I", "property_iri": "http://ex.org/hasTopic",
         "split_char": "", "ref_type": ""},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")

    # No expansion — same number of rows as input
    assert len(rows) == 2
    assert rows[0]["A rdfs:label"] == "Alice"
    assert rows[1]["A rdfs:label"] == "Bob"


# ── 7. test_expand_rows_with_ref_type ────────────────────────

def test_expand_rows_with_ref_type(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "expand-ref")
    csv_data = [
        {"Name": "Alice", "Project": "ProjectX"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Name", "directive_type": "TYPE", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Project", "directive_type": "I", "property_iri": "http://ex.org/participatesIn",
         "split_char": "", "ref_type": "project"},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")

    # 1 main row + 1 expanded row for ProjectX
    assert len(rows) == 2
    main_row = rows[0]
    expanded_row = rows[1]

    assert expanded_row["TYPE"] == "project"
    assert expanded_row["A rdfs:label"] == "ProjectX"
    # Main row's project column should be replaced with the IRI
    proj_directive = _build_directive(mappings[2])
    assert main_row[proj_directive].startswith("https://example.org/r/")


# ── 8. test_expand_rows_with_split_and_ref_type ──────────────

def test_expand_rows_with_split_and_ref_type(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "expand-split-ref")
    csv_data = [
        {"Name": "Sarath", "Discipline": "Computational Materials Science,software development"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Discipline", "directive_type": "I", "property_iri": "http://ex.org/hasSubjectArea",
         "split_char": ",", "ref_type": "academic discipline"},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")

    # 1 main + 2 expanded (one per discipline)
    assert len(rows) == 3

    main_row = rows[0]
    exp1 = rows[1]
    exp2 = rows[2]

    assert exp1["TYPE"] == "academic discipline"
    assert exp1["A rdfs:label"] == "Computational Materials Science"
    assert exp2["TYPE"] == "academic discipline"
    assert exp2["A rdfs:label"] == "software development"

    # Main row discipline cell should have pipe-separated IRIs
    disc_directive = _build_directive(mappings[1])
    iris = main_row[disc_directive].split("|")
    assert len(iris) == 2
    assert iris[0] == exp1["ID"]
    assert iris[1] == exp2["ID"]


# ── 9. test_expand_rows_iri_generation ───────────────────────

def test_expand_rows_iri_generation(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "expand-iri-gen")
    csv_data = [
        {"Name": "Alice", "Disc": "Math,Physics"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Disc", "directive_type": "I", "property_iri": "http://ex.org/disc",
         "split_char": ",", "ref_type": "discipline"},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")

    # Main row gets index 0 -> /1, expanded rows get /2 and /3
    assert rows[0]["ID"] == "https://example.org/r/1"
    assert rows[1]["ID"] == "https://example.org/r/2"
    assert rows[2]["ID"] == "https://example.org/r/3"


# ── 10. test_expand_rows_replaces_cell_with_iri ──────────────

def test_expand_rows_replaces_cell_with_iri(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "expand-replace")
    csv_data = [
        {"Name": "Bob", "Project": "Alpha"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Project", "directive_type": "I", "property_iri": "http://ex.org/participatesIn",
         "split_char": "", "ref_type": "project"},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")

    main_row = rows[0]
    expanded_row = rows[1]

    proj_directive = _build_directive(mappings[1])
    # The cell should be replaced with the generated IRI, not "Alpha"
    assert main_row[proj_directive] == expanded_row["ID"]
    assert "Alpha" not in main_row[proj_directive]
    # But the expanded row label should be "Alpha"
    assert expanded_row["A rdfs:label"] == "Alpha"


# ── 11. test_expand_rows_multiple_columns ────────────────────

def test_expand_rows_multiple_columns(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "expand-multi-col")
    csv_data = [
        {"Name": "Sarath", "Discipline": "CompSci,SWDev", "Project": "ProjA"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
        {"column_name": "Discipline", "directive_type": "I", "property_iri": "http://ex.org/hasSubjectArea",
         "split_char": ",", "ref_type": "academic discipline"},
        {"column_name": "Project", "directive_type": "I", "property_iri": "http://ex.org/participatesIn",
         "split_char": "", "ref_type": "project"},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")

    # 1 main + 2 disciplines + 1 project = 4
    assert len(rows) == 4

    main_row = rows[0]
    # Expanded rows: disciplines first (processed in mapping order), then project
    disc_rows = [r for r in rows[1:] if r["TYPE"] == "academic discipline"]
    proj_rows = [r for r in rows[1:] if r["TYPE"] == "project"]
    assert len(disc_rows) == 2
    assert len(proj_rows) == 1

    disc_directive = _build_directive(mappings[1])
    proj_directive = _build_directive(mappings[2])
    assert "|" in main_row[disc_directive]  # pipe-separated for 2 disciplines
    assert "|" not in main_row[proj_directive]  # single project


# ── 12. test_iri_strategy_sequential ─────────────────────────

def test_iri_strategy_sequential(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "iri-seq")
    csv_data = [
        {"Name": "A"}, {"Name": "B"}, {"Name": "C"},
    ]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_sequential", "https://example.org/r")
    assert rows[0]["ID"] == "https://example.org/r/1"
    assert rows[1]["ID"] == "https://example.org/r/2"
    assert rows[2]["ID"] == "https://example.org/r/3"


# ── 13. test_iri_strategy_uuid ───────────────────────────────

def test_iri_strategy_uuid(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "iri-uuid")
    csv_data = [{"Name": "Alice"}]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_uuid", "https://example.org/r")
    assert rows[0]["ID"].startswith("https://example.org/r/")
    # UUID should be long enough
    local_part = rows[0]["ID"].split("/r/")[1]
    assert len(local_part) >= 32


# ── 14. test_iri_strategy_hash ───────────────────────────────

def test_iri_strategy_hash(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "iri-hash")
    csv_data = [{"Name": "Alice", "Age": "30"}]
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": "", "ref_type": ""},
    ]

    rows = expand_template_rows(board_dir, csv_data, mappings, "auto_hash", "https://example.org/r")
    assert rows[0]["ID"].startswith("https://example.org/r/")
    # Hash should be deterministic
    rows2 = expand_template_rows(board_dir, csv_data, mappings, "auto_hash", "https://example.org/r")
    assert rows[0]["ID"] == rows2[0]["ID"]


# ── 15. test_build_kg_from_template ──────────────────────────

@pytest.mark.asyncio
async def test_build_kg_from_template(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "build-tpl")
    templates_dir = board_dir / "kg" / "templates"
    templates_dir.mkdir(parents=True, exist_ok=True)

    tpl_path = templates_dir / "test-template.tsv"
    tpl_path.write_text("ID\tTYPE\n", encoding="utf-8")

    mock_result = AsyncMock()
    mock_result.success = True
    mock_result.stderr = ""

    with patch("app.services.csv_import.run_robot", return_value=mock_result):
        from app.services.csv_import import build_kg_from_template
        result = await build_kg_from_template(board_dir, str(tpl_path))

    assert result["success"] is True


# ── 16. test_merge_kg_files ──────────────────────────────────

@pytest.mark.asyncio
async def test_merge_kg_files(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "merge-test")
    output_dir = board_dir / "kg" / "output"
    output_dir.mkdir(parents=True, exist_ok=True)

    # Create two dummy OWL files
    f1 = output_dir / "a.owl"
    f2 = output_dir / "b.owl"
    f1.write_text("<rdf/>", encoding="utf-8")
    f2.write_text("<rdf/>", encoding="utf-8")

    mock_result = AsyncMock()
    mock_result.success = True
    mock_result.stderr = ""

    with patch("app.services.csv_import.run_robot", return_value=mock_result):
        from app.services.csv_import import merge_kg_files
        result = await merge_kg_files(board_dir, [str(f1), str(f2)])

    assert result["success"] is True


# ── 17. test_list_kg_files ───────────────────────────────────

def test_list_kg_files(tmp_data_dir):
    board_dir = _setup(tmp_data_dir, "list-files")

    # Create files in each subdirectory
    _write_upload(board_dir, "data.csv", "a,b\n1,2\n")

    tpl_dir = board_dir / "kg" / "templates"
    tpl_dir.mkdir(parents=True, exist_ok=True)
    (tpl_dir / "test-template.tsv").write_text("ID\tTYPE\n", encoding="utf-8")

    out_dir = board_dir / "kg" / "output"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "result.owl").write_text("<owl/>", encoding="utf-8")

    result = list_kg_files(board_dir)
    assert len(result["uploads"]) == 1
    assert result["uploads"][0]["name"] == "data.csv"
    assert len(result["templates"]) == 1
    assert result["templates"][0]["name"] == "test-template.tsv"
    assert len(result["output"]) == 1
    assert result["output"][0]["name"] == "result.owl"


# ── 18. test_full_pipeline ───────────────────────────────────

@pytest.mark.asyncio
async def test_full_pipeline(tmp_data_dir):
    """Upload CSV -> map -> expand -> generate template -> build (with mocks)."""
    board_dir = _setup(tmp_data_dir, "pipeline")
    _write_upload(board_dir, "researchers.csv",
                  'Name,Discipline,Project\nSarath,"CompSci,SWDev",ProjA\n')

    # Step 1: analyze
    analysis = analyze_csv(board_dir, "researchers.csv")
    assert analysis["row_count"] == 1
    assert len(analysis["columns"]) == 3

    # Step 2: map columns with ref_type
    mappings = [
        {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "",
         "split_char": "", "ref_type": ""},
        {"column_name": "Discipline", "directive_type": "I",
         "property_iri": "http://ex.org/hasSubjectArea",
         "split_char": ",", "ref_type": "academic discipline"},
        {"column_name": "Project", "directive_type": "I",
         "property_iri": "http://ex.org/participatesIn",
         "split_char": "", "ref_type": "project"},
    ]

    # Step 3: generate template (with expansion)
    result = generate_robot_template(
        board_dir, "researchers.csv", mappings,
        "auto_sequential", "https://example.org/r",
    )

    # 1 main + 2 disciplines + 1 project = 4 rows
    assert result["total_data_rows"] == 4
    assert Path(result["template_path"]).exists()

    # Read the template and verify
    content = Path(result["template_path"]).read_text(encoding="utf-8")
    reader = csv.reader(content.splitlines(), delimiter="\t")
    all_rows = list(reader)
    # Row 0: comment header, Row 1: directives, Rows 2-5: data
    assert len(all_rows) == 6

    # Step 4: build (mocked)
    mock_result = AsyncMock()
    mock_result.success = True
    mock_result.stderr = ""

    with patch("app.services.csv_import.run_robot", return_value=mock_result):
        from app.services.csv_import import build_kg_from_template
        build_result = await build_kg_from_template(board_dir, result["template_path"])

    assert build_result["success"] is True


# ── 19. test_upload_endpoint ─────────────────────────────────

@pytest.mark.asyncio
async def test_upload_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tpl-upload")
    await asyncio.sleep(0.1)

    csv_content = b"Name,Age,City\nAlice,30,Berlin\nBob,25,London\n"
    resp = await admin_client.post(
        "/api/csv/tpl-upload/upload",
        files={"file": ("people.csv", io.BytesIO(csv_content), "text/csv")},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["row_count"] == 2
    assert len(body["columns"]) == 3
    assert body["columns"][0]["name"] == "Name"


# ── 20. test_entities_endpoint ───────────────────────────────

@pytest.mark.asyncio
async def test_entities_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tpl-entities")
    await asyncio.sleep(0.1)

    # Write ontology file to board dir
    from app.services.board import get_board_dir
    board_dir = get_board_dir("tpl-entities")
    _write_ontology(board_dir)

    resp = await admin_client.get("/api/csv/tpl-entities/entities")
    assert resp.status_code == 200
    body = resp.json()
    # Should have classes, object_properties, etc. keys
    assert "classes" in body
    assert "object_properties" in body
    assert "data_properties" in body


# ── 21. test_template_endpoint ───────────────────────────────

@pytest.mark.asyncio
async def test_template_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tpl-gen")
    await asyncio.sleep(0.1)

    csv_content = b"Name,Topic\nAlice,Math\nBob,Physics\n"
    await admin_client.post(
        "/api/csv/tpl-gen/upload",
        files={"file": ("data.csv", io.BytesIO(csv_content), "text/csv")},
    )

    resp = await admin_client.post("/api/csv/tpl-gen/template", json={
        "filename": "data.csv",
        "column_mappings": [
            {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": ""},
            {"column_name": "Topic", "directive_type": "I", "property_iri": "http://ex.org/hasTopic",
             "split_char": ""},
        ],
        "iri_strategy": "auto_sequential",
        "base_iri": "https://example.org/resource",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["total_data_rows"] == 2
    assert body["template_name"].endswith(".tsv")


# ── 22. test_build_endpoint ──────────────────────────────────

@pytest.mark.asyncio
async def test_build_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tpl-build")
    await asyncio.sleep(0.1)

    # Upload and generate template first
    csv_content = b"Name\nAlice\nBob\n"
    await admin_client.post(
        "/api/csv/tpl-build/upload",
        files={"file": ("build.csv", io.BytesIO(csv_content), "text/csv")},
    )
    tpl_resp = await admin_client.post("/api/csv/tpl-build/template", json={
        "filename": "build.csv",
        "column_mappings": [
            {"column_name": "Name", "directive_type": "A rdfs:label", "property_iri": "", "split_char": ""},
        ],
        "iri_strategy": "auto_sequential",
        "base_iri": "https://example.org/resource",
    })
    template_path = tpl_resp.json()["template_path"]

    mock_result = AsyncMock()
    mock_result.success = True
    mock_result.stderr = ""

    with patch("app.services.csv_import.run_robot", return_value=mock_result):
        resp = await admin_client.post("/api/csv/tpl-build/build", json={
            "template_path": template_path,
        })

    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True


# ── 23. test_files_endpoint ──────────────────────────────────

@pytest.mark.asyncio
async def test_files_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tpl-files")
    await asyncio.sleep(0.1)

    # Upload a file
    csv_content = b"x,y\n1,2\n"
    await admin_client.post(
        "/api/csv/tpl-files/upload",
        files={"file": ("sample.csv", io.BytesIO(csv_content), "text/csv")},
    )

    resp = await admin_client.get("/api/csv/tpl-files/files")
    assert resp.status_code == 200
    body = resp.json()
    assert "uploads" in body
    upload_names = [f["name"] for f in body["uploads"]]
    assert "sample.csv" in upload_names
