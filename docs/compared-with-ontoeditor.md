<!--
  Written for: the OntoBoard maintainer, deciding what to build before submitting.

  Produced by reading the OntoEditor ESWC 2024 In-Use paper (the nearest published neighbour)
  against this repository, feature by feature. Every "OntoBoard already does X" below was
  checked in the code, not remembered.
-->

Grounded against the code. Findings follow.

---

# What OntoBoard should build to stand beside OntoEditor

Repository read at `C:/Users/eno/Documents/Git_repo/ebrahimnorouzi_github/ISE-FIZKarlsruhe/ontoboard` (v1.118.0, 378 Java files, 2,363 `@Test` methods).

---

## 1. What OntoEditor has that OntoBoard lacks

### (a) A textual RDF source editor with live syntax validation across three serialisations — BUILD A NARROW VERSION, 3–4 d

This is OntoEditor's actual core (CodeMirror 5 + N3.js / rdfxml-streaming-parser / jsonld-streaming-parser, errors as you type). OntoBoard has **no text surface anywhere**: `views/` is a canvas (`views/SchemaCanvasView.java`, 4,964 lines) and two tables (`views/SheetTable.java`, `views/SheetHeaderTable.java`). Protégé has no source view either, so this is a genuine hole.

Do **not** build a collaborative text editor. Build a read-only, round-trip-validated source view of the open ontology in a chosen serialisation.

- New `views/SourceView.java`, registered in `plugin.xml` beside `views/SheetsTab.java`; new `robot/SourceRoundTrip.java` to own render-then-reparse-and-report. `odk/ExportFormats.java` already holds the format enum and the `OWLDocumentFormat` for each.
- Add a `SelfCheck` entry (`SelfCheck.run()` at `SelfCheck.java:130`), because this is a bundle-visible path.
- **Hard constraint, verified:** JSON-LD is unreachable. `docs/limitations.md` records that neither `owlapi-osgidistribution` exports any `org.openrdf.*` or `org.eclipse.rdf4j.*` package; I confirmed against `~/.m2/.../owlapi-osgidistribution-4.5.9.jar`'s manifest. Any Rio path throws `NoClassDefFoundError` inside Felix. The honest table cell is **RDF/XML, Turtle, OWL/XML, Functional, Manchester, OBO-Graph JSON** — six formats, no JSON-LD. Still wider than OntoEditor's three. Claiming JSON-LD would be caught.

### (b) Character-level concurrent text co-editing (ShareDB + ot-text) — DO NOT BUILD

A category error for a plugin that does not own the text buffer. `docs/limitations.md` already states the plugin learns of a change after Protégé applied it. In Related Work, make this the **axis** rather than a concession: OntoEditor synchronises *text*, OntoBoard synchronises *axioms*. The table row is "unit of synchronisation", and OntoBoard wins it.

### (c) Chat — DO NOT BUILD, AND SAY WHY

Zero occurrences in the codebase; the only grep hit is a javadoc sentence at `collab/BoardIdTest.java:124`. The deliberate alternative already ships: `prov/EditorNotes.java` writes `IAO:0000116`, `menu/TrackerItemAction.java` writes `IAO:0000233`. `docs/limitations.md` has the reasoning — an unbounded mutable conversation would appear in every release and every diff. That is a stronger paper sentence than a chat panel.

If a reviewer insists: ephemeral session chat riding the existing `presence` envelope in `collab/bridge.mjs` is **1.5 d** (one value in `CollabMessages.Kind`, one panel in `collab/CollabDialog.java`). It buys a tick and nothing else.

### (d) Side-by-side diff and merge-conflict resolution (Mergely) — BUILD, 6–8 d, HIGHEST-VALUE FEATURE HERE

OntoBoard has the better half and no UI. `robot/AxiomDiff.java` and `odk/ReleaseDiff.java` give axiom-level comparison with typed `Change` kinds and `asReleaseNotes()`. What is missing is resolving a **git merge conflict** without leaving Protégé: `git/GitRepo.java` has `statusCommand/stageCommand/commitCommand/pullCommand/pushCommand` and `explain(...)`, but `GitRepo.parseStatus` parses only `changed` and `untracked` — there is no conflicted state anywhere.

This is the one row where "axiom-level, not line-level" stops being a claim and becomes a demonstration: a conflicted `.owl` in RDF/XML is **not resolvable by line**.

- New `git/MergeConflict.java` — detect `UU` from porcelain status, read both sides with `git show :2:<path>` / `:3:<path>` through `proc/ProcessRunner.java`.
- Compare with `odk/ReleaseDiff.between(before, after)`, unchanged.
- New `menu/ResolveConflictAction.java` + existing `menu/ResultDialog.java` for per-axiom pick; write with the OWL API, then `git add`.
- Extend `GitRepo.Status` with a `conflicted` list.

