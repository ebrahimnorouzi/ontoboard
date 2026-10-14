<!--
  Written for: the OntoBoard maintainer, before touching the paper again.

  Every claim in paper/main.tex and paper/ontoboard-iswc2026.tex checked against the
  repository as it is today. Read the verdict at the end first.
-->

# OntoBoard paper sources — audit of false and unsupported claims

**Audited:** 2026-10-14 · **Against:** working copy at `d9edf81`, version 1.119.0
**Files audited in full:** `paper/main.tex` (62 lines), `paper/ontoboard-iswc2026.tex` (416 lines),
`paper/sections/{abstract,introduction,architecture,crdt,demonstration,related,conclusion}.tex` (7 files)

## Specification used

No external specification applies to this task — it is a consistency audit, not a metadata file.
The authority is the repository itself. Every "why it is false" cell below cites a path and, where
useful, a line, all read during this audit. Where the repository is itself inconsistent, that is
flagged in §5 rather than used as evidence against the paper.

## Verdict in one line

**Not patchable. The papers describe software that was deleted from this repository on
2026-10-04 and must be rewritten from the current plugin.** Roughly three quarters of the body
text — the title, abstract, system overview, the entire CRDT section, the demonstration and the
conclusion — describes a React/FastAPI web application that is now reachable only at the git tag
`web-app-final`. Full reasoning in §6.

---

## 1. The single fact that decides this audit

The repository states, in its own source, that the described system is gone:

`protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/collab/OntologyOperation.java:20-24`

> Both were originally copied from the OntoBoard web application's
> `frontend/src/collab/useOperationSync.ts` [...] **The web application is no longer part of this
> repository — it is in the history at the tag `web-app-final`** — so the bridge is now the only
> other place the vocabulary lives.

Corroborating evidence:

| Fact | Evidence |
|---|---|
| Web app deleted 2026-10-04 | `e3940d5` *"chore(repo): the repository is the plugin now"* removed all of `backend/` and `frontend/` |
| Deleted state preserved | tag `web-app-final` → `2adc6fd` (2026-10-03) |
| Nothing tracked under either path | `git ls-files backend` → 0 files; `git ls-files frontend` → 0 files |
| On-disk `backend/`, `frontend/` are residue | 327 + 7 408 files on disk, every one gitignored (`__pycache__/`, `node_modules/`, `.pytest_cache/`); `git status` reports nothing |
| The repository is now the plugin | 493 of 1 048 tracked files are under `protege-plugin/`; 213 main + 167 test Java classes |

**Caveat the brief did not anticipate.** The premise that Hocuspocus and Yjs were deleted is
**only partly true**. `collab/` survives with 10 tracked files (`server.mjs`, `bridge.mjs`,
`mint-token.mjs`, 5 test files, `package.json` + lock) and still depends on
`@hocuspocus/server ^3.4.4` and `yjs ^13.6.20`. But its role inverted — `collab/server.mjs:1`
describes it as **"awareness only (cursors, locking, save broadcast)"**, documents are **not
persisted**, and the Protégé plugin **"has no Yjs implementation"** (`server.mjs:9-11`), talking
instead to a JSON bridge on port 1235. So citations to Yjs and Hocuspocus are not automatically
wrong; the claims built on them are.

## 2. Ground-truth table

| Paper says | Repository says | Source |
|---|---|---|
| Browser web application | Protégé Desktop plugin, OSGi bundle | `protege-plugin/pom.xml:10` `<packaging>bundle</packaging>` |
| MIT licence | **Apache-2.0** | `LICENSE:2-4`; "MIT" appears nowhere in the repo outside these two .tex files |
| ROBOT 1.9.6 | **1.9.8** | `pom.xml:32` `<robot.version>1.9.8</robot.version>` |
| Java 21 | **Java 8** | `pom.xml:21` `<maven.compiler.release>8</maven.compiler.release>` |
| 5 Docker-composed services | **no Dockerfile or compose file exists** | `find` for `*docker*`/`compose*` → nothing |
| 214 REST endpoints | **no HTTP API**; in-process library calls | `robot/` package, 21 classes |
| React 18 / Cytoscape.js / Zustand | **Swing + JGraphX** | `pom.xml:114` `jgraphx`; `canvas/CollaborativeGraphComponent.java` |
| FastAPI / rdflib / owlready2 | **none present** | no Python in tracked tree |
| 17 mutation types | **18** | `OntologyOperation.java:28` *"The 18 types the bridge knows"* |
| 123 ODPs across 16 domains | 123 ODP-collection patterns ✓, but **25 domains**, **163 patterns total** | `patterns/index.tsv`: 163 rows; col 3 → 5 collections; col 6 → 25 distinct domains |
| Semantic merge engine | **does not exist anywhere in the repo** | grep for `semanticMerge|merge-?engine|resolveConflict|add-?[Ww]ins` → one false positive in `sheet/SheetLocation.java` |
| 2 385 tests (brief) | **2 385** `@Test` anchors in 167 test classes ✓ | `grep -c` over `protege-plugin/src/test` |
| ~66 MB jar (brief) | **66 520 129 bytes** ✓ | `protege-plugin/target/ontoboard-1.119.0.jar` |

