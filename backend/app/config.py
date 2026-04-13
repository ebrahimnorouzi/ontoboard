"""Centralised settings — all config comes from environment variables."""

import os
from pathlib import Path


# ── Paths ──────────────────────────────────────────────────────
DATA_DIR = Path(os.getenv("DATA_DIR", "/app/data"))
DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{DATA_DIR / 'ontoboard.db'}")

# ── ROBOT ─────────────────────────────────────────────────────
# ROBOT is installed directly in the backend image (robot.jar + Java).
# No Docker-in-Docker needed.

# ── Auth ───────────────────────────────────────────────────────
SECRET_KEY = os.getenv("SECRET_KEY", "change-me-in-production-please")
ACCESS_TOKEN_EXPIRE_MINUTES = int(os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", "480"))
ALGORITHM = "HS256"

# ── Redis ──────────────────────────────────────────────────────
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")

# ── Admin bootstrap ───────────────────────────────────────────
ADMIN_USERNAME = os.getenv("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "admin")
ADMIN_EMAIL = os.getenv("ADMIN_EMAIL", "admin@ontoboard.local")
