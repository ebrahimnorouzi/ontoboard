# Development Guide

This guide covers the development workflow, code conventions, and architecture patterns used in OntoBoard.

## Development Setup

### Prerequisites

- Python 3.12+
- Node.js 20+
- Docker and Docker Compose
- Git

### Starting Development Mode

```bash
# Build images (first time only)
./run.sh build

# Start with source-mounted hot-reload
./run.sh dev
```

In development mode:
- Frontend source (`frontend/src/`) is mounted into the container; Vite provides hot module replacement
- Backend source (`backend/app/`) is mounted into the container; Uvicorn auto-reloads on file changes
- The collab and worker services use pre-built images

### Running Without Docker

**Backend:**

```bash
cd backend
python -m venv venv
source venv/bin/activate        # Linux/macOS
# venv\Scripts\activate         # Windows

pip install -r requirements.txt
pip install -r requirements-test.txt

# Set environment variables
export DATA_DIR=../data
export SECRET_KEY=dev-secret
export REDIS_URL=redis://localhost:6379/0

uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

**Frontend:**

```bash
cd frontend
npm install

export VITE_API_URL=http://localhost:8000
export VITE_COLLAB_URL=ws://localhost:1234

npm run dev
```

## Running Tests

### Backend Tests

The backend test suite contains 181 tests covering all API endpoints and services.

```bash
cd backend
python -m pytest tests/ -v --tb=short
```

Run specific test files:

```bash
python -m pytest tests/test_boards.py -v
python -m pytest tests/test_reasoning.py -v
python -m pytest tests/test_axiom.py -v
```

Run with coverage:

```bash
python -m pytest tests/ --cov=app --cov-report=html
```

The pytest configuration is in `backend/pytest.ini`:

```ini
[pytest]
testpaths = tests
asyncio_mode = auto
```

### Test Structure

Tests use the FastAPI test client with a test database. The `conftest.py` file provides shared fixtures:

```
backend/tests/
+-- conftest.py           # Shared fixtures (test client, test DB, auth helpers)
+-- test_auth.py          # Authentication tests (5 tests)
+-- test_users.py         # User management tests (8 tests)
+-- test_boards.py        # Board CRUD tests (16 tests)
+-- test_canvas.py        # Canvas load/save tests (13 tests)
+-- test_axiom.py         # Axiom editor tests (13 tests)
+-- test_tree.py          # Tree browser tests (14 tests)
+-- test_reasoning.py     # Reasoning tests (12 tests)
+-- test_sparql.py        # SPARQL query tests (12 tests)
+-- test_csv_import.py    # CSV import tests (13 tests)
+-- test_odk.py           # ODK build tests
+-- test_odk_config.py    # ODK config tests
+-- test_odk_lifecycle.py # ODK lifecycle tests
+-- test_odk_mediator.py  # ODK mediator tests
+-- test_publish.py       # Publish pipeline tests (9 tests)
+-- test_task.py          # Task management tests (9 tests)
+-- test_invite.py        # Invite system tests (10 tests)
+-- test_restrictions.py  # OWL restrictions tests
+-- test_characteristics.py # Property characteristics tests
+-- test_search.py        # Search tests
+-- test_refactor.py      # Refactoring tests
+-- test_imports.py       # Import management tests
+-- test_version.py       # Version management tests
+-- test_dl_query.py      # DL query tests
+-- test_robot_commands.py # ROBOT command tests
+-- test_quality.py       # Quality checks tests
+-- test_analysis.py      # Analysis tools tests
+-- test_docs.py          # Documentation tests (12 tests)
+-- test_queue.py         # Job queue tests (11 tests)
+-- test_notifications.py # Notification tests
+-- test_conversion.py    # Format conversion tests
+-- test_health.py        # Health check tests
+-- test_metadata.py      # Metadata tests (13 tests)
+-- test_owl.py           # OWL parsing tests
+-- test_idranges.py      # ID range tests
+-- test_e2e_lifecycle.py # End-to-end lifecycle tests
+-- test_mwo301_integration.py # Integration test with real ontology
```

## Code Structure Conventions

### Backend: Service/Router Pattern

The backend follows a strict separation between API routing and business logic:

```
app/
+-- routers/     # API endpoint definitions (thin layer)
+-- services/    # Business logic (all heavy processing)
+-- schemas/     # Pydantic models for request/response validation
+-- models/      # SQLAlchemy ORM models (database tables)
```

**Routers** define HTTP endpoints, validate inputs via Pydantic schemas, call service functions, and return responses. They should not contain business logic.

Example router pattern:

```python
# app/routers/reasoning.py

from fastapi import APIRouter, Depends
from app.deps import get_current_user, get_db
from app.schemas.reasoning import ReasoningResult, Inference
from app.services import reasoning as reasoning_svc

