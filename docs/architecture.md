# Architecture

> **Scope: the web application.** The Protégé plugin is a separate client with a
> different feature set — see "What it does today" in the
> [README](../README.md), [feature parity](feature-parity.md) and
> [limitations](limitations.md).


OntoBoard is a 5-service microservice architecture orchestrated with Docker Compose. This document describes the system design, data flow, directory structure, and API surface.

## System Overview

```
                         +-------------------+
                         |   Browser Client  |
                         | React + Cytoscape |
                         +--------+----------+
                                  |
                   +--------------+--------------+
                   |              |              |
              HTTP/REST      WebSocket      WebSocket
              :3000           :3000          :1234
                   |              |              |
          +--------v---+  +------v------+  +----v--------+
          |  Frontend   |  |  Frontend   |  |   Collab    |
          |  (Vite)     |  |  proxy to   |  | Hocuspocus  |
          |  React 18   |  |  Backend    |  |  Yjs sync   |
          +--------+---+  +------+------+  +----+--------+
                   |              |              |
                   |         HTTP :8000          |
                   |              |              |
                   |     +-------v--------+     |
                   |     |    Backend     |     |
                   |     |   FastAPI      |     |
                   |     |  rdflib+ROBOT  |     |
                   |     |  owlready2    |     |
                   |     |  Java 21      |     |
                   |     +--+-------+--+-+     |
                   |        |       |  |       |
                   |   SQLite    Redis |  Filesystem
                   |   :file     :6379 |  ./data/
                   |        |       |  |       |
                   |        |  +----v--v-+     |
                   |        |  |  Redis  |     |
                   |        |  | 7-alpine|     |
                   |        |  +----+----+     |
                   |        |       |          |
                   |        |  +----v--------+ |
                   |        |  |   Worker    | |
                   |        |  | ROBOT+Java  | |
                   |        |  | subprocess  | |
                   |        |  +-------------+ |
                   |        |                  |
                   +--------+------------------+
                         Shared ./data/ volume
```

**Key architectural decision**: ROBOT 1.9.6 and Java 21 are installed directly in the backend and worker Docker images. There is no Docker-in-Docker. There is no dependency on the odkfull container at runtime. All ROBOT commands run as local subprocesses via `subprocess.run(["robot", ...])`.

## Services

### 1. Frontend (React + Cytoscape.js)

| Property | Value |
|----------|-------|
| Image | `ontoboard-frontend:latest` |
| Base | `node:20-alpine` |
| Port | 3000 |
| Stack | React 18.3, TypeScript 5.6, Cytoscape.js 3.30, Zustand 5, Monaco Editor, Vite 5 |

The frontend is a single-page application built with React 18 and TypeScript. The ontology canvas uses Cytoscape.js for graph rendering with dagre, cose-bilkent, and force-directed layouts. State management uses Zustand with a single `ontologyStore` that tracks all canvas entities (classes, properties, individuals, literals, sticky notes, frames) and auto-saves to the backend with an 800ms debounce.

The canvas auto-fits the viewport on initial load. Tab state is preserved so visited tabs stay mounted. Debug logging is available for edge rendering diagnostics. Pattern applications are batched into single state updates.

Key frontend dependencies:
- `cytoscape` + `cytoscape-dagre` + `cytoscape-cose-bilkent` + `cytoscape-edgehandles` -- graph rendering and interaction
- `@monaco-editor/react` -- Manchester Syntax axiom editor
- `yjs` + `y-websocket` -- real-time collaboration sync
- `zustand` -- state management
- `react-router-dom` -- client-side routing

### 2. Backend (FastAPI + rdflib + ROBOT + owlready2)

| Property | Value |
|----------|-------|
| Image | `ontoboard-backend:latest` |
| Base | `python:3.12-slim` + Java 21 JRE + ROBOT 1.9.6 + Widoco 1.4.25 + make |
| Port | 8000 |
| Stack | FastAPI 0.115, rdflib 7.1, owlready2 0.47, SQLAlchemy 2, Dulwich |

