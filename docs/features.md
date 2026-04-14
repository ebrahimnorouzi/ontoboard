# Features

This document provides detailed documentation of all OntoBoard features, including the Manchester Syntax parser grammar, SWRL rule format, ID ranges OWL format, and collaboration scaling guide.

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

**Edge rendering note**: Edges only appear on the canvas if both source and target nodes exist on the canvas. Debug logging is available for diagnosing edge rendering issues.

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

### Auto-Fit Viewport

On initial load, the canvas automatically fits all nodes into the viewport. This ensures the user sees the full ontology without manual zooming.

### Tab State Preservation

Visited tabs (e.g., Axiom Editor, Tree Browser, Pattern Library) stay mounted when switching between tabs. This prevents data loss and avoids unnecessary re-renders.

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

---

## Manchester Syntax Parser

OntoBoard includes a recursive descent parser for Manchester OWL Syntax class expressions, implemented in `backend/app/services/manchester_parser.py`. The parser is bidirectional: it can parse Manchester Syntax strings into RDF triples and render RDF triples back into Manchester Syntax strings.

### Grammar

The parser implements the following grammar (in EBNF notation):

```
ClassExpression  ::= Intersection ( 'or' Intersection )*
Intersection     ::= Primary ( 'and' Primary )*
Primary          ::= 'not' Primary
                   | '(' ClassExpression ')'
                   | Restriction
                   | ClassName

Restriction      ::= PropertyName 'some' ClassExpression
                   | PropertyName 'only' ClassExpression
                   | PropertyName 'value' IndividualName
                   | PropertyName 'min' NonNegativeInteger ClassExpression?
                   | PropertyName 'max' NonNegativeInteger ClassExpression?
                   | PropertyName 'exactly' NonNegativeInteger ClassExpression?

ClassName        ::= IRI | PrefixedName | SimpleLabel
PropertyName     ::= IRI | PrefixedName | SimpleLabel
IndividualName   ::= IRI | PrefixedName | SimpleLabel
```

### Supported Constructs

| Construct | Manchester Syntax | OWL Construct |
|-----------|-------------------|---------------|
| Intersection | `A and B` | `owl:intersectionOf` |
| Union | `A or B` | `owl:unionOf` |
| Complement | `not A` | `owl:complementOf` |
| Existential | `prop some A` | `owl:someValuesFrom` |
| Universal | `prop only A` | `owl:allValuesFrom` |
| Has Value | `prop value ind` | `owl:hasValue` |
| Min Cardinality | `prop min 1 A` | `owl:minCardinality` / `owl:minQualifiedCardinality` |
| Max Cardinality | `prop max 5 A` | `owl:maxCardinality` / `owl:maxQualifiedCardinality` |
| Exact Cardinality | `prop exactly 2 A` | `owl:cardinality` / `owl:qualifiedCardinality` |
| Nested | `A and (B or C)` | Nested blank nodes |

### Examples

```
# Simple intersection
Person and Agent

# Existential restriction
hasPart some Organ

# Complex nested expression
Animal and hasPart some (Organ or Tissue) and not Plant

# Cardinality with filler
hasChild min 1 Person

# Multiple restrictions
Person and worksFor some Organization and hasAge some xsd:integer

# Deeply nested
(A or B) and (C or (D and not E))
```

### Bidirectional Round-Tripping

The parser supports full round-tripping:

1. **Parse**: Manchester Syntax string -> tokenize -> recursive descent -> RDF triples (blank nodes for restrictions)
2. **Render**: RDF triples -> traverse blank node structure -> reconstruct Manchester Syntax string

This means you can parse an expression, store it as RDF, and render it back to get an equivalent Manchester Syntax string.

### Full OWL 2 Coverage

The Manchester parser supports all standard OWL 2 class expression constructs:
- `HasSelf`: `likes Self`
- `ObjectOneOf`: `{john, jane, bob}`
- Datatype facet restrictions: `xsd:integer[>= 0, <= 100]`, `xsd:string[minLength 1]`
- All Boolean connectives, restrictions, and cardinality constraints

---

## Property Characteristics

