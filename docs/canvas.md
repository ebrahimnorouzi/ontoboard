<!--
  Written for: somebody looking at a board and asking "is that everything the ontology says, and
  where is it?". Measured on MWO 3.0.2 (660 terms with its imports) and NFDIcore 3.0.5 (399 terms).
-->

# What the canvas draws

## What is on a board

Every class, object property, data property and individual of the open ontology, imports
included, up to 1,500 terms, the ontology's own first. Terms an import only brings along are not
drawn: a board shows the terms somebody chose from another ontology, their superclasses, and what
your own classes refer to. *Imported terms* lists the rest, and *Find* draws any of them.
*Hide from board* takes a term off; *More → Show every term* brings them back.

A class's data properties and keys are rows in its box, `name : datatype [n..m]`, and no
property is a box of its own: *More → Properties as boxes* draws them as 1.154.0 did, and
*More → Show unused properties* adds the ontology's own properties nothing uses. The toolbar's
*Properties* lists the ontology's properties in their hierarchy, each with its characteristics and
domain → range. *More → Board settings...* sets each kind's fill, outline and shape, each line's
colour and dash, the fade of imported terms, the arrangement, its direction and spacing - for all
your ontologies, or for this project in its board file.

Every term is named by its label, with its short identifier under it (`measurement datum` above
`obo:IAO_0000109`); the hover has the full IRI.

The board is saved to `<ontology file>.ontoboard.json` beside the ontology from your first change
to it; an ontology opened and closed leaves no file.

## Where things go

The board is an outline (*Arrange → Outline*). It arranges itself again when a term or a pattern
arrives or goes, until you move a box by hand or it holds more than 400 terms; then a new term goes
below its parent, inside its parent's frame, and *Arrange → Outline* gives you the whole
arrangement back. Each automatic arrangement is one step of the board's *Undo*. A frame is moved by
its title; dragging its empty inside moves the board.

| | Where |
|---|---|
| Design patterns the ontology imports or has merged in | a frame each, in rows along the top, holding the pattern's classes as a hierarchy |
| Classes | each class with its subclasses below it, indented; trees side by side in label order |
| A class's restrictions, unions, enumerations | a grey node below the class |
| Individuals | a band below the classes, each under its class, its values below it |
| A property whose domains name one class on the board | beside that class, inside its frame; object properties before data properties |
| Every other property | an *Object properties* or *Data properties* frame, as its hierarchy; inverse pairs side by side |

A pattern merged into the ontology's own file is found by its terms: its frame appears when the
ontology declares at least two of the pattern's terms and they are at least 60% of them. A class two
patterns share is drawn once, in the first pattern's frame, and the second frame's note says where.

Lines are routed around the boxes at right angles, at least 12 px apart, and labels are kept clear
of boxes, other labels and frame names. `PatternBoardsTest` draws every pattern in the library, and
boards of several patterns together: no two boxes overlap and no line passes through a box.

## Description logic

| On the board | Means | Line |
|---|---|---|
| `∃ R` | some: every A relates by R to some B | solid blue |
| `∀ R` | only: whatever an A relates to by R is a B | dashed, diamond head |
| `≥ n R`, `≤ n R`, `= n R` | at least, at most, exactly n | dotted, round head |
| `∃ R.{…}` | has the value: the arrow points at the individual | solid blue |
| `¬ R` | a negative assertion between two individuals | grey, dashed |
| `⊓`, `⊔`, `¬`, `{ … }` | and, or, not, one of - a grey node, its parts hanging off it | |
| `R⁻` | the inverse of R | |
| `≡` before a name, and a disc with three bars on the box's left | a defined class | |

A subclass arrow (⊑) is a hollow-headed solid line; an equivalence is two of them. The legend, *?*
on the toolbar, has every line.

Select a line in Protégé's description of a class or a property - *SubClass Of: ∃
hasTopping.Cheese*, *Domains*, *Inverse Of* - and the board selects the arrows that draw it and
scrolls to them.

## What is drawn

Every logical axiom: 921 of 921 in MWO, 529 of 529 in NFDIcore.

| Axiom | Drawn as |
|---|---|
| `SubClassOf(A B)` | hollow-headed arrow |
| `SubClassOf(A, R some/only/min/max/exactly B)` | arrow labelled `∃ R`, `∀ R`, `≥ n R`, `≤ n R` or `= n R` |
| `SubClassOf(A, B ⊓ …)` | an arrow to each named conjunct |
| `EquivalentClasses` | two hollow arrows, or the definition's arrows both ways |
| Union, complement, enumeration, nested filler | a grey node, its operands hanging off it |
| Domain and range | long-dashed lines from the property's node, labelled `domain` / `range` |
| Disjoint classes or properties | a dashed red line for each pair |
| Sub-properties | an arrow between property nodes |
| `InverseObjectProperties` | a dashed line, arrowheads at both ends |
| Property characteristics | a suffix on the label - `(transitive)`, `(functional)` |
| `SubPropertyChainOf` | a `chain: R o S` node with numbered links |
| `ClassAssertion` | an `rdf:type` arrow |
| Property assertions | an arrow labelled with the property, `not R` for a negative one; a data value in a box of its own |
| `HasKey` | an arrow to each key property, labelled `key` |
| `SameIndividual` / `DifferentIndividuals` | a line labelled `same as` / `different from` |
| `SubClassOf(R some B, A)` | an arrow from A to B labelled `R (scoped domain)` |
| `DisjointUnion(A B C)` | arrows from B and C to A, and a disjointness line between B and C |
| SWRL rule | a node reading the rule, with `if` and `then` lines to its terms |

Annotations are not drawn: they are on the hover and in the left panel, and the label is the
node's name. Class expressions are drawn when *More → Show class expressions* is on; it is off by
default. A rule from an import is not drawn, and the reasoner still uses it.

A test turns every subclass, type and existential arrow back into the axiom it claims and checks
it with HermiT.

## On a large board

Disjointness, domains, ranges and a rule's lines are drawn for the selected term, not all at once;
with *Focus* off they are not drawn. The exception is the line from a property to the class it
stands beside. Selecting a term brings it and its neighbours forward and fades the rest; a click on
empty board ends that.

On a board of more than 150 relations, the other relations and any line longer than 1,600 px are
drawn faintly, as straight connectors, until one of their ends is selected.

*More → Show on the board* switches object properties, data properties, individuals, imported
terms and rules off and on.

## Working on the board

- **Show only this and its neighbours**: right-click a term. *Escape*, or *More → Show the whole
  board*, brings the rest back.
- **Fold** a class with right-click → *Collapse subclasses*; it then shows how many terms it holds.
  *More → Expand every folded class* opens them all.
- **Rules**: double-click a rule's node to read it set out, condition by condition.
- **Definition…**, on the right-click menu of a term your own file declares, writes its definition
  without leaving the board.
- **Keys**: **Tab** makes a subclass of the selected class, **Enter** a sibling; double-click empty
  board for a new class. *?* → *Keyboard shortcuts* lists them all.

## Good to know

- A **grey node is not a term**: it has no IRI, and an arrow ending on one cannot be deleted from
  the board - edit the class's description instead. The same holds for a rule's node.
- The **DOT export** carries every node and edge, grey nodes and rules included.
- A **new node on a board you arranged by hand** goes below its parent or into the first free
  place, and keeps that place.
- **Redrawing** after an edit changes only what changed: 110 ms on MWO after a class is added.
