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

What genuinely remains is the ODK/ROBOT pipeline surface, the pattern library, provenance,
quality reporting, GitHub integration, and live collaboration.

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
| Create entities on canvas | ✅ | ✅ | Double-click or palette drag |
| Draw edges that write axioms | ✅ | ✅ | Plugin is **better**: six OWLAx readings vs the web app's `rdfs:domain`/`range`, which silently intersects domains |
| Delete axioms from canvas | ✅ | ✅ | With a confirmation distinct from removing from canvas |
| Layout algorithms | ✅ | ✅ | Hierarchical, organic, circle, grid |
| Minimap, PNG/SVG export | ✅ | ✅ | |
| Labels from `rdfs:label`, namespace colours | 🔶 | ✅ | Plugin colours by namespace and prefers labels |
| Selection synced with the editor | ➖ | ✅ | **New capability** — impossible in the web app |
| Live redraw on external edits | ➖ | ✅ | Canvas is a view over Protégé's model |
| Layout persistence | ✅ | ✅ | Plugin keeps it in a sidecar, so the ontology stays byte-clean |
| Frames and sticky notes | ✅ | ❌ | **Build.** Small; sidecar format already reserves them |
| Undo/redo | ✅ | ✅ | Protégé's own undo, which is stronger than the web app's 50-snapshot stack |

## 3. Reasoning and quality

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| ELK, HermiT, JFact, Pellet | ✅ | 🧩 | Bundled reasoner plugins, with incremental and background reasoning the web app lacked |
| Explanation / justification | ✅ | 🧩 | `explanation-workbench`, bundled |
| Consistency checking | ✅ | 🧩 | Protégé |
| ROBOT report | ✅ | 🔶 | Core built and tested; **needs a view**. Requires Protégé 5.6.x |
| ROBOT commands | 🔶 7 of 24 | ❌ | **Build.** All 21 `Operation` classes are reachable in the embedded `robot-core` |
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
| Makefile target execution + live log | ✅ | ❌ | **Build.** `ProcessBuilder`; needs `make` on PATH |
| Build error explanations | ✅ | ❌ | **Build.** Pattern-match ROBOT/ODK output |
| Import resolution + catalog | ✅ | ❌ | **Build.** `OWLOntologyIRIMapper` + catalog writer |
| ID range management | ✅ | 🔶 | Scaffold writes the file; **no editor yet** |
| Multi-file / file switcher | ✅ | ➖ | Protégé's own ontology switcher |

## 5. Data integration

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| ODP pattern library | ✅ 13 patterns | ❌ | **Build.** Distinctive and valuable; `patterns-repository/` is already in the repo |
| CSV → ROBOT template wizard | ✅ | 🧩/❌ | Cellfie is bundled and does spreadsheet→OWL. A ROBOT-template-specific wizard is **optional** |
| SPARQL query panel | ✅ | 🧩 | `sparql-query-plugin`, bundled |
| GitHub import | ✅ | ❌ | **Build.** JGit ships with Protégé; also the basis of git-mode collaboration |
| Export formats | ✅ | 🔶 | Protégé exports OWL formats; ROBOT `convert` would add OBO/JSON-LD |
| ZIP export | ✅ | ➖ | It is a folder on disk |
| Widoco HTML docs | ✅ | ❌ | **Build.** External 39 MB jar, invoked as a subprocess |
| Prefix management | ✅ | 🔶 | Colouring done; editing prefixes not |
| Provenance (PROV-O, Dublin Core) | ✅ | ❌ | **Build.** A change listener; small |

## 6. Collaboration

| Feature | Web app | Plugin | Assessment |
|---|---|---|---|
| Real-time multi-user editing | ✅ | ✅ | Against a server the team runs; see [collaboration](collaboration.md) |
| Cursor sharing | ✅ | ✅ | Graph-space, so a cursor lands on the same entity at any zoom |
| Semantic merge engine | ✅ | ✅* | *Reused server-side rather than reimplemented in Java — deliberate |
| Entity locking | ✅ | ❌ | **Build.** Advisory in both |
| Comments, @mentions | ✅ | ❌ | **Build.** REST against the existing backend |
| Git-based collaboration | ❌ | ❌ | **Build.** Branch, commit, push, `ROBOT diff`. How OBO ontologies are actually built |
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

| # | Work | Why it is next |
|---|---|---|
| ~~1~~ | ~~Live collaboration client + cursors~~ | **Done in 1.9.0.** Also fixed a bridge defect that stopped any operation crossing between Protégé and a browser in either direction |
| 1 | **Git mode** (branch, commit, push, `ROBOT diff`) | Serves every team without a server, and it is the ODK release workflow. Git mode is *selectable* today; the in-plugin commit and diff tooling is not built |
| 3 | **ROBOT/ODK views** — report, commands, Makefile targets, live log | The reason Protégé was chosen; `robot-core` is embedded and idle |
| 4 | **ODP pattern library** | Distinctive, and `patterns-repository/` is already here |
| 5 | **Provenance, prefixes, ID ranges** | Small each, and they complete the ODK story |
| 6 | **Quality** — OOPS!, OQuaRE, analysis | Rounds out the report view |
| 7 | **GitHub import, Widoco, export formats** | Useful, not blocking |
| 8 | **Frames and sticky notes** | Cosmetic; the sidecar already reserves them |
| 9 | **Comments, entity locking** | After the collaboration client works end to end |

**Deliberately not building:** anything marked 🧩 (Protégé already provides it — duplicating
mature plugins is waste) or ➖ (web-application machinery with no desktop meaning).

## Honest caveats

- **ROBOT-dependent features need Protégé 5.6.x.** On 5.5.0 the report, SPARQL query and
  export paths fail on an OWL API incompatibility. See [limitations](limitations.md).
- **Collaboration is verified in pieces, not end to end.** The bridge's side is proved against a
  real `Y.Doc` and real sockets; the client's against a real WebSocket server; the loop guard
  against a host that re-fires its change listener the way Protégé does. What no test covers is
  "two people see each other's cursors", which needs a server, a browser and Protégé at once.
- **No plugin UI is covered by tests.** 322 tests cover logic; every Swing surface is verified
  by hand.
