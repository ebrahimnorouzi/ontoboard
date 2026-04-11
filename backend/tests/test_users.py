"""Tests for user management endpoints (admin only)."""

import pytest


@pytest.mark.asyncio
async def test_list_users_as_admin(admin_client):
    resp = await admin_client.get("/api/users/")
    assert resp.status_code == 200
    users = resp.json()
    assert len(users) >= 1
    assert any(u["username"] == "admin" for u in users)


@pytest.mark.asyncio
async def test_list_users_unauthenticated(client):
    resp = await client.get("/api/users/")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_create_user(admin_client):
    resp = await admin_client.post("/api/users/", json={
        "username": "alice",
        "email": "alice@test.local",
        "password": "secret123",
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["username"] == "alice"
    assert body["role"] == "user"


@pytest.mark.asyncio
async def test_create_duplicate_user(admin_client):
    await admin_client.post("/api/users/", json={
        "username": "bob", "email": "bob@test.local", "password": "pass",
    })
    resp = await admin_client.post("/api/users/", json={
        "username": "bob", "email": "bob2@test.local", "password": "pass",
    })
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_get_user(admin_client):
    create = await admin_client.post("/api/users/", json={
        "username": "charlie", "email": "charlie@test.local", "password": "pass",
    })
    user_id = create.json()["id"]
    resp = await admin_client.get(f"/api/users/{user_id}")
    assert resp.status_code == 200
    assert resp.json()["username"] == "charlie"
    assert "board_count" in resp.json()


@pytest.mark.asyncio
async def test_update_user(admin_client):
    create = await admin_client.post("/api/users/", json={
        "username": "dave", "email": "dave@test.local", "password": "pass",
    })
    user_id = create.json()["id"]
    resp = await admin_client.patch(f"/api/users/{user_id}", json={"display_name": "Dave S."})
    assert resp.status_code == 200
    assert resp.json()["display_name"] == "Dave S."


@pytest.mark.asyncio
async def test_deactivate_user(admin_client):
    create = await admin_client.post("/api/users/", json={
        "username": "eve", "email": "eve@test.local", "password": "pass",
    })
    user_id = create.json()["id"]
    resp = await admin_client.delete(f"/api/users/{user_id}")
    assert resp.status_code == 200

    # Verify user is deactivated
    detail = await admin_client.get(f"/api/users/{user_id}")
    assert detail.json()["is_active"] is False


@pytest.mark.asyncio
async def test_non_admin_cannot_create_user(client, admin_client):
    # Create a regular user
    await admin_client.post("/api/users/", json={
        "username": "regular", "email": "regular@test.local", "password": "pass",
    })
    # Login as regular user
    login = await client.post("/api/auth/login", json={"username": "regular", "password": "pass"})
    token = login.json()["access_token"]

    resp = await client.post(
        "/api/users/",
        json={"username": "hack", "email": "h@test.local", "password": "x"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 403
