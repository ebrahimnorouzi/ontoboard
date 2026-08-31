# Several experts, one ontology, over years

Requirements analysis for the request: *an ontology expert, a ROBOT tool expert, a domain expert, an
ODK expert and several collaborators develop one ontology together, and can see how it evolved
through releases, edits and decisions.*

Produced by putting the question to five expert panels independently — an ontology engineer, a
ROBOT expert, a domain expert who barely knows OWL, an ODK expert, and a governance-and-evolution
expert — each grounded in the plugin's actual source and told what already exists so they could not
propose it again. Every proposal was then given to a verifier whose job was to **refute** it, on
one of two grounds: that it is already implemented in a usable form, or that the claim about
OBO/ODK/ROBOT practice it rests on is wrong.

**68 proposals, 46 survived, 22 refuted.** The refutations are as useful as the survivals and are
recorded in §5.

---

## 1. What the panels agreed on without conferring

The panels never saw each other's output. The overlap is therefore signal, not consensus-seeking.

| Theme | Panels | What it means |
|---|---|---|
| **Compare two releases** | **5 of 5** | Nothing in the plugin can say what changed between two versions of the ontology |
| **Record which upstream release an import came from** | 4 | A module is cut from ChEBI and nothing records which ChEBI |
| **Obsolete rather than delete** | 3 | Deleting a published term breaks every consumer that referenced it |
| **The history of one term** | 2 | *Who made this, when, why, and which release first shipped it* is unanswerable |
| **Curation status** | 2 | No way to say "this term is not finished" that anything can act on |
| **Templates / design patterns** | 2 | Authoring is one axiom at a time |
| **The `base` artefact** | 2 | The scaffold's own YAML promises it and nothing builds it |

Every panel asked for the release diff, in its own vocabulary: the ontology engineer wanted it at
the entailment level, the ROBOT expert wanted `robot diff`, the domain expert wanted *"what changed
since I last looked, in sentences"*, the ODK expert wanted it by term, and the governance expert
wanted *added / obsoleted / redefined / moved*. That is one feature with four audiences.

---

## 2. Fixed immediately: the report profile was running 7 of 32 checks

Not a requirement — a defect the ODK panel found in our own scaffold, and the one finding worth
acting on before anything else.

`OdkScaffold.reportProfile()` wrote seven rules. ROBOT's `ReportOperation.getProfile(path)` builds a
fresh map from that file and **does not merge it with the defaults** — the bundled
`report_profile.txt` is read only when no path is given. Verified in the robot-core 1.9.8 bytecode
by the panel and confirmed here against the jar: ROBOT ships **32** rules.

So a generated project ran seven of them. Both this plugin's quality report and the generated CI
read the same file, so both were equally blind, and nothing anywhere said which checks were not
running. The rules off by omission were precisely the ones that catch what several editors do to
one ontology:

`deprecated_class_reference`, `misused_obsolete_label`, `misused_replaced_by`, `duplicate_label`,
`duplicate_definition`, `illegal_use_of_built_in_vocabulary`,
`multiple_equivalent_class_definitions`, `invalid_entity_uri`

Two more were quietly downgraded: `missing_label` ERROR → WARN, `multiple_labels` ERROR → INFO.

**Fixed.** The scaffold now writes every rule at ROBOT's own severity, with a header explaining that
deleting a line disables a check silently, so a project relaxes a rule deliberately and never by
omission. A test reads robot-core's own bundled profile and fails if any rule is missing — so
upgrading the dependency fails the build rather than silently adding a check nobody runs. Verified
by mutation: dropping one rule fails two tests, both naming it.

---

## 3. The requirements, ranked

Grouped by what they serve. Sizes are the panels' own estimates, checked by the verifier.

### 3.1 Seeing the evolution — the request's own subject

| Id | Requirement | Size | Storage |
|---|---|---|---|
| **EVO-1** | **Compare two releases and say what changed: terms added, obsoleted, redefined, moved.** Every panel asked for this. `robot diff` gives the axiom-level answer; the human-readable roll-up is what four of the five actually described | medium | nothing — computed from two files |
| **EVO-2** | Release notes generated from that diff, written beside the release rather than from memory | small | `releases/<date>/CHANGES.md`, beside the artefact, not in the ontology |
| **EVO-3** | Show the canvas as it was at a chosen release, and what has moved since | medium | nothing |
| **HIST-1** | The history of one term — every commit that touched it, joined to the release it first shipped in | large | nothing — read from git, never accumulated in the ontology |
| **REL-3** | Tie each release to the exact commit that produced it | small | `owl:versionInfo` already exists; the commit goes beside the release |
| **EVO-10** | Know which release the open file descends from, and how far it has moved | small | sidecar |

**Why not in the ontology.** A per-term changelog inside the file would grow without bound, appear
in every release and every diff, and an importer gains nothing from it — the project's own storage
rule. Git already holds *what changed*; the gap is that nothing joins it to *which release* and
*which decision*.

