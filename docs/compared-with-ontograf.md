# Compared with OntoGraf

[OntoGraf](https://protegewiki.stanford.edu/wiki/OntoGraf) ships with Protégé and is the
visualisation most people reach for first, so it is the fair comparison for OntoBoard's board.
Written from `ontograf-2.0.3.jar` itself.

## Taken from OntoGraf

- **Superclasses, equivalent and disjoint classes in a term's tooltip** (1.74.0).
- **Export as GraphViz DOT** (1.79.0).
- **Pinned tooltips** (1.83.0): select terms and press <kbd>P</kbd>, Shift+P clears; up to four.
- **A graph of the imports** (1.83.0): *Reuse → Imports graph…*, with Copy as DOT.

OntoGraf's saved graphs are covered differently: OntoBoard saves the board beside the ontology
automatically. Not taken, on purpose: OntoGraf's layout engine and its expand-by-arc browsing,
which suit exploring an unfamiliar ontology more than composing a diagram you keep.

## What OntoBoard does that OntoGraf does not

| | |
|---|---|
| **Relations from the axioms you wrote** | Restrictions inside equivalences and conjunctions, cardinality, `hasValue`, scoped domains - not only subclass, type and domain/range |
| **The reasoner's conclusions, and why** | Inferred edges drawn dotted; *Why is this inferred?* answers with the axioms that force them |
| **Editing** | Draw an arrow and choose the axiom it writes; create terms; set parents; obsolete. OntoGraf is read-only |
| **Identifiers and labels together** | `obo:BFO_0000023` above "role" |
| **A key that travels** | Exported images carry the legend and the namespace colours |
| **Editorial notes on the board** | `IAO:0000116` notes readable and writable from the board |
| **The ODK pipeline** | Build, report, release, imports - the board is one view of a project |
| **Live collaboration** | Shared sessions with cursors |
