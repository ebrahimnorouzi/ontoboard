"""Tests for analysis: unused entities, deprecation, batch annotation."""

import asyncio, textwrap
from pathlib import Path
import pytest
from app.services.analysis import find_unused_entities, find_deprecated, batch_add_annotation

A_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
         xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/t"/>
        <owl:Class rdf:about="http://ex.org/t#Used"><rdfs:label>Used</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Unused"><rdfs:label>Unused</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Child"><rdfs:label>Child</rdfs:label>
            <rdfs:subClassOf rdf:resource="http://ex.org/t#Used"/>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/t#DeprecatedClass">
            <rdfs:label>Old</rdfs:label><owl:deprecated rdf:datatype="http://www.w3.org/2001/XMLSchema#boolean">true</owl:deprecated>
        </owl:Class>
    </rdf:RDF>
""")

def _setup(d, bid):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True); (p / f"{bid}.owl").write_text(A_OWL)

def test_find_unused(tmp_data_dir):
    _setup(tmp_data_dir, "a-unused")
    unused = find_unused_entities(tmp_data_dir / "a-unused")
    labels = [u["label"] for u in unused]
    assert "Unused" in labels
    assert "Used" not in labels  # Used is referenced by Child

def test_find_deprecated(tmp_data_dir):
    _setup(tmp_data_dir, "a-depr")
    dep = find_deprecated(tmp_data_dir / "a-depr")
    assert len(dep) >= 1
    assert any("Old" in d["label"] or "Deprecated" in d["label"] for d in dep)

def test_batch_annotate(tmp_data_dir):
    _setup(tmp_data_dir, "a-batch")
    count = batch_add_annotation(
        tmp_data_dir / "a-batch",
        ["http://ex.org/t#Used", "http://ex.org/t#Unused"],
        "http://www.w3.org/2000/01/rdf-schema#comment",
        "Batch comment", "en",
    )
    assert count == 2

@pytest.mark.asyncio
async def test_unused_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/a-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "a-api")
    resp = await admin_client.get("/api/analysis/a-api/unused")
    assert resp.status_code == 200

@pytest.mark.asyncio
async def test_deprecated_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/a-dep"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "a-dep")
    resp = await admin_client.get("/api/analysis/a-dep/deprecated")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1

@pytest.mark.asyncio
async def test_batch_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/a-bat"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "a-bat")
    resp = await admin_client.post("/api/analysis/a-bat/batch-annotate", json={
        "entity_iris": ["http://ex.org/t#Used"], "property_iri": "http://www.w3.org/2000/01/rdf-schema#comment",
        "value": "test", "language": "en",
    })
    assert resp.status_code == 200
    assert resp.json()["annotated"] == 1