### 3.2 Not breaking consumers

| Id | Requirement | Size |
|---|---|---|
| **OBS-1** | **Obsolete a term instead of deleting it** — `owl:deprecated true`, `IAO:0100001 term replaced by`, the `obsolete ` label prefix — and refuse to delete one that has already shipped in a release | medium |
| **EVO-2b** | A release refuses to silently drop a term the previous release published | small |
| **PROF-1** | Attribute OWL profile violations to the axiom that caused them, and warn at the authoring gesture that leaves EL | small |
| **CUR-1** | Curation status from the IAO value set, and a release that knows what is not finished | small |

**PROF-1 is sharper than it looks, and the verifier corrected it.** ROBOT's measure already reports
EL/QL/RL/DL as booleans, so *"is this EL?"* is answered today. What is missing is *which axiom*.
And this plugin's own `EdgeAxioms.Candidate.FUNCTIONALITY` writes `ObjectMaxCardinality(1 R B)`,
which is **not** EL — while the scaffold's Makefile and this plugin's own release both classify with
ELK, which ignores what it cannot express. So the tool offers a gesture that makes part of the
ontology invisible to the project's own release reasoner, and warns nobody.

### 3.3 Imports that stay honest

| Id | Requirement | Size |
|---|---|---|
| **IMP-1** | Record which upstream release each module was extracted from, and report when upstream has moved | medium |
| **ODK-5** | Report terms the edit file references but no import provides — the seed gap | medium |
| **ODK-2** | Build the `base` artefact the scaffold's own YAML promises | large |

### 3.4 The domain expert

| Id | Requirement | Size |
|---|---|---|
| **DEF-1** | Write a definition and its source without knowing an IAO number | small |
| **DEF-2** | See what a term *means* on the canvas, not only what it is called | small |
| **SAME-1** | Say "these two mean the same thing" without asserting an equivalence axiom | medium |
| **WHO-1** | Attribute the note itself, so a note from 2019 does not read like one from Tuesday | medium |
| **SAFE-1** | Say what a change will do before it is made | medium |

**WHO-1 is a gap this plugin created.** Editorial notes sync between editors and carry no author or
date, so a board's notes are undated anonymous text — while terms beside them carry full provenance.

### 3.5 ROBOT surface still unexposed

`template` (the single most used ROBOT feature in OBO projects), `diff`, `export`, `query`/`verify`
with SPARQL — and the scaffold writes a SPARQL check that nothing can run.

### 3.6 Governance

| Id | Requirement | Size |
|---|---|---|
| **EVO-7** | Provenance for edits made in Protégé's own editors, not only on the canvas | medium |
| **EVO-8** | Tell an editor when their change was overtaken, and by whom | medium |
| **ODK-12** | CI that gates a merge: reasoning test, OWL 2 DL validation, ID range validation | medium |

**EVO-7 is also a gap this plugin created.** Provenance is stamped where the canvas makes the edit.
An edit made in Protégé's class hierarchy or its Manchester syntax editor — which is most editing —
gets none, so the ontology's provenance silently records only what was done through one view.

---

## 4. What this analysis changed about the plan

Three things the panels got right that were not on any previous list:

1. **The report profile defect.** Not a feature request — a live hole in what the tool generates.
2. **The EL/authoring contradiction.** The plugin offers an axiom its own release reasoner ignores.
3. **Two provenance gaps of our own making** — unattributed notes (WHO-1), and provenance only for
   canvas edits (EVO-7). Both are consequences of building the canvas path first.

---

## 5. Refuted, and worth recording

22 proposals did not survive. The instructive ones:

| Proposal | Why it fell |
|---|---|
| *Justify an inference / name who wrote the axiom* | Protégé's explanation workbench already does the first, and the second rests on axiom-level provenance that does not exist |
| *Disjointness, property characteristics, definitions* | Protégé's own editors do this, reachable beside the canvas |
| *Commit, branch and review from inside Protégé* | Implemented earlier in this same session |
| *Link a term to the discussion that decided it* | Implemented earlier in this same session — proposed independently by two panels, both refuted |
| *A release refuses to ship without required metadata* | Premise factually wrong about what OBO requires |
| *Rename an IRI safely* | Protégé's refactor menu already does it |
| *Publish in the formats consumers ask for* | Protégé's Save As already covers them |

**One refutation was itself wrong.** A verifier rejected the ontology engineer's obsoletion
proposal as "already implemented, and reachable". It is not — `grep` for `owl:deprecated`,
`IAO:0100001` or any obsoletion action across `src/main` and `plugin.xml` finds nothing. Two other
panels proposed the same thing and their versions survived, which is what caught it. Adversarial
verification lowers the false-positive rate; it does not remove it, and a single refutation of a
thing three panels asked for deserves a second look.

