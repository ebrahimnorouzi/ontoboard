# The sheet editor: what it should do, and in what order

A spreadsheet surface inside Protégé for building a knowledge graph — adding data, editing it,
searching it, and being told what is wrong with it.

Written against a working system rather than from imagination. The MatWerk knowledge graph is
26 tabs of one published Google Sheet, 8,471 data rows, built by an Airflow DAG that runs
`robot merge … template --merge-before` per tab and gates each on `robot explain --reasoner
hermit -M inconsistency`. Every number below was measured on it.

## What is already true, measured

Running all 26 sheets through OntoBoard's engine in the DAG's own order:

| | |
|---|---|
| data rows | 8,471 |
| axioms generated | 36,761 |
| **distinct individuals** | **6,771** |
| problems | **0** |
| time | about 7 seconds |
| ELK over the combined 34,773 axioms | consistent, 0 unsatisfiable, 3.0 s |
| HermiT over the same | **no result in 840 s, killed** |

So the engine already builds the whole graph correctly and fast. Adding a row works end to
end: appending one organization gives +4 axioms and +1 individual, declared, typed
`OBI_0000245`, two labels, and absent from the graph built before the edit.

Two things that shape everything below:

- **Whole-graph HermiT does not terminate.** The production pipeline gates each sheet
  separately, and that is evidently the only shape that finishes. Per-sheet reasoning is the
  only honest offer.
- **Checked as data rather than as a build, the same graph has 1,227 findings**, 289 of them
  with an applicable correction. "It builds" and "it is right" are different questions.

As of 1.109.0 the engine for this exists — `TemplateColumns`, `LabelIndex`, `SheetAudit` — and
is reachable read-only through *Knowledge graph ▸ Check sheets…*. Everything below builds on
those three.

---

## Stage 1 — a sheet you can actually edit

The table itself, column-aware. Without this nothing else has anywhere to live.

1. **Open a folder of sheets as tabs.** One tab per `.tsv`/`.csv`, the two header rows frozen
   and visually distinct from data, the author's own heading shown above ROBOT's spec.
2. **Column-aware cell editors.** A reference column gets a completing combo; a literal column
   gets a text field; a language column shows its tag; a typed column validates against its
   datatype. `TemplateColumns` already knows which is which.
3. **Add row, add N rows, duplicate row, delete row** — with Protégé's undo covering the lot.
4. **Fill down and fill series.** The spreadsheet staple, and the MatWerk `temporal` sheet is
   171 rows of `temporal region <n>`, which is exactly this.
5. **Paste a block from the clipboard.** TSV in, multiple cells out. People will paste from
   Excel whatever else exists.
6. **Live per-cell markers.** Re-running the audit on the edited sheet is milliseconds, so a
   bad cell can be underlined as it is typed rather than at build time.
7. **Sort and filter a view without reordering the file.** Sorting a template in place would
   change the file for everybody else in git; sorting the view would not.

## Stage 2 — completion and reference resolution

What makes a reference column usable at all. `LabelIndex` already answers every query here.

8. **Autocomplete a name across the ontology and every open sheet.** 6,058 names indexed from
   the MatWerk sheets; typing `nims` offers the right four. Case- and punctuation-insensitive,
   so `fraunhofer gesellschaft` finds `Fraunhofer-Gesellschaft`.
9. **Show where each candidate comes from**, as `organization row 57` or `the open ontology`,
   because two candidates with one name is the normal case here.
10. **Refuse to guess when a name is ambiguous.** 30 names mean more than one thing. The
    picker should make the author choose and write the IRI, not pick for them.
11. **Show the IRI behind the name** on hover, and offer *insert the IRI instead of the name* —
    the only permanent fix for an ambiguous reference.
12. **Go to definition.** Jump from a reference to the sheet and row that defines it.
13. **Find references to this thing.** The reverse: every cell in every sheet that names it.
    Needed before anyone dares rename or delete a row.
14. **Search across all sheets** by name, by IRI, or by cell text, with results as a list that
    navigates.

## Stage 3 — creating the thing you just referred to

The feature you asked for specifically: a person's name and family name, typed into a column
that wants an individual, should be able to become one.

15. **Create the referenced thing on the spot.** When a reference column holds a name that
    nothing defines, offer *create it* — in the sheet that already holds things of that kind,
    with the type its `TYPE` column uses, a minted identifier, and the name as its label. Then
    put the name back in the original cell, now resolvable.
16. **Pick which sheet it goes in.** Derived rather than asked: the sheet whose rows are
    already the range of this property, or whose `TYPE` matches. Only ask when the answer is
    genuinely open.
17. **Split a compound cell into several things.** `(a) Felix Fritzen; (b) Mauricio Fernández`
    is a real `req_2` cell. Offer to split it on its separator and make one individual per
    part, each with its own identifier — which is what a `SPLIT=` column means anyway.
18. **Promote a literal to a thing.** A cell that is text today but ought to be an entity
    tomorrow: mint an identifier, move the text to its label, change the column kind, leave a
    note saying what moved.

