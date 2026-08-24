# Getting Started

## Protégé plugin

### Install

1. Download `ontoboard-<version>.jar` from the releases page.
2. **Remove any older `ontoboard-*.jar`** from Protégé's `plugins/` folder. The bundle is
   `singleton:=true`; two versions side by side stop it resolving and the tab silently never
   appears.
3. Copy the jar into `plugins/`.
4. Restart Protégé, then **Window → Tabs → OntoBoard**.

Protégé **5.6.x** is recommended. It runs on 5.5.0 with fewer ROBOT operations available —
see [limitations](limitations.md#host-support).

### A first session

**Start a project.** *Tools → New ODK Project…*. Give it an ontology ID (lowercase, no
spaces — it becomes file names and IRIs), a title, and a folder. Leave Base IRI empty to get
the OBO convention. It writes a full ODK repository and opens the edit file.

**Put something on the canvas.** The canvas starts empty on purpose — Protégé opens
ontologies with 100,000+ classes and drawing all of them would hang. Either drag *Class*
from the palette on the left, or select an entity in the class hierarchy and use
*Add selected entity to canvas* from the right-click menu.

**Grow the diagram.** Right-click a node and *Expand neighbours (1 hop)* to pull in what it
is directly related to. Drag nodes to arrange them, or use the toolbar's layout algorithms.

**Relate two classes.** Right-click a node, *Create relation from this node…*, pick a target
and a property, then choose how the arrow should be read. The default — existential — means
"every A is related to some B", which is usually what an arrow on a diagram means. The
dialog explains each alternative.

**Check where it went.** The new axioms are in Protégé's class hierarchy immediately, and
Protégé's undo reverses them. The canvas layout is saved beside the ontology in
`<ontology>.ontoboard.json`; the ontology file itself is untouched by the diagram.

**Build it.** From `src/ontology`, with `make` and ROBOT on your PATH:

```bash
make reason    # classify with ELK
make report    # ROBOT quality report -> report.tsv
```

## Web application

```bash
git clone <this repo>
cd ontoboard
./run.sh
```

Open [http://localhost:3000](http://localhost:3000), log in with `admin` / `admin`. Requires
Docker and Docker Compose.

Create a board from scratch, an uploaded OWL file, or a GitHub repository. Invite others and
you will see their cursors and edits live, comment on entities, and track work on the Kanban
board.
