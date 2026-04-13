# Limitations and Roadmap

This document lists known limitations of OntoBoard and planned improvements.

## Known Limitations

### Collaboration Model

**Polling-based sync, not true CRDT**

OntoBoard uses Yjs/Hocuspocus for awareness (cursor sharing) and save notifications, but the actual ontology data is not stored in a CRDT structure. Instead, the collaboration flow works as follows:

1. User A saves the canvas to the backend via HTTP
2. A Yjs broadcast notifies other clients that a save occurred
3. User B's client fetches the latest state from the backend via HTTP

This means:
- **Last-write-wins**: If two users edit the same entity simultaneously and save within the 800ms debounce window, the second save overwrites the first. There is no automatic merge of concurrent edits.
- **No offline support**: Edits made while disconnected are lost if another user saves in the meantime.
- **Polling fallback**: If the WebSocket connection drops, clients fall back to polling the `sync-check` endpoint, which increases latency for detecting changes.

Entity locking is advisory only -- it is not enforced at the API level. Two users can technically edit the same entity simultaneously.

### OWL Format Support

**Primary format: OWL/XML**

OntoBoard uses rdflib for OWL parsing. rdflib handles:
- OWL/XML (primary format)
- Turtle (.ttl)
- RDF/XML
- N-Triples (.nt)
- JSON-LD

For formats that rdflib cannot parse natively (e.g., OWL Functional Syntax), OntoBoard uses ROBOT `convert` to transform them into OWL/XML first. This adds a processing step and requires ROBOT/Java to be available.

**Limitations:**
- OWL Functional Syntax must be converted before editing (not native)
- OBO Format requires ROBOT for conversion
- Manchester Syntax is supported for axiom editing but not as a file format
- Very large ontologies (50,000+ triples) may cause slow rdflib parsing

### Scale Limits

**Tested up to approximately 10 concurrent users**

| Dimension | Tested Limit | Notes |
|-----------|:------------:|-------|
| Concurrent users | ~10 | WebSocket connection limit depends on Hocuspocus configuration |
| Nodes on canvas | ~400 | Cytoscape.js performance degrades with 500+ nodes due to rendering cost |
| Triples in ontology | ~50,000 | rdflib parsing becomes slow beyond this; ROBOT handles larger files |
| Board file size | ~50 MB | Limited by Docker volume mount and file I/O |
| Concurrent boards | No hard limit | Limited by memory (SQLite DB + rdflib graphs in memory) |

For larger ontologies, consider:
- Using the tree browser and axiom editor instead of the canvas (which loads all entities)
- Splitting ontologies into modules using ROBOT extract
- Using the ODK import system to work with subsets

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

### Database

**SQLite in development mode**