---

## 3. WRONG claims

Fix column: **E** = fixable by editing words · **S** = section must be rewritten · **D** = delete outright

Both `.tex` files carry the same text, so each claim is given once with both line numbers.
`iswc2026` = `paper/ontoboard-iswc2026.tex`.

### 3.1 Licence — the most urgent single fix

| # | File:line | Claim as written | Why false | Fix |
|---|---|---|---|---|
| 1 | `sections/conclusion.tex:15` · `iswc2026:285` | "OntoBoard is open-source under the **MIT license**" | `LICENSE` is the Apache License 2.0 (`LICENSE:2-4`). `NOTICE` carries Apache-style third-party attribution. "MIT" occurs nowhere else in the repository. Publishing an MIT claim over Apache-2.0 code misstates the terms to every reader. | **E** |

### 3.2 Identity and metadata

| # | File:line | Claim as written | Why false | Fix |
|---|---|---|---|---|
| 2 | `main.tex:40-41` · `iswc2026:31-32` | "FIZ Karlsruhe ... **and Karlsruhe Institute of Technology (KIT), AIFB**, Germany" | Ground truth gives one affiliation: Information Service Engineering (ISE), FIZ Karlsruhe. `README.md` and `NOTICE` name ISE / FIZ Karlsruhe only; no KIT/AIFB affiliation appears in the repository. | **E** |
| 3 | `main.tex:42` · `iswc2026:33` | `ebrahim.norouzi@kit.edu` | `NOTICE:4` gives `ebrahim.norouzi@fiz-karlsruhe.de`. The brief also records the publication e-mail as UNKNOWN — confirm before submission rather than carrying a third address. | **E** |
| 4 | `main.tex:36-37` · `iswc2026:27` | Title: "**Reconciling Concurrent OWL Edits Through Ontology-Aware CRDTs**" | The contribution the title names does not exist in the software (see §3.5). The title must follow the rewrite, not survive it. | **S** |
| 5 | `iswc2026:2-3` | "CEURART 1-column format (switch `\documentclass` to ceurart when template is released)" | Stale editorial note; `\documentclass[a4paper]{article}` is still in place at `iswc2026:11` and `main.tex:6`. `paper/` also carries unused `llncs.cls`, `splncs04.bst` and `fig1.eps`, none referenced by either driver. | **E** |

### 3.3 Abstract and introduction

| # | File:line | Claim as written | Why false | Fix |
|---|---|---|---|---|
| 6 | `sections/abstract.tex:9` · `iswc2026:46` | "an open-source, **browser-based** ontology development environment" | It is a Protégé Desktop plugin shipped as an OSGi bundle (`pom.xml:10`), installed by dropping a jar into Protégé's `plugins/` folder (`README.md`, Install). | **S** |
| 7 | `sections/abstract.tex:10-12` · `iswc2026:47-49` | "The principal contribution is an *ontology-aware* operation-based CRDT protocol ... classified by a *semantic merge engine* that distinguishes structural, semantic, and positional fields" | No merge engine exists (§2). The stated principal contribution of both papers is absent from the software. | **S** |
| 8 | `sections/abstract.tex:12` · `iswc2026:49` | "true semantic conflicts are surfaced with interactive resolution controls" | No conflict UI exists. `docs/limitations.md` states the opposite: **"Nothing prevents two people editing one term — and nothing in OntoBoard can."** | **D** |
| 9 | `sections/introduction.tex:23` · `iswc2026:77` | "OntoBoard is presented as an integrated, **browser-based** environment" | As #6. | **S** |
| 10 | `sections/introduction.tex:26` · `iswc2026:80` | Contribution 1: "An *ontology-aware, operation-based CRDT* synchronization protocol in which concurrent mutations are classified by a semantic merge engine" | As #7. | **S** |
| 11 | `sections/introduction.tex:27` · `iswc2026:81` | Contribution 2: "A *unified **web** interface* ... and **browser-accessible** execution of 13 ROBOT commands" | No web interface. ROBOT runs in-process inside Protégé via embedded `robot-core` 1.9.8 (`pom.xml:119-121`). Command count also wrong — see #21. | **S** |

