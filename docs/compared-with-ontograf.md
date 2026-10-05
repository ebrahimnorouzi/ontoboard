# Compared with OntoGraf

[OntoGraf](https://protegewiki.stanford.edu/wiki/OntoGraf) ships with Protégé and is the
visualisation most people reach for first, so it is the fair comparison for OntoBoard's canvas.

This page was written by reading `ontograf-2.0.3.jar` — its `plugin.xml`, its classes and its
enums — rather than from memory, because a comparison written from impressions is how a project
talks itself into believing it already does something.

---

## What OntoGraf does that OntoBoard does not

### 1. Configurable tooltips · *worth doing*

OntoGraf's hover text is a set of switchable sections, chosen in a dialog
(`TooltipConfigurationDialog`), separately for classes and individuals. For a class
(`NodeOWLClassTooltipType`) the sections are:

> `TITLE` · `URI` · `SUPERCLASSES` · `EQUIVALENT_CLASSES` · `DISJOINT_CLASSES` · `ANNOTATIONS`

OntoBoard's node tooltip is fixed: label, kind, whether the term is imported, its editorial
notes and its IRI. **The three it does not have are the axiomatic ones** — superclasses,
equivalent classes, disjoint classes. Those are the sections that answer "what is this term,
in OWL" without leaving the board, and on a canvas that deliberately draws only some axioms they
matter more here than they do in OntoGraf.

Disjointness is the strongest case: OntoBoard draws no disjointness at all, anywhere, so a
tooltip is currently the only place it could appear.

### 2. Pinned tooltips · *worth doing, cheap*

`PinTooltipsAction` keeps a tooltip open after the pointer leaves, so you can read one term's
details while looking at another. OntoBoard's vanish on exit, which makes comparing two terms a
matter of memory.

### 3. A graph of the imports · *worth doing*

`OntoGrafImportView` and `ImportsGraphModel` draw the `owl:imports` structure as a graph.
OntoBoard has the same information in a table (*Project → Imports…*) and it is a better table
than OntoGraf's graph is a graph — it says what each import resolved to and why an unresolved one
failed. But an ODK project's import structure is a shape, and a shape is easier to see than to
read: five modules importing a mirror that imports upstream is one picture and twelve table rows.

Cheap, too: the nodes are ontologies, not terms, so there are never many.

### 4. Export as GraphViz DOT · *worth doing, cheap*

`ExportAsDotAction`. OntoBoard exports PNG and SVG. DOT is different in kind: it hands the graph
to someone else's layout engine, which is what people with a LaTeX pipeline or a house style
actually want. The projection already holds nodes and edges, so this is a serialiser.

### 5. Save and reopen a named graph · *already covered, differently*

`SaveGraphAction` / `OpenGraphAction` save a graph to a file. OntoBoard persists the board
automatically to a sidecar beside the ontology, which is the better default — nobody loses work by
forgetting to save. What OntoBoard lacks is *several named boards per ontology*: one diagram for
the process model, another for the data model. That is worth having and is not what OntoGraf's
feature is, so it is listed here as a prompt rather than as a gap.

---

## What OntoBoard does that OntoGraf does not

Listed because "what else can we take from OntoGraf" is only half the question, and because
several of these are the reason the canvas exists.

| | |
|---|---|
| **Relations from the axioms you wrote** | OntoGraf draws subclass, type and domain/range. OntoBoard reads restrictions inside `EquivalentClasses` and conjunctions, cardinality, `hasValue` and scoped domains — on a real ODK project that is 42 arrows against a handful |
| **The reasoner's conclusions, and why** | Inferred edges drawn dotted, and *Why is this inferred?* answers with the axioms that force them — a real justification, not a path |
| **Editing** | Draw an arrow and choose the axiom it writes; create terms; set parents; obsolete. OntoGraf is read-only |
| **Identifiers and labels together** | `obo:BFO_0000023` above "role", from the ontology's own prefixes |
| **A key that travels** | Exported images carry the legend and the namespace colours |
| **Editorial notes on the board** | `IAO:0000116` notes readable and writable from the canvas, with a badge where one exists |
| **The ODK pipeline** | Build, report, release, imports — the canvas is one view of a project, not a separate tool |
| **Live collaboration** | Shared sessions with cursors |

---

## Recommendation

In the order the effort pays back:

1. **Superclasses, equivalents and disjoints in the node tooltip.** The information is one OWL
   API call away and disjointness has nowhere else to appear.
2. **Export as DOT.** A serialiser over a projection that already exists.
3. **Pinned tooltips.** Small, and it makes comparing two terms possible.
4. **An imports graph.** More work than the three above and less often needed, but it is the one
   genuinely different *view* OntoGraf has.

Deliberately not recommended: copying OntoGraf's layout engine or its navigation model. Its
expand-and-collapse-by-arc-type browsing suits exploring an unfamiliar ontology; OntoBoard's
canvas is for composing a diagram you intend to keep, and the two want different interactions.
