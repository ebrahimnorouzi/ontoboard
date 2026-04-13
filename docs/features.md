# Features

This document provides detailed documentation of all OntoBoard features.

## Visual Ontology Canvas

The canvas is the primary workspace for visual ontology modeling. It is built on Cytoscape.js and provides an infinite, pannable, zoomable surface for creating and editing OWL ontologies.

### Node Types

| Node | Shape | Description |
|------|-------|-------------|
| Class | Rounded rectangle | OWL class (e.g., `owl:Class`) |
| Individual | Diamond | Named individual (e.g., `owl:NamedIndividual`) |
| Literal | Small rectangle | Data value with datatype and optional language tag |

### Edge Types

| Edge | Style | Description |
|------|-------|-------------|
| SubClassOf | Dashed arrow | `rdfs:subClassOf` hierarchy |
| Object Property | Solid arrow | Named object property between classes |
| Data Property | Thin arrow | Data property connecting class to literal |
| rdf:type | Dotted arrow | Individual-to-class membership |

### Canvas Interactions

- **Double-click empty space**: Create a new class at that position
- **Right-click empty space**: Context menu with options to add class, individual, literal, sticky note, or frame
- **Right-click a node**: Context menu with edit, delete, add subclass, add property, and other entity-specific options
- **Drag from edge handle**: Draw an edge (property or SubClassOf) to another node
- **Drag a node**: Reposition the node on the canvas
- **Scroll/pinch**: Zoom in and out
- **Click + drag background**: Pan the canvas
- **Click a node**: Select it and show details in the right sidebar

### Canvas Frames

Frames are labeled rectangular regions for visually grouping related entities. They have a configurable background color with transparency and a border color. Frames do not affect the ontology semantics -- they are purely organizational.

### Sticky Notes

Text annotations that can be placed anywhere on the canvas. Sticky notes have configurable color, font size, and dimensions. They are stored in the canvas state but do not generate OWL axioms.

### Minimap

A small overview panel in the corner of the canvas showing the full graph layout. Clicking on the minimap navigates to that area of the canvas.

### Layout Algorithms

The canvas supports multiple automatic layout algorithms:
- **Dagre**: Hierarchical layout following the SubClassOf tree
- **CoSE-Bilkent**: Compound spring embedder for complex graphs
- **Grid**: Simple grid arrangement
- **Circle**: Circular arrangement

### Export

The canvas can be exported as:
- PNG image (screenshot of the visible area)
- SVG vector graphic
- OWL file in various formats

### Auto-Save

The canvas auto-saves to the backend 800ms after the last edit. A save indicator in the toolbar shows the save status (saving, saved, error). Saves are debounced to avoid excessive network requests.

### Undo/Redo

The canvas maintains an undo stack (up to 50 snapshots). Each destructive operation (add, delete, move) pushes a snapshot. Undo restores the previous snapshot; redo replays the undone operation. Keyboard shortcuts: Ctrl+Z (undo), Ctrl+Shift+Z or Ctrl+Y (redo).

## Pattern Library

OntoBoard includes a built-in library of Ontology Design Patterns (ODPs) from the Ontology Design Patterns Association (ODPA).

### Built-in Patterns

The following patterns are included out of the box (stored in `data/patterns/odpa/`):

- **Part-Of**: Mereological parthood relations
- **Quality**: Quality/attribute pattern
- **Participation**: Entity participation in events
- **Classification**: Type/category classification
- And additional patterns depending on the installation

Each pattern consists of:
- `metadata.json`: Pattern name, description, list of classes and properties
- `pattern.owl`: OWL file containing the pattern axioms

### Applying Patterns

1. Open the Pattern Library panel from the right sidebar
2. Browse or search for a pattern
3. Click "Apply" or drag the pattern onto the canvas
4. The pattern's classes and properties are instantiated on the canvas
5. Each pattern gets a unique color code for visual distinction
6. The `patternMap` in the store tracks which classes came from which pattern

### Custom Patterns

Users can upload custom patterns:

1. Click "Upload Pattern" in the Pattern Library
2. Provide a JSON metadata file and an OWL file
3. The pattern is stored in `data/patterns/user/` and available to all boards
4. Batch upload is supported for uploading multiple patterns at once

### Pattern Management

- **Reload**: Refresh the pattern list from disk
- **Delete**: Remove a user-uploaded pattern (built-in patterns cannot be deleted)

## Axiom Editor

The Axiom Editor provides two views for working with OWL axioms:

### Manchester Syntax View

A Monaco-based code editor with syntax highlighting and auto-completion for Manchester OWL Syntax. Features:

- Syntax highlighting for Manchester Syntax keywords (`SubClassOf`, `EquivalentTo`, `DisjointWith`, `some`, `only`, `and`, `or`, `not`, `min`, `max`, `exactly`, `value`, `Self`)
- Auto-completion for entity names (classes, properties, individuals) in the current ontology
- Real-time validation of Manchester Syntax expressions
- Edit axioms for any selected entity

### Structured View

Lists all axioms for the selected entity in a structured table:

| Axiom Type | Subject | Predicate | Object |
|------------|---------|-----------|--------|
| SubClassOf | Person | rdfs:subClassOf | Agent |
| ObjectProperty | Person | worksFor | Organization |

Each axiom can be individually deleted or edited.

### Validation

The axiom editor validates Manchester Syntax expressions before applying them:
- Checks for undefined entity references
- Validates syntax structure
- Reports errors with line numbers and descriptions

## Reasoning

OntoBoard integrates with ROBOT for automated reasoning.

### Supported Reasoners

| Reasoner | Description |
|----------|-------------|
| **ELK** | Fast reasoner for EL++ ontologies. Good for large biomedical ontologies. |
| **HermiT** | Full OWL 2 DL reasoner. Handles all OWL 2 constructs. |
| **JFact** | OWL 2 DL reasoner with good performance on medium ontologies. |
| **Whelk** | Lightweight EL reasoner written in Scala. |

### Reasoning Workflow

1. Open the Reasoning Panel from the right sidebar
2. Select a reasoner from the dropdown
3. Click "Run Reasoning"
4. The job is submitted to the worker queue
5. Progress is streamed in real-time via SSE
6. When complete, inferred axioms are displayed

### Inferred Axioms

After reasoning, inferred axioms (SubClassOf, EquivalentClass, etc.) can be:
- **Viewed**: Listed in the Reasoning Panel with subject, predicate, and object
- **Toggled on canvas**: Show/hide inferred relationships as dashed edges on the canvas
- **Applied**: Suggestions for fixing inconsistencies (e.g., removing contradictory axioms)

### Consistency Checking

The reasoner checks for logical consistency. If the ontology is inconsistent:
- Error messages explain the cause
- Fix suggestions are provided where possible
- The "Apply Fix" endpoint can automatically resolve certain issues

## ODK Integration

OntoBoard integrates with the Ontology Development Kit for full ontology lifecycle management.

### ODK Seed

Create a new ODK-structured ontology project:
1. Specify the ontology ID, title, and base IRI
2. OntoBoard runs `robot template` and `make` to generate the ODK project structure
3. The generated files (Makefile, YAML config, OWL template) are stored in the board directory

### ODK Build

Run the ODK build pipeline:
- Executes `make` targets inside the `odkfull` Docker container
- Supported targets: `all`, `test`, `prepare_release`, `publish`, `docs`, `refresh_imports`
- Output is streamed in real-time via SSE

### ODK Test

Run ontology tests:
- Consistency checking
- ROBOT report for quality metrics
- Custom test targets from the Makefile

### ODK Release

Full release workflow:
1. Quality checks (ROBOT report)
2. Build step (`make prepare_release`)
3. Generate release artifacts
4. Download release artifacts as ZIP

### ODK Configuration

Edit the ODK configuration file (`{ontology}-odk.yaml`):
- View and edit YAML configuration
- List available Makefile targets
- Generate changelog from git history
- Generate GitHub Actions CI YAML

### ROBOT Commands