router = APIRouter()

@router.post("/{board_id}/run", response_model=ReasoningResult)
async def run_reasoning(
    board_id: str,
    reasoner: str = "ELK",
    user=Depends(get_current_user),
    db=Depends(get_db),
):
    return reasoning_svc.run_reasoner(board_id, reasoner)
```

**Services** contain the actual business logic. They work with rdflib graphs, file I/O, Docker SDK, and other libraries. Services are stateless functions.

Example service pattern:

```python
# app/services/reasoning.py

from pathlib import Path
from app.config import DATA_DIR

def run_reasoner(board_id: str, reasoner: str = "ELK") -> dict:
    board_dir = DATA_DIR / board_id
    owl_file = _find_owl(board_dir)
    # ... reasoning logic using ROBOT subprocess or rdflib ...
    return {"consistent": True, "inferences": [...]}
```

**Schemas** define Pydantic models for request validation and response serialization:

```python
# app/schemas/reasoning.py

from pydantic import BaseModel

class ReasoningResult(BaseModel):
    consistent: bool
    inferences: list
    errors: list[str] = []

class Inference(BaseModel):
    inference_type: str
    subject: str
    subject_label: str
    predicate: str
    object: str
    object_label: str
```

**Models** define SQLAlchemy ORM classes for database tables:

```python
# app/models/board.py

from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base

class Board(Base):
    __tablename__ = "boards"
    id: Mapped[int] = mapped_column(primary_key=True)
    board_id: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    # ...
```

### Dependency Injection

The `deps.py` module provides FastAPI dependencies used across routers:

| Dependency | Purpose |
|------------|---------|
| `get_db()` | Yields a SQLAlchemy session, auto-closes after request |
| `get_current_user_optional()` | Returns User or None (for public endpoints) |
| `get_current_user()` | Returns User or raises 401 (for authenticated endpoints) |
| `require_admin()` | Returns User or raises 403 (for admin endpoints) |

### Frontend: Component Architecture

The frontend follows a component-based architecture with Zustand for state management and custom hooks for data fetching.

#### Directory Layout

```
src/
+-- api.ts              # API client (fetch wrapper with JWT auth)
+-- auth.tsx            # AuthProvider context (login, logout, token management)
+-- main.tsx            # App entry point, React Router setup
+-- store/
|   +-- ontologyStore.ts # Zustand store (single source of truth for canvas state)
+-- components/         # Reusable UI components (organized by feature domain)
+-- hooks/              # Custom React hooks (data fetching, state management)
+-- collab/             # Collaboration-specific components and hooks
+-- pages/              # Page-level components (routed by React Router)
+-- styles/             # Global CSS
```

#### Zustand Store

The `ontologyStore.ts` is the single source of truth for all canvas state. It manages:

- Canvas entities (classes, properties, individuals, literals, sticky notes, frames)
- Selection state
- Undo/redo stacks (max 50 snapshots)
- Prefix colors and pattern assignments
- Inference display state
- Provenance settings
- Auto-save with 800ms debounce

Key design decisions:
- All mutations call `debouncedSave(get)` to trigger auto-save
- Destructive operations push to the undo stack via `pushUndo(get, set)`
- Provenance stamps (`created_by`, `created_at`, `modified_by`, `modified_at`) are automatically added when `trackProvenance` is enabled
- A `setOnSaveCallback` function allows the BoardPage to register a Yjs broadcast after each save

#### Custom Hooks Pattern

Hooks encapsulate API calls and local state for specific features:

```typescript
// hooks/useReasoning.ts

export function useReasoning(boardId: string) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ReasoningResult | null>(null);

  const run = async (reasoner: string) => {
    setRunning(true);
    const data = await apiJson(`/api/reasoning/${boardId}/run`, {
      method: "POST",
      body: JSON.stringify({ reasoner }),
    });
    setResult(data);
    setRunning(false);
  };

  return { running, result, run };
}
```

Available hooks:

| Hook | Purpose |
|------|---------|
| `useOntology` | Ontology metadata and statistics fetching |
| `useAxiomEditor` | Axiom listing, editing, and validation |
| `useReasoning` | Reasoning execution and inference display |
| `useSparql` | SPARQL query execution |
| `useTasks` | Task CRUD and comments |
| `useCsvImport` | CSV upload, analysis, and KG building |
| `useTreeData` | Tree hierarchy data fetching |
| `useRestrictions` | OWL restriction management |
| `usePublish` | Publish pipeline management |
| `useDocs` | Documentation building |
| `useInvite` | Invite link management |

#### API Client

The `api.ts` module provides a typed fetch wrapper:

```typescript
export async function apiJson<T>(path: string, init?: RequestInit): Promise<T> {
  // Adds Authorization header from stored token
  // Base URL from VITE_API_URL environment variable
  // Throws on non-2xx responses
}
```

#### Page Components

Pages are the top-level components routed by React Router:

| Page | Path | Description |
|------|------|-------------|
| `HomePage` | `/` | Landing page |
| `LoginPage` | `/login` | Login form |
| `SignupPage` | `/signup` | Registration form |
| `DashboardPage` | `/dashboard` | Board listing and management |
| `BoardPage` | `/boards/:boardId` | Main ontology editor (canvas + panels) |
| `AdminPage` | `/admin` | User and system administration |
| `InvitePage` | `/invite/:token` | Invite link acceptance |
| `FeedbackPage` | `/feedback` | User feedback form |

#### CSS Modules

Components use CSS Modules for scoped styling:

```
components/
+-- canvas/
    +-- OntologyCanvas.tsx
    +-- OntologyCanvas.module.css
