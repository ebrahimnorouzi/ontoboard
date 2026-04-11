"""Shared test fixtures for the restructured backend."""

import os
from unittest.mock import patch, MagicMock

import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

os.environ["SECRET_KEY"] = "test-secret"
os.environ["ADMIN_USERNAME"] = "admin"
os.environ["ADMIN_PASSWORD"] = "admin123"
os.environ["ADMIN_EMAIL"] = "admin@test.local"


@pytest.fixture()
def tmp_data_dir(tmp_path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    with patch("app.config.DATA_DIR", data_dir), \
         patch("app.services.board.DATA_DIR", data_dir), \
         patch("app.services.odk.DATA_DIR", data_dir), \
         patch("app.services.ontology.DATA_DIR", data_dir), \
         patch("app.routers.odk.DATA_DIR", data_dir), \
         patch("app.routers.owl.DATA_DIR", data_dir), \
         patch("app.routers.ontology.DATA_DIR", data_dir), \
         patch("app.routers.axiom.DATA_DIR", data_dir), \
         patch("app.routers.tree.DATA_DIR", data_dir), \
         patch("app.routers.publish.DATA_DIR", data_dir), \
         patch("app.routers.reasoning.DATA_DIR", data_dir), \
         patch("app.routers.csv_import.DATA_DIR", data_dir), \
         patch("app.routers.sparql.DATA_DIR", data_dir), \
         patch("app.routers.docs.DATA_DIR", data_dir), \
         patch("app.routers.invite.FRONTEND_URL", "http://test-frontend"):
        yield data_dir


@pytest.fixture()
def db_session(tmp_data_dir):
    """Create a fresh SQLite database for each test."""
    db_url = f"sqlite:///{tmp_data_dir / 'test.db'}"
    engine = create_engine(db_url, connect_args={"check_same_thread": False})
    _session_factory = sessionmaker(bind=engine)

    from app.database import Base
    Base.metadata.create_all(bind=engine)

    # Bootstrap admin
    session = _session_factory()
    from app.services.user import ensure_admin
    ensure_admin(session, "admin", "admin@test.local", "admin123")
    session.close()

    with patch("app.deps.SessionLocal", _session_factory), \
         patch("app.database.SessionLocal", _session_factory), \
         patch("app.main.SessionLocal", _session_factory):
        yield _session_factory


@pytest.fixture()
def mock_docker():
    mock_client = MagicMock()
    mock_client.images.get.side_effect = Exception("No Docker in tests")
    with patch("app.services.board.docker.from_env", return_value=mock_client), \
         patch("app.services.odk.docker.from_env", return_value=mock_client), \
         patch("app.services.robot.docker.from_env", return_value=mock_client):
        yield mock_client


@pytest.fixture()
async def client(db_session, mock_docker):
    """Unauthenticated test client — fixtures are used via pytest dependency chain."""
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture()
async def admin_client(client):
    """Client that is already logged in as admin."""
    resp = await client.post("/api/auth/login", json={"username": "admin", "password": "admin123"})
    token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token}"
    yield client
