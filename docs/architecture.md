# Architecture

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
                   |        |  | Redis queue | |
                   |        |  | odkfull     | |
                   |        |  +-------------+ |
                   |        |                  |
                   +--------+------------------+
                         Shared ./data/ volume
```

## Services

### 1. Frontend (React + Cytoscape.js)

| Property | Value |
|----------|-------|
| Image | `ontoboard-frontend:latest` |
| Base | `node:20-alpine` |
| Port | 3000 |
| Stack | React 18, TypeScript, Cytoscape.js, Zustand, Monaco Editor, Vite |

The frontend is a single-page application built with React 18 and TypeScript. The ontology canvas uses Cytoscape.js for graph rendering with dagre, cose-bilkent, and force-directed layouts. State management uses Zustand with a single `ontologyStore` that tracks all canvas entities (classes, properties, individuals, literals, sticky notes, frames) and auto-saves to the backend with an 800ms debounce.

Key frontend dependencies:
- `cytoscape` + `cytoscape-dagre` + `cytoscape-cose-bilkent` + `cytoscape-edgehandles` -- graph rendering and interaction
- `@monaco-editor/react` -- Manchester Syntax axiom editor
- `yjs` + `y-websocket` -- real-time collaboration sync
- `zustand` -- state management
- `react-router-dom` -- client-side routing

### 2. Backend (FastAPI + rdflib + ROBOT)

| Property | Value |
|----------|-------|
| Image | `ontoboard-backend:latest` |
| Base | `python:3.12-slim` + Java JRE + ROBOT 1.9.6 |
| Port | 8000 |
| Stack | FastAPI, rdflib 7.1, SQLAlchemy, Dulwich, Docker SDK |

The backend is a FastAPI application providing 100+ REST API endpoints across 35 routers. It embeds ROBOT (robot.jar) with Java for OWL processing and uses rdflib for lightweight RDF/OWL parsing. SQLAlchemy manages the SQLite database for user accounts, boards, tasks, comments, and notifications. Dulwich provides Git operations for version tracking.

Key backend dependencies:
- `fastapi` + `uvicorn` -- web framework and ASGI server
- `rdflib` -- RDF/OWL graph parsing and manipulation
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
| Stack | Hocuspocus Server, Yjs, jsonwebtoken |

The collaboration server runs Hocuspocus, a WebSocket server for Yjs document synchronization. Each board gets its own Yjs document, identified by the board ID. The server:
- Authenticates users via JWT tokens (same SECRET_KEY as the backend)
- Persists Yjs document state to disk as binary snapshots (`collab-state.bin`)
- Allows anonymous connections as read-only viewers
- Broadcasts awareness updates (cursor positions, user actions)

### 4. Worker (Redis Queue Consumer)

| Property | Value |
|----------|-------|
| Image | `ontoboard-worker:latest` |
| Base | `python:3.12-slim` |
| Port | None (background service) |
| Stack | Python, Redis, Docker SDK |

The worker service polls a Redis job queue (`ontoboard:jobs`) and executes long-running tasks by spinning up `odkfull` Docker containers. Supported job types:

| Job Type | Description |
|----------|-------------|
| `reason` | Run a reasoner (ELK, HermiT, JFact, Whelk) on the ontology |
| `publish` | Run the ODK publish pipeline (test, prepare_release, publish) |
| `build_docs` | Generate ontology documentation via `make docs` |
| `robot_report` | Run ROBOT report for quality checking |

Each job gets a unique ID. Progress is published via Redis pub/sub (`ontoboard:progress:{job_id}`), and results are stored in Redis with a 1-hour TTL. The worker auto-pulls the ODK Docker image if not found locally.

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
|   |   +-- models/             # SQLAlchemy ORM models
|   |   |   +-- user.py         # User model (id, username, email, role, etc.)
|   |   |   +-- board.py        # Board + BoardMember models
|   |   |   +-- activity.py     # Activity log model
|   |   |   +-- task.py         # Task + TaskComment models
|   |   |   +-- comment.py      # Comment model (board/entity-level)
|   |   |   +-- invite.py       # InviteLink model
|   |   |   +-- notification.py # Notification model
|   |   +-- routers/            # API endpoint definitions (35 router modules)
|   |   +-- schemas/            # Pydantic request/response schemas
|   |   +-- services/           # Business logic layer (30+ service modules)
|   +-- tests/                  # pytest test suite (181 tests)
|   +-- Dockerfile              # Python 3.12 + Java JRE + ROBOT
|   +-- requirements.txt        # Python dependencies
|   +-- requirements-test.txt   # Test dependencies (pytest)
|   +-- pytest.ini              # pytest configuration
+-- frontend/                   # React frontend service
|   +-- src/
|   |   +-- api.ts              # API client (fetch wrapper with auth)
|   |   +-- auth.tsx            # Authentication context and hooks
|   |   +-- main.tsx            # App entry point and routing
|   |   +-- store/
|   |   |   +-- ontologyStore.ts # Zustand store (single source of truth)
|   |   +-- components/         # React components
|   |   |   +-- canvas/         # OntologyCanvas, ContextMenu, EditPopup, Minimap, etc.
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
|   |   |   +-- useOntology.ts  # Ontology data fetching
|   |   |   +-- useAxiomEditor.ts
|   |   |   +-- useReasoning.ts
|   |   |   +-- useSparql.ts
|   |   |   +-- useTasks.ts
|   |   |   +-- useCsvImport.ts
|   |   |   +-- useTreeData.ts
|   |   |   +-- useRestrictions.ts
|   |   |   +-- usePublish.ts
|   |   |   +-- useDocs.ts
|   |   |   +-- useInvite.ts
|   |   +-- collab/             # Collaboration components
|   |   |   +-- useCollaboration.ts  # Yjs connection and sync
|   |   |   +-- useYjsSync.ts       # Canvas state sync via Yjs
|   |   |   +-- CollabStatus.tsx     # Connection status indicator
|   |   +-- pages/              # Page-level components
|   |   |   +-- HomePage.tsx
|   |   |   +-- LoginPage.tsx
|   |   |   +-- SignupPage.tsx
|   |   |   +-- DashboardPage.tsx
|   |   |   +-- BoardPage.tsx
|   |   |   +-- AdminPage.tsx
|   |   |   +-- InvitePage.tsx
|   |   |   +-- FeedbackPage.tsx
|   |   +-- styles/
|   |       +-- global.css
|   +-- Dockerfile              # Node 20 Alpine + Vite dev server
|   +-- package.json            # npm dependencies
|   +-- vite.config.ts
|   +-- tsconfig.json
+-- collab/                     # Collaboration server
|   +-- server.mjs              # Hocuspocus server with JWT auth
|   +-- package.json
|   +-- Dockerfile
+-- worker/                     # Background job worker
|   +-- worker/
|   |   +-- main.py             # Redis queue consumer + Docker SDK
|   +-- requirements.txt
|   +-- Dockerfile
+-- data/                       # Persistent data (Docker volume)
|   +-- ontoboard.db            # SQLite database
|   +-- patterns/
|   |   +-- odpa/               # Built-in ODPA patterns (JSON + OWL)
|   |   +-- user/               # User-uploaded patterns
|   +-- {username}/
|       +-- {board-id}/
|           +-- src/
|           |   +-- ontology/
|           |       +-- *.owl   # Primary OWL file
|           |       +-- Makefile
|           |       +-- *.yaml  # ODK config
|           +-- canvas.json     # Canvas layout state
|           +-- collab-state.bin # Yjs persistence
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
Other clients receive Yjs update -> reload from backend
```

