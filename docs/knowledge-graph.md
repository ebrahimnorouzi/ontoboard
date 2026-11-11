<!--
  Written for: somebody with lists of things - places, people, events - in spreadsheets, who wants
  them as data that uses their ontology, without writing RDF. The Sheet editor, Check sheets and
  Template were run in Protégé 5.6.9 on a test project before this page was written; the
  identifiers were filled, and the questions answered, by the same code outside Protégé.
-->

# From spreadsheets to a knowledge graph

An ontology says what kinds of things there are and how they relate. A **knowledge graph** is the
things themselves - this pizzeria, that baker, the pizza baked at 18:05 - described with the
ontology's terms. OntoBoard builds one from spreadsheets, inside Protégé.

## Keep the data apart from the ontology

Put the graph in a file of its own that **imports** the ontology, rather than adding individuals
to the ontology. The ontology stays reusable by anyone, the data can change every day without a
new ontology release, and the graph says which version of the ontology it goes with.

```turtle
@prefix ex:  <https://example.org/pizzeria/data/> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .

<https://example.org/pizzeria/data.owl> a owl:Ontology ;
    owl:imports <https://example.org/pizza/1.0.0> .     # the ontology release it is made for
```

Beside it, a `catalog-v001.xml` that maps that address to the release file on disk, so Protégé
opens the graph without the network:

```xml
<uri name="https://example.org/pizza/1.0.0" uri="../releases/1.0.0/pizza.owl"/>
```

## 1. One sheet per kind of thing

Each sheet is a ROBOT template: the first row is your own headings, the second says what each
column is, and every row after that is one thing.

| ID | Type | Name | Located in |
|---|---|---|---|
| `ID` | `TYPE` | `AL rdfs:label@en` | `I ex:locatedIn` |
| `ex:P_0000101` | pizzeria | Pizzeria Da Luigi | Karlsruhe |
| | pizzeria | Pizzeria Napoli Centrale | Naples |

- `TYPE` names the class each row is an individual of - by its label, as the ontology writes it.
- `I <property>` relates the row to another individual - by its label or by its ID. *Karlsruhe*
  above is a row of the cities sheet: a sheet can name the rows of every other sheet in the same
  folder, in any order, and two sheets may name each other. ROBOT's command line and ODK read
  sheets one after another and find a label only in a sheet already read, so there a sheet can
  name by label only the sheets given before it - *Check sheets...* says in which order, and
  which sheets have none. A row named by its ID, without quotes, works in any order: ROBOT
  expands the ID itself.
- **The prefix of the IDs has to be declared in the graph file.** `ex:P_0000101` means something
  only where `ex:` is declared: in Protégé, the *Active ontology* tab → *Ontology Prefixes*, then
  save; in Turtle, `@prefix ex: <https://example.org/pizzeria/data/> .`. Undeclared, every row of
  the sheet fails with ROBOT's *could not interpret 'ex:P_0000101'*. *Template...* names the prefix
  and, when another prefix of the graph already stands for an address ending in that name - `:`
  for `https://example.org/kg/` when the IDs say `kg:` - names it (since 1.134.0).
- `AL rdfs:label@en` gives the row its name in English. (`A rdfs:label@en` looks the same and gives
  no name at all - *Template...* says so.)
- **Dates and numbers**: in an `I <property>` column, write the type in the cell -
  `2026-11-01T18:00:00^^xsd:dateTime`. ROBOT does not take it from the property, so a date typed
  plainly becomes text; *Template...* warns when the property says its values are dates. A column
  that types its values itself - `AT dcterms:date^^xsd:date` - takes the value alone: ROBOT reads
  no type in its cells, so `2026-01-15^^xsd:date` there stays text, *2026-01-15^^xsd:date*, which
  is no date. *Check sheets...* and *Template...* say so (since 1.135.2).

*Spreadsheets → Template...* → *Write me a template to start from* writes a sheet with the
columns most terms need, to copy.

## 2. Give every row an identifier

