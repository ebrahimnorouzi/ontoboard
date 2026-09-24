# Feature Parity: Web Application vs Protégé Plugin

What the web application does, what the plugin does today, and — for each gap — whether it is
worth building, already solved by something else, or not applicable to a desktop plugin.

Written to answer three questions honestly: *what am I missing, can it be done, and should
it be?*

**Legend** — ✅ present · 🔶 partial · ❌ absent · ➖ not applicable · 🧩 already provided by a
plugin bundled with Protégé 5.6.9

---

## The headline

The gap is smaller than the feature list suggests, for two reasons.

**Protégé already does a lot of it.** Class/property/individual editing, Manchester syntax,
property characteristics and chains, reasoners, explanation, DL Query, SPARQL, SWRL and CSV
import all exist in Protégé or in plugins that ship with it. The web application had to build
those because a browser has no OWL editor; the plugin gets them by living inside one. Building
OntoBoard versions would duplicate mature tools and is the wrong use of effort.

**A third of the web application is web-application machinery.** Boards, accounts, invite
links, notifications, an admin page — these are not ontology features, they are what a
multi-tenant web service needs. A desktop plugin has no use for them.

What genuinely remains is the ODK pattern library, the SPARQL panel, Widoco documentation,
pull requests, and threaded comments. The ODK/ROBOT pipeline surface, provenance, quality
reporting, git tooling and live collaboration all ship — this document said otherwise for
several releases, because the plugin grew faster than the table did.

---

## 1. Ontology editing

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| Class / property / individual editing | ✅ | ✅ | Protégé's own editors, better than either could build |
| Manchester syntax editing | ✅ | ✅ | OWL API parser + Protégé's expression editor with autocomplete |
| Property characteristics (all 7) | ✅ | ✅ | Protégé entity editor |
| Property chains, XSD ranges, annotation CRUD | ✅ | ✅ | Protégé entity editor |
| OWL restrictions editor | ✅ | ✅ | Protégé class expression editor |
| Structured axiom view | ✅ | ✅ | Protégé |
| DL Query | ✅ | 🧩 | `org.coode.dlquery`, bundled |
| SWRL rules | ✅ | 🧩 | SWRLTab, bundled |
| Custom datatypes, `owl:hasKey`, negative assertions | ❌ | ✅ | Protégé supports these; the web app never did |

**Nothing to build.** The plugin is ahead here simply by running inside Protégé. Since
1.8.0 the OntoBoard tab also carries Protégé's own class, property, datatype and
individual views in a tabbed column beside the canvas, so none of this needs a tab switch.

## 2. Visual canvas

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| Graph canvas, drag, pan, zoom | ✅ | ✅ | JGraphX |
| Create entities on canvas | ✅ | ✅ | Double-click empty canvas, or drag from the entity trees |
| Draw edges that write axioms | ✅ | ✅ | Plugin is **better**: six OWLAx readings vs the web app's `rdfs:domain`/`range`, which silently intersects domains |
| Delete axioms from canvas | ✅ | ✅ | With a confirmation distinct from removing from canvas |
| Layout algorithms | ✅ | ✅ | Hierarchical, organic, circle, grid |
| Minimap, PNG/SVG export | ✅ | ✅ | |
| Labels from `rdfs:label`, namespace colours | 🔶 | ✅ | Plugin colours by namespace and prefers labels |
| Selection synced with the editor | ➖ | ✅ | **New capability** — impossible in the web app |
| Live redraw on external edits | ➖ | ✅ | Canvas is a view over Protégé's model |
| Layout persistence | ✅ | ✅ | Plugin keeps it in a sidecar, so the ontology stays byte-clean |
| Frames and sticky notes | ✅ | ✅ | Right-click the canvas; kept in the sidecar |
| Undo/redo | ✅ | ✅ | Protégé's own undo, which is stronger than the web app's 50-snapshot stack |

