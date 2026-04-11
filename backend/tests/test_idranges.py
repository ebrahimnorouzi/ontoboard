"""Tests for ID ranges and ODP patterns."""

import asyncio
from pathlib import Path
import pytest
from app.services.idranges import generate_idranges_xml, parse_idranges, allocate_range, reserve_next_id
from app.services.patterns import list_patterns, get_pattern, apply_pattern

def _setup(d, bid):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text('<?xml version="1.0"?><rdf:RDF xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><owl:Ontology rdf:about="http://ex.org/t"/></rdf:RDF>')

def test_generate_idranges(tmp_data_dir):
    _setup(tmp_data_dir, "id-gen")
    path = generate_idranges_xml(tmp_data_dir / "id-gen", "id-gen")
    assert path.exists()
    ranges = parse_idranges(tmp_data_dir / "id-gen")
    assert len(ranges) == 1
    assert ranges[0]["user"] == "default"

def test_allocate_range(tmp_data_dir):
    _setup(tmp_data_dir, "id-alloc")
    generate_idranges_xml(tmp_data_dir / "id-alloc", "id-alloc")
    result = allocate_range(tmp_data_dir / "id-alloc", "alice")
    assert result["user"] == "alice"
    ranges = parse_idranges(tmp_data_dir / "id-alloc")
    assert len(ranges) == 2

def test_reserve_id(tmp_data_dir):
    _setup(tmp_data_dir, "id-res")
    generate_idranges_xml(tmp_data_dir / "id-res", "id-res")
    allocate_range(tmp_data_dir / "id-res", "bob")
    iri = reserve_next_id(tmp_data_dir / "id-res", "bob")
    assert iri is not None
    assert "bob" in iri.lower() or "0" in iri

def test_list_patterns():
    pats = list_patterns()
    assert len(pats) >= 5
    assert any(p["id"] == "part-of" for p in pats)

def test_get_pattern():
    p = get_pattern("part-of")
    assert p is not None
    assert len(p["classes"]) == 2
    assert len(p["properties"]) == 2

def test_apply_pattern(tmp_data_dir):
    _setup(tmp_data_dir, "pat-apply")
    result = apply_pattern(tmp_data_dir / "pat-apply", "part-of", "http://ex.org/test")
    assert len(result["classes"]) == 2
    assert len(result["properties"]) == 2

@pytest.mark.asyncio
async def test_idranges_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/id-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "id-api")
    resp = await admin_client.post("/api/idranges/id-api/allocate", json={})
    assert resp.status_code == 201
    resp2 = await admin_client.get("/api/idranges/id-api")
    assert len(resp2.json()) >= 1

@pytest.mark.asyncio
async def test_patterns_endpoint(admin_client, tmp_data_dir):
    resp = await admin_client.get("/api/patterns/")
    assert resp.status_code == 200
    assert len(resp.json()) >= 5

@pytest.mark.asyncio
async def test_pattern_apply_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/pat-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "pat-api")
    resp = await admin_client.post("/api/patterns/pat-api/apply/part-of", json={"base_iri": "http://ex.org/test"})
    assert resp.status_code == 201
    assert len(resp.json()["classes"]) == 2
