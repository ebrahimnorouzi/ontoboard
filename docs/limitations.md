# Limitations and Roadmap

Honest inventory of what each OntoBoard client does not do. Kept specific rather than
reassuring, because a quality tool that overstates itself is worse than one that admits
gaps.

## Protégé plugin

### Not implemented

What the plugin does not do today. The pattern library (1.82.0, extensible since 1.92.0) and the
SPARQL panel (1.33.0) used to be on this list and are now features - they are described in
[working with ODK](odk-workflow.md) and the [pattern library](patterns/index.md).

- **Live collaboration** — built as of 1.9.0, but only for the seventeen operation types
  the web application understands. Equivalence, disjointness, property characteristics,
  chains, `owl:hasKey`, negative assertions, global domain and range, datatype definitions
  and imports do **not** travel; the plugin counts them and says so in the status bar rather
  than dropping them silently. Use git for work on those. See
  [collaboration](collaboration.md).
- **Threaded comments and discussion** — a term can be linked to its issue with
  *Notes → Discussion link…*, which writes `IAO:0000233 term tracker item`, and editorial
  notes (`IAO:0000116`/`IAO:0000232`) carry an author and date. But the argument itself
  lives in the tracker: there is no reply thread inside the ontology, deliberately, because
  an unbounded mutable conversation would appear in every release and every diff.
- **Pull requests** — *Git…* does status, stage, commit, pull, push and branch, and
  *Open from GitHub…* clones. Opening or reviewing a PR still means leaving Protégé.
- **Widoco HTML documentation.**
- **Entity locking** — nothing stops two live collaborators editing the same term at once.

### Known constraints

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

### What used to be here

Thirty entries describing defects that were found and fixed - the canvas losing work, live
collaboration being inert for forty releases, arrows that were unread rather than missing -
have moved to [what was fixed](fixed.md). They are kept because how a defect was found is
worth more than the fact it is gone, but they are not limitations, and a page called
Limitations should answer what the tool cannot do today.

### Not covered by tests

1207 tests cover projection, axiom construction, layout persistence, ODK scaffolding, the
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
project with dated releases, which a freshly scaffolded one has none of. The note-editing and
collaboration items are Swing surfaces and nothing here opens a window.

**The canvas is the exception, as of 1.74.0, and the story of how is worth the paragraph.** The
tab check was added in 1.44.0 with a comment saying it was "the first check that the canvas can
be built at all under Felix". It was not. It added the tab, removed it, and returned "opened, its
views constructed" without asking anything — and Protege builds a view's content lazily, when the
view is shown, so adding and removing a tab builds *nothing*. Two things were wrong at once: the
check verified nothing, and there was nothing to verify because the work never happened. Both were
invisible, because Protege's `View.createContent` catches whatever `initialise()` throws and puts
an error label in the view's place, so a canvas that crashed on every open produced exactly the
same PASS as one that worked. 1.73.0 shipped with the canvas crashing on every open and a receipt
recording PASS 10/10.

It is real now. `ViewHealth` is a register the views write to themselves before Protege can
swallow anything; the self-test clears it, opens the tab, selects it, and — if nothing reports in
— calls `View.createUI` on each view directly, the same method Protege's own hierarchy listener
calls. The receipt names what was built. The first run that did this found a second crash, older
than 1.73.0 and present in every release the view has existed in: with no ontology open, the view
threw on construction and was lost for the session.

This is still not a check that the canvas *draws* anything. Nothing here looks at a pixel. It is a
check that the largest class in the plugin can be constructed inside Felix, which is the failure
class that had been shipping.

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
2. **Widoco** — generated documentation. The pattern library that used to share this line
   shipped in 1.82.0 and became extensible in 1.92.0.
3. **Entity locking** — once live collaboration has been used in anger.
4. **Collaboration vocabulary** — the nine axiom kinds the live protocol cannot carry.
5. **`src/metadata`** — the OBO Foundry registry entry: `<id>.yml` holding the PURL
   configuration and `<id>.md` holding the description. Neither is read nor written today, and
   it is last on this list because it only matters when submitting to the Foundry — three of the
   four real projects measured for 1.99.0 have one, and the fourth is not an OBO ontology.
   The other three `src/` directories are all covered; see
   [the workspace](odk-workflow.md#2-the-workspace).

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

Specs and plans live in [superpowers/](https://github.com/ebrahimnorouzi/ontoboard/tree/main/docs/superpowers/), including the reasoning behind
decisions that were reversed — the plugin was briefly intended to replace the web
application, and does not.
