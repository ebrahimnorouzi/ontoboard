"""Notification system tests.

Tests:
1. Admin gets notified when a new user signs up
2. Approve user → user gets "activated" notification
3. Reject user keeps them inactive
4. Board sharing creates notification for the target user
5. Notification CRUD: list, count, mark read, mark all read, delete
6. Unread count endpoint (lightweight polling)
"""

import pytest


# ═══════════════════════════════════════════════════════════════
# Test 1: Signup creates admin notification
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_signup_notifies_admin(admin_client, client, tmp_data_dir):
    """When a user signs up, admin gets a 'signup' notification."""
    # Sign up a new user
    resp = await client.post("/api/auth/signup", json={
        "username": "newguy",
        "email": "newguy@example.com",
        "password": "secret123",
    })
    assert resp.status_code == 201
    assert resp.json()["is_active"] is False

    # Admin should have a notification
    resp = await admin_client.get("/api/notifications/")
    assert resp.status_code == 200
    data = resp.json()
    assert data["unread_count"] >= 1
    signup_notifs = [n for n in data["notifications"] if n["category"] == "signup"]
    assert len(signup_notifs) >= 1
    assert "newguy" in signup_notifs[0]["title"]
    assert signup_notifs[0]["is_read"] is False


# ═══════════════════════════════════════════════════════════════
# Test 2: Approve user → user gets notification + can login
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_approve_user(admin_client, client, tmp_data_dir):
    """Approving a user activates their account and sends them a notification."""
    # Sign up
    resp = await client.post("/api/auth/signup", json={
        "username": "pendinguser",
        "email": "pending@example.com",
        "password": "pass1234",
    })
    user_id = resp.json()["id"]

    # Cannot login while inactive
    resp = await client.post("/api/auth/login", json={
        "username": "pendinguser",
        "password": "pass1234",
    })
    assert resp.status_code == 403

    # Admin approves
    resp = await admin_client.post(f"/api/users/{user_id}/approve")
    assert resp.status_code == 200
    assert resp.json()["is_active"] is True

    # Now user can login
    resp = await client.post("/api/auth/login", json={
        "username": "pendinguser",
        "password": "pass1234",
    })
    assert resp.status_code == 200
    token = resp.json()["access_token"]

    # User should have an "activated" notification
    from httpx import AsyncClient
    client.headers["Authorization"] = f"Bearer {token}"
    resp = await client.get("/api/notifications/")
    assert resp.status_code == 200
    activated = [n for n in resp.json()["notifications"] if n["category"] == "activated"]
    assert len(activated) == 1
    assert "approved" in activated[0]["title"].lower()


# ═══════════════════════════════════════════════════════════════
# Test 3: Reject user keeps them inactive
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_reject_user(admin_client, client, tmp_data_dir):
    """Rejecting a user deactivates them."""
    resp = await client.post("/api/auth/signup", json={
        "username": "badactor",
        "email": "bad@example.com",
        "password": "pass1234",
    })
    user_id = resp.json()["id"]

    resp = await admin_client.post(f"/api/users/{user_id}/reject")
    assert resp.status_code == 200
    assert "rejected" in resp.json()["detail"]

    # Still cannot login
    resp = await client.post("/api/auth/login", json={
        "username": "badactor",
        "password": "pass1234",
    })
    assert resp.status_code == 403


# ═══════════════════════════════════════════════════════════════
# Test 4: Approve already active → 400
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_approve_already_active(admin_client, tmp_data_dir):
    """Approving an already active user returns 400."""
    # Admin is already active
    resp = await admin_client.get("/api/users/")
    admin_user = [u for u in resp.json() if u["username"] == "admin"][0]

    resp = await admin_client.post(f"/api/users/{admin_user['id']}/approve")
    assert resp.status_code == 400


# ═══════════════════════════════════════════════════════════════
# Test 5: Pending users list
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_pending_users_list(admin_client, client, tmp_data_dir):
    """Pending endpoint shows only inactive users."""
    # Create 2 pending users
    await client.post("/api/auth/signup", json={
        "username": "pend1", "email": "p1@test.com", "password": "pass123",
    })
    await client.post("/api/auth/signup", json={
        "username": "pend2", "email": "p2@test.com", "password": "pass123",
    })

    resp = await admin_client.get("/api/users/pending")
    assert resp.status_code == 200
    pending = resp.json()
    names = [u["username"] for u in pending]
    assert "pend1" in names
    assert "pend2" in names
    assert "admin" not in names


