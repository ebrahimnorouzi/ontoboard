# What six expert users found, and what to do about it

Six personas were walked through OntoBoard against the real ODK projects on this machine —
NFDIcore 3.0.4, MWO 3.0.2, PMDco 3.0.0-rc2, ECTO — and the real MatWerk KG spreadsheet. Every
claim was then put to a separate adversarial pass that checked it against the code and threw
out what did not hold.

**72 findings survived. 49 claims were thrown out.** That ratio is the reason to trust the 72.

This is a list to triage, not a backlog that has been agreed. The ordering below is a
recommendation; the grouping is the part that matters, because the three groups want different
decisions from you.

- **Group A — silent correctness failures.** The tool does the wrong thing and says it
  succeeded. No product decision is needed; these are bugs.
- **Group B — cheap and high value.** Small, self-contained, and each unblocks a persona.
- **Group C — the real features.** These need your decision on scope and order before anyone
  writes code.

Two of Group A were fixed while this evaluation ran, in 1.106.0 and 1.107.0.

---

## Group A — silent correctness failures

### A1. The starter template has never produced a single axiom, anywhere

The worst finding in the set, because this feature is the advertised on-ramp for domain
experts — `TemplateSheet.starter`'s own comment says "the blank page is the real barrier".

`TemplateAction` derives the ID prefix from the last path segment of the ontology IRI, and
`TemplateSheet` hardcodes `0000001`–`0000003`. Measured:

| project | ontology IRI | prefix it derives | result |
|---|---|---|---|
| PMDco | `https://w3id.org/pmd/co` | `co:` — not in its prefix map (it uses `pmd:`) | 3 rows, **0 axioms**, 3 errors |
| NFDIcore | `https://nfdi.fiz-karlsruhe.de/ontology` | `ontology:` | 0 axioms |
| MWO | — | no `mwo:` declared at all | 0 axioms |
| **a fresh OntoBoard-scaffolded project** | — | `editOwl` writes `xmlns=` and no named prefix | **0 axioms** |

So it fails on every real project *and* on the project OntoBoard itself creates. With no
context at all, the same.

**And a test hid it.** `TemplateSheetTest.theStarterTemplateItselfRuns` is green only because
its fixture does `prefixes.put("ex:", NS)`. A false green over a feature that has never worked
is worse than no test, and the same shape of mistake should be looked for elsewhere.

### A2. Rename leaves a reference to an IRI it just deleted

`TermRename.copyOf` rebuilds the copy under the original ontology IRI, and `Plan` carries only
axiom changes — there is no `SetOntologyID`, so an ontology IRI starting with the renamed
prefix cannot move. Measured on an IRI-valued annotation: 6 changes, the subject renamed, and
the **object left pointing at `…/MWO_0001001` while the same plan removes its declaration**.

A correctness bug rather than an omission, and a namespace move is this feature's stated
purpose. `RenameAction` and `TermRename` contain no occurrence of `idranges`, `catalog` or
`yaml`, so the ODK side files are left behind too and nothing says the job is half done.

### A3. Re-running *Import terms…* destroys the existing term list

`ImportModules.writeTerms` builds a fresh header plus `sortedUnique(terms)` and calls
`Files.write` with no read of the file. There is no exists-or-overwrite guard anywhere in
`ImportTermsAction`.

The sentence to fix first is the javadoc at `ImportTermsAction.java:577`, which says re-running
on an existing import is "ordinary — it is how a term list grows". That is false, and it tells
the user the destructive path is the safe one. On MWO's `pmdco_terms.txt` that is 20
hand-commented terms gone without a word.

*Project > Term lists…* is the non-destructive editor, and a user has no way to know they
should prefer it.

**Decision for you:** should re-running merge or replace? The generated file's own comment
("edit it and re-run the import to change what the module contains") argues for replace. Either
way the silence is the defect.

### A4. An empty Base IRI mints terms in a namespace you do not own

`ProjectWizard`'s hint says "Leave Base IRI empty to use the OBO convention".
`OdkProjectConfig.normaliseIri` then defaults to `http://purl.obolibrary.org/obo/<id>.owl`, and
`OdkScaffold.idRanges` turns that into `idprefix http://purl.obolibrary.org/obo/MWO_` — so every
term minted is `http://purl.obolibrary.org/obo/MWO_0001000`.

