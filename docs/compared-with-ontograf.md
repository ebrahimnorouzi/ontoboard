# Compared with OntoGraf

[OntoGraf](https://protegewiki.stanford.edu/wiki/OntoGraf) ships with Protégé and is the
visualisation most people reach for first, so it is the fair comparison for OntoBoard's canvas.

This page was written by reading `ontograf-2.0.3.jar` — its `plugin.xml`, its classes and its
enums — rather than from memory, because a comparison written from impressions is how a project
talks itself into believing it already does something.

---

## What OntoGraf did that OntoBoard did not

This section was the original survey, written when none of it was built. Four of the five are
now in the plugin and the headings say which release; the reasoning is kept as written, because
the argument for building something is worth more than a tick.

### 1. Configurable tooltips · *built in 1.74.0*

OntoGraf's hover text is a set of switchable sections, chosen in a dialog
(`TooltipConfigurationDialog`), separately for classes and individuals. For a class
(`NodeOWLClassTooltipType`) the sections are:

> `TITLE` · `URI` · `SUPERCLASSES` · `EQUIVALENT_CLASSES` · `DISJOINT_CLASSES` · `ANNOTATIONS`

OntoBoard's node tooltip was fixed: label, kind, whether the term is imported, its editorial
notes and its IRI. **The three it lacked were the axiomatic ones** — superclasses,
equivalent classes, disjoint classes. Those are the sections that answer "what is this term,
in OWL" without leaving the board, and on a canvas that deliberately draws only some axioms they
matter more here than they do in OntoGraf.

Disjointness is the strongest case: OntoBoard draws no disjointness at all, anywhere, so a
tooltip is currently the only place it could appear.

### 2. Pinned tooltips · *built in 1.83.0*

`PinTooltipsAction` keeps a tooltip open after the pointer leaves, so you can read one term's
details while looking at another. OntoBoard's vanished on exit, which made comparing two terms
a matter of memory.

Now: select a term and press <kbd>P</kbd>, Shift+P to clear. It works on the selection rather
than on what is under the pointer, so selecting two terms and pressing P puts both side by
side — which is the reason to want the feature. Four at a time, the oldest closing to make
room, and a card is dropped when its term leaves the board rather than left floating where
that term used to be.

### 3. A graph of the imports · *built in 1.83.0*

`OntoGrafImportView` and `ImportsGraphModel` draw the `owl:imports` structure as a graph.
OntoBoard has the same information in a table (*Project → Imports…*) and it is a better table
than OntoGraf's graph is a graph — it says what each import resolved to and why an unresolved one
failed. But an ODK project's import structure is a shape, and a shape is easier to see than to
read: five modules importing a mirror that imports upstream is one picture and twelve table rows.

Cheap, too: the nodes are ontologies, not terms, so there are never many.

### 4. Export as GraphViz DOT · *built in 1.79.0*

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

**All four are now built.** In the order they shipped:

1. **Superclasses, equivalents and disjoints in the node tooltip** (1.74.0). Disjointness had
   nowhere else to appear and still has nowhere else on the board.
2. **Export as DOT** (1.79.0). A serialiser over the projection the canvas already holds.
3. **Pinned tooltips** (1.83.0). Select a term and press <kbd>P</kbd>; Shift+P clears them. Up
   to four at once — enough to compare, few enough to still see the diagram.
4. **An imports graph** (1.83.0). *Project → Imports graph…*, with a Copy as DOT button.

One honest note on the fourth. Measured on the real ODK project this plugin is built against,
the imports graph is a star: the edit file and four modules, nothing nested, nothing shared. For
that project the table already said everything the picture does. The graph earns its place on a
project whose modules import a shared mirror, which is the structure ODK's `module_type: mirror`
produces — and it is the only thing in OntoBoard that will tell you a module is shared, because
the table lists direct imports one row each.

Deliberately not implemented: OntoGraf's layout engine and its navigation model. Its
expand-and-collapse-by-arc-type browsing suits exploring an unfamiliar ontology; OntoBoard's
canvas is for composing a diagram you intend to keep, and the two want different interactions.
