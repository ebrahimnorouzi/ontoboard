# Limitations and Roadmap

This document lists known limitations of OntoBoard, provides a detailed comparison with Protege, and outlines planned improvements.

## Known Limitations

### 1. Collaboration Model

**Polling-based sync, not true CRDT**

OntoBoard uses Yjs/Hocuspocus for awareness (cursor sharing, entity locking) and save notifications, but the actual ontology data is not stored in a CRDT structure. Instead, the collaboration flow works as follows:

1. User A saves the canvas to the backend via HTTP
2. A Yjs broadcast notifies other clients that a save occurred
3. User B's client fetches the latest state from the backend via HTTP

This means:
- **Last-write-wins**: If two users edit the same entity simultaneously and save within the 800ms debounce window, the second save overwrites the first. There is no automatic merge of concurrent edits.
- **No offline support**: Edits made while disconnected are lost if another user saves in the meantime.
- **Polling fallback**: If the WebSocket connection drops, clients fall back to polling the `sync-check` endpoint every 30 seconds, which increases latency for detecting changes.
- **Tested up to ~10-15 users**: Beyond this range, performance has not been validated.

Entity locking is advisory only -- it is not enforced at the API level. Two users can technically edit the same entity simultaneously.

### 2. Manchester Parser Limitations

The recursive descent Manchester parser handles most OWL 2 class expressions but not:
- **Datatype restrictions with facets**: e.g., `xsd:integer[> 5]`, `xsd:string[minLength 1]`
- **HasSelf**: e.g., `likes Self`
- **ObjectOneOf with multiple individuals**: e.g., `{john, jane, bob}` (single individual works)
- **Complex data ranges**: e.g., `integer[>= 0, <= 100]`

### 3. SWRL Storage Format

SWRL rules are stored as OWL annotation properties rather than in native OWL/XML SWRL format. This means:
- Rules are not directly executable by standard OWL reasoners that expect native SWRL syntax
- The simplified atom parsing handles common cases but may not cover all SWRL built-in functions
- Import/export of SWRL rules from/to other tools may require format conversion

### 4. Embedded Reasoner (owlready2) Issues

- **Windows path issues**: `file:///` URIs in owlready2 may fail on Windows paths with spaces or non-ASCII characters
- **Performance**: Slower than ROBOT for large ontologies (owlready2 loads the entire ontology into Python memory)
- **Java dependency**: owlready2 requires Java for its HermiT bridge, even though it's an "embedded" reasoner
- **Inference differences**: May produce slightly different results than ROBOT's reasoners for edge cases

### 5. Import Resolution Limitations

- Downloads may fail for ontologies behind authentication (no credential support)
- No HTTP proxy support
- No automatic freshness checking (must manually re-resolve)
- Large import chains may be slow to resolve sequentially

### 6. OWL Format Support

| Format | Read | Write | Notes |
|--------|:----:|:-----:|-------|
| OWL/XML | Native | Native | Primary format, full support |
| Turtle (.ttl) | Native | Native | Full support via rdflib |
| N-Triples (.nt) | Native | Native | Full support via rdflib |
| RDF/XML | Native | Native | Full support via rdflib |
| JSON-LD | Native | Native | Full support via rdflib |
| OWL Functional Syntax | Via ROBOT | Via ROBOT | Auto-conversion; may lose some constructs during round-trip |
| Manchester Syntax | Partial | Partial | Supported for axiom editing, not as file format |
| OBO Format | Read-only | No | Read-only via rdflib; no writing support |

### 7. Canvas Rendering

- Edges only show on canvas if both source and target nodes exist on the canvas
- Performance degrades with 1000+ entities on the canvas
- Tested up to ~500 classes with acceptable performance

### 8. Scale Limits

