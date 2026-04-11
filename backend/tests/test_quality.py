"""Tests for quality evaluation and registry compliance."""

import asyncio, textwrap
from pathlib import Path
import pytest
from app.services.quality import calculate_oquare, check_registry_compliance

Q_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
         xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <owl:Ontology rdf:about="http://ex.org/t"><rdfs:label>Test</rdfs:label><dc:creator>Admin</dc:creator></owl:Ontology>
        <owl:Class rdf:about="http://ex.org/t#A"><rdfs:label>A</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://ex.org/t#B"><rdfs:label>B</rdfs:label><rdfs:subClassOf rdf:resource="http://ex.org/t#A"/></owl:Class>
    </rdf:RDF>
""")

def _setup(d, bid):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True); (p / f"{bid}.owl").write_text(Q_OWL)

def test_oquare(tmp_data_dir):
    _setup(tmp_data_dir, "q-oq")
    result = calculate_oquare(tmp_data_dir / "q-oq")
    assert result["overall_score"] > 0
    assert result["entity_count"] >= 2

def test_compliance_bioportal(tmp_data_dir):
    _setup(tmp_data_dir, "q-bp")
    checks = check_registry_compliance(tmp_data_dir / "q-bp", "bioportal")
    fields = [c["field"] for c in checks]
    assert "label" in fields
    assert "creator" in fields

def test_compliance_ols(tmp_data_dir):
    _setup(tmp_data_dir, "q-ols")
    checks = check_registry_compliance(tmp_data_dir / "q-ols", "ols")
    assert any(c["field"] == "versionIRI" for c in checks)

@pytest.mark.asyncio
async def test_oquare_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/q-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "q-api")
    resp = await admin_client.get("/api/quality/q-api/oquare")
    assert resp.status_code == 200
    assert resp.json()["overall_score"] > 0

@pytest.mark.asyncio
async def test_compliance_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/q-comp"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "q-comp")
    resp = await admin_client.get("/api/quality/q-comp/compliance/bioportal")
    assert resp.status_code == 200
