# Try it: BFO and a knowledge graph

The same pizzas, aligned to **BFO 2020** with design patterns from BFO and NFDIcore - and a knowledge graph
beside the ontology: which pizza was baked, by whom, in which oven and when, kept in nine
spreadsheets and turned into individuals by OntoBoard.

[:material-github: Pizza BFO on GitHub](https://github.com/ebrahimnorouzi/pizza-bfo){ .md-button .md-button--primary }
[:material-download: Download it as a ZIP](https://github.com/ebrahimnorouzi/pizza-bfo/archive/refs/heads/main.zip){ .md-button }
[:material-book-open-variant: Read its documentation](https://ebrahimnorouzi.github.io/pizza-bfo/ontology/index-en.html){ .md-button }

You need Protégé 5.6.x (or 5.5.0) with [the OntoBoard plugin](../getting-started.md). Unpack the
example anywhere and open `src/ontology/pizzabfo-edit.owl` with *File → Open...*

## 1. The ontology, on BFO

Click the **OntoBoard** tab. A pizza is a BFO *object*, its base a *fiat object part*, its
spiciness a *quality* and the baking a *process*. Right-click `pizza baking` → *Show only this and
its neighbours* to see who and what takes part in it.

*OntoBoard → Reuse → Imported terms...* shows each import - BFO 2020, two BFO patterns and four
NFDIcore patterns - with the terms it brings.

## 2. Open the knowledge graph

*File → Open...* → `kg/pizzabfo-kg.ttl`, in a new window. It imports a release of the ontology
and holds the data: cities, pizzerias, bakers, ovens, pizzas and bakings.

*OntoBoard → Spreadsheets → Sheet editor* opens the nine sheets it is made from - the toolbar says
*Folder: kg/templates (9 sheets)*. Row 1 of each sheet is the heading, row 2 says what each
column means to ROBOT, and every row after that is one individual.

## 3. Declare the identifiers' prefix

The sheets write their identifiers as `kg:PB_0000001`. In the graph's window: the *Active
ontology* tab → *Ontology Prefixes* → add `kg` for
`https://ebrahimnorouzi.github.io/pizza-bfo/kg/`, then *File → Save*.

## 4. Add an order

Tab `08_pizzas` → *Add row*. Leave *ID* empty; *Type* `quattro formaggi`, *Name*
`order 6, a quattro formaggi`. Then *Generate IRIs...* → *Continue the numbering in these sheets*,
and *Save all*.

## 5. Check the sheets, and build

*OntoBoard → Spreadsheets → Check sheets...* reads every sheet against the graph: names that point
at nothing, names that mean two things. Then *Spreadsheets → Template...* → any sheet of the
folder → *Add every sheet in its folder*: the rows become individuals, and *Edit → Undo* takes
the whole run back.

## 6. Ask the graph

*Reasoner → HermiT → Start reasoner*, then the *DL Query* tab: `'cheesy pizza'`, tick
*Instances*. Your new order is among them - nobody said it was cheesy; the ontology says what a
cheesy pizza is. *OntoBoard → Check and test → SPARQL...* runs the questions in `kg/cq/` over
what the graph says.

## 7. Make its documentation page

Back in the ontology's window, *OntoBoard → Build and release → Documentation...* runs
[Widoco](https://github.com/dgarijo/Widoco) on the release and opens the page it writes: every
class and property - and, with *Include the WebVowl diagram* ticked, a diagram of how they
connect. It needs Widoco's jar; the dialog says where to get it.
[The page this example publishes](https://ebrahimnorouzi.github.io/pizza-bfo/ontology/index-en.html)
was made that way.

## Where next

- [From spreadsheets to a knowledge graph](../knowledge-graph.md) - the same steps for your own data
- [Working with ODK](../odk-workflow.md) - build, test and release the ontology
- [Made with OntoBoard](../made-with-ontoboard.md) - more ontologies to open