## 3. Reasoning and quality

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| ELK, HermiT, JFact, Pellet | ✅ | 🧩 | Bundled reasoner plugins, with incremental and background reasoning the web app lacked |
| Explanation / justification | ✅ | 🧩 | `explanation-workbench`, bundled |
| Consistency checking | ✅ | 🧩 | Protégé |
| ROBOT report | ✅ | ✅ | *ROBOT → Quality report…*, all 32 of ROBOT's rules, on both hosts. Checked against real ROBOT in `obolibrary/odkfull` and by a startup self-check in the host |
| ROBOT commands | 🔶 7 of 24 | 🔶 6 as menu commands | measure, report, profile, transform (relax/reduce/repair/merge), extract, template |
| OOPS! pitfall check | ✅ | ❌ | **Build.** An HTTP call to the OOPS! service |
| OQuaRE metrics | ✅ | ❌ | **Build.** Port `quality.py` |
| Analysis: unused, deprecated, circular imports | ✅ | ❌ | **Build.** OWL API traversals, small |
| SHACL validation | ❌ | 🧩 | `shacl4protege` exists — neither product built it |

## 4. ODK pipeline

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| ODK project scaffolding | ✅ | ✅ | Same file tree, no Docker needed |
| Open an existing ODK repo | ✅ | ✅ | Detects the repo, refuses to guess between edit files |
| `odk.yaml` editing | ✅ | ❌ | **Build.** Small |
| Makefile target execution | ✅ | ✅ | *Project → Build…*; the transcript is shown afterwards, not streamed live |
| Build error explanations | ✅ | ✅ | `MakeRun.explain` names the cause for missing rules, missing robot, OOM and report violations |
| Import resolution + catalog | ✅ | ✅ | *Project → Imports* reports what did not resolve; *ROBOT → Import terms…* writes module, catalog entry and import together |
| ID range management | ✅ | ✅ | *Project → ID ranges…* allocates a range and rewrites the file |
| Multi-file / file switcher | ✅ | ➖ | Protégé's own ontology switcher |

## 5. Data integration

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| ODP pattern library | ✅ 13 patterns | ❌ | **Build.** Distinctive and valuable; `patterns-repository/` is already in the repo |
| CSV → ROBOT template wizard | ✅ | ✅ | *ROBOT → Template…*, TSV or CSV, every bad row reported at once. robot-core 1.9.8 reads no XLSX templates at all, so this is not a gap against ROBOT |
| SPARQL query panel | ✅ | 🧩 | `sparql-query-plugin`, bundled |
| GitHub import | ✅ | ✅ | *Project → Open from GitHub…* clones and opens the edit file |
| Export formats | ✅ | 🔶 | Protégé exports OWL formats; ROBOT `convert` would add OBO/JSON-LD |
| Materialize inferred relations | ➖ | ✅ | *ROBOT → Transform… → Materialize relations*. Where Reason asserts inferred subclass axioms, this asserts inferred existential relations, so a consumer that cannot reason still sees them |
| Axiom-level release diff | ➖ | ✅ | *Project → Compare releases…*, opt-in alongside the term-level table. ROBOT's own `diff`, with labels on, because the two answer different questions |
| SPARQL over the open ontology | ✅ | ✅ | *ROBOT → SPARQL…* runs a query you write, or the project's own `src/sparql/*.rq` checks with the pass/fail convention `make sparql_test` uses. SELECT and ASK only |
| Explanation of unsatisfiability | ➖ | ✅ | *ROBOT → Explain…* names every unsatisfiable class, the axioms behind each, and which single axiom appears in the most justifications. Protégé's explanation workbench explains one entailment you have already selected; this starts from "the reasoner went red" |
| Term table export | ➖ | ✅ | *ROBOT → Export terms…* writes one row per term and the columns you choose, as TSV, CSV, JSON, YAML or HTML. Not in the web application. `xlsx` is the one format the bundle cannot write — see Known constraints |
| ZIP export | ✅ | ➖ | It is a folder on disk |
| Widoco HTML docs | ✅ | ❌ | **Build.** External 39 MB jar, invoked as a subprocess |
| Prefix management | ✅ | 🔶 | Colouring done; editing prefixes not |
| Provenance (Dublin Core) | ✅ | ✅ | `dcterms:contributor`/`created`/`date`, stamped for edits made anywhere in Protégé, not only on the canvas |