The backend is a FastAPI application providing 100+ REST API endpoints across 37 routers. It contains 41 service modules with all business logic. ROBOT (robot.jar) with Java runs OWL processing as a local subprocess. Widoco 1.4.25 generates HTML documentation. rdflib handles lightweight RDF/OWL parsing. owlready2 provides an embedded reasoner as an alternative to ROBOT. SQLAlchemy manages the SQLite database for user accounts, boards, tasks, comments, and notifications. Dulwich provides Git operations for version tracking.

Key backend services:
- `manchester_parser.py` -- recursive descent parser for Manchester Syntax class expressions with full OWL 2 coverage (HasSelf, ObjectOneOf, datatype facets; bidirectional: parse and render)
- `swrl.py` -- SWRL rule management (native OWL/XML format with `swrl:Imp`, `swrl:ClassAtom`, `swrl:Variable` triples; backward compatible with annotation-based rules)
- `reasoning.py` -- reasoner integration via ROBOT subprocess and owlready2
- `robot.py` -- ROBOT command execution (7 of 24 commands: convert, report, reason, template, diff, query, explain)
- `imports.py` -- import resolution with download and catalog management
- `ontology.py` -- OWL file parsing and manipulation via rdflib

Key backend dependencies:
- `fastapi` + `uvicorn` -- web framework and ASGI server
- `rdflib` -- RDF/OWL graph parsing and manipulation
- `owlready2` -- embedded OWL reasoner and ontology manipulation
- `sqlalchemy` -- ORM for SQLite/PostgreSQL
- `dulwich` -- pure-Python Git library for version control
- `redis` -- job queue client
- `pyyaml` -- ODK configuration file parsing
- `PyJWT` + `bcrypt` -- authentication

### 3. Collaboration Server (Hocuspocus + Yjs)

| Property | Value |
|----------|-------|
| Image | `ontoboard-collab:latest` |
| Base | `node:20-alpine` |
| Port | 1234 |
| Stack | Hocuspocus Server 3.4, Yjs 13.6, jsonwebtoken |

The collaboration server runs Hocuspocus, a WebSocket server for Yjs document synchronization. Each board gets its own Yjs document, identified by the board ID. The server:
- Authenticates users via JWT tokens (same SECRET_KEY as the backend)
- Provides awareness (cursor sharing, entity locking) and document sync
- Hosts a shared `Y.Array("ops")` per board -- the operation log for the CRDT sync system
- Allows anonymous connections as read-only viewers
- Broadcasts awareness updates (cursor positions, entity selections, user actions)
- Global error handlers prevent crashes from corrupt WebSocket data

**CRDT operation log**: In addition to ephemeral awareness, each Yjs document now carries a `Y.Array<string>` named `"ops"` that serves as an append-only operation log. Each ontology mutation (addClass, updateClass, removeProperty, etc.) is serialized as an `OntologyOperation` JSON object and pushed to this array. Hocuspocus propagates the array delta to all connected clients within ~50ms. The array is not persisted to disk -- it lives in server memory for the duration of the Yjs document (while at least one client is connected). Backend persistence continues via the existing HTTP save endpoint.

### 4. Worker (Redis Queue Consumer + ROBOT + Java)

| Property | Value |
|----------|-------|
| Image | `ontoboard-worker:latest` |
| Base | `python:3.12-slim` + Java 21 JRE + ROBOT 1.9.6 + make |
| Port | None (background service) |
| Stack | Python 3.12, ROBOT 1.9.6, Java 21, Redis |

The worker service polls a Redis job queue (`ontoboard:jobs`) and executes long-running ROBOT tasks as local subprocesses. ROBOT and Java are installed directly in the worker image -- there is no Docker-in-Docker.

Supported job types:

| Job Type | Description |
|----------|-------------|
| `reason` | Run a reasoner (ELK, HermiT, JFact, Whelk) on the ontology |
| `robot_report` | Run ROBOT report for quality checking |
| `robot_explain` | Run ROBOT explain for entailment justifications |
| `convert` | Convert between OWL formats |