Three of the four projects here are **not** in the OBO Foundry (measured earlier this session:
nfdicore, mwo and pmdco all 404 against the registry; only ECTO resolves). ROBOT's
`invalid_entity_uri` check specifically approves that shape, so nothing ever flags it, and it
cannot be fixed later without an IRI migration.

Second defect in the same method: `IdRanges.prefixFor` always drops the last path segment, so
the obvious correct answer `http://purls.helmholtz-metadaten.de/mwo` yields `…/MWO_` where real
MWO uses `…/mwo/MWO_`. Only typing the full ontology IRI ending in `<id>.owl` gives the right
prefix, and the field is labelled just "Base IRI".

### A5. An unresolvable prefix in an annotation column writes a broken mapping, silently

A sheet with `AI skos:exactMatch` and the cell `PMDCO:CrystalStructure` reports **0 problems**
and writes `<skos:exactMatch rdf:resource="PMDCO:CrystalStructure"/>` — a relative IRI that
resolves against the document base to a mapping pointing at nothing.

The same bad CURIE in an `SC %` column *is* reported. `TemplateSheet.whyNot` checks prefixes
only for reference columns, and ROBOT's `invalid_entity_uri` examines `?entity rdf:type ?value`
and never an annotation value — so neither the plugin nor the quality gate catches it. Zero
tests mention `exactMatch`.

### A6. Seven patterns collapse onto one module file, and the second import replaces the first

`TermExtract.shortNameOf` returns `"extract"` when a pattern declares no ontology IRI;
`moduleFileFor` and `moduleIriIn` both key on it; `wouldOverwriteAnotherModule` returns null at
`here.equals(mine)`, which is exactly this case; `writeTerms` replaces rather than appends; and
`declareProduct` emits a note, not a warning.

Seven of 163, but they include **all four `bfo-*` patterns** — which are what a BFO-family
modeller reaches for, and which rank 2, 5, 8 and 10 in the suggestions for MWO. Same pass should
fix the adjacent collisions: `actuation-actuator-effect`/`contentops`,
`collection`/`collection-entity`/`collectionentity`, `place`/`spatial-object`,
`simpletopic`/`topic` each share one ontology IRI.

### Fixed while this ran

- **A7 — one bad `I` cell discarded a whole template.** robot-core 1.9.8 raises a bare
  `NullPointerException("object cannot be null")` for an unresolvable reference, in partial mode
  as well as strict, so nothing at all was produced and the problem was blamed on "the
  template". Fixed in **1.106.0**: nine good rows and one typo now give 36 axioms and a problem
  naming the row, the column, the heading and the cell.
- **A8 — a rename pasted from a spreadsheet built an IRI with a tab in it.** A third column
  stayed attached to the second, and the plan declared
  `Class(<…#new⇥renamed for clarity>)` while deleting the real term, reporting one entity
  affected and no unmatched mappings. Fixed in **1.107.0**.

---

## Group B — cheap and high value

### B1. *Compare releases…* refuses on every real project

Zero repositories on this machine have a `releases/<YYYY-MM-DD>/` directory. `data/admin/mwo`
*has* a `releases/` holding `mwo-20260417.owl` — a dated **file** — so the feature refuses on a
project that visibly has a release.

The dialog already carries "Or compare against a published release (URL)", whose own help calls
it "ODK's release_diff… what will consumers see change?" — the exact pre-merge review step — and
the published URL fully replaces the From side, so it needs no local release. It is unreachable
only because `configure()` returns false first.

`ReleaseHistoryTest` only ever creates the layout OntoBoard's own scaffold writes, so the
assumption was never tested against a project OntoBoard did not create. **Moving that guard
below the dialog is a few lines**, and it is the cheapest high-value fix in the list — it also
gives the ODP-extraction persona a two-sided axiom diff today.

### B2. The pattern search misses the pattern, and the website finds it

`PatternLibrary.matching` searches 5 fields. The repo's own `docs/assets/pattern-finder.js`
searches 8. Measured over the same 163 patterns:

| query | plugin | website |
|---|---|---|
| roles over time | **0** | 1 |
| during | **0** | 2 |
| temporal role | 2 | 3 |
| process participant | 5 | 7 |

The single hit the website finds for "roles over time" and the plugin does not is
`nfdi-process-role-realization` — exactly the pattern that persona needed. No stemming either:
"role" 21 vs "roles" 10.