OntoBoard provides a UI with checkboxes for all 7 OWL object property characteristics:

| Characteristic | OWL Type | Description |
|----------------|----------|-------------|
| Functional | `owl:FunctionalProperty` | Property has at most one value per individual |
| Inverse Functional | `owl:InverseFunctionalProperty` | Property value uniquely identifies the individual |
| Transitive | `owl:TransitiveProperty` | If a R b and b R c, then a R c |
| Symmetric | `owl:SymmetricProperty` | If a R b, then b R a |
| Asymmetric | `owl:AsymmetricProperty` | If a R b, then NOT b R a |
| Reflexive | `owl:ReflexiveProperty` | Every individual is related to itself |
| Irreflexive | `owl:IrreflexiveProperty` | No individual is related to itself |

### Setting Characteristics

1. Select an object property in the tree browser or canvas
2. In the detail panel, toggle the characteristic checkboxes
3. Changes are saved immediately to the OWL file as RDF type assertions

### API

```
GET  /api/characteristics/{board_id}/entity/{entity_iri}  # Get current characteristics
PUT  /api/characteristics/{board_id}/entity/{entity_iri}  # Set characteristics
```

The PUT request body:
```json
{
  "functional": true,
  "inverse_functional": false,
  "transitive": true,
  "symmetric": false,
  "asymmetric": false,
  "reflexive": false,
  "irreflexive": false
}
```

---

## Property Chains

Define property chain axioms (OWL `owl:propertyChainAxiom`) where a chain of properties implies another property.

### Example

`hasParent o hasBrother -> hasUncle`

This means: if individual `x` has parent `y`, and `y` has brother `z`, then `x` has uncle `z`.

### UI

The property chain editor provides:
- An ordered list of properties forming the chain
- Add property to the chain
- Remove property from the chain
- Reorder properties via drag-and-drop
- The "implies" property (the property being defined by the chain)

### API

```
POST /api/characteristics/{board_id}/property-chain
```

Request body:
```json
{
  "property_iri": "http://example.org/hasUncle",
  "chain": [
    "http://example.org/hasParent",
    "http://example.org/hasBrother"
  ]
}
```

---

## Data Property XSD Ranges

A dropdown selector for setting the range of data properties to common XSD datatypes:

| XSD Type | Description |
|----------|-------------|
| `xsd:string` | Character string |
| `xsd:integer` | Arbitrary-precision integer |
| `xsd:float` | 32-bit floating point |
| `xsd:double` | 64-bit floating point |
| `xsd:boolean` | True or false |
| `xsd:decimal` | Arbitrary-precision decimal |
| `xsd:date` | Calendar date (YYYY-MM-DD) |
| `xsd:dateTime` | Date and time with timezone |
| `xsd:anyURI` | Uniform Resource Identifier |

The range is set as `rdfs:range` on the data property in the OWL file.

---

## Annotation Properties

Full CRUD for annotation properties:

### Operations

- **Create**: Add a new annotation property with IRI and label
- **Read**: List all annotation properties in the ontology
- **Update**: Edit annotation property label and other annotations
- **Delete**: Remove an annotation property and all its usage

### Built-in Annotation Properties

OntoBoard recognizes common annotation properties:
- `rdfs:label` -- human-readable label
- `rdfs:comment` -- descriptive comment
- `skos:definition` -- formal definition
- `skos:prefLabel` -- preferred label
- `skos:altLabel` -- alternative label
- `dcterms:description` -- Dublin Core description
- `dcterms:creator` -- Dublin Core creator
- `dcterms:created` -- Dublin Core creation date

---

## ROBOT Explain (Ontology Debugging)

ROBOT explain generates justifications for entailments -- the minimal set of axioms that cause an inference to hold.

### Usage

1. Open the ROBOT panel or reasoning panel
2. Click "Explain" for an inconsistency or inference
3. OntoBoard runs `robot explain` as a subprocess
4. Results show:
   - The entailment being explained
   - The justification axioms (minimal set)
   - Suggested fixes for removing the inconsistency

### API

```
POST /api/robot/{board_id}/explain
```

