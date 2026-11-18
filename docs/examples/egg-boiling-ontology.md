# Try it: boiling eggs, an ODK project

A small application ontology for a test kitchen that boils eggs by a recipe - so many minutes for a
soft, a medium or a hard egg - weighs each egg first, and publishes its records as datasets. It is
an ODK project on PMDco and NFDIcore design patterns and one term of IAO, released as 1.0.0 and
1.1.0. Every step that built it was an OntoBoard menu action, without Docker; its README lists them,
and every release is a git tag.

[:material-github: The egg-boiling ontology on GitHub](https://github.com/ebrahimnorouzi/egg-boiling-ontology){ .md-button .md-button--primary }
[:material-download: Download it as a ZIP](https://github.com/ebrahimnorouzi/egg-boiling-ontology/archive/refs/heads/main.zip){ .md-button }
[:material-book-open-variant: Read its documentation](https://ebrahimnorouzi.github.io/egg-boiling-ontology/ontology/index-en.html){ .md-button }

You need Protégé 5.6.x (or 5.5.0) with [the OntoBoard plugin](../getting-started.md). Unpack the
example anywhere and open `src/ontology/eggboil-edit.owl` with *File → Open...*

## 1. See it whole

Click the **OntoBoard** tab. Type `egg boiling` in the board's *Find* field, then right-click it
→ *Show only this and its neighbours*: a raw egg goes in, a boiled egg comes out, and a report is
about it. The ⋮ button → *Show the whole board* brings everything back.

## 2. See what it reuses

*OntoBoard → Reuse → Imported terms...* shows its four imports with the terms each brings: IAO's
`report`, PMDco's *Measurement and setpoint* and *Fundamental property* patterns, and NFDIcore's
*dataset* pattern.

## 3. Check it as ODK does

*OntoBoard → Build and release → Build...* → *Check everything - make test* runs ODK's own `test` -
the reasoner, ODK's checks, the quality report, the OWL profile - and the project's two questions.
Without the network, choose *Check quickly (keeps the imports as they are) - make test_fast*.

## 4. Get your own identifiers, and add a term

*OntoBoard → Project → ID ranges...* → *Allocate a block to an editor* → *OK* gives you a block of
identifiers, `EGGBOIL_0000001` and up. If Protégé 5.6 asks for your ID range when the file opens,
cancel it and allocate your block here. Then right-click `boiled egg` on the board →
*New subclass...* → `soft-boiled egg`: it gets the next number of your block.

## 5. Look back

*OntoBoard → Build and release → History...* lists the two releases; *Compare releases...* between
`1.0.0` and `1.1.0` shows what the second one added. When you are happy, *File → Save*; in a clone,
*OntoBoard → Share → Git...* shows what you changed.

## 6. Make its documentation page

*OntoBoard → Build and release → Documentation...* runs [Widoco](https://github.com/dgarijo/Widoco)
on the release and opens the page it writes, downloading Widoco first if it is not installed.
[The page this example publishes](https://ebrahimnorouzi.github.io/egg-boiling-ontology/ontology/index-en.html)
was made that way.

## Where next

- [Start here: the guide](../guide.md) - the same steps for an ontology of your own
- [Working with ODK](../odk-workflow.md) - build, test and release
- [Try it: a pizza ontology](pizza.md) and [BFO and a knowledge graph](pizza-bfo.md) - the other
  examples
- [Made with OntoBoard](../made-with-ontoboard.md) - more ontologies to open
