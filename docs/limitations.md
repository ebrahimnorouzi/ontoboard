# Limitations and Roadmap

This document lists known limitations of OntoBoard, provides a detailed comparison with Protege, and outlines planned improvements.

## Known Limitations

### 1. Collaboration Model

**Operation-based CRDT with semantic merge**

OntoBoard uses an operation-based CRDT system (Phases 1-3) built on Yjs/Hocuspocus. Each ontology mutation is propagated as an individual operation via a shared `Y.Array`, achieving < 100ms cross-client sync. A semantic merge engine auto-resolves non-conflicting concurrent edits (different fields, additive ops, position-only changes) and surfaces true conflicts (same semantic field) with resolution UI.

**Remaining limitations:**
- **No offline support**: The operation log is ephemeral (in Hocuspocus memory). Edits made while disconnected are not queued for replay. If a user disconnects and reconnects, they reload from the backend.
- **Operation log growth**: The `Y.Array("ops")` grows unbounded during a session. For very long sessions with many edits, Hocuspocus memory may increase. Restarting the collab server clears the log (clients reload from backend).
- **Tested up to ~10-15 users**: Beyond this range, performance has not been validated. The merge engine handles N-user conflicts, but network overhead scales with user count.
- **Entity locking is advisory**: Not enforced at the API level. Two users can edit the same entity simultaneously -- the merge engine will classify the result.
- **Position-only merges use last-write-wins**: Concurrent position edits (drag-to-move) are resolved by timestamp, which may cause a brief visual jump for the "losing" user.
- **Consistency check is manual**: The `POST /api/reasoning/{board_id}/consistency-check` endpoint must be called explicitly after a merge to verify OWL consistency. It is not triggered automatically.

### 2. Embedded Reasoner (owlready2) Issues

- **Windows path issues**: `file:///` URIs in owlready2 may fail on Windows paths with spaces or non-ASCII characters
- **Performance**: Slower than ROBOT for large ontologies (owlready2 loads the entire ontology into Python memory)
- **Java dependency**: owlready2 requires Java for its HermiT bridge, even though it's an "embedded" reasoner
- **Inference differences**: May produce slightly different results than ROBOT's reasoners for edge cases

### 3. Import Resolution Limitations

- Downloads may fail for ontologies behind authentication (no credential support)
- No HTTP proxy support
- No automatic freshness checking (must manually re-resolve)
- Large import chains may be slow to resolve sequentially

### 4. OWL Format Support

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

### 5. Canvas Rendering

- Edges only show on canvas if both source and target nodes exist on the canvas
- Performance degrades beyond ~500 classes on the canvas
- Tab scrolling uses simple conditional rendering (no wrapper divs) for reliable scrolling in all panels

### 6. Scale Limits

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

### 7. ROBOT Command Coverage

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

### 8. Widoco

- Widoco 1.4.25 is installed in the backend Docker image
- Requires Docker image rebuild (`./run.sh build`) if Widoco is updated or if the image was built before Widoco was added

### 9. Other Limitations

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
- **owlready2 Windows path issues**: `file:///` URIs may fail on Windows paths with spaces or non-ASCII characters

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
| HasSelf | Yes | Yes | `likes Self` |
| ObjectOneOf (multiple) | Yes | Yes | `{john, jane, bob}` |
| Datatype restrictions (facets) | Yes | Yes | `xsd:integer[>= 0, <= 100]` |
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
| SWRL native OWL/XML storage | Yes | Yes | `swrl:Imp` + `swrl:ClassAtom` + `swrl:Variable` |
| SWRL execution | Yes | Yes | Standard reasoners can execute native SWRL rules |
| **Collaboration** | | | |
| Real-time multi-user editing | No | Yes | Operation-based CRDT + semantic merge |
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
| CSV import | Plugin (Cellfie) | Built-in | ROBOT Template Builder with merge + consistency check |
| SPARQL query | Plugin (SPARQL Tab) | Built-in | |
| GitHub import | No | Yes | With OWL Functional Syntax auto-conversion |
| HTML documentation | No (plugin) | Yes | Widoco 1.4.25 |
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

