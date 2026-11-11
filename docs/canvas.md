<!--
  Written for: somebody looking at a board and asking "is that everything the ontology says, and
  where is it?". Points and numbers only. Measured on MWO 3.0.2 (660 terms with its imports) and
  NFDIcore 3.0.5 (399 terms), every term on the board, class expressions switched on.
-->

# What the canvas draws

## What is on a board

Every class, object property, data property and individual of the open ontology, imports included,
as soon as the OntoBoard tab opens - up to 1,500 terms, the ontology's own first. *Hide from board*
takes one off and keeps it off; *More → Show every term* brings them back. A board saved by OntoBoard
before 1.131.0 keeps the terms chosen then, and says so.

**Terms an import brings along are not drawn.** Since 1.135.1 a board shows the terms somebody chose
from another ontology, their superclasses, and what your own classes refer to. One class taken from a
large ontology can bring eighty more that it depends on: they are still in the ontology, *Imported
terms* lists them, and *Find* draws any of them.

**Every term is named by its label**, wherever it comes from, with its short identifier under it:
`measurement datum` above `obo:IAO_0000109`. The hover has the full IRI. A term with no label shows its
short identifier alone.

The board is written to `<ontology file>.ontoboard.json`, beside the ontology, from your first change
to it - a move, a hidden term, a note - and not before: an ontology opened and closed leaves no file.
Until 1.133.0 one was written anyway, as Protégé closed or made the ontology active again, and that
file then kept the outline of the day it was written.

## Where things go

The board is laid out as an outline (*Arrange → Outline*). It arranges itself again when a term or a
pattern arrives or goes, until you move a box by hand or it holds more than 400 terms; then a new term
goes below its parent, inside its parent's frame, which grows to hold it, and *Arrange → Outline* gives
you the whole arrangement back, its frames folded into rows afresh, at no less than 50% so it can be
read (*Fit* shows all of it). Dragging the empty inside of a frame moves the board, as dragging empty
board does; a frame is moved by its title. A board saved before 1.135.1 counts as arranged by hand.
Each automatic arrangement is a step *Undo* on the board takes back.

