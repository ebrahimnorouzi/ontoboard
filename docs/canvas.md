<!--
  Written for: somebody looking at a board and asking "is that everything the ontology says, and
  where is it?". Points and numbers only. Measured on MWO 3.0.2 (660 terms with its imports) and
  NFDIcore 3.0.5 (399 terms), every term on the board, class expressions drawn.
-->

# What the canvas draws

## What is on a board

Every class, object property, data property and individual of the open ontology, imports included,
as soon as the OntoBoard tab opens - up to 1,500 terms, the ontology's own first. A term made
anywhere in Protégé appears beside its superclass. *Hide from board* takes one off and keeps it off;
*More → Show every term* brings them back. A board saved by OntoBoard before 1.131.0 keeps the terms
chosen then, and says so.

## Where things go

The board is laid out as an outline (*Arrange → Outline*):

| | Where |
|---|---|
| Design patterns the ontology imports, or has merged in | a frame each, named after the pattern, along the top |
| Classes | a tree read left to right: a class, then its subclasses stacked in the next column |
| Individuals | in the column right of their class |
| A class's restrictions, unions, enumerations | a grey node in the column right of the class |
| Object properties, data properties | each a frame of its own, as its property hierarchy |
| A subtree too tall for a screen | a block of its own; blocks stand in columns |

MWO 3.0.2 under the layered layout every board used to get: 32,335 × 10,078 px. As an outline:
18,003 × 5,541 px, no two boxes overlapping.

## Drawn

Every logical axiom in both ontologies - 921 of 921 in MWO, 529 of 529 in NFDIcore.

| Axiom | Drawn as | On MWO |
|---|---|---|
| `SubClassOf(A B)` | hollow-headed arrow | 391 |
| `SubClassOf(A, R some/only/min/max/exactly B)` | arrow labelled `R`, with the qualifier unless it is `some` | 86 |
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

Whole of MWO: **749 nodes and 1,252 edges**. Class expressions can be switched off under
*More → Show class expressions*; then the 47 axioms whose shape needs an expression node are not
drawn.

## On a large board

More than 150 relations, and lines drawn alike become a mesh across the whole board. So on a
board that size:

- disjointness, domains and ranges are drawn for the term in focus - select it - rather than all
  at once (106 and 118 lines on MWO);
- the other relations, and any line longer than 1,600 px, are drawn faintly until one of their
  ends is selected;
- selecting a term brings it and everything it is connected to forward and fades the rest
  (*Focus* on the toolbar turns this off).

*More → Show on the board* switches object properties, data properties, individuals, imported
terms and rules off and on. A class can be folded - right-click, *Collapse subclasses* - and then
shows how many terms it holds.

## Working on the board

- **A defined class** - one with an equivalence, so the reasoner can find its members - has `≡`
  before its name. The legend (*?* on the toolbar) has it.
- **Show only this and its neighbours**: right-click a term. Everything else leaves the board until
  *Escape*, or *More → Show the whole board*.
- **Fold** a class with right-click → *Collapse subclasses*; *More → Expand every folded class*
  opens them all again.
- **Rules**: double-click a rule's node to read it set out - *If*, each condition on a line,
  *then*, each conclusion. *More → Show on the board → Rules* takes them off a board that has many.
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
- A **node with no saved position** - a new term, or a derived node such as a datatype, a class
  expression or a rule - is placed beside its parent or in the first free slot, and keeps that
  place from then on.
- **Redrawing** after an edit changes only what changed: on MWO, 12 ms when nothing did, and
  110 ms to project and redraw after a class is added. The board is not redrawn while it is not
  on screen; it catches up when it is shown.