| Dimension | Tested Limit | Notes |
|-----------|:------------:|-------|
| Concurrent users | ~10-15 | WebSocket connection limit depends on Hocuspocus configuration |
| Nodes on canvas | ~500 | Cytoscape.js performance degrades with 1000+ nodes |
| Triples in ontology | ~50,000 | rdflib parsing becomes slow beyond this; ROBOT handles larger files |
| Board file size | ~50 MB | Limited by Docker volume mount and file I/O |
| Concurrent boards | No hard limit | Limited by memory (SQLite DB + rdflib graphs in memory) |

For larger ontologies, consider:
- Using the tree browser and axiom editor instead of the canvas (which loads all entities)
- Splitting ontologies into modules using ROBOT extract
- Using the ODK import system to work with subsets

### 9. ROBOT Command Coverage

7 of 24 ROBOT commands are fully implemented and tested:

| Implemented | Not Implemented |
|-------------|-----------------|
| `convert` | `annotate` (endpoint exists, limited testing) |
| `report` | `repair` (endpoint exists, limited testing) |
| `reason` | `extract` (endpoint exists, limited testing) |
| `template` | `filter` (endpoint exists, limited testing) |
| `diff` | `expand` |
| `query` | `collapse` |
| `explain` | `relax` |
| | `merge` (endpoint exists, limited testing) |
| | `unmerge` |
| | `rename` (endpoint exists, limited testing) |
| | `mirror` |
| | `reduce` |
| | `remove` |
| | `validate-profile` |
| | `materialize` |
| | `measure` |
| | `python` |

### 10. Other Limitations

- **No plugin system**: Cannot extend functionality without modifying source code
- **No offline mode**: Requires network connectivity to the backend
- **SQLite in development**: Not tested with PostgreSQL in production
- **No SHACL validation support**: Only OWL reasoning, no SHACL shapes
- **No OBO format editing**: OBO files are read-only via rdflib
- **No real-time consistency checking**: Must manually trigger reasoning
- **No OAuth/OIDC**: JWT-based local authentication only
- **No two-factor authentication**
- **No refresh token mechanism**: Token expiration default is 8 hours
- **No password self-service reset**: Requires admin intervention

### Browser Requirements

OntoBoard's frontend requires a modern browser with:
- ES2020+ JavaScript support
- WebSocket support (for collaboration)
- CSS Grid and Flexbox
- Canvas API (for Cytoscape.js)

**Supported browsers:**
- Chrome/Chromium 90+
- Firefox 90+
- Safari 15+
- Edge 90+

Internet Explorer is not supported.

---

## Comparison with Protege

### Feature-by-Feature Comparison

