"""Tests for import management."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph, URIRef, OWL

from app.services.imports import list_imports, add_import, remove_import

IMPORT_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/test#" xml:base="http://ex.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/test">
            <owl:imports rdf:resource="http://purl.obolibrary.org/obo/ro.owl"/>
        </owl:Ontology>
        <owl:Class rdf:about="http://ex.org/test#A"><rdfs:label>A</rdfs:label></owl:Class>
    </rdf:RDF>
""")

CATALOG_XML = textwrap.dedent("""\
    <?xml version="1.0" encoding="UTF-8" standalone="no"?>
    <catalog prefer="public" xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
        <uri id="ro" name="http://purl.obolibrary.org/obo/ro.owl" uri="imports/ro.owl"/>
    </catalog>
""")


def _setup(d, bid, owl=IMPORT_OWL):
    ont_dir = d / bid / "src" / "ontology"
    ont_dir.mkdir(parents=True, exist_ok=True)
    (ont_dir / f"{bid}.owl").write_text(owl)
    (ont_dir / "catalog-v001.xml").write_text(CATALOG_XML)
    (ont_dir / "Makefile").write_text("ONT_ID := test\nIMPORT_MODULES := ro\n")


def test_list_imports(tmp_data_dir):
    _setup(tmp_data_dir, "imp-list")
    imports = list_imports(tmp_data_dir / "imp-list")
    assert len(imports) >= 1
    assert any("ro.owl" in i["iri"] for i in imports)


def test_add_import(tmp_data_dir):
    _setup(tmp_data_dir, "imp-add")
    ok = add_import(tmp_data_dir / "imp-add", "http://purl.obolibrary.org/obo/go.owl", "go")
    assert ok
    imports = list_imports(tmp_data_dir / "imp-add")
    assert any("go.owl" in i["iri"] for i in imports)
    # Check Makefile updated
    mf = (tmp_data_dir / "imp-add" / "src" / "ontology" / "Makefile").read_text()
    assert "go" in mf


def test_remove_import(tmp_data_dir):
    _setup(tmp_data_dir, "imp-rem")
    ok = remove_import(tmp_data_dir / "imp-rem", "http://purl.obolibrary.org/obo/ro.owl")
    assert ok
    imports = list_imports(tmp_data_dir / "imp-rem")
    assert not any("ro.owl" in i["iri"] for i in imports)


@pytest.mark.asyncio
async def test_list_imports_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/imp-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "imp-api")
    resp = await admin_client.get("/api/imports/imp-api")
    assert resp.status_code == 200
    assert len(resp.json()) >= 1


@pytest.mark.asyncio
async def test_add_import_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/imp-add-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "imp-add-api")
    resp = await admin_client.post("/api/imports/imp-add-api", json={
        "iri": "http://purl.obolibrary.org/obo/bfo.owl", "prefix": "bfo",
    })
    assert resp.status_code == 201
