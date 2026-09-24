<!-- Provenance: produced by a four-angle design panel (odk-first, robot-first,
verification-first, user-journey), scored by three independent judges per plan, and synthesised
from the winner. Final scores: verification-first 218, odk-first 217, user-journey 196,
robot-first 194 - one point between the top two, which is not a mandate for either.

Independently re-verified by hand before committing: the two Protege installs and their OWL API
versions; ontoboard-1.19.0.jar present in both plugins/ directories; 21 load events across 16
OntoBoard versions in ~/.Protege/logs/protege.log; the "Cannot access report query files"
exception at log:399033; ReportOperation.getDefaultQueryStrings using
ReportOperation.class.getClassLoader().getResource and comparing URL.getProtocol() to "file";
ExportOperation having zero references to rdf4j, openrdf or rio. Anything the panel marked
UNVERIFIED is still unverified.

Test-count note: the panel counts 1,063 @Test methods; surefire reports 1,062 run and 1 skipped.
Both are right. -->

# Correction that this plan rests on

Every status report in the session that produced this document said that no Protege had ever
loaded this plugin and that there was no Protege on the development machine. **Both were false.**
Two installs sit in `~/Documents` - 5.5.0 and 5.6.9 - each with `ontoboard-1.19.0.jar` in its
`plugins/` directory, and the log records 21 load events across 16 versions from 1.0.1 to 1.19.0.
The tab opened; the canvas ran against a real ontology. The error came from checking
`C:\Program Files` and the Desktop, concluding "none", and then repeating that as established
fact instead of as an inference.

It matters because it inverts the headline risk. The accurate statement is not "never verified in
a host". It is: **the host was the oracle up to 1.19.0, and versions 1.20.0 through 1.24.0 - the
five releases carrying the canvas fixes and all four audit rounds - were written blind.**

And the log contains a defect that 1,062 tests and four audit rounds did not find: `robot report`
fails on *both* hosts because ROBOT looks up its query directory by URL protocol and an OSGi
bundle resource is neither `file:` nor `jar:`. `docs/limitations.md` blamed the OWL API for it and
promised 5.6.x had "the full surface"; it does not. In the other direction, `export` works today
and was listed as blocked in error. Both documents are now corrected.

---

# OntoBoard: the next phase

*Written 2026-09-24 against commit `28038e7` (`feat/canvas-interaction`), `protege-plugin/pom.xml` version **1.24.0**. Every number below was re-derived from the working tree, the two Protégé installs on this machine, `~/.Protege/logs/protege.log`, and `javap` over `robot-core-1.9.8`. Where a claim could not be checked here it is marked **UNVERIFIED**.*

---

## 1. Where things actually stand

### The premise the last four plans were built on is false

The brief says *"NO PROTEGE HAS EVER LOADED THIS JAR… There is no Protege on the development machine."* `TESTING.md` says the same thing in its closing section. Both are wrong, and the error is load-bearing, because it retires the only oracle that has ever found a defect the test suite could not.

| Evidence | Value |
|---|---|
| `C:\Users\eno\Documents\Protege-5.5.0` | OWL API **4.5.9**, bundled JRE **1.8.0_121** |
| `C:\Users\eno\Documents\Protege-5.6.9` | OWL API **4.5.29**, bundled JRE **Temurin 11.0.25** |
| Both `plugins/` dirs | `ontoboard-1.19.0.jar`, 64,816,768 bytes, 2026-08-31 09:47 — same build in both |
| `~/.Protege/logs/protege.log` | 67,785,550 bytes; **16 distinct OntoBoard versions** logged, 1.0.1 → 1.19.0 |
| log:399435, log:400014 | `Plugin: OntoBoard (1.19.0)` at 2026-08-31 10:14:08 and 10:23:56 |
| log:400093/400125/400134 | `Saved tab state for 'OntoBoard' tab` — the tab opened and closed |
| log:398401, log:398616 | `SchemaCanvasView  OntoBoard: dropped N canvas entries for entities no longer in the ontology` — the canvas ran against a real ontology |

