# Try it: a pizza ontology

A small ontology of pizzas - their bases, toppings, spiciness and prices - made with OntoBoard
from three ontology design patterns (Componency, Classification and Price) and released five
times. Every step that built it was an OntoBoard menu action, and every release is a git tag, so
you can follow how it grew.

[:material-github: The pizza ontology on GitHub](https://github.com/ebrahimnorouzi/pizza-ontology){ .md-button .md-button--primary }
[:material-download: Download it as a ZIP](https://github.com/ebrahimnorouzi/pizza-ontology/archive/refs/heads/main.zip){ .md-button }

You need Protégé 5.6.x (or 5.5.0) with [the OntoBoard plugin](../getting-started.md). Unpack the
example anywhere and open `src/ontology/pizza-edit.owl` with *File → Open...*

## 1. See it whole

Click the **OntoBoard** tab. Every class, property and individual is on the board, and the three
patterns sit in frames along the top. Type `named pizza` in the board's *Find* field, then
right-click it → *Show only this and its neighbours*. The ⋮ button → *Show the whole board*
brings everything back.

## 2. Get your own identifiers

New terms in this project are numbered from a block reserved for each editor.
*OntoBoard → Project → ID ranges...* → *Allocate a block to an editor* → *OK* gives you yours.

## 3. Add a topping

Right-click `vegetable topping` on the board → *New subclass...* → `artichoke topping`. It gets
the next number of your block. Click it, and give it a definition in *Annotations and
description*: the property `IAO_0000115`, the text `A pizza topping of artichoke hearts.`

## 4. Let the reasoner work

*Reasoner → ELK → Start reasoner*, then switch on *Inferences* in the board's toolbar. Dotted
edges are what the reasoner concluded: nobody wrote that a diavola is a spicy pizza. Right-click
that dotted edge → *Why is this inferred?...* to see the axioms behind it.

## 5. Run the project's checks

*OntoBoard → Check and test → SPARQL...* → *Run this project's checks (src/sparql)*. Each check
is a question the ontology must answer - every pizza has a base, every pizza has a price - and
each says pass or FAIL.

## 6. Look back

*OntoBoard → Build and release → History...* lists the five releases. *Compare releases...*
between `0.1.0` and `1.0.0` shows, term by term, what each iteration added.

When you are happy, *File → Save*. If you cloned the example rather than downloading it,
*OntoBoard → Share → Git...* shows what you changed.

## Where next

- [Start here: the guide](../guide.md) - the same steps for an ontology of your own
- [Working with ODK](../odk-workflow.md) - build, test and release
- [The pattern library](../patterns/index.md) - where Componency, Classification and Price come from
- [Try it: BFO and a knowledge graph](pizza-bfo.md) - the next example