## Stage 4 — identifiers

19. **Mint into the ID column**, with the strategy chosen per sheet: the project's
    `-idranges.owl` block, base IRI plus counter, base plus timestamp, or a UUID of the label.
20. **Say what each strategy costs.** This is the part a tool usually hides. A timestamp is
    not stable across re-runs; a UUID-of-label changes the moment a typo in the label is
    fixed; a counter collides when two people mint at once — MatWerk's own scheme is
    `base + 13-digit millisecond + counter`, which collides only if two editors mint in the
    same millisecond. Idempotence is the whole question: re-running a build must not mint a
    second node for one thing.
21. **Never mint during a build.** Minting is an explicit action that writes identifiers back
    into the sheet and reports what it wrote; a build that mints is a build whose output
    depends on when it ran.
22. **Reserve a block** so two people editing the same sheet in git do not collide.

## Stage 5 — cleaning, which is where the duplicates are

All of this exists in `SheetAudit` today; the editor's job is to make it actionable.

23. **Apply one correction, or all of a kind.** 289 of the 1,227 findings carry an exact
    replacement — stray whitespace, a non-breaking space, a curly quote, a quoted reference, a
    reference one edit from a real name. *Fix all 145 whitespace findings* should be one
    click, with one undo.
24. **Duplicate review, side by side.** Two rows whose names are one edit apart with different
    identifiers: show both rows, every cell, and what references each — then *merge into the
    left*, *merge into the right*, or *they are different things, stop telling me*. The last
    option matters; without it the check gets turned off.
25. **Ambiguous name review.** For each of the 30, every candidate IRI, where it came from, and
    how many cells reference the name — then rename one, or rewrite the references as IRIs.
26. **Remember what was dismissed.** A judgement that two rows are genuinely different must
    survive the next run, or the list never shrinks.
27. **Placeholder sweep.** 687 cells contain `_name_` or `n/a`. Listing them by column is the
    fastest cleanup available in that data.
28. **Normalise on paste**, optionally: trim, collapse doubled spaces, straighten quotes. The
    settings most of these findings would never have existed under.

## Stage 6 — build, export, reason

29. **Derive the build order from the references themselves.** MatWerk maintains it by hand in
    Airflow, in a different place from where the references are written. Note `--merge-before`
    accumulates, so a sheet's scope is its whole transitive upstream chain — getting that wrong
    is what made an earlier analysis here report 17 false unresolvable references.
30. **Handle a cycle** by building the whole strongly-connected component together and saying
    which cells close it. Two sheets referencing each other is natural for a graph.
31. **Build one sheet, or all of them**, with per-sheet axiom and individual counts.
32. **Export per sheet and combined.** Both were produced in the measurement run:
    `<sheet>.owl` each, `_kg-only.owl`, and `_kg-with-ontology.owl`.
33. **Reason per sheet, not over the graph.** ELK by default — 3 seconds and it finishes.
    Offer HermiT per sheet with a time budget and report a timeout as a timeout rather than
    hanging.
34. **Keep the four artefacts apart** on disk: the template, the sheet-set configuration, the
    generated graph, and the inference verdict. Say which are committed and which are
    gitignored.
35. **Warn before "Check it, change nothing" lies to you.** The safe default cannot validate a
    chained sheet: `role.tsv` alone is 650 problems and 0 axioms, and 4 problems and 2,712
    axioms once its upstream sheets are in. Today the cautious choice reports hundreds of
    errors that would vanish under the irreversible one. That is a trap, not a feature.

## Stage 7 — getting data in and out

36. **Read `.xlsx` directly.** robot-core 1.9.8 has no XLSX reader, and Apache POI is a
    dependency this bundle will not take. An `.xlsx` is a ZIP of XML, and Java 8 has both
    `ZipFile` and a SAX parser — so a reader for the subset that matters (shared strings,
    one sheet per tab, cell values) is writable with zero new jars. Worth checking against a
    file Excel actually wrote before promising it.
37. **Multiple sheets from one workbook**, which is the normal shape of the thing.
38. **Fetch a published Google Sheet** by its publish id and gid, as the MatWerk DAG does, with
    the fetched copy written into the repo so a build is reproducible offline.
39. **Write back** — the hard half, and the one to defer. Minting into a TSV that is then
    re-exported from Google Sheets loses the identifiers. Until write-back is solved, minting
    should warn that the sheet is the source of truth.
40. **Round-trip check.** Read, write, read again, and assert nothing changed. Cheap, and the
    only defence against a lossy reader.

## What I would build first

Stage 1 items 1–3 and 6, Stage 2 items 8–10, Stage 5 item 23. That is an editable sheet with
real completion and one-click cleanup, which is the smallest thing that is better than the
Google Sheet it replaces. Everything else is worth more once that exists and less before it.

Stage 3 item 15 is the one you named and the one with the most leverage per line, because it
removes the only step in this workflow that currently requires leaving the sheet.

## What not to build

A second reasoner path, a second apply path, or a second term-import path. Each already exists
and each has been argued for in its own file; the editor should call them.