What has **never** been loaded is **1.20.0, 1.21.0, 1.22.0, 1.23.0, 1.24.0** — five releases containing canvas properties, ODK identifier minting, provenance stamping, the top-level menu, and *all* of the audit fixes (rounds 1–4: 16 + 18 + 5 + round 4, commits `8834ce7`, `3d637f2`, `c3ce7cf`, `28038e7`). So the gap is not "build a host loop". It is: **the host stopped being the oracle five versions ago, and everything since was written blind.**

### The host already found a defect four audit rounds and 1,063 tests did not

`protege.log:399033-399079`, 2026-08-28 22:37:53, `AWT-EventQueue-0`, under OntoBoard 1.15.0, stack frames tagged `[na:1.8.0_121]` → the **5.5.0** install, ontology `mmo-edit.owl`:

```
de.fizkarlsruhe.ise.ontoboard.robot.QualityReport$QualityReportException:
  ROBOT could not produce a quality report: Cannot access report query files.
  at QualityReport.java:63  <- QualityReportAction.java:27  <- OntoBoardAction.java:68
Caused by: java.io.IOException: Cannot access report query files.
  at org.obolibrary.robot.ReportOperation.getDefaultQueryStrings(ReportOperation.java:790)
  at org.obolibrary.robot.ReportOperation.getReportQueries(ReportOperation.java:636)
  at org.obolibrary.robot.ReportOperation.getReport(ReportOperation.java:265)
```

Mechanism, confirmed from `robot-core-1.9.8` bytecode: `getDefaultQueryStrings` does `ClassLoader.getResource("report_queries")`, then compares `URL.getProtocol()` against constant `"file"` and then `"jar"`; anything else falls through to `throw new IOException("Cannot access report query files.")`. Under Felix the bundle's own resource URLs are neither — `report_queries/` lives inside `robot-core-1.9.8.jar`, one of **103 embedded jars** on a **518-entry `Bundle-ClassPath`**. The trap is bounded: of the 16 relevant operation classes, `ReportOperation` is the only one that enumerates a resource directory.

### Two barriers, not one — and the docs name the wrong one

`docs/limitations.md:35` blames the OWL API Rio/RDF4J split for `report`, `query` **and** `export`, and promises 5.6.x has "the full surface". Measured:

| Operation | Barrier A: bundle-protocol resource | Barrier B: Rio signature | Works on 5.5.0? | Works on 5.6.9? |
|---|---|---|---|---|
| `report` | **yes** (both hosts) | yes, via `QueryOperation.loadOntologyAsDataset` | no | **no** — and the docs say yes |
| `query` | no | **yes** | no | yes |
| `export` | no | **no reference at all** | **yes, today** | yes |

Barrier B verified precisely: `QueryOperation.loadOntologyAsModel` is the single Rio call site, constructing `owlapi.rio.RioRenderer(…, org.eclipse.rdf4j.rio.RDFHandler, …, org.eclipse.rdf4j.model.Resource[])`. The host's own `RioRenderer` constructor takes `org.openrdf.rio.RDFHandler` on 5.5.0 and `org.eclipse.rdf4j.rio.RDFHandler` on 5.6.9. `ExportOperation`'s constant pool contains zero matches for `rdf4j`, `openrdf`, `rio/`, `jena`, or `poi`. **`export` is a working capability that a sentence of prose removed from the roadmap.**

### Everything else, with numbers

