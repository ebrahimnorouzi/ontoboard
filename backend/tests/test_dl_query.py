"""Tests for DL Query and SWRL."""

import asyncio, textwrap
from pathlib import Path
import pytest
from rdflib import Graph
from app.services.dl_query import execute_dl_query
from app.services.swrl import list_rules, add_rule, delete_rule

DL_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/t#" xml:base="http://ex.org/t"
         xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/t"/>
        <owl:Class rdf:about="http://ex.org/t#Animal"><rdfs:label>Animal</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://ex.org/t#Cat"><rdfs:label>Cat</rdfs:label><rdfs:subClassOf rdf:resource="http://ex.org/t#Animal"/></owl:Class>
        <owl:NamedIndividual rdf:about="http://ex.org/t#Felix"><rdf:type rdf:resource="http://ex.org/t#Cat"/><rdfs:label>Felix</rdfs:label></owl:NamedIndividual>
    </rdf:RDF>
""")

def _setup(d, bid):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True); (p / f"{bid}.owl").write_text(DL_OWL)

def test_dl_subclasses():
    g = Graph(); g.parse(data=DL_OWL, format="xml")
    r = execute_dl_query(g, "Animal", "subclasses")
    assert any("Cat" in e["label"] for e in r)

def test_dl_superclasses():
    g = Graph(); g.parse(data=DL_OWL, format="xml")
    r = execute_dl_query(g, "Cat", "superclasses")
    assert any("Animal" in e["label"] for e in r)

def test_dl_instances():
    g = Graph(); g.parse(data=DL_OWL, format="xml")
    r = execute_dl_query(g, "Cat", "instances")
    assert any("Felix" in e["label"] for e in r)

def test_dl_not_found():
    g = Graph(); g.parse(data=DL_OWL, format="xml")
    r = execute_dl_query(g, "NonExistent", "subclasses")
    assert any("error" in e.get("type", "") for e in r)

def test_swrl_crud(tmp_data_dir):
    _setup(tmp_data_dir, "swrl-test")
    add_rule(tmp_data_dir / "swrl-test", "hasParent(?x, ?y) ^ hasParent(?y, ?z) -> hasGrandparent(?x, ?z)")
    rules = list_rules(tmp_data_dir / "swrl-test")
    assert len(rules) == 1
    delete_rule(tmp_data_dir / "swrl-test", "hasParent(?x, ?y) ^ hasParent(?y, ?z) -> hasGrandparent(?x, ?z)")
    assert len(list_rules(tmp_data_dir / "swrl-test")) == 0

@pytest.mark.asyncio
async def test_dl_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/dl-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "dl-api")
    resp = await admin_client.post("/api/dlquery/dl-api/dl-query", json={"query": "Animal", "query_type": "subclasses"})
    assert resp.status_code == 200

@pytest.mark.asyncio
async def test_swrl_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/swrl-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "swrl-api")
    resp = await admin_client.post("/api/dlquery/swrl-api/swrl", json={"rule": "test(?x) -> result(?x)"})
    assert resp.status_code == 201
    resp2 = await admin_client.get("/api/dlquery/swrl-api/swrl")
    assert len(resp2.json()) == 1
