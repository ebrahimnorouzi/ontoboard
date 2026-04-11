# OntoBoard — Complete Requirements Analysis

## Current Implementation Status

### Implemented (12 Phases Complete + Invite System)

| Feature | Phase | Tests |
|---------|:-----:|:-----:|
| Auth (JWT, admin bootstrap) | - | 5 |
| User management (admin CRUD) | - | 8 |
| Board CRUD, sharing, access control | - | 16 |
| Ontology Metadata Dashboard (IRI, stats, prefixes, report) | 1 | 13 |
| Tldraw Canvas (visual OWL editing, auto-tag shapes) | 2 | 13 |
| Manchester Syntax Axiom Editor (Monaco) | 3 | 13 |
| Protege-like Tree Browser (hierarchy, detail, annotation CRUD, entity CRUD) | 4 | 14 |
| Real-time Collaboration (Yjs/Hocuspocus, JWT auth, persistence) | 5 | - |
| ODK Publish Pipeline (quality checks, streaming build) | 6 | 9 |
| Reasoning (ELK/HermiT/JFact/Whelk, inferences, errors, fix suggestions) | 7 | 12 |
| Task Management + GitHub Issues (Kanban, comments) | 8 | 9 |
| CSV Import + KG Generation (analyze, IRI strategies, preview, build) | 9 | 13 |
| SPARQL + KG Visualization (SELECT/CONSTRUCT/ASK, graph viz) | 10 | 12 |
| Documentation Generation (summary markdown, ODK mkdocs) | 11 | 12 |
| Worker Service + Redis Job Queue | 12 | 11 |
| Board Invitation System (invite links, viewer/editor, accept/revoke) | - | 10 |
| **Total** | | **181** |

---

## Missing Features — Protege Expert Analysis

### CRITICAL (Must Fix for Production Use)

#### P-1: Class Restrictions & Expressions
- **someValuesFrom / allValuesFrom**: Can render but NOT create. Need restriction builder UI + `POST /api/axiom/{id}/restriction` endpoint
- **Cardinality constraints** (min/max/exact/qualified): No support at all. Need cardinality fields in restriction form
- **Complex class expressions** (Union, Intersection, Complement): No support. Need Manchester parser for `and`/`or`/`not`
- **hasValue restrictions**: No support
- **Datatype restrictions** (facets: minInclusive, pattern, length): No support

#### P-2: Property Characteristics
All missing — need checkboxes in property detail panel:
- `owl:FunctionalProperty`, `owl:InverseFunctionalProperty`
- `owl:TransitiveProperty`, `owl:SymmetricProperty`
- `owl:AsymmetricProperty`, `owl:ReflexiveProperty`, `owl:IrreflexiveProperty`
- **Implementation**: `PUT /api/axiom/{id}/entity/{iri}/characteristics` with boolean flags

#### P-3: Property Chains
- `owl:propertyChainAxiom` (R1 ∘ R2 ⊆ R3): Not implemented
- Need chain builder UI + `POST /api/axiom/{id}/property-chain`

#### P-4: Global Entity Search
- No full-text search across labels, IRIs, comments
- Need `POST /api/tree/{id}/search` with type filtering, pagination
- Critical for usability on large ontologies

#### P-5: Rename IRI (Cascading)
- Can only delete+recreate entities — loses references
- Need atomic `PUT /api/tree/{id}/entity/{iri}/rename` that updates all references

### HIGH Priority

| ID | Feature | What's Missing |
|----|---------|----------------|
| P-6 | Move entity in hierarchy | No parent change after creation |
| P-7 | Import management | Can read imports, cannot add/remove |
| P-8 | Ontology metadata editor | No DC/DCTERMS form (creator, license, etc.) |
| P-9 | OWL version management | Can read versionIRI, cannot set |
| P-10 | In-session undo/redo | Only git-based history, no Ctrl+Z |
| P-11 | AllDisjointClasses | Only pairwise disjoint, not mutual |

### MEDIUM Priority

| ID | Feature | What's Missing |
|----|---------|----------------|
| P-12 | DL Query language | Only SPARQL, no DL syntax |
| P-13 | Explanation/justification | Can show inferences, cannot explain why |
| P-14 | Find unused entities | No dead code detection |
| P-15 | Find & replace in annotations | No bulk text operations |
| P-16 | SKOS annotation support | No prefLabel/altLabel/definition forms |
| P-17 | SWRL rules | No creation, editing, or execution |
| P-18 | Disjoint properties | Not implemented |
| P-19 | Real-time consistency checking | Must manually run reasoning |
| P-20 | Data/annotation property visualization | Canvas shows object properties only |

