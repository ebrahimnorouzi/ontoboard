<!--
  Written for: a reviewer, or anybody who wants to check a number before believing it.
-->

# Measurements

Every figure this project quotes about a real knowledge graph, with the command that produces
it. The commands run the shipped code - not a copy of it, and not something written for the
benchmark - so a number here is the number the plugin would give you.

## What you need

A clone of the repository, a JDK 8 or later, and Maven. No Protégé, no display, no `make`, no
container.

```
git clone https://github.com/ebrahimnorouzi/ontoboard
cd ontoboard/protege-plugin
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mvn -q -DskipTests compile
```

That writes a classpath file once. Every command below is then:

```
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.bench.Bench <command> <input>
```

On Windows the classpath separator is `;` rather than `:`.

### Why not just the plugin jar

Because it would be a lie to say it works. The released jar is a Protégé plugin, and Protégé
provides the OWL API - so the dependency is `provided` and the jar does not contain it, because a
second copy of the OWL API inside an OSGi framework breaks the plugin in the host. `java -jar
ontoboard.jar version` and `--help` work; anything that reads a sheet needs the OWL API, and the
jar tells you so and prints the two commands above rather than failing with a
`ClassNotFoundException`.

Assembling the missing pieces out of a Protégé install was tried and does not work either: the
bundle embeds logback 1.5.6 against slf4j-api 1.7.12, which resolves only because Protégé's OSGi
framework wires `org.slf4j` to its own 2.x. That is not a configuration anybody should pretend
into existence for a benchmark.

### The input