**Positioning hazard, not a factual error.** `sections/introduction.tex:12` / `iswc2026:66` opens
Barrier 1 with "The most widely adopted ontology editor, Protégé, is a single-user desktop
application." That sentence is still true of stock Protégé, but OntoBoard is now *a Protégé
plugin*, so the paper would be attacking its own host. Likewise lines 14-15 / 68-69 criticise
WebProtégé and VocBench for locking, while OntoBoard now ships advisory editing claims
(`collab/EditingClaim.java`) that are weaker than a lock, not stronger. Both passages survive as
prose but must be re-aimed. **S**

### 3.4 System Overview — the whole architecture is wrong

| # | File:line | Claim as written | Why false | Fix |
|---|---|---|---|---|
| 12 | `sections/architecture.tex:8` · `iswc2026:90` | "deployed as **five Docker-composed services**" | No Dockerfile and no compose file exist anywhere in the repository. The deliverable is one jar. | **S** |
| 13 | same line | "a **React/TypeScript** frontend with a **Cytoscape.js** graph canvas and **Zustand** state management" | Deleted with `frontend/` in `e3940d5`. The canvas is Swing/JGraphX (`pom.xml:114`, `canvas/` 20+ classes). | **S** |
| 14 | same line | "a **FastAPI** backend providing **214 REST endpoints**" | Deleted with `backend/` in `e3940d5`. No HTTP API remains; nothing in the repository has ever counted 214 endpoints. | **S** |
| 15 | same line | "a **Redis-backed worker** for long-running ROBOT tasks; and a **Redis** instance for job queuing and progress pub/sub" | No Redis dependency in `pom.xml` or `collab/package.json`. ROBOT operations run in-process on the ontology Protégé has open. | **S** |
| 16 | same line | "a Hocuspocus WebSocket server hosting Yjs documents for real-time synchronization" | **Partly true, materially misdescribed.** `collab/` survives, but `server.mjs:1` scopes it to "awareness only (cursors, locking, save broadcast)", documents are not persisted, and the plugin speaks JSON on port 1235 because it "has no Yjs implementation". | **E** |
| 17 | `sections/architecture.tex:13-24` · `iswc2026:98-109` | ASCII architecture figure: "Browser Client / React 18 / Cytoscape.js / Zustand + Yjs", "Backend (API) / FastAPI / rdflib+owlready2", "Worker / Python + ROBOT" | Every box but "Collab Server" names deleted or never-present software. | **D** |
| 18 | `sections/architecture.tex:23` · `iswc2026:108` | "ROBOT **1.9.6** + Java **21**" | `pom.xml:32` pins robot-core **1.9.8**; `pom.xml:21` sets `maven.compiler.release` **8**. Both numbers wrong in the same table cell. | **E** |
| 19 | `sections/architecture.tex:26` · `iswc2026:112` | Caption: "OWL graphs are manipulated via **rdflib** and **owlready2**. ROBOT and Java are installed directly in **container images**, eliminating Docker-in-Docker" | No Python libraries; no container images. OWL manipulation is via OWL API 4.5.9 forced down for Protégé 5.5.0 (`pom.xml:38-47`). | **D** |
| 20 | `sections/architecture.tex:32` · `iswc2026:118` | "A **recursive-descent Manchester Syntax parser** supports the full OWL 2 expression language, including `HasSelf`, `ObjectOneOf`, qualified cardinality restrictions, and datatype facets" | No parser of any kind exists in the plugin — grep for `class.*Parser` / "recursive descent" over `src/main/java` returns nothing. The only Manchester code is the OWL API's **renderer** (output only), used in `robot/Explanations.java:14,241` and noted at `TermSummary.java:29`. Axiom *input* is Protégé's own editors. | **S** |
| 21 | `sections/architecture.tex:36` · `iswc2026:122` | "**Thirteen** ROBOT commands (reason, report, explain, **convert**, template, diff, query, merge, extract, **annotate**, rename, **repair**, **filter**) are exposed **through the browser**" | Three named commands have no implementation: `ConvertOperation`, `AnnotateOperation`, `FilterOperation` appear nowhere in `src/main/java`. `repair` exists only inside `RobotTransform.java:366`, not as its own command. Four current capabilities are missing from the list — **measure, profile, transform, export terms** (`robot/OntologyMeasurements.java`, `RobotTransform.java`, `TermExport.java`). And nothing is exposed through a browser. | **S** |
| 22 | `sections/architecture.tex:37` · `iswc2026:123` | "Build logs are streamed in real time via **server-sent events**" | SSE is an HTTP mechanism; there is no HTTP server. Logs render into a Swing dialog (`README.md`: "report into a dialog you can read and save"). | **E** |
| 23 | `sections/architecture.tex:38` · `iswc2026:124` | "Four OWL reasoners are supported: ELK, HermiT, JFact, and Whelk via ROBOT, with **HermiT and Pellet additionally available through embedded owlready2**" | owlready2 is gone, so Pellet is not available by any route; no Pellet/Openllet reference exists in `src/main/java`. The clause is false. The sentence also omits the reasoner that now dominates the codebase: **Konclude**, 148 references and its own package (`konclude/Konclude.java`, `KoncludeInferences.java`, `KoncludeInstall.java`, `KoncludeLog.java`), added in `df45064`. | **E** |
| 24 | `sections/architecture.tex:41` | "123 Ontology Design Patterns from the ODPA catalog **across 16 domains** (applicable via **drag-and-drop** with domain filtering)" | "123" is correct for the ODP-portal subset (`patterns/index.tsv` col 3 = `odp` → 123 rows), but **163** patterns ship in total and the domain column holds **25** distinct values, not 16. Domain filtering exists; drag-and-drop does not — the `pattern/` package has no `TransferHandler`/`DropTarget`, and patterns are applied by ROBOT module extraction (`README.md`: "import the terms you want through ROBOT's module extraction"). | **E** |
| 25 | `iswc2026:127` | "The system bundles **13** Ontology Design Patterns from the ODPA catalog" | Wrong by an order of magnitude — 123 ODP patterns, 163 total. **Also a direct contradiction between the two paper sources**: `architecture.tex:41` says 123, `iswc2026:127` says 13. At least one has always been wrong. | **E** |
| 26 | `sections/architecture.tex:41` · `iswc2026:127` | "a **six-step** CSV-to-Knowledge-Graph import wizard built on ROBOT templates" | The capability survives — `docs/feature-parity.md:133` records "CSV → ROBOT template wizard ... *ROBOT → Template…*, TSV or CSV" — but it is a Protégé dialog, not a browser wizard, and the step count is unverified in the current implementation (`sheet/` package, 11 classes). | **E** |

