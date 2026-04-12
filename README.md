# OntoBoard

A collaborative ontology engineering platform built on top of the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). OntoBoard provides visual ontology modeling on an infinite canvas with full ODK/ROBOT pipeline integration, real-time collaboration, and comprehensive ontology lifecycle management.

**Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/)** at [ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering)

Contact: [ebrahim.norouzi@fiz-karlsruhe.de](mailto:ebrahim.norouzi@fiz-karlsruhe.de)

## Quick Start

```bash
./run.sh              # Build + start everything
# Open http://localhost:3000 — Login: admin / admin
```

## Architecture

| Service | Stack | Port |
|---------|-------|------|
| **Frontend** | React 18, Cytoscape.js, Zustand, Vite | 3000 |
| **Backend** | FastAPI, rdflib, SQLAlchemy, Docker SDK | 8000 |
| **Collaboration** | Hocuspocus (Yjs WebSocket) | 1234 |
| **Worker** | Python, Redis consumer | — |
| **Redis** | Redis 7 Alpine | 6379 |

## Key Features

### Visual Ontology Modeling
- **Cytoscape.js canvas** with dagre, force, and grid layouts — handles 400+ nodes
- Drag-and-drop class, individual, and literal creation
- Edge drawing for object properties, data properties, SubClassOf, rdf:type
- Manchester Syntax axiom editor with auto-completion
- Canvas frames for grouping and organizing entities
- Sticky notes for annotations directly on the canvas

### Ontology Design Patterns (ODP)
- Built-in pattern library (Part-Of, Quality, Participation, Classification, etc.)
- Drag-and-drop patterns from the library onto the canvas
- Unique color coding per pattern for visual distinction
- Upload custom patterns with JSON metadata + OWL files (batch upload supported)

### ODK Pipeline Integration
- One-click ODK seed, build, test, release workflows
- Live SSE terminal streaming for all ODK operations
- ROBOT integration: convert, report, reason, template, diff, merge, extract
- Import/export: OWL/XML, Turtle, OBO, JSON-LD, OWL Functional, N-Triples
- Full ODK repository export as ZIP

### Reasoning & Validation
- ELK, HermiT, JFact, Whelk reasoners
- Inferred axioms visualization on the canvas (toggle on/off)
- ROBOT report with detailed error logging
- Consistency checking with fix suggestions

### Real-time Collaboration
- Yjs-based real-time sync with WebSocket
- Live cursor tracking with user actions (editing, dragging, selecting)
- Comments with @mentions and notification system
- Task board with Kanban columns (To Do, In Progress, Review, Done)
- Invite links with configurable roles, expiration, and usage limits
- Board sharing with role-based access control (owner, editor, viewer)

### CSV to Knowledge Graph
- ROBOT template-compatible CSV import wizard
- Column mapping with auto-type detection
- Multiple IRI generation strategies (sequential, UUID, hash, timestamp, custom pattern)
- Preview triples before building
- Download generated templates

### Board Management
- Import boards from GitHub repositories or ZIP files
- Favourite boards with star toggle
- Provenance tracking (who created/modified each entity, when)
- ID range allocation (Protege-style per-contributor ranges)
- Git-backed version control for all changes

### Prefix & Namespace Management
- Editable prefix table with color coding
- Per-prefix class coloring on the canvas
- IRI editing directly in the graph
- Auto-sync between graph and prefix panel

## Commands

```bash
./run.sh build    # Build Docker images
./run.sh up       # Start services
./run.sh test     # Run backend tests
./run.sh logs     # View logs
./run.sh clean    # Stop + delete data
```

## API Documentation

Interactive API docs at [http://localhost:8000/docs](http://localhost:8000/docs) (60+ endpoints)

## Technology Stack

- **Frontend**: React 18, TypeScript, Cytoscape.js, Zustand, Vite, CSS Modules
- **Backend**: FastAPI, Python 3.11+, rdflib, SQLAlchemy, Dulwich (Git), Docker SDK
- **Collaboration**: Yjs, y-websocket, Hocuspocus
- **Ontology Tools**: OWL API (via ROBOT), rdflib, ODK (odkfull Docker image)
- **Database**: SQLite (development), PostgreSQL-ready
- **Infrastructure**: Docker Compose, Redis

## License

ISE / FIZ Karlsruhe