| Feature | Protege 5.6 | OntoBoard | Notes |
|---------|:-----------:|:---------:|-------|
| **Ontology Editing** | | | |
| Class hierarchy browser | Yes | Yes | OntoBoard: tree browser + canvas |
| Object property hierarchy | Yes | Yes | |
| Data property hierarchy | Yes | Yes | |
| Annotation property hierarchy | Yes | Yes | Full CRUD |
| Individual editing | Yes | Yes | |
| Manchester Syntax editing | Yes | Yes | Recursive descent parser |
| Structured axiom view | Yes | Yes | |
| **OWL Constructs** | | | |
| SubClassOf | Yes | Yes | |
| EquivalentClasses | Yes | Yes | |
| DisjointClasses | Yes | Yes | |
| someValuesFrom | Yes | Yes | |
| allValuesFrom | Yes | Yes | |
| hasValue | Yes | Yes | |
| minCardinality | Yes | Yes | Qualified and unqualified |
| maxCardinality | Yes | Yes | Qualified and unqualified |
| exactCardinality | Yes | Yes | Qualified and unqualified |
| Union (or) | Yes | Yes | |
| Intersection (and) | Yes | Yes | |
| Complement (not) | Yes | Yes | |
| Nested expressions | Yes | Yes | Arbitrary nesting depth |
| Property characteristics (7) | Yes | Yes | All 7 via UI checkboxes |
| Property chains | Yes | Yes | Ordered list editor |
| Inverse properties | Yes | Yes | |
| HasSelf | Yes | No | |
| ObjectOneOf (multiple) | Yes | No | Single individual works |
| Datatype restrictions (facets) | Yes | No | `xsd:integer[> 5]` not supported |
| Custom datatypes | Yes | No | |
| Key axioms (owl:hasKey) | Yes | No | |
| Negative property assertions | Yes | No | |
| Annotation on axioms | Yes | No | |
| **Reasoning** | | | |
| ELK | Yes | Yes | Via ROBOT |
| HermiT | Yes | Yes | Via ROBOT + owlready2 |
| JFact | Yes (plugin) | Yes | Via ROBOT |
| Whelk | No | Yes | Via ROBOT |
| Explanation / justification | Yes | Yes | Via ROBOT explain |
| Consistency checking | Yes | Yes | |
| Incremental reasoning | Yes | No | |
| Background reasoning | Yes | No | Must manually trigger |
| **SWRL** | | | |
| SWRL rule editing | Yes | Yes | Human-readable format |
| SWRL execution | Yes | No | Rules stored as annotations |
| **Collaboration** | | | |
| Real-time multi-user editing | No | Yes | Yjs/Hocuspocus |
| Cursor sharing | No | Yes | |
| Entity locking | No | Yes | Advisory |
| Comments with @mentions | No | Yes | |
| Task management (Kanban) | No | Yes | |
| Invite links | No | Yes | |
| **Visualization** | | | |
| Graph canvas | Plugin (OntoGraf) | Built-in | Cytoscape.js |
| Design patterns | No | Yes | 13 ODPA patterns |
| Minimap | No | Yes | |
| Canvas frames / sticky notes | No | Yes | |
| **Data Integration** | | | |
| CSV import | Plugin (Cellfie) | Built-in | |
| SPARQL query | Plugin (SPARQL Tab) | Built-in | |
| GitHub import | No | Yes | With OWL Functional Syntax auto-conversion |
| **Ontology Management** | | | |
| Provenance tracking | No | Yes | PROV-O + Dublin Core |
| ID range management | Yes (file-based) | Yes | UI + OWL Functional Syntax |
| ODK/ROBOT pipeline | No (CLI only) | Yes | 7 ROBOT commands via UI |
| Version management | Basic | Yes | Semantic versioning |
| Quality reports | No | Yes | ROBOT report, OOPS!, OQuaRE |
| Import resolution UI | Partial | Yes | Download + catalog management |
| **Platform** | | | |
| Desktop (Java) | Yes | No | |
| Web-based | No | Yes | Docker deployment |
| Multi-platform | Via Java | Via Docker | |

### Where Protege Wins

1. **Full OWL 2 construct coverage**: Protege supports every OWL 2 construct including HasSelf, complex ObjectOneOf, datatype facets, custom datatypes, key axioms, negative assertions, and annotation on axioms
2. **Incremental and background reasoning**: Protege can reason in the background and update incrementally
3. **SWRL execution**: Protege can execute SWRL rules via its reasoner integration
4. **Mature plugin ecosystem**: 20+ years of plugins for various use cases
5. **Scale**: Tested with very large ontologies (100,000+ classes)
6. **Native OWL API**: Direct access to the OWL API without subprocess overhead
7. **Offline use**: No network or Docker required

### Where OntoBoard Wins

1. **Real-time collaboration**: Multiple users editing simultaneously with cursor sharing
2. **Web-based**: No local installation, accessible from any browser
3. **ODK/ROBOT integration**: ROBOT commands and ODK workflows from the UI
4. **Visual canvas**: Drag-and-drop graph editing as a first-class feature
5. **Design pattern library**: 13 curated ODPA patterns, drag-and-drop application
6. **Task management**: Built-in Kanban board for team coordination
7. **Provenance tracking**: Automatic PROV-O + Dublin Core metadata
8. **CSV import**: Built-in wizard without plugins
9. **Modern UI**: React-based responsive interface

