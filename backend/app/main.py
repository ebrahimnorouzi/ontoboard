"""OntoBoard FastAPI application."""

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import ADMIN_USERNAME, ADMIN_PASSWORD, ADMIN_EMAIL
from app.database import create_tables, SessionLocal
from app.routers import (
    auth, users, boards, odk, owl, ontology, axiom, tree, publish, reasoning,
    task, csv_import, sparql, docs, jobs, invite, restrictions, characteristics,
    search, refactor, imports, version, robot_commands,
    dl_query, odk_config, quality, idranges, patterns, analysis,
    odk_mediator, export,
)
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
app.include_router(restrictions.router, prefix="/api/restrictions", tags=["restrictions"])
app.include_router(characteristics.router, prefix="/api/characteristics", tags=["characteristics"])
app.include_router(search.router,   prefix="/api/search",   tags=["search"])
app.include_router(refactor.router, prefix="/api/refactor", tags=["refactor"])
app.include_router(imports.router,  prefix="/api/imports",  tags=["imports"])
app.include_router(version.router,  prefix="/api/version",  tags=["version"])
app.include_router(robot_commands.router, prefix="/api/robot", tags=["robot"])
app.include_router(dl_query.router,  prefix="/api/dlquery",  tags=["dlquery"])
app.include_router(odk_config.router, prefix="/api/odk-config", tags=["odk-config"])
app.include_router(quality.router,   prefix="/api/quality",   tags=["quality"])
app.include_router(idranges.router,  prefix="/api/idranges",  tags=["idranges"])
app.include_router(patterns.router,  prefix="/api/patterns",  tags=["patterns"])
app.include_router(analysis.router,  prefix="/api/analysis",  tags=["analysis"])
app.include_router(odk_mediator.router, prefix="/api/odk-mediator", tags=["odk-mediator"])
app.include_router(export.router,       prefix="/api/export",       tags=["export"])


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
