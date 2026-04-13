"""Tests for the file-based pattern system — ODPA and user patterns."""

import json

import pytest


def _make_odpa_pattern(data_dir, pattern_id, name="Test Pattern"):
    """Create an ODPA pattern on disk with metadata.json and pattern.owl."""
    d = data_dir / "patterns" / "odpa" / pattern_id
    d.mkdir(parents=True, exist_ok=True)
    (d / "metadata.json").write_text(json.dumps({
        "id": pattern_id,
        "name": name,
        "description": f"Description for {name}",
        "category": "structural",
    }))
    (d / "pattern.owl").write_text(
        '<?xml version="1.0"?>'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"'
        '         xmlns:owl="http://www.w3.org/2002/07/owl#"'
        '         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"'
        f'         xml:base="http://example.org/patterns/{pattern_id}">'
        f'<owl:Ontology rdf:about="http://example.org/patterns/{pattern_id}"/>'
        '</rdf:RDF>'
    )


def _make_user_pattern(data_dir, username, pattern_id, name="User Pattern"):
    """Create a user pattern on disk."""
    d = data_dir / "patterns" / "user" / username / pattern_id
    d.mkdir(parents=True, exist_ok=True)
    (d / "metadata.json").write_text(json.dumps({
        "id": pattern_id,
        "name": name,
        "description": f"Description for {name}",
        "category": "structural",
    }))
    (d / "pattern.owl").write_text(
        '<?xml version="1.0"?>'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"'
        '         xmlns:owl="http://www.w3.org/2002/07/owl#"'
        f'         xml:base="http://example.org/patterns/{pattern_id}">'
        f'<owl:Ontology rdf:about="http://example.org/patterns/{pattern_id}"/>'
        '</rdf:RDF>'
    )


@pytest.fixture(autouse=True)
def _reset_pattern_cache():
    """Clear the in-memory pattern cache before each test."""
    from app.services import patterns as pattern_svc
    pattern_svc._patterns = None
    yield
    pattern_svc._patterns = None


# ── Load from directory ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_patterns_load_from_odpa_directory(admin_client, tmp_data_dir):
    _make_odpa_pattern(tmp_data_dir, "observer", "Observer Pattern")

    resp = await admin_client.post("/api/patterns/reload")
    assert resp.status_code == 200
    assert resp.json()["reloaded"] >= 1

    resp2 = await admin_client.get("/api/patterns/")
    patterns = resp2.json()
    ids = [p["id"] for p in patterns]
    assert "observer" in ids

    # Check the pattern has source=odpa
    observer = next(p for p in patterns if p["id"] == "observer")
    assert observer["source"] == "odpa"
    assert observer["name"] == "Observer Pattern"


@pytest.mark.asyncio
async def test_user_patterns_saved_to_user_directory(admin_client, tmp_data_dir):
    resp = await admin_client.post("/api/patterns/upload", data={
        "name": "My Custom Pattern",
        "description": "A user-defined pattern",
        "category": "structural",
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["source"] == "user"
    assert body["uploaded_by"] == "admin"

    # Verify directory structure
    pattern_id = body["id"]
    user_dir = tmp_data_dir / "patterns" / "user" / "admin" / pattern_id
    assert user_dir.is_dir()
    assert (user_dir / "metadata.json").is_file()
    assert (user_dir / "pattern.owl").is_file()


@pytest.mark.asyncio
async def test_delete_user_pattern(admin_client, tmp_data_dir):
    # Create a user pattern
    resp = await admin_client.post("/api/patterns/upload", data={
        "name": "Deletable Pattern",
        "description": "Will be deleted",
    })
    assert resp.status_code == 201
    pattern_id = resp.json()["id"]

    # Delete it
    del_resp = await admin_client.delete(f"/api/patterns/{pattern_id}")
    assert del_resp.status_code == 200
    assert del_resp.json()["deleted"] == pattern_id

    # Verify it is gone from listing
    listing = await admin_client.get("/api/patterns/")
    ids = [p["id"] for p in listing.json()]
    assert pattern_id not in ids

    # Verify the directory was removed
    user_dir = tmp_data_dir / "patterns" / "user" / "admin" / pattern_id
    assert not user_dir.exists()


@pytest.mark.asyncio
async def test_cannot_delete_odpa_pattern(admin_client, tmp_data_dir):
    _make_odpa_pattern(tmp_data_dir, "undeletable", "Cannot Delete")

    # Force reload so the pattern is in cache
    await admin_client.post("/api/patterns/reload")

    resp = await admin_client.delete("/api/patterns/undeletable")
    assert resp.status_code == 400

    # Verify it still exists
    listing = await admin_client.get("/api/patterns/")
    ids = [p["id"] for p in listing.json()]
    assert "undeletable" in ids


@pytest.mark.asyncio
async def test_reload_patterns(admin_client, tmp_data_dir):
    # Start empty
    resp = await admin_client.get("/api/patterns/")
    initial_count = len(resp.json())

    # Add patterns on disk
    _make_odpa_pattern(tmp_data_dir, "reload-test-1", "Reload Test 1")
    _make_odpa_pattern(tmp_data_dir, "reload-test-2", "Reload Test 2")

    # Reload
    reload_resp = await admin_client.post("/api/patterns/reload")
    assert reload_resp.status_code == 200
    reloaded = reload_resp.json()["reloaded"]
    assert reloaded >= 2

    # Verify new patterns are in listing
    resp2 = await admin_client.get("/api/patterns/")
    ids = [p["id"] for p in resp2.json()]
    assert "reload-test-1" in ids
    assert "reload-test-2" in ids
    assert len(resp2.json()) >= initial_count + 2


@pytest.mark.asyncio
async def test_list_patterns_returns_source_field(admin_client, tmp_data_dir):
    _make_odpa_pattern(tmp_data_dir, "src-odpa", "ODPA Source")
    _make_user_pattern(tmp_data_dir, "admin", "src-user", "User Source")

    await admin_client.post("/api/patterns/reload")

    resp = await admin_client.get("/api/patterns/")
    patterns = resp.json()

    odpa_pat = next((p for p in patterns if p["id"] == "src-odpa"), None)
    user_pat = next((p for p in patterns if p["id"] == "src-user"), None)

    assert odpa_pat is not None
    assert odpa_pat["source"] == "odpa"

    assert user_pat is not None
    assert user_pat["source"] == "user"


@pytest.mark.asyncio
async def test_get_single_pattern(admin_client, tmp_data_dir):
    _make_odpa_pattern(tmp_data_dir, "single-get", "Single Get")
    await admin_client.post("/api/patterns/reload")

    resp = await admin_client.get("/api/patterns/single-get")
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"] == "single-get"
    assert body["name"] == "Single Get"


@pytest.mark.asyncio
async def test_get_nonexistent_pattern(admin_client, tmp_data_dir):
    resp = await admin_client.get("/api/patterns/no-such-pattern")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_upload_duplicate_pattern(admin_client, tmp_data_dir):
    await admin_client.post("/api/patterns/upload", data={
        "name": "Duplicate Test",
    })

    resp2 = await admin_client.post("/api/patterns/upload", data={
        "name": "Duplicate Test",
    })
    assert resp2.status_code == 409