Competency questions are already in `patterns/index.tsv` column 9 and already exposed by
`DesignPattern.getCompetencyQuestions()`. **Adding `q`, collection and publisher to the haystack
is a three-line change.**

### B3. Getting Started sends collaborators to a retired web application

The last section of `docs/getting-started.md` instructs `git clone`, `./run.sh`,
`localhost:3000`, `admin`/`admin`, and promises "Invite others and you will see their cursors
and edits live". Neither `run.sh` nor any compose file exists. `index.md` already says the web
app is gone, and `limitations.md` still carries a "## Web application" section describing the
wrong product.

This is the first document a collaborating pair opens at the moment they ask "how do we
collaborate", and the instructions fail outright. **The fix is deleting two stale sections.**

### B4. The recommender has no test at full coverage, which is why saturation went unnoticed

Re-implementing `score`/`forOntology` over `mwo-full.ttl`: 87 patterns qualify and **all top 15
sit at coverage 1.00** — i.e. patterns MWO already contains in full — while
`time-indexed-participation` ranks 70 of 87 and `participation`/`agent-role`/`co-participation`
rank 41–43 at 0.38.

`PatternRecommenderTest` has 11 tests, every one a synthetic ontology with 3–10 terms, and the
only score assertions are relative. Nothing builds a vocabulary that fully covers a pattern.

The paired fix is cheap: a pattern import lands at `<stem>/imports/<pattern-id>_import.owl`, so
the library can mark an already-imported pattern by reading the open ontology's import
declarations — which is also the long-standing "a 1.00 score may mean you already contain it"
item.

### B5. A pattern's detail pane cannot say what the pattern commits you to

`PatternLibrary.Contents` carries classes, properties, imports and the ontology IRI but **no
axioms**, and `describe()` returns the two counts. So `nfdi-process-role-realization` (4
`rdfs:subClassOf`, 1 `rdfs:range`, **zero restrictions**) and `mwo-process-agent-role` (4
restrictions, 2 equivalences, 3 domains, 4 ranges) both render as "N classes, M properties".

Two findings in one: a UI gap — show the axiom census — and a data gap, in that the `nfdi-*`
patterns under-deliver against their own descriptions, which promise participation via
`RO_0000057`, realization via `BFO_0000055` and temporal bounding via `BFO_0000199`.

---

## Group C — the real features, and the decisions they need

### C1. Mappings — nothing exists, and this is what you asked to have tested

Verified exhaustively: zero occurrences of SSSOM, `skos:exactMatch`, `skos:closeMatch` or
`hasDbXref` in the plugin's main sources. `skos` appears only as a prefix entry and a javadoc
example; the only `oboInOwl` use is obsoletion (`consider`, `replaced_by`). Zero occurrences of
mapping/sssom/exactMatch/xref anywhere under `docs/`. None of the 43 menu entries is a mapping
action.

**So "lots of testing for the mappings feature" has nothing to test yet.** Three personas hit
this independently — the domain expert who cannot record that her "tensile test" is PMDco's
"Mechanical Property Analyzing Process", the collaborating pair who cannot record an MWO↔PMDco
correspondence, and the ODP user who cannot reuse a pattern's terms rather than copying them.

This is the single most-converged gap in the evaluation and the clearest candidate for the next
real feature.

**Decisions:** SSSOM TSV as the on-disk form, or annotation assertions in the ontology, or both?
Does a mapping participate in the quality gate? Does it travel in live collaboration?

### C2. The multi-sheet template — the engine works, the orchestration does not

Important correction to the brief, and to my own earlier conclusion: the engine already handles
this. **13 MatWerk tabs run in DAG order via repeated ADD gave 30,972 axioms and 5 problems.**

Three things are genuinely missing:

1. **No multi-sheet run.** The full 26-tab DAG is 27 dialog round-trips.
2. **Nothing tells you the order, and order is everything.** Same files, same context: wrong
   order 43 problems, DAG order 5.
3. **The safe default cannot validate a chained sheet.** "Check it, change nothing" is the
   default and the honest choice, and it is the one mode that cannot work on a downstream
   sheet, because checking one requires the upstream sheets to have been ADDed first. Measured:
   `role.tsv` alone is **650 problems and 0 axioms** in check mode, and **4 problems and 2,712
   axioms** once its upstream stages are in. So the domain expert's cautious option reports
   hundreds of errors that would vanish had they taken the irreversible one.