**Survives in this section:** the SPARQL panel (`robot/SparqlQuery.java`), Widoco documentation
generation (`widoco/Widoco.java`, `WidocoInstall.java`) and PROV-O provenance (`prov/` package)
all exist. Only their delivery mechanism is misdescribed.

### 3.5 CRDT section — unsalvageable in full

This is the paper's claimed principal contribution. Nothing in it is implemented.
`sections/crdt.tex` (72 lines) and `iswc2026:131-199` must be **deleted or rewritten from
scratch**. Itemised for completeness:

| # | File:line | Claim as written | Why false |
|---|---|---|---|
| 27 | `crdt.tex:23` · `iswc2026:150` | "one of **17** supported mutation types" | `OntologyOperation.java:28` — "The **18** types the bridge knows", listing `addClass` … `updateAnnotation`. `docs/limitations.md` likewise says "a vocabulary of eighteen operation types". |
| 28 | `crdt.tex:25-26` · `iswc2026:152-153` | "Operations are appended to a shared `Y.Array` hosted in the board's Yjs document. Yjs guarantees causal delivery" | The plugin has no Yjs implementation (`collab/server.mjs:9-11`); it sends JSON over a WebSocket bridge (`CollabClient.java:14-15`, `org.java_websocket`). |
| 29 | `crdt.tex:28` · `iswc2026:155` | "the local **Zustand** store is updated optimistically" | Zustand died with `frontend/`. State lives in Protégé's own model, which the plugin observes *after* the fact. |
| 30 | `crdt.tex:29` · `iswc2026:156` | "bypassing the undo stack and persistence layer — only the originator **writes to the backend**" | There is no backend. `collab/server.mjs:4-6`: documents "are NOT persisted ... The ontology itself lives in each editor's own files". |
| 31 | `crdt.tex:32-37` · `iswc2026:159-164` | §"Conflict Detection via Sliding Window": affected-IRI sets $\mathcal{E}(o)$, window $\Delta = 2$ s, cross-user overlap test | No such mechanism exists. No sliding window, no overlap detection, no $\Delta$. |
| 32 | `crdt.tex:39-62` · `iswc2026:166-189` | §"Semantic Merge Engine" and **Table: seven merge rules** (both / conflict / add-wins / lww) | The merge engine does not exist (§2). Every row of the table is unimplemented. |
| 33 | `crdt.tex:64-65` · `iswc2026:191-192` | "The distinction between *semantic fields* (label, IRI, color, datatype) and *positional fields* ($x,y,w,h$) is central to the design" | Central to the *deleted* design. Layout now lives in a sidecar file, `<ontology>.ontoboard.json` (`docs/collaboration.md`), deliberately outside the ontology — a different mechanism answering a different problem. |
| 34 | `crdt.tex:67-68` · `iswc2026:194-195` | "an interactive banner ... *keep mine* / *keep theirs* / *keep both*"; "Auto-resolved merges ... brief non-blocking notifications" | No such UI. grep for `keep ?(mine|theirs)` returns nothing. |
| 35 | `crdt.tex:70` · `iswc2026:197` | "a **dedicated endpoint** invokes **owlready2's** embedded HermiT reasoner and reports any unsatisfiable classes" | No endpoints, no owlready2. |
| 36 | `crdt.tex:72` · `iswc2026:199` | "validated by **25 unit tests** ... and **19 integration tests** for the end-to-end conflict detection pipeline" | Those 44 tests do not exist. The 2 385 `@Test` anchors in `protege-plugin/src/test` test the plugin; no conflict-detection pipeline is among them. **Citing tests for absent code is the most damaging claim in the paper.** |