| | Where |
|---|---|
| Design patterns the ontology imports, or has merged in | a frame each, named after the pattern, in rows along the top, each frame keeping its row and place when the board is arranged again. Inside it, the pattern's classes as a hierarchy, as below, with their superclasses that no frame holds, and the classes under them: your own, and those from an import or found and added |
| Classes - the TBox | a class, and its subclasses below it, one under the other and indented, more columns when there are more of them than a column holds (520 px), each column filled before the next, so a subclass added moves only those after it; trees side by side in label order, in a block the shape of a screen |
| A class's restrictions, unions, enumerations | a grey node below the class, with its subclasses |
| Individuals - the ABox | a band below all the classes: each class's individuals under that class, in a column; an individual's values right below it, the column as wide as they reach |
| A property whose domains name one class on the board | beside that class, since 1.135.1: 80 px right of its box, the first level with its top, a class's properties one under the other, object properties before data properties, each by label, a sub-property under its super-property when both stand beside the class, and a data property's datatype under it. Inside the class's frame - a pattern's, or one you drew - which grows to hold them; where that would put them or the frame on a box, a note or another frame, nothing is moved to make room and the property stays in its section. Or with the class in the outline. The class and its properties take their room together, so its subclasses stand below them. A sub-property stands beside its own class wherever its super-property stands; the line between two properties that stand apart waits for a selection - see [On a large board](#on-a-large-board) |
| Every other object property and data property | each a frame of its own, Object properties and Data properties, as its property hierarchy: right of the classes, or below them when that gives the board more nearly a screen's shape. Here stand the properties with no domain, with `owl:Thing`, a class expression, a class not on the board, or two classes on it - a super-property's domain does not count; a frame with none is not drawn. Two properties that are each other's inverse stand side by side, so the line between them is short, and two hierarchies that mirror each other stand side by side with their tops level; where there is no room beside, the partner goes into a band below. The same ontology gets the same arrangement whatever order its axioms come in. A data property's datatype stands under it |

Lines are routed around the boxes, at right angles, on a board of up to 150 drawn lines. No two
lines share a stretch or run closer than 12 px, except at a box with no point left, where two lines
share its last point's stub or arrive 10 px apart. A subclass line runs down a lane of its own on
the left of its subclasses, or joins its parent's trunk under the parent. A label sits on its own
line, clear of boxes, other labels and other lines wherever its line has room for it, and off a frame's
outline and its name - at every zoom, where it keeps the place and the size it has at 100% (since
1.135.2; before, zoomed out, a label could stand a few pixels off its place and be drawn up to 26 px
wider, onto the line beside it). No line runs through the arrowhead of another class's subclass
line where it can go round, and lines keep off a frame's name. On a board of
more than 150 lines, relation arrows keep a straight connector and are drawn faintly until one of
their ends is selected; the status line says so once.

`PatternBoardsTest` draws every pattern in the library on a board of its own, four boards of several
patterns together - the three scenarios' among them - and a made-up board of 120 boxes: no two boxes
overlap and no line passes through a box on any of them, and every line is routed. On the boards of
several patterns nothing runs together and no label touches anything. Over every pattern alone,
lines run on top of each other for 0.3% of all line length and closer than 12 px for 1.5% (in
1.135.2, drawn in Segoe UI), mostly where a box has more lines than room; 78 of 650 labels touch
something - 77 in DejaVu Sans, as Linux draws them - all in crowded spots. On the study's board, a
pass of the router takes 20 to 65 ms. On pizza (86 terms) and MWO (660, with 21 frames), measured in
Protégé 5.6.9 before 1.135.1: no two boxes overlap, and no line passes through a frame neither of its
ends is in.

**A class two patterns share is drawn once**, in the frame of the pattern that came first, and the
second pattern's frame has a note at its top that names it and says where it is, as the status line
does when the pattern is imported. BFO's classes are not named in the note. A superclass that no
pattern lists stands in the frame of its first subclass, above it, and a class with two
superclasses stands under the deeper one, so the other is above it too. On the boards of the study,
every subclass line points up to its superclass; with frames in several rows a line between two
frames can still point down.

**A pattern is found by its terms, too.** An ontology that has a library pattern merged into its
own file - by Protégé, ROBOT or by hand, with no import and no note left by OntoBoard - gets the
pattern's frame when it declares at least two of the pattern's own terms, and they are at least
60% of them. An ontology that imports library patterns is framed by those imports only. Terms many patterns share, such as an upper ontology's, do not count. Each class is in
one frame. Measured in Protégé 5.6.9 since 1.133.0: the pizza ontology as a single file shows 3
patterns, NFDIcore 14 and MWO 19, with no overlaps. A board saved before 1.133.0 gets them with
*Arrange → Outline*.


## Description logic

Arrows and grey nodes are written in description logic:

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

A subclass arrow (⊑) is a hollow-headed solid line; an equivalence is two of them. The legend,
*?* on the toolbar, has every line.

**From the description view to the board.** Select a line in Protégé's description of a class -
*SubClass Of: ∃ hasTopping.Cheese* - and the board selects the arrows that draw it, with the terms
at their ends, and scrolls to them. A property's description works the same way: *Domains*,
*Ranges*, *SubProperty Of*, *Inverse Of*, *Equivalent To*, *Disjoint With* and *SuperProperty Of
(Chain)* select the line at the property's box.

## Drawn

Every logical axiom in both ontologies - 921 of 921 in MWO, 529 of 529 in NFDIcore.

| Axiom | Drawn as | On MWO |
|---|---|---|
| `SubClassOf(A B)` | hollow-headed arrow | 391 |
| `SubClassOf(A, R some/only/min/max/exactly B)` | arrow labelled `∃ R`, `∀ R`, `≥ n R`, `≤ n R` or `= n R`, each with its own line | 86 |
| `SubClassOf(A, B ⊓ …)` | an arrow to each named conjunct, the rest as below | 36 |
| `EquivalentClasses(A B)` | two hollow arrows | - |
| `EquivalentClasses(A, R some B)` and other definitions | as `SubClassOf`, both ways | 51 |
| An equivalence between two restrictions | two expression nodes joined both ways | 1 |
| Union, complement, enumeration, nested filler, inverse-property restriction | a grey node for the expression, its operands hanging off it | 77 nodes |
| `ObjectPropertyDomain` / `ObjectPropertyRange` | from the property's node, long-dashed, labelled `domain` / `range`; a union as its own node | 57 / 55 |
| `DataPropertyDomain` / `DataPropertyRange` | the same, the range to its datatype | 3 / 3 |
| `DisjointClasses` | dashed red line, no arrowheads, one per pair; a restriction as its node | 17 axioms |
| `SubObjectPropertyOf` / `SubDataPropertyOf` | arrow between property nodes | 49 |
| `EquivalentObjectProperties` | two sub-property arrows - or an inverse line, for `p ≡ inverse(q)` | 1 |
| `InverseObjectProperties` | dashed line, arrowheads both ends | 41 |
| Property characteristics | a suffix on the property's label - `(transitive)`, `(functional)` … | 27 |
| `SubPropertyChainOf` | a `chain: R o S` node with numbered links | 5 |
| `ClassAssertion` | `rdf:type` arrow | 50 |
| `ObjectPropertyAssertion` | arrow between two individuals, labelled with the property | 5 |
| `HasKey` | an arrow to each key property, labelled `key` | 0 |
| SWRL rule | a node reading the rule - `rule: a(?x) ∧ b(?x, ?y) → c(?x)` - with a dotted `if` line from each term its body uses and a `then` line to each term it concludes | 4 |
| `SubClassOf(R some B, A)` - a scoped domain | an arrow from A to B labelled `R (scoped domain)` | - |
| Any other `SubClassOf` whose subclass is an expression | the expression as its node, a hollow arrow from it | - |
| `DisjointUnion(A B C)` | hollow arrows from B and C to A, and a disjointness line between B and C | - |
| `DisjointObjectProperties` / `DisjointDataProperties` | a disjointness line between the property nodes | - |
| `SameIndividual` / `DifferentIndividuals` | a line labelled `same as` / `different from`, one per pair | - |
| `ObjectPropertyAssertion` that is negative | an arrow labelled `not R` | - |
| `DataPropertyAssertion` | a box holding the value, and an arrow to it from the individual, labelled with the property; `not R` for a negative one | - |

Every arrow of the first kinds - subclass, type, and an existential restriction - says something
the ontology entails: a test turns each back into the axiom it claims and checks it with HermiT,
on every version of the pizza example and on the shapes that were once drawn wrongly.

Whole of MWO: **749 nodes and 1,252 edges**. The table counts class expressions drawn, which you ask
for: *More → Show class expressions*, off by default since 1.135.1. Off, the 47 axioms whose shape needs
an expression node are not drawn; arrows whose ends are named terms are.

A rule is drawn when it is in the ontology's own file. A rule from an import is not, and the
reasoner still uses it.

## On a large board

On any board, disjointness, domains and ranges, and a rule's lines are drawn for the term in focus -
select it - rather than all at once (106 and 118 lines on MWO). The one exception, since 1.135.1,
is the line from a property to the one class its domains name, while the property stands beside
that class where the Outline puts it, in the same frame or both in none: it is drawn always, routed
with the board's own lines, while the property's range waits like the others. A property anywhere
else has both its lines waiting - in its section, moved away by hand, placed by Hierarchical,
Organic, Circle or Grid, and on a board saved by an earlier build any property, until
*Arrange → Outline*. So does the is-a or inverse line between two properties that stand apart, one
beside its class and the other beside another class or elsewhere, which would cross the board as a
range does; between two beside the same class, or two not beside a class, it is drawn. With
*Focus* switched off, the lines that wait are not drawn at all. The lines a selection shows are
routed
round the boxes and
off the frames' names when they appear, among the lines already drawn, which keep their routes; and
one that stays shown when the selection changes - its other end clicked, a second term added - stays
where it is. A click answers at once: on a class many lines meet, its lines appear over a moment as
they are routed, and none is drawn through a box meanwhile; *Export image...* routes any still
waiting before it draws, so a picture taken in that moment has them all. Resizing a term, a frame or
a sticky note routes the board again, as moving one does, and the board keeps the new size.
Selecting a term also brings it and everything it is connected to forward and fades the rest; a
click on empty board ends that, and *Focus* on the toolbar turns it off.

More than 150 relations, and lines drawn alike become a mesh across the whole board. So on a board
that size, the other relations, and any line longer than 1,600 px, are drawn faintly until one of
their ends is selected; so is the domain line of a property beside its class, which keeps the
straight connector there too. It does not count towards the 150: short and beside its class, it is
no part of a mesh, and counted it made pizza-bfo such a board.

*More → Show on the board* switches object properties, data properties, individuals, imported
terms and rules off and on. A class can be folded - right-click, *Collapse subclasses* - and then
shows how many terms it holds.

## Working on the board

- **A defined class** - one with an equivalence, so the reasoner can find its members - has `≡`
  before its name and a badge on the left of its box, a disc with three bars, as Protégé's class
  tree marks one. The legend (*?* on the toolbar) has it.
- **Show only this and its neighbours**: right-click a term. Everything else leaves the board until
  *Escape*, or *More → Show the whole board*.
- **Fold** a class with right-click → *Collapse subclasses*; *More → Expand every folded class*
  opens them all again.
- **Rules**: double-click a rule's node to read it set out - *If*, each condition on a line,
  *then*, each conclusion. *More → Show on the board → Rules* takes them off a board that has many.
- **Definition…**, on the right-click menu of a term your own file declares, writes its definition
  in a small window: what a quality report asks for, without leaving the board.
- **Keys**, as in a mind map: **Tab** makes a subclass of the selected class, **Enter** a sibling;
  double-click empty board for a new class. *?* → *Keyboard shortcuts* lists them all.

## Not drawn

| | Why |
|---|---|
| Annotations | On the hover and in the left panel's editable frames; `rdfs:label` is already the node's name. |

## Consequences worth knowing

- **One hop** means "the other end of an edge the canvas would draw", plus the property an arrow
  stands for. A domain and a range are drawn from the property's own node, so a class reaches
  the classes at the property's other end through the property.
- An **expression node is not a term**: it has no IRI, clicking it selects nothing in Protégé,
  and an arrow ending on one cannot be retracted from the canvas - edit the class's definition in
  the description view instead. The same holds for a rule's node and for a domain that is a union.
- The **DOT export** carries every node and edge kind, expression and rule nodes included.
- A **node with no saved position** - a new term on a board you have arranged by hand, or a derived
  node such as a datatype, a class expression or a rule - is placed below its parent or in the first
  free slot, and keeps that place from then on.
- **Redrawing** after an edit changes only what changed: on MWO, 12 ms when nothing did, and
  110 ms to project and redraw after a class is added. The board is not redrawn while it is not
  on screen; it catches up when it is shown.