### Collaboration Flow

```
Client A edits              Client B views
    |                           |
    v                           |
Zustand mutation                |
    |                           |
    v                           |
Auto-save to backend            |
    |                           |
    v                           |
Yjs broadcast (via              |
Hocuspocus WebSocket)  -------> |
    |                           v
    |                    Yjs update received
    |                           |
    |                           v
    |                    loadFromBackend()
    |                           |
    |                           v
    |                    Canvas re-rendered
```

### ODK Job Flow

```
User clicks "Run Reasoning"
         |
         v
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
Worker runs Docker container:
  docker run obolibrary/odkfull robot reason -r ELK -i /work/src/ontology/ont.owl
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

## Database Schema

OntoBoard uses SQLite with SQLAlchemy ORM. The schema consists of 7 tables:

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
|   +-- odpa/                          # Built-in ODPA patterns
|   |   +-- part-of/
|   |   |   +-- metadata.json          # Pattern metadata (name, description, classes, properties)
|   |   |   +-- pattern.owl            # OWL file with pattern axioms
|   |   +-- quality/
|   |   +-- participation/
|   |   +-- classification/
|   |   +-- ...
|   +-- user/                          # User-uploaded patterns
|       +-- {pattern-id}/
|           +-- metadata.json
|           +-- pattern.owl
+-- {board-id}/                        # Per-board data directory
    +-- canvas.json                    # Canvas state (classes, properties, positions, etc.)
    +-- collab-state.bin               # Yjs document persistence
    +-- src/
        +-- ontology/
            +-- {ontology-name}.owl    # Primary OWL file
            +-- {ontology-name}-edit.owl # Edit version (ODK convention)
            +-- Makefile               # ODK Makefile
            +-- {ontology-name}-odk.yaml # ODK configuration
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

The backend exposes 100+ endpoints across 35 router modules, organized by domain:

| Router Prefix | Endpoints | Description |
|---------------|:---------:|-------------|
| `/api/auth` | 3 | Login, signup, current user |
| `/api/users` | 9 | User CRUD, approval, admin |
| `/api/boards` | 14 | Board CRUD, sharing, cloning, activity |
| `/api/owl` | 4 | Canvas load/save, CSV upload, KG build |
| `/api/ontology` | 14 | Metadata, statistics, prefixes, annotations, identity |
| `/api/tree` | 9 | Tree hierarchy, entity CRUD, annotations |
| `/api/axiom` | 5 | Axiom listing, Manchester Syntax, validation |
| `/api/restrictions` | 4 | OWL restrictions (some/all/cardinality/complex) |
| `/api/characteristics` | 5 | Property characteristics, chains, disjoint |
| `/api/reasoning` | 3 | Run reasoner, get inferences, apply fix |
| `/api/sparql` | 3 | SPARQL query, visualization, prefixes |
| `/api/csv` | 4 | CSV upload, preview, build |
| `/api/patterns` | 7 | Pattern library, apply, upload |
| `/api/publish` | 3 | Quality check, publish run, status |
| `/api/odk` | 1 | ODK build |
| `/api/odk-mediator` | 10 | ODK seed, reason, test, verify, release, DOSDP |
| `/api/odk-config` | 5 | ODK config, targets, changelog, CI YAML |
| `/api/odk-setup` | 10 | Board creation, import, file browser |
| `/api/odk-imports` | 8 | Import declaration, terms, Makefile targets |
| `/api/imports` | 3 | OWL import management |
| `/api/version` | 4 | Version info, strategy |
| `/api/search` | 1 | Full-text entity search |
| `/api/refactor` | 4 | Rename IRI, move entity, undo/redo |
| `/api/dlquery` | 4 | DL Query, SWRL rules |
| `/api/robot` | 14 | ROBOT commands (annotate, repair, extract, filter, etc.) |
| `/api/jobs` | 4 | Job submission, status, streaming, listing |
| `/api/tasks` | 7 | Task CRUD, comments, GitHub integration |
| `/api/comments` | 3 | Board/entity comments |
| `/api/docs` | 4 | Documentation build, status, serve |
| `/api/invite` | 5 | Invite link creation, acceptance, info |
| `/api/notifications` | 5 | Notification listing, read, delete |
| `/api/quality` | 4 | OOPS!, OQuaRE, compliance, registry |
| `/api/analysis` | 5 | Unused entities, deprecated, import health, circular deps |
| `/api/idranges` | 5 | ID range allocation, reservation |
| `/api/export` | 2 | Ontology export, ZIP download |
| `/api/help` | 4 | Help topics, import steps |

See [API Reference](api-reference.md) for the complete endpoint listing.
