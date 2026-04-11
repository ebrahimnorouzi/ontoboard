"""Tests for entity search."""

import asyncio
import textwrap
from pathlib import Path

import pytest
from rdflib import Graph

from app.services.search import search_entities

SEARCH_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/test#" xml:base="http://ex.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
        <owl:Ontology rdf:about="http://ex.org/test"/>
        <owl:Class rdf:about="http://ex.org/test#Pizza"><rdfs:label xml:lang="en">Pizza</rdfs:label><rdfs:comment>A flat bread with toppings</rdfs:comment></owl:Class>
        <owl:Class rdf:about="http://ex.org/test#Topping"><rdfs:label xml:lang="en">Topping</rdfs:label></owl:Class>
        <owl:Class rdf:about="http://ex.org/test#MargheritaPizza"><rdfs:label xml:lang="en">Margherita Pizza</rdfs:label></owl:Class>
        <owl:ObjectProperty rdf:about="http://ex.org/test#hasTopping"><rdfs:label xml:lang="en">has topping</rdfs:label></owl:ObjectProperty>
        <owl:NamedIndividual rdf:about="http://ex.org/test#MyPizza"><rdf:type rdf:resource="http://ex.org/test#Pizza"/><rdfs:label xml:lang="en">My Pizza</rdfs:label></owl:NamedIndividual>
    </rdf:RDF>
""")


def _setup(d, bid, owl=SEARCH_OWL):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(owl)


def test_search_by_label():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "Pizza")
    assert len(results) >= 2
    labels = [r["label"] for r in results]
    assert "Pizza" in labels


def test_search_by_iri_fragment():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "Margherita")
    assert any("Margherita" in r["label"] for r in results)


def test_search_by_comment():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "flat bread")
    assert any(r["match_field"] == "comment" for r in results)


def test_search_filter_by_type():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "Pizza", entity_types=["individual"])
    assert all(r["entity_type"] == "individual" for r in results)
    assert len(results) >= 1


def test_search_case_insensitive():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "pizza")
    assert len(results) >= 2


def test_search_limit():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "Pizza", limit=1)
    assert len(results) == 1


def test_search_no_results():
    g = Graph(); g.parse(data=SEARCH_OWL, format="xml")
    results = search_entities(g, "xyznonexistent")
    assert len(results) == 0


@pytest.mark.asyncio
async def test_search_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/search-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "search-api")

    resp = await admin_client.post("/api/search/search-api", json={"query": "Pizza"})
    assert resp.status_code == 200
    assert len(resp.json()) >= 2
