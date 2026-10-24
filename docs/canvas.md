<!--
  Written for: somebody looking at a board and asking "is that everything the ontology says?".
  Points and numbers only. Measured on MWO 3.0.1 (524 terms), DUL and NFDIcore.
-->

# What the canvas draws

Every number here is measured on **MWO 3.0.1** with all 524 terms on the board.

## Drawn

| Axiom | Drawn as | On MWO |
|---|---|---|
| `SubClassOf(A B)` | hollow-headed arrow | 285 |
| `SubClassOf(A, R some/only/min/max/exactly B)` | arrow labelled `R some` | 69 |
| `SubClassOf(A, B ⊓ …)` — the **named** conjunct | hollow-headed arrow | 36 (new in 1.123.0) |
| `EquivalentClasses(A B)` | two hollow arrows | new in 1.123.0 |
| `EquivalentClasses(A, R some B)` | arrow labelled `R some` | 8 |
| `SubObjectPropertyOf` / `SubDataPropertyOf` | arrow between property nodes | 45 |
| `ClassAssertion` | `rdf:type` arrow | 50 |
| domain + range pair | class-to-class arrow labelled with the property | 32 |
| `DataSomeValuesFrom` | arrow to a datatype node | — |
| `ObjectHasValue` | arrow to the individual | — |
| **Union, complement, enumeration, nested filler, inverse-property restriction** | **a grey node for the expression, operands hanging off it** | **29 nodes, new in 1.123.0** |

Before 1.123.0 the last row drew **nothing at all** — 39 of MWO's 116 restriction occurrences,
34%, were invisible, with no message saying so. Turn it off under **⋮ → Show class expressions**.

Cost of having it on, whole of MWO: 524 → 553 nodes, 525 → 652 edges, render 70 ms → 123 ms.

## Drawn since 1.124.0

| Axiom | Drawn as | On MWO |
|---|---|---|
| `DisjointClasses` | dashed red line, no arrowheads, one per pair | 29 from 15 axioms |
| `InverseObjectProperties` | dashed line with arrowheads both ends, between the two property nodes | 38 |
| Property characteristics (transitive, functional, symmetric, …) | a suffix on the property's label | 25 |
| `SubPropertyChainOf` | a `chain: R o S` node with numbered links | 3 |
| `ObjectPropertyAssertion` | an arrow between two individuals, labelled with the property | 5 |
| `HasKey` | an arrow to each key property, labelled `key` | 0 on MWO, 1 on DUL |

Whole-of-MWO totals: **556 nodes, 730 edges** (was 524 / 489 before 1.123.0).

Every one of these obeys the board's opt-in rule — both ends must already be on the canvas. That
is what stops disjointness from filling a taxonomy with red lines.

## Not drawn

| | Why |
|---|---|
| SWRL rules (4 on MWO) | A rule is a horn clause over variables. There is no two-ended or node shape for it that would not be an invented notation, and a picture that misleads is worse than a gap. Protégé's own rule tab shows them. |
| Literals | A value is not a term; `rdfs:label` is already the node's name. |
| Annotations | On the hover and in the left panel's editable frames. |

## Consequences worth knowing

- A term with **no edges** is now genuinely unconstrained as far as the canvas can tell: the six
  relations that used to be hover-only are drawn.
- **One hop** means "the other end of an edge the canvas would draw", plus the property an arrow
  stands for — so disjoint classes and inverse pairs are neighbours now that they are edges.
- An **expression node is not a term**: it has no IRI, clicking it selects nothing in Protégé,
  and an arrow ending on one cannot be retracted from the canvas — edit the class's definition in
  the description view instead.
- The **DOT export** carries every node and edge kind, expression nodes included.
- A node with **no saved position** — a newly added term, or a derived node such as a datatype, a
  class expression or a chain — is placed in the first free slot rather than at a fixed offset,
  so it no longer lands on top of a term you arranged.