---

## Missing Features — ODK/ROBOT Expert Analysis

**Current ROBOT command coverage: 29%** (7 of 24 commands)
**Current ODK workflow coverage: 15%** (2 of 13 workflows)

### CRITICAL (Blocks Production Use)

#### R-1: Import Mirroring (`robot mirror`)
- Cannot download and localize external imports
- Boards can't properly manage ontologies with imports
- Need: `robot_mirror()` + import manager UI + catalog.xml support

#### R-2: Module Extraction (`robot extract`)
- Cannot extract subsets/modules from ontologies
- Need: `robot extract --method STAR/TOP/BOT` + extraction term UI
- Essential for creating focused subsets

#### R-3: edit-file.yaml Support
- Core ODK configuration file cannot be customized
- Need: YAML editor + reload on save

#### R-4: Full Release Workflow (`make release`)
- No semantic versioning, changelog, or GitHub release integration
- Need: version management + `make release` + artifact packaging

#### R-5: DOSDP Template Support
- Dead Simple OWL Design Patterns not supported
- Need: DOSDP YAML editor + `robot template` integration
- Critical for pattern-based ontology development

### HIGH Priority

| ID | Feature | ROBOT Command / ODK Target |
|----|---------|---------------------------|
| R-6 | `robot annotate` | Add annotations/metadata in batch |
| R-7 | `robot rename` | Batch rename IRIs with regex/mapping |
| R-8 | `robot repair` | Auto-fix common ontology issues |
| R-9 | `robot explain` | Generate explanations for entailments |
| R-10 | `robot expand` | Expand asserted + inferred axioms |
| R-11 | Full format conversion | OBO, Turtle, Functional, N-Triples (currently JSON-LD only) |
| R-12 | `robot report --profile` | Custom report profiles (HPO, OBO, etc.) |
| R-13 | `robot validate` | SHACL/SHEXPath validation |
| R-14 | `make all_imports` | Fetch and manage all imports |
| R-15 | `make refresh_imports` | Check import freshness, auto-update |
| R-16 | `make all_subsets` | Generate all declared subsets |
| R-17 | `make all_reports` | Generate all standard reports |
| R-18 | `make check` | Run full ontology health checks |
| R-19 | `make test` | Run test suite with test.owl |
| R-20 | Custom Makefile targets | User-defined build steps |
| R-21 | GitHub release integration | Auto-publish releases to GitHub |
| R-22 | IRI pattern validation | Enforce IRI naming schemes |
| R-23 | Version management | Semantic versioning, version.txt |
| R-24 | Prefix management (write) | Currently read-only from rdflib |

### MEDIUM Priority

| ID | Feature | ROBOT Command |
|----|---------|---------------|
| R-25 | `robot collapse` | Reduce property chains |
| R-26 | `robot filter` | Select/exclude axioms by criteria |
| R-27 | `robot merge` (explicit) | Merge with conflict handling |
| R-28 | `robot relax` | Remove SubClassOf axioms |
| R-29 | `robot unmerge` | Decompose merged ontology |
| R-30 | Custom SPARQL validation | Execute validation.sparql checks |
| R-31 | SPARQL result export | Save query results to CSV/TSV |
| R-32 | Query templates library | Save/reuse common SPARQL queries |
| R-33 | Changelog generation | Auto-generate from git log |
| R-34 | Import health checking | Validate import URIs resolve |
| R-35 | Circular dependency detection | Find import cycles |
| R-36 | Deprecation tracking | Monitor owl:deprecated |
| R-37 | GitHub Actions workflow | Auto-create CI workflow YAML |
| R-38 | Batch annotation tools | Add annotations to multiple entities |
| R-39 | Annotation language support | Multi-language labels/definitions UI |

---

## Implementation Roadmap

### Phase 13: Core OWL Expressiveness (P-1 through P-3)
**Goal**: Make OntoBoard capable of creating real-world ontologies

