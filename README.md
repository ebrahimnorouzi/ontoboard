# OntoBoard

A collaborative ontology engineering platform built on top of the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). OntoBoard provides visual ontology modeling on an infinite canvas with full ROBOT pipeline integration, real-time collaboration, and comprehensive ontology lifecycle management.

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

OntoBoard uses a 5-service microservice architecture. ROBOT 1.9.6 and Java 21 are installed directly in the backend and worker Docker images -- there is no Docker-in-Docker or odkfull container dependency at runtime.

```
                  +-----------------+
                  |    Frontend     | :3000
                  |  React 18 +    |
                  |  Cytoscape.js  |
                  +--------+-------+
                           |
          +----------------+----------------+
          |                |                |
+---------v------+ +------v------+ +-------v------+
|    Backend     | |   Collab    | |    Worker    |
|  FastAPI +     | | Hocuspocus  | |  Python +   |
|  ROBOT + Java  | | Yjs + JWT   | |  ROBOT +    |
|  :8000         | | :1234       | |  Java       |
+--------+------+  +------------+  +------+------+
         |                                |
         +--------------+-----------------+
                        |
                +-------v-------+
                |    Redis      | :6379
                |  Job Queue    |
                +---------------+
```

| Service | Stack | Port |
|---------|-------|------|
| **Frontend** | React 18, Cytoscape.js 3.30, Zustand 5, Monaco Editor, Vite 5, TypeScript 5.6 | 3000 |
| **Backend** | FastAPI 0.115, Python 3.12, rdflib 7.1, owlready2 0.47, ROBOT 1.9.6, Java 21, SQLAlchemy 2 | 8000 |
| **Collaboration** | Hocuspocus 3.4 (Yjs WebSocket server) | 1234 |
| **Worker** | Python 3.12, ROBOT 1.9.6, Java 21, Redis consumer for long-running jobs | -- |
| **Redis** | Redis 7 Alpine -- job queue + pub/sub | 6379 |

## Key Features

### Visual Ontology Canvas
- **Cytoscape.js** infinite canvas with dagre, force, and grid layouts
- Drag-and-drop class, individual, and literal creation
- Edge drawing for object properties, data properties, SubClassOf, rdf:type
- Canvas frames and sticky notes for organization
- Minimap for navigation
- Auto-fit viewport on initial load
- Tab state preserved (visited tabs stay mounted)

### Manchester Syntax Parser
- Recursive descent parser (`manchester_parser.py`) for OWL 2 class expressions
- Supports: `and`, `or`, `not`, `some`, `only`, `value`, `min`/`max`/`exactly`, nested parentheses
- Bidirectional: parse Manchester syntax to RDF triples, render RDF triples back to Manchester syntax
- Example: `Animal and hasPart some (Organ or Tissue) and not Plant`

### Property Characteristics and Chains
- UI checkboxes for all 7 OWL property characteristics: Functional, InverseFunctional, Transitive, Symmetric, Asymmetric, Reflexive, Irreflexive
- Property chain editor with ordered list, add/remove/reorder
- Data property XSD range dropdown: `xsd:string`, `xsd:integer`, `xsd:float`, `xsd:double`, `xsd:boolean`, `xsd:decimal`, `xsd:date`, `xsd:dateTime`, `xsd:anyURI`
- Full annotation property CRUD

### ROBOT Explain (Ontology Debugging)
- Justification axioms for entailments
- Suggested fixes for inconsistencies
- 7 of 24 ROBOT commands implemented: `convert`, `report`, `reason`, `template`, `diff`, `query`, `explain`

### Import Resolution
- Resolve status checking for all imports
- Download remote imports on demand
- Catalog management (catalog-v001.xml)

### SWRL Rule Editor
- View, create, and delete SWRL rules
- Human-readable format display
- Rules stored as OWL annotations

### Embedded Reasoner
- owlready2 integration as alternative to ROBOT subprocess
- Consistency check endpoint
- ELK, HermiT, JFact, Whelk reasoners via ROBOT

