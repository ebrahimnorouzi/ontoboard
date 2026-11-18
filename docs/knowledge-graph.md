<!--
  Written for: somebody with lists of things - places, people, events - in spreadsheets, who wants
  them as data that uses their ontology, without writing RDF.
-->

# From spreadsheets to a knowledge graph

An ontology says what kinds of things there are; a **knowledge graph** is the things themselves -
this pizzeria, that baker - described with the ontology's terms. OntoBoard builds one from
spreadsheets, inside Protégé.

## Keep the data apart from the ontology

Put the graph in a file of its own that **imports** the ontology release it is made for, with a
`catalog-v001.xml` beside it that maps that address to the release file on disk:

```turtle
<https://example.org/pizzeria/data.owl> a owl:Ontology ;
    owl:imports <https://example.org/pizza/1.0.0> .
```

```xml
<uri name="https://example.org/pizza/1.0.0" uri="../releases/1.0.0/pizza.owl"/>
```

## 1. One sheet per kind of thing

Each sheet is a ROBOT template: your headings, then a row saying what each column is, then one row
per thing.

| ID | Type | Name | Located in |
|---|---|---|---|
| `ID` | `TYPE` | `AL rdfs:label@en` | `I ex:locatedIn` |
| `ex:P_0000101` | pizzeria | Pizzeria Da Luigi | Karlsruhe |

- `TYPE` names the row's class by its label; `I <property>` relates it to another row, by label or
  ID - sheets of one folder may name each other's rows.
- **Declare the IDs' prefix in the graph file** (*Active ontology → Ontology Prefixes*), or every
  row fails with *could not interpret*.
- `AL rdfs:label@en` names the row (`A rdfs:label@en` does not).
- **Type dates and numbers in the cell** - `2026-11-01T18:00:00^^xsd:dateTime` - or in the column,
  `AT dcterms:date^^xsd:date`, not both.

*Spreadsheets → Template...* → *Write me a template to start from* writes a sheet to copy.

## 2. Give every row an identifier

Leave the ID blank on new rows. *Spreadsheets → Sheet editor* opens the project's sheets; select a
sheet and press **Generate IRIs...** - from your ID range, continuing the numbering, a timestamp,
a UUID or the label. It never reuses an identifier. **Save** writes the files.

## 3. Check the sheets

*Spreadsheets → Check sheets...* lists, cell by cell, names that point at nothing or at two
things, leftover placeholders, stray whitespace and curly quotes, with the correction where it is
obvious. Nothing is changed; the sheet editor marks the same problems as you type.

## 4. Build the graph

Open the graph file in Protégé, save the sheets, then *Spreadsheets → Template...*, choose a
sheet and *Add every sheet in its folder*: the sheets are read together, and *Edit → Undo* removes
the lot. A row that cannot be read is listed with its sheet, row and column; run *Check it, change
nothing* first. *File → Save* keeps the result.

*Template...* only ever adds: run it again after changing a label and the graph has both. To
rebuild, undo the earlier run, or build into a fresh copy of the graph file - its header,
prefixes and imports, no individuals.

## 5. Ask it

- *Reasoner → HermiT → Start reasoner*: whether the graph is consistent, and what each thing turns
  out to be - a pizza with a hot topping is a *spicy pizza* where the ontology defines one.
- *Check and test → SPARQL...*: questions, such as which pizzas each pizzeria baked on a day.

## 6. Version it

Give the graph a version IRI - `https://example.org/pizzeria/data/1.1.0` - and keep each release;
it imports the ontology release it was made for, so a reader knows which ontology its data means.