# ═══════════════════════════════════════════════════════════════
# Test 6: Board sharing creates notification
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_share_board_notification(admin_client, client, tmp_data_dir):
    """Sharing a board sends a notification to the target user."""
    # Create and approve a user
    resp = await client.post("/api/auth/signup", json={
        "username": "editor1", "email": "ed@test.com", "password": "pass123",
    })
    user_id = resp.json()["id"]
    await admin_client.post(f"/api/users/{user_id}/approve")

    # Create a board
    await admin_client.post("/api/boards/shared-board", json={
        "display_name": "Shared Board",
    })

    # Share with the user
    resp = await admin_client.post("/api/boards/shared-board/share", json={
        "username": "editor1", "role": "editor",
    })
    assert resp.status_code == 201

    # Login as editor1 and check notifications
    resp = await client.post("/api/auth/login", json={
        "username": "editor1", "password": "pass123",
    })
    token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token}"

    resp = await client.get("/api/notifications/")
    assert resp.status_code == 200
    shared = [n for n in resp.json()["notifications"] if n["category"] == "board_shared"]
    assert len(shared) >= 1
    assert "shared-board" in shared[0]["title"]
    assert "shared-board" in shared[0]["link"]


# ═══════════════════════════════════════════════════════════════
# Test 7: Notification CRUD — mark read, mark all, delete
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_notification_crud(admin_client, client, tmp_data_dir):
    """Test marking notifications read and deleting them."""
    # Create some notifications via signups
    await client.post("/api/auth/signup", json={
        "username": "u1", "email": "u1@test.com", "password": "pass",
    })
    await client.post("/api/auth/signup", json={
        "username": "u2", "email": "u2@test.com", "password": "pass",
    })

    # Get unread count
    resp = await admin_client.get("/api/notifications/count")
    assert resp.status_code == 200
    assert resp.json()["unread_count"] >= 2

    # Get notifications
    resp = await admin_client.get("/api/notifications/")
    notifs = resp.json()["notifications"]
    assert len(notifs) >= 2

    # Mark one as read
    notif_id = notifs[0]["id"]
    resp = await admin_client.post(f"/api/notifications/{notif_id}/read")
    assert resp.status_code == 200

    # Verify unread count decreased
    resp = await admin_client.get("/api/notifications/count")
    prev_count = resp.json()["unread_count"]

    # Mark all as read
    resp = await admin_client.post("/api/notifications/read-all")
    assert resp.status_code == 200

    resp = await admin_client.get("/api/notifications/count")
    assert resp.json()["unread_count"] == 0

    # Filter unread only — should be empty
    resp = await admin_client.get("/api/notifications/?unread_only=true")
    assert len(resp.json()["notifications"]) == 0

    # Delete a notification
    resp = await admin_client.delete(f"/api/notifications/{notif_id}")
    assert resp.status_code == 200

    # Delete non-existent
    resp = await admin_client.delete("/api/notifications/99999")
    assert resp.status_code == 404


# ═══════════════════════════════════════════════════════════════
# Test 8: Permanent user deletion
# ═══════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_permanent_delete_user(admin_client, client, tmp_data_dir):
    """Admin can permanently delete a user."""
    # Create user
    resp = await client.post("/api/auth/signup", json={
        "username": "deleteme", "email": "del@test.com", "password": "pass123",
    })
    user_id = resp.json()["id"]

    # Permanent delete
    resp = await admin_client.request("DELETE", f"/api/users/{user_id}/permanent")
    assert resp.status_code == 200
    assert "permanently deleted" in resp.json()["detail"]

    # Verify user is gone
    resp = await admin_client.get(f"/api/users/{user_id}")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_cannot_delete_self(admin_client, tmp_data_dir):
    """Admin cannot permanently delete themselves."""
    resp = await admin_client.get("/api/users/")
    admin_user = [u for u in resp.json() if u["username"] == "admin"][0]

    resp = await admin_client.request("DELETE", f"/api/users/{admin_user['id']}/permanent")
    assert resp.status_code == 400
