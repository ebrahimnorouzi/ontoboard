"""Shared test fixtures for the restructured backend."""

import contextlib
import os
from unittest.mock import patch

import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

os.environ["SECRET_KEY"] = "test-secret"
os.environ["ADMIN_USERNAME"] = "admin"
os.environ["ADMIN_PASSWORD"] = "admin123"
os.environ["ADMIN_EMAIL"] = "admin@test.local"

# Modules that import DATA_DIR at module level — must be patched for tests.
# We only patch modules where DATA_DIR is a module-level attribute
# (i.e., `from app.config import DATA_DIR` at the top of the file).
# Modules that import DATA_DIR inside functions don't need patching here.
_DATA_DIR_MODULES = []

def _find_data_dir_modules():
    """Dynamically find all modules that have DATA_DIR as a module-level attribute."""
    import importlib, pkgutil
    for pkg in ["app.services", "app.routers"]:
        try:
            parent = importlib.import_module(pkg)
            for _, name, _ in pkgutil.iter_modules(parent.__path__):
                mod_name = f"{pkg}.{name}"
                try:
                    mod = importlib.import_module(mod_name)
                    if hasattr(mod, "DATA_DIR"):
                        _DATA_DIR_MODULES.append(mod_name)
                except Exception:
                    pass
        except Exception:
            pass
    # Always patch app.config itself
    if "app.config" not in _DATA_DIR_MODULES:
        _DATA_DIR_MODULES.insert(0, "app.config")

_find_data_dir_modules()


def _noop_git_commit(board_dir, message=""):
    """No-op replacement for git_commit in tests."""
    pass


@pytest.fixture()
def tmp_data_dir(tmp_path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    with contextlib.ExitStack() as stack:
        for mod in _DATA_DIR_MODULES:
            stack.enter_context(patch(f"{mod}.DATA_DIR", data_dir))
        stack.enter_context(patch("app.routers.invite.FRONTEND_URL", "http://test-frontend"))
        # Patch git_commit to no-op — tests don't need actual git repos
        stack.enter_context(patch("app.services.board.git_commit", _noop_git_commit))
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
async def client(db_session):
    """Unauthenticated test client."""
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
