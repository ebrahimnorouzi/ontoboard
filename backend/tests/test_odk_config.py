"""Tests for ODK config and workflows."""

import asyncio
from pathlib import Path
import pytest
from app.services.odk_config import get_config, save_config, list_makefile_targets, generate_changelog, generate_ci_yaml

def _setup(d, bid):
    p = d / bid / "src" / "ontology"; p.mkdir(parents=True, exist_ok=True)
    (p / "Makefile").write_text("ONT_ID := test\n\nall:\n\t@echo done\n\ncheck:\n\t@echo checking\n")

def test_save_and_get_config(tmp_data_dir):
    _setup(tmp_data_dir, "odk-cfg")
    save_config(tmp_data_dir / "odk-cfg", {"id": "test", "imports": ["ro"]})
    cfg = get_config(tmp_data_dir / "odk-cfg")
    assert cfg["id"] == "test"

def test_list_targets(tmp_data_dir):
    _setup(tmp_data_dir, "odk-tgt")
    targets = list_makefile_targets(tmp_data_dir / "odk-tgt")
    names = [t["name"] for t in targets]
    assert "all" in names

def test_generate_ci_yaml():
    yaml = generate_ci_yaml("test-ont")
    assert "test-ont" in yaml
    assert "make check" in yaml

@pytest.mark.asyncio
async def test_config_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/odk-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "odk-api")
    resp = await admin_client.put("/api/odk-config/odk-api/config", json={"config": {"id": "test"}})
    assert resp.status_code == 200
    resp2 = await admin_client.get("/api/odk-config/odk-api/config")
    assert resp2.json()["id"] == "test"

@pytest.mark.asyncio
async def test_targets_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/odk-tgt-api"); await asyncio.sleep(0.1); _setup(tmp_data_dir, "odk-tgt-api")
    resp = await admin_client.get("/api/odk-config/odk-tgt-api/targets")
    assert resp.status_code == 200

@pytest.mark.asyncio
async def test_ci_yaml_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/odk-ci"); await asyncio.sleep(0.1)
    resp = await admin_client.get("/api/odk-config/odk-ci/ci-yaml")
    assert resp.status_code == 200
    assert "make check" in resp.json()["yaml"]
