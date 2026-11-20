<!--
  Written for: a reviewer, or anybody who wants to check a number before believing it.
-->

# Measurements

Every figure this project quotes about a real knowledge graph, with the command that produces it.
The commands run the shipped code.

## What you need

A clone, a JDK 8 or later, and Maven - no Protégé, no display, no container:

```
git clone https://github.com/ebrahimnorouzi/ontoboard
cd ontoboard/protege-plugin
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mvn -q -DskipTests compile
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.bench.Bench <command> <input>
```

On Windows the classpath separator is `;`. The plugin jar alone cannot run these: it leaves the OWL
API to Protégé. The figures below are from the NFDI-MatWerk knowledge-graph workbook, which is not
ours to share - ask [NFDI-MatWerk](https://nfdi-matwerk.de/), or use your own workbook;
`protege-plugin/src/test/resources/workbook/sample.xlsx` shows the shape. The input is never
written to, and a run is deterministic.

## How big the corpus is

`Bench corpus "UniqueIds(8) - cleaned.xlsx"`:

```
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

The `.xlsx` reader agrees cell for cell with openpyxl on eight workbooks (48,813 cells).

## What the audit finds

`Bench summary`:

```
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

`audit` prints them one per line; `conflicts` only the cross-sheet contradictions.

## What applying the suggestions does

`Bench repair`: 7,406 cells changed in two passes, 625 findings left - the kinds no replacement can
settle - and a second run changes nothing.

## Whether a cell survives the trip to ROBOT

`Bench roundtrip`: 100,498 cells compared with what ROBOT's own reader gets back from the `.tsv`
files, 0 changed, 0 tabs unreadable. It exits 1 if a single cell changed, so it can gate a build.

## The assistant benchmark

`de.fizkarlsruhe.ise.ontoboard.assistant.bench.AssistantBench`, from the same classpath, compares
models on five tasks; the cases and how to run it are in
[benchmarks/assistant](https://github.com/ebrahimnorouzi/ontoboard/tree/main/benchmarks/assistant).
Keys come only from environment variables.

## ODK in Docker and OntoBoard, side by side

The same tasks on the same small projects, run with ODK's own program in ODK's image (v1.6.1) and
with OntoBoard's code without a container; the results must agree.

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

Medians of five runs (lowest-highest), measured at 1.153.0 on one busy Windows 11 laptop, so not
a benchmark; not measured again for 1.154.0 or 1.155.0. On a quiet machine, with Docker and ODK's image:

```
cd protege-plugin
mvn test -Dtest=SideBySideLiveTest -Dsidebyside.runs=5
```

It writes `target/side-by-side/report.md` and `results.csv`.

## What OntoBoard's own engine runs of ODK's 79 sample configurations

Each of ODK's 79 sample configurations, seeded as ODK seeds it, and each target asked of
OntoBoard's engine before a run; the answers are held row for row in
`protege-plugin/src/test/resources/odk/engine-reach-79.txt` (`mvn test -Dtest=EngineReachCorpusTest`).

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
| DOSDP's targets | 93 of 118 | 0 | 25 | 0 |
| `all_subsets` | 3 of 3 | 0 | 0 | 2 |
| SSSOM's targets | 0 of 3 | 0 | 3 | 0 |
| all | 654 of 710 | 20 | 36 | 499 |

For the targets of *Build*, *Release* and *Refresh imports* together: 74 configurations run every
one by OntoBoard itself (48 at 1.153.0), 3 stop only where ODK's own make stops too, and 2 need
the container - one for SSSOM, one for `semsql` and the `db` export. The DOSDP rows count
DOSDP-Tools as installed; the table is Java 11's, as in Protégé 5.6. These are fresh seeds: a real
project's own rules decide for it.

## The same output, sampled

**Ten of ODK's own samples**, each run with `test_fast` and `prepare_release_fast` by ODK's make
in ODK's image and by OntoBoard's engine, held against each other without Docker:

```
mvn test -Dtest=CorpusEquivalenceTest
mvn test -Dtest=CorpusEquivalenceTest -Dowlapi.version=4.5.29
```

With OWL API 4.5.29 (Protégé 5.6.9's) all 20 runs agree; with 4.5.9 (Protégé 5.5.0's) every
recipe line and file set agrees, and a few files differ in how that OWL API writes OBO.

**Two real projects with rules of their own**, nfdicore and mwo: their import rules and
`test_fast`, in ODK's container and by OntoBoard's engine, agree - nfdicore on OWL API 4.5.29 only
([limitations](limitations.md)). With Docker and the network:
`mvn test -Dtest=RealProjectsLiveTest -Dowlapi.version=4.5.29`.

**A project with DOSDP patterns**, ODK's `tests__test-patterns`: `test` and `prepare_release` agree
on both OWL APIs (`mvn test -Dtest=DosdpEquivalenceLiveTest`, with DOSDP-Tools installed).

## The test suite

`mvn -B clean test`: 6,302 tests at 1.155.0, 0 failures; a few skip where the platform lacks what
they test. The 43 live tests start ODK's container or the collaboration server and skip without
them. CI runs the suite on Linux under `xvfb-run -a` and on Windows.

## The host

Every release is loaded into both supported Protégés:

```
pwsh -File protege-plugin/tools/smoke.ps1 -Install "$HOME/Documents/Protege-5.6.9" -SelfTest
pwsh -File protege-plugin/tools/smoke.ps1 -Install "$HOME/Documents/Protege-5.5.0" -SelfTest
```

The receipts, one per version, are in
[protege-plugin/tools/smoke-receipt/](https://github.com/ebrahimnorouzi/ontoboard/tree/main/protege-plugin/tools/smoke-receipt/).
