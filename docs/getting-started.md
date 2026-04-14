# Getting Started

This guide covers installing, configuring, and running OntoBoard, and walks through creating your first ontology board.

## Prerequisites

OntoBoard runs as a set of Docker containers. You need:

| Requirement | Minimum Version | Notes |
|-------------|----------------|-------|
| **Docker** | 20.10+ | Docker Desktop on Windows/macOS, or Docker Engine on Linux |
| **Docker Compose** | v2.0+ | Included with Docker Desktop; install separately on Linux |
| **Git** | 2.30+ | For cloning the repository |
| **Disk space** | ~2 GB | For Docker images (backend image includes Java 21 + ROBOT 1.9.6) |

Optional (for development mode):

| Requirement | Version | Notes |
|-------------|---------|-------|
| Python | 3.12+ | For running backend tests locally |
| Node.js | 20+ | For frontend development |

> **Note**: OntoBoard does NOT require the odkfull Docker image (~2.5 GB). ROBOT 1.9.6 and Java 21 are installed directly in the backend and worker Docker images. All ROBOT commands run as local subprocesses.

## Quick Start

1. Clone the repository:

```bash
git clone https://github.com/ISE-FIZKarlsruhe/ontoboard.git
cd ontoboard
```

2. Start all services:

```bash
./run.sh
```

This will:
- Check if Docker images exist; build them if missing
- Start all 5 services (frontend, backend, collab, worker, redis)
- Print the service URLs

3. Open the application:

```
Frontend: http://localhost:3000
Backend API: http://localhost:8000
API Docs (Swagger): http://localhost:8000/docs
```

4. Log in with the default admin credentials:

```
Username: admin
Password: admin
```

## Environment Configuration

OntoBoard uses environment variables for configuration. Default values work out of the box for local development. For production, create a `.env` file in the project root:

```bash
# Security -- CHANGE THIS for production
SECRET_KEY=your-secret-key-here

# Admin bootstrap credentials
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-secure-password
ADMIN_EMAIL=admin@your-domain.com

# Data directory (inside containers)
DATA_DIR=/app/data
```

See `.env.example` for the full list of configurable variables.

## run.sh Commands

The `run.sh` script provides all common operations:

| Command | Description |
|---------|-------------|
| `./run.sh` or `./run.sh start` | Build missing images and start all services |
| `./run.sh build` | Force rebuild all Docker images |
| `./run.sh up` | Start services without building |
| `./run.sh down` | Stop all services |
| `./run.sh dev` | Start in development mode (source mounted for hot-reload) |
| `./run.sh test` | Run backend pytest suite |
| `./run.sh logs` | Tail service logs |
| `./run.sh clean` | Stop services and delete all data |

## Service Ports

| Service | Port | Description |
|---------|------|-------------|
| Frontend | 3000 | React application (Vite dev server) |
| Backend | 8000 | FastAPI REST API + ROBOT 1.9.6 + Java 21 |
| Collab | 1234 | Hocuspocus WebSocket server for real-time sync |
| Redis | 6379 | Job queue and pub/sub |

## First Board Creation Walkthrough

### Step 1: Create a New Board

1. After logging in, you land on the **Dashboard** page
2. Click the **"Create Board"** button
3. In the Create Board wizard, enter:
   - **Board ID**: A URL-friendly slug (e.g., `my-first-ontology`)
   - **Display Name**: A human-readable name (e.g., "My First Ontology")
   - **Description**: Optional description of the ontology
   - **Ontology IRI**: The base IRI for your ontology (e.g., `http://example.org/my-ontology`)
4. Click **Create**

The board is created with a starter OWL file and the ODK directory structure under `data/{username}/{board-id}/src/ontology/`.

### Step 2: Add Classes to the Canvas

1. The board opens on the **Ontology Canvas** with auto-fit viewport
2. **Double-click** on empty canvas space to create a new class
3. Enter the class label (e.g., "Person") and confirm
4. Create a second class (e.g., "Organization")
5. Drag classes to arrange them on the canvas

### Step 3: Add Relationships

1. Hover over a class node to see the edge handle (small circle)
2. Click and drag from the edge handle of "Person" to "Organization"
3. Select the relationship type:
   - **SubClassOf** for hierarchy
   - **Object Property** for named relations (e.g., `worksFor`)
4. The edge appears on the canvas with the property label

### Step 4: Add Individuals

1. Right-click on the canvas to open the context menu
2. Select **"Add Individual"**
3. Enter the individual label and select its type (class)
4. The individual appears as a diamond-shaped node

### Step 5: Edit Axioms with Manchester Syntax

