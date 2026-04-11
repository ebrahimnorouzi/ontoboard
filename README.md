# OntoBoard

A collaborative ontology editor wrapping the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). Visual ontology modeling on an infinite canvas with full ODK/ROBOT pipeline integration.

## Quick Start

```bash
./run.sh              # Build + start everything
# Open http://localhost:3000 — Login: admin / admin
```

## Architecture

| Service | Stack | Port |
|---------|-------|------|
| **frontend** | React 18, Cytoscape.js, Monaco, Zustand | 3000 |
| **backend** | FastAPI, rdflib, SQLAlchemy, Docker SDK | 8000 |
| **collab** | Hocuspocus (Yjs) | 1234 |
| **worker** | Python, Redis consumer | — |
| **redis** | Redis 7 Alpine | 6379 |

## Features

- **Cytoscape.js canvas** — dagre/force/grid layouts, handles 400+ nodes, edge drawing, entity editing
- **OWL expressiveness** — restrictions, cardinality, property characteristics, chains, DL Query, SWRL
- **ODK pipeline** — seed, imports, reasoning (ELK/HermiT), SPARQL verify, release, DOSDP — all via live SSE terminal
- **Multi-format** — import OWL/TTL/OBO/JSONLD, export to ZIP ODP repository
- **Collaboration** — real-time Yjs sync, invite links, task management, GitHub issues
- **Quality** — OOPS! scanner, OQuaRE metrics, ROBOT report, registry compliance

## Commands

```bash
./run.sh build    # Build Docker images
./run.sh up       # Start services
./run.sh test     # Run 330+ backend tests
./run.sh logs     # View logs
./run.sh clean    # Stop + delete data
```

## API Docs

http://localhost:8000/docs (60+ endpoints)

## License

ISE / FIZ Karlsruhe