### (e) GitLab and Bitbucket — FIX THE HONESTY, 2–3 d

`git/GitRepo.java` is plain git and already works against any remote — clone, commit, push, pull on GitLab work today. What is GitHub-only is `git/PullRequest.java` (`PullRequest.GH = "gh"`), `git/GitHubUrl.java`, `menu/OpenFromGitHubAction.java`.

Add a `glab` branch to `PullRequest.createCommand/listCommand/currentCommand` and host detection in `GitHubUrl`. Skip Bitbucket. This matters beyond the table: MatWerk's own pipeline is GitLab, so it is an adopter need.

### (f) One shareable link to join a session — BUILD, 2 d

OntoEditor generates a project link. OntoBoard makes you paste a bridge URL plus a JWT minted by hand with `collab/mint-token.mjs`. `collab/BoardId.forOntology()` already removed the board-name step by deriving it from the ontology IRI.

- Parse an `ontoboard://join?bridge=…&token=…` blob in `collab/CollabSettings.java`; one paste field in `collab/CollabDialog.java`; emit the blob from `collab/mint-token.mjs` instead of a bare JWT. `collab/CollabSettingsStore.java` already persists.
- **This is a user-study blocker.** If task 1 is "join a session", today task 1 is "wait for a colleague to email you a JWT".

### (g) Browser access / zero install / published Docker image — BUILD ONLY THE COMPOSE FILE, 0.5 d

The web app was deliberately retired (tag `web-app-final`, see `docs/feature-parity.md`). Do not rebuild it. Do ship `collab/Dockerfile` + `collab/compose.yml` for `collab/server.mjs` — `ahemid/ontoeditor` is the whole of OntoEditor's reproducibility story and a signed jar plus a compose file beats it.

### (h) Repository browsing before cloning — SKIP

`git/GitClone.java` clones a URL. Minor.

### (i) Not a feature, and the cheapest reviewer credit in this list — 1 d

OntoEditor has a w3id PURL and nothing else: no DOI, no Zenodo archive, no Resource Availability Statement. OntoBoard has 45 tags and also no DOI, no PURL, no `CITATION.cff`. Fix that and you are ahead on the one axis an In-Use reviewer checks mechanically.

---

## 2. Where OntoBoard wins

### Real differentiators — lead with these

1. **The unit of synchronisation is an axiom, not a character.** `collab/OperationMapper.java` maps `OWLOntologyChange` ↔ operation; `collab/bridge.mjs` stores them in `doc.getArray("ops")`. A text CRDT converges a file; this converges a model. No row of OntoEditor's table can express it.
2. **ROBOT in-process against the open ontology.** `robot/` — 24 classes. `QualityReport` (32 rules), `OntologyMeasurements` (60 metrics), `Explanations`, `SparqlQuery`, `TermExtract`, `TermRename`, `AxiomDiff`, `ProfileCheck`. Nine operations compared against the real `robot` binary by `RobotCliParityTest`, and the report against `obolibrary/odkfull` by `RobotParityTest`. OntoEditor has no reasoner at all.
3. **The ODK project lifecycle inside the editor.** `odk/` — 40 classes. `OdkScaffold`, `OdkYaml` (preserves keys it does not model), `IdRanges`, `ImportModules`, `MakeTargets`, `InProcessTargets`, `Release`, `ReleaseDiff`. `OdkBuildTest` runs a scaffolded project's own `make test` and `make prepare_release`. Backed by the ODK authors' own sentence that the ODK "is not aimed at the actual process of ontology editing and therefore lacks any support for data entry".
4. **Data-level audit of the spreadsheet before it becomes axioms.** `sheet/SheetAudit.java` — ten `Kind`s (`AMBIGUOUS_NAME`, `DUPLICATE_ID`, `NEAR_DUPLICATE`, `UNRESOLVED_REFERENCE`, `WHITESPACE`, `TYPOGRAPHIC_CHARACTER`, `MISSING_ID`, `PLACEHOLDER`, `QUOTED_REFERENCE`, `UNUSED_COLUMN`), each `Finding` carrying a `getSuggestion()`, bulk-applied by `sheet/SheetFix.java`. `robot template` validates template-string syntax only and cannot read `.xlsx` at all; `sheet/Workbook.java` reads it with `ZipFile` + SAX and no POI.
5. **Authentication exists.** OntoEditor's Limitation #1 is that it has none. `collab/CollabSettings` carries a JWT, `collab/mint-token.mjs` signs it with the operator's `SECRET_KEY`, `collab/__tests__/hocuspocus-auth.test.mjs` tests it, and the client stops rather than retrying on expiry (`collab/CollabClient.java`, `onRefused`).
6. **Local-filesystem ontologies and multi-file projects** — OntoEditor Limitations #2 and #3. `odk/Catalog.java`, `odk/ImportsGraph.java`, `odk/ImportHealth.java`.
7. **Auditable verification.** 2,363 tests; `SelfCheck.run()` on every startup; a per-release receipt in `protege-plugin/tools/smoke-receipt/<version>.txt` naming what passed *and what was not checked*. OntoEditor's repo: 0 releases, 0 forks, last push 2023-12-11 — before publication.