Each job gets a unique ID. Progress is published via Redis pub/sub (`ontoboard:progress:{job_id}`), and results are stored in Redis with a 1-hour TTL.

### 5. Redis

| Property | Value |
|----------|-------|
| Image | `redis:7-alpine` |
| Port | 6379 |

Redis serves two purposes:
1. **Job queue**: The backend pushes jobs to a list (`ontoboard:jobs`), and the worker pops them with `BLPOP`
2. **Progress pub/sub**: The worker publishes progress updates, and the backend streams them to clients via SSE

## Directory Structure

```
ontoboard/
+-- backend/                    # FastAPI backend service
|   +-- app/
|   |   +-- config.py           # Environment variable configuration
|   |   +-- database.py         # SQLAlchemy engine and session setup
|   |   +-- deps.py             # Dependency injection (auth, DB sessions)
|   |   +-- main.py             # FastAPI app, router registration, startup
|   |   +-- models/             # SQLAlchemy ORM models (8 files, 7 tables)
|   |   |   +-- user.py         # User model (id, username, email, role, etc.)
|   |   |   +-- board.py        # Board + BoardMember models
|   |   |   +-- activity.py     # Activity log model
|   |   |   +-- task.py         # Task + TaskComment models
|   |   |   +-- comment.py      # Comment model (board/entity-level)
|   |   |   +-- invite.py       # InviteLink model
|   |   |   +-- notification.py # Notification model
|   |   +-- routers/            # API endpoint definitions (37 router modules)
|   |   +-- schemas/            # Pydantic request/response schemas
|   |   +-- services/           # Business logic layer (41 service modules)
|   |       +-- manchester_parser.py  # Recursive descent Manchester parser
|   |       +-- swrl.py              # SWRL rule management
|   |       +-- reasoning.py         # ROBOT + owlready2 reasoning
|   |       +-- robot.py             # ROBOT command execution
|   |       +-- imports.py           # Import resolution and catalog management
|   |       +-- ontology.py          # OWL file parsing via rdflib
|   +-- tests/                  # pytest test suite (48+ files, 500+ functions)
|   |   +-- conftest.py         # Shared fixtures (test client, auth helpers)
|   |   +-- test_tier1_features.py   # Manchester parser, characteristics, chains, XSD, annotations
|   |   +-- test_tier2_features.py   # ROBOT explain, imports, SWRL, embedded reasoner
|   |   +-- test_owl2_features.py    # OWL 2: HasSelf, ObjectOneOf, datatype facets (30 tests)
|   |   +-- test_robot_template.py   # ROBOT Template Builder (23 tests)
|   |   +-- test_*.py           # 44 additional test files
|   +-- seed/
|   |   +-- patterns/           # 13 bundled ODPA patterns (tracked in git)
|   |       +-- agent-role/     # metadata.json + pattern.owl
|   |       +-- classification/
|   |       +-- collection-entity/
|   |       +-- co-participation/
|   |       +-- description/
|   |       +-- information-realization/
|   |       +-- observation/
|   |       +-- participation/
|   |       +-- part-of/
|   |       +-- sequence/
|   |       +-- situation/
|   |       +-- spatial-object/
|   |       +-- time-interval/
|   +-- Dockerfile              # Python 3.12 + Java 21 + ROBOT 1.9.6 + Widoco 1.4.25 + make
|   +-- requirements.txt        # FastAPI, rdflib, owlready2, etc.
|   +-- requirements-test.txt   # Test dependencies (pytest)
|   +-- pytest.ini              # pytest configuration
+-- frontend/                   # React frontend service
|   +-- src/
|   |   +-- api.ts              # API client (fetch wrapper with auth)
|   |   +-- auth.tsx            # Authentication context and hooks
|   |   +-- main.tsx            # App entry point and routing
|   |   +-- store/
|   |   |   +-- ontologyStore.ts # Zustand store (single source of truth)
|   |   +-- components/         # 25 feature directories
|   |   |   +-- canvas/         # OntologyCanvas, ContextMenu, EditPopup, Minimap
|   |   |   +-- tree/           # TreeBrowser (Protege-style hierarchy)
|   |   |   +-- axiom/          # AxiomEditor (Monaco + Manchester Syntax)
|   |   |   +-- patterns/       # PatternLibrary (ODPA patterns)
|   |   |   +-- reasoning/      # ReasoningPanel (ELK, HermiT, etc.)
|   |   |   +-- sparql/         # SparqlPanel (SPARQL query editor)
|   |   |   +-- csv/            # CsvImportWizard
|   |   |   +-- comments/       # CommentsPanel
|   |   |   +-- odk/            # OdkPanel (ODK pipeline controls)
|   |   |   +-- tasks/          # TaskBoard (Kanban)
|   |   |   +-- publish/        # PublishPanel
|   |   |   +-- share/          # ShareDialog
|   |   |   +-- files/          # FileBrowser
|   |   |   +-- docs/           # DocsPanel
|   |   |   +-- restrictions/   # RestrictionBuilder
|   |   |   +-- idranges/       # IdRangeManager
|   |   |   +-- export/         # ExportOntologyDialog
|   |   |   +-- wizard/         # CreateBoardWizard
|   |   |   +-- board/          # BoardSettingsDialog
|   |   +-- hooks/              # Custom React hooks
|   |   +-- collab/             # Collaboration components
|   |   |   +-- useCollaboration.ts  # Yjs connection and sync
|   |   |   +-- useYjsSync.ts       # Canvas state sync via Yjs
|   |   |   +-- CollabStatus.tsx     # Connection + entity lock visibility
|   |   +-- pages/              # 9 page routes
|   |       +-- HomePage.tsx
|   |       +-- LoginPage.tsx
|   |       +-- SignupPage.tsx
|   |       +-- DashboardPage.tsx
|   |       +-- BoardPage.tsx
|   |       +-- AdminPage.tsx
|   |       +-- InvitePage.tsx
|   |       +-- FeedbackPage.tsx
|   |       +-- DocsPage.tsx
|   +-- Dockerfile              # Node 20 Alpine + Vite dev server
|   +-- package.json            # npm dependencies
+-- collab/                     # Collaboration server
|   +-- server.mjs              # Hocuspocus server with JWT auth
|   +-- package.json
|   +-- Dockerfile
+-- worker/                     # Background job worker
|   +-- worker/
|   |   +-- main.py             # Redis queue consumer + ROBOT subprocess
|   +-- requirements.txt
|   +-- Dockerfile              # Python 3.12 + Java 21 + ROBOT 1.9.6 + Widoco 1.4.25 + make
+-- data/                       # Persistent data (Docker volume, gitignored)
|   +-- ontoboard.db            # SQLite database
|   +-- patterns/
|   |   +-- odpa/               # Auto-seeded from backend/seed/patterns/
|   |   |   +-- part-of/
|   |   |   +-- quality/
|   |   |   +-- participation/
|   |   |   +-- classification/
|   |   |   +-- ...
|   |   +-- user/               # User-uploaded patterns
|   |       +-- {username}/
|   |           +-- {pattern-id}/
|   |               +-- metadata.json
|   |               +-- pattern.owl
|   +-- {board-id}/             # Per-board data directory
|       +-- canvas.json         # Canvas state (classes, properties, positions)
|       +-- collab-state.bin    # Yjs document persistence
|       +-- src/
|           +-- ontology/
|               +-- {ontology-name}.owl       # Primary OWL file
|               +-- {ontology-name}-edit.owl  # Edit version (ODK convention)
|               +-- Makefile                  # ODK Makefile
|               +-- {ontology-name}-odk.yaml  # ODK configuration
|               +-- catalog-v001.xml          # Import catalog
|               +-- imports/                  # Mirrored imports
|               +-- components/               # Ontology components
|               +-- reports/                  # ROBOT report output
+-- docs/                       # 7 documentation files
+-- docker-compose.yml          # Production compose
+-- docker-compose.dev.yml      # Development compose (source mounted)
+-- run.sh                      # CLI entry point
+-- .env.example                # Environment variable template
```

