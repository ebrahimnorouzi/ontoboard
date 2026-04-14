"""Tests for ID ranges and ODP patterns."""

import asyncio
import json
from pathlib import Path
import pytest
from app.services.idranges import generate_idranges_xml, parse_idranges, allocate_range, reserve_next_id
from app.services import patterns as pattern_svc

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

def _seed_patterns(data_dir):
    """Create a test pattern in the ODPA dir (directory-based format)."""
    pattern_dir = data_dir / "patterns" / "odpa" / "part-of"
    pattern_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "patterns" / "user").mkdir(parents=True, exist_ok=True)
    # metadata.json
    (pattern_dir / "metadata.json").write_text(json.dumps({
        "id": "part-of", "name": "Part-Of Pattern",
        "description": "Test", "category": "structural",
    }))
    # pattern.owl with classes and properties
    (pattern_dir / "pattern.owl").write_text(
        '<?xml version="1.0"?>'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"'
        '  xmlns:owl="http://www.w3.org/2002/07/owl#"'
        '  xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"'
        '  xml:base="http://example.org/partof">'
        '<owl:Ontology rdf:about="http://example.org/partof"/>'
        '<owl:Class rdf:about="http://example.org/Whole"><rdfs:label>Whole</rdfs:label></owl:Class>'
        '<owl:Class rdf:about="http://example.org/Part"><rdfs:label>Part</rdfs:label></owl:Class>'
        '<owl:ObjectProperty rdf:about="http://example.org/hasPart"><rdfs:label>hasPart</rdfs:label>'
        '  <rdfs:domain rdf:resource="http://example.org/Whole"/>'
        '  <rdfs:range rdf:resource="http://example.org/Part"/>'
        '</owl:ObjectProperty>'
        '<owl:ObjectProperty rdf:about="http://example.org/isPartOf"><rdfs:label>isPartOf</rdfs:label>'
        '  <rdfs:domain rdf:resource="http://example.org/Part"/>'
        '  <rdfs:range rdf:resource="http://example.org/Whole"/>'
        '</owl:ObjectProperty>'
        '</rdf:RDF>'
    )
    # Reset in-memory cache
    pattern_svc._patterns = None

def test_list_patterns(tmp_data_dir):
    _seed_patterns(tmp_data_dir)
    pats = pattern_svc.list_patterns()
    assert len(pats) >= 1
    assert any(p["id"] == "part-of" for p in pats)

def test_get_pattern(tmp_data_dir):
    _seed_patterns(tmp_data_dir)
    p = pattern_svc.get_pattern("part-of")
    assert p is not None
    assert len(p["classes"]) == 2
    assert len(p["properties"]) == 2

def test_apply_pattern(tmp_data_dir):
    _setup(tmp_data_dir, "pat-apply")
    _seed_patterns(tmp_data_dir)
    result = pattern_svc.apply_pattern(tmp_data_dir / "pat-apply", "part-of", "http://ex.org/test")
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
    _seed_patterns(tmp_data_dir)
    resp = await admin_client.get("/api/patterns/")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1

@pytest.mark.asyncio
async def test_pattern_apply_endpoint(admin_client, tmp_data_dir):
    _seed_patterns(tmp_data_dir)
    await admin_client.post("/api/boards/pat-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "pat-api")
    resp = await admin_client.post("/api/patterns/pat-api/apply/part-of", json={"base_iri": "http://ex.org/test"})
    assert resp.status_code == 201
    assert len(resp.json()["classes"]) == 2