### Merely more features — include if the table has room, do not lead

The canvas, 163 patterns + `pattern/PatternRecommender.java`, `konclude/`, `widoco/`, PNG/SVG/DOT export, six export formats, `prov/`. Each has a prior claimant (CoModIDE / OWLViz / OntoGraf; the ODP portal; any OWL reasoner; Widoco itself).

### Honest subtraction — two rows that look like wins and are not

- **Conflict resolution.** OntoEditor ticks it with Mergely. OntoBoard has no conflict UI until §1(d) is built. **Do not tick this row today.**
- **Git integration.** OntoEditor's commit "replaces the complete file content on the remote", so OntoBoard wins on substance — but OntoBoard's PR support is `gh`-only and `docs/limitations.md` says reading a diff, reviewing and merging are still the browser's job. The honest cell is "✓ (GitHub; GitLab/Bitbucket for push and pull only)".

---

## 3. The collaboration claim: close it, then narrow it. Do not drop it.

It is the only row where you beat OntoEditor on its own headline. But do not claim it as it stands.

### The gap is not "five more axiom types". The vocabulary is canvas-shaped.

`collab/OntologyOperation.TYPES` is 18 strings — `addClass`, `addStickyNote`, `addFrame`, `addLiteral` — inherited from the retired React client, and `collab/bridge.mjs` **refuses** any type not in `OPERATION_TYPES`. So `OperationMapper.toOperation` (lines 220–236) has exactly four `instanceof` branches and one `unmappable` fallback; the rest is counted in `collab/SessionNotices.java` and shown in a status line.

### `docs/limitations.md` undercounts the gap, and a reviewer will find it

That page's table lists five uncarried kinds (equivalence, disjointness, `owl:hasKey`, negative assertions, datatype definitions) and reports zeros for three. But **`ObjectPropertyAssertion` is not in the table and is not carried.** There is no occurrence of `ObjectPropertyAssertion`, `DataPropertyAssertion`, `ObjectPropertyDomain` or `ObjectPropertyRange` anywhere in `OperationMapper.java` — I grepped it. For an instance-heavy project, which is exactly what the MatWerk KG sheet chain produces (30,972 axioms, per the triage doc), that is the most common axiom after declarations and annotations. **Today, one individual related to another individual does not reach a peer.** Fix the page before the paper.

### The fix is one change, not five — 6 d total

Add a nineteenth operation type, `axiom`, whose payload is the axiom in **OWL Functional Syntax** plus the ontology's prefix map. `toOperation`'s fallback then renders instead of refusing; `toChanges` parses instead of skipping.

Functional syntax because it round-trips every OWL 2 axiom type, is line-oriented, carries no blank-node ambiguity — and, **verified**: `org.semanticweb.owlapi.functional.parser` and `org.semanticweb.owlapi.functional.renderer` are both in the `Export-Package` header of `owlapi-osgidistribution` 4.5.9 (and 4.5.29). Unlike every Rio path, this resolves inside Felix on both hosts.

| Work | File | Size |
|---|---|---|
| One entry in `TYPES`, one in `OPERATION_TYPES` (already pinned against each other by test) | `collab/OntologyOperation.java`, `collab/bridge.mjs` | 0.5 d |
| Replace the `unmappable(...)` fallback with a renderer; add an `"axiom".equals(type)` branch to `toChanges` emitting `AddAxiom`/`RemoveAxiom`. Keep the four specific branches — they carry canvas geometry via `putGeometry`, which a generic axiom cannot | `collab/OperationMapper.java` | 3 d |
| Round-trip test per axiom type; live crossing of equivalence, disjointness and object-property assertion | `collab/OperationMapperTest.java`, `collab/CollabLiveTest.java` (twelve tests already of this exact shape) | 2 d |
| Rewrite the entry as a constraint about *anonymous-subject axioms having no canvas geometry*, not about axiom coverage | `docs/limitations.md`, `docs/collaboration.md` | 0.5 d |