## 6. Collaboration

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| Real-time multi-user editing | ✅ | ✅ | Against a server the team runs; see [collaboration](collaboration.md) |
| Cursor sharing | ✅ | ✅ | Graph-space, so a cursor lands on the same entity at any zoom |
| Semantic merge engine | ✅ | ✅* | *Reused server-side rather than reimplemented in Java — deliberate |
| Entity locking | ✅ | ❌ | **Build.** Advisory in both |
| Comments, @mentions | ✅ | 🔶 | Attributed editor and curator notes ship, and *Notes → Discussion link…* points at the issue. No reply threads, no @mentions |
| Git-based collaboration | ❌ | ✅ | *Git…* does status, stage, commit, pull, push and branch; *Project → Compare releases…* diffs two versions term by term. No pull requests |
| Task board (Kanban) | ✅ | ➖ | Belongs in the web app; a desktop plugin is the wrong home |

## 7. Web-application machinery

Not applicable to a desktop plugin — Protégé has no notion of tenants, accounts or sharing.
These stay in the web app, which is why it is retained.

| Feature | Web app | Plugin |
|---|---|---|
| Boards, dashboard, board settings, cloning | ✅ | ➖ |
| Accounts, JWT auth, signup, admin approval | ✅ | ➖ |
| Sharing, invite links, access requests, starring | ✅ | ➖ |
| Notifications, activity log | ✅ | ➖ |
| In-app docs page | ✅ | ➖ (Protégé help) |
| File browser | ✅ | ➖ (the OS) |

---

## What to build, in order

Ranked by value per unit of effort, not by how the list happens to be ordered above.

### Done

| Work | Release |
|---|---|
| Live collaboration client + cursors | 1.9.0 |
| ROBOT surface — report, transforms, measure, term import, templates, profile | 1.10.0–1.19.0 |
| ODK surface — Makefile targets, build runner, import resolution, ID ranges | 1.13.0–1.19.0 |
| Git tooling — clone, status, commit, pull, push, branch, release comparison | 1.19.0 |
| Provenance, editorial notes, discussion links, obsoletion | 1.19.0 |
| Frames and sticky notes | 1.19.0 |
| Properties and inferred individual types on the canvas | 1.20.0 |

Most of that shipped under an unchanged version number, which is how this table came to
describe a plugin several releases behind the one being downloaded.

### Next

| # | Work | Why it is next |
|---|---|---|
| 1 | **Pull requests** | The rest of the git surface ships; opening and reviewing a PR still means leaving Protégé |
| 2 | **SPARQL** | The scaffold writes `src/sparql/check_labels.rq` and the generated build now runs it — the plugin still cannot |
| 3 | **ODP pattern library** | Distinctive, and `patterns-repository/` is already here |
| 4 | **Quality** — OOPS!, OQuaRE | Rounds out the report view |
| 5 | **Widoco, export formats, prefix editing** | Useful, not blocking |
| 6 | **Entity locking, threaded comments** | After live collaboration has been used in anger |
| 7 | **Collaboration vocabulary** | The nine axiom kinds the live protocol cannot carry |

**Deliberately not building:** anything marked 🧩 (Protégé already provides it — duplicating
mature plugins is waste) or ➖ (web-application machinery with no desktop meaning).

## Honest caveats

- **ROBOT-dependent features run on both supported hosts** as of 1.26.0, and the startup
  self-check says so in each one's own log. This entry previously said they needed Protégé 5.6.x
  because "the report, SPARQL query and export paths fail on an OWL API incompatibility", which
  was wrong three times over: the report failed on *both* hosts and for an OSGi reason, export
  has no RDF-layer dependency at all, and SPARQL query is not implemented, so it cannot fail.
  What the OWL API version does still rule out is listed in [limitations](limitations.md).
- **Collaboration is verified in pieces, not end to end.** The bridge's side is proved against a
  real `Y.Doc` and real sockets; the client's against a real WebSocket server; the loop guard
  against a host that re-fires its change listener the way Protégé does. What no test covers is
  "two people see each other's cursors", which needs a server, a browser and Protégé at once.
- **No plugin UI is covered by tests.** 1114 tests cover logic; every Swing surface is verified
  by hand.
