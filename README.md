# OntoBoard

A collaborative, infinite-canvas ontology editor that wraps the [Ontology Development Kit (ODK)](https://github.com/INCATools/ontology-development-kit). Think Miro, but for building OWL ontologies and knowledge graphs.

## Architecture

| Service      | Stack                   | Port  | Purpose                                          |
|-------------|-------------------------|-------|--------------------------------------------------|
| **frontend** | React, Tldraw, Vite     | 3000  | Visual canvas editor & Manchester Syntax panel   |
| **backend**  | FastAPI, Docker SDK      | 8000  | Board provisioning, OWL load/save, KG generation |
| **collab**   | Hocuspocus (Yjs)         | 1234  | Real-time collaboration via WebSocket/CRDT       |
| **worker**   | Python, Docker SDK       | —     | Long-running ODK/ROBOT jobs                      |

The backend communicates with the `obolibrary/odkfull` Docker image to run ODK seed, ROBOT conversions, and `make` builds.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (with Docker Compose)
- The `obolibrary/odkfull` image: `docker pull obolibrary/odkfull:latest`

## Quick Start

```bash
# 1. Build all images (uses --network host to bypass Docker Desktop proxy)
bash build.sh

# 2. Start all services
docker compose up
```

Then open:
- **App:** http://localhost:3000
- **API docs:** http://localhost:8000/docs
- **Create a board:** http://localhost:3000/board/my-ontology

## API Endpoints

### Board Provisioning
| Method   | Endpoint              | Description                                |
|----------|-----------------------|--------------------------------------------|
| `GET`    | `/api/boards/`        | List all boards                            |
| `GET`    | `/api/boards/{id}`    | Get board info                             |
| `POST`   | `/api/boards/{id}`    | Provision: create dir, ODK seed, git init  |
| `DELETE` | `/api/boards/{id}`    | Delete a board                             |

### OWL & Knowledge Graph
| Method | Endpoint                    | Description                              |
|--------|-----------------------------|------------------------------------------|
| `GET`  | `/api/owl/{id}/load`        | Convert .owl to canvas JSON via ROBOT    |
| `POST` | `/api/owl/{id}/save`        | Export canvas JSON to .owl               |
| `POST` | `/api/owl/{id}/upload-csv`  | Upload CSV, returns column headers       |
| `POST` | `/api/owl/{id}/build-kg`    | Generate ROBOT template, produce .ttl KG |

### ODK Pipeline
| Method | Endpoint               | Description                        |
|--------|------------------------|------------------------------------|
| `POST` | `/api/odk/{id}/build`  | Run `make` in ODK container (SSE)  |

## Visual Modeling

- **Square** = `owl:Class`
- **Arrow** = `owl:ObjectProperty`
- Side panel provides a Monaco-based Manchester Syntax axiom editor
- CSV columns can be mapped to ontology classes to generate Knowledge Graphs

## Project Structure

```
ontoboard/
├── docker-compose.yml
├── build.sh                    # Build script (bypasses Docker proxy)
├── backend/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── app/
│       ├── main.py             # FastAPI app
│       ├── config.py
│       └── routers/
│           ├── boards.py       # Board CRUD & provisioning
│           ├── odk.py          # ODK build pipeline (SSE)
│           └── owl.py          # OWL load/save, CSV, KG
├── frontend/
│   ├── Dockerfile
│   ├── package.json
│   └── src/
│       ├── main.tsx
│       ├── components/         # Navbar, Logo
│       ├── pages/              # HomePage, DashboardPage, BoardPage
│       └── styles/             # Global CSS variables & reset
├── collab/
│   ├── Dockerfile
│   └── server.mjs              # Hocuspocus Yjs server
└── worker/
    ├── Dockerfile
    └── worker/main.py          # Task queue placeholder
```

## License

ISE / FIZ Karlsruhe