OntoBoard exposes the following ROBOT commands through the API:

| Command | Description |
|---------|-------------|
| `reason` | Run a reasoner and generate inferred axioms |
| `report` | Generate a quality report (TSV format) |
| `convert` | Convert between OWL formats (XML, Turtle, OBO, JSON-LD, Functional, N-Triples) |
| `annotate` | Add annotations/metadata in batch |
| `rename` | Batch rename IRIs with regex or mapping |
| `repair` | Auto-fix common ontology issues |
| `extract` | Extract modules (STAR, TOP, BOT methods) |
| `filter` | Filter axioms by criteria |
| `expand` | Expand asserted and inferred axioms |
| `collapse` | Reduce property chains |
| `relax` | Remove certain SubClassOf axioms |
| `merge` | Merge multiple ontologies |
| `unmerge` | Decompose merged ontology |
| `explain` | Generate explanations for entailments |
| `diff` | Compare two ontology versions |
| `template` | Generate ontology from ROBOT template |

### Import Management (ODK-style)

The ODK import system manages ontology imports following ODK conventions:

- **Declare imports**: Register external ontologies to import
- **Add terms**: Specify which terms to extract from imports
- **Configure Makefile targets**: Add `make` targets for each import
- **Refresh imports**: Re-fetch and update imported modules
- **Check Makefile**: Verify import targets exist in the Makefile

## Collaboration

OntoBoard provides real-time collaboration features powered by Yjs and Hocuspocus.

### Real-time Sync

- Each board has a Yjs document shared via WebSocket
- When one user saves, a Yjs update is broadcast to all connected clients
- Other clients reload the canvas from the backend to get the latest state
- Connection status is shown in the toolbar (connected, disconnected, reconnecting)

### Cursor Sharing

Connected users can see each other's cursors on the canvas. Each user's cursor shows:
- Username label
- Current action (editing, dragging, selecting)
- Color-coded per user

### Entity Locking

When a user is editing an entity (e.g., editing axioms or annotations), the entity is soft-locked:
- Other users see a lock indicator on the entity
- Locking is advisory (not enforced at the API level)
- Locks are released when the user finishes editing or disconnects

### Save Broadcasting

After each save, a Yjs broadcast notifies other clients. The receiving clients then poll the backend to get the updated state. This hybrid approach (Yjs for notification + HTTP for data) ensures consistency.

### Polling Fallback

If the WebSocket connection drops, clients fall back to periodic polling of the backend to detect changes. The `sync-check` endpoint returns a timestamp of the last update.

## Comments System

Comments can be attached at two levels:

### Board-level Comments

General comments about the ontology or the board. Visible to all board members.

### Entity-level Comments

Comments attached to a specific entity IRI. Shown when the entity is selected. Useful for discussing specific modeling decisions.

### Features

- **@mentions**: Tag other users with `@username`. Mentioned users receive notifications.
- **Threading**: Comments can have replies (parent-child hierarchy via `parent_id`)
- **Deletion**: Comment authors and board owners can delete comments.
- **Notifications**: Mentioned users and board members receive notifications for new comments.

## SPARQL Query Panel

Execute SPARQL queries against the loaded ontology.

### Query Types

| Type | Description |
|------|-------------|
| SELECT | Tabular results with variable bindings |
| CONSTRUCT | Returns an RDF graph (can be visualized) |
| ASK | Boolean yes/no answer |

### Graph Visualization

CONSTRUCT query results can be visualized as a graph on the canvas, showing the returned triples as nodes and edges.

### Prefix Auto-loading

The SPARQL panel auto-loads all prefixes from the ontology, so users can write queries using compact IRIs (e.g., `ex:Person` instead of `<http://example.org/Person>`).

## CSV Import

Import tabular data from CSV files and convert them to OWL ontology axioms.

### Import Wizard

1. **Upload**: Upload a CSV file to the board
2. **Analyze**: OntoBoard analyzes column headers and auto-detects types
3. **Map columns**: Assign each column a role:
   - IRI column (subject)
   - Label column (`rdfs:label`)
   - Type column (`rdf:type`)
   - Property columns (object or data properties)