Request body:
```json
{
  "axiom": "SubClassOf: Person DisjointWith: Agent"
}
```

Response:
```json
{
  "explanation": "...",
  "justification_axioms": [...],
  "suggested_fixes": [...]
}
```

---

## Import Resolution

Full import management for OWL ontologies:

### Features

1. **List imports**: Show all `owl:imports` declarations with resolve status
2. **Check resolve status**: Test whether each import IRI is reachable
3. **Download remote imports**: Fetch and cache imported ontologies locally
4. **Catalog management**: Generate and update `catalog-v001.xml` for local resolution
5. **Add/remove imports**: Modify the imports list in the OWL file

### Flow

```
GET /api/imports/{board_id}
  Returns: [
    { "iri": "http://purl.obolibrary.org/obo/ro.owl", "resolved": true },
    { "iri": "http://example.org/missing.owl", "resolved": false }
  ]

POST /api/imports/{board_id}
  Body: { "iri": "http://purl.obolibrary.org/obo/bfo.owl" }
  -> Downloads the import, creates catalog entry, adds owl:imports triple

DELETE /api/imports/{board_id}/{import_iri}
  -> Removes the owl:imports triple
```

### Limitations

- Downloads may fail for ontologies behind authentication
- No proxy support
- No automatic freshness checking

---

## SWRL Rule Editor

OntoBoard supports SWRL (Semantic Web Rule Language) rules for defining inference rules.

### SWRL Format

Rules are stored as OWL annotations with a human-readable format:

```
Person(?x) ^ hasAge(?x, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?x)
```

**Syntax:**
- Atoms are separated by `^` (conjunction) in the body (antecedent)
- `->` separates the body from the head (consequent)
- Variables are prefixed with `?`
- Class atoms: `ClassName(?variable)`
- Property atoms: `propertyName(?subject, ?object)`
- Built-in atoms: `swrlb:builtinName(?args...)`

### Supported Atom Types

| Atom Type | Example |
|-----------|---------|
| Class atom | `Person(?x)` |
| Object property atom | `hasFriend(?x, ?y)` |
| Data property atom | `hasAge(?x, ?age)` |
| Same individual | `sameAs(?x, ?y)` |
| Different individuals | `differentFrom(?x, ?y)` |
| SWRL built-ins | `swrlb:greaterThan(?age, 18)` |

### API

```
GET    /api/dlquery/{board_id}/swrl      # List all SWRL rules
POST   /api/dlquery/{board_id}/swrl      # Add a new SWRL rule
DELETE /api/dlquery/{board_id}/swrl      # Delete a SWRL rule
```

### Storage

SWRL rules are stored as annotation properties in the OWL file rather than in native OWL/XML SWRL format. This simplifies parsing and manipulation but means the rules are not directly executable by standard OWL reasoners. The simplified atom parsing handles the common cases.

---

## Embedded Reasoner (owlready2)

As an alternative to ROBOT subprocess calls, OntoBoard integrates owlready2 for embedded reasoning.

### Features

- Load OWL files into owlready2 World objects
- Run HermiT reasoner (bundled with owlready2)
- Check consistency
- Query inferred axioms
- No separate Java process needed (owlready2 uses its own Java bridge)

### Consistency Check Endpoint

```
POST /api/reasoning/{board_id}/run
  Body: { "reasoner": "owlready2" }
```

### Known Issues

- Windows path issues with `file:///` URIs in owlready2
- Slower than ROBOT for large ontologies
- Requires Java (owlready2 bundles its own Java bridge)
- May produce different inference results than ROBOT's reasoners for edge cases

---

## Pattern Library

OntoBoard includes a built-in library of 13 Ontology Design Patterns (ODPs) from the Ontology Design Patterns Association (ODPA).

### Built-in Patterns

The following patterns are stored in `backend/seed/patterns/` and auto-seeded to `data/patterns/odpa/` at startup:

