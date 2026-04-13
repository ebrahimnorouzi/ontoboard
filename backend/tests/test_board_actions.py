"""Tests for board management — clone, update settings, delete, star, prefix update."""

import asyncio

import pytest


def _setup(d, bid):
    p = d / bid / "src" / "ontology"
    p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(
        '<?xml version="1.0"?>'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"'
        '         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"'
        '         xmlns:owl="http://www.w3.org/2002/07/owl#"'
        '         xml:base="http://ex.org/t">'
        '<owl:Ontology rdf:about="http://ex.org/t"/>'
        '</rdf:RDF>'
    )


# ── Clone board ──────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_clone_board(admin_client, tmp_data_dir):
    bid = "clone-src"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    resp = await admin_client.post(
        f"/api/boards/{bid}/clone", json={"new_board_id": "clone-dst"}
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["board_id"] == "clone-dst"
    assert body["owner_username"] == "admin"

    # Verify the cloned board exists on disk
    assert (tmp_data_dir / "clone-dst" / "src" / "ontology").is_dir()

    # Verify the cloned board is accessible
    resp2 = await admin_client.get("/api/boards/clone-dst")
    assert resp2.status_code == 200


@pytest.mark.asyncio
async def test_clone_board_duplicate_id(admin_client, tmp_data_dir):
    bid = "clone-dup-src"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    # First clone succeeds
    resp = await admin_client.post(
        f"/api/boards/{bid}/clone", json={"new_board_id": "clone-dup-dst"}
    )
    assert resp.status_code == 201

    # Second clone with same ID fails
    resp2 = await admin_client.post(
        f"/api/boards/{bid}/clone", json={"new_board_id": "clone-dup-dst"}
    )
    assert resp2.status_code == 409


@pytest.mark.asyncio
async def test_clone_nonexistent_board(admin_client, tmp_data_dir):
    resp = await admin_client.post(
        "/api/boards/no-such/clone", json={"new_board_id": "clone-new"}
    )
    assert resp.status_code == 404


# ── Update board settings ────────────────────────────────────────


@pytest.mark.asyncio
async def test_update_display_name(admin_client, tmp_data_dir):
    bid = "upd-name"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    resp = await admin_client.patch(
        f"/api/boards/{bid}", json={"display_name": "My Ontology"}
    )
    assert resp.status_code == 200
    assert resp.json()["display_name"] == "My Ontology"


@pytest.mark.asyncio
async def test_update_description(admin_client, tmp_data_dir):
    bid = "upd-desc"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    resp = await admin_client.patch(
        f"/api/boards/{bid}", json={"description": "An updated description"}
    )
    assert resp.status_code == 200
    assert resp.json()["description"] == "An updated description"


@pytest.mark.asyncio
async def test_update_is_public(admin_client, tmp_data_dir):
    bid = "upd-pub"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    resp = await admin_client.patch(f"/api/boards/{bid}", json={"is_public": False})
    assert resp.status_code == 200
    assert resp.json()["is_public"] is False

    # Toggle back
    resp2 = await admin_client.patch(f"/api/boards/{bid}", json={"is_public": True})
    assert resp2.status_code == 200
    assert resp2.json()["is_public"] is True


# ── Update prefix ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_update_prefix(admin_client, tmp_data_dir):
    bid = "upd-prefix"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    # Add a prefix first
    add_resp = await admin_client.post(
        f"/api/ontology/{bid}/prefixes",
        json={"prefix": "ex", "namespace": "http://example.org/"},
    )
    assert add_resp.status_code == 201

    # Update the prefix
    resp = await admin_client.put(
        f"/api/ontology/{bid}/prefixes/ex",
        json={"prefix": "ex2", "namespace": "http://example2.org/"},
    )
    assert resp.status_code == 200
    assert resp.json()["success"] is True


# ── Delete board ─────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_delete_board(admin_client, tmp_data_dir):
    bid = "del-act"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)
    assert (tmp_data_dir / bid).is_dir()

    resp = await admin_client.delete(f"/api/boards/{bid}")
    assert resp.status_code == 200

    # Board should be gone
    resp2 = await admin_client.get(f"/api/boards/{bid}")
    assert resp2.status_code == 404

    # Directory should be removed
    assert not (tmp_data_dir / bid).exists()


@pytest.mark.asyncio
async def test_delete_nonexistent_board(admin_client, tmp_data_dir):
    resp = await admin_client.delete("/api/boards/no-such")
    assert resp.status_code == 404


# ── Star / unstar ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_star_unstar(admin_client, tmp_data_dir):
    bid = "star-test"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    # Star
    resp = await admin_client.post(f"/api/boards/{bid}/star")
    assert resp.status_code == 200
    assert resp.json()["is_starred"] is True

    # Unstar (toggle)
    resp2 = await admin_client.post(f"/api/boards/{bid}/star")
    assert resp2.status_code == 200
    assert resp2.json()["is_starred"] is False


@pytest.mark.asyncio
async def test_star_nonexistent_board(admin_client, tmp_data_dir):
    resp = await admin_client.post("/api/boards/no-such/star")
    assert resp.status_code == 404
