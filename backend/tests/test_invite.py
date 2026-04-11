"""Tests for the invite link system."""

import asyncio
from datetime import datetime, timedelta
from unittest.mock import patch

import pytest


@pytest.mark.asyncio
async def test_create_invite_editor(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/inv-test")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/invite/inv-test/create", json={
        "role": "editor", "max_uses": 5,
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["role"] == "editor"
    assert body["token"]
    assert body["url"].startswith("http://test-frontend/invite/")
    assert body["expires_at"] is None


@pytest.mark.asyncio
async def test_create_invite_viewer(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/inv-test2")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/invite/inv-test2/create", json={
        "role": "viewer", "expires_hours": 24, "max_uses": 0,
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["role"] == "viewer"
    assert body["expires_at"] is not None


@pytest.mark.asyncio
async def test_accept_invite(admin_client, client, tmp_data_dir):
    # Create a user and a board
    await admin_client.post("/api/users/", json={
        "username": "invitee1", "email": "inv1@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/inv-accept")
    await asyncio.sleep(0.1)

    # Create invite
    resp = await admin_client.post("/api/invite/inv-accept/create", json={
        "role": "editor", "max_uses": 0,
    })
    token = resp.json()["token"]

    # Login as the invitee
    login = await client.post("/api/auth/login", json={
        "username": "invitee1", "password": "pass",
    })
    invitee_token = login.json()["access_token"]

    # Accept invite
    resp = await client.post(
        f"/api/invite/accept/{token}",
        headers={"Authorization": f"Bearer {invitee_token}"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["board_id"] == "inv-accept"
    assert body["role"] == "editor"

    # Verify the user is now a member
    members = await admin_client.get("/api/boards/inv-accept/members")
    assert any(m["username"] == "invitee1" and m["role"] == "editor" for m in members.json())


@pytest.mark.asyncio
async def test_invite_info_public(admin_client, client, tmp_data_dir):
    await admin_client.post("/api/boards/inv-info")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/invite/inv-info/create", json={
        "role": "viewer", "max_uses": 0,
    })
    token = resp.json()["token"]

    # Info endpoint requires no auth
    resp = await client.get(f"/api/invite/info/{token}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["board_name"] == "inv-info"
    assert body["role"] == "viewer"
    assert body["created_by"] == "admin"
    assert body["valid"] is True


@pytest.mark.asyncio
async def test_revoke_invite(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/inv-revoke")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/invite/inv-revoke/create", json={
        "role": "editor", "max_uses": 0,
    })
    token = resp.json()["token"]

    # Revoke
    resp = await admin_client.delete(f"/api/invite/inv-revoke/{token}")
    assert resp.status_code == 200

    # Verify it's no longer listed
    resp = await admin_client.get("/api/invite/inv-revoke/links")
    tokens = [link["token"] for link in resp.json()]
    assert token not in tokens

    # Info should show invalid
    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.get(f"/api/invite/info/{token}")
        assert resp.status_code == 200
        assert resp.json()["valid"] is False


@pytest.mark.asyncio
async def test_expired_invite(admin_client, client, tmp_data_dir):
    await admin_client.post("/api/users/", json={
        "username": "invitee_exp", "email": "exp@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/inv-expired")
    await asyncio.sleep(0.1)

    # Create invite that expires in 1 hour
    resp = await admin_client.post("/api/invite/inv-expired/create", json={
        "role": "editor", "expires_hours": 1, "max_uses": 0,
    })
    token = resp.json()["token"]

    # Patch datetime to simulate expiry
    login = await client.post("/api/auth/login", json={
        "username": "invitee_exp", "password": "pass",
    })
    invitee_token = login.json()["access_token"]

    future = datetime.utcnow() + timedelta(hours=2)
    with patch("app.services.invite.datetime") as mock_dt:
        mock_dt.datetime.utcnow.return_value = future
        mock_dt.timedelta = timedelta

        resp = await client.post(
            f"/api/invite/accept/{token}",
            headers={"Authorization": f"Bearer {invitee_token}"},
        )
        assert resp.status_code == 410


@pytest.mark.asyncio
async def test_max_uses_exceeded(admin_client, client, tmp_data_dir):
    await admin_client.post("/api/users/", json={
        "username": "invitee_max1", "email": "max1@test.local", "password": "pass",
    })
    await admin_client.post("/api/users/", json={
        "username": "invitee_max2", "email": "max2@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/inv-maxuses")
    await asyncio.sleep(0.1)

    # Create invite with max_uses=1
    resp = await admin_client.post("/api/invite/inv-maxuses/create", json={
        "role": "viewer", "max_uses": 1,
    })
    token = resp.json()["token"]

    # First user accepts
    login1 = await client.post("/api/auth/login", json={
        "username": "invitee_max1", "password": "pass",
    })
    t1 = login1.json()["access_token"]
    resp = await client.post(
        f"/api/invite/accept/{token}",
        headers={"Authorization": f"Bearer {t1}"},
    )
    assert resp.status_code == 200

    # Second user should be rejected
    login2 = await client.post("/api/auth/login", json={
        "username": "invitee_max2", "password": "pass",
    })
    t2 = login2.json()["access_token"]
    resp = await client.post(
        f"/api/invite/accept/{token}",
        headers={"Authorization": f"Bearer {t2}"},
    )
    assert resp.status_code == 410


@pytest.mark.asyncio
async def test_only_owner_can_create_invite(admin_client, client, tmp_data_dir):
    # Create a regular user and a board owned by admin
    await admin_client.post("/api/users/", json={
        "username": "nonadmin", "email": "na@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/inv-perm")
    await asyncio.sleep(0.1)

    # Login as non-admin
    login = await client.post("/api/auth/login", json={
        "username": "nonadmin", "password": "pass",
    })
    na_token = login.json()["access_token"]

    # Try to create invite — should be 403
    resp = await client.post(
        "/api/invite/inv-perm/create",
        json={"role": "viewer", "max_uses": 0},
        headers={"Authorization": f"Bearer {na_token}"},
    )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_only_owner_can_revoke_invite(admin_client, client, tmp_data_dir):
    await admin_client.post("/api/users/", json={
        "username": "nonadmin2", "email": "na2@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/inv-perm2")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/invite/inv-perm2/create", json={
        "role": "viewer", "max_uses": 0,
    })
    token = resp.json()["token"]

    # Login as non-admin
    login = await client.post("/api/auth/login", json={
        "username": "nonadmin2", "password": "pass",
    })
    na_token = login.json()["access_token"]

    resp = await client.delete(
        f"/api/invite/inv-perm2/{token}",
        headers={"Authorization": f"Bearer {na_token}"},
    )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_list_invite_links(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/inv-list")
    await asyncio.sleep(0.1)

    # Create two invites
    await admin_client.post("/api/invite/inv-list/create", json={
        "role": "editor", "max_uses": 0,
    })
    await admin_client.post("/api/invite/inv-list/create", json={
        "role": "viewer", "max_uses": 5,
    })

    resp = await admin_client.get("/api/invite/inv-list/links")
    assert resp.status_code == 200
    links = resp.json()
    assert len(links) == 2
    roles = {link["role"] for link in links}
    assert roles == {"editor", "viewer"}