| Pattern | Description |
|---------|-------------|
| **Agent Role** | Agents playing roles in contexts |
| **Classification** | Type/category classification hierarchy |
| **Collection Entity** | Collections and their members |
| **Co-Participation** | Entities co-participating in events |
| **Description** | Descriptive characterizations |
| **Information Realization** | Information objects realized in physical media |
| **Observation** | Observations, measurements, and values |
| **Participation** | Entity participation in events/processes |
| **Part-Of** | Mereological parthood relations |
| **Sequence** | Ordered sequences of entities |
| **Situation** | Situations and their participants |
| **Spatial Object** | Spatial objects and locations |
| **Time Interval** | Temporal intervals and their relations |

Each pattern consists of:
- `metadata.json`: Pattern name, description, source URL, list of classes and properties
- `pattern.owl`: OWL file containing the pattern axioms

### Applying Patterns

1. Open the Pattern Library panel from the right sidebar
2. Browse or search for a pattern
3. Click "Apply" or drag the pattern onto the canvas
4. The pattern's classes and properties are instantiated on the canvas
5. Each pattern gets a unique color code based on namespace prefix
6. The `patternMap` in the store tracks which classes came from which pattern
7. Pattern applications are batched into a single state update for performance

### Auto-Coloring

On first load, OntoBoard automatically assigns different colors to each namespace prefix. When a pattern is applied, its classes appear in the pattern's namespace color, making it easy to visually distinguish pattern-sourced entities from manually created ones.

### Custom Patterns

Users can upload custom patterns:

1. Click "Upload Pattern" in the Pattern Library
2. Provide a JSON metadata file and an OWL file
3. The pattern is saved per-user in `data/patterns/user/{username}/{pattern-id}/`
4. Batch upload is supported for uploading multiple patterns at once

### Pattern Management

- **Reload**: Refresh the pattern list from disk
- **Delete**: Remove a user-uploaded pattern (built-in ODPA patterns cannot be deleted)

---

## Axiom Editor

The Axiom Editor provides two views for working with OWL axioms:

### Manchester Syntax View

A Monaco-based code editor with syntax highlighting and auto-completion for Manchester OWL Syntax. Features:

- Syntax highlighting for Manchester Syntax keywords (`SubClassOf`, `EquivalentTo`, `DisjointWith`, `some`, `only`, `and`, `or`, `not`, `min`, `max`, `exactly`, `value`, `Self`)
- Auto-completion for entity names (classes, properties, individuals) in the current ontology
- Real-time validation of Manchester Syntax expressions via the recursive descent parser
- Edit axioms for any selected entity
- Bidirectional: edits are parsed to RDF, and existing RDF is rendered to Manchester Syntax

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
- Validates syntax structure via the recursive descent parser
- Reports errors with line numbers and descriptions

---

## Reasoning

OntoBoard integrates with ROBOT and owlready2 for automated reasoning.

### Supported Reasoners

| Reasoner | Backend | Description |
|----------|---------|-------------|
| **ELK** | ROBOT | Fast reasoner for EL++ ontologies. Good for large biomedical ontologies. |
| **HermiT** | ROBOT + owlready2 | Full OWL 2 DL reasoner. Handles all OWL 2 constructs. |
| **JFact** | ROBOT | OWL 2 DL reasoner with good performance on medium ontologies. |
| **Whelk** | ROBOT | Lightweight EL reasoner written in Scala. |
| **owlready2** | Embedded | Embedded Python reasoner via owlready2 (HermiT bridge). |

### Reasoning Workflow

1. Open the Reasoning Panel from the right sidebar
2. Select a reasoner from the dropdown
3. Click "Run Reasoning"
4. For ROBOT reasoners: the command runs as a subprocess in the backend or worker
5. For owlready2: reasoning runs embedded in the Python process
6. When complete, inferred axioms are displayed

### Inferred Axioms

After reasoning, inferred axioms (SubClassOf, EquivalentClass, etc.) can be:
- **Viewed**: Listed in the Reasoning Panel with subject, predicate, and object
- **Toggled on canvas**: Show/hide inferred relationships as dashed edges on the canvas
- **Applied**: Suggestions for fixing inconsistencies (e.g., removing contradictory axioms)

### Consistency Checking