The default database is SQLite, which has limitations:
- Single-writer concurrency (SQLAlchemy's `check_same_thread=False` allows multi-threaded reads but writes are serialized)
- Not suitable for high-concurrency production deployments with many users
- Database file is stored at `data/ontoboard.db`

The SQLAlchemy ORM is database-agnostic, so switching to PostgreSQL for production requires only changing the `DATABASE_URL` environment variable. However, this has not been extensively tested.

### Authentication

- No OAuth/OIDC integration (JWT-based local authentication only)
- No two-factor authentication
- Token expiration is configurable (default: 8 hours) but there is no refresh token mechanism
- Password reset requires admin intervention (no self-service email reset)

### Docker Dependencies

- The worker service requires Docker socket access (`/var/run/docker.sock`) to run ODK containers
- The odkfull Docker image (~2.5 GB) must be pulled on first use
- Docker-in-Docker is used for ODK operations, which adds complexity and may not work in all environments (e.g., some CI/CD systems, rootless Docker)

---

## Missing OWL Features

Based on a comparison with Protege, the following OWL features have limited or no support:

### Partially Implemented

| Feature | Current State | Gap |
|---------|--------------|-----|
| Class restrictions | someValuesFrom and allValuesFrom are supported via the restriction builder | Qualified cardinality, complex nested expressions need improvement |
| Property characteristics | All 7 characteristics (functional, transitive, etc.) can be set | UI could be more discoverable |
| Import management | Can add/remove imports | No automatic mirroring or catalog.xml generation |
| SWRL rules | Basic listing, add, delete | No execution or integration with reasoning |

### Not Implemented

| Feature | Description |
|---------|-------------|
| Explanation/justification | Cannot explain why an inference holds (no proof tree) |
| Real-time consistency checking | Must manually trigger reasoning; no background checking |
| Data/annotation property visualization on canvas | Canvas shows only object properties as edges |
| SKOS annotation forms | No dedicated prefLabel/altLabel/definition input fields |
| Custom datatypes | Cannot define new datatypes with facet restrictions |
| Keys (owl:hasKey) | Cannot define key axioms for classes |
| Negative property assertions | Cannot assert that an individual does NOT have a property value |
| Annotation assertion axioms on axioms | Cannot annotate axioms themselves (reification needed) |

---

## Roadmap

The following features are planned for future development, organized by priority.

### Phase 13: Core OWL Expressiveness

**Goal**: Make OntoBoard capable of creating production-quality ontologies.

- Qualified cardinality restrictions (min, max, exactly with class filler)
- Complex class expression builder (union, intersection, complement with nesting)
- hasValue restrictions
- Datatype restrictions (facets: minInclusive, maxInclusive, pattern, length)
- Property chain axiom builder

### Phase 14: Search, Rename, and Usability

**Goal**: Essential usability improvements for working with large ontologies.

- Global full-text search across labels, IRIs, comments, and annotations (with type filtering and pagination)
- Atomic IRI rename with cascading reference updates
- Move entity in hierarchy (reparent)
- Improved undo/redo with operation-level granularity (not just canvas snapshots)

### Phase 15: Import and Version Management

**Goal**: Proper ontology import lifecycle management.

- Import mirroring (`robot mirror`)
- catalog.xml support for local resolution
- Import freshness checking and auto-update
- Semantic versioning workflow (version.txt, versionIRI auto-increment)
- Version comparison and diff visualization

### Phase 16: Advanced ODK Integration

**Goal**: Full ODK workflow support beyond basic build/test/release.

- edit-file.yaml editor with schema validation
- DOSDP (Dead Simple OWL Design Patterns) template editor and instantiation
- Custom Makefile target execution
- Full format conversion pipeline (OBO, Turtle, Functional, N-Triples)
- Custom ROBOT report profiles (HPO, OBO Foundry, etc.)

### Phase 17: Advanced Reasoning and Explanation

**Goal**: Match Protege's reasoning capabilities.

- DL Query with full OWL 2 DL syntax support
- Explanation/justification for inferences (proof tree visualization)
- ROBOT explain integration
- Background consistency checking (triggered on save)

### Phase 18: Quality and Validation

**Goal**: Comprehensive ontology quality assurance.

- SHACL validation
- Custom SPARQL validation queries
- Report profiles for different ontology registries (OBO Foundry, BioPortal, LOV)
- Automated fix suggestions for common quality issues

### Future Considerations

- **OAuth/OIDC integration**: Allow login via institutional identity providers
- **PostgreSQL migration**: For production deployments with high concurrency
- **CRDT-based collaboration**: Replace polling with true conflict-free replicated data types for concurrent editing
- **Plugin system**: Allow community extensions for custom functionality
- **Internationalization**: Multi-language UI
- **Accessibility**: WCAG 2.1 AA compliance
- **Mobile-responsive UI**: Touch-friendly canvas interactions
- **GraphDB/Triplestore integration**: Store ontologies in a proper triplestore instead of file-based rdflib parsing
- **Ontology alignment tools**: Semi-automated mapping between ontologies
- **AI-assisted modeling**: Suggestions for class hierarchies, property definitions, and axiom patterns

---

## Feature Coverage Summary

Based on the REQUIREMENTS.md analysis:

| Category | Implemented | Missing | Coverage |
|----------|:-----------:|:-------:|:--------:|
| Core OWL Expressiveness | 3 | 12 | 20% |
| Property Features | 2 | 10 | 17% |
| Search and Refactoring | 1 | 7 | 13% |
| Import Management | 1 | 6 | 14% |
| ODK/ROBOT Commands | 7 | 17 | 29% |
| ODK Workflows | 2 | 11 | 15% |
| Quality Control | 3 | 10 | 23% |
| Reasoning and Explanation | 4 | 5 | 44% |
| Release and CI/CD | 0 | 8 | 0% |
| DOSDP Patterns | 0 | 5 | 0% |
| Visualization | 3 | 4 | 43% |
| Collaboration | 5 | 2 | 71% |
| **Total** | **31** | **97** | **24%** |

The platform provides a functional and usable ontology editor with collaboration, but achieving full Protege feature parity and complete ODK workflow integration requires continued development across the phases listed above.
