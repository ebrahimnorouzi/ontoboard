# Feature Parity: Web Application vs Protégé Plugin

What the plugin does, measured against the OntoBoard web application it grew out of. The web
application is no longer in this repository; it is kept at the tag `web-app-final`. Read the
web-app column as a historical yardstick.

**Legend** — ✅ present · 🔶 partial · ❌ absent · ➖ not applicable · 🧩 provided by a plugin bundled
with Protégé 5.6.9

The gap is small: Protégé already provides the OWL editing, reasoners, explanation, DL Query,
SPARQL and SWRL a browser had to build, and a third of the web application was multi-tenant
machinery a desktop plugin does not need. What remains is threaded comments and reviewing pull
requests.

## 1. Ontology editing

| Feature | Web app | Plugin | Note |
|---|---|---|---|
| Class, property, individual editing; Manchester syntax; characteristics, chains, restrictions | ✅ | ✅ | Protégé's own editors |
| DL Query | ✅ | 🧩 | `org.coode.dlquery` |
| SWRL rules | ✅ | 🧩 | SWRLTab |
| Custom datatypes, `owl:hasKey`, negative assertions | ❌ | ✅ | Protégé |

## 2. The board

| Feature | Web app | Plugin | Note |
|---|---|---|---|
| Graph canvas, drag, pan, zoom, pinch | ✅ | ✅ | The whole ontology as an outline |
| Find a term, expand neighbours | ✅ | ✅ | |
| Create entities and write axioms from the board | ✅ | ✅ | Six OWLAx readings of an arrow |
| Read axioms back as arrows | ❓ | ✅ | Restrictions, cardinality, `hasValue`, scoped domains |
| Delete axioms from the board | ✅ | ✅ | A definition's arrow refuses deletion |
| Layout algorithms, snap, alignment guides | ✅ | ✅ | |
| Minimap, PNG/SVG/DOT export | ✅ | ✅ | |
| Frames and coloured sticky notes | ✅ | ✅ | Kept in the sidecar, never in a release |
| Editorial notes on the board | ❌ | ✅ | `IAO:0000116`, an axiom that travels |
| Undo: axioms / the board itself | ✅ | ✅ | Protégé's undo / 25 steps per session |
| Selection synced with Protégé, live redraw | ➖ | ✅ | The board is a view over Protégé's model |
| Why is this inferred? | ➖ | ✅ | A justification for a dotted edge |

## 3. Reasoning and quality

| Feature | Web app | Plugin | Note |
|---|---|---|---|
| ELK, HermiT, JFact, Pellet; explanation; consistency | ✅ | 🧩 | Bundled with Protégé |
| Konclude | ➖ | ✅ | *Check and test → Reason with Konclude…*, downloaded on request |
| ROBOT report | ✅ | ✅ | All 32 rules, checked against real ROBOT |
| Explanation of unsatisfiability | ➖ | ✅ | *Check and test → Explain…* |
| OOPS!, OQuaRE, unused or circular imports | ✅ | ❌ | Not built |
| SHACL validation | ❌ | 🧩 | `shacl4protege` |

## 4. ODK pipeline

| Feature | Web app | Plugin | Note |
|---|---|---|---|
| ODK project creation | ✅ | ✅ | ODK v1.6.1's own seed |
| Open, update, carry over a project | ✅ | ✅ | *Project → Update project files…* is ODK's own update |
| Build and release, with nothing installed | ❌ | ✅ | ODK's own targets, run by OntoBoard itself |
| Build in ODK's container, Podman, a native ODK | ❌ | ✅ | |
| Build error explanations | ✅ | ✅ | The failing check, rule and terms |
| An ontology and its history as an ODK repository | ❌ | ✅ | *Project → Turn an ontology into an ODK repository…* |
| Import resolution, catalog, ID ranges | ✅ | ✅ | |
| `odk.yaml` editing | ✅ | ✅ | *Project configuration…*, with ODK's own descriptions |
| Documentation site | ❓ | ✅ | ODK's docs workflow; Widoco pages |

## 5. Data and reuse

| Feature | Web app | Plugin | Note |
|---|---|---|---|
| Pattern library | ✅ 13 | ✅ 162, plus your own | |
| CSV/TSV → ROBOT template | ✅ | ✅ | *Spreadsheets → Template…* |
| Terms from another ontology, by label | ❓ | ✅ | Every label and synonym |
| Which terms came from where | ❌ | ✅ | *Imported terms…* |
| A competency question as a test | ❌ | ✅ | *Check and test → Write a test from a question…* |
| SPARQL, results as TSV | ✅ | ✅ | |
| Release diff, term- and axiom-level | ➖ | ✅ | *Build and release → Compare releases…* |
| Bulk IRI rename, materialized relations, term export | ➖ | ✅ | |
| Export formats, prefix editing | ✅ | 🔶 | |
| Provenance (Dublin Core) | ✅ | ✅ | Stamped for edits anywhere in Protégé |

## 6. Collaboration

| Feature | Web app | Plugin | Note |
|---|---|---|---|
| Real-time editing, cursors, presence | ✅ | ✅ | Against a server the team runs |
| Locking | ✅ | 🔶 | An advisory *editing* badge |
| Comments, @mentions | ✅ | 🔶 | Notes and discussion links; no threads |
| Git: status, commit, pull, push, branch, pull requests | ❌ | ✅ | Reviewing a pull request stays in the browser |

## 7. Web-application machinery

Boards, accounts, sharing, invitations, notifications, an admin page and a file browser have no
meaning in a desktop plugin (➖).

**Deliberately not built:** anything marked 🧩 or ➖.
