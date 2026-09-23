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
- **Threaded comments and discussion** — a term can be linked to its issue with
  *Notes → Discussion link…*, which writes `IAO:0000233 term tracker item`, and editorial
  notes (`IAO:0000116`/`IAO:0000232`) carry an author and date. But the argument itself
  lives in the tracker: there is no reply thread inside the ontology, deliberately, because
  an unbounded mutable conversation would appear in every release and every diff.
- **Ontology design pattern library** — the bundled ODPA patterns are not exposed.
- **SPARQL query panel** — the ODK scaffold writes `src/sparql/check_labels.rq` and nothing
  in the plugin can run it.
- **Pull requests** — *Git…* does status, stage, commit, pull, push and branch, and
  *Open from GitHub…* clones. Opening or reviewing a PR still means leaving Protégé.
- **Widoco HTML documentation.**
- **Entity locking** — nothing stops two live collaborators editing the same term at once.

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

1045 tests cover projection, axiom construction, layout persistence, ODK scaffolding, report
parsing and the OSGi configuration. They do **not** cover any Swing UI:
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

1. **Pull requests** — the rest of the git surface ships; opening and reviewing a PR does not.
2. **SPARQL** — the scaffold writes a check the plugin cannot run, which is the wrong way
   round.
3. **Pattern library and Widoco.**
4. **Entity locking** — once live collaboration has been used in anger.
5. **Collaboration vocabulary** — the nine axiom kinds the live protocol cannot carry.

Done, with the release that did it: live collaboration and cursors (1.9.0); the entity-view
column and tab layout (1.8.0); ROBOT transforms, term import and the quality report
(1.10.0–1.18.0); git tooling, the ODK build runner, ID range editing, provenance stamping,
editorial notes, frames and sticky notes, obsoletion, OWL 2 profile checking, release
comparison and ROBOT templates (1.19.0); properties and inferred individual types on the
canvas (1.20.0).

Those middle releases all shipped under one unchanged version number, which is why the list
above names versions at all — see the note in [development](development.md) about what that
cost.

Specs and plans live in [superpowers/](superpowers/), including the reasoning behind
decisions that were reversed — the plugin was briefly intended to replace the web
application, and does not.