4. **IRI strategy**: Choose how IRIs are generated:
   - Sequential numbering (e.g., `ex:Entity_001`)
   - UUID-based
   - Hash-based (from label)
   - Timestamp-based
   - Custom pattern
5. **Preview**: View the generated triples before committing
6. **Build**: Generate the OWL axioms and add them to the ontology

### ROBOT Template Compatibility

The CSV import generates ROBOT template-compatible CSV files that can be used with `robot template` for batch ontology construction.

## Board Management

### Creating Boards

Boards can be created in several ways:
- **Blank board**: Empty canvas with a starter OWL file
- **From file upload**: Import an OWL file (.owl, .ttl, .rdf, .nt, .jsonld) or ZIP
- **From GitHub**: Clone a GitHub repository containing an ODK project
- **Clone existing**: Duplicate an existing board

### Board Settings

Board owners can configure:
- Display name and description
- Public/private visibility
- Anonymous view/edit permissions
- Tags for organization

### Sharing

- **Direct share**: Add users as editors or viewers via their username
- **Invite links**: Generate shareable links with configurable:
  - Role (editor or viewer)
  - Expiration date
  - Maximum number of uses
- **Request access**: Users can request access to a board; the owner approves or rejects

### Starring

Users can star/favourite boards for quick access. Starred boards appear at the top of the dashboard.

### Cloning

Clone an existing board to create a copy with a new board ID. All ontology files and canvas state are duplicated.

### Activity Log

Every significant action on a board is logged:
- Board creation, editing, sharing
- Ontology uploads, builds, exports
- Reasoning runs, quality checks

The activity log is accessible via the board detail view.

## File Browser

Browse and manage files in the board's ODK directory structure:

- View all files in `src/ontology/` and subdirectories
- Read file contents (OWL, YAML, Makefile, etc.)
- Edit files directly in the browser
- Create new files
- Rename and delete files
- Download individual files

## Task Management

A Kanban-style task board for tracking ontology engineering work.

### Kanban Columns

| Column | Status |
|--------|--------|
| To Do | `todo` |
| In Progress | `in_progress` |
| Review | `review` |
| Done | `done` |

### Task Properties

- Title and description
- Priority (low, medium, high, critical)
- Assignee (board member)
- Linked entity IRI (connect a task to an ontology entity)
- Due date
- GitHub issue integration (link to or create GitHub issues)

### Task Comments

Each task has its own comment thread for discussion.

## Provenance Tracking

OntoBoard tracks provenance metadata using PROV-O and Dublin Core concepts.

### Entity Provenance

Each canvas entity (class, property, individual) records:
- `created_by`: Username of the creator
- `created_at`: ISO 8601 timestamp of creation
- `modified_by`: Username of the last modifier
- `modified_at`: ISO 8601 timestamp of last modification

### Provenance Settings

Users can configure:
- **Enable/disable tracking**: Toggle provenance stamping on/off
- **Target**: Apply provenance to board state, OWL file, or both

### OWL-level Provenance

When the provenance target includes "ontology", Dublin Core metadata is written to the OWL file:
- `dcterms:creator`
- `dcterms:created`
- `dcterms:modified`

## Prefix Management

### Prefix Table

The ontology dashboard shows all namespace prefixes:
- Prefix name (e.g., `ex`)
- Namespace IRI (e.g., `http://example.org/`)
- Color (for per-prefix class coloring on the canvas)

### Operations

- **Add prefix**: Register a new namespace prefix
- **Edit prefix**: Change the prefix name or namespace IRI
- **Delete prefix**: Remove a prefix binding
- **Color coding**: Assign a color to a prefix; all classes in that namespace appear in that color on the canvas

### IRI Resolution

The backend resolves compact IRIs (e.g., `ex:Person`) to full IRIs and vice versa. This is used throughout the UI for human-readable entity display.

## Version Management

### Semantic Versioning

