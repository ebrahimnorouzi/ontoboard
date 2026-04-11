"""Tests for documentation generation service and endpoints."""

import asyncio
import textwrap
from pathlib import Path

import pytest

from app.services.docs import get_docs_status, get_docs_content, list_docs_files, _generate_summary_md

SIMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://example.org/test#"
         xml:base="http://example.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://example.org/test"/>
        <owl:Class rdf:about="http://example.org/test#Animal">
            <rdfs:label xml:lang="en">Animal</rdfs:label>
        </owl:Class>
        <owl:Class rdf:about="http://example.org/test#Cat">
            <rdfs:label xml:lang="en">Cat</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://example.org/test#Animal"/>
        </owl:Class>
    </rdf:RDF>
""")


def _setup(data_dir: Path, board_id: str, owl: str = SIMPLE_OWL):
    ont_dir = data_dir / board_id / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{board_id}.owl").write_text(owl)


# ── Unit tests ─────────────────────────────────────────────────

def test_generate_summary_md(tmp_data_dir):
    _setup(tmp_data_dir, "sum-test")
    md = _generate_summary_md(tmp_data_dir / "sum-test")
    assert "# http://example.org/test Documentation" in md
    assert "Animal" in md or "Classes" in md
    assert "| Classes | **2** |" in md
    assert "SubClassOf: 1" in md


def test_docs_status_no_docs(tmp_data_dir):
    _setup(tmp_data_dir, "nodocs")
    status = get_docs_status(tmp_data_dir / "nodocs")
    assert status["generated"] is False
    assert status["page_count"] == 0


def test_docs_status_with_docs(tmp_data_dir):
    _setup(tmp_data_dir, "withdocs")
    docs_dir = tmp_data_dir / "withdocs" / "docs"
    docs_dir.mkdir()
    (docs_dir / "index.md").write_text("# Hello")
    (docs_dir / "about.md").write_text("# About")

    status = get_docs_status(tmp_data_dir / "withdocs")
    assert status["generated"] is True
    assert status["page_count"] == 2


def test_get_docs_content(tmp_data_dir):
    docs_dir = tmp_data_dir / "content-test" / "docs"
    docs_dir.mkdir(parents=True)
    (docs_dir / "readme.md").write_text("# Test Content")

    content = get_docs_content(tmp_data_dir / "content-test", "readme.md")
    assert content == "# Test Content"


def test_get_docs_content_not_found(tmp_data_dir):
    (tmp_data_dir / "missing" / "docs").mkdir(parents=True)
    content = get_docs_content(tmp_data_dir / "missing", "nonexistent.md")
    assert content is None


def test_get_docs_content_path_traversal(tmp_data_dir):
    docs_dir = tmp_data_dir / "traversal" / "docs"
    docs_dir.mkdir(parents=True)
    content = get_docs_content(tmp_data_dir / "traversal", "../../etc/passwd")
    assert content is None


def test_list_docs_files(tmp_data_dir):
    docs_dir = tmp_data_dir / "list-test" / "docs"
    docs_dir.mkdir(parents=True)
    (docs_dir / "a.md").write_text("a")
    (docs_dir / "b.html").write_text("b")
    sub = docs_dir / "sub"
    sub.mkdir()
    (sub / "c.md").write_text("c")

    files = list_docs_files(tmp_data_dir / "list-test")
    assert "a.md" in files
    assert "b.html" in files


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_build_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/docs-build")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "docs-build")

    resp = await admin_client.post("/api/docs/docs-build/build")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")

    # After build, docs should exist
    assert (tmp_data_dir / "docs-build" / "docs" / "ontology-summary.md").exists()
    content = (tmp_data_dir / "docs-build" / "docs" / "ontology-summary.md").read_text()
    assert "Animal" in content or "Classes" in content


@pytest.mark.asyncio
async def test_status_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/docs-stat")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "docs-stat")

    # Before build
    resp = await admin_client.get("/api/docs/docs-stat/status")
    assert resp.status_code == 200
    assert resp.json()["generated"] is False

    # Build
    await admin_client.post("/api/docs/docs-stat/build")

    # After build
    resp2 = await admin_client.get("/api/docs/docs-stat/status")
    assert resp2.json()["generated"] is True
    assert resp2.json()["page_count"] >= 1


@pytest.mark.asyncio
async def test_serve_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/docs-serve")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "docs-serve")

    # Build first
    await admin_client.post("/api/docs/docs-serve/build")

    # Serve
    resp = await admin_client.get("/api/docs/docs-serve/serve/ontology-summary.md")
    assert resp.status_code == 200
    assert "Documentation" in resp.text


@pytest.mark.asyncio
async def test_files_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/docs-files")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "docs-files")

    await admin_client.post("/api/docs/docs-files/build")

    resp = await admin_client.get("/api/docs/docs-files/files")
    assert resp.status_code == 200
    assert "ontology-summary.md" in resp.json()


@pytest.mark.asyncio
async def test_build_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/docs-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/docs/docs-noauth/build")
        assert resp.status_code == 401