## Data Flow

### Canvas Save Flow

```
User edits canvas (drag, create, delete)
         |
         v
Zustand store mutated (addClass, addProperty, etc.)
         |
         v
Provenance stamp added (created_by, created_at, modified_by, modified_at)
  using PROV-O agents, Dublin Core, XSD datatypes
         |
         v
Undo snapshot pushed (max 50 snapshots)
         |
         v
800ms debounce timer starts
         |
         v
POST /api/owl/{board_id}/save
  - Canvas JSON (classes, properties, individuals, literals, sticky notes, frames)
  - Backend writes canvas.json to data/{board_id}/
  - Backend updates OWL file via rdflib (adds/removes triples)
  - Backend records activity log
         |
         v
onSaveCallback invoked -> Yjs broadcast to other clients
         |
         v
Other clients receive Yjs update -> reload from backend within ~1 second
```

### Collaboration Flow (Operation-Based CRDT)

The collaboration system uses an operation-based CRDT architecture. Instead of syncing full state (which causes last-write-wins), individual operations are propagated via a shared Yjs array.

```
Client A edits              Client B views
    |                           |
    v                           |
Zustand mutation                |
    |                           |
    +---> pushOp to Y.Array ----+---> Hocuspocus broadcasts delta
    |                           |
    v                           v
Local state updated        Y.Array observer fires
    |                           |
    v                           v
debouncedSave (800ms)      Merge engine classifies op:
    |                        - "both"           -> apply silently
    v                        - "last-write-wins" -> apply later op
POST /api/owl/.../save       - "add-wins"       -> re-apply the add
    |                        - "conflict"       -> show ConflictBanner
    v                           |
Yjs broadcastSave              v
    |                      applyRemoteOp(op)
    +----(safety net)---->     |
                               v
                          Canvas re-rendered (< 100ms)
```

