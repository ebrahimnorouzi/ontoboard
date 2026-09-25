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
- ~~**SPARQL query panel**~~ — **done in 1.33.0.** *ROBOT → SPARQL…* runs a query you write, or
  the project's own checks in `src/sparql` with the same pass/fail convention `make sparql_test`
  uses. `SELECT` and `ASK` only: a curator exploring a query should not be one typo away from an
  `INSERT`. Protégé 5.6.9 also ships `sparql-query-plugin` for ad-hoc querying; what OntoBoard
  adds is running *the project's own committed checks* against the ontology in front of you.
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
- **Every operation is now compared against the real command line, not just the report.** Until
  1.48.0 `report` was the only one. The evidence for the rest was that they did not throw, which is
  weaker than it sounds: a wrapper that passes an option in the wrong place produces plausible
  output that nobody can tell is wrong.

  `RobotCliParityTest` runs nine operations twice — once in process through the plugin, once as the
  `robot` command a project's CI actually runs in `obolibrary/odkfull` — over the same pizza, and
  requires the answers to match: `reason` (the inferred axioms), `measure` (every metric name and
  value), `export` (the table, row for row), `extract` (the module, axiom for axiom), `diff` (the
  text), `verify` (which checks fail and with how many violations), `explain` (which classes, and
  the axioms the justification rests on) and `template` (the axioms built).

  Each comparison passes the options the plugin's own call passes, not the ones a Makefile passes —
  comparing `robot reason --exclude-tautologies` against a plugin call that asks for ROBOT's
  defaults would be comparing two different questions and reporting the difference as a defect.

  Doing this found four real defects, none of which threw: a written export joined a multi-valued
  cell with the string `tsv` instead of a bar, because the format name was passed where ROBOT wants
  the separator; the measurements panel silently omitted a whole class of metrics, including the
  axiom-type breakdown, because only two of `MeasureResult`'s three maps were read — twelve of the
  ninety-eight it reports for pizza v2; the set-valued metrics came
  out in hash order, so the panel reshuffled between runs and never matched a build's metrics file;
  and the explanation named one class two ways at once.

  The suite deliberately compares two ROBOT versions: the plugin embeds robot-core 1.9.8 and
  `odkfull` ships 1.9.10. Pinning both to one version would make it agree with itself and answer a
  question nobody asked, since what a project's CI runs is `odkfull`. The cost is that their OWL API
  versions render an axiom slightly differently — an explicit `^^xsd:string` on a plain literal, and
  a space before one closing parenthesis — and the suite normalises exactly those two renderings and
  nothing else.
- **Live collaboration did not work at all before 1.50.0, and its tests all passed.** Worth reading
  as a method failure rather than three bugs, because the method is reusable and the bugs are not.

  The plugin's collaboration tests drive the client against a fake transport. The server's tests
  drive the bridge against a document loader they supply themselves. Both suites are thorough - 130
  Java tests and 41 JavaScript ones - and between them they establish that each side is
  self-consistent. Neither can say anything about the join, and the join was where all three defects
  were:

  1. `server.mjs` called `openDirectConnection` on the `Server` wrapper rather than on the
     `Hocuspocus` instance it holds. Resolving a board threw, the bridge answered "could not open
     board" and closed the socket, so **no Protégé peer could join any board in any release**.
  2. `CollabMessages` flattened nested payload fields to strings, with a comment saying nested
     structures were "stringified rather than lost". They were lost. A label travels as
     `data.updates.label`, the code applying it asks `updates instanceof Map`, and a String is not a
     Map - so **renaming or labelling a term never reached a peer**, silently. Outbound was fine,
     which is why no round trip inside one language could show it.
  3. `livePeers` in the bridge left the `ontology` field out of the payload it broadcast, though the
     connection record carried it. So every peer's ontology arrived empty and
     `CollabSession.peerOntologyWarning` - the warning that two people on a colliding board id are
     editing unrelated ontologies - **could never fire**.

  All three are now pinned by tests that cross the socket: `CollabLiveTest` starts the shipped
  server, connects two sessions and requires edits, labels, renames, notes and the mismatch warning
  to arrive; `collab/__tests__/server-boot.test.mjs` does the same from the JavaScript side. Each was
  confirmed to fail against the unfixed code before being kept.

  The lesson, stated so it outlives the fix: **a test that injects a dependency cannot check the
  wiring.** Where two implementations meet, one test has to run both.