The figures below are from the NFDI-MatWerk knowledge-graph workbook, which is not ours to
redistribute - ask [NFDI-MatWerk](https://nfdi-matwerk.de/) for it, or run the commands against
your own workbook or folder of ROBOT templates. A small workbook used by the test suite lives at
`protege-plugin/src/test/resources/workbook/sample.xlsx` if you only want to see the shape of the
output.

The input is never written to. An `.xlsx` is expanded into a temporary folder which is deleted on
the way out, and `repair` changes only the copy in memory. There is no clock and no randomness
anywhere in it, so running a command twice gives identical bytes - a difference in the output is a
difference in the software or in the data, never in the run.

## How big the corpus is

```
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.bench.Bench corpus "UniqueIds(8) - cleaned.xlsx"
```

```
# ontoboard 1.120.0 bench corpus UniqueIds(8) - cleaned.xlsx
measure	value
tabs read	35
robot template tabs	28
other tabs	7
data rows in template tabs	8438
data rows in other tabs	253
non-empty cells in template tabs	36512
non-empty cells in other tabs	912
identifiers	8411
identifiers with a label	7635
tabs that could not be opened	0
```

Twenty-eight of the thirty-five tabs are ROBOT templates - a template row naming `ID` or
`LABEL`. The other seven are a project's own working notes, counted apart rather than quoted as
templates. "Non-empty cells" rather than "cells", because a spreadsheet's extent is whatever
somebody once clicked in.

The `.xlsx` reader behind this was checked cell by cell against openpyxl on eight workbooks -
48,813 cells, exact agreement, no disagreements of any kind. A reader of somebody else's format
should be held to somebody else's reading of it.

## What the audit finds

```
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.bench.Bench summary "UniqueIds(8) - cleaned.xlsx"
```

```
# ontoboard 1.120.0 bench summary UniqueIds(8) - cleaned.xlsx
kind	findings	carrying a replacement
AMBIGUOUS_NAME	23	0
DUPLICATE_ID	83	0
NEAR_DUPLICATE	184	0
UNRESOLVED_REFERENCE	269	7
WHITESPACE	135	135
TYPOGRAPHIC_CHARACTER	7304	7304
MISSING_ID	1	0
PLACEHOLDER	10	0
UNUSED_COLUMN	22	0
CONFLICTING_DEFINITION	40	0
TOTAL	8071	7446
```

`audit` prints the same findings one per line, with the cell, the suggestion and the message;
`conflicts` prints only the cross-sheet contradictions.

Those forty are worth a word, because the honest number was nearly very different. 1,639 of the
6,688 identifiers are the subject of rows in more than one sheet, which is the intended way to
use ROBOT - it merges what the templates assert, so one sheet can give a thing its name and
another its affiliation. Reporting all 1,639 would have been a list of 1,619 non-problems.
Twenty contradict: one identifier declared as two different types, in two files neither author
can see from the other. None of the 1,639 disagrees about a *label*, and that is a measurement
rather than a lucky absence - 1,627 of them carry a label on both sides and every one matches.

## What applying the suggestions does

```
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.bench.Bench repair "UniqueIds(8) - cleaned.xlsx"
```

```
# ontoboard 1.120.0 bench repair UniqueIds(8) - cleaned.xlsx
kind	before	after	after a second pass
AMBIGUOUS_NAME	23	23	23
DUPLICATE_ID	83	83	83
NEAR_DUPLICATE	184	184	184
UNRESOLVED_REFERENCE	269	262	262
WHITESPACE	135	0	0
TYPOGRAPHIC_CHARACTER	7304	0	0
MISSING_ID	1	1	1
PLACEHOLDER	10	10	10
UNUSED_COLUMN	22	22	22
CONFLICTING_DEFINITION	40	40	40
TOTAL	8071	625	625
cells changed	7406
passes before nothing more changed	2
left alone, changed by something other than this fix	0
no replacement to apply	625
cells a second run would change	0
one undo for the batch	apply the audit's suggestions
```

The third column is the point. A repair that keeps finding work to do is a repair fighting
itself, so the number worth quoting is not "7,406 cells changed" but "625 left, and a second run
changes nothing". Two passes, because forty cells carry two findings each and the second
finding's replacement was computed from text the first had already replaced.

What is left is only the kinds no replacement can settle: duplicated identifiers, near-duplicate
names, cross-sheet contradictions, ambiguous names, unused columns, placeholders, a missing
identifier, and 262 references that name nothing. A tool that picked which of two duplicated
entities to keep would eventually pick wrong, silently, in somebody's published graph.

## Whether a cell survives the trip to ROBOT

```
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.bench.Bench roundtrip "UniqueIds(8) - cleaned.xlsx"
```

```
# ontoboard 1.120.0 bench roundtrip UniqueIds(8) - cleaned.xlsx
measure	value
cells compared	100498
cells that changed	0
tabs ROBOT could not read	0
```

Every cell of the workbook, compared against what ROBOT's own reader gets back out of the `.tsv`
files written for it. This is the measurement that found the defect 1.120.0 fixes: before it,
the same command reported **20,795 cells changed and two tabs ROBOT could not read at all**,
because thirteen of the thirty-five tabs hold 404 straight double-quote characters and one
unbalanced `"` makes OpenCSV swallow the rest of the file. 2,711 data rows - 31% of the graph -
were not in the editor, and nothing anywhere said so.

It exits 1 if a single cell changed, so it can gate a build.

## The assistant benchmark

The assistant's benchmark compares models on the same cases and writes a table. It has five
tasks: explaining a reasoner problem, asking for a missing term, writing a definition,
repairing a problem in a loop where the reasoner checks each removal, and writing a sentence as
an axiom that the reasoner compares with the one held back. It is run the same way,
from this classpath, with
`de.fizkarlsruhe.ise.ontoboard.assistant.bench.AssistantBench`. The cases, the ontology snapshots
they were made from, what each score means and how to run it are in
[benchmarks/assistant](https://github.com/ebrahimnorouzi/ontoboard/tree/main/benchmarks/assistant).
Keys come only from environment variables and are never written to its output.

## ODK in Docker and OntoBoard, side by side

The same tasks, on fresh copies of the same small projects, run two ways: with ODK's own program
in ODK's Docker image (v1.6.1), and with OntoBoard's own code in one Java process, without a
container - as inside Protégé. For each task the two results are compared - the files written,
the reports, the built ontologies - and must agree: the test fails at the first line in which
they differ.

| Task | Project | ODK in Docker, s | OntoBoard, s | Agree |
|---|---|---|---|---|
| New project | eggboil | 11.3 (8.0-18.3) | 4.2 (1.5-5.2) | yes |
| Update project files | egg-changed | 13.7 (12.0-29.6) | 3.0 (2.7-5.5) | yes |
| Refresh imports | loim | 39.3 (28.1-47.0) | 7.5 (4.9-12.6) | yes |
| Refresh one import | loim | 31.5 (21.4-36.0) | 4.0 (2.5-7.7) | yes |
| Test, fast | egg | 73.3 (61.2-103.7) | 5.2 (3.2-9.1) | yes |
| Test, full | egg | 56.9 (50.1-98.5) | 1.1 (1.0-6.5) | yes |
| Release, fast | egg | 49.0 (45.6-53.1) | 1.5 (0.91-2.3) | yes |
| Release, full | egg | 60.2 (53.4-66.9) | 2.3 (1.2-3.3) | yes |
| Clean | egg | 1.9 (1.3-2.3) | 0.08 (0.07-0.21) | yes |

Each time is the median of five runs, after one that is not counted, with the lowest and the
highest in brackets: the wall clock from the start of a task to its end. ODK's include starting a
container for every call; OntoBoard's do not include starting Java, which Protégé has already
started. In every task OntoBoard's slowest run took less time than ODK's fastest.

Measured on 15 November 2026 on one Windows 11 laptop with eight logical processors, Docker
29.9.3 and Java 11, while other programs kept its processors 93% busy on average. The times say
what that machine gave that day: on a quiet machine both columns are smaller, and the gap between
them may be another. The projects are small fixtures, so how the two compare on a large ontology
is not measured here. ROBOT is 1.9.8 inside OntoBoard and 1.9.10 in the image, and the imports
were read from local files, so nothing was fetched over the network.

With Docker running and ODK's image on the machine:

```
cd protege-plugin
mvn test -Dtest=SideBySideLiveTest -Dsidebyside.runs=5
```

It writes `target/side-by-side/report.md` - this table, the machine, and what the run does not
show - and the numbers in `target/side-by-side/results.csv`.

The figures above are 1.153.0's. This run was not made again for 1.154.0: it needs a quiet
machine, and the machine 1.154.0 was made on was never quiet while it was made, so no timing of
1.154.0 is quoted on this page.

## What OntoBoard's own engine runs of ODK's 79 sample configurations

ODK keeps 79 sample configurations of its own: the configurations of real projects, and those of
its tests. Each is seeded by OntoBoard from ODK's templates as ODK seeds it - `SeedCorpusTest`
holds every file to ODK's own seed - and every target a person would run is asked of OntoBoard's
own engine as it is asked before a run: will it run here, and if not, every reason why. Nothing
is run and nothing is downloaded. The answers are a table in the repository,
`protege-plugin/src/test/resources/odk/engine-reach-79.txt`, one row per configuration and
target, which the test holds row for row:

```
cd protege-plugin
mvn test -Dtest=EngineReachCorpusTest
```

At 1.154.0, by target, with 1.153.0's engine asked the same way beside it:

| Target | Run by OntoBoard itself | Stops where ODK's make stops too | Needs ODK's container | At 1.153.0, run |
|---|---|---|---|---|
| `test` | 76 of 79 | 2 | 1 | 50 |
| `test_fast` | 78 of 79 | 1 | 0 | 77 |
| `prepare_release` | 75 of 79 | 2 | 2 | 49 |
| `prepare_release_fast` | 76 of 79 | 1 | 2 | 75 |
| `refresh-imports` | 73 of 75 | 1 | 1 | 72 |
| `no-mirror-refresh-imports` | 67 of 75 | 8 | 0 | 67 |
| `refresh-<id>`, one import of each kind | 98 of 104 | 5 | 1 | 97 |
| `recreate-components` | 15 of 16 | 0 | 1 | 10 |
| DOSDP's targets: `patterns`, `dosdp_validation`, `update_patterns`, `dosdp-docs-*`, `dosdp-matches-*` | 93 of 118 | 0 | 25 | 0 |
| `all_subsets` | 3 of 3 | 0 | 0 | 2 |
| SSSOM's targets: `all_mappings`, `validate_mappings`, `normalize_mappings` | 0 of 3 | 0 | 3 | 0 |
| all | 654 of 710 | 20 | 36 | 499 |

By configuration, for the targets of *Build*, *Release* and *Refresh imports* - `test`,
`test_fast`, `prepare_release`, `prepare_release_fast`, `refresh-imports` and each
`refresh-<id>`:

| | At 1.154.0 | At 1.153.0 |
|---|---|---|
| every one of them run by OntoBoard itself | 74 | 48 |
| stop only where ODK's own make stops too | 3 | 3 |
| need ODK's container | 2 | 28 |

The 26 that moved: the 25 configurations with DOSDP patterns, which needed DOSDP-Tools, ODK's
pattern check and the shell's one-word tests around them - one of them also a word in brackets
the shell has to leave as it is - and one whose module is the project's own
(`module_type: custom`, for which ODK's seed writes a rule into the project's own makefile). Of
the 2 left, one needs SSSOM's programs - `sssom`, `sssom-cli`, `tsvalid` and ROBOT's SSSOM
plugin - and the other `semsql`, for the `db` export.

What each number counts:

- A row is one configuration and one target. `refresh-<id>` is asked for the first import of each
  kind - module type, mirror type, its own address or ODK's, base, gzip, large - 104 rows in place
  of 367, since the others of a kind run the same recipes under another name. Not asked:
  `update_repo`, ODK's update, which OntoBoard makes by its own code, and `public_release`, which
  publishes a GitHub release.
- "Stops where ODK's make stops too": the engine finds, before a line runs, that make itself
  stops there - "No rule to make target". These are faults of the samples themselves - a
  component named without `.owl`, a report on a file named `.owl`, imports merged so that there
  is nothing to refresh - and 8 are `no-mirror-refresh-imports` on a fresh seed, which has no
  `mirror/` yet; *Refresh imports...* downloads such an import instead. Nine of the engine's
  answers, five stops and four runs, were checked by running ODK's own make dry in ODK's image
  when the table was first made, and agree.
- The DOSDP rows count DOSDP-Tools as installed, on Java 11: the 119 MB download *Build...* and
  *Release...* offer. The 25 DOSDP rows that need the container are `update_patterns`, which
  fetches patterns from elsewhere with `wget`, `find` and `cp -r`.
- The table is Java 11's, as in Protégé 5.6. On Protégé 5.5.0's Java 8 a target that writes
  ROBOT's JSON is refused besides, since the library that writes it needs Java 11; that refusal
  is left out of the comparison there.
- These are fresh seeds. A real project's own rules decide for it: of 30 real repositories behind
  ODK's samples that could be read, 26 add rules of their own, 23 of them on ODK's own targets or
  files (counted roughly, by a pattern). The next section has two such projects.

## The same output, sampled

**Ten of ODK's own samples.** `tests__test-minimal`, `test-release`, `test-release-reason`,
`test-release-materialise`, `test-robot-report`, `test-sparql-report`, `test-edit-obo`,
`test-uribase-suffix`, `test-module-star` and `examples__minimal`, each seeded as ODK seeds it,
and each run two ways: `test_fast` and `prepare_release_fast` by ODK's own make in ODK's image
(v1.6.1), recorded once - all 20 runs ended with make's exit 0, and made or changed 7 to 31 files
each, 291 in all - and by OntoBoard's own engine, held against that recording without Docker:

```
cd protege-plugin
mvn test -Dtest=CorpusEquivalenceTest
mvn test -Dtest=CorpusEquivalenceTest -Dowlapi.version=4.5.29
```

Held for each run: make's exit; every recipe line make said, in its order; what ROBOT said of
each check and of its report; the set of files the run made or changed; and each file by its
kind - a report's rows in any order, the profile's verdict, an ontology made along the way as the
same axioms, a product as ODK's file but for what shows the OWL API's version, anything else byte
for byte.

With OWL API 4.5.29 - Protégé 5.6.9's, and ROBOT's in ODK's image - all 20 runs agree. With
4.5.9, Protégé 5.5.0's, every recipe line and every set of files agree, and six runs made files
that differ in three ways, each the older OWL API's way of writing: an OBO header lists its
values in another order, a `Typedef` for a property the header names by a short name has the
property's whole IRI as its id, and where the edit file is OBO, `oboInOwl:id` has no label. The
three are allowed on 4.5.9 only, each narrowly. On Protégé 5.5.0's Java 8 the two releases that
write JSON are refused before they run, and the refusal is what is held there. A second run on
4.5.9 also found that the older OWL API keeps an OBO file it has read open until Java collects
it, which on Windows blocks the next delete - [fixed](fixed.md) in 1.154.0.

**Two real projects with rules of their own.** nfdicore and mwo build their import modules by
rules in their own makefile - ROBOT chains with `query --update`, and in nfdicore's two
`sed -i` substitutions. Brought to ODK v1.6.1 as *Update project files...* brings them, each was
run twice, on two copies, in ODK's container and by OntoBoard's engine: a refresh of each import
a rule of theirs builds, then `test_fast`, each step's files held against the other route's.
They agree - mwo on both OWL APIs, nfdicore on 4.5.29 only: its DCAT import brings SKOS, PROV-O
and DC Terms by http addresses that move to https, which Protégé 5.5.0's OWL API does not follow
([limitations](limitations.md)). With Docker, ODK's image and the network:

```
mvn test -Dtest=RealProjectsLiveTest -Dowlapi.version=4.5.29
```

It keeps each step's time on both routes in `target/real-projects/`; those were taken on a busy
machine, and are not quoted.

**A project with DOSDP patterns.** ODK's own sample with patterns, `tests__test-patterns`: its
`test` and `prepare_release`, and a `test` that builds the imports with the patterns' terms, were
recorded in ODK's image and run by OntoBoard itself with DOSDP-Tools 0.19.3 installed from its
release; the recipe lines and the files agree, on both OWL APIs. It skips itself unless
DOSDP-Tools is installed where OntoBoard puts it:

```
mvn test -Dtest=DosdpEquivalenceLiveTest
```

## The test suite

```
mvn -B clean test
```

5,667 tests at 1.153.0, 0 failures; a few skip themselves where the platform lacks what they
test - a Mac's JDK, an executable bit, a file system that keeps case. The 18 live tests, which
start ODK's container or the collaboration server, skip themselves where ODK's image or Node is
not there. Some tests build the board's windows and need a display: CI runs the suite on Linux
under a virtual one (`xvfb-run -a mvn -B clean test`), and on Windows as it is.

## The host

A plugin that passes its tests and does not start in Protege has not been tested. Every release
is loaded into both supported hosts and the result is written down:

```
pwsh -File protege-plugin/tools/smoke.ps1 -Install "$HOME/Documents/Protege-5.6.9" -SelfTest
pwsh -File protege-plugin/tools/smoke.ps1 -Install "$HOME/Documents/Protege-5.5.0" -SelfTest
```

The receipts are in [protege-plugin/tools/smoke-receipt/](https://github.com/ebrahimnorouzi/ontoboard/tree/main/protege-plugin/tools/smoke-receipt/),
one per version, each naming the Protege build, the OWL API, the JRE and the heap it ran under.
