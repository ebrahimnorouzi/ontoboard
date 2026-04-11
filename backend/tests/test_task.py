"""Tests for task management endpoints."""

import asyncio

import pytest


@pytest.mark.asyncio
async def test_create_task(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-board")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/tasks/task-board", json={
        "title": "Fix Pizza class",
        "description": "The Pizza class needs SubClassOf Food",
        "priority": "high",
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["title"] == "Fix Pizza class"
    assert body["priority"] == "high"
    assert body["status"] == "todo"
    assert body["created_by_username"] == "admin"


@pytest.mark.asyncio
async def test_list_tasks(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-list")
    await asyncio.sleep(0.1)

    await admin_client.post("/api/tasks/task-list", json={"title": "Task 1"})
    await admin_client.post("/api/tasks/task-list", json={"title": "Task 2"})

    resp = await admin_client.get("/api/tasks/task-list")
    assert resp.status_code == 200
    assert len(resp.json()) == 2


@pytest.mark.asyncio
async def test_update_task_status(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-upd")
    await asyncio.sleep(0.1)

    create = await admin_client.post("/api/tasks/task-upd", json={"title": "My task"})
    task_id = create.json()["id"]

    resp = await admin_client.patch(f"/api/tasks/task-upd/{task_id}", json={
        "status": "in_progress",
    })
    assert resp.status_code == 200
    assert resp.json()["status"] == "in_progress"


@pytest.mark.asyncio
async def test_assign_task(admin_client, tmp_data_dir):
    # Create a user to assign to
    await admin_client.post("/api/users/", json={
        "username": "assignee1", "email": "a1@test.local", "password": "pass",
    })
    await admin_client.post("/api/boards/task-assign")
    await asyncio.sleep(0.1)

    create = await admin_client.post("/api/tasks/task-assign", json={
        "title": "Assigned task",
        "assignee_username": "assignee1",
    })
    assert create.status_code == 201
    assert create.json()["assignee_username"] == "assignee1"


@pytest.mark.asyncio
async def test_delete_task(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-del")
    await asyncio.sleep(0.1)

    create = await admin_client.post("/api/tasks/task-del", json={"title": "To delete"})
    task_id = create.json()["id"]

    resp = await admin_client.delete(f"/api/tasks/task-del/{task_id}")
    assert resp.status_code == 200

    listing = await admin_client.get("/api/tasks/task-del")
    assert len(listing.json()) == 0


@pytest.mark.asyncio
async def test_task_comments(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-cmt")
    await asyncio.sleep(0.1)

    create = await admin_client.post("/api/tasks/task-cmt", json={"title": "Commentable"})
    task_id = create.json()["id"]

    # Add comment
    resp = await admin_client.post(f"/api/tasks/task-cmt/{task_id}/comments", json={
        "text": "This is a comment",
    })
    assert resp.status_code == 201
    assert resp.json()["text"] == "This is a comment"
    assert resp.json()["username"] == "admin"

    # List comments
    resp2 = await admin_client.get(f"/api/tasks/task-cmt/{task_id}/comments")
    assert resp2.status_code == 200
    assert len(resp2.json()) == 1


@pytest.mark.asyncio
async def test_task_with_entity_link(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-entity")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/tasks/task-entity", json={
        "title": "Review Pizza class",
        "entity_iri": "http://example.org/pizza#Pizza",
    })
    assert resp.status_code == 201
    assert resp.json()["entity_iri"] == "http://example.org/pizza#Pizza"


@pytest.mark.asyncio
async def test_task_filter_by_status(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-filter")
    await asyncio.sleep(0.1)

    create1 = await admin_client.post("/api/tasks/task-filter", json={"title": "T1"})
    create2 = await admin_client.post("/api/tasks/task-filter", json={"title": "T2"})
    await admin_client.patch(f"/api/tasks/task-filter/{create2.json()['id']}", json={"status": "done"})

    todo = await admin_client.get("/api/tasks/task-filter?status=todo")
    assert len(todo.json()) == 1
    assert todo.json()[0]["title"] == "T1"

    done = await admin_client.get("/api/tasks/task-filter?status=done")
    assert len(done.json()) == 1
    assert done.json()[0]["title"] == "T2"


@pytest.mark.asyncio
async def test_task_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/task-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.get("/api/tasks/task-noauth")
        assert resp.status_code == 401
