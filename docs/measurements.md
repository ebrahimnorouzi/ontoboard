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

## The test suite

```
mvn -B clean test
```

2,4xx tests, 0 failures. Run headless, which is how CI runs it and where it belongs:

```
mvn -B clean test -DargLine=-Djava.awt.headless=true
```

## The host

A plugin that passes its tests and does not start in Protege has not been tested. Every release
is loaded into both supported hosts and the result is written down:

```
pwsh -File protege-plugin/tools/smoke.ps1 -Install "$HOME/Documents/Protege-5.6.9" -SelfTest
pwsh -File protege-plugin/tools/smoke.ps1 -Install "$HOME/Documents/Protege-5.5.0" -SelfTest
```

The receipts are in [protege-plugin/tools/smoke-receipt/](https://github.com/ebrahimnorouzi/ontoboard/tree/main/protege-plugin/tools/smoke-receipt/),
one per version, each naming the Protege build, the OWL API, the JRE and the heap it ran under.
