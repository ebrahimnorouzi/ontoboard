"""Tests for job queue service and endpoints."""

import asyncio
import json

import pytest
from unittest.mock import patch, MagicMock


# ── Unit tests with fakeredis ──────────────────────────────────

def _get_fake_redis():
    import fakeredis
    return fakeredis.FakeRedis(decode_responses=True)


def test_enqueue_and_get_status():
    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        from app.services.queue import enqueue_job, get_job_status

        job_id = enqueue_job("test-board", "reason", {"reasoner": "ELK"})
        assert job_id

        status = get_job_status(job_id)
        assert status is not None
        assert status["job_type"] == "reason"
        assert status["board_id"] == "test-board"
        assert status["status"] == "pending"
        assert status["params"]["reasoner"] == "ELK"


def test_update_job():
    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        from app.services.queue import enqueue_job, get_job_status, update_job

        job_id = enqueue_job("board1", "publish", {})
        update_job(job_id, status="running", progress=50)

        status = get_job_status(job_id)
        assert status["status"] == "running"
        assert status["progress"] == 50


def test_complete_job():
    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        from app.services.queue import enqueue_job, get_job_status, complete_job

        job_id = enqueue_job("board1", "reason", {})
        complete_job(job_id, result={"consistent": True})

        status = get_job_status(job_id)
        assert status["status"] == "completed"
        assert status["progress"] == 100
        assert status["result"]["consistent"] is True


def test_complete_job_with_error():
    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        from app.services.queue import enqueue_job, get_job_status, complete_job

        job_id = enqueue_job("board1", "reason", {})
        complete_job(job_id, error="Reasoning failed")

        status = get_job_status(job_id)
        assert status["status"] == "failed"
        assert status["error"] == "Reasoning failed"


def test_list_board_jobs():
    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        from app.services.queue import enqueue_job, list_board_jobs

        enqueue_job("boardA", "reason", {})
        enqueue_job("boardA", "publish", {})
        enqueue_job("boardB", "reason", {})

        jobs_a = list_board_jobs("boardA")
        assert len(jobs_a) == 2
        assert all(j["board_id"] == "boardA" for j in jobs_a)

        jobs_b = list_board_jobs("boardB")
        assert len(jobs_b) == 1


def test_get_nonexistent_job():
    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        from app.services.queue import get_job_status
        assert get_job_status("nonexistent") is None


# ── Integration tests: API endpoints ──────────────────────────

@pytest.mark.asyncio
async def test_submit_job_endpoint(admin_client, tmp_data_dir):
    """Submit a job — should work even without real Redis (mocked to fakeredis)."""
    await admin_client.post("/api/boards/job-test")
    await asyncio.sleep(0.1)

    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        resp = await admin_client.post("/api/jobs/job-test/submit", json={
            "job_type": "reason",
            "params": {"reasoner": "ELK"},
        })
        assert resp.status_code == 200
        body = resp.json()
        assert body["job_id"]
        assert body["status"] == "pending"


@pytest.mark.asyncio
async def test_submit_invalid_job_type(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/job-bad")
    await asyncio.sleep(0.1)

    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        resp = await admin_client.post("/api/jobs/job-bad/submit", json={
            "job_type": "invalid_type",
        })
        assert resp.status_code == 400


@pytest.mark.asyncio
async def test_get_job_status_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/job-stat")
    await asyncio.sleep(0.1)

    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        # Submit
        submit = await admin_client.post("/api/jobs/job-stat/submit", json={
            "job_type": "publish",
        })
        job_id = submit.json()["job_id"]

        # Get status
        resp = await admin_client.get(f"/api/jobs/status/{job_id}")
        assert resp.status_code == 200
        assert resp.json()["status"] == "pending"


@pytest.mark.asyncio
async def test_list_jobs_endpoint(admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/job-list")
    await asyncio.sleep(0.1)

    fake = _get_fake_redis()
    with patch("app.services.queue._get_redis", return_value=fake):
        await admin_client.post("/api/jobs/job-list/submit", json={"job_type": "reason"})
        await admin_client.post("/api/jobs/job-list/submit", json={"job_type": "publish"})

        resp = await admin_client.get("/api/jobs/job-list/list")
        assert resp.status_code == 200
        assert len(resp.json()) == 2


@pytest.mark.asyncio
async def test_submit_requires_auth(client, admin_client, tmp_data_dir):
    await admin_client.post("/api/boards/job-noauth")
    await asyncio.sleep(0.1)

    from httpx import AsyncClient, ASGITransport
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as anon:
        resp = await anon.post("/api/jobs/job-noauth/submit", json={"job_type": "reason"})
        assert resp.status_code == 401