**Operation schema** (`OntologyOperation`):
```typescript
interface OntologyOperation {
  id: string;           // UUID -- for deduplication
  type: OntologyOpType; // "addClass" | "updateClass" | "removeClass" | ...
  timestamp: number;    // Date.now() on the originator
  userId: string;       // display name of the originator
  data: any;            // operation-specific payload
}
```

**Conflict detection window**: A 2-second sliding window tracks recent ops from all users. When a new op arrives (local or remote), if another user touched the same entity IRI within the window, the pair is classified by the merge engine.

**Merge rule table** (Phase 3):

| Op A | Op B | Resolution |
|------|------|------------|
| `addClass(X)` | `addClass(Y)` | both (additive) |
| `updateClass(X, label)` | `updateClass(X, pos)` | both (different fields) |
| `updateClass(X, label="A")` | `updateClass(X, label="B")` | **conflict** (same field) |
| `addClass(X)` | `removeClass(X)` | add-wins (conservative) |
| `updateClass(X, x=1)` | `updateClass(X, x=2)` | last-write-wins (position ephemeral) |
| `addAxiom(SubClassOf X Y)` | `addAxiom(SubClassOf X Z)` | both (additive) |
| `updateClass(X)` | `removeClass(X)` | add-wins (edit preserved) |

**Entity locking** (awareness layer):
- Client A selects an entity -> Yjs awareness broadcasts selection
- Client B sees entity highlighted with Client A's color
- CollabStatus dropdown shows "A is editing ClassName"

**Safety nets**:
- Polling fallback: If WebSocket disconnects, clients poll sync-check endpoint every 30s
- Save broadcast: After each save, Yjs awareness notifies other clients to reload
- On reconnect: full state reload from backend
- Backend consistency check: `POST /api/reasoning/{board_id}/consistency-check` runs owlready2 HermiT to detect OWL inconsistencies introduced by a merge

### ROBOT Command Flow

