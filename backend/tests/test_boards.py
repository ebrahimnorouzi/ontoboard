"""Tests for board CRUD, access control, sharing, and activity."""

import asyncio

import pytest


@pytest.mark.asyncio
async def test_list_boards_empty(admin_client):
    resp = await admin_client.get("/api/boards/")
    assert resp.status_code == 200
    assert resp.json() == []


@pytest.mark.asyncio
async def test_list_boards_requires_auth(client):
    resp = await client.get("/api/boards/")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_create_board_requires_auth(client):
    resp = await client.post("/api/boards/test-ont")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_create_board(admin_client, tmp_data_dir):
    resp = await admin_client.post("/api/boards/test-ont")
    assert resp.status_code == 201
    body = resp.json()
    assert body["board_id"] == "test-ont"
    assert body["owner_username"] == "admin"
    assert body["is_public"] is True
    assert body["odk_seeded"] is True
    assert body["git_initialized"] is True
    assert body["user_role"] == "owner"

    # Filesystem check
    assert (tmp_data_dir / "test-ont" / "src" / "ontology" / "test-ont.owl").is_file()
    assert (tmp_data_dir / "test-ont" / ".git").is_dir()
    await asyncio.sleep(0.1)


@pytest.mark.asyncio
async def test_create_board_duplicate(admin_client):
    await admin_client.post("/api/boards/dup")
    resp = await admin_client.post("/api/boards/dup")
    assert resp.status_code == 409
    await asyncio.sleep(0.1)


@pytest.mark.asyncio
async def test_get_board(admin_client):
    await admin_client.post("/api/boards/get-test")
    resp = await admin_client.get("/api/boards/get-test")
    assert resp.status_code == 200
    body = resp.json()
    assert body["board_id"] == "get-test"
    assert body["member_count"] == 0
    assert body["activity_count"] >= 1  # "created" activity
    await asyncio.sleep(0.1)


@pytest.mark.asyncio
async def test_get_board_not_found(admin_client):
    resp = await admin_client.get("/api/boards/nonexistent")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_update_board(admin_client):
    await admin_client.post("/api/boards/upd-test")
    resp = await admin_client.patch("/api/boards/upd-test", json={
        "description": "A test ontology",
        "is_public": False,
    })
    assert resp.status_code == 200
    assert resp.json()["description"] == "A test ontology"
    assert resp.json()["is_public"] is False
    await asyncio.sleep(0.1)


@pytest.mark.asyncio
async def test_delete_board(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/del-test")
    assert (tmp_data_dir / "del-test").is_dir()

    resp = await admin_client.delete("/api/boards/del-test")
    assert resp.status_code == 200
    assert not (tmp_data_dir / "del-test").exists()
    await asyncio.sleep(0.1)


@pytest.mark.asyncio
async def test_invalid_board_id(admin_client):
    resp = await admin_client.post("/api/boards/!!!!")
    assert resp.status_code == 400


# ── Sharing ────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_share_board(admin_client):
    # Create user and board
    await admin_client.post("/api/users/", json={
        "username": "viewer1", "email": "v1@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/share-test")

    resp = await admin_client.post("/api/boards/share-test/share", json={
        "username": "viewer1", "role": "editor",
    })
    assert resp.status_code == 201

    # Verify member list
    members = await admin_client.get("/api/boards/share-test/members")
    assert members.status_code == 200
    assert any(m["username"] == "viewer1" for m in members.json())
    await asyncio.sleep(0.1)


@pytest.mark.asyncio
async def test_unshare_board(admin_client):
    await admin_client.post("/api/users/", json={
        "username": "viewer2", "email": "v2@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/unshare-test")
    await admin_client.post("/api/boards/unshare-test/share", json={
        "username": "viewer2", "role": "viewer",
    })

    resp = await admin_client.delete("/api/boards/unshare-test/share/viewer2")
    assert resp.status_code == 200

    members = await admin_client.get("/api/boards/unshare-test/members")
    assert not any(m["username"] == "viewer2" for m in members.json())
    await asyncio.sleep(0.1)


# ── Access control ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_private_board_hidden_from_anon(admin_client):
    """Anonymous users get 401 on both board detail and listing (auth required)."""
    await admin_client.post("/api/boards/priv-test", json={"is_public": False})
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.get("/api/boards/priv-test")
        assert resp.status_code == 401

        listing = await anon.get("/api/boards/")
        assert listing.status_code == 401


@pytest.mark.asyncio
async def test_shared_user_can_view_private_board(admin_client, client):
    await admin_client.post("/api/users/", json={
        "username": "shared_viewer", "email": "sv@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/priv-shared", json={"is_public": False})
    await admin_client.post("/api/boards/priv-shared/share", json={
        "username": "shared_viewer", "role": "viewer",
    })
    await asyncio.sleep(0.1)

    # Login as shared user
    login = await client.post("/api/auth/login", json={"username": "shared_viewer", "password": "pass"})
    token = login.json()["access_token"]

    resp = await client.get("/api/boards/priv-shared", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    assert resp.json()["user_role"] == "viewer"


# ── Activity log ───────────────────────────────────────────────

@pytest.mark.asyncio
async def test_activity_log(admin_client):
    await admin_client.post("/api/boards/act-test")
    await admin_client.patch("/api/boards/act-test", json={"description": "updated"})
    await asyncio.sleep(0.1)

    resp = await admin_client.get("/api/boards/act-test/activity")
    assert resp.status_code == 200
    activities = resp.json()
    actions = [a["action"] for a in activities]
    assert "created" in actions
    assert "updated" in actions


# ── Admin stats ────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_admin_stats(admin_client):
    await admin_client.post("/api/boards/stat-test")
    await asyncio.sleep(0.1)

    resp = await admin_client.get("/api/boards/admin/stats")
    assert resp.status_code == 200
    body = resp.json()
    assert body["total_users"] >= 1
    assert body["total_boards"] >= 1
    assert body["total_activities"] >= 1


@pytest.mark.asyncio
async def test_admin_stats_requires_admin(client):
    resp = await client.get("/api/boards/admin/stats")
    assert resp.status_code == 401
