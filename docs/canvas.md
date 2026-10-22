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

## Not drawn

| Axiom | On MWO | Why |
|---|---|---|
| `DisjointClasses` | 15 | In the hover text. An edge for it would double the edge count on a taxonomy. |
| `InverseObjectProperties` | 38 | Hover text. |
| `TransitiveObjectProperty` and the other characteristics | 25 | A property of one property — a badge, not an edge. |
| `SubPropertyChainOf` | 3 | No two-ended shape fits a chain. |
| `ObjectPropertyAssertion` | 5 | Individual-to-individual; the canvas is a schema diagram. |
| `HasKey`, SWRL rules | 0 / 4 | No visual form attempted. |
| Literals | — | A value is not a term; `rdfs:label` is already the node's name. |

## Consequences worth knowing

- A term with **no edges** may still be constrained — by disjointness or a characteristic, both
  of which are hover-only.
- **One hop** means "the other end of an edge the canvas would draw", plus the property an arrow
  stands for. So a term related only by disjointness is not a neighbour.
- An **expression node is not a term**: it has no IRI, clicking it selects nothing in Protégé,
  and an arrow ending on one cannot be retracted from the canvas — edit the class's definition in
  the description view instead.
- The **DOT export** carries every node kind, expression nodes included.