- **A live session belongs to one ontology, and did not before 1.51.0.** It resolved "the
  ontology" from Protégé's current selection on every operation, which broke in both directions: an
  edit to any other loaded ontology - an ODK project has every import module loaded alongside the
  edit file - was published to the board and applied to every peer's edit file, and switching the
  active ontology while connected redirected peers' edits into whatever file was now in front of the
  user. A session is now bound at start; changes to another ontology are skipped silently, because
  there is nothing wrong with the axiom and counting it as unshareable would send the user hunting
  for a protocol limitation that does not exist.
- **Renaming a term used to delete its labels in every other language.** A label change said only
  "this term's label is now X", and applying it removed every `rdfs:label` on the term. Three labels
  on one term collapsed to one at the receiving peer. The operation now carries the language tag and
  a peer replaces only that language; an operation with no tag is taken as the untagged label, which
  is what an older peer means by it, so the two versions interoperate without loss.
- **Live mode cannot carry every axiom, and the number is measured.** Offering every axiom in pizza
  v5 to a live session, 96 of 141 travel and 45 do not. `SubClassOf`, class and individual
  declarations, `rdfs:label` including renames, `ClassAssertion` and every annotation cross.
  `EquivalentClasses`, `DisjointClasses`, domain, range, property chains, `owl:hasKey`, property
  declarations and every `ObjectPropertyAssertion` do not, because the shared vocabulary has
  eighteen operation types fixed by the web client and an axiom with no operation cannot cross.

  The plugin reports this rather than hiding it - the toolbar shows `N changes not shared` with the
  reason in its tooltip. The practical consequence is worth saying plainly: live mode is for drawing
  a hierarchy, naming terms and leaving notes. It is not a way to build a release, because most of
  the logical content is in the second list. `reports/v5/collaboration.md` in the pizza project
  carries the per-axiom-type table.
- **Reason previews what the build will write, which it did not until 1.49.0.** The comparison
  above is against the options each call passes, and for `reason` that exposed something the option
  check could not: the plugin was passing ROBOT's bare defaults while the Makefile OntoBoard itself
  generates passes `--equivalent-classes-allowed asserted-only --exclude-tautologies structural`.

  Both calls were running ROBOT correctly. They were asking different questions. On pizza v2 the
  preview showed seven new axioms where `make reason` writes two, the other five being
  `SubClassOf owl:Thing` tautologies that the build drops and that were never going to reach a
  release. A curator reviewing inferences before cutting one was reading five lines of noise and
  might reasonably have concluded the ontology said more than it does.

  `RobotTransform.reasonOptions()` now holds those two options, `OdkScaffold` builds the Makefile's
  flags from the same constants, and the parity test runs the command line with them - so the
  preview, the generated build and the test cannot drift apart without one of them failing.

  Worth noting what this says about the older tests: not one of them failed when this changed. The
  tautologies were in the shipped output for twenty-four versions and nothing had ever pinned them
  either way.
- **The plugin's OWL 2 DL check and `robot validate-profile` disagree, on purpose.** The OWL API
  adds a missing annotation-property declaration while parsing. So an ontology that is outside DL
  for exactly that reason — a curator typed a property name into Protégé and never declared it — is
  inside DL again by the time it has been written and read back. `robot validate-profile` only ever
  sees the file and reports it in profile; the plugin checks the ontology being edited and reports
  the violation.

  The plugin is the more useful of the two here, because the point of a profile check in an editor
  is to catch this while it can still be fixed by hand. But a curator who runs both and gets two
  answers deserves to know which is which, so `RobotCliParityTest` pins the disagreement rather than
  leaving it as a surprise — and so nobody later makes the plugin agree with the command line by
  deleting the check that does the work.