Leave the ID blank on new rows. *Spreadsheets → Sheet editor* opens the project's sheets by
itself: the first of `templates/` beside the open file, `src/templates/`, `kg/templates/` and
`templates/` at the project root that holds a sheet - [where it looks, exactly](odk-workflow.md).
The toolbar names the folder; **Project templates ▾** offers the others. Select a sheet's tab and
press **Generate IRIs...**. It offers the project's ID ranges, *Continue the numbering in these sheets*,
a timestamp, a UUID or the label itself, previews what it will write, and never mints an
identifier already used in a sheet of the folder, nor an IRI the ontology already uses. It
compares identifiers as they are written: with CURIE IDs such as `kg:PB_0000101` it cannot see the
graph's own individuals, so do not delete a built row from its sheet and then generate again.
**Save** writes the files.

## 3. Check the sheets

*Spreadsheets → Check sheets...* reads the whole folder against the open ontology and lists, cell
by cell: names that point at nothing or at two things, a template placeholder left in a cell,
a type written in a cell whose column already gives one, pasted whitespace, a curly quote where a
plain one was meant - each with the correction where there is an obvious one. Nothing is changed.
The sheet editor marks the same problems where you type.

A name is a row's label - `LABEL`, or `A`, `AL` or `AT` on `rdfs:label` - because that is what
ROBOT resolves a name by. A comment, a definition or a synonym names nothing, so two rows that
share a comment are fine. A name that means two things is listed where the sheets use it: a label
a row is given, or a name a cell refers to. A label that only the imported ontologies give two
terms, and no sheet uses, is not the sheets' concern. *Template...* counts these names the same
way before it builds, and says how many (since 1.135.2).

## 4. Build the graph

Open the graph file in Protégé. *Spreadsheets → Template...*, choose any sheet of the folder and
*Add every sheet in its folder*: the sheets are read together, so their order does not matter,
and *Edit → Undo* removes the lot in one step. *Add the axioms to this ontology* adds the one
sheet - which can still name the rows of the others.
A row that cannot be read in full is listed with its sheet, row and column. The other rows are
built, and so is what that row says outside the failing cell - usually its ID and label - so run
*Check it, change nothing* first, or use *Edit → Undo* after. *Template...* reads the saved sheet
files, not what the sheet editor shows: save the sheets first. *File → Save*.

**What *Template...* does, and what it does not.** It turns the sheets into axioms and adds them to
the ontology open in Protégé - in memory, until *File → Save*. It only ever adds: change a label
in a sheet and run it again, and the graph has the old label and the new one. Nothing links the
graph back to the sheets and nothing keeps them in step: an edit made to an individual in Protégé
does not reach the sheet, and an edit to the sheet reaches the graph only when *Template...* is run
again. To rebuild after changing rows, undo the earlier run (*Edit → Undo*, in the same session)
or build into a fresh copy of the graph file - one that has its header, prefixes and imports and
no individuals. *Build and release → Build...* builds the ontology, from the ontology's own window
(from the graph's window it finds no Makefile), and the Makefile OntoBoard writes has no target
for the graph: the graph is built with *Template...*.

## 5. Ask it

- *Reasoner → HermiT → Start reasoner*: whether the graph is consistent with the ontology, and
  what each thing turns out to be - a pizza with a hot topping is a *spicy pizza* when the ontology
  defines one, without anybody saying so.
- *Check and test → SPARQL...*: questions - which pizzas each pizzeria baked on a day, who baked
  them, in which oven.

## 6. Version it

Give the graph a version IRI and a version - `https://example.org/pizzeria/data/1.1.0` - and keep a
copy of each release, as you do for the ontology. It imports the ontology release it was made for,
by that release's version IRI, so a reader can tell exactly which ontology its data means.

## Measured

On a test project: nine sheets - cities, pizzerias, bakers, their roles, ovens, instants,
intervals, pizzas, bakings - with every name across sheets by label. The generator behind
*Generate IRIs...* filled 16 blank identifiers, each sheet continuing its own numbering; *Check sheets...* had nothing to
report; *Template...* added the nine sheets in order, 105 axioms and 25 individuals; HermiT found
the graph consistent and one of the three pizzas spicy, from its type and the class hierarchy
the ontology release states.