The reasoner checks for logical consistency. If the ontology is inconsistent:
- Error messages explain the cause
- ROBOT explain provides justification axioms (the minimal set of axioms causing the inconsistency)
- Fix suggestions are provided where possible
- The "Apply Fix" endpoint can automatically resolve certain issues

---

## ODK Integration

OntoBoard integrates with the Ontology Development Kit for ontology lifecycle management. ROBOT 1.9.6 and Java 21 are installed directly in the Docker images -- there is no Docker-in-Docker dependency.

### ODK Scaffold

Create a new ODK-structured ontology project:
1. Specify the ontology ID, title, and base IRI
2. OntoBoard generates the ODK project structure manually (Makefile, YAML config, OWL template)
3. No Docker dependency for scaffold generation

### ROBOT Commands

OntoBoard implements 7 of 24 ROBOT commands:

| Command | Description |
|---------|-------------|
| `convert` | Convert between OWL formats (OWL/XML, Turtle, OBO, JSON-LD, Functional, N-Triples) |
| `report` | Generate a quality report (TSV format) |
| `reason` | Run a reasoner and generate inferred axioms |
| `template` | Generate ontology from ROBOT template (CSV) |
| `diff` | Compare two ontology versions |
| `query` | Execute SPARQL queries against the ontology |
| `explain` | Generate explanations/justifications for entailments |

All commands run as local subprocesses: `subprocess.run(["robot", "command", ...])`.

### Build Error Explanations

OntoBoard recognizes 8 common error patterns from ROBOT output and provides human-readable explanations:
- Unsatisfiable classes
- Inconsistent ontology
- Missing imports
- Invalid syntax
- Reasoner timeout
- Out of memory
- File not found
- Unknown annotation property

### OWL Functional Syntax Support

OWL files in Functional Syntax are automatically converted to OWL/XML via `robot convert` when imported. This enables editing of ontologies that use the ODK-standard Functional Syntax format.

### Multi-File Ontology Loading

OntoBoard can merge multiple OWL files (edit + release versions) into a single view. The file switcher dropdown allows switching between:
- Edit file (`*-edit.owl`)
- Release file (`*.owl`)
- Import modules (`imports/*.owl`)

### ODK Configuration

Edit the ODK configuration file (`{ontology}-odk.yaml`):
- View and edit YAML configuration
- List available Makefile targets
- Generate changelog from git history
- Generate GitHub Actions CI YAML

### Import Management (ODK-style)

The ODK import system manages ontology imports following ODK conventions:

- **Declare imports**: Register external ontologies to import
- **Add terms**: Specify which terms to extract from imports
- **Configure Makefile targets**: Add `make` targets for each import
- **Refresh imports**: Re-fetch and update imported modules
- **Check Makefile**: Verify import targets exist in the Makefile

---

## Collaboration

OntoBoard provides real-time collaboration features powered by Yjs and Hocuspocus.

### Real-time Sync

- Each board has a Yjs document shared via WebSocket
- When one user saves, a Yjs update is broadcast to all connected clients
- Other clients reload the canvas from the backend within ~1 second
- Connection status is shown in the toolbar (connected, disconnected, reconnecting)

### Cursor Sharing

Connected users can see each other's cursors on the canvas via Yjs awareness. Each user's cursor shows:
- Username label
- Current action (editing, dragging, selecting)
- Color-coded per user

### Entity Locking

When a user selects an entity (e.g., editing axioms or annotations), the entity is soft-locked:
- Other users see a lock indicator on the entity with the editing user's color
- The CollabStatus dropdown shows who is editing which entity (e.g., "Alice is editing ClassName")
- Locking is advisory (not enforced at the API level)
- Locks are released when the user finishes editing or disconnects

### Save Broadcasting

After each save, a Yjs broadcast notifies other clients. The receiving clients then poll the backend to get the updated state. This hybrid approach (Yjs for notification + HTTP for data) ensures consistency.

### Polling Fallback

If the WebSocket connection drops, clients fall back to periodic polling (every 30 seconds) of the `sync-check` endpoint to detect changes. On reconnection, a full state reload occurs from the backend.

