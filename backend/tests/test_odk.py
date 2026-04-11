"""Tests for ODK build endpoint."""

import asyncio

import pytest


@pytest.mark.asyncio
async def test_odk_build_requires_auth(client):
    resp = await client.post("/api/odk/nonexistent/build")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_odk_build_board_not_found(admin_client):
    resp = await admin_client.post("/api/odk/nonexistent/build")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_odk_build_returns_stream(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/build-test")
    await asyncio.sleep(0.1)

    resp = await admin_client.post("/api/odk/build-test/build")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")
