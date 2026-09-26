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
| Graph canvas, drag, pan, zoom | ✅ | ✅ | JGraphX. Since 1.58.0 the zoom has a readout in the status bar, clickable for 100%, and a *Fit* |
| Snap and align | ❓ | ✅ | Since 1.66.0 a visible 20px dot grid. Snapping had been on since the first version at an invisible 10px step, which is sixteen candidate columns across one node |
| Keyboard zoom and selection | ❓ | ✅ | Since 1.66.0: Ctrl+0 actual size, Ctrl+1 fit, Ctrl+2 frame the selection, Ctrl+± zoom, Ctrl+A select nodes, Ctrl+D copy notes and frames |
| Sticky notes in colours | ✅ | ✅ | Since 1.66.0. The model, the sidecar field and the renderer had all been in place since notes existed; nothing ever wrote the colour, so every note was yellow |
| Undo on the canvas | ❓ | ✅ | Since 1.62.0, for what the board owns - arranging, adding, removing, moving, notes and frames - with Ctrl+Z, 25 steps, per session. It never touches axioms, which are Protégé's own Edit > Undo, and the status line says so on every undo |
| Draw imported terms | ❓ | ✅ | Since 1.61.0. Faded, said so on hover and in the legend, and findable by the Find box. Before that, dragging `bfo:continuant` onto the board did nothing and left no trace. The web app has no import handling to compare against |
| Expand a term's neighbours | ✅ | ✅ | Since 1.59.0 the new terms are placed in a ring around the source, counted in the status bar, saved, and collapsible. Before that they were laid in a row at the top of the board and nothing was saved or reported |
| Find a term on the board | ✅ | ✅ | Since 1.58.0. Matches label and IRI, ranked so an exact name beats a longer one containing it; centres and selects the match, which also selects it in Protégé. Ctrl+Enter adds a match that is in the ontology but not on the board. Before this there was no search at all |
| Create entities on canvas | ✅ | ✅ | Double-click empty canvas, or drag from the entity trees |
| Write axioms from the canvas | ✅ | ✅ | Plugin is **better** on the axiom: six OWLAx readings vs the web app's `rdfs:domain`/`range`, which silently intersects domains. Since 1.60.0 it also matches on the gesture - hover a term, drag from its handle, and a popup at the drop point offers only what those two ends can legally assert. The node menu still has both paths. Until 1.52.0 this row said "draw edges" while `setConnectable(false)` sat in `SchemaCanvasView`, which sent people hunting for a handle that was not there |
| Delete axioms from canvas | ✅ | ✅ | With a confirmation distinct from removing from canvas |
| Layout algorithms | ✅ | ✅ | Hierarchical, organic, circle, grid. Since 1.64.0 the hierarchy runs the right way up - superclass above subclass - with orthogonal routing, loose terms parked under the diagram, and the result fitted to the window |
| The canvas can be reviewed without launching Protégé | ❌ | ✅ | Since 1.64.0. `CanvasDesignProofTest` renders the board, a 31-term board, the legend and the empty state to `target/design/` headlessly. Three of the four defects that release fixed were found by looking at those images |
| Minimap, PNG/SVG export | ✅ | ✅ | Since 1.65.0 an exported PNG no longer carries grey label chips - the edge-label backing was the canvas colour, which is wrong on a white page |
| Labels from `rdfs:label`, namespace colours | 🔶 | ✅ | Plugin colours by namespace and prefers labels |
| Selection synced with the editor | ➖ | ✅ | **New capability** — impossible in the web app |
| Live redraw on external edits | ➖ | ✅ | Canvas is a view over Protégé's model |
| Layout persistence | ✅ | ✅ | Plugin keeps it in a sidecar, so the ontology stays byte-clean |
| Frames and sticky notes | ✅ | ✅ | Right-click the canvas; kept in the sidecar, so they never reach a release |
| Editorial notes on the canvas | ❌ | ✅ | **New capability.** Right-click a term for its `IAO:0000116` editor note, read and written in place. The same note the Notes menu writes - an axiom, not a diagram annotation, so it travels with the ontology and reaches a live peer |
| Undo/redo, axioms | ✅ | ✅ | Protégé's own undo, which is stronger than the web app's 50-snapshot stack |
| Undo/redo, the board itself | ✅ | ❌ | **Build.** Arrangement, board membership, notes and frames are not axioms, so Protégé has nothing to undo. Removing twelve arranged nodes is final. Until 1.52.0 the row above covered for this |