OntoBoard supports semantic versioning for ontologies:
- Read and set the ontology version IRI
- Configure versioning strategy (manual, date-based, etc.)
- Version information is embedded in the OWL file as `owl:versionIRI`

### Git-backed History

All changes to ontology files are tracked via Git (using Dulwich). Users can:
- View the change history
- Compare versions

## DL Query

Execute Description Logic queries against the ontology:
- Query using DL syntax via `POST /api/dlquery/{board_id}/dl-query`
- Results return matching entities
- The DL query is translated to SPARQL for execution

## SWRL Rules

Manage SWRL (Semantic Web Rule Language) rules:
- List existing rules in the ontology
- Add new SWRL rules
- Delete rules

## Quality and Analysis

### ROBOT Report

Generate a detailed quality report covering:
- Missing labels, definitions
- Deprecated entity usage
- IRI pattern violations
- Report output in TSV format

### OOPS! Integration

Check the ontology against the OOPS! pitfall catalog for common modeling errors.

### OQuaRE Metrics

Calculate ontology quality metrics following the OQuaRE framework.

### Registry Compliance

Check compliance with ontology registries (e.g., OBO Foundry, BioPortal requirements).

### Analysis Tools

- **Unused entities**: Find entities with no incoming or outgoing references
- **Deprecated entities**: List all entities marked with `owl:deprecated`
- **Import health**: Check if import IRIs resolve correctly
- **Circular dependencies**: Detect circular import chains
- **Batch annotate**: Add annotations to multiple entities at once

## OWL Restrictions

Create and manage OWL class restrictions:

### Restriction Types

| Type | Example |
|------|---------|
| someValuesFrom | `hasParent some Person` |
| allValuesFrom | `hasParent only Human` |
| minCardinality | `hasChild min 1` |
| maxCardinality | `hasChild max 5` |
| exactCardinality | `hasChild exactly 2` |
| hasValue | `hasCountry value :Germany` |

### Complex Class Expressions

- Union (`or`): `Male or Female`
- Intersection (`and`): `Person and hasAge some xsd:integer`
- Complement (`not`): `not Male`

## Property Characteristics

Configure OWL property characteristics:

| Characteristic | Description |
|----------------|-------------|
| Functional | Property has at most one value per individual |
| Inverse Functional | Property value uniquely identifies the individual |
| Transitive | If a R b and b R c, then a R c |
| Symmetric | If a R b, then b R a |
| Asymmetric | If a R b, then NOT b R a |
| Reflexive | Every individual is related to itself |
| Irreflexive | No individual is related to itself |

### Property Chains

Define property chain axioms (e.g., `hasParent o hasBrother -> hasUncle`).

### Disjoint Declarations

- All Disjoint Classes: Declare multiple classes as mutually disjoint
- Disjoint Properties: Declare properties as disjoint

## ID Range Management

Protege-style ID range allocation for collaborative development:

- **Allocate ranges**: Assign numeric ID ranges to contributors
- **Reserve IDs**: Reserve specific IDs within a range
- **Edit ranges**: Modify range boundaries
- **Delete ranges**: Remove range allocations

This prevents ID conflicts when multiple contributors create entities simultaneously.

## Notifications

Persistent notification system:

| Category | Description |
|----------|-------------|
| `signup` | New user registered (admin only) |
| `activated` | Your account was approved |
| `board_shared` | You were added to a board |
| `board_update` | A board you're on was updated |
| `mention` | Someone mentioned you in a comment |
| `system` | System announcements |

Notifications can be:
- Listed with unread count
- Marked as read (individually or all at once)
- Deleted

## Export

### Ontology Export

Export the ontology in multiple formats:
- OWL/XML
- Turtle (.ttl)
- OBO Format
- JSON-LD
- OWL Functional Syntax
- N-Triples

### ZIP Export

Download the entire board directory as a ZIP file, including:
- All OWL files
- ODK configuration (Makefile, YAML)
- Import files
- Canvas state

## Help System

Built-in help topics accessible via the API:
- Topic listing with descriptions
- Detailed field-level help
- Import workflow step-by-step guidance
