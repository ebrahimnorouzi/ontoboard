"""Registry submission service — BioPortal, OLS, LOV."""

import json
import logging
import urllib.request
import urllib.error
from pathlib import Path

from app.services.ontology import load_graph, get_ontology_metadata

logger = logging.getLogger("ontoboard.registry")


def submit_to_bioportal(board_dir: Path, api_key: str, ontology_name: str = "") -> dict:
    """Submit ontology to BioPortal REST API."""
    try:
        g = load_graph(board_dir)
        meta = get_ontology_metadata(g)
        owl_content = g.serialize(format="xml")
    except Exception as exc:
        return {"error": f"Failed to load ontology: {exc}"}

    name = ontology_name or meta.get("ontology_iri", "").split("/")[-1]

    # BioPortal API: POST /ontologies
    try:
        payload = json.dumps({
            "acronym": name.upper()[:20],
            "name": name,
            "administeredBy": ["admin"],
        }).encode()
        req = urllib.request.Request(
            "https://data.bioontology.org/ontologies",
            data=payload,
            headers={
                "Authorization": f"apikey token={api_key}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        return {"error": f"BioPortal API error {exc.code}"}
    except Exception as exc:
        return {"error": str(exc)}


def generate_ols_config(board_dir: Path) -> str:
    """Generate OLS-compatible configuration YAML."""
    try:
        g = load_graph(board_dir)
        meta = get_ontology_metadata(g)
    except Exception:
        meta = {"ontology_iri": "", "prefixes": []}

    iri = meta.get("ontology_iri", "http://example.org/ontology")
    return f"""id: {iri.split('/')[-1]}
ontology_purl: {iri}
title: {iri.split('/')[-1]}
description: Ontology registered from OntoBoard
"""


def generate_lov_metadata(board_dir: Path) -> dict:
    """Generate LOV-compatible metadata."""
    try:
        g = load_graph(board_dir)
        meta = get_ontology_metadata(g)
    except Exception:
        meta = {}

    return {
        "uri": meta.get("ontology_iri", ""),
        "prefix": meta.get("ontology_iri", "").split("/")[-1].lower() if meta.get("ontology_iri") else "",
        "titles": [{"value": meta.get("ontology_iri", "").split("/")[-1], "lang": "en"}],
        "descriptions": [{"value": "Ontology created with OntoBoard", "lang": "en"}],
    }