**What actually exists, and is a defensible replacement contribution.** Live collaboration is
real but modest and honestly bounded in the repo's own documentation:

- Operations carry **exactly four axiom classes** — declarations, `SubClassOf`, class assertions,
  annotation assertions — out of 18 declared types; everything else is counted and reported in the
  status bar rather than dropped silently (`docs/limitations.md`).
- Collision handling is **advisory, not enforced**: `collab/EditingClaim.java:6-9` —
  *"'Entity locking' promises that two people cannot edit the same term; OntoBoard cannot deliver
  that, and should not pretend to."* A claim rides the presence heartbeat, expires with its holder
  (10 s TTL) and in place after `HOLD_MILLIS` = 5 min.
- Offline edits are queued and flushed on reconnect with original timestamps, bounded at
  `PENDING_LIMIT` = 200, with dedup over `SEEN_LIMIT` = 2000 ids (`CollabClient.java`).
- `docs/limitations.md` publishes the measured cost of the four-axiom limit against three real
  ontologies (equivalence 16/36/260 and disjointness 18/18/44 in nfdicore/mwo/pmdco).

That is a smaller claim than a CRDT protocol, and a far more credible one.

### 3.6 Demonstration scenario

All six scenarios are staged in a browser with two users on shared canvases, so the section needs
rewriting as a Protégé walkthrough. Scenario-by-scenario salvage value:

| # | File:line | Claim | Status | Fix |
|---|---|---|---|---|
| 37 | `demonstration.tex:12-14` · `iswc2026:211-213` | "creates classes via double-click ... User A's cursor and selection visible via the **Yjs awareness layer**" | Peer cursors are real (`canvas/PeerCursorLayer.java`, `collab/PeerCursors.java`), but the plugin reaches them through the JSON bridge, not a Yjs awareness layer. | **E** |
| 38 | `demonstration.tex:16-18` · `iswc2026:215-217` | "The **merge engine** classifies the pair as *different fields* and applies both changes silently. A brief green notification" | No merge engine, no notification. | **D** |
| 39 | `demonstration.tex:20-22` · `iswc2026:219-221` | "An **amber banner** appears ... *Keep mine* / *Keep theirs* / *Keep both*" | No conflict UI. | **D** |
| 40 | `demonstration.tex:24-26` · `iswc2026:223-225` | "selects the 'biology' domain from the pattern library filter and **drags** the *Part-Of* pattern onto the canvas" | Mostly recoverable: a `biology` domain exists (19 patterns) and `patterns/part-of` ships. But application is not drag-and-drop (#24), and the claim that terms "appear on both users' canvases simultaneously" is unsupported — pattern import is not among the four carried axiom classes. | **E** |
| 41 | `demonstration.tex:28-30` · `iswc2026:227-229` | "**Browser-based** ROBOT reasoning ... Build logs stream in real time; inferred axioms are overlaid on the canvas as **dashed edges**" | Dashed inferred edges are genuine and a strength — `README.md`: "Switch on the reasoner's conclusions, then right-click a dotted edge and ask **why**" (`reason/EdgeExplanation.java`). Only "browser-based" and "stream" are wrong. | **E** |
| 42 | `demonstration.tex:32-33` · `iswc2026:231-232` | "entered in the axiom editor with **live syntax validation and error highlighting**" | Depends on the parser that does not exist (#20). Protégé's own editors provide this, not OntoBoard. | **S** |

### 3.7 Related work

The prose about *other* tools survives intact. The OntoBoard column does not.

| # | File:line | Claim | Why false | Fix |
|---|---|---|---|---|
| 43 | `related.tex:17` · `iswc2026:250` | Comparison table, "Web-based" row: OntoBoard **✓** | It is a desktop plugin. The cell should read `--`, or the row should be dropped as no longer a dimension on which OntoBoard competes. | **E** |
| 44 | `related.tex:20` · `iswc2026:253` | "Real-time collab." row: OntoBoard **CRDT** | No CRDT. Honest cell: *presence + advisory claims, 4 axiom kinds*. | **E** |
| 45 | `related.tex:21` · `iswc2026:254` | "Conflict merge" row: OntoBoard **semantic** | No merge of any kind. Honest cell: `--`. | **E** |
| 46 | `related.tex:18` · `iswc2026:251` | "Full OWL 2" row: OntoBoard **✓** | Overstated in both directions that matter: collaboration carries 4 of 13 axiom kinds, and `docs/limitations.md` records a deliberate divergence between the plugin's OWL 2 DL check and `robot validate-profile`. | **E** |
| 47 | `related.tex:37` · `iswc2026:270` | "(i) synchronization is *optimistic* (**no locks**; edits are applied immediately on all clients)" | The differentiator is gone. OntoBoard's actual position is the opposite end of the design space from the one claimed — it cannot enforce anything, so it reports instead. | **S** |
| 48 | `related.tex:19` · `iswc2026:252` | "Visual canvas" row: Protégé = "**plugin**" | Still literally true, but the cell now silently describes OntoBoard's own delivery mechanism, which collapses the distinction the table is drawing. | **S** |

**Survives:** rows "ROBOT integration", "ODP library", "Reasoning" (all ✓ and all genuine), and
the five paragraphs of prose at `related.tex:31-35` / `iswc2026:264-268` on Protégé, WebProtégé,
VocBench 3, OnToology and Chowlk.

### 3.8 Conclusion

| # | File:line | Claim | Why false | Fix |
|---|---|---|---|---|
| 49 | `conclusion.tex:8` · `iswc2026:278` | "a **web-based** ontology development environment in which an ontology-aware, operation-based **CRDT** protocol enables real-time collaborative OWL editing without pessimistic locking" | Both halves false (#6, #7). | **S** |
| 50 | `conclusion.tex:10` · `iswc2026:280` | "integrating ROBOT pipeline execution **into the browser**" | In-process inside Protégé. The underlying achievement is real and stronger than claimed — `README.md`: seven of eight generated build targets run "with **nothing installed at all**: no make, no robot, no shell, no container". | **E** |
| 51 | `conclusion.tex:12` · `iswc2026:282` | Future work: "**offline operation queuing with replay-on-reconnect**" | Already implemented — `CollabClient.java` holds operations in a bounded queue and flushes them on reconnect with original timestamps. Listing shipped behaviour as future work understates the tool. | **E** |

---

## 4. UNVERIFIABLE claims — numbers nobody has measured

Distinct from §3: these are not contradicted by the repository, but nothing in it supports them,
and the repository's own evaluation plan treats the central one as **work not yet done**.

| # | File:line | Claim | Status |
|---|---|---|---|
| U1 | `abstract.tex:13` · `iswc2026:50` | "**Sub-100 ms** synchronization latency has been measured with up to **15 concurrent users**" | **No such measurement exists.** `docs/evaluation-plan.md:190` specifies this benchmark as future work — "B3 — Convergence and latency benchmark ... N headless Yjs clients (2, 4, 8, 16) ... Measure ... propagation latency p50/p95/p99 ... *20 h*" — and its Table 15 (`:359-361`) is an empty template awaiting results. "has been measured" is therefore not merely unsupported but contradicted by the repo's own plan. Note the plan's client counts are 2/4/8/16, never 15. |
| U2 | `crdt.tex:27` · `iswc2026:154` | "The WebSocket server propagates array deltas within approximately **50 ms**" | Same gap as U1; no harness, no recorded run. |
| U3 | `demonstration.tex:14` · `iswc2026:213` | "observed on User B's canvas within **~100 ms**" | Same gap. |
| U4 | `crdt.tex:70` · `iswc2026:197` | consistency check "typically completing **within two seconds**" | No timing data, and the mechanism it describes (owlready2 endpoint) does not exist. |
| U5 | `conclusion.tex:9` · `iswc2026:279` | the merge engine "**has been shown** to resolve the **majority** of concurrent modifications automatically" | No study, no data, and no merge engine. "Has been shown" asserts an evaluation that was never run. |
| U6 | `architecture.tex:37` · `iswc2026:123` | "**seven** common failure patterns ... are automatically recognized" | The mechanism exists (`odk/MakeRun.java` matches `odk-info`, `OutOfMemoryError`), but the count of seven is unverified against the current implementation. |
| U7 | `related.tex:32` · `iswc2026:265` | WebProtégé "pioneered web-based ontology editing for over **68 000 projects**" | External third-party figure, uncited and unverifiable from this repository. Attach a source or drop the number. |
| U8 | `related.tex:8` · `iswc2026:241` | "compares OntoBoard ... across **eight dimensions**" | Arithmetically consistent with the table as written (8 rows), but will need recounting after rows are corrected per #43-#46. |

### Bibliography consequences

`paper/references.bib` (204 lines) and the duplicated `thebibliography` at `iswc2026:292-414`
contain entries that exist only to support deleted software:

- **Delete:** `cytoscapejs` (`references.bib:99`, `iswc2026:349`), `fastapi` (`:109`, `:354`),
  `rdflib` (`:116`, `:359`), `owlready2` (`:123`, `:364`).
- **Keep only if collaboration is still discussed:** `yjs` (`:132`, `:369`), `hocuspocus`
  (`:139`, `:374`) — justified by the surviving `collab/` service, not by the paper's current text.
- **Delete with the CRDT section:** `shapiro2011crdt` (`:146`), `ot` (`:156`), `automerge` (`:164`).
- **Keep:** `owl2`, `simperl2014collaborative`, `protege`, `palma2011ontology`, `webprotege`,
  `vocbench3`, `ontoology`, `chowlk`, `odk`, `robot`, `odpa`, `widoco`, `provo`, `shacl` — 14
  entries, all still earned. `manchester` survives only if a Manchester claim survives (#20).

Maintaining the bibliography twice, in `references.bib` and inline in `iswc2026.tex`, is itself a
defect: the two will drift. Pick one.

---

## 5. Repository findings that contradict the brief or itself

Reported as instructed — these are repo problems, not paper problems.

1. **`paper/` is not version-controlled.** `.gitignore` contains `paper/*`, and
   `git log --all -- paper` is empty. Both `.tex` files are untracked local-only files. Whatever
   is decided about them, nothing is recoverable from git if they are lost.
2. **A dangling reference to the deleted web app in shipped code.**
   `CollabClient.java:35-36` — "They keep their original timestamps, so **the web application's
   merge engine** still orders them correctly against whatever happened meanwhile." The class
   doc explains the plugin's behaviour by reference to software that no longer exists. Worth
   rewording before anyone reads it as evidence that a merge engine ships.
3. **Pattern count disagrees across four places.** `README.md` and `.gitignore` say **159**
   ("123 ODP portal + 36 from MWO/PMDco"); the 1.118.0 smoke receipt says **163 patterns
   indexed**; `patterns/index.tsv` holds **163** rows and `patterns/` **163** `.ttl` files; and
   `docs/evaluation-plan.md:19` records `patterns/` as holding **164 dirs**. The 4-pattern `bfo`
   collection appears to post-date the README text. 163 is the count I could verify two ways.
4. **The repository already knows its numbers are stale.** `docs/evaluation-plan.md:12-21` is a
   table titled "Numbers to fix before anything is printed", flagging the test count (2 347 in the
   receipt vs 2 359 then, **2 385** now), the pattern count, and
   `docs/limitations.md:124` "still says '1207 tests cover…'". Its own advice — "Pick one source of
   truth (the receipt) and regenerate the rest from it" — applies to any new paper.
5. **Brief confirmed, with one correction.** Name, repo URL, Apache-2.0 at `LICENSE`, version
   1.119.0 (`pom.xml:9`), 46 tags, artifactId `ontoboard`, Java 8, robot-core 1.9.8, 66 MB jar and
   2 385 tests all check out exactly. The one correction: **Hocuspocus and Yjs were not deleted**
   — `collab/` survives as an awareness-only service (§1). The brief's "214 REST endpoints",
   React, Cytoscape.js, FastAPI and owlready2 are all correctly described as gone.
6. **No ORCID or publication e-mail is recoverable from the repository**, consistent with the
   brief. Any new artefact needs the TODO placeholder; the papers meanwhile assert a KIT address
   (#3) that no repository file supports.

---

## 6. Verdict

**Rewrite. Do not patch.**

The two sources are one paper in two forms, and the thing they are about no longer exists.
Counting by line, roughly 75-80 % of the body describes the deleted web application: the entire
System Overview, the entire CRDT section (the claimed principal contribution), the entire
Demonstration, the OntoBoard half of Related Work, the abstract, the conclusion and the title.
An edit pass cannot fix this, for three reasons:

1. **The contribution is gone, not relocated.** Patching would mean deleting the paper's thesis
   and finding a new one — which is a rewrite under a different name.
2. **The claims are load-bearing on each other.** The merge engine supports the abstract, the
   contribution list, Table 2, three of six demo scenarios, two table rows and the conclusion.
   Pulling it collapses the structure.
3. **The paper asserts evidence that does not exist** (#36 — "25 unit tests ... 19 integration
   tests"; U1 — "has been measured"; U5 — "has been shown"). These are not stale numbers but
   claims of work never done, and they are the ones that would end badly in review.

### What survives, and is worth carrying forward

| Survives | Where | Condition |
|---|---|---|
| **Barrier 2 motivation** — ODK/ROBOT demand Makefiles, Java classpaths, Docker, POSIX shell | `introduction.tex:17-20` · `iswc2026:71-74` | **Verbatim.** This is now the paper's strongest and most defensible claim, and it is exactly the thesis `README.md` leads with. Build the new paper on it. |
| **Related-work prose on other tools** | `related.tex:31-35` · `iswc2026:264-268` | Verbatim, minus the 68 000 figure (U7). |
| **Barrier 1 background** | `introduction.tex:11-15` · `iswc2026:65-69` | Re-aim: the paper now ships *inside* Protégé and cannot criticise it as the foe. |
| **Bibliography** | `references.bib` | 14 of 23 entries keep; delete 4 outright, 3 with the CRDT section, 2 conditional. |
| **Comparison-table skeleton** | `related.tex:10-29` | Keep the format; rebuild the OntoBoard column and drop "Web-based". |
| **Capability claims, re-situated** | `architecture.tex:36-41` | ROBOT integration, reasoning, pattern library, SPARQL, Widoco, PROV-O are all real. Only "in the browser" is false. |

### Scope of the rewrite

| Must be written from scratch | Lines |
|---|---|
| Title, abstract | `main.tex:36-37`, `abstract.tex` · `iswc2026:27,42-51` |
| Contributions list | `introduction.tex:22-28` · `iswc2026:76-82` |
| System Overview incl. figure | `architecture.tex` (41 lines) · `iswc2026:86-127` |
| CRDT section incl. Table 2 | `crdt.tex` (72 lines) · `iswc2026:131-199` |
| Demonstration | `demonstration.tex` (34 lines) · `iswc2026:203-233` |
| Conclusion | `conclusion.tex` (15 lines) · `iswc2026:274-285` |

### Recommended shape for the replacement

The honest paper is the one the README already tells: **an OSGi plugin that embeds `robot-core`
1.9.8 and runs ODK/ROBOT operations in-process against the ontology Protégé has open**, so that
seven of eight generated build targets run with nothing installed — no make, no robot, no shell,
no container, on any platform. That is a genuine contribution, it is what the 2 385 tests
actually test, and it needs no unmeasured latency figure to stand up.

Collaboration becomes a secondary, carefully-bounded section built on `collab/` and
`docs/limitations.md`: presence and peer cursors, advisory editing claims that expire with their
holder, four of thirteen axiom kinds carried, and the measured census of what that costs on three
real ontologies. Publishing that gap, measured, is what `docs/evaluation-plan.md:190` calls the
difference between an In-Use paper and a brochure.

Two further assets the papers do not mention at all and the rewrite should: the **Konclude**
integration (148 references, its own package, added in `df45064`) and the **ROBOT-template
spreadsheet editor** (`sheet/`, 11 classes, the subject of the three most recent commits). And
`docs/evaluation-plan.md` is a ready-made, costed empirical section — a within-subjects n=16
design against the real baseline of Protégé + ROBOT CLI + LibreOffice — which is what would move
this from a demo paper to one with results.

### Immediate action regardless of the rewrite decision

Fix the licence claim (#1) now. It is a two-word edit in each file, and of everything in this
audit it is the only error that misinforms readers about their legal rights to the code.