The claim then becomes **"every OWL 2 axiom type the OWL API can render"**, which no reviewer can shrink.

### A convergence defect you must fix and then measure — 2–3 d

`bridge.mjs` stores ops in a `Y.Array`, so every peer sees the same total order. But `CollabSession.onOperation` (line 291) applies in **arrival** order against local state, with no causal metadata beyond `operation.getTimestamp()`, and the apply path is idempotent-add (`declareIfAbsent`, `addIfAbsent`) plus unconditional `retract`.

So: Alice adds X and publishes; Bob removes X and publishes. Alice applies her add, then Bob's remove → X absent. Bob's remove is a local no-op, then he applies Alice's add → X present. **The copies have diverged and the status bar says "Connected".** Add and remove of the same axiom do not commute, and nothing orders them.

Fix: a last-writer-wins tiebreak on `(timestamp, userId)` per axiom key, in a new `collab/AxiomClock.java` that `CollabSession` consults before applying. This is the finding that turns a 5-client experiment from a smoke test into a result.

---

## 4. The single most defensible contribution claim

Not collaboration — OntoEditor owns that word in the reviewer's mind and you would be arguing about degree.

> **OntoBoard is the first ontology editor in which the ODK project lifecycle and ROBOT's operations execute in-process against the ontology the editor has open, closing the edit/validate/release loop that today crosses a container boundary on every iteration.**

It holds because: the ODK authors write that the ODK "is not aimed at the actual process of ontology editing and therefore lacks any support for data entry (such as visual editors)" and does not integrate with WebProtégé; ROBOT is a CLI with no GUI; an exhaustive search of the `protegeproject` organisation returns ten Desktop plugins and none touches ROBOT or the ODK. It is a *combination* claim — the kind In-Use accepts — and it is falsifiable in one search.

**Nothing more needs building.** `odk/InProcessTargets.java` and `robot/` *are* the claim. Two things need **measuring**, and neither exists:

1. **The loop, timed.** The claim is about iteration cost, so give a number: wall-clock for `edit → robot report → read finding → fix → report again`, container versus in-process, on NFDIcore 3.0.4, MWO 3.0.2, PMDco 3.0.0-rc2 and ECTO — all already on this machine under `data/`. `robot/QualityReport` is the in-process arm, `proc/ProcessRunner` plus the pattern in `RobotCliParityTest` is the container arm. **3 d.** It is the only results table in your paper, and **OntoEditor has no results table at all.**
2. **Agreement with ROBOT, as a figure.** You have `RobotParityTest` and `RobotCliParityTest` comparing nine operations against the real binary. Report the count, and report the one principled disagreement that `docs/limitations.md` already documents (the OWL 2 DL check versus `robot validate-profile`). **1 d** — reporting, not code.

**And the adopter must be written up as a deployment, not a mention.** OntoEditor's §8 Adoption is
61 words about *potential* integration with VoCoREG and establishes zero third-party use —
remarkable for an In-Use paper. You have one real adopter. Quantify it: which ontologies, how many
terms and axioms through the sheet chain (30,972 is already measured), how many releases cut via
`Build and release → Release…`, over what period. **2 d of writing, 0 of code**, and it is where you
clear the bar outright.

---

## 5. Six weeks, prioritised

### Week 1 — make the claims true and citable (5 d)

1. **Fix `docs/limitations.md`'s collaboration table** — add `ObjectPropertyAssertion`, `DataPropertyAssertion`, domain and range; recount against NFDIcore/MWO/PMDco. **1 d.** First, because it is the only item currently *wrong in public*.
2. ~~**Reconcile the pattern count.**~~ **Done in 1.128.0.** One number everywhere: **162 bundled patterns, 152 of them distinct** — ten rows are another pattern under a second name and carry a `sameAs`. The number moved from 163 in the same release, because `contentops` was removed: it was never a pattern but the text of the ODP wiki's *index* page, with the SAN ontology attached by mistake and shared byte-for-byte with `actuation-actuator-effect`. The reader-facing counts in `README.md`, `docs/index.md`, the library page, `compared-with.md`, `persistent-identifiers.md`, `requirements-status.md` and `odk-workflow.md` are now generated from, or agree with, `patterns/index.tsv`. A reviewer who counts gets 162.
3. **Zenodo DOI + `w3id.org/ontoboard` PURL + `CITATION.cff` + a Resource Availability paragraph.** **1 d.**
4. **Join-by-link** — `collab/CollabSettings.java`, `collab/CollabDialog.java`, `collab/mint-token.mjs`. **2 d.** Study blocker.
5. **`collab/Dockerfile` + `collab/compose.yml`.** **0.5 d.**
6. **Fix `paper/references.bib`** — the ODK entry's title is wrong. It is *"Ontology Development Kit: a toolkit for building, maintaining and standardizing biomedical ontologies"*, Database 2022:baac087, DOI `10.1093/database/baac087`, not "A Practical Guide to Implementing the Ontology Development Kit (ODK)". **0.5 d.**