- **Code**: 103 main source files, 23,539 LOC. 25 of them touch `javax.swing`/`java.awt`. Largest: `SchemaCanvasView.java` at **2,114 lines**, exercised by exactly one test class that constructs no view (`SchemaCanvasViewTest` drives the static reset core only, and says so in its own javadoc).
- **Tests**: 72 test classes, **1,063 `@Test` methods**. `docs/limitations.md` says 1062. Every one runs Protégé-free logic.
- **CI**: none. `find` turns up no `.github/` anywhere in the ontoboard repo — only inside `data/admin/mwo` and `node_modules`. 1,063 tests run on one laptop by hand.
- **Menu surface**: 27 extensions in `plugin.xml` — 1 `WorkspaceTab`, 1 `ViewComponent`, 25 `EditorKitMenuAction` of which 4 are group headers. **21 invocable items.** `MenuStructureTest` proves the paths parse; nothing proves an item is reachable or that its body links.
- **ROBOT coverage**: `robot-core-1.9.8` ships 21 `*Operation` classes (verified by listing). Main code references 10: Extract, Merge, Mireot, Reason, Reduce, Relax, Repair, Report, Template, plus metrics for Measure.
- **ODK fidelity**: `OdkScaffold` writes a Makefile with **7 targets** (`all test reason report sparql_test clean prepare_release`) as a Java string literal, never regenerated. The `<id>-odk.yaml` it writes carries **9 keys**; `OdkProjectLoader.titleFor` reads back exactly **one** (`title:`) and its javadoc says so deliberately. `import_group`, `release_artefacts`, `export_formats`, `robot_report` are decorative. *(ODK's own template declaring 56 targets: **UNVERIFIED** here — no `odkfull` checkout on this machine.)*
- **Pizza** (`c:/tmp/ontoboard-e2e/pizza`): 8 commits, 7 tags (`v1 v2 v3 v3.1 v4 v5 v6`), but only **2 dated release artefacts** (`releases/2026-09-23`, `releases/2026-09-24`) and `reports/v1`–`v4` (v5/v6 regenerated in place). `make test` passes in the real `obolibrary/odkfull` container — with **ROBOT 1.9.10**, while `.github/workflows/qc.yml` installs **1.9.8** and the bundle embeds **robot-core 1.9.8**. Three-way version drift, undeclared. `src/ontology/imports/food_import.owl` exists with no committed term list, so nothing — including the plugin that made it — can rebuild it.
- **Install hazards, live**: both `plugins/` dirs hold `ontoboard-1.19.0.jar`, and `Bundle-SymbolicName: ontoboard;singleton:=true` means two versions is an unresolved collision, not an upgrade. `5.5.0/plugins` already demonstrates the failure four times over: cellfie ×2, comodide/CoModIDE ×2, shacl4protege ×2, swrltab ×2.
- **Heap, corrected**: `INSTALL.md` says `-Xmx500M` is too low for what already ships. Both installs' `Protege.l4j.ini` do say `-Xmx500M`, and 5.6.9's `conf/jvm.conf` has `max_heap_size` commented out — so **`Protege.exe` users get 500M**. But both `run.bat` files pass `-Xmx32G`. A smoke script built on `run.bat` therefore does *not* reproduce the user's memory conditions.
- **Not ours**: `Protege-5.5.0/hs_err_pid4180.log` is an `awt.dll` access violation from 2025-01-30, months before OntoBoard's first load. Do not read it as our crash.

### What is genuinely unverified

Whether `report` fails on 5.6.9 (predicted from mechanism, never run there). Whether any of 1.20.0–1.24.0 resolves at all under Felix. Whether all 21 menu items render. Whether `ParameterDialog`, `ResultDialog`, `BackgroundRun` work. Whether the canvas paints. Whether live collaboration connects from inside a host. ODK's real target count.

---

## 2. What "fully functional" means, as conditions a machine checks

| # | Condition |
|---|---|
| **F1** | One command, on both hosts, exits 0 having resolved **exactly one** `ontoboard` bundle **at the version in `pom.xml`**, started it, opened the tab, and shut down — and names the failing log line when it does not. |
| **F2** | All 21 invocable menu items have executed inside a host, per host, with the outcome recorded as a committed machine-readable artefact, not a memory. |
| **F3** | No shipped action throws `LinkageError`, `NoClassDefFoundError`, `UnsupportedClassVersionError` or an unhandled `IOException` on either supported host — **or** the limitation names the exact class and method in `docs/limitations.md` and a test pins it. |
| **F4** | Every "does not work" sentence in `docs/limitations.md` is either reproduced by a test or deleted. Zero tests remain that pass whether an operation returns rows or throws. |
| **F5** | `Robot > Quality report` on pizza produces the same violation set (rule, subject, severity) as `obolibrary/odkfull`'s `make report` over the same ontology and `profile.txt`. |
| **F6** | Scaffold a project, re-run the plugin's regenerator over it, `git status --porcelain` is empty. Run it over committed pizza: still empty. |
| **F7** | `make all_imports` in a plugin-scaffolded project rebuilds every module from committed inputs against a pinned mirror, offline, byte-identically. |
| **F8** | A version bump cannot land without a smoke receipt for that exact version — enforced by `.githooks/pre-commit`, which already gates version bumps and is already installed (`core.hooksPath=.githooks`). |
| **F9** | Every ROBOT operation the generated Makefile invokes is reachable from a menu item, or is named in `limitations.md` as deliberately CLI-only. One embedded ROBOT version, declared once, used by bundle, scaffold and CI. |

Sizes below: **S** ≤ 2 days, **M** ≈ 1 week, **L** 2–4 weeks.

---

## 3. The phases, ranked

### Phase 0 — Host in the loop, scripted · **M** · satisfies F1, F2, F8

**Goal.** Turn "it loads" from a memory into a red/green command, get 1.24.0 into both hosts, and come out with a written defect list. This phase writes no features.

1. **Delete** `ontoboard-1.19.0.jar` from **both** `plugins/` dirs before copying 1.24.0 in. `singleton:=true` plus two jars is an unresolved collision; the same directory already shows it four times.
2. Write `protege-plugin/tools/smoke.ps1` around the launch line that already exists in `run.bat`: `jre\bin\java <opts> -Dorg.protege.plugin.dir=plugins -classpath bundles/...;bin/protege-launcher.jar org.protege.osgi.framework.Launcher <ontology>`. Note the classpath differs between installs (5.6.9 adds `glassfish-corba-orb.jar` and puts the launcher under `bundles/`) — read it from each `run.bat` rather than hardcoding.
3. **Run at the user's heap, not `run.bat`'s.** `run.bat` hands out `-Xmx32G`; real users launching `Protege.exe` get `-Xmx500M`. Smoke twice: once at `-Xmx500M` to reproduce the shipped default, once at `-Xmx2G` (what `INSTALL.md` tells people to set). An `OutOfMemoryError` that only appears at 500M is a defect our users hit and we never would.
4. Record `protege.log`'s byte offset before launch (it is 67 MB — `tail -c +$offset`, never grep the whole file), launch, wait, kill, assert only on the new slice.
5. **Assert present**: `Starting bundle ontoboard`; `Plugin: OntoBoard (1.24.0)` — Protégé logs `Bundle-Version`, so this is the proof of *which* build ran; `Saved tab state for 'OntoBoard' tab`.
6. **Assert absent**: `unresolved constraint`, `Importing java.* packages not allowed`, `UnsupportedClassVersionError`, `NoClassDefFoundError`, and any `de.fizkarlsruhe` stack frame.
7. Run against **both** hosts. They are different machines in every way that matters: Java 8 vs Java 11 (so `_noee` and the Java-11 classfiles in logback/obographs only bite on 5.5.0), OWL API 4.5.9 vs 4.5.29. A pass on one is not a pass.
8. **By hand, once, on each host**: open `c:/tmp/ontoboard-e2e/pizza/src/ontology/pizza-edit.owl` and click all 21 items. Record for each: rendered / ran / result shown / threw-with-what. Expect throws. Confirm or refute the prediction that `report` fails on 5.6.9 too.
9. Add the repo's first `.github/workflows/build.yml`: `mvn -B test` on push. Also add a `tools/smoke-receipt/<version>.txt` convention and extend `.githooks/pre-commit` to refuse a version bump with no receipt (F8).

**Verification.** `smoke.ps1` exits 0 on both hosts and prints the resolved version, or names the line that failed. A committed table of 21 outcomes × 2 hosts.
**Pizza → v7.** `reports/v7/host.md` — the first pizza report whose provenance line reads *"a running Protégé 5.6.9 / 5.5.0"* instead of *"the plugin's logic classes"*, which is exactly the gap `TESTING.md`'s closing section admits. Delete that false closing paragraph in the same commit.

### Phase 1 — Fix what the host found; report first · **M** · satisfies F3, F4, F5

**Goal.** Ship no new surface while the flagship ROBOT action is known broken, and stop the docs misnaming the cause.

1. **Stop calling `ReportOperation.getReport`.** Enumerate rules from `report_profile.txt` — one flat resource in `robot-core-1.9.8.jar`, 32 `rule ⇥ severity` lines, readable as a *stream* under Felix (only URL-protocol directory enumeration fails). No hardcoded rule list, no directory listing. Then read `report_queries/<rule>.rq` per rule the same way. There are 33 `.rq` files; `multiple_asserted_superclasses` is the one not in the default profile, so drive from the profile and treat a missing `.rq` as a warning, not a crash.
2. Drive the **public** `ReportOperation.getViolations(IOHelper, Dataset, String ruleName, String query, Map options)` per rule. Confirmed public in 1.9.8.
3. **Build the `Dataset` ourselves.** `getReport`'s only robot-internal calls are `OptionsHelper.getOption`, `OptionsHelper.optionIsTrue` and `QueryOperation.loadOntologyAsDataset` — and that last one routes through `RioRenderer`. Write `OntologyDataset`: serialize the live `OWLOntology` to a byte stream with the OWL API, read it back with Jena's `RDFDataMgr`. `jena-arq-3.17.0`, `jena-core`, `jena-tdb` are already embedded in the bundle; no new dependency, no Rio. **This makes `report` work on 5.5.0 as well**, which `limitations.md` says is impossible. Phase 5 needs the same class, which is why it is built here.
4. Assemble severities from the project's own `profile.txt` exactly as `QualityReport.optionsFor` already does, so the plugin and the project's CI keep agreeing about what counts as a violation.
5. **Rewrite `docs/limitations.md:30-38`**: `report` fails on 5.5.0 **and** 5.6.9 for an OSGi reason; `query` alone is Rio-blocked, and only on 5.5.0; `export` has no Rio reference and works on 5.5.0 today.
6. **Sweep both directions** (the move worth stealing from the runner-up): grep `limitations.md` for every disclaimed capability and verify each against the bytecode; grep the suite for tests that accept either outcome — `QualityReportTest`'s `LinkageError` tolerance is one, and `QualityReport.java:107`'s exception message is that same prose copied into user-facing text — and replace each with the experiment that collapses it.
7. Declare `ROBOT_VERSION` once. Bundle embeds 1.9.8; `qc.yml` installs 1.9.8; the container run used 1.9.10. Pick one, put it in the config, have the scaffold and CI render from it.

**Verification.** The plugin's `report.tsv` and `odkfull`'s `report.tsv` over the same ontology and profile agree on every (rule, subject, level) triple. Zero either-outcome tests left in `src/test`.
**Pizza → v8.** `reports/v8/robot.md` carries both tables side by side plus the diff, and `src/ontology/report.tsv` is regenerated by the plugin, in a host, on both Protégés.

### Phase 2 — A self-test that runs inside the host · **L** · satisfies F2, F3 permanently

**Goal.** Automate what only the host can answer, so Phase 0's afternoon is never repeated by hand and "another careful reading of the same code" stops being the oracle.

1. A second, **never-shipped** bundle `ontoboard-selftest`, its own `Bundle-SymbolicName`. The shipped jar gains no test code.
2. Register on `org.protege.editor.core.application.EditorKitHook` — verified present in `protege-editor-core` 5.5.0's `plugin.xml`, alongside `OtherStartupActions` as fallback. Entry point `EditorKitHook.setup(EditorKit)`.
3. On `-Dontoboard.selftest=<dir>`: load a named ontology; for each `OntoBoardAction` subclass call `setEditorKit(kit)` then `actionPerformed(...)`; write one JUnit XML per action; `System.exit` with the aggregate.
4. **Exploit the seam that already exists.** `OntoBoardAction` splits `configure()` (EDT, may open a dialog) from `run(OWLOntology)` (background) and exposes `runsInBackground()`. Feed parameters in place of `ParameterDialog`, keep `OperationResult` in place of `ResultDialog`. No refactor of shipped code to start.
5. **Construct `SchemaCanvasView` for real** — 2,114 lines, the largest class in the plugin, today covered by static helpers only. Drive `switchToActiveOntology`, add and remove entities, assert the canvas model and the layout sidecar. The log already shows this class dropping canvas entries in anger; that behaviour should be asserted, not observed.
6. **Spend AssertJ-Swing on exactly three things and nothing else**: `ParameterDialog` round-trips a parameter set; `ResultDialog` renders 5,000 rows without hanging; `CollaborativeGraphComponent` paints a peer cursor.
7. Explicitly do **not** widget-test the other 22 Swing classes. 25 of 103 files touch Swing/AWT; a GUI robot over all of it is the most brittle assertion money can buy.

**Verification.** One JUnit XML per action per host; every action either passes or names its own failure, with no human watching. Wire it into the Phase 0 command so `smoke.ps1` and the selftest are one exit code.
**Pizza → v9.** `reports/v9/selftest/*.xml` committed, plus `reports/v9/host.md` regenerated from them rather than typed.

### Phase 3 — Make the configuration real and the generator regenerable · **M** · satisfies F6, F9

**Goal.** ODK's `update_repo`. Without it, every later capability reaches only *newly created* projects, and pizza would have to be regenerated from scratch to gain any of them. You cannot close a 49-target gap by appending to a Java string literal.

1. A minimal reader filling `OdkProjectConfig` from `<id>-odk.yaml`: `id`, `title`, `description`, `uribase`, `license`, `import_group.products`, `release_artefacts`, `primary_release`, `export_formats`, `robot_report.*`. A targeted line scan over a closed key set, **not** a YAML library — a new dependency in a bundle with 103 embedded jars is not free, and `OdkProjectLoader.titleFor`'s javadoc already argues for the scan.
2. Rewrite `OdkScaffold.generatedMakefile` from a literal into a renderer over that config. Same for the catalog, the CI workflow, and the directory set.
3. `Project > Update project files…`: re-render every generated file in place; **never** touch `<id>.Makefile`, `<id>-edit.owl`, `imports/*`, or anything hand-written. Show the diff before writing — this action can destroy work.
4. `src/metadata`, `src/scripts`, `src/patterns` either carry content or do not exist. An empty directory advertises an absent capability, same pathology as the decorative YAML.
5. Extend `MakeTargets.ordered`'s preferred list (`all test reason report prepare_release release all_imports refresh-imports update_repo clean` — already anticipating targets that do not exist yet) as each becomes real.

**Verification.** Round-trip property test: scaffold, read the YAML back, re-render, require byte-identity — a generator that is not a fixed point corrupts a project on its second run. **And the free one**: run the regenerator over committed pizza and assert `git status --porcelain` is empty. That single line proves the committed releases are what today's generator produces, and it is the precondition for ever letting a regenerator touch a user's real project.
**Pizza → v10.** `reports/v10/make-targets.md` shows the target count rising from 7, produced by re-running the regenerator over the existing project — not by regenerating it.

### Phase 4 — Imports that can be rebuilt · **M** · satisfies F7

**Goal.** Reproducibility is ODK's actual guarantee. Pizza is the counter-example: six tagged releases, CI green, and an import module nobody can regenerate.

1. Write the term list to a committed `src/ontology/imports/<src>_terms.txt` and treat the module as the **output of a rule**, never the artefact itself. Today `ImportTermsAction` (590 lines) takes terms from a dialog, writes `imports/food_import.owl` plus a catalog entry, and the input dies with the dialog. Inverting the direction is a text write next to an extraction the plugin already performs correctly.
2. Add `MirrorOperation.mirror(ontology, File, File)` and `writeCatalog` behind a `mirror-%` rule. The plugin already owns three-quarters of this: `Catalog.java` writes exactly the format `writeCatalog` produces, `ImportHealth.java` diagnoses unresolved imports, `TermExtract` wraps Extract and Mireot. What is missing is fetching and pinning. Not Rio-coupled.
3. `all_imports` iterating the committed term files against the pinned mirror.
4. Keep `ImportProvenance` stamping which upstream release each module was cut from — it already exists (commit `6799ba9`) and now has a pinned source to name.

**Verification.** Delete every `imports/*.owl`, go offline, `make all_imports`, and `git diff` is empty.
**Pizza → v11.** `src/ontology/imports/food_terms.txt` and `mirror/food.owl` committed; `reports/v11/imports.md` shows the offline rebuild producing a byte-identical module.

### Phase 5 — The ROBOT surface that earns its place · **L** · satisfies F9

Ordered by value per line, each with the reason it is worth building *here* rather than at the CLI.

1. **`export`** — highest value per line, and the docs are wrong about it. `ExportOperation.createExportTable` + `saveTable` to CSV/TSV/HTML/JSON. Reuse `ParameterDialog` for the column list and `ResultDialog` for the preview. **Suppress the XLSX option**: `log4j-api` is excluded from the bundle (bnd rejects a second `Bundle-Activator`), which leaves POI without a logging backend. Term tables are how an ontology gets reviewed by people who will not open Protégé.
2. **`materialize`** — nearly free. `materialize(ontology, reasonerFactory, Set<OWLObjectProperty>, options)` returns void and mutates in place, exactly the shape `RobotTransform` already wraps, so it drops in as a sixth `Kind` beside relax/reason/reduce/repair/merge and inherits the throwaway-copy-then-`OWLOntologyChange` pattern that gives it correct Protégé events and one undo. Needs ELK (embedded, `elk-*-0.6.0.jar`), slow, so it runs on the existing `BackgroundRun`. Unlocks the `simple` release artefact.
3. **`diff`** — as a **second tab inside the existing Compare Releases dialog**, not a new menu item, plus markdown output for pasting into a PR. `ReleaseDiff.java` (454 lines) already answers the human question — added, obsoleted, removed, relabelled, redefined, moved — and its javadoc correctly argues an axiom list does not. But it is blind to a rewritten logical definition, a new disjointness, an altered cardinality: precisely what a reviewer must see.
4. **`rename`** — last, and only with a dry run. `renameFull` / `renamePrefixes` rewriting thousands of references is irreversible at the CLI; routed through `RobotTransform`'s copy-and-diff it comes back as **one undoable change set**. This is the case where the plugin is genuinely better than `robot`, which is the only reason to build it.

**Verification.** Each operation runs inside the Phase 2 selftest against pizza, on both hosts, with its result asserted — not merely "did not throw". `rename` additionally asserts the change set is a single undo.
**Pizza → v12.** `exports/pizza-terms.tsv`; a `simple` release artefact produced by `materialize`; `reports/v12/release-notes.md` carrying an axiom-level section under the existing term-level one.

### Phase 6 — The SPARQL panel · **S** · closes the most embarrassing gap

The scaffold has written `src/sparql/check_labels.rq` into every project since the beginning and nothing in the plugin could run it; `make test` in the container runs it via `robot verify`. This is the wrong way round, and after Phase 1 it is nearly free: `OntologyDataset` already exists, Jena ARQ is already embedded, and `QueryOperation.loadOntologyAsDataset` — the Rio-blocked path — is precisely the thing we no longer call. Run the project's own `.rq` files over our own Dataset, render through `ResultDialog`, and it works on 5.5.0 too.

**Verification.** `PASS Rule ../sparql/check_labels.rq: 0 violation(s)` from the plugin, matching `robot verify` in CI, asserted in the selftest.
**Pizza → v13.** `reports/v13/sparql.md` with both outputs.

---

## 4. What will NOT be built, and why

- **`python`.** `PythonOperation` drives an external Python process over a Py4J gateway. An OSGi bundle inside Protégé cannot own that lifecycle, and no OBO workflow needs it from a menu.
- **`explain`.** Both installs already carry `explanation-workbench-3.0.x`. Shipping a second, worse explanation UI inside the same application is a regression dressed as a feature.
- **`expand`, `filter`, `unmerge`.** `expand` (SWRL-to-OWL) is absent from the standard ODK build; `filter` overlaps `extract`, which is already wired, and a second subsetting dialog would only confuse which one to use; `unmerge` is meaningful only in a CLI pipeline with intermediate files on disk. Each gets one sentence in `limitations.md` naming it CLI-only, deliberately.
- **Widget tests for the other 22 Swing classes.** Three still-picture assertions, then stop. A GUI robot over the toolbar, minimap and drop handling costs more to maintain than the defects it would find.
- **The ODP pattern library, Widoco, pull requests, threaded comments, entity locking, and the 9 axiom kinds the collaboration protocol cannot carry.** All named in `limitations.md` today, all correctly named. None of them is the reason the flagship ROBOT action has been broken since at least 1.15.0. They stay on the roadmap, below everything above.
- **A YAML library.** Phase 3's reader is a line scan over a closed key set. 103 embedded jars is already the problem, not the solution.
- **All 56 ODK targets.** We render the targets the config declares and the plugin can actually run. A target that only a Docker container can execute belongs in the container, and the plugin should say so rather than emit a rule that fails.
- **Regenerating a user's `<id>.Makefile` or `<id>-edit.owl`, ever.** Phase 3's `Update project files…` touches generated files only, shows the diff first, and is the one action in this plan that could destroy work.

---

## 5. The single thing to do first

**Phase 0, item 1–7: delete the stale jars, write `smoke.ps1`, get 1.24.0 resolving on both hosts.**

The argument is not that host verification is generally good practice. It is that **this project already ran this experiment, and it worked.** The only defect-finding oracle OntoBoard has ever had that its 1,063 tests do not duplicate is a Protégé log, and that log contains a real, still-unfixed, flagship-breaking defect — `Cannot access report query files` — that four audit rounds and three plan drafts did not see. Then, at 1.20.0, the oracle was switched off, and five releases shipped without it, including every one of the audit fixes.

Against the alternatives:

- **Fix `report` first?** Tempting — the defect is diagnosed and the fix is specified. But we would ship 1.25.0 into a host that has never resolved 1.20.0–1.24.0, and if the bundle no longer resolves at all (the top-level menu added in 1.23.0 touched `plugin.xml`; `ViewConfigTest` and `PluginXmlTest` check it as *text*, Felix checks it as a *manifest*) then the fix reaches nobody and we will not know. Phase 0 costs days and removes that uncertainty for every phase after it.
- **Build the selftest bundle first?** It is the better long-run oracle, and it is Phase 2 for that reason. But it is an `L`, it depends on `EditorKitHook` behaving as documented, and writing a harness for a bundle that might not resolve is building the instrument before checking the telescope points at anything.
- **Start with ODK fidelity — YAML, `update_repo`, imports?** This is the largest capability gap and the most defensible long-term direction. It is Phase 3. Starting there means adding pipeline stages to a plugin whose existing ROBOT stage is known broken in the host, which is how a 7-of-56 target count becomes a 20-of-56 target count that still cannot produce a quality report.

Phase 0 also converts the largest unknown into a written record **cheaply**: no new code ships, nothing can regress, and its output — a table of 21 outcomes across two hosts — is the input every other phase needs to be sequenced honestly.

---

## 6. What no plan can settle

1. **Which host is *the* supported host.** Dropping 5.5.0 deletes the Rio problem, the `_noee` hack, the Java 8 classfile risk, and a whole column of `limitations.md`. It also drops every user on the version this plugin was built against and whose `plugins/` directory holds our jar today. This is a product decision, not an engineering one.
2. **Whether the plugin should ever rewrite a user's generated files.** Phase 3's `Update project files…` is the only way an existing project gains capability, and the only action in this plan that can destroy work. A diff-first dialog reduces the risk; it does not answer whether we should take it.
3. **Whether the version-bump gate becomes a host gate.** `.githooks/pre-commit` already refuses shipped-code changes without a version bump — written after the rule was broken three times by its own author. Extending it to refuse a bump without a smoke receipt would have prevented five blind releases. It would also block a one-line docs-adjacent fix on a day no Protégé is available. Whose call, and is `--no-verify` an acceptable escape?
4. **Whether pizza remains the proving ground.** It is committed, tagged, CI-green, and cheap. It is also 100 axioms of toy content, and the defect the host found surfaced on `mmo-edit.owl`, a real project. Promoting `mwo`/`mmo` to second proving ground costs setup and would have caught that failure in a test rather than a log.
5. **How much of `robot report` we are willing to reimplement.** Phase 1 stops calling `getReport` and drives `getViolations` per rule with our own query strings and our own `Dataset`. That is a supported public API, but it is ROBOT's *internals* re-plumbed, and a `robot-core` upgrade could break it where the CLI path would not. The alternative — a patch upstream to `ReportOperation` so it reads via `getResourceAsStream` — is the right fix for everyone and is not on our schedule.
6. **Whether the canvas sidecar-versus-OPLa-SD trade stays.** Both installs carry CoModIDE. Interoperating means annotations inside the ontology, which means ROBOT `report` and `diff` see layout noise. `limitations.md` calls the trade deliberate. It is, but it is also the one design decision in the plugin that forecloses working with the other schema-canvas tool in the same application.