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
  in the plugin can run it. What changed in 1.25.0 is that the obstacle is gone rather than the
  feature being done: `OntologyDataset` already produces a Jena model on both hosts and
  `jena-arq` is already embedded, so this is now a panel and a menu item, not a compatibility
  problem. Protégé 5.6.9 also ships `sparql-query-plugin`, which covers ad-hoc querying; the
  gap OntoBoard should close is running *the project's own* committed `.rq` checks.
- **Pull requests** — *Git…* does status, stage, commit, pull, push and branch, and
  *Open from GitHub…* clones. Opening or reviewing a PR still means leaving Protégé.
- **Widoco HTML documentation.**
- **Entity locking** — nothing stops two live collaborators editing the same term at once.

### Known constraints

- **The bundle-resource barrier is fixed as of 1.25.0.** It is written up here rather than
  deleted, because the way it hid for nine versions is the more useful part.

  `ReportOperation` finds its queries with
  `ReportOperation.class.getClassLoader().getResource("report_queries")` and then compares
  `URL.getProtocol()` against `"file"` and `"jar"`. Inside an OSGi bundle the protocol is
  neither — Felix answers `bundle` — so it threw `IOException: Cannot access report query
  files`, and **ROBOT report failed on both hosts**, not just on 5.5.0. Every public entry
  point routes through that one private method: `getReport`, all six `report` overloads, and
  `getTDBReport`, which looked like an escape until its bytecode turned out to call
  `getReportQueries` too. (An earlier note here claimed `getViolations` was a way round it.
  There is no such method; `javap` settles it.)

  No test could fail on this. Maven runs against an exploded classpath where
  `getResource("report_queries")` returns a `file:` URL and works perfectly. The failure was
  sitting in `~/.Protege/logs/protege.log:399033` under OntoBoard 1.15.0 the whole time.

  The fix does not extract the jar, which is what this entry previously proposed. Directory
  *enumeration* is the only thing that fails; `getResourceAsStream` on a known path works. So
  `ReportQueries` reads robot-core's own `report_profile.txt` and its 33 `report_queries/*.rq`
  as streams, and `RuleRunner` runs them over a Jena model built by `OntologyDataset`. The
  rules, the severities and the SPARQL stay ROBOT's; only the plumbing is ours. The cost is
  that a robot-core upgrade which renames a rule or moves those resources breaks this where
  the CLI would not — `SelfCheck` and `OdkScaffoldTest` turn that into a failure rather than a
  silent gap.

  Two things check it, because "it works in the bundle" cannot be checked by a unit test.
  `RobotParityTest` runs `robot report` inside `obolibrary/odkfull` over the same ontology and
  the same profile and requires an identical violation set — 7/7 rows on `fixture-tiny`, 21/21
  on pizza v2. And `OntoBoardStartup` runs `SelfCheck` in the host at startup, which
  `tools/smoke.ps1` asserts; the 1.25.0 receipt records `PASS 4/4` on **both** installs.
- **The OWL API Rio barrier is real, and nothing the plugin ships touches it.** OWL API moved
  its RDF layer from Sesame to RDF4J at 4.5.25 and `robot-core` targets the newer form, so a
  code path that constructs a `RioRenderer` fails on Protégé 5.5.0's 4.5.9 and works on
  5.6.x's 4.5.29. In `robot-core` the call site is `QueryOperation.loadOntologyAsModel`, and
  the plugin has no reference to it — `OntologyDataset` goes through RDF/XML bytes instead,
  which every OWL API version can write and Jena can read without any OWL API at all.

  So this entry no longer describes a limitation of anything that ships. It used to say the
  report "could not work" on 5.5.0; the 1.25.0 receipt shows it passing there on OWL API
  4.5.9. `export` was also listed as blocked by this and never was — its constant pool
  contains no match for `rdf4j`, `openrdf` or `rio`. The practical consequence is for whatever
  is built next: a SPARQL panel should build its model with `OntologyDataset`, not with
  `QueryOperation`, and then it works on both hosts.
- **ROBOT cannot write `.xlsx` output from inside the bundle.** `log4j-api` is excluded
  because it declares its own OSGi `Bundle-Activator` (`org.apache.logging.log4j.util.Activator`,
  in its manifest) and bnd rejects a second one, which leaves Apache POI without the logging
  backend POI 5.x expects. The bundle does embed `log4j-over-slf4j`, but that implements the
  log4j **1.x** API, not the 2.x one POI calls.

  This entry used to say "ROBOT's XLSX *template* path is unavailable", which is wrong twice
  over. robot-core 1.9.8 has no XLSX template-reading path at all: `IOHelper.readTable`
  delegates to `TemplateHelper.readTable`, whose bytecode contains no POI reference of any
  kind, and the only POI users in robot-core are `export.Cell`, `export.Row`, `export.Table`
  and `ReportOperation` — all of them *writing* spreadsheets. So templates are unaffected, but
  for a different reason than was given, and what is actually unavailable is spreadsheet
  output. The plugin's report no longer goes near `Table`, so it is further from POI than the
  version that claimed to be safe from it.
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

1068 tests cover projection, axiom construction, layout persistence, ODK scaffolding, the
report's rule execution and the OSGi configuration. Two of them reach outside the JVM:
`RobotParityTest` compares the report against real ROBOT in `obolibrary/odkfull` and is
*skipped*, not failed, where Docker is absent — so a green run on a machine without Docker
proves less than it looks. They do **not** cover any Swing UI:
dialogs, toolbar, minimap, drop handling, and every visual choice need a display and are
verified by hand. Treat visual behaviour as unverified after each change.

Behaviour *inside the bundle* is a third category, and the one that has cost the most. No test
can see it, because Maven's classpath is not Felix's. `OntoBoardStartup` now runs `SelfCheck`
at startup and `tools/smoke.ps1` asserts the verdict, so four things are checked in the host on
every release: robot-core's profile is readable, all 32 of its queries are readable, Jena can
read what the OWL API writes, and the report produces findings end to end. That is four things,
not the 21 menu items — driving those is still Phase 2's self-test bundle.

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
   round. No longer blocked by anything: `OntologyDataset` and the embedded `jena-arq` are
   what it needs, and both hosts can do it.
3. **Pattern library and Widoco.**
4. **Entity locking** — once live collaboration has been used in anger.
5. **Collaboration vocabulary** — the nine axiom kinds the live protocol cannot carry.

Done, with the release that did it: live collaboration and cursors (1.9.0); the entity-view
column and tab layout (1.8.0); ROBOT transforms, term import and the quality report
(1.10.0–1.18.0); git tooling, the ODK build runner, ID range editing, provenance stamping,
editorial notes, frames and sticky notes, obsoletion, OWL 2 profile checking, release
comparison and ROBOT templates (1.19.0); properties and inferred individual types on the
canvas (1.20.0); ROBOT's quality report actually running inside the bundle, on both hosts,
checked against real ROBOT and against a startup self-check in the host (1.25.0).

Those middle releases all shipped under one unchanged version number, which is why the list
above names versions at all — see the note in [development](development.md) about what that
cost.

Specs and plans live in [superpowers/](superpowers/), including the reasoning behind
decisions that were reversed — the plugin was briefly intended to replace the web
application, and does not.