- **The Rio barrier applies to both hosts, and it is an OSGi barrier rather than a version
  one.** This was described here for five versions as a 5.5.0 problem, and that was wrong.

  The version story is true as far as it goes: OWL API moved its RDF layer from Sesame to
  RDF4J at 4.5.25, `robot-core` targets the newer form, so `RioRenderer`'s constructor takes
  an `org.eclipse.rdf4j.rio.RDFHandler` on 4.5.29 and an `org.openrdf.rio.RDFHandler` on
  4.5.9. But inside a bundle that difference never gets a chance to matter. **Neither
  `owlapi-osgidistribution` exports any `org.openrdf.*` or `org.eclipse.rdf4j.*` package** —
  4.5.9 keeps 16 such jars and 4.5.29 keeps 17 on their own private `Bundle-ClassPath`, and
  the `Export-Package` header of each lists 75 and 82 packages with not one of them among
  these — and OntoBoard embeds no rdf4j jar either. So an rdf4j type is simply invisible to
  our bundle, on 5.6.9 exactly as on 5.5.0, and a Rio path fails with
  `NoClassDefFoundError` before any signature is compared.

  Two consequences worth stating plainly. `OntologyDataset` is not a 5.5.0 workaround; it is
  the only way this plugin can get a Jena model at all, on either host. And a robot-core
  operation whose *static initialiser* touches rdf4j — `ExpandOperation` reads
  `org.eclipse.rdf4j.model.vocabulary.DCTERMS.SOURCE` there — cannot be wrapped at all,
  because merely loading the class throws.

  Nothing the plugin ships touches Rio, and the report no longer does, which is why it passes
  on 4.5.9. `export` was also listed as blocked by this and never was: its constant pool
  contains no match for `rdf4j`, `openrdf` or `rio`. And `QueryOperation` cannot be called at
  all - not one method. Its constant pool carries
  `RioRenderer.<init>(OWLOntology, org.eclipse.rdf4j.rio.RDFHandler, ...)`, so resolving the class
  throws `NoClassDefFoundError` before any method body runs. That was established by calling
  `execQuery`, after a per-method disassembly wrongly suggested only the loaders were affected.
  `SparqlQuery` therefore executes with Jena directly, the way `RuleRunner` does.
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

- **The canvas lost work in two ways until 1.52.0, and neither could be caught by a test.**
  `capturePositions` walked entity nodes only, so moving a sticky note or resizing a frame was
  discarded on the next refresh - which any edit anywhere in Protégé triggers. And the toolbar's
  `Arrange` never saved what it produced, while the two other callers of the same layout code did, so
  a hierarchical arrangement of thirty classes reverted the moment anything else changed.

  Both were unreachable from a test: the capture was an inline loop inside a 2,114-line view over a
  live `mxGraph`. It is a static function over a geometry lookup now, and the two tests that were
  impossible to write before are in `SchemaCanvasViewTest`. The layouts also leave notes and frames
  alone - a frame slotted into a grid encloses nothing, which destroys the only thing it is for.

  What remains, and is named in [the canvas plan](superpowers/specs/2026-09-25-canvas-interface-plan.md):
  no undo for anything the board owns, four coordinate-conversion bugs that put created things where
  the user did not click, no search, and tooltips that show internal ids.

### Not covered by tests

1174 tests cover projection, axiom construction, layout persistence, ODK scaffolding, the
report's rule execution and the OSGi configuration. Fourteen of them reach outside the JVM:
`RobotParityTest` compares the report against real ROBOT in `obolibrary/odkfull`,
`RobotCliParityTest` compares nine more operations against the same image's `robot` command, and
`OdkBuildTest` runs a freshly scaffolded project's own `make test` and `make prepare_release` in
that image. All are *skipped*, not failed, where Docker is absent — so a green run on a machine
without Docker proves considerably less than it looks, and now says nothing at all about whether
this plugin agrees with ROBOT.

`OdkBuildTest` earns its keep: three defects in the generated build had survived a 30-assertion
test class and were found the first time anyone executed it — a `--` inside an XML comment in the
catalog, which made `make` die on the first robot call; no robot invocation passing the catalog at
all; and a release gate weaker than the CI gate. Asserting what a generated file *says* is not the
same as running it. They do **not** cover any Swing UI:
dialogs, toolbar, minimap, drop handling, and every visual choice need a display and are
verified by hand. Treat visual behaviour as unverified after each change.

Behaviour *inside the bundle* is a third category, and the one that has cost the most. No test
can see it, because Maven's classpath is not Felix's. `OntoBoardStartup` now runs `SelfCheck`
at startup and `tools/smoke.ps1` asserts the verdict, so seven things are checked in the host on
every release: robot-core's profile is readable, all 32 of its queries are readable, Jena can
read what the OWL API writes, the report produces findings end to end, the export produces terms
end to end, the explanation of a deliberately unsatisfiable class produces a justification, and
every class `plugin.xml` names loads *and constructs*.

That last one is half of what the plan calls Phase 2. `PluginXmlTest` already calls
`Class.forName` on each of those classes — on Maven's classpath, where everything resolves. Felix
is a different class space, and a menu action referencing a Protégé type the bundle never imported
passes that test and dies on click, invisibly, because an action that fails to load simply does
nothing. All 29 are now loaded *and constructed* in the host.

