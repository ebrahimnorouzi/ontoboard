# Limitations and Roadmap

Honest inventory of what each OntoBoard client does not do. Kept specific rather than
reassuring, because a quality tool that overstates itself is worse than one that admits
gaps.

## Protégé plugin

### Not implemented

The plugin is early. These exist in the web application but not yet here:

- **Live collaboration** — built as of 1.9.0, but only for the seventeen operation types
  the web application understands. Equivalence, disjointness, property characteristics,
  chains, `owl:hasKey`, negative assertions, global domain and range, datatype definitions
  and imports do **not** travel; the plugin counts them and says so in the toolbar rather
  than dropping them silently. Use git for work on those. See
  [collaboration](collaboration.md).
- **Comments and discussion** — none.
- **Ontology design pattern library** — the bundled ODPA patterns are not exposed.
- **CSV / ROBOT template import** — no wizard.
- **SPARQL query panel.**
- **GitHub integration** — no clone, branch, commit or PR support.
- **Widoco HTML documentation.**
- **Import resolution and catalog management UI.**
- **ID range management UI** — the ODK wizard writes an id-ranges file, but nothing edits it.
- **Provenance stamping** (PROV-O / Dublin Core on edit).
- **Frames and sticky notes** — the sidecar format reserves them; nothing draws them.

### Known constraints

- **ROBOT coverage depends on the host's OWL API.** On Protégé 5.5.0 (OWL API 4.5.9),
  ROBOT's report, SPARQL query and export fail, because OWL API moved its RDF layer from
  Sesame to RDF4J at 4.5.25 and `robot-core` targets the newer form. Loading, reasoning,
  saving and conversion work. Protégé 5.6.x (OWL API 4.5.29) has the full surface. The
  plugin reports which operations are unavailable rather than failing obscurely.
- **ROBOT's XLSX template path is unavailable.** `log4j-api` is excluded from the bundle
  because it declares its own OSGi `Bundle-Activator` and bnd rejects two, which leaves
  Apache POI without a logging backend. TSV templates are unaffected.
- **Guava is pinned to 18.0**, the version Protégé ships. ROBOT paths beyond reasoning are
  not individually verified against it.
- **Canvas layout is not interoperable with CoModIDE**, which stores positions as OPLa-SD
  annotations inside the ontology. OntoBoard uses a sidecar file to keep the ontology
  byte-clean, so ROBOT `report` and `diff` stay unaffected. The trade is deliberate.
- **Sidecar writes are not atomic.** A crash mid-write can lose diagram layout — never the
  ontology.
- **A punned IRI** (declared as both a class and an individual) resolves
  non-deterministically for canvas selection.
- **Canvas scale.** The canvas is opt-in precisely because rendering a 100,000-class
  ontology is not viable; expect to work with tens of entities at a time, not thousands.
- **The bundle is ~64 MB**, because ROBOT and its dependencies are embedded to run
  in-process.
- **`make` targets need `make` and ROBOT on PATH.** The ODK wizard creates a project without
  Docker, but building it is a normal ODK build.

### Not covered by tests

309 tests cover projection, axiom construction, layout persistence, ODK scaffolding, report
parsing and the OSGi configuration. They do **not** cover any Swing UI: the palette panel,
dialogs, toolbar, minimap, drop handling, and every visual choice need a display and are
verified by hand. Treat visual behaviour as unverified after each change.

## Web application

- **No offline support.** The operation log is ephemeral; edits made while disconnected are
  not queued for replay.
- **Tested to roughly 10–15 concurrent users.** Beyond that, network overhead and
  `Y.Array` growth are unvalidated.
- **Entity locking is advisory**, not enforced at the API level.
- **Position conflicts use last-write-wins**, which can cause a brief visual jump.
- **Consistency checking is manual** — reasoning must be triggered explicitly.
- **7 of 24 ROBOT commands** are fully implemented and tested.
- **No SHACL validation**, no OAuth/OIDC, no 2FA, no self-service password reset.
- **SQLite in development**; PostgreSQL is untested.
- OWL constructs not supported: custom datatypes, `owl:hasKey`, negative property
  assertions, annotations on axioms.

## Roadmap

Ordered by what is being worked on:

1. **Git tooling in the plugin** — branch, commit, push and `ROBOT diff` from the OntoBoard
   tab. Git mode is selectable today, but doing the git part still means leaving Protégé.
2. **ROBOT and ODK surface in the plugin** — report view, reasoning, Makefile targets,
   import resolution.
3. **Data integration** — pattern library, CSV/template wizard, SPARQL, GitHub.
4. **Quality** — ROBOT report UI, OOPS!, OQuaRE, provenance, ID ranges.
5. **Comments and entity locking** — once live collaboration has been used in anger.

Done: live collaboration and cursors (1.9.0); the entity-view column and the tab layout that
actually reaches upgraded users (1.8.0).

Specs and plans live in [superpowers/](superpowers/), including the reasoning behind
decisions that were reversed — the plugin was briefly intended to replace the web
application, and does not.