### Week 2 — close the collaboration gap (6 d)

7. **The generic `axiom` operation**, broken down in §3: `collab/OntologyOperation.java`, `collab/bridge.mjs`, `collab/OperationMapper.java` both directions, tests, docs. **6 d.**

### Week 3 — make convergence honest and measurable (7 d)

8. **`collab/AxiomClock.java`** + the LWW tiebreak in `CollabSession.onOperation`. **3 d.**
9. **N-client convergence harness → `collab/ConvergenceTest.java`.** **3 d.** `collab/CollabHarness.java` already starts the real server, holds a `List<Peer>`, and offers `join(user, board, ontology)`, `waitFor(Condition)`, `waitUntilConnected(Peer...)`; `CollabLiveTest` already drives two peers over a real socket. Extend to five peers with a scripted edit stream each against a shared base, and **report what OntoEditor did not**: per-operation propagation latency (p50/p95), time to convergence after the last edit, **axiom-set equality across all five copies** via `OWLOntology.getAxioms()` difference — the assertion a text editor structurally cannot make — and sustained ops/second. Skip where node is absent, exactly as now.
10. **Write the result as a dated evidence file** alongside `protege-plugin/tools/smoke-receipt/`. **1 d.**

### Week 4 — the row OntoEditor ticks and OntoBoard does not (6–8 d)

11. **Axiom-level merge-conflict resolution** — `git/MergeConflict.java`, `GitRepo.Status.conflicted`, `menu/ResolveConflictAction.java`, reusing `odk/ReleaseDiff.between`. **6–8 d.** The one new feature worth building for this paper.

### Week 5 — the measurements the paper rests on (6 d)

12. **In-process versus container loop benchmark**, four real projects. **3 d.**
13. **ROBOT agreement figure**, written up from `RobotCliParityTest`. **1 d.**
14. **`glab` support** in `git/PullRequest.java` + host detection in `git/GitHubUrl.java`. **2 d.** Needed for MatWerk regardless.

### Week 6 — the user study (6 d)

15. **Run it, copying OntoEditor's instrument so the numbers are comparable and the method unarguable:** Concurrent Think-aloud during tasks, then the **USE Questionnaire** (Perlman, four subscales, 1–5 Likert, free, already cited by the precedent). **Beat it on three axes it chose not to defend:** n ≥ 12 rather than 9; report **mean and SD per subscale and per item**, which it does not; add **task completion time and completion rate**, which it does not report at all. Three task groups on a shared base, as it did — but make them the three things only OntoBoard can do: (i) scaffold an ODK project and cut a release; (ii) import the cleaned MatWerk `.xlsx`, run the ten-kind audit, bulk-fix, mint IDs, build the template; (iii) join a live session and co-edit **including an equivalence axiom** — which is only a task at all if week 2 landed. Recruit from NFDI-MatWerk *and* outside it, and report the split: OntoEditor's nine were all "computer science background", which is the weakest thing about its study. **5 d.**
16. **Report the expert evaluation as a second prong** — six personas, four real projects, 72 findings surviving an adversarial pass that rejected 49 (`docs/superpowers/specs/2026-10-08-expert-evaluation-triage.md`). A method with a stated rejection rate is more than the precedent offers. **1 d of writing.**

### If six weeks becomes four

Cut items 11 (merge resolution), 14 (`glab`, unless MatWerk needs it this quarter) and chat (never built). Keep 1–10, 12, 13, 15, 16. The paper stands on the collaboration gap being closed *and measured*, the in-process loop being timed, and the study reporting what the precedent did not.

### One pre-flight check before the study depends on it

`docs/superpowers/specs/2026-10-08-expert-evaluation-triage.md` item **A1**: the starter template had never produced a single axiom on any real project or on OntoBoard's own scaffold, and a fixture-rigged test (`TemplateSheetTest.theStarterTemplateItselfRuns`, which does `prefixes.put("ex:", NS)`) hid it. Commit `870e02b fix(template): the starter has never produced an axiom, and now does` claims the fix — **verify it against PMDco's `pmd:` prefix specifically** before task group (ii) relies on it. A participant hitting that costs more credibility than the whole comparison table gains.
