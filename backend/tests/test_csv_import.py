"""Tests for CSV import service and endpoints."""

import asyncio
import io
from pathlib import Path

import pytest

from app.services.csv_import import analyze_csv, generate_iri, preview_kg, build_knowledge_graph


def _write_csv(data_dir: Path, board_id: str, filename: str, content: str) -> Path:
    upload_dir = data_dir / board_id / "uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)
    path = upload_dir / filename
    path.write_text(content)
    return path


# ── Unit tests ─────────────────────────────────────────────────

def test_analyze_csv(tmp_data_dir):
    path = _write_csv(tmp_data_dir, "csv-unit", "people.csv",
                      "Name,Age,City\nAlice,30,Berlin\nBob,25,London\nCarol,35,Paris\n")
    result = analyze_csv(path)
    assert result["filename"] == "people.csv"
    assert result["separator"] == ","
    assert result["row_count"] == 3
    assert len(result["columns"]) == 3
    assert result["columns"][0]["name"] == "Name"
    assert result["columns"][1]["inferred_type"] == "integer"
    assert len(result["sample_rows"]) == 3


def test_analyze_tsv(tmp_data_dir):
    path = _write_csv(tmp_data_dir, "tsv-unit", "data.tsv",
                      "ID\tValue\n1\t3.14\n2\t2.71\n")
    result = analyze_csv(path)
    assert result["separator"] == "\t"
    assert result["columns"][1]["inferred_type"] == "float"


def test_generate_iri_sequential():
    iri = generate_iri("sequential", {"Name": "Alice"}, 0, "http://ex.org/inst")
    assert iri == "http://ex.org/inst/ind_0"


def test_generate_iri_uuid():
    iri = generate_iri("uuid", {"Name": "Alice"}, 0, "http://ex.org/inst")
    assert iri.startswith("http://ex.org/inst/")
    assert len(iri) > 30


def test_generate_iri_hash():
    iri = generate_iri("hash", {"Name": "Alice", "Age": "30"}, 0, "http://ex.org/inst")
    assert iri.startswith("http://ex.org/inst/")
    # Same input → same hash
    iri2 = generate_iri("hash", {"Name": "Alice", "Age": "30"}, 1, "http://ex.org/inst")
    assert iri == iri2


def test_generate_iri_pattern():
    iri = generate_iri("pattern", {"Name": "Alice", "City": "Berlin"}, 0,
                       "http://ex.org/inst", "http://ex.org/{Name}_{City}")
    assert iri == "http://ex.org/Alice_Berlin"


def test_preview_kg(tmp_data_dir):
    path = _write_csv(tmp_data_dir, "prev-unit", "test.csv",
                      "Name,Age\nAlice,30\nBob,25\n")
    mappings = [
        {"column": "Name", "target_iri": "http://ex.org/name", "mapping_type": "data_property", "iri_role": "value"},
    ]
    triples = preview_kg(path, mappings, "sequential", None, "http://ex.org/inst", limit=2)
    assert len(triples) == 2
    assert triples[0]["subject"] == "http://ex.org/inst/ind_0"
    assert triples[0]["predicate"] == "http://ex.org/name"


def test_build_knowledge_graph(tmp_data_dir):
    board_dir = tmp_data_dir / "kg-unit"
    _write_csv(tmp_data_dir, "kg-unit", "people.csv",
               "Name,Age,Type\nAlice,30,Person\nBob,25,Person\n")

    mappings = [
        {"column": "Type", "target_iri": "http://ex.org/Person", "mapping_type": "class_assertion", "iri_role": "value"},
        {"column": "Name", "target_iri": "http://ex.org/hasName", "mapping_type": "data_property", "iri_role": "value"},
        {"column": "Age", "target_iri": "http://ex.org/hasAge", "mapping_type": "data_property", "iri_role": "value"},
    ]

    result = build_knowledge_graph(
        board_dir, "people.csv", mappings,
        "sequential", None, "http://ex.org/inst",
    )
    assert result["individuals_count"] == 2
    assert result["triples_count"] > 0
    assert Path(result["output_path"]).exists()

    # Verify the Turtle file
    content = Path(result["output_path"]).read_text()
    assert "Alice" in content
    assert "Bob" in content


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_upload_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/csv-api")
    await asyncio.sleep(0.1)

    csv_content = b"Name,Age,City\nAlice,30,Berlin\nBob,25,London\n"
    resp = await admin_client.post(
        "/api/csv/csv-api/upload",
        files={"file": ("people.csv", io.BytesIO(csv_content), "text/csv")},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["row_count"] == 2
    assert len(body["columns"]) == 3
    assert body["columns"][0]["name"] == "Name"
    assert body["columns"][1]["inferred_type"] == "integer"
    assert len(body["sample_rows"]) == 2


@pytest.mark.asyncio
async def test_list_files_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/csv-files")
    await asyncio.sleep(0.1)

    await admin_client.post(
        "/api/csv/csv-files/upload",
        files={"file": ("a.csv", io.BytesIO(b"x,y\n1,2\n"), "text/csv")},
    )
    await admin_client.post(
        "/api/csv/csv-files/upload",
        files={"file": ("b.tsv", io.BytesIO(b"x\ty\n1\t2\n"), "text/tsv")},
    )

    resp = await admin_client.get("/api/csv/csv-files/files")
    assert resp.status_code == 200
    assert "a.csv" in resp.json()
    assert "b.tsv" in resp.json()


@pytest.mark.asyncio
async def test_preview_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/csv-prev")
    await asyncio.sleep(0.1)

    await admin_client.post(
        "/api/csv/csv-prev/upload",
        files={"file": ("data.csv", io.BytesIO(b"Name,Age\nAlice,30\nBob,25\n"), "text/csv")},
    )

    resp = await admin_client.post("/api/csv/csv-prev/preview", json={
        "csv_file": "data.csv",
        "mappings": [
            {"column": "Name", "target_iri": "http://ex.org/name", "mapping_type": "data_property", "iri_role": "value"},
        ],
        "iri_strategy": "sequential",
        "base_iri": "http://ex.org/inst",
        "limit": 2,
    })
    assert resp.status_code == 200
    assert len(resp.json()["triples"]) == 2


@pytest.mark.asyncio
async def test_build_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/csv-build")
    await asyncio.sleep(0.1)

    await admin_client.post(
        "/api/csv/csv-build/upload",
        files={"file": ("people.csv", io.BytesIO(b"Name,Type\nAlice,Person\nBob,Person\n"), "text/csv")},
    )

    resp = await admin_client.post("/api/csv/csv-build/build", json={
        "csv_file": "people.csv",
        "mappings": [
            {"column": "Type", "target_iri": "http://ex.org/Person", "mapping_type": "class_assertion", "iri_role": "value"},
            {"column": "Name", "target_iri": "http://ex.org/hasName", "mapping_type": "data_property", "iri_role": "value"},
        ],
        "iri_strategy": "hash",
        "base_iri": "http://ex.org/inst",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["individuals_count"] == 2
    assert body["triples_count"] > 0

    # Verify file on disk
    from app.services.board import get_board_dir
    assert (get_board_dir("csv-build") / "knowledge_graph.ttl").exists()


@pytest.mark.asyncio
async def test_build_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/csv-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/csv/csv-noauth/build", json={
            "csv_file": "x.csv", "mappings": [],
            "iri_strategy": "sequential", "base_iri": "http://ex.org",
        })
        assert resp.status_code == 401