```
User clicks "Run Reasoning" / "ROBOT Explain" / etc.
         |
         v
POST /api/reasoning/{board_id}/run  (or /api/robot/{board_id}/explain)
         |
         v
Backend locates the OWL file on disk
         |
         v
Backend runs ROBOT as subprocess:
  subprocess.run(["robot", "reason", "-r", "ELK", "-i", "ont.owl", ...])
         |
         v
ROBOT (Java process) runs locally inside the Docker container
         |
         v
Backend parses ROBOT output
         |
         v
Result returned to frontend via HTTP response
```

For long-running jobs:

```
POST /api/jobs/{board_id}/submit
  { job_type: "reason", params: { reasoner: "ELK" } }
         |
         v
Backend creates job record in Redis
  - Key: ontoboard:job:{job_id}
  - Pushes to list: ontoboard:jobs
         |
         v
Worker BLPOP from ontoboard:jobs
         |
         v
Worker runs ROBOT as local subprocess (Java process, no Docker-in-Docker)
         |
         v
Worker publishes progress via Redis pub/sub:
  ontoboard:progress:{job_id} -> { status: "running", progress: 50 }
         |
         v
Backend streams progress via SSE:
  GET /api/jobs/stream/{job_id}
         |
         v
Frontend displays progress bar and terminal output
         |
         v
Job completed -> result stored in Redis (1h TTL)
```

### Manchester Syntax Parsing Flow

```
User types Manchester expression in axiom editor:
  "Animal and hasPart some (Organ or Tissue) and not Plant"
         |
         v
PUT /api/axiom/{board_id}/axioms/{entity_iri}
         |
         v
manchester_parser.py tokenizes the expression:
  [Animal, and, hasPart, some, (, Organ, or, Tissue, ), and, not, Plant]
         |
         v
Recursive descent parser builds AST:
  IntersectionOf(
    Animal,
    SomeValuesFrom(hasPart, UnionOf(Organ, Tissue)),
    ComplementOf(Plant)
  )
         |
         v
AST converted to rdflib triples (blank nodes for restrictions)
         |
         v
Triples written to OWL file via rdflib graph
         |
         v
200 OK returned to frontend

Reverse direction (render):
  GET /api/axiom/{board_id}/manchester/{entity_iri}
         |
         v
  rdflib graph traversed for entity's axioms
         |
         v
  RDF triples rendered back to Manchester Syntax string
```

## Database Schema

OntoBoard uses SQLite with SQLAlchemy ORM. The schema consists of 7 tables across 8 model files:

### users

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| username | VARCHAR(80) UNIQUE | Login name |
| email | VARCHAR(255) UNIQUE | Email address |
| hashed_password | VARCHAR(255) | bcrypt hash |
| role | VARCHAR(20) | `"admin"` or `"user"` |
| is_active | BOOLEAN | Account active flag |
| display_name | VARCHAR(120) | Optional display name |
| created_at | DATETIME | Account creation time |
| updated_at | DATETIME | Last update time |

### boards

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| board_id | VARCHAR(120) UNIQUE | URL slug (e.g., `"my-ontology"`) |
| display_name | VARCHAR(200) | Human-readable name |
| description | TEXT | Board description |
| owner_id | INTEGER FK(users) | Board owner |
| is_public | BOOLEAN | Public visibility |
| allow_anonymous_view | BOOLEAN | Allow unauthenticated viewing |
| allow_anonymous_edit | BOOLEAN | Allow unauthenticated editing |
| is_starred | BOOLEAN | Starred/favourited |
| tags | VARCHAR(500) | Comma-separated tags |
| created_at | DATETIME | Creation time |
| updated_at | DATETIME | Last update time |

### board_members

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| board_id | INTEGER FK(boards) | Board reference |
| user_id | INTEGER FK(users) | User reference |
| role | VARCHAR(20) | `"editor"` or `"viewer"` |
| added_at | DATETIME | When the share was created |

UNIQUE constraint on (board_id, user_id).

