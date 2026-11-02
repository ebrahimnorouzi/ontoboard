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
new ontology release, and each says which version of the other it goes with.

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
- `I <property>` relates the row to another individual - also by its label. *Karlsruhe* above is
  a row of the cities sheet. A sheet can name only what exists when it is built, so build the
  sheets in order: cities before pizzerias, pizzerias before what happens in them.
- `AL rdfs:label@en` gives the row its name in English. (`A rdfs:label@en` looks the same and gives
  no name at all - *Template...* says so.)
- **Dates and numbers**: write the type in the cell - `2026-11-01T18:00:00^^xsd:dateTime`. ROBOT
  does not take it from the property, so a date typed plainly becomes text; *Template...* warns
  when the property says its values are dates.

*Spreadsheets → Template...* → *Write me a template to start from* writes a sheet with the
columns most terms need, to copy.

## 2. Give every row an identifier

Leave the ID blank on new rows. *Spreadsheets → Sheet editor* opens the sheets beside the open
file (`templates/`, or `src/templates/` in a project) - select a sheet's tab and press
**Generate IRIs...**. It offers the project's ID ranges, *Continue the numbering in these sheets*,
a timestamp, a UUID or the label itself, previews what it will write, and never mints an
identifier that is already used in a sheet or in the ontology. **Save** writes the files.

## 3. Check the sheets

*Spreadsheets → Check sheets...* reads the whole folder against the open ontology and lists, cell
by cell: names that point at nothing or at two things, a template placeholder left in a cell,
pasted whitespace, a curly quote where a plain one was meant - each with the correction where there
is an obvious one. Nothing is changed. The sheet editor marks the same problems where you type.

## 4. Build the graph

Open the graph file in Protégé. *Spreadsheets → Template...*, choose the first sheet and
*Add the axioms to this ontology*; then the next sheet. Each sheet is one step for *Edit → Undo*.
A row that cannot be read is listed with its row and column, and the other rows are still built.
*File → Save*.

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
the graph consistent and one of the three pizzas spicy, from the ontology's definitions.