```

This avoids global CSS conflicts and keeps styles co-located with their components.

## Adding New Features

### Adding a New API Endpoint

1. **Create the schema** in `backend/app/schemas/`:
   ```python
   # app/schemas/my_feature.py
   from pydantic import BaseModel

   class MyRequest(BaseModel):
       param: str

   class MyResponse(BaseModel):
       result: str
   ```

2. **Create the service** in `backend/app/services/`:
   ```python
   # app/services/my_feature.py
   def do_something(board_id: str, param: str) -> dict:
       # Business logic here
       return {"result": "done"}
   ```

3. **Create the router** in `backend/app/routers/`:
   ```python
   # app/routers/my_feature.py
   from fastapi import APIRouter, Depends
   from app.deps import get_current_user
   from app.schemas.my_feature import MyRequest, MyResponse
   from app.services import my_feature as svc

   router = APIRouter()

   @router.post("/{board_id}/action", response_model=MyResponse)
   async def action(board_id: str, body: MyRequest, user=Depends(get_current_user)):
       return svc.do_something(board_id, body.param)
   ```

4. **Register the router** in `backend/app/main.py`:
   ```python
   from app.routers import my_feature
   app.include_router(my_feature.router, prefix="/api/my-feature", tags=["my-feature"])
   ```

5. **Write tests** in `backend/tests/test_my_feature.py`:
   ```python
   def test_action(client, auth_header):
       resp = client.post("/api/my-feature/test-board/action",
                          json={"param": "value"},
                          headers=auth_header)
       assert resp.status_code == 200
       assert resp.json()["result"] == "done"
   ```

### Adding a New Frontend Component

1. **Create the component** in `frontend/src/components/my-feature/`:
   ```
   MyFeaturePanel.tsx
   MyFeaturePanel.module.css
   ```

2. **Create a custom hook** (if the feature needs API calls) in `frontend/src/hooks/`:
   ```typescript
   // hooks/useMyFeature.ts
   export function useMyFeature(boardId: string) {
     // API calls and state management
   }
   ```

3. **Add the panel to BoardPage**: Import and render the component in the appropriate sidebar or panel area of `BoardPage.tsx`.

### Adding a New Database Model

1. **Create the model** in `backend/app/models/`:
   ```python
   # app/models/my_model.py
   from sqlalchemy.orm import Mapped, mapped_column
   from app.database import Base

   class MyModel(Base):
       __tablename__ = "my_table"
       id: Mapped[int] = mapped_column(primary_key=True)
       # ... columns
   ```

2. **Import in models/__init__.py** so SQLAlchemy creates the table on startup.

3. The table is auto-created on next application startup via `create_tables()` in `database.py`.

## Key Configuration Files

| File | Purpose |
|------|---------|
| `backend/app/config.py` | All backend configuration from environment variables |
| `docker-compose.yml` | Production service definitions |
| `docker-compose.dev.yml` | Development overrides (source mounts) |
| `frontend/vite.config.ts` | Vite build configuration |
| `frontend/tsconfig.json` | TypeScript configuration |
| `backend/pytest.ini` | pytest test runner configuration |
| `.env.example` | Environment variable template |

## Debugging

### Backend Logs

```bash
docker compose logs backend -f
```

Or in development mode, logs appear directly in the terminal.

### Frontend Logs

Open browser DevTools (F12) to see console logs and network requests.

### Database Inspection

The SQLite database is at `data/ontoboard.db`. You can inspect it with any SQLite client:

```bash
sqlite3 data/ontoboard.db
.tables
.schema boards
SELECT * FROM boards;
```

### API Testing

Use the interactive Swagger UI at `http://localhost:8000/docs` to test API endpoints directly. You can authorize with a JWT token obtained from the login endpoint.