### activities

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| board_id | INTEGER FK(boards) | Board reference |
| user_id | INTEGER FK(users) | Acting user (nullable) |
| action | VARCHAR(50) | Action type (created, edited, shared, built, uploaded, etc.) |
| detail | TEXT | Additional detail text |
| created_at | DATETIME | Timestamp |

### tasks

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| board_id | INTEGER FK(boards) | Board reference |
| title | VARCHAR(300) | Task title |
| description | TEXT | Task description |
| status | VARCHAR(30) | `todo`, `in_progress`, `review`, `done` |
| priority | VARCHAR(20) | `low`, `medium`, `high`, `critical` |
| assignee_id | INTEGER FK(users) | Assigned user (nullable) |
| created_by_id | INTEGER FK(users) | Creator |
| entity_iri | VARCHAR(500) | Linked ontology entity IRI |
| github_issue_url | VARCHAR(500) | GitHub issue URL |
| github_issue_number | INTEGER | GitHub issue number |
| due_date | DATETIME | Due date (nullable) |
| created_at | DATETIME | Creation time |
| updated_at | DATETIME | Last update time |

### task_comments

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| task_id | INTEGER FK(tasks) | Parent task |
| user_id | INTEGER FK(users) | Comment author |
| text | TEXT | Comment text |
| created_at | DATETIME | Timestamp |

### comments

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| board_id | INTEGER FK(boards) | Board reference |
| entity_iri | VARCHAR(500) | Entity IRI (null for board-level comments) |
| user_id | INTEGER FK(users) | Comment author |
| text | TEXT | Comment text with @mentions |
| parent_id | INTEGER FK(comments) | Parent comment for threading |
| created_at | DATETIME | Timestamp |

### invite_links

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| board_id | INTEGER FK(boards) | Board reference |
| token | VARCHAR(64) UNIQUE | Random invite token |
| role | VARCHAR(20) | `"editor"` or `"viewer"` |
| created_by | INTEGER FK(users) | Link creator |
| created_at | DATETIME | Creation time |
| expires_at | DATETIME | Expiration (nullable) |
| max_uses | INTEGER | Maximum uses (0 = unlimited) |
| use_count | INTEGER | Current use count |
| is_active | BOOLEAN | Active flag |

### notifications

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Auto-increment |
| user_id | INTEGER FK(users) | Recipient user |
| category | VARCHAR(30) | `signup`, `activated`, `board_shared`, `board_update`, `mention`, `system` |
| title | VARCHAR(200) | Notification title |
| message | TEXT | Notification message |
| link | VARCHAR(500) | Optional navigation link |
| is_read | BOOLEAN | Read flag |
| created_at | DATETIME | Timestamp |

## File Storage Layout

Each board's ontology files are stored on disk under the shared `data/` volume:

```
data/
+-- ontoboard.db                       # SQLite database
+-- patterns/
|   +-- odpa/                          # Auto-seeded from backend/seed/patterns/
|   |   +-- part-of/
|   |   |   +-- metadata.json          # Pattern metadata (name, description, classes, properties)
|   |   |   +-- pattern.owl            # OWL file with pattern axioms
|   |   +-- classification/
|   |   +-- participation/
|   |   +-- agent-role/
|   |   +-- collection-entity/
|   |   +-- co-participation/
|   |   +-- description/
|   |   +-- information-realization/
|   |   +-- observation/
|   |   +-- sequence/
|   |   +-- situation/
|   |   +-- spatial-object/
|   |   +-- time-interval/
|   +-- user/                          # User-uploaded patterns (per-user)
|       +-- {username}/
|           +-- {pattern-id}/
|               +-- metadata.json
|               +-- pattern.owl
+-- {board-id}/                        # Per-board data directory
    +-- canvas.json                    # Canvas state (classes, properties, positions, etc.)
    +-- kg/                            # CSV import / ROBOT Template Builder files
    |   +-- uploads/                   # Uploaded CSV files
    |   +-- templates/                 # Generated ROBOT template TSV files
    |   +-- output/                    # Merged output OWL files
    +-- src/
        +-- ontology/
            +-- {ontology-name}.owl    # Primary OWL file
            +-- {ontology-name}-edit.owl # Edit version (ODK convention)
            +-- Makefile               # ODK Makefile
            +-- {ontology-name}-odk.yaml # ODK configuration
            +-- catalog-v001.xml       # Import catalog for local resolution
            +-- imports/               # Mirrored imports
            +-- components/            # Ontology components
            +-- reports/               # ROBOT report output
```

