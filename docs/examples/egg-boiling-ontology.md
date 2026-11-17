# Try it: boiling eggs, an ODK project

A small application ontology for a test kitchen that boils eggs by a recipe - so many minutes in
boiling water for a soft, a medium or a hard egg - weighs each egg first, and publishes its
records as datasets. It is an ODK project, built on PMDco and NFDIcore design patterns and one
term of IAO, and released twice, as 1.0.0 and 1.1.0. Every step that built it was an OntoBoard
menu action, its README lists them, and every release is a git tag. The README says it was built
with OntoBoard 1.153.0 in Protégé 5.6.9, with ODK's own targets run by OntoBoard itself - nothing
else installed, no Docker.

[:material-github: The egg-boiling ontology on GitHub](https://github.com/ebrahimnorouzi/egg-boiling-ontology){ .md-button .md-button--primary }
[:material-download: Download it as a ZIP](https://github.com/ebrahimnorouzi/egg-boiling-ontology/archive/refs/heads/main.zip){ .md-button }
[:material-book-open-variant: Read its documentation](https://ebrahimnorouzi.github.io/egg-boiling-ontology/ontology/index-en.html){ .md-button }

You need Protégé 5.6.x (or 5.5.0) with [the OntoBoard plugin](../getting-started.md). Unpack the
example anywhere and open `src/ontology/eggboil-edit.owl` with *File → Open...*

## 1. See it whole

Click the **OntoBoard** tab. Type `egg boiling` in the board's *Find* field, then right-click it
→ *Show only this and its neighbours*: what an egg boiling takes in and gives out - a raw egg, a
boiled egg - and the report that is about it. The ⋮ button → *Show the whole board* brings
everything back.

## 2. See what it reuses

*OntoBoard → Reuse → Imported terms...* shows its four imports with the terms each brings: IAO's
`report`, PMDco's *Measurement and setpoint* and *Fundamental property* patterns, and NFDIcore's
*dataset* pattern. IAO's is a module ODK builds again from IAO's own address; the patterns are
kept as they are (`module_type: custom` in `src/ontology/eggboil-odk.yaml`).

## 3. Check it as ODK does

*OntoBoard → Build and release → Build...* → *Check everything (downloads the imports again) -
make test*. That is ODK's own `test` - the reasoner, ODK's checks, the quality report and the OWL
profile - and the project's two questions, asked of the ontology with its imports: every kind of
egg boiling dataset has some *has part*, and every kind of egg mass value specifies the value of
some *mass*. Without the network, choose *Check quickly (keeps the imports as they are) - make
test_fast*. Where no container runs it, OntoBoard runs it itself, inside Protégé.

## 4. Get your own identifiers, and add a term

Terms are numbered from a block reserved for each editor, `EGGBOIL_0000001` and up.
*OntoBoard → Project → ID ranges...* → *Allocate a block to an editor* → *OK* gives you yours.
Protégé 5.6 may ask for your ID range when the file opens, as it asks anybody it finds in no
range: cancel it, allocate your block here, and Protégé finds it from then on. Then right-click
`boiled egg` on the board → *New subclass...* → `soft-boiled egg`. It gets the next number of
your block.

## 5. Look back

*OntoBoard → Build and release → History...* lists the two releases. *Compare releases...*
between `1.0.0` and `1.1.0` shows, term by term, what the second one added.

When you are happy, *File → Save*. If you cloned the example rather than downloading it,
*OntoBoard → Share → Git...* shows what you changed.

## 6. Make its documentation page

*OntoBoard → Build and release → Documentation...* runs [Widoco](https://github.com/dgarijo/Widoco)
on the release and opens the page it writes. If Widoco is not installed, the dialog offers to
download it.
[The page this example publishes](https://ebrahimnorouzi.github.io/egg-boiling-ontology/ontology/index-en.html)
was made that way.

## Where next

- [Start here: the guide](../guide.md) - the same steps for an ontology of your own
- [Working with ODK](../odk-workflow.md) - build, test and release
- [Try it: a pizza ontology](pizza.md) and [BFO and a knowledge graph](pizza-bfo.md) - the other
  examples
- [Made with OntoBoard](../made-with-ontoboard.md) - more ontologies to open
