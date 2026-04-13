# OntoBoard Documentation

**OntoBoard** is a collaborative ontology engineering platform built on top of the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). It provides visual ontology modeling on an infinite canvas with full ODK/ROBOT pipeline integration, real-time collaboration, and comprehensive ontology lifecycle management.

Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/) at [ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering).

## What is OntoBoard?

OntoBoard bridges the gap between traditional desktop ontology editors (like Protege) and modern collaborative web applications. It lets ontology engineers:

- **Visually model** OWL ontologies on a drag-and-drop canvas powered by Cytoscape.js
- **Collaborate in real-time** with cursor tracking, entity locking, and live sync via Yjs/Hocuspocus
- **Run ODK/ROBOT pipelines** directly from the browser -- seed, build, test, reason, and release
- **Import ontology design patterns** from a built-in pattern library (ODPA) or upload custom patterns
- **Query ontologies** with SPARQL and DL Query, and visualize results as graphs
- **Manage the full ontology lifecycle** including versioning, import management, provenance tracking, and publishing

## Who is it for?

- **Ontology engineers** who want a visual, web-based alternative to Protege with collaboration support
- **Research teams** building shared ontologies who need real-time collaboration and task management
- **ODK users** who want a graphical interface for the ODK command-line pipeline
- **Knowledge graph developers** who need to import CSV data and build knowledge graphs from tabular sources

## Key Areas of the Application

### Dashboard
The landing page after login. Shows all boards the user owns or has been shared with. Starred boards appear at the top. Users can create new boards, import from GitHub, or upload OWL/ZIP files.

### Ontology Canvas
The main workspace. An infinite Cytoscape.js canvas where users drag-and-drop to create classes, individuals, and literals. Right-click context menus provide entity operations. Edge handles allow drawing object properties and SubClassOf relations between nodes.

### Tree Browser
A Protege-style hierarchical tree showing classes, object properties, data properties, annotation properties, and individuals. Clicking an entity opens a detail panel with annotations, axioms, restrictions, and characteristics.

### Axiom Editor
A Monaco-based Manchester Syntax editor with auto-completion for editing class expressions and axioms. Supports structured view for exploring existing axioms per entity.

### Pattern Library
Browse and apply Ontology Design Patterns (ODPA). Patterns can be dragged onto the canvas, and each pattern gets a unique color code. Users can upload custom patterns with JSON metadata and OWL files.

### ODK Panel
Run full ODK workflows: seed new ontology projects, build releases, run tests, refresh imports, and execute ROBOT commands (reason, report, convert, annotate, extract, merge, and more).

### Collaboration
Real-time collaboration powered by Yjs and Hocuspocus. See other users' cursors and actions. Comments with @mentions, a Kanban task board, and invite links with configurable roles.

## Quick Links

| Document | Description |
|----------|-------------|
| [Getting Started](getting-started.md) | Installation, setup, and first board walkthrough |
| [Architecture](architecture.md) | Microservice architecture, data flow, and directory structure |
| [Features](features.md) | Detailed documentation of all features |
| [API Reference](api-reference.md) | Complete REST API endpoint reference |
| [Development Guide](development.md) | Developer guide for contributing and extending |
| [Limitations & Roadmap](limitations.md) | Known limitations and future plans |

## Quick Start

```bash
git clone <repository-url>
cd ontoboard
./run.sh          # Build + start everything
# Open http://localhost:3000
# Login: admin / admin
```

## Technology Stack Overview

| Layer | Technologies |
|-------|-------------|
| Frontend | React 18, TypeScript, Cytoscape.js, Zustand, Monaco Editor, Vite, CSS Modules |
| Backend | FastAPI, Python 3.12, rdflib, SQLAlchemy, Dulwich (Git), ROBOT 1.9.6 |
| Collaboration | Yjs, y-websocket, Hocuspocus |
| Ontology Tools | OWL API (via ROBOT), rdflib, ODK (odkfull Docker image) |
| Database | SQLite (with SQLAlchemy ORM, PostgreSQL-ready) |
| Infrastructure | Docker Compose, Redis 7, Node.js 20 |

## Contact

Ebrahim Norouzi -- [ebrahim.norouzi@fiz-karlsruhe.de](mailto:ebrahim.norouzi@fiz-karlsruhe.de)

ISE / FIZ Karlsruhe
