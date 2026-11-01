---
hide:
  - navigation
---

<div class="ob-hero ob-reveal" markdown>

# Build an ontology without leaving Protégé

OntoBoard runs the **ODK** and **ROBOT** pipeline inside Protégé Desktop - building, reasoning,
reporting, releasing - and draws your **whole ontology on a board** you can work on the way you
work on a whiteboard. No terminal, no Docker command to remember, no switching windows to find out
whether your ontology is sound.

<div class="ob-buttons" markdown>
[:material-download: Download the plugin](https://github.com/ebrahimnorouzi/ontoboard/releases/latest/download/ontoboard.jar){ .md-button .md-button--primary }
[:material-map-marker-path: Start here: the guide](guide.md){ .md-button }
[Getting started](getting-started.md){ .md-button }
[Browse 162 patterns](patterns/index.md){ .md-button }
</div>

<p class="ob-sub">One jar. Protégé 5.6.x or 5.5.0. Nothing else to install.</p>

</div>

<div class="ob-figures ob-reveal" markdown>
<div class="ob-figure"><strong>162</strong><span>design patterns, ready to import</span></div>
<div class="ob-figure"><strong>100%</strong><span>of the logical axioms of two published ontologies drawn on the board</span></div>
<div class="ob-figure"><strong>2</strong><span>Protégé versions, both smoke-tested every release</span></div>
<div class="ob-figure"><strong>2,660</strong><span>tests behind it</span></div>
</div>

## Where do you start?

<div class="ob-reveal" markdown>

Click the answer that fits; each opens the next step. The same steps are in Protégé under
**OntoBoard → Start here: the guide...**, ticked as your project does them.

???+ question "Do you already have an ontology?"

    ??? success "No - I am starting from an idea"

        1. **OntoBoard → Start here: the guide...** - ten steps from an idea to a released
           ontology, each with what it does, why, and a button that does it.
        2. **Project → New ODK project...** writes a project: the file you edit, a place for
           tests, imports and releases. You only give it a short name and a title.
        3. **Pattern library...** - start from a tried design pattern for what you want to say,
           instead of a blank page. [How to choose one](choosing-a-pattern.md)

    ??? info "Yes - one .owl file"

        Open it in Protégé and go to the **OntoBoard** tab: the whole ontology is on the board,
        laid out as an outline. That is all you need to look, edit and draw.

        When you want builds, tests and versioned releases, **Project → Turn an ontology into
        an ODK repository...** makes a project from the file - and from its earlier versions,
        if you have them, as releases and git tags - ready to push to GitHub. Each step is shown
        as it runs. [How it works](odk-workflow.md#from-an-ontology-you-already-have-turn-an-ontology-into-an-odk-repository-since-11330)

    ??? info "Yes - a project folder (an ODK repository)"

        **Project → Open existing ODK project...** - or **Open from GitHub...** to clone one
        first. Then **Build...** checks it, **Release...** publishes a version, and
        **History...** shows every release and what changed in each.

???+ question "Do you need terms another ontology already has?"

    ??? success "Yes - I know which ontology"

        **OntoBoard → Find terms in another ontology...** - give its web address, a file, or the
        address of a page about it; search by label, tick, and **Add them**. Nothing to look up
        by IRI. [Step 4 of the guide](guide.md#4-reuse-terms-from-other-ontologies-optional)

    ??? info "I imported some before and want to change them"

        **OntoBoard → Imported terms...** shows each import and every term it brings, by
        label - and adds more, or takes some out, rebuilding the import at once.

???+ question "Do you have data - lists of things - in spreadsheets?"

    ??? success "Yes"

        Keep it in a graph file of its own that imports your ontology, one sheet per kind of
        thing: the **Sheet editor** gives new rows identifiers, **Check sheets...** finds names
        that point at nothing, **Template...** turns each sheet into individuals.
        [From spreadsheets to a knowledge graph](knowledge-graph.md)

???+ question "Do you know ODK, ROBOT or eXtreme Design?"

    ??? success "No"

        You do not need to. Every step is a menu item, and the guide says what each one does
        and why it matters, in plain words. Nothing to install, nothing to type.
        [Start here: the guide](guide.md)

    ??? info "Yes"

        [Working with ODK](odk-workflow.md) maps each ODK step to the menu item that runs it in
        Protégé, and says which parts of a project OntoBoard reads, writes, or leaves alone.

</div>

## Why you would want it

<div class="ob-compare ob-reveal" markdown>

<div class="ob-without" markdown>
### Without OntoBoard
- Edit in Protégé, then switch to a terminal to build
- `docker run` with the right mounts, every time
- A quality report you read as text in a console
- Find a design pattern by reading somebody's web page and retyping its terms
- A class hierarchy as a tree of names; relations, domains and rules out of sight
</div>

<div class="ob-with" markdown>
### With OntoBoard
- Build, reason and report from the menu bar
- The project's own `<id>-odk.yaml` read and honoured
- Findings in a sortable table, by *your* project's `profile.txt`
- Pick a pattern, and its terms are extracted and imported the ODK way
- Every class, property, individual, restriction and rule on one board
</div>

</div>

## What it does

<div class="ob-grid ob-reveal" markdown>

<div class="ob-card" markdown>
### :material-draw: Your whole ontology on a board
Every term, as soon as the tab opens: patterns framed along the top, classes as a tree, properties
in sections. Select a term and its relations come forward; fold a class; jump between frames;
press Tab for a new subclass. [What the board draws](canvas.md)
</div>

<div class="ob-card" markdown>
### :material-map-marker-path: A guide for the way
Ten steps from an idea to a released ontology, ticked against your project, each explained and
one click away - for people who have never heard of ODK. [Start here](guide.md)
</div>

<div class="ob-card" markdown>
### :material-cog-play: ODK without the terminal
Create a project, run the build, edit every key in the ODK YAML at any depth, manage ID ranges,
run the project's own scripts, refresh imports, release, compare releases.
[See the workflow](odk-workflow.md)
</div>

<div class="ob-card" markdown>
### :material-history: History of every release
Every release in the project, what each holds and what changed from one to the next, term by
term - added, removed, relabelled, moved, made obsolete. Open any of them in a window of its own.
</div>

<div class="ob-card" markdown>
### :material-source-repository: An ontology becomes a repository
An ontology in any format, with its earlier versions, becomes an ODK project: each version a
release and a git tag, imports turned into modules, built and released, every step shown as it
runs. Ready to push to GitHub. [How](odk-workflow.md#from-an-ontology-you-already-have-turn-an-ontology-into-an-odk-repository-since-11330)
</div>

<div class="ob-card" markdown>
### :material-magnify: Terms from other ontologies
Load an ontology from its web address - or the page about it - search its labels, tick the terms
you want, add them. *Imported terms* then shows what came from where, by label, and adds or takes
out terms. [How](guide.md#4-reuse-terms-from-other-ontologies-optional)
</div>

<div class="ob-card" markdown>
### :material-table: A knowledge graph from spreadsheets
One sheet per kind of thing, identifiers generated, names checked across sheets, each sheet turned
into individuals in a graph that imports your ontology. [How](knowledge-graph.md)
</div>

<div class="ob-card" markdown>
### :material-help-circle: Questions become tests
Pick a rule and the terms - every pizza has a base, nothing is both vegetarian and meaty - and
OntoBoard writes the test and runs it. No SPARQL. [Step 6](guide.md#6-turn-your-questions-into-tests)
</div>

<div class="ob-card" markdown>
### :material-check-decagram: ROBOT, in process
`robot-core` runs inside the plugin against the live ontology - report, measure, explain, extract,
template, SPARQL, profile. No external binary, no temporary files.
</div>

<div class="ob-card" markdown>
### :material-shape: Patterns you can import
162 ontology design patterns from the ODP portal, MWO, PMDco and NFDI MatWerk - ranked against
*your* ontology, with the reason shown. [Browse them](patterns/index.md)
</div>

<div class="ob-card" markdown>
### :material-account-group: Edit together, or not
A live session with cursors and shared edits if your group runs the server; plain git if it does
not. The plugin counts anything it cannot share and says so. [How it works](collaboration.md)
</div>

<div class="ob-card" markdown>
### :material-file-document-check: Honest about its limits
Every gap is written down, measured, with the defects found along the way.
[Limitations](limitations.md) - [Feature parity](feature-parity.md)
</div>

</div>

## See a pattern, live

<div class="ob-reveal" markdown>

This is the **Componency** pattern as it ships in the plugin - the same graph you get on any of the
[162 pattern pages](patterns/index.md). Drag a node, scroll to zoom, search inside it. In Protégé,
choosing this pattern extracts its terms into your own ontology the way ODK expects.

```ontoink
source: patterns/ttl/componency.ttl
height: 460px
```

</div>

## Where to go next

<div class="ob-reveal" markdown>

| | |
|---|---|
| [Start here: the guide](guide.md) | The steps from an idea to a released ontology, for newcomers |
| [Getting started](getting-started.md) | Install, and a first session end to end |
| [What the board draws](canvas.md) | Every kind of axiom, how it is drawn, and where things go |
| [Pattern library](patterns/index.md) | All 162 patterns, grouped by source, with a graph each |
| [Working with ODK](odk-workflow.md) | The ODK workflow step by step, and which steps OntoBoard does for you |
| [From spreadsheets to a knowledge graph](knowledge-graph.md) | Data in sheets, as individuals that use your ontology |
| [Glossary](glossary.md) | The words the menus and docs use, in plain terms |
| [Collaboration](collaboration.md) | Live sessions, and how to run the server |
| [Limitations and roadmap](limitations.md) | What it does not do, specifically, and why |
| [Feature parity](feature-parity.md) | The feature inventory, measured |
| [Compared with OntoGraf](compared-with-ontograf.md) | What Protégé's own visualisation does that this canvas does not |
| [Development](development.md) | Building, testing, and the OSGi constraints that are load-bearing |

</div>

<div class="ob-reveal" markdown>

Each release ships a receipt under
[`protege-plugin/tools/smoke-receipt/`](https://github.com/ebrahimnorouzi/ontoboard/tree/main/protege-plugin/tools/smoke-receipt/)
recording what was verified in a real Protégé, on which hosts, and what was not - it is also the
release notes. [`docs/superpowers/`](https://github.com/ebrahimnorouzi/ontoboard/tree/main/docs/superpowers/)
holds the specs and plans, including the platform evaluation that chose Protégé over a Miro app,
and decisions that were later reversed.

> **A note on the web application.** OntoBoard began as a browser application with a Python
> backend, and this repository used to carry both. It is now the plugin alone. The web
> application, its services and the compose files are still in the history, at the tag
> [`web-app-final`](https://github.com/ebrahimnorouzi/ontoboard/tree/web-app-final). Several
> documents here still measure the plugin against it, which is why its name appears: it was the
> yardstick, not a dependency.

</div>

<script src="assets/landing.js" defer></script>
