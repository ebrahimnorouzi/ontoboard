"""Tests for version management."""

import asyncio
import textwrap
from pathlib import Path

import pytest

from app.services.version import get_version_info, set_version, _bump_version

VERSION_OWL = textwrap.dedent("""\
    <?xml version="1.0"?>
    <rdf:RDF xmlns="http://ex.org/test#" xml:base="http://ex.org/test"
         xmlns:owl="http://www.w3.org/2002/07/owl#"
         xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <owl:Ontology rdf:about="http://ex.org/test">
            <owl:versionInfo>1.0.0</owl:versionInfo>
        </owl:Ontology>
    </rdf:RDF>
""")


def _setup(d, bid, owl=VERSION_OWL):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(owl)


def test_get_version(tmp_data_dir):
    _setup(tmp_data_dir, "ver-get")
    info = get_version_info(tmp_data_dir / "ver-get")
    assert info["version"] == "1.0.0"


def test_bump_patch():
    assert _bump_version("1.0.0", "patch") == "1.0.1"


def test_bump_minor():
    assert _bump_version("1.2.3", "minor") == "1.3.0"


def test_bump_major():
    assert _bump_version("2.1.5", "major") == "3.0.0"


def test_set_version_bump(tmp_data_dir):
    _setup(tmp_data_dir, "ver-bump")
    result = set_version(tmp_data_dir / "ver-bump", bump="minor")
    assert result["version"] == "1.1.0"
    # version.txt should be written
    assert (tmp_data_dir / "ver-bump" / "version.txt").read_text().strip() == "1.1.0"


def test_set_version_iri(tmp_data_dir):
    _setup(tmp_data_dir, "ver-iri")
    result = set_version(tmp_data_dir / "ver-iri", version_iri="http://ex.org/test/1.0")
    assert result["version_iri"] == "http://ex.org/test/1.0"


@pytest.mark.asyncio
async def test_version_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/ver-api")
    await asyncio.sleep(0.1)
    _setup(tmp_data_dir, "ver-api")

    resp = await admin_client.get("/api/version/ver-api")
    assert resp.status_code == 200
    assert resp.json()["version"] == "1.0.0"

    resp2 = await admin_client.put("/api/version/ver-api", json={"bump": "patch"})
    assert resp2.status_code == 200
    assert resp2.json()["version"] == "1.0.1"