### Collaboration Scaling Guide

| Users | Expected Behavior | Notes |
|:-----:|-------------------|-------|
| 1-5 | Smooth real-time updates | Recommended range |
| 5-10 | Occasional brief delays on save broadcast | Works well for team use |
| 10-15 | Noticeable latency, possible last-write-wins conflicts | Maximum tested range |
| 15+ | Not tested; may degrade | Consider splitting into sub-boards |

**Scaling considerations:**
- Collaboration is polling-based, not CRDT. Last-write-wins semantics apply.
- Entity locking is advisory only -- two users can edit the same entity simultaneously.
- The 800ms save debounce window means near-simultaneous edits to the same entity may conflict.
- The Hocuspocus WebSocket server handles awareness (cursors, selections) efficiently, but data sync goes through HTTP.
- For teams larger than 10-15, consider splitting the ontology into modules and assigning different boards to different sub-teams.

---

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

---

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

---

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

---

## Board Management

### Creating Boards

Boards can be created in several ways:
- **Blank board**: Empty canvas with a starter OWL file
- **From file upload**: Import an OWL file (.owl, .ttl, .rdf, .nt, .jsonld) or ZIP
- **From GitHub**: Clone a GitHub repository containing an ODK project (with OWL Functional Syntax auto-conversion)
- **Clone existing**: Duplicate an existing board

### Board Settings Dialog

Board owners can configure via a settings dialog:
- Display name and description
- Public/private visibility
- Anonymous view/edit permissions
- Tags for organization
- Clone the board
- Delete the board

### Dashboard Board Cards

Each board card on the dashboard includes an action bar with:
- **Star**: Toggle favourite status
- **Clone**: Duplicate the board
- **Delete**: Remove the board

### Sharing

- **Direct share**: Add users as editors or viewers via their username
- **Invite links**: Generate shareable links with configurable:
  - Role (editor or viewer)
  - Expiration date
  - Maximum number of uses
- **Request access**: Users can request access to a board; the owner approves or rejects

### Starring

Users can star/favourite boards for quick access. Starred boards appear at the top of the dashboard.

### Activity Log

Every significant action on a board is logged:
- Board creation, editing, sharing
- Ontology uploads, builds, exports
- Reasoning runs, quality checks

The activity log is accessible via the board detail view.

---

## ID Range Management

Protege-style ID range allocation for collaborative development, with OWL Functional Syntax support.

### Purpose

When multiple contributors create entities simultaneously, they need non-overlapping ID ranges to prevent conflicts. Each contributor is allocated a range of numeric IDs (e.g., 1-999, 1000-1999) and creates entities with IRIs in their assigned range.

### ID Ranges OWL Functional Syntax Format

OntoBoard supports the ODK standard format for ID ranges, stored in OWL Functional Syntax:

```
Prefix(: = <http://purl.obolibrary.org/obo/myont.owl#>)
Prefix(idrange: = <http://purl.obolibrary.org/obo/myont/idrange/>)

Ontology(<http://purl.obolibrary.org/obo/myont/idrange>

    AnnotationAssertion(idrange:has_id_policy <http://purl.obolibrary.org/obo/myont.owl> "OBO")

    # Alice's ID range
    AnnotationAssertion(idrange:allocated_to idrange:range_1 "Alice")
    AnnotationAssertion(idrange:id_start idrange:range_1 "1"^^xsd:integer)
    AnnotationAssertion(idrange:id_end idrange:range_1 "999"^^xsd:integer)

    # Bob's ID range
    AnnotationAssertion(idrange:allocated_to idrange:range_2 "Bob")
    AnnotationAssertion(idrange:id_start idrange:range_2 "1000"^^xsd:integer)
    AnnotationAssertion(idrange:id_end idrange:range_2 "1999"^^xsd:integer)
)
```

### Operations

- **Allocate ranges**: Assign numeric ID ranges to contributors
- **Reserve IDs**: Reserve specific IDs within a range
- **Edit ranges**: Modify range boundaries
- **Delete ranges**: Remove range allocations

### API