1. Click on a class to select it
2. Open the **Axiom Editor** panel from the right sidebar
3. Add Manchester Syntax expressions. The parser supports:
   - Simple: `Person SubClassOf Agent`
   - Existential: `Person SubClassOf worksFor some Organization`
   - Complex: `Animal and hasPart some (Organ or Tissue) and not Plant`
   - Cardinality: `Person SubClassOf hasChild min 0 Person`
   - HasSelf: `likes Self`
   - ObjectOneOf: `{john, jane, bob}`
   - Datatype facets: `hasAge some xsd:integer[>= 0, <= 150]`
4. The axiom editor provides auto-completion for entity names

### Step 6: Configure Property Characteristics

1. Select an object property in the tree browser
2. In the detail panel, use checkboxes for:
   - Functional, InverseFunctional, Transitive, Symmetric, Asymmetric, Reflexive, Irreflexive
3. Add property chains via the chain editor (e.g., `hasParent o hasBrother -> hasUncle`)
4. For data properties, select XSD range from the dropdown

### Step 7: Apply an Ontology Design Pattern

1. Open the **Pattern Library** panel
2. Browse the 13 curated ODPA patterns (e.g., Part-Of, Participation, Classification)
3. Click "Apply" or drag the pattern onto the canvas
4. Pattern classes are auto-colored by namespace prefix

### Step 8: Save and Export

- The canvas **auto-saves** after 800ms of inactivity
- To export, use the export menu for OWL/XML, Turtle, or other formats
- To run quality checks, use **ROBOT report** from the ODK Panel
- To debug inconsistencies, use **ROBOT explain** for justification axioms

## Importing from GitHub

OntoBoard can import existing ontology repositories from GitHub:

1. From the Dashboard, click **"Import from GitHub"**
2. Enter the GitHub repository URL (e.g., `https://github.com/obophenotype/cell-ontology`)
3. OntoBoard will:
   - Clone the repository
   - Detect the OWL file and ODK structure
   - Auto-convert OWL Functional Syntax files to OWL/XML via ROBOT
   - Create a board with the imported ontology
4. The board is ready for visual editing

You can also import from a ZIP file containing an ODK project structure.

## Importing from a File

1. From the Dashboard, click **"Import from File"**
2. Upload an OWL file (.owl, .ttl, .rdf, .nt, .jsonld) or a ZIP file
3. OntoBoard parses the ontology and creates the board with all classes, properties, and individuals displayed on the canvas

**Supported formats:**
- OWL/XML (native)
- Turtle (native)
- N-Triples (native)
- OWL Functional Syntax (auto-converted via ROBOT)
- Manchester Syntax (partial support)
- RDF/XML (native)
- OBO Format (read-only via rdflib)

## Development Mode Setup

Development mode mounts your source code into the containers for hot-reload:

```bash
# Build images first (only needed once)
./run.sh build

# Start in development mode
./run.sh dev
```

In development mode:
- **Frontend**: Source files from `frontend/src/` are mounted. Vite provides instant hot-reload on code changes.
- **Backend**: Source files from `backend/app/` are mounted. Uvicorn auto-reloads on Python file changes.
- **Collab**: Runs from the pre-built image (no hot-reload for the collab server).

### Running Backend Tests Locally

```bash
cd backend
pip install -r requirements.txt
pip install -r requirements-test.txt
python -m pytest tests/ -v
```

The test suite contains 500+ tests across 48+ test files covering all API endpoints and services including Manchester parser, OWL 2 features (HasSelf, ObjectOneOf, datatype facets), ROBOT template builder, property characteristics, SWRL rules, ROBOT explain, import resolution, and embedded reasoner.

### Running Frontend Locally (without Docker)

```bash
cd frontend
npm install
npm run dev
```

Set the API URL in your environment:
```bash
export VITE_API_URL=http://localhost:8000
export VITE_COLLAB_URL=ws://localhost:1234
```

## Troubleshooting

### Build Failures Due to Network Timeouts

If `docker compose up --build` fails with pip or npm timeouts, build images individually:

```bash
docker build --network host -t ontoboard-frontend ./frontend
docker build --network host -t ontoboard-backend  ./backend
docker build --network host -t ontoboard-collab   ./collab
docker build --network host -t ontoboard-worker   ./worker
docker compose up -d
```

### Port Conflicts

If ports 3000, 8000, 1234, or 6379 are already in use, stop the conflicting services or modify the port mappings in `docker-compose.yml`.

### ROBOT / Java Issues

ROBOT 1.9.6 and Java 21 are installed inside the backend and worker Docker images. If ROBOT commands fail:
- Check that the Docker images were built successfully (`./run.sh build`)
- Verify Java is available inside the container: `docker compose exec backend java -version`
- Verify ROBOT is available: `docker compose exec backend robot --version`

### Resetting All Data

To start fresh:

```bash
./run.sh clean
./run.sh
```

This removes the `data/` directory containing the SQLite database, all board files, and collaboration state.
