# OntoBoard Documentation

**OntoBoard** is a collaborative ontology engineering platform built on top of the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). It provides visual ontology modeling on an infinite canvas with full ROBOT pipeline integration, real-time collaboration, and comprehensive ontology lifecycle management.

Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/) at [ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering).

## What is OntoBoard?

OntoBoard bridges the gap between traditional desktop ontology editors (like Protege) and modern collaborative web applications. It lets ontology engineers:

- **Visually model** OWL ontologies on a drag-and-drop canvas powered by Cytoscape.js
- **Edit complex class expressions** using a recursive descent Manchester Syntax parser with full round-tripping
- **Collaborate in real-time** with cursor tracking, entity locking, and live sync via Yjs/Hocuspocus
- **Run ROBOT commands** directly from the browser -- reason, report, explain, convert, diff, query, and template
- **Debug ontologies** with ROBOT explain for justification axioms and suggested fixes
- **Import ontology design patterns** from a built-in pattern library (13 ODPA patterns) or upload custom patterns
- **Write SWRL rules** in a human-readable format with a dedicated editor
- **Query ontologies** with SPARQL and DL Query, and visualize results as graphs
- **Manage the full ontology lifecycle** including versioning, import resolution, provenance tracking, and publishing

## Who is it for?

- **Ontology engineers** who want a visual, web-based alternative to Protege with collaboration support
- **Research teams** building shared ontologies who need real-time collaboration and task management
- **ODK users** who want a graphical interface for the ROBOT command-line tools
- **Knowledge graph developers** who need to import CSV data and build knowledge graphs from tabular sources

## What has changed (Tier 1 + Tier 2 features)?

OntoBoard now includes two complete tiers of functionality beyond the original core:

**Tier 1** -- Core ontology expressiveness matching Protege:
- Manchester Syntax expression parser with recursive descent (bidirectional: parse and render)
- Property characteristics UI (all 7 OWL characteristics)
- Property chain editor with ordered list management
- Data property XSD range dropdown (9 XSD types)
- Full annotation property CRUD

**Tier 2** -- Advanced tooling:
- ROBOT explain for ontology debugging with justification axioms
- Full import resolution with download and catalog management
- SWRL rule editor (view/create/delete in human-readable format)
- Embedded reasoner via owlready2 as alternative to ROBOT subprocess
- Consistency check endpoint

**Architecture change**: ROBOT 1.9.6 and Java 21 are installed directly in the backend and worker Docker images. There is no Docker-in-Docker or odkfull container dependency. All ROBOT commands run as local subprocesses.

## Key Areas of the Application

### Dashboard
The landing page after login. Shows all boards the user owns or has been shared with. Board cards include an action bar with star, clone, and delete buttons. Starred boards appear at the top. Users can create new boards, import from GitHub (with OWL Functional Syntax auto-conversion), or upload OWL/ZIP files.

### Ontology Canvas
The main workspace. An infinite Cytoscape.js canvas where users drag-and-drop to create classes, individuals, and literals. Auto-fit viewport on initial load. Tab state is preserved (visited tabs stay mounted). Right-click context menus provide entity operations. Edge handles allow drawing object properties and SubClassOf relations between nodes.

### Tree Browser
A Protege-style hierarchical tree showing classes, object properties, data properties, annotation properties, and individuals. Clicking an entity opens a detail panel with annotations, axioms, restrictions, characteristics, and property chains.

### Axiom Editor
A Monaco-based Manchester Syntax editor with auto-completion for editing class expressions and axioms. The backend uses a recursive descent parser supporting `and`, `or`, `not`, `some`, `only`, `value`, `min`/`max`/`exactly`, and nested parentheses. Supports structured view for exploring existing axioms per entity.

### Pattern Library
Browse and apply Ontology Design Patterns (ODPA). 13 curated patterns stored in `backend/seed/patterns/` and auto-seeded to `data/patterns/odpa/` at startup. User patterns saved per-user. Patterns can be dragged onto the canvas with auto-coloring per namespace prefix.

### ODK Panel
Run ROBOT commands and ODK workflows: scaffold new projects, convert formats (including OWL Functional Syntax), run reasoning, generate reports, diff versions, execute SPARQL queries, and explain entailments. Build error explanations cover 8 known error patterns. Multi-file ontology loading merges edit and release files. File switcher dropdown allows switching between edit, release, and import modules.

### Collaboration
Real-time collaboration powered by Yjs and Hocuspocus. See other users' cursors and entity selections. CollabStatus dropdown shows who is editing which entity. Comments with @mentions, threaded replies, a Kanban task board, and invite links with configurable roles.

### SWRL Rule Editor
View, create, and delete SWRL rules in human-readable format. Rules are stored as OWL annotations.

### ROBOT Explain
Debug ontology inconsistencies with justification axioms and suggested fixes via ROBOT explain.

## Quick Links

| Document | Description |
|----------|-------------|
| [Getting Started](getting-started.md) | Installation, setup, and first board walkthrough |
| [Architecture](architecture.md) | Microservice architecture, data flow, and directory structure |
| [Features](features.md) | Detailed documentation of all features including Manchester parser grammar, SWRL format, and ID ranges |
| [API Reference](api-reference.md) | Complete REST API endpoint reference |
| [Development Guide](development.md) | Developer guide for contributing, testing, and extending |
| [Limitations & Roadmap](limitations.md) | Known limitations, Protege comparison, and future plans |

## Quick Start

```bash
git clone https://github.com/ISE-FIZKarlsruhe/ontoboard.git
cd ontoboard
./run.sh          # Build + start everything
# Open http://localhost:3000
# Login: admin / admin
```

## Technology Stack Overview

| Layer | Technologies |
|-------|-------------|
| Frontend | React 18.3, TypeScript 5.6, Cytoscape.js 3.30, Zustand 5, Monaco Editor, Vite 5 |
| Backend | FastAPI 0.115, Python 3.12, rdflib 7.1, owlready2 0.47, SQLAlchemy 2, Dulwich (Git) |
| Ontology Tools | ROBOT 1.9.6, OWL API (via ROBOT), Java 21, owlready2 (embedded reasoner) |
| Collaboration | Yjs 13.6, y-websocket, Hocuspocus 3.4 |
| Database | SQLite (dev) / PostgreSQL-ready |
| Infrastructure | Docker, Docker Compose, Redis 7 |

## Contact

Ebrahim Norouzi -- [ebrahim.norouzi@fiz-karlsruhe.de](mailto:ebrahim.norouzi@fiz-karlsruhe.de)

ISE / FIZ Karlsruhe
