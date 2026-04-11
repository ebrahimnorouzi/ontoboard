"""Tests for ontology metadata, prefix management, and find/replace."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph

from app.services.metadata import get_metadata, update_metadata, add_prefix, find_replace_annotations

META_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/test#" xml:base="http://ex.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
         xmlns:dc="http://purl.org/dc/elements/1.1/">
        <owl:Ontology rdf:about="http://ex.org/test">
            <dc:title>Test Ontology</dc:title>
            <dc:creator>Admin</dc:creator>
        </owl:Ontology>
        <owl:Class rdf:about="http://ex.org/test#Pizza">
            <rdfs:label xml:lang="en">Pizza</rdfs:label>
            <rdfs:comment xml:lang="en">A flat Italian bread</rdfs:comment>
        </owl:Class>
        <owl:Class rdf:about="http://ex.org/test#Calzone">
            <rdfs:label xml:lang="en">Calzone</rdfs:label>
            <rdfs:comment xml:lang="en">A folded Italian bread</rdfs:comment>
        </owl:Class>
    </rdf:RDF>
""")


def _setup(d, bid, owl=META_OWL):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(owl)


def test_get_metadata(tmp_data_dir):
    _setup(tmp_data_dir, "meta-get")
    g = Graph(); g.parse(data=META_OWL, format="xml")
    meta = get_metadata(g)
    assert meta["title"] == "Test Ontology"
    assert meta["creator"] == "Admin"


def test_update_metadata(tmp_data_dir):
    _setup(tmp_data_dir, "meta-upd")
    result = update_metadata(tmp_data_dir / "meta-upd", {"title": "Updated Title", "description": "A test ontology"})
    assert result["title"] == "Updated Title"
    assert result["description"] == "A test ontology"


def test_add_prefix(tmp_data_dir):
    _setup(tmp_data_dir, "meta-pfx")
    ok = add_prefix(tmp_data_dir / "meta-pfx", "myns", "http://myns.org/")
    assert ok


def test_find_replace(tmp_data_dir):
    _setup(tmp_data_dir, "meta-fr")
    count = find_replace_annotations(tmp_data_dir / "meta-fr", "Italian bread", "Italian food")
    assert count == 2  # Pizza and Calzone comments


def test_find_replace_no_match(tmp_data_dir):
    _setup(tmp_data_dir, "meta-fr2")
    count = find_replace_annotations(tmp_data_dir / "meta-fr2", "xyznonexistent", "replacement")
    assert count == 0


# ── Integration tests ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_update_metadata_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/meta-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "meta-api")

    resp = await admin_client.put("/api/ontology/meta-api/metadata", json={
        "fields": {"title": "My Ontology", "creator": "TestUser"},
    })
    assert resp.status_code == 200
    assert resp.json()["title"] == "My Ontology"


@pytest.mark.asyncio
async def test_add_prefix_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/pfx-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "pfx-api")

    resp = await admin_client.post("/api/ontology/pfx-api/prefixes", json={
        "prefix": "ex", "namespace": "http://example.org/",
    })
    assert resp.status_code == 201


@pytest.mark.asyncio
async def test_find_replace_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/fr-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "fr-api")

    resp = await admin_client.post("/api/ontology/fr-api/find-replace", json={
        "find": "Italian bread", "replace": "Italian food",
    })
    assert resp.status_code == 200
    assert resp.json()["replaced"] == 2
