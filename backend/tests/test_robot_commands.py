"""Tests for ROBOT command router."""

import asyncio
import textwrap
from pathlib import Path

import pytest

SIMPLE_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/test#" xml:base="http://ex.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/test"/>
        <owl:Class rdf:about="http://ex.org/test#A"><rdfs:label>A</rdfs:label></owl:Class>
    </rdf:RDF>
""")


def _setup(d, bid):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(SIMPLE_OWL)


@pytest.mark.asyncio
async def test_robot_repair(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-repair")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-repair")
    resp = await admin_client.post("/api/robot/rob-repair/repair")
    assert resp.status_code == 200
    assert "command" in resp.json()


@pytest.mark.asyncio
async def test_robot_expand(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-expand")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-expand")
    resp = await admin_client.post("/api/robot/rob-expand/expand")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_collapse(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-collapse")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-collapse")
    resp = await admin_client.post("/api/robot/rob-collapse/collapse")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_relax(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-relax")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-relax")
    resp = await admin_client.post("/api/robot/rob-relax/relax")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_extract(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-extract")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-extract")
    resp = await admin_client.post("/api/robot/rob-extract/extract", json={
        "method": "STAR", "term_iris": ["http://ex.org/test#A"],
    })
    assert resp.status_code == 200
    assert resp.json()["output_file"] is not None


@pytest.mark.asyncio
async def test_robot_convert_ttl(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-conv")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-conv")
    resp = await admin_client.post("/api/robot/rob-conv/convert", json={"output_format": "ttl"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_convert_obo(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-obo")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-obo")
    resp = await admin_client.post("/api/robot/rob-obo/convert", json={"output_format": "obo"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_annotate(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-annot")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-annot")
    resp = await admin_client.post("/api/robot/rob-annot/annotate", json={
        "annotations": {"http://purl.org/dc/elements/1.1/title": "Test Ontology"},
    })
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_explain(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-explain")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-explain")
    resp = await admin_client.post("/api/robot/rob-explain/explain", json={
        "axiom": "A SubClassOf owl:Thing",
    })
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_unmerge(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-unmerge")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "rob-unmerge")
    resp = await admin_client.post("/api/robot/rob-unmerge/unmerge")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_robot_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/rob-noauth")
    await asyncio.sleep(0.1)
    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/robot/rob-noauth/repair")
        assert resp.status_code == 401
