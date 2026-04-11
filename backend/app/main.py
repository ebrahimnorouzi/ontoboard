"""OntoBoard FastAPI application."""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import ADMIN_USERNAME, ADMIN_PASSWORD, ADMIN_EMAIL
from app.database import create_tables, SessionLocal
from app.routers import auth, users, boards, odk, owl, ontology, axiom, tree, publish, reasoning, task, csv_import, sparql, docs, jobs, invite
from app.services.user import ensure_admin

logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="OntoBoard API",
    description="Collaborative ontology editor — backend API",
    version="0.2.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Routers ────────────────────────────────────────────────────
app.include_router(auth.router,   prefix="/api/auth",   tags=["auth"])
app.include_router(users.router,  prefix="/api/users",  tags=["users"])
app.include_router(boards.router, prefix="/api/boards", tags=["boards"])
app.include_router(odk.router,    prefix="/api/odk",    tags=["odk"])
app.include_router(owl.router,    prefix="/api/owl",    tags=["owl"])
app.include_router(ontology.router, prefix="/api/ontology", tags=["ontology"])
app.include_router(axiom.router,    prefix="/api/axiom",    tags=["axiom"])
app.include_router(tree.router,     prefix="/api/tree",     tags=["tree"])
app.include_router(publish.router,  prefix="/api/publish",  tags=["publish"])
app.include_router(reasoning.router, prefix="/api/reasoning", tags=["reasoning"])
app.include_router(task.router,      prefix="/api/tasks",     tags=["tasks"])
app.include_router(csv_import.router, prefix="/api/csv",      tags=["csv"])
app.include_router(sparql.router,    prefix="/api/sparql",    tags=["sparql"])
app.include_router(docs.router,      prefix="/api/docs",      tags=["docs"])
app.include_router(jobs.router,      prefix="/api/jobs",      tags=["jobs"])
app.include_router(invite.router,   prefix="/api/invite",   tags=["invite"])


@app.on_event("startup")
def on_startup():
    create_tables()
    # Bootstrap admin from env vars
    db = SessionLocal()
    try:
        admin = ensure_admin(db, ADMIN_USERNAME, ADMIN_EMAIL, ADMIN_PASSWORD)
        logging.info("Admin user '%s' ready (id=%d)", admin.username, admin.id)
    finally:
        db.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