Point 3 is the sharpest item in Group C and is arguably a Group A bug wearing a feature's
clothing.

**And a diagnosis problem on top.** `req_1.tsv` with no context gives 45 problems and 123
axioms; with `mwo-full.ttl` as context, 0 problems and 258 axioms. Same sheet, same per-row
confidence, opposite verdict. The message added in 1.106.0 improves placement but tells a user
whose cell is already a full IRI that the column accepts "an IRI, a CURIE … or the exact label"
— unusable advice for the dominant MatWerk failure, where `role`, `people` and `organization`
fail on full NFDIcore and OBI IRIs. It never says the one true thing: *this term is not in your
ontology or its imports, so import it or run the earlier sheets in the chain first.*

**On cross-sheet references, the ground truth corrected me.** I concluded that
`dataportal`'s 17 institute references could not resolve because it never merges `organization`.
They all resolve — `template --merge-before` writes the merged input into its output, so a
sheet's label scope is its whole transitive upstream chain, and `req_2` five steps upstream
holds 5,064 labels.

What is actually wrong is **ambiguity**. 57 institute labels are declared in two sheets; 55
agree on an IRI and 2 do not. `National Institute for Materials Science (NIMS)` has two distinct
IRIs in scope and **9 of those 17 references name that label**. A further 23 labels are
ambiguous inside one upstream sheet alone — VTK, LAMMPS, ASE, "crystal plasticity", "atomistic
simulations" each carry two IRIs. So a cross-sheet label reference resolves to whichever IRI
ROBOT picks, with no warning and no say for the author.

"Report a label matching more than one IRI, naming every candidate and the sheet it came from,
and never resolve it silently" is therefore a requirement with evidence behind it, where "cannot
resolve" was a requirement with a mistake behind it.

**Decisions:** does OntoBoard read exported TSVs under `src/templates/`, or fetch the published
sheet? Does minting write back into the sheet? Keep MatWerk's `base + millisecond + counter`
scheme, or move to ODK id ranges? Does the Airflow DAG eventually read a `sheets.yaml` that
OntoBoard writes? And the real sheets cannot be committed as fixtures as they stand —
`people.tsv` holds 60 named people with e-mail addresses and ORCIDs.

### C3. DOSDP — the mechanism ODK projects actually use for patterns

Zero occurrences of `dosdp` in the plugin's main sources. ECTO declares `use_dosdps: TRUE` and
ships **31 DOSDP YAML files** under `src/patterns/dosdp-patterns/` (35 YAML in total under
`src/patterns/`) plus 28 TSVs. OntoBoard's "Pattern library" is a different thing with the same
name, and `ContributedPatterns` reads only `.owl/.ttl/.rdf/.owx/.omn/.ofn` — so pointing it at
ECTO's `src/patterns`, exactly as the dialog advises, shows 21 stale import modules from
`data/old_modules/` as "your patterns" while hiding all 35 real ones.

Three personas hit this. But note the cheaper intermediate: **`ROBOT > Template` already
specialises** — `C 'has participant' some %` yields
`SubClassOf(ex:0006 ObjectSomeValuesFrom(ex:hasParticipant ex:0003))`, verified against the
1.106.0 jar. So "after importing a pattern, offer a template seeded with its terms and its
restriction columns" is incremental and worth doing before any DOSDP decision.