---

## Roadmap

The following features are planned for future development, organized by priority.

### Phase 13: Remaining OWL Expressiveness

**Goal**: Close the gap with Protege's OWL 2 construct coverage.

- HasSelf support
- ObjectOneOf with multiple individuals
- Datatype restrictions (facets: minInclusive, maxInclusive, pattern, length)
- Custom datatypes
- Key axioms (owl:hasKey)
- Negative property assertions
- Annotation on axioms (reification)

### Phase 14: Remaining ROBOT Commands

**Goal**: Implement the remaining 17 ROBOT commands.

- Full testing of existing endpoints (annotate, repair, extract, filter, merge, rename)
- New commands: mirror, reduce, remove, validate-profile, materialize, measure
- Custom ROBOT report profiles (HPO, OBO Foundry)
- ROBOT template improvements

### Phase 15: Advanced Reasoning

**Goal**: Match Protege's reasoning workflow.

- Background consistency checking (triggered on save)
- Incremental reasoning (only re-check affected axioms)
- DL Query with full OWL 2 DL syntax support
- Proof tree visualization for explanations
- SWRL execution via ROBOT or owlready2

### Phase 16: SHACL and Validation

**Goal**: Add SHACL validation alongside OWL reasoning.

- SHACL shape editor
- SHACL validation endpoint
- Custom SPARQL validation queries
- Validation report UI
- Report profiles for different registries (OBO Foundry, BioPortal, LOV)

### Phase 17: Scale and Performance

**Goal**: Handle larger ontologies efficiently.

- Virtual scrolling for tree browser (handle 10,000+ entities)
- Canvas virtualization (only render visible nodes)
- Lazy loading of ontology data
- SPARQL endpoint for large ontologies (triplestore backend)
- PostgreSQL migration for production deployments

### Phase 18: Authentication and Security

**Goal**: Production-grade authentication.

- OAuth 2.0 / OIDC integration (institutional identity providers)
- Two-factor authentication
- Refresh token mechanism
- Self-service password reset

### Future Considerations

- **CRDT-based collaboration**: Replace polling with true conflict-free replicated data types
- **Plugin system**: Allow community extensions for custom functionality
- **Internationalization**: Multi-language UI
- **Accessibility**: WCAG 2.1 AA compliance
- **Mobile-responsive UI**: Touch-friendly canvas interactions
- **GraphDB/Triplestore integration**: Store ontologies in a triplestore instead of file-based rdflib
- **Ontology alignment tools**: Semi-automated mapping between ontologies
- **AI-assisted modeling**: Suggestions for class hierarchies, property definitions, and axiom patterns
- **Offline mode**: Service worker for offline editing with sync-on-reconnect
- **DOSDP pattern editor**: Dead Simple OWL Design Patterns template editor and instantiation

---

## Feature Coverage Summary

| Category | Implemented | Key Gaps |
|----------|:-----------:|----------|
| Core OWL constructs | 15+ | HasSelf, ObjectOneOf, datatype facets, custom datatypes, keys |
| Property features | 10+ | Negative assertions |
| Manchester Syntax | Parser + renderer | Datatype restrictions, HasSelf |
| ROBOT commands | 7 of 24 | 17 commands not fully implemented |
| Reasoning | ROBOT + owlready2 | No background/incremental reasoning |
| SWRL | CRUD | No execution |
| Collaboration | Full awareness + sync | Not CRDT, last-write-wins, ~15 user max |
| Import management | Resolve + download + catalog | No auto-freshness, no auth |
| Visualization | Canvas + patterns + minimap | Scale limited to ~500 classes |
| Quality | ROBOT report, OOPS!, OQuaRE | No SHACL |
| Authentication | JWT local | No OAuth, no 2FA |
| Database | SQLite | Not tested with PostgreSQL |