| Type | File | Purpose |
|------|------|---------|
| new | `app/services/restrictions.py` | Create/edit OWL restrictions, cardinality, hasValue |
| new | `app/services/characteristics.py` | Property characteristics (functional, transitive, etc.) |
| new | `app/schemas/restrictions.py` | Restriction creation schemas |
| mod | `app/services/axiom.py` | Extend Manchester parser for restrictions |
| mod | `app/routers/axiom.py` | Add restriction + characteristics endpoints |
| mod | `app/services/tree.py` | Show characteristics in entity detail |
| new | frontend restriction builder | Visual form for creating restrictions |
| new | frontend characteristics checkboxes | Property detail panel enhancements |

### Phase 14: Search, Rename, Undo (P-4, P-5, P-6, P-10)
**Goal**: Essential usability for working with ontologies

| Type | File | Purpose |
|------|------|---------|
| new | `app/services/search.py` | Full-text search across entities |
| new | `app/services/refactor.py` | Rename IRI (cascading), move in hierarchy |
| new | `app/services/undo.py` | In-session undo/redo stack |
| new | `app/routers/search.py` | Search endpoint |
| new | `app/routers/refactor.py` | Rename/move endpoints |
| new | frontend search dialog | Global Ctrl+F search |
| new | frontend undo/redo buttons | Toolbar buttons + keyboard shortcuts |

### Phase 15: Import & Version Management (P-7, P-9, R-1, R-14, R-15)
**Goal**: Proper ontology import lifecycle

| Type | File | Purpose |
|------|------|---------|
| mod | `app/services/ontology.py` | Add/remove imports, mirror |
| new | `app/services/imports.py` | Mirror, refresh, catalog.xml management |
| new | `app/services/version.py` | Semantic versioning, versionIRI |
| new | `app/routers/imports.py` | Import management endpoints |
| new | frontend import manager | Add/remove/mirror imports UI |
| new | frontend version editor | Version management panel |

### Phase 16: Advanced ODK Integration (R-3, R-5, R-11, R-12)
**Goal**: Full ODK workflow support

| Type | File | Purpose |
|------|------|---------|
| new | `app/services/odk_config.py` | Parse/edit edit-file.yaml |
| new | `app/services/dosdp.py` | DOSDP pattern editor + instantiation |
| mod | `app/services/robot.py` | Add annotate, rename, repair, extract, filter, expand |
| new | `app/routers/odk_config.py` | Config + DOSDP endpoints |
| mod | `app/routers/publish.py` | Full release workflow + GitHub release |
| new | frontend YAML editor | edit-file.yaml editor |
| new | frontend DOSDP panel | Pattern creation + instantiation |

### Phase 17: Advanced Reasoning & Explanation (P-12, P-13, R-9)
**Goal**: Full reasoning capabilities matching Protege

| Type | File | Purpose |
|------|------|---------|
| new | `app/services/dl_query.py` | DL Query parser + execution |
| mod | `app/services/reasoning.py` | Explanation generation via ROBOT explain |
| new | `app/routers/dl_query.py` | DL Query endpoint |
| new | frontend DL query tab | DL Query editor with results |
| new | frontend explanation viewer | Proof tree visualization |

### Phase 18: Quality & Validation (R-12, R-13, R-30)
**Goal**: Comprehensive ontology quality assurance

| Type | File | Purpose |
|------|------|---------|
| new | `app/services/validation.py` | SHACL validation, custom SPARQL checks |
| mod | `app/services/publish.py` | Report profiles, custom checks |
| new | `app/routers/validation.py` | Validation endpoints |
| new | frontend validation panel | Check results, fix suggestions |

---

## Summary Statistics

| Category | Implemented | Missing | Total |
|----------|:-----------:|:-------:|:-----:|
| Core OWL Expressiveness | 3 | 12 | 15 |
| Property Features | 2 | 10 | 12 |
| Search & Refactoring | 1 | 7 | 8 |
| Import Management | 1 | 6 | 7 |
| ODK/ROBOT Commands | 7 | 17 | 24 |
| ODK Workflows | 2 | 11 | 13 |
| Quality Control | 3 | 10 | 13 |
| Reasoning & Explanation | 4 | 5 | 9 |
| Release & CI/CD | 0 | 8 | 8 |
| DOSDP Patterns | 0 | 5 | 5 |
| Visualization | 3 | 4 | 7 |
| Collaboration | 5 | 2 | 7 |
| **Total** | **31** | **97** | **128** |

**Current feature coverage: ~24%**