**A trap to fix either way:** `C ex:hasParticipant some %` fails with ROBOT's misleading "the
term before 'some' must be a property" even with the property declared and the prefix mapped —
robot-core resolves names in class-expression columns **by label, not CURIE**. That column is
the one that applies a pattern, and it is undocumented (`some %` appears nowhere in `src` or
`docs`) and untested (none of `TemplateSheetTest`'s 24 tests uses a class-expression column).

### C4. Find a term by its name

`ImportTermsAction`'s help is "One term per line, as a full IRI or a CURIE". No OLS, BioPortal,
Ontobee or EBI client exists. A domain expert who wants BFO's "material entity" must already
know `BFO_0000004`.

Smaller than it looks: the source ontology is already loaded in the same JVM, and
`TermListAction` already does selection-to-term. The real obstacle is dialog flow — parameters
are collected *before* the source is loaded, so there is nothing in memory to complete against
at the moment the user types.

### C5. No route from an existing ontology into an ODK project

`ProjectWizard` collects id, title, description, IRI, licence and folder; `OdkScaffold` writes a
nine-line edit file with no terms. No action among the 38 adopts an existing ontology. The
nearest paths are *Import terms…* with your own file as the source — which makes your work an
import rather than the project's own content — and `File > Save As` onto the scaffolded edit
file, which nothing suggests and which leaves the ontology IRI and id ranges inconsistent.

This blocks the entire front half for the "I know OWL, not ODK" persona, who is the most likely
new user.

### C6. ODP extraction across ontologies — a negative result, not a feature

The honest answer, and the survey agent and I reached it independently.

**Across NFDIcore, MWO and PMDco, the number of authored restriction shapes shared by all three
is zero.** On the complete `-full` artefacts they share 23, of which 19 have a BFO property and
a BFO filler — they arrive with the imports. Reproducible with the new
`tools/shared-shapes.py`:

```
nfdicore-base.ttl   1,506 triples    22 restriction shapes
mwo-base.ttl          514 triples     4 restriction shapes
pmdco-base.ttl      8,627 triples   236 restriction shapes
shared by all 3: 0
```

So an extractor that mined the complete files and matched axioms would report twenty-three
shared "patterns", every one an artefact of a shared import — a confident, precise, wrong
answer. The three *do* reach the same shapes (the role pattern is in all of them) but through
different vocabulary: `RO_0000087 has role` and `BFO_0000196 bearer of` in NFDIcore and MWO
against OBI's `characteristic of` in PMDco. Exact IRI matching cannot see it.

The corpus is also not independent — MWO's signature contains 100% of NFDIcore's — so
NFDIcore + MWO is one observation, not two.

**Recommendation: do not build the miner.** Build the measurement instead — an evidence report
saying which of your ontologies evidence which pattern, counting upstream-minted and
self-minted terms separately and refusing to call an all-upstream match recurrent. The survey
and the negative result are a publishable contribution and are largely done; the feature is
not.

**Decision:** is the deliverable here a paper or a plugin feature? And is the corpus negotiable
— there is a 43-ontology cultural-heritage corpus on this machine that would make the question
answerable, where these three do not.

### C7. An LLM assistant — build the axiom path, cut the chatbot

Reviewed and cut down to something defensible: **not a chatbot**, but one menu item that drafts
ROBOT template rows for a named class, on your own API key, with the request shown before it is
sent.

The shape is forced by what already exists. The model writes rows into a TSV; it is never asked
for an identifier (`TermMinter` mints from the project's `-idranges.owl`); `TemplateSheet.run`
validates the rows against the open ontology, which is already the hallucinated-reference check,
already tested; and `ROBOT > Template… > Add` applies them through the model manager so one
undo reverses the batch. No new write path into the ontology, no new jar — `HttpURLConnection`
and the Jackson already in the bundle.

A template row is also the only review artefact a domain expert can actually judge: somebody who
cannot read `ObjectSomeValuesFrom(…)` can read `has part | metal matrix`.

**Decisions:** Ollama-only first, or an OpenAI-compatible endpoint (one wire format covering
DeepSeek, Groq, vLLM, LM Studio and Hugging Face)? Is a two-step apply acceptable — draft to
TSV, then run Template — or must one dialog do both? Who is v1 for, which decides whether the
model may draft logical restriction columns at all? And should a machine-assisted term carry an
`IAO:0000116` note, or should attribution live only in the git commit?

---

## What the challenge threw out

Recorded because it is evidence about the other 72. Among the 49 rejected: that OntoBoard is
single-ontology by construction (two actions already load a second ontology); that the
saturation warning is invisible to the user (it is shown); that 14 patterns lack an ontology IRI
(it is 7); and that the MatWerk chain cannot be run at all (it can — 30,972 axioms).

## Suggested order

1. **A1** — the on-ramp has never worked, and a false-green test hid it.
2. **B1, B2, B3** — a few lines each, one persona unblocked apiece.
3. **A3, A4** — the two that quietly cost a user their work or their namespace.
4. **C2 point 3** — the safe default that cannot validate a chained sheet.
5. **A2, A5, A6** — the remaining silent failures.
6. **C1** — mappings, the most-converged gap and the one you asked to test.
7. **C2 proper**, then **C4**, then **C3**'s cheap increment.
8. **C6** as a paper, not a feature. **C7** when you want it.