## 3. Reasoning and quality

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| ELK, HermiT, JFact, Pellet | ✅ | 🧩 | Bundled reasoner plugins, with incremental and background reasoning the web app lacked |
| Explanation / justification | ✅ | 🧩 | `explanation-workbench`, bundled |
| Consistency checking | ✅ | 🧩 | Protégé |
| ROBOT report | ✅ | ✅ | *ROBOT → Quality report…*, all 32 of ROBOT's rules, on both hosts. Checked against real ROBOT in `obolibrary/odkfull` and by a startup self-check in the host |
| Agreement with the `robot` command | n/a | ✅ | Nine operations — reason, measure, export, extract, diff, verify, explain, template and the report — are run both in the plugin and as the `robot` command inside `obolibrary/odkfull` and required to give the same answer. Only the report was checked this way before 1.48.0 |
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
| OWL 2 DL profile gate in the build | ✅ | ✅ | The generated `make test` runs `robot validate-profile --profile DL`, as real ODK does. It did not until 1.46.0, so a project could drift out of DL with CI green |
| Update an existing project | ➖ | ✅ | *Project → Update project files…* re-renders the generated files of a project that already exists, from its own `-odk.yaml` — ODK's `update_repo`. Previewed first; never touches the ontology, the custom Makefile, the ID ranges, the catalog, the report profile or the SPARQL checks |
| In-host self-test | ➖ | ✅ | *OntoBoard → Run self-test*, and `smoke.ps1 -SelfTest`, actually run nine menu items in Protégé against a throwaway scaffolded ODK project and assert what each reported |
| Diff against the published release | ➖ | ✅ | *Compare releases…* takes a URL for the 'From' side and writes ROBOT's markdown diff to a file — ODK's `release_diff`, which answers what consumers will see change |
| Mirrored upstream ontologies | ➖ | ✅ | *Refresh imports…* keeps downloaded copies under `src/ontology/mirror/` (ODK's layout, gitignored because CHEBI alone is hundreds of MB) so a later rebuild works offline and records what it actually built from |
| Rebuildable import modules | ➖ | ✅ | *Import terms…* now writes `imports/<name>_terms.txt` beside the module, and *Project → Refresh imports…* reports whether every import can be rebuilt from the repository's own lists, then rebuilds them — ODK's `all_imports`/`refresh-imports` |
| Bulk IRI rename | ➖ | ✅ | *ROBOT → Rename IRIs…* moves whole IRIs or a whole namespace, previewed before it applies and undoable in one step. Protégé renames one entity at a time |
| Materialize inferred relations | ➖ | ✅ | *ROBOT → Transform… → Materialize relations*. Where Reason asserts inferred subclass axioms, this asserts inferred existential relations, so a consumer that cannot reason still sees them |
| Axiom-level release diff | ➖ | ✅ | *Project → Compare releases…*, opt-in alongside the term-level table. ROBOT's own `diff`, with labels on, because the two answer different questions |
| SPARQL over the open ontology | ✅ | ✅ | *ROBOT → SPARQL…* runs a query you write, or the project's own `src/sparql/*.rq` checks with the pass/fail convention `make sparql_test` uses. SELECT and ASK only |
| SPARQL results as TSV | ➖ | ✅ | The same dialog writes results to a file — what ODK's `custom_reports` target produces — and one TSV per failing check, as `robot verify --output-dir` does |
| Explanation of unsatisfiability | ➖ | ✅ | *ROBOT → Explain…* names every unsatisfiable class, the axioms behind each, and which single axiom appears in the most justifications. Protégé's explanation workbench explains one entailment you have already selected; this starts from "the reasoner went red" |
| Term table export | ➖ | ✅ | *ROBOT → Export terms…* writes one row per term and the columns you choose, as TSV, CSV, JSON, YAML or HTML. Not in the web application. `xlsx` is the one format the bundle cannot write — see Known constraints |
| ZIP export | ✅ | ➖ | It is a folder on disk |
| Widoco HTML docs | ✅ | ❌ | **Build.** External 39 MB jar, invoked as a subprocess |
| Prefix management | ✅ | 🔶 | Colouring done; editing prefixes not |
| Provenance (Dublin Core) | ✅ | ✅ | `dcterms:contributor`/`created`/`date`, stamped for edits made anywhere in Protégé, not only on the canvas |

## 6. Collaboration

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| Real-time multi-user editing | ✅ | ✅ | Against a server the team runs; see [collaboration](collaboration.md). Verified over a real socket since 1.50.0 - and inert in every release before it |
| Cursor sharing | ✅ | ✅ | Graph-space, so a cursor lands on the same entity at any zoom |
| Presence without moving the mouse | ❓ | ✅ | Since 1.63.0. Selecting a term and joining a board both announce; before that presence came only from mouse motion, so a peer reading the diagram was invisible |
| A peer's node lands where they have it | ❓ | ✅ | Since 1.63.0. The geometry every operation already carried is now read on arrival, for terms this board has no position for |
| Warning when peers edit different ontologies | ➖ | ✅ | Two people whose board id collides otherwise apply each other's axioms in silence. The warning names the peer. It could never fire before 1.50.0 |
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
| 7 | **Collaboration vocabulary** | Measured on pizza v5: 45 of 141 axioms cannot travel - every `EquivalentClasses`, `DisjointClasses`, domain, range, property chain and `ObjectPropertyAssertion`, and property declarations. See `reports/v5/collaboration.md` in the pizza project for the table |

**Deliberately not building:** anything marked 🧩 (Protégé already provides it — duplicating
mature plugins is waste) or ➖ (web-application machinery with no desktop meaning).

## Honest caveats

- **ROBOT-dependent features run on both supported hosts** as of 1.26.0, and the startup
  self-check says so in each one's own log. This entry previously said they needed Protégé 5.6.x
  because "the report, SPARQL query and export paths fail on an OWL API incompatibility", which
  was wrong three times over: the report failed on *both* hosts and for an OSGi reason, export
  has no RDF-layer dependency at all, and SPARQL query is not implemented, so it cannot fail.
  What the OWL API version does still rule out is listed in [limitations](limitations.md).
- **Collaboration is verified end to end as of 1.50.0, and was entirely broken before it.** It used
  to be verified "in pieces, not end to end" - the bridge against a real `Y.Doc`, the client against
  a real WebSocket server, the loop guard against a host that re-fires its change listener. Every
  piece passed. The feature did not work at all: no peer could join any board, because
  `server.mjs` resolved a board on the wrong object and the bridge refused every connection.

  Two pieces that each pass can still be joined together wrongly, and neither piece's tests can see
  it - the bridge's tests inject their own document loader, the plugin's tests inject their own
  transport, so the line between them was never executed. `CollabLiveTest` (6 tests) and
  `collab/__tests__/server-boot.test.mjs` (4) now start the shipped server and speak to it over a
  socket, in both languages. What is still not covered is a *browser* and Protégé at once; Protégé
  to Protégé through the real server is covered.
- **The tab and its views are constructed by the in-host self-test since 1.57.0**, on both
  Protégés - so "the canvas cannot be built under Felix" is no longer an open question, though
  whether it paints correctly still is.
- **No plugin UI is covered by tests.** 1207 tests cover logic; every Swing surface is verified
  by hand.