---

## 6. Order of work

1. **EVO-1 + EVO-2** — the release diff and generated release notes. Five panels, and the request's
   own subject.
2. **OBS-1 + EVO-2b** — obsoletion, and a release that will not silently drop a published term.
   Three panels, and the failure it prevents is irreversible for consumers.
3. **PROF-1** — profile violation attribution and the gesture warning. Small, and it closes a
   contradiction the tool currently has with itself.
4. **WHO-1 + EVO-7** — the two provenance gaps this plugin created.
5. **IMP-1** — import provenance. Four panels.
6. **TPL-1** — ROBOT template. Large, and the biggest single lever for a domain expert.

---

## 7. What no analysis settles

- **Whether the diff roll-up says what a curator needs.** *Added, obsoleted, redefined, moved* is
  the vocabulary four panels used, but which of those matter, and what "redefined" should mean in
  practice, is a question for people running a real release.
- **How much a domain expert should be shielded.** The panel argued for not dumbing anything down
  while removing the need to know IAO numbers. Where that line sits is a judgement about a
  particular team.
- **Whether curation status belongs in the ontology at all.** It passes the storage rule — bounded,
  standard, useful to an importer — but a project that does not use it will find the annotations
  noise.

---

## 8. What was built, and what the building taught

All six items in §6 are implemented and tested. 1014 tests pass from a clean build.

| Id | Where it lives | Tests |
|---|---|---|
| EVO-1 + EVO-2 | `odk/ReleaseDiff.java`, `menu/CompareReleasesAction.java` | `ReleaseDiffTest` |
| OBS-1 + EVO-2b | `odk/Obsoletion.java`, `menu/ObsoleteAction.java` | `ObsoletionTest` |
| PROF-1 | `reason/ProfileCheck.java`, `menu/ProfileAction.java`, the warning in `SchemaCanvasView.createRelationFrom` | `ProfileCheckTest` |
| WHO-1 | `prov/EditorNotes.java` attribution, `menu/AllNotesAction.java` | `EditorNotesTest` |
| EVO-7 | `prov/EditWatcher.java`, `SchemaCanvasView.stampEditsMadeElsewhere` | `EditWatcherTest` |
| IMP-1 | `robot/ImportProvenance.java`, `menu/ImportsAction.java` | `ImportProvenanceTest` |
| TPL-1 | `robot/TemplateSheet.java`, `menu/TemplateAction.java` | `TemplateSheetTest` |

### Five things the implementation found that the analysis did not

1. **A stamp brings its own declarations, and they are not terms.** `EditWatcher` counted the
   annotation property declarations that accompany every provenance stamp as newly created terms —
   so the plugin recorded itself as the author of `dcterms:contributor`, and stamped the
   declarations a stamp had just added. Caught by its own termination test.

2. **An `xsd:date` holding a timestamp.** `EditorNoteAction` passed a full ISO timestamp to a
   helper that builds an `xsd:date` literal, producing `"2026-08-31T09:14:02Z"^^xsd:date` — outside
   its own datatype's value space. Three callers had each rolled their own clock and one had rolled
   it wrong; there is one now.

3. **Computing beats tabulating, demonstrated on the author.** The first `ProfileCheck` test
   asserted from memory that max-cardinality 1 leaves OWL 2 DL. It leaves EL and lands in RL. That
   is the entire argument for asking the profile checker rather than keeping a table of which
   constructs are which.

4. **Checking rows separately invents errors.** Reporting every bad row of a template at once means
   checking rows in isolation — and a row whose parent is a term the sheet introduces two rows later
   then looks broken. A confident, precise, wrong finding is worse than the one real error ROBOT
   gives. The per-row pass is seeded with the whole sheet's terms; a test holds it.

5. **`force=true` tolerates bad rows but not a bad template row.** Established by probe against
   robot-core 1.9.8, along with: the template row can be validated with no data rows present,
   `RowParseException` carries row, column and cell as fields, and ROBOT's row numbers already count
   the two header rows so they match a spreadsheet's own. None of this is documented; all of it
   shapes the design.

### What is still not covered

- **No Swing surface is under test.** Every action's logic is in a Protégé-free class that is
  tested; the dialogs, the tables and the canvas gesture are not, and cannot be without a UI
  harness.
- **OSGi resolution is unverified.** The bundle builds and its manifest is checked, but no Protégé
  installation on this machine has loaded it.
- **The upstream freshness check only sees what is open.** Fetching ChEBI to read one line of its
  header is not something to do behind a user's back, so `ImportProvenance` answers "not checked"
  until they open the upstream themselves. A project that never opens its upstreams never learns
  they moved.
- **Templates go into the edit file, not a component.** ODK keeps template output in
  `src/ontology/components/` under the Makefile's ownership. Writing there from the plugin would
  put two authors on one file, so `TemplateAction` adds to the ontology you have open and says so.