```
GET    /api/idranges/{board_id}            # List all ranges
POST   /api/idranges/{board_id}/allocate   # Allocate a new range
PUT    /api/idranges/{board_id}/{owner}    # Update a range
DELETE /api/idranges/{board_id}/{owner}    # Delete a range
POST   /api/idranges/{board_id}/reserve   # Reserve an ID
```

---

## File Browser

Browse and manage files in the board's ODK directory structure:

- View all files in `src/ontology/` and subdirectories
- Read file contents (OWL, YAML, Makefile, etc.)
- Edit files directly in the browser
- Create new files
- Rename and delete files
- Download individual files

### Ontology File Switcher

A dropdown selector allows switching between different OWL files:
- Edit file (`*-edit.owl`)
- Release file (`*.owl`)
- Import modules (`imports/*.owl`)

Changes to the active file are reflected on the canvas.

---

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

---

## Provenance Tracking

OntoBoard tracks provenance metadata using PROV-O and Dublin Core concepts with proper semantic web ontologies.

### Entity Provenance

Each canvas entity (class, property, individual) records:
- `created_by`: Username of the creator (PROV-O agent)
- `created_at`: ISO 8601 timestamp of creation (XSD dateTime)
- `modified_by`: Username of the last modifier (PROV-O agent)
- `modified_at`: ISO 8601 timestamp of last modification (XSD dateTime)

### OWL-level Provenance

When the provenance target includes "ontology", Dublin Core metadata is written to the OWL file:
- `dcterms:creator` -- creator agent
- `dcterms:created` -- creation date (XSD dateTime)
- `dcterms:modified` -- modification date (XSD dateTime)

### Provenance Settings

Users can configure:
- **Enable/disable tracking**: Toggle provenance stamping on/off
- **Target**: Apply provenance to board state, OWL file, or both

---

## Prefix Management

### Prefix Table

The ontology dashboard shows all namespace prefixes:
- Prefix name (e.g., `ex`) -- editable inline
- Namespace IRI (e.g., `http://example.org/`) -- editable inline
- Color (for per-prefix class coloring on the canvas)

### Operations

- **Add prefix**: Register a new namespace prefix
- **Edit prefix**: Change both the prefix name and namespace IRI (inline editing)
- **Delete prefix**: Remove a prefix binding
- **Color coding**: Assign a color to a prefix; all classes in that namespace appear in that color on the canvas. Colors are auto-assigned per namespace on first load.

---

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

---

## OWL Restrictions

Create and manage OWL class restrictions:

### Restriction Types

| Type | Example | OWL Construct |
|------|---------|---------------|
| someValuesFrom | `hasParent some Person` | `owl:someValuesFrom` |
| allValuesFrom | `hasParent only Human` | `owl:allValuesFrom` |
| minCardinality | `hasChild min 1` | `owl:minCardinality` |
| maxCardinality | `hasChild max 5` | `owl:maxCardinality` |
| exactCardinality | `hasChild exactly 2` | `owl:cardinality` |
| hasValue | `hasCountry value :Germany` | `owl:hasValue` |

### Complex Class Expressions

- Union (`or`): `Male or Female`
- Intersection (`and`): `Person and hasAge some xsd:integer`
- Complement (`not`): `not Male`

---

## DL Query

Execute Description Logic queries against the ontology:
- Query using DL syntax via `POST /api/dlquery/{board_id}/dl-query`
- Results return matching entities
- The DL query is translated to SPARQL for execution

---

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

---

## Export

### Ontology Export

Export the ontology in multiple formats:
- OWL/XML (native)
- Turtle (.ttl) (native)
- N-Triples (.nt) (native)
- OWL Functional Syntax (via ROBOT conversion -- may lose some constructs)
- Manchester Syntax (partial)
- OBO Format (read-only via rdflib)
- JSON-LD

### ZIP Export

Download the entire board directory as a ZIP file, including:
- All OWL files
- ODK configuration (Makefile, YAML)
- Import files
- Canvas state

---

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

---

## Help System

Built-in help topics accessible via the API:
- Topic listing with descriptions
- Detailed field-level help
- Import workflow step-by-step guidance
