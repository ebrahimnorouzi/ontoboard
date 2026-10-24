<!--
  Written for: whoever (person or assistant) picks this up in a fresh session.
  Paste the "Prompt" section as the first message. Everything else is reference.
-->

# Next session: start here

## Prompt to paste

> You are continuing OntoBoard, a Protégé plugin for ODK/ROBOT ontology engineering at
> `C:\Users\eno\Documents\Git_repo\ebrahimnorouzi_github\ISE-FIZKarlsruhe\ontoboard`.
> Read `docs/superpowers/NEXT-SESSION.md` first — it has the house rules, the state, and the
> three jobs in priority order. Then do job 1.

That is the whole prompt. Everything below is in the file, so the session starts small.

## House rules (these are not negotiable, and each one was learned the hard way)

- **Measure, don't reason.** A claim without a number, a `file:line`, or quoted output is not a
  finding. Three times in the last session a confident diagnosis was wrong and a measurement
  settled it in minutes.
- **Comments carry the reason**, including what was tried and failed. Match the surrounding
  density; it is unusually high here on purpose.
- Java 8 source, OWL API 4.5.x (`provided`), JGraphX 4.2.2, JUnit 5. ~2,450 tests.
- Must work on **Protégé 5.5.0 (JRE 8, OWL API 4.5.9)** and **5.6.9 (Temurin 11, 4.5.29)**. Both
  are installed under `~/Documents/`.
- **The working copy is CRLF.** Scripted edits must preserve the line ending git checked out.
- **Bash heredocs collapse doubled backslashes.** Use the Write/Edit tools for anything with
  `\\`, or build the string in Python.
- **The IDE's Eclipse compiler writes into `target/classes`** beside javac's output and produces
  phantom `NoSuchMethodError: access$0`. Authoritative runs go in a clean copy of the repo.
- A shipped-code change needs a **pom bump + `protege-plugin/tools/smoke-receipt/<version>.txt` +
  both host smokes**, or the pre-commit hook refuses it. `tools/smoke.ps1 -Install <dir>
  -Version <v> -SelfTest`; quote every `-D` argument or PowerShell splits it.
- Don't drive the user's desktop with synthetic clicks. They are usually at the keyboard.

## State as of 1.124.0

Released, green, both hosts smoked. Recent work: the pattern library (163 patterns, a worked
example each), canvas panning, click-to-navigate, one-hop = adjacency in the drawn graph,
anonymous class expressions, an editable entity pane, git conflict refusal.

**Jobs 1 and 2 below are DONE** (1.124.0): all six remaining axiom types are drawn, and
unpositioned nodes find free space instead of landing on an arranged term. `docs/canvas.md` is
the current truth about what the canvas draws. Job 3 is what is left.

**Two regressions shipped in 1.123.0 and were fixed in 1.123.1.** Both were invisible to the
suite because the canvas component cannot be built headless. The fix added
`CanvasGestureInteractionTest` (6 tests, skips without a display) and made CI run the suite under
`xvfb-run`. **Before changing anything in `canvas/` or `views/SchemaCanvasView.java`, run that
test class with a display and make sure it still passes.**

## Jobs 1 and 2 — done in 1.124.0, kept here for context

All six axiom types that were hover text are drawn: `DisjointClasses` (29 lines from 15 axioms on
MWO), `InverseObjectProperties` (38), property characteristics (25, as a label suffix),
`SubPropertyChainOf` (3, as a chain node), `ObjectPropertyAssertion` (5), `HasKey`. MWO's full
board is 556 nodes / 730 edges. **SWRL rules are deliberately not drawn** — a horn clause over
variables has no honest node-and-edge form; `docs/canvas.md` says so.

Overlapping is fixed by `canvas/CanvasPlacement.java`: an unpositioned node searches for the
first free slot rather than stepping right from (40,40) blind to the saved positions.

If you add another node or edge kind, it must land in the same commit in: the enum,
`canvas/SchemaStyles.java`, `canvas/SchemaGraph.java`, `canvas/CanvasLegend.java` (switch **and**
an `Entry` — the completeness guard has no catch-all), `canvas/CanvasTooltips.java`,
`canvas/DotExport.java`.

## Job 3 — merge-conflict resolution (the "Mergely" item). THIS IS THE JOB.

`docs/compared-with-ontoeditor.md` §(d) has the design; `docs/conflicts.md` has the survey and a
ranked list. The first piece already exists: `GitRepo.Status.getConflicted()` parses all seven
unmerged codes and `GitAction` refuses to commit while any exist. What is missing, cheapest first:

1. **The editing claim never fires** (~half a day). `CollabSession.editedTerm` wants an IRI and is
   handed `OntologyOperation`'s random UUID, so no claim is ever set and the advisory badge is
   dead. Do **not** just pass `getIri()` — it is the *predicate* for edge operations and the
   literal `"rdf:type"` for class assertions. Add a `subjectIri` to `OperationMapper.Outbound`,
   set only where a single term is the subject.
2. PR mergeability: add `mergeable,mergeStateStatus` to `PullRequest.LIST_FIELDS` (under an hour).
3. A NACK, so a dropped operation tells its sender (`CollabSession.skipped` logs at INFO only).
4. Role enforcement — `role` is parsed in `bridge.mjs` and discarded; anyone who can connect can
   write.
5. A read-only conflict report from `ReleaseDiff`'s six change kinds (2–3 days, best value).
6. Applicable diff objects: neither diff returns `List<OWLOntologyChange>`. Everything above 5
   depends on this.

## Known, deliberate, and not bugs

- The pattern index's content depends on thirteen websites. `PatternIndexTest` names the rows it
  cannot compare rather than failing. Making it hermetic rewrites 97 of 163 entries
  (`affordance`: 237 terms online, 24 offline) — a maintainer's decision.
- 39 patterns declare no object property with both a domain and a range.
- Three patterns have terms in a `file:/C:/Users/...` namespace.
- `.xlsx` is read-only; `.tsv`/`.csv` round-trip.
- No Zenodo DOI, no w3id PURL, not in Protégé's plugin registry, paper audited not rewritten.

## Upstream defects found and not yet reported

BFO's `preceded by` definition reverses the relation (wrong in release-2024-01-29, both profiles,
and master). MWO use cases 2 and 3 have swapped headings; its `developed by` / `adapted by`
comments look swapped. The NFDIcore extension has a reversed `has sparql endpoint` domain, two
unlabelled properties, two obsolete ranges. PMDco's figures bind `tensile_testing_process:` to
the fatigue-testing IRI, figure 8b uses six obsolete terms, and there is a `has part` subproperty
cycle.