1. **Remaining OWL 2 constructs**: Protege supports custom datatypes, key axioms (`owl:hasKey`), negative property assertions, and annotation on axioms -- which OntoBoard does not yet implement
2. **Incremental and background reasoning**: Protege can reason in the background and update incrementally
3. **Mature plugin ecosystem**: 20+ years of plugins for various use cases
4. **Scale**: Tested with very large ontologies (100,000+ classes)
5. **Native OWL API**: Direct access to the OWL API without subprocess overhead
6. **Offline use**: No network or Docker required

### Where OntoBoard Wins

1. **Real-time collaboration**: Multiple users editing simultaneously with cursor sharing
2. **Web-based**: No local installation, accessible from any browser
3. **ODK/ROBOT integration**: ROBOT commands and ODK workflows from the UI
4. **Visual canvas**: Drag-and-drop graph editing as a first-class feature
5. **Design pattern library**: 13 curated ODPA patterns, drag-and-drop application
6. **Task management**: Built-in Kanban board for team coordination
7. **Provenance tracking**: Automatic PROV-O + Dublin Core metadata
8. **CSV import**: ROBOT Template Builder with 6-step wizard, merge, and consistency check
9. **HTML documentation**: Widoco 1.4.25 integration for comprehensive ontology documentation
10. **Modern UI**: React-based responsive interface with in-app documentation

---

## Roadmap

The following features are planned for future development, organized by priority.

### Phase 13: Remaining OWL Expressiveness

**Goal**: Close the remaining gap with Protege's OWL 2 construct coverage.

**Resolved in this release:**
- ~~HasSelf support~~ -- implemented (`likes Self`)
- ~~ObjectOneOf with multiple individuals~~ -- implemented (`{john, jane, bob}`)
- ~~Datatype restrictions (facets)~~ -- implemented (`xsd:integer[>= 0, <= 100]`)

**Still planned:**
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
- ~~SWRL execution via ROBOT or owlready2~~ -- resolved (native OWL/XML SWRL format, executable by standard reasoners)

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

- ~~**CRDT-based collaboration**~~: Implemented (Phases 1-3: operation log, conflict detection, semantic merge)
- **Plugin system**: Allow community extensions for custom functionality
- **Internationalization**: Multi-language UI
- **Accessibility**: WCAG 2.1 AA compliance
- **Mobile-responsive UI**: Touch-friendly canvas interactions
- **GraphDB/Triplestore integration**: Store ontologies in a triplestore instead of file-based rdflib
- **Ontology alignment tools**: Semi-automated mapping between ontologies
- **AI-assisted modeling**: Suggestions for class hierarchies, property definitions, and axiom patterns
- **Offline mode**: Queue operations locally in IndexedDB, replay on reconnect (Phase 4 of CRDT plan)
- **DOSDP pattern editor**: Dead Simple OWL Design Patterns template editor and instantiation

---

## Feature Coverage Summary

| Category | Implemented | Key Gaps |
|----------|:-----------:|----------|
| Core OWL constructs | 18+ (incl. HasSelf, ObjectOneOf, datatype facets) | Custom datatypes, keys, negative assertions |
| Property features | 10+ | Negative assertions |
| Manchester Syntax | Full OWL 2 parser + renderer | None for standard constructs |
| SWRL | Native OWL/XML format, executable by reasoners | -- |
| ROBOT commands | 7 of 24 | 17 commands not fully implemented |
| Reasoning | ROBOT + owlready2 | No background/incremental reasoning |
| CSV Import | ROBOT Template Builder (6-step wizard) | -- |
| Documentation | Widoco 1.4.25 HTML generation | Requires rebuild for updates |
| Collaboration | Operation-based CRDT + semantic merge + conflict UI | No offline support, ~15 user max |
| Import management | Resolve + download + catalog | No auto-freshness, no auth |
| Visualization | Canvas + patterns + minimap | Scale limited to ~500 classes |
| Quality | ROBOT report (with violation cards), OOPS!, OQuaRE | No SHACL |
| Authentication | JWT local | No OAuth, no 2FA |
| Database | SQLite | Not tested with PostgreSQL |
| Testing | 500+ backend tests + 44 frontend tests | -- |
