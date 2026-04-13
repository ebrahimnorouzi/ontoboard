"""Tests for the comments system — create, list, reply, delete, filter."""

import asyncio

import pytest


BID = "comment-board"


def _setup(d, bid):
    p = d / bid / "src" / "ontology"
    p.mkdir(parents=True, exist_ok=True)
    (p / f"{bid}.owl").write_text(
        '<?xml version="1.0"?>'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"'
        '         xmlns:owl="http://www.w3.org/2002/07/owl#">'
        '<owl:Ontology rdf:about="http://ex.org/t"/>'
        '</rdf:RDF>'
    )


@pytest.mark.asyncio
async def test_create_comment(admin_client, tmp_data_dir):
    _setup(tmp_data_dir, BID)
    await admin_client.post(f"/api/boards/{BID}")
    await asyncio.sleep(0.1)

    resp = await admin_client.post(f"/api/comments/{BID}", json={"text": "Hello board"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["text"] == "Hello board"
    assert body["username"] == "admin"
    assert body["entity_iri"] is None
    assert body["parent_id"] is None


@pytest.mark.asyncio
async def test_list_comments(admin_client, tmp_data_dir):
    bid = "comment-list"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    await admin_client.post(f"/api/comments/{bid}", json={"text": "First"})
    await admin_client.post(f"/api/comments/{bid}", json={"text": "Second"})

    resp = await admin_client.get(f"/api/comments/{bid}")
    assert resp.status_code == 200
    comments = resp.json()
    assert len(comments) == 2
    assert comments[0]["text"] == "First"
    assert comments[1]["text"] == "Second"


@pytest.mark.asyncio
async def test_reply_to_comment(admin_client, tmp_data_dir):
    bid = "comment-reply"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    parent = await admin_client.post(f"/api/comments/{bid}", json={"text": "Parent"})
    parent_id = parent.json()["id"]

    reply = await admin_client.post(
        f"/api/comments/{bid}", json={"text": "Reply", "parent_id": parent_id}
    )
    assert reply.status_code == 201
    assert reply.json()["parent_id"] == parent_id

    # Listing should show threaded structure: parent with nested reply
    resp = await admin_client.get(f"/api/comments/{bid}")
    comments = resp.json()
    assert len(comments) == 1  # only top-level
    assert comments[0]["text"] == "Parent"
    assert len(comments[0]["replies"]) == 1
    assert comments[0]["replies"][0]["text"] == "Reply"


@pytest.mark.asyncio
async def test_delete_own_comment(admin_client, tmp_data_dir):
    bid = "comment-del"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    created = await admin_client.post(f"/api/comments/{bid}", json={"text": "To delete"})
    comment_id = created.json()["id"]

    resp = await admin_client.delete(f"/api/comments/{bid}/{comment_id}")
    assert resp.status_code == 200
    assert resp.json()["success"] is True

    # Verify it is gone
    listing = await admin_client.get(f"/api/comments/{bid}")
    assert len(listing.json()) == 0


@pytest.mark.asyncio
async def test_cannot_delete_others_comment(admin_client, client, tmp_data_dir):
    bid = "comment-deny"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    # Create a second user
    await admin_client.post(
        "/api/users/", json={"username": "user2", "email": "u2@test.local", "password": "pass"}
    )

    # Admin creates a comment
    created = await admin_client.post(f"/api/comments/{bid}", json={"text": "Admin comment"})
    comment_id = created.json()["id"]

    # Login as user2
    login = await client.post(
        "/api/auth/login", json={"username": "user2", "password": "pass"}
    )
    token = login.json()["access_token"]

    # Share board with user2 so they can access comments
    await admin_client.post(
        f"/api/boards/{bid}/share", json={"username": "user2", "role": "editor"}
    )

    # user2 tries to delete admin's comment
    resp = await client.delete(
        f"/api/comments/{bid}/{comment_id}",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_filter_by_entity_iri(admin_client, tmp_data_dir):
    bid = "comment-filter"
    _setup(tmp_data_dir, bid)
    await admin_client.post(f"/api/boards/{bid}")
    await asyncio.sleep(0.1)

    iri = "http://ex.org/t#ClassA"
    await admin_client.post(
        f"/api/comments/{bid}", json={"text": "On ClassA", "entity_iri": iri}
    )
    await admin_client.post(f"/api/comments/{bid}", json={"text": "Board level"})

    # Filter by entity_iri
    resp = await admin_client.get(f"/api/comments/{bid}", params={"entity_iri": iri})
    assert resp.status_code == 200
    comments = resp.json()
    assert len(comments) == 1
    assert comments[0]["entity_iri"] == iri

    # Without filter returns all
    resp_all = await admin_client.get(f"/api/comments/{bid}")
    assert len(resp_all.json()) == 2


@pytest.mark.asyncio
async def test_comment_on_nonexistent_board(admin_client, tmp_data_dir):
    resp = await admin_client.post(
        "/api/comments/no-such-board", json={"text": "Hello"}
    )
    assert resp.status_code == 404
