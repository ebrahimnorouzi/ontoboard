# OntoBoard

A collaborative ontology engineering platform built on top of the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). OntoBoard provides visual ontology modeling on an infinite canvas with full ODK/ROBOT pipeline integration, real-time collaboration, and comprehensive ontology lifecycle management.

**Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/)** at [ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering)

Contact: [ebrahim.norouzi@fiz-karlsruhe.de](mailto:ebrahim.norouzi@fiz-karlsruhe.de)

---

## Quick Start

```bash
git clone https://github.com/ISE-FIZKarlsruhe/ontoboard.git
cd ontoboard
./run.sh           # Build + start everything
```

Open [http://localhost:3000](http://localhost:3000) and login with `admin` / `admin`.

> **Requirements**: Docker and Docker Compose. See [docs/getting-started.md](docs/getting-started.md) for detailed setup.

## Architecture

OntoBoard uses a 5-service microservice architecture:

```
                  ┌─────────────────┐
                  │    Frontend      │ :3000
                  │  React 18 +     │
                  │  Cytoscape.js   │
                  └────────┬────────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
┌─────────▼──────┐ ┌──────▼──────┐ ┌───────▼──────┐
│    Backend     │ │   Collab    │ │    Worker    │
│  FastAPI +     │ │ Hocuspocus  │ │   Redis      │
│  ROBOT + Java  │ │ Yjs + JWT   │ │   Consumer   │
│  :8000         │ │ :1234       │ │              │
└────────┬───────┘ └─────────────┘ └──────┬───────┘
         │                                │
         └────────────┬───────────────────┘
                      │
              ┌───────▼───────┐
              │    Redis      │ :6379
              │  Job Queue    │
              └───────────────┘
```

| Service | Stack | Port |
|---------|-------|------|
| **Frontend** | React 18, Cytoscape.js, Zustand, Vite, TypeScript | 3000 |
| **Backend** | FastAPI, rdflib, ROBOT 1.9.6, Java 21, SQLAlchemy | 8000 |
| **Collaboration** | Hocuspocus (Yjs WebSocket server) | 1234 |
| **Worker** | Python, Redis consumer for long-running jobs | -- |
| **Redis** | Redis 7 Alpine — job queue + pub/sub | 6379 |

## Key Features

### Visual Ontology Canvas
- **Cytoscape.js** infinite canvas with dagre, force, and grid layouts
- Drag-and-drop class, individual, and literal creation
- Edge drawing for object properties, data properties, SubClassOf, rdf:type
- Canvas frames and sticky notes for organization
- Minimap for navigation

### Ontology Design Patterns (ODP)
- Curated pattern library from [ontologydesignpatterns.org](http://ontologydesignpatterns.org) (13 patterns)
- Each pattern stored as `metadata.json` + `pattern.owl` in `data/patterns/odpa/`
- User-uploaded patterns saved per user in `data/patterns/user/{username}/`
- Drag-and-drop patterns onto the canvas with auto-coloring

### Axiom Editor
- Manchester Syntax editor with syntax highlighting (Monaco)
- Structured view with per-axiom-type grouping (SubClassOf, EquivalentTo, DisjointWith, etc.)
- Annotation property chooser (rdfs:comment, skos:definition, dcterms:description, etc.)
- Autocomplete from ontology entities

### ODK / ROBOT Integration
- ROBOT 1.9.6 installed directly in the backend image (no Docker-in-Docker)
- `robot report`, `robot reason`, `robot convert`, `robot diff`, `robot query`
- ODK scaffold generation for new boards
- Live SSE terminal for build operations
- Import from GitHub repositories

### Reasoning & Validation
- ELK, HermiT, JFact, Whelk reasoners via ROBOT
- Inferred axioms visualized on the canvas
- ROBOT report with violation details
- Fix suggestions for inconsistencies

### Real-time Collaboration
- **Cursor sharing**: see other users' cursors in real-time via Yjs awareness
- **Entity locking**: when you select an entity, others see it highlighted with your color
- **Instant save broadcast**: when you save, all other users reload within ~1 second
- **Fallback polling**: 30-second backend poll as safety net
- **Comments**: threaded discussions with @mentions and notifications
- **Task board**: Kanban columns (To Do, In Progress, Review, Done)
- **Sharing**: invite links with roles, expiration, and usage limits

### Board Management
- Create boards from scratch, GitHub repos, or file upload (OWL, TTL, RDF, OBO)
- Clone, rename, delete boards
- Board settings: visibility (public/private), description, tags
- Star/unstar for quick access
- File browser with inline editing

### Additional Features
- CSV to Knowledge Graph import wizard
- SPARQL query panel with result visualization
- Provenance tracking (PROV-O + Dublin Core)
- Prefix management with per-prefix canvas coloring
- ID range allocation (Protege-style)
- Git-backed version control
- Quality metrics (OQUARE compliance)

## Commands

```bash
./run.sh              # Build (if needed) + start all services
./run.sh build        # Force rebuild all Docker images
./run.sh up           # Start services (no build)
./run.sh down         # Stop services
./run.sh dev          # Development mode (source mounted for hot-reload)
./run.sh test         # Run backend tests
./run.sh logs         # View service logs
./run.sh clean        # Stop + remove all data
```

## Project Structure

```
ontoboard/
├── backend/                 # FastAPI backend
│   ├── app/
│   │   ├── routers/         # 37 API route modules
│   │   ├── services/        # 40 business logic modules
│   │   ├── models/          # SQLAlchemy ORM models
│   │   └── schemas/         # Pydantic schemas
│   ├── tests/               # 40 test files, 150+ test functions
│   ├── Dockerfile           # Python 3.12 + Java 21 + ROBOT 1.9.6
│   └── requirements.txt
├── frontend/                # React SPA
│   ├── src/
│   │   ├── components/      # 23 feature directories
│   │   ├── pages/           # 8 page routes
│   │   ├── store/           # Zustand state management
│   │   ├── collab/          # Yjs collaboration hooks
│   │   └── hooks/           # Custom React hooks
│   └── Dockerfile
├── collab/                  # Hocuspocus WebSocket server
├── worker/                  # Redis job consumer
├── data/                    # Runtime data (boards, patterns)
│   └── patterns/
│       ├── odpa/            # 13 curated ODPA patterns
│       └── user/            # User-uploaded patterns
├── docs/                    # Documentation
├── docker-compose.yml       # Production setup
├── docker-compose.dev.yml   # Development setup
└── run.sh                   # Orchestration script
```

## API Documentation

Interactive API docs available at [http://localhost:8000/docs](http://localhost:8000/docs) after starting the backend.

**60+ REST endpoints** covering: boards, ontology CRUD, axioms, reasoning, SPARQL, patterns, comments, collaboration, ODK workflows, file management, and more.

See [docs/api-reference.md](docs/api-reference.md) for the full reference.

## Technology Stack

| Layer | Technologies |
|-------|-------------|
| **Frontend** | React 18, TypeScript, Cytoscape.js 3.30, Zustand 5, Monaco Editor, Vite 5 |
| **Backend** | FastAPI, Python 3.12, rdflib 7.1, SQLAlchemy 2, Dulwich (Git) |
| **Ontology Tools** | ROBOT 1.9.6, OWL API, Java 21 |
| **Collaboration** | Yjs 13.6, y-websocket, Hocuspocus 3.4 |
| **Database** | SQLite (dev) / PostgreSQL-ready |
| **Infrastructure** | Docker, Docker Compose, Redis 7 |

## Testing

```bash
./run.sh test         # Run all backend tests
cd backend && python3 -m pytest tests/ -v --tb=short
```

40 test files with 150+ test functions covering: API endpoints, OWL parsing, axiom editing, canvas operations, reasoning, collaboration, comments, patterns, and ODK workflows.

## Documentation

Full documentation is in the [docs/](docs/) directory:
- [Getting Started](docs/getting-started.md)
- [Architecture](docs/architecture.md)
- [Features](docs/features.md)
- [API Reference](docs/api-reference.md)
- [Development Guide](docs/development.md)
- [Limitations & Roadmap](docs/limitations.md)

## License

ISE / FIZ Karlsruhe

---

**OntoBoard** is a research prototype developed at the [Information Service Engineering (ISE)](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering) group of [FIZ Karlsruhe](https://www.fiz-karlsruhe.de).
