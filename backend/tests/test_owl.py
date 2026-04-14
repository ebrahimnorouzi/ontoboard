"""Tests for OWL/CSV endpoints."""

import asyncio
import io

import pytest


@pytest.mark.asyncio
async def test_upload_csv(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/csv-test")
    await asyncio.sleep(0.1)

    csv_content = b"Name,Age,City\nAlice,30,Berlin\nBob,25,London\n"
    resp = await admin_client.post(
        "/api/owl/csv-test/upload-csv",
        files={"file": ("people.csv", io.BytesIO(csv_content), "text/csv")},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["filename"] == "people.csv"
    assert body["columns"] == ["Name", "Age", "City"]
    from app.services.board import get_board_dir
    assert (get_board_dir("csv-test") / "uploads" / "people.csv").is_file()


@pytest.mark.asyncio
async def test_upload_csv_requires_auth(admin_client):
    await admin_client.post("/api/boards/csv-auth")
    await asyncio.sleep(0.1)

    # Anonymous request (no auth header)
    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        csv_content = b"a,b\n1,2\n"
        resp = await anon.post(
            "/api/owl/csv-auth/upload-csv",
            files={"file": ("x.csv", io.BytesIO(csv_content), "text/csv")},
        )
        assert resp.status_code == 401


@pytest.mark.asyncio
async def test_upload_tsv(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/tsv-test")
    await asyncio.sleep(0.1)

    tsv_content = b"Name\tAge\tCity\nAlice\t30\tBerlin\n"
    resp = await admin_client.post(
        "/api/owl/tsv-test/upload-csv",
        files={"file": ("data.tsv", io.BytesIO(tsv_content), "text/tsv")},
    )
    assert resp.status_code == 200
    assert resp.json()["columns"] == ["Name", "Age", "City"]


@pytest.mark.asyncio
async def test_generate_robot_template():
    from app.services.odk import generate_robot_template
    from app.schemas.board import CanvasGraph
    import tempfile
    from pathlib import Path

    graph = CanvasGraph(
        classes=[
            {"id": "http://ex.org/Pizza", "label": "Pizza", "x": 0, "y": 0, "iri": "http://ex.org/Pizza"},
            {"id": "http://ex.org/Topping", "label": "Topping", "x": 0, "y": 0, "iri": "http://ex.org/Topping"},
        ],
        properties=[{
            "id": "http://ex.org/hasTopping", "source": "http://ex.org/Pizza",
            "target": "http://ex.org/Topping", "label": "hasTopping", "iri": "http://ex.org/hasTopping",
        }],
    )

    with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False) as f:
        out_path = Path(f.name)

    try:
        generate_robot_template(graph, out_path)
        content = out_path.read_text()
        lines = content.strip().split("\n")
        assert len(lines) == 5
        assert "Pizza" in content
        assert "owl:Class" in content
        assert "hasTopping" in content
    finally:
        out_path.unlink(missing_ok=True)


@pytest.mark.asyncio
async def test_parse_json_ld():
    from app.services.odk import parse_json_ld
    import json
    import tempfile
    from pathlib import Path

    json_ld = {
        "@graph": [
            {"@id": "http://ex.org/Pizza", "@type": ["owl:Class"], "rdfs:label": "Pizza"},
            {"@id": "http://ex.org/Topping", "@type": ["http://www.w3.org/2002/07/owl#Class"], "rdfs:label": {"@value": "Topping"}},
            {
                "@id": "http://ex.org/hasTopping", "@type": ["owl:ObjectProperty"], "rdfs:label": "hasTopping",
                "rdfs:domain": {"@id": "http://ex.org/Pizza"}, "rdfs:range": {"@id": "http://ex.org/Topping"},
            },
        ]
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(json_ld, f)
        tmp_path = Path(f.name)

    try:
        graph = parse_json_ld(tmp_path)
        assert len(graph.classes) == 2
        assert len(graph.properties) == 1
        assert graph.properties[0]["source"] == "http://ex.org/Pizza"
    finally:
        tmp_path.unlink(missing_ok=True)


@pytest.mark.asyncio
async def test_generate_kg_template():
    from app.services.odk import generate_kg_template
    import tempfile
    from pathlib import Path

    with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False) as f:
        f.write("Name,Age\nAlice,30\nBob,25\n")
        csv_path = Path(f.name)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False) as f:
        template_path = Path(f.name)

    try:
        generate_kg_template([{"column": "Name", "class_iri": "http://ex.org/Person"}], csv_path, template_path)
        content = template_path.read_text()
        lines = content.strip().split("\n")
        assert len(lines) == 4
        assert "Alice" in content
        assert "http://ex.org/Person" in lines[1]
    finally:
        csv_path.unlink(missing_ok=True)
        template_path.unlink(missing_ok=True)