### Ontology Design Patterns (ODP)
- 13 curated ODPA patterns from [ontologydesignpatterns.org](http://ontologydesignpatterns.org)
- Each pattern stored as `metadata.json` + `pattern.owl` in `backend/seed/patterns/`
- User patterns saved per-user in `data/patterns/user/{username}/{pattern-id}/`
- Drag-and-drop onto canvas with auto-coloring per namespace prefix

### Real-time Collaboration
- **Cursor sharing**: see other users' cursors in real-time via Yjs awareness
- **Entity locking**: when you select an entity, others see it highlighted with your color
- **Instant save broadcast**: when you save, all other users reload within ~1 second
- **Fallback polling**: 30-second backend poll as safety net when Yjs disconnects
- **Comments**: threaded discussions with @mentions and notifications
- **Task board**: Kanban columns (To Do, In Progress, Review, Done)
- **Entity lock visibility**: CollabStatus dropdown shows who is editing which entity

### ODK Integration
- ODK scaffold generation (manual, no Docker dependency)
- Build error explanations (8 known error patterns with human-readable messages)
- OWL Functional Syntax support (auto-conversion via ROBOT)
- Multi-file ontology loading (edit + release files merged)
- Ontology file switcher dropdown (switch between edit, release, import modules)

### Board Management
- Board settings dialog (rename, description, visibility, tags, clone, delete)
- Board cards with action bar (star, clone, delete) on dashboard
- ID ranges with OWL Functional Syntax support (ODK standard format)
- Create boards from scratch, GitHub repos, or file upload (OWL, TTL, RDF, OBO)

### Additional Features
- CSV to Knowledge Graph import wizard
- SPARQL query panel with result visualization
- Provenance tracking using PROV-O agents, Dublin Core, XSD datatypes
- Prefix management with editable names and IRIs, per-prefix canvas coloring
- GitHub import with auto-conversion of OWL Functional Syntax
- Git-backed version control
- Quality metrics (OQUARE compliance)

## Comparison with Protege

| Feature | Protege 5.6 | OntoBoard |
|---------|:-----------:|:---------:|
| OWL class hierarchy browser | Yes | Yes |
| Manchester Syntax editing | Yes | Yes (recursive descent parser) |
| Property characteristics (all 7) | Yes | Yes |
| Property chains | Yes | Yes |
| Data property XSD ranges | Yes | Yes |
| Annotation property CRUD | Yes | Yes |
| Reasoner integration (ELK, HermiT) | Yes | Yes (via ROBOT + owlready2) |
| Explanation / justification | Yes | Yes (ROBOT explain) |
| SWRL rules | Yes | Yes (annotation-based) |
| Consistency checking | Yes | Yes |
| Visual graph canvas | Plugin (OntoGraf) | Built-in (Cytoscape.js) |
| Real-time collaboration | No | Yes (Yjs/Hocuspocus) |
| Web-based (no install) | No (desktop Java) | Yes (Docker) |
| ODK/ROBOT pipeline integration | No | Yes |
| Design pattern library | No | Yes (13 ODPA patterns) |
| CSV import | No (plugin) | Built-in |
| Task management | No | Yes (Kanban) |
| Import resolution UI | Partial | Yes |
| ID range management | Yes (file-based) | Yes (UI + OWL Functional Syntax) |
| SPARQL query panel | Plugin (SPARQL Tab) | Built-in |
| Provenance tracking | No | Yes (PROV-O) |
| DataRange restrictions (`xsd:integer[> 5]`) | Yes | No |
| HasSelf | Yes | No |
| ObjectOneOf (multiple individuals) | Yes | No |
| Custom datatypes | Yes | No |
| Key axioms (owl:hasKey) | Yes | No |
| Negative property assertions | Yes | No |
| SHACL validation | No | No |

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
│   │   ├── services/        # 41 business logic modules
│   │   │   ├── manchester_parser.py  # Recursive descent Manchester parser
│   │   │   └── swrl.py              # SWRL rule management
│   │   ├── models/          # 8 SQLAlchemy ORM models (7 tables)
│   │   └── schemas/         # Pydantic request/response schemas
│   ├── tests/               # 46 test files, 455+ test functions
│   ├── seed/patterns/       # 13 bundled ODPA patterns (tracked in git)
│   ├── Dockerfile           # Python 3.12 + Java 21 + ROBOT 1.9.6 + make
│   └── requirements.txt     # FastAPI, rdflib, owlready2, etc.
├── frontend/                # React SPA
│   ├── src/
│   │   ├── components/      # 25 feature directories
│   │   ├── pages/           # 8 page routes
│   │   ├── store/           # Zustand state management
│   │   ├── collab/          # Yjs collaboration (useCollaboration, CollabStatus)
│   │   └── hooks/           # Custom React hooks
│   └── Dockerfile
├── collab/                  # Hocuspocus WebSocket server
├── worker/                  # Redis job consumer (ROBOT + Java installed locally)
├── data/                    # Runtime data (gitignored)
│   └── patterns/
│       ├── odpa/            # Auto-seeded from backend/seed/patterns/
│       └── user/            # Per-user uploaded patterns
├── docs/                    # 7 documentation files
├── docker-compose.yml       # Production setup
├── docker-compose.dev.yml   # Development setup
└── run.sh                   # Orchestration script
```

## API Documentation

Interactive API docs available at [http://localhost:8000/docs](http://localhost:8000/docs) after starting the backend.

**60+ REST endpoints** covering: boards, ontology CRUD, axioms, Manchester syntax parsing, reasoning, ROBOT commands, SPARQL, SWRL rules, patterns, comments, collaboration, ODK workflows, import resolution, file management, and more.

See [docs/api-reference.md](docs/api-reference.md) for the full reference.

## Technology Stack

| Layer | Technologies |
|-------|-------------|
| **Frontend** | React 18.3, TypeScript 5.6, Cytoscape.js 3.30, Zustand 5, Monaco Editor, Vite 5 |
| **Backend** | FastAPI 0.115, Python 3.12, rdflib 7.1, owlready2 0.47, SQLAlchemy 2, Dulwich (Git) |
| **Ontology Tools** | ROBOT 1.9.6, OWL API (via ROBOT), Java 21, owlready2 (embedded reasoner) |
| **Collaboration** | Yjs 13.6, y-websocket, Hocuspocus 3.4 |
| **Database** | SQLite (dev) / PostgreSQL-ready |
| **Infrastructure** | Docker, Docker Compose, Redis 7 |

## Testing

```bash
./run.sh test         # Run all backend tests
cd backend && python -m pytest tests/ -v
```

46 test files with 455+ test functions covering:

| Category | Tests |
|----------|:-----:|
| Manchester parser | 19 |
| Property characteristics | 8 |
| Property chains | 6 |
| XSD ranges | 6 |
| Annotation CRUD | 7 |
| ROBOT explain | 6 |
| Import resolution | 8 |
| SWRL rules | 6 |
| Embedded reasoner | 6 |
| Integration tests | 4 |
| Existing tests (API, canvas, reasoning, etc.) | 380+ |

## Documentation

Full documentation is in the [docs/](docs/) directory:
- [Documentation Index](docs/index.md)
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