The other half arrived in 1.40.0 and grew in 1.44.0. `tools/smoke.ps1 -SelfTest` sets
`-Dontoboard.selftest=true`, and the startup hook then drives **nine** menu items against a
throwaway ODK project — scaffolded by the wizard's own code into a temp directory and deleted
afterwards — logging what each reported: Measure, Quality report, Explain, SPARQL, Export terms,
Profile, Imports, Refresh imports, All notes. A person can run the same thing from *OntoBoard →
Run self-test*; both go through one implementation, because a self-test with two implementations
grows a version nobody runs.

A real project rather than an ontology in memory, because half the menu is project-aware — it
looks for `src/ontology`, a catalog, `imports/`. What unlocked that was removing seven private
copies of `fileOf(OWLOntology)`, each of which asked the *model manager's* manager for the document
IRI and so answered only for the ontology Protégé has open.

It earned itself immediately: on its first run it found `QualityReportAction` throwing
`NullPointerException`, because its `options` field had no initial value and `run()` therefore
worked only after `configure()`. From the menu `configure()` always runs, so no user could reach
it — a latent trap rather than a live bug — but it was invisible to 1133 unit tests and to anyone
clicking the item.

**Nine of roughly twenty items, and the rest are excluded for one stated reason each.**
*Transform* defaults to applying its changes and would push them through the live session's model
manager. *Rename IRIs*, *Import terms*, *Obsolete*, *Release*, *Build*, *Git*, *Open from GitHub*
and *New ODK project* write files, run `make`, or reach the network. *Compare releases* needs a
project with dated releases, which a freshly scaffolded one has none of. The canvas, note-editing
and collaboration items are Swing surfaces and nothing here opens a window.

Those could now be covered — the scratch project makes it possible — but each needs its own
fixture: a release history, a git remote, an upstream to import from. Inventing those badly would
be worse than the gap. So this still does not "drive the menu", and says so in its own output.

The export check is there for a specific reason. Its safety rested on a chain of inferences — POI
is reached only by `Table.asWorkbook`, which `write` calls only for `xlsx`, which the plugin does
not offer — and a chain of inferences about a classloader is exactly what was wrong about the
report for nine versions.

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
2. **Pattern library and Widoco.**
3. **Entity locking** — once live collaboration has been used in anger.
4. **Collaboration vocabulary** — the nine axiom kinds the live protocol cannot carry.

Done, with the release that did it: live collaboration and cursors (1.9.0); the entity-view
column and tab layout (1.8.0); ROBOT transforms, term import and the quality report
(1.10.0–1.18.0); git tooling, the ODK build runner, ID range editing, provenance stamping,
editorial notes, frames and sticky notes, obsoletion, OWL 2 profile checking, release
comparison and ROBOT templates (1.19.0); properties and inferred individual types on the
canvas (1.20.0); ROBOT's quality report actually running inside the bundle, on both hosts,
checked against real ROBOT and against a startup self-check in the host (1.25.0); ROBOT term
export as TSV, CSV, JSON, YAML or HTML, with the same in-host check (1.26.0); explanations for
unsatisfiable classes and inconsistency, with ROBOT's axiom-impact summary (1.31.0); SPARQL,
including the project's own committed checks (1.33.0); ROBOT's axiom-level diff beside the
term-level one, and a startup check that every class plugin.xml names really loads under Felix
(1.34.0); ROBOT's materialize, which asserts inferred relations rather than inferred subclass
axioms, bulk IRI renaming for moving a namespace, and SPARQL results written as TSV the way ODK's
`custom_reports` does, and committed term lists so an import module can be rebuilt from the
repository rather than kept as a blob, a mirror so a rebuild works offline, a diff against the published release, and re-rendering an
existing project's generated files so a generator fix reaches projects that already exist
(1.35.0-1.45.0); an OWL 2 DL profile gate in the generated build, and a collaboration fix so an
incoming annotation declares its property rather than pushing the ontology outside DL (1.46.0);
nine operations compared against the real `robot` command rather than only the report, and the four
defects that comparison found (1.48.0); a Reason preview that passes the same options as the
generated build, so it shows what a release will contain (1.49.0); live collaboration verified over a
real socket for the first time, and the three defects that had made it inert in every release
(1.50.0); a generated ODK
build that honours its own catalog, rejects equivalences nobody asserted, and gates a release on
the same checks CI runs (1.28.0).

Those middle releases all shipped under one unchanged version number, which is why the list
above names versions at all — see the note in [development](development.md) about what that
cost.

Specs and plans live in [superpowers/](superpowers/), including the reasoning behind
decisions that were reversed — the plugin was briefly intended to replace the web
application, and does not.