## Authentication Flow

OntoBoard uses JWT bearer token authentication:

1. User logs in via `POST /api/auth/login` with username + password
2. Backend verifies credentials (bcrypt) and returns a JWT token
3. Token contains: `sub` (username), `user_id`, `role`, expiration (default: 8 hours)
4. Frontend stores the token and sends it as `Authorization: Bearer <token>` on all API requests
5. The same JWT is used to authenticate with the Hocuspocus collaboration server
6. The `deps.py` module provides FastAPI dependencies: `get_current_user`, `get_current_user_optional`, `require_admin`

## API Overview

The backend exposes 100+ endpoints across 37 router modules, organized by domain:

| Router Prefix | Endpoints | Description |
|---------------|:---------:|-------------|
| `/api/auth` | 3 | Login, signup, current user |
| `/api/users` | 9 | User CRUD, approval, admin |
| `/api/boards` | 14 | Board CRUD, sharing, cloning, activity |
| `/api/owl` | 4 | Canvas load/save, CSV upload, KG build |
| `/api/ontology` | 14 | Metadata, statistics, prefixes, annotations, identity |
| `/api/tree` | 9 | Tree hierarchy, entity CRUD, annotations |
| `/api/axiom` | 5 | Axiom listing, Manchester Syntax parsing, validation |
| `/api/restrictions` | 4 | OWL restrictions (some/all/cardinality/complex) |
| `/api/characteristics` | 5 | Property characteristics, chains, disjoint |
| `/api/reasoning` | 3 | Run reasoner (ROBOT + owlready2), inferences, apply fix |
| `/api/sparql` | 3 | SPARQL query, visualization, prefixes |
| `/api/csv` | 4 | CSV upload, preview, build |
| `/api/patterns` | 7 | Pattern library, apply, upload |
| `/api/publish` | 3 | Quality check, publish run, status |
| `/api/odk` | 1 | ODK build |
| `/api/odk-mediator` | 10 | ODK seed, reason, test, verify, release, DOSDP |
| `/api/odk-config` | 5 | ODK config, targets, changelog, CI YAML |
| `/api/odk-setup` | 10 | Board creation, import, file browser |
| `/api/odk-imports` | 8 | Import declaration, terms, Makefile targets |
| `/api/imports` | 3 | OWL import resolution and management |
| `/api/version` | 4 | Version info, strategy |
| `/api/search` | 1 | Full-text entity search |
| `/api/refactor` | 4 | Rename IRI, move entity, undo/redo |
| `/api/dlquery` | 4 | DL Query, SWRL rules (view/create/delete) |
| `/api/robot` | 14 | ROBOT commands (convert, report, explain, diff, etc.) |
| `/api/jobs` | 4 | Job submission, status, streaming, listing |
| `/api/tasks` | 7 | Task CRUD, comments, GitHub integration |
| `/api/comments` | 3 | Board/entity comments with @mentions |
| `/api/docs` | 4 | Documentation build, status, serve |
| `/api/invite` | 5 | Invite link creation, acceptance, info |
| `/api/notifications` | 5 | Notification listing, read, delete |
| `/api/quality` | 4 | OOPS!, OQuaRE, compliance, registry |
| `/api/analysis` | 5 | Unused entities, deprecated, import health, circular deps |
| `/api/idranges` | 5 | ID range allocation (OWL Functional Syntax support) |
| `/api/export` | 2 | Ontology export, ZIP download |
| `/api/help` | 4 | Help topics, import steps |

See [API Reference](api-reference.md) for the complete endpoint listing.
