---
hide:
  - navigation
---

<div class="ob-hero ob-reveal" markdown>

# Build an ontology without leaving Protégé

OntoBoard runs the **ODK** and **ROBOT** pipeline inside Protégé Desktop - building, reasoning,
testing, releasing - and draws your **whole ontology on a board** you can work on like a
whiteboard. No terminal, no Docker, no switching windows.

<div class="ob-buttons" markdown>
[:material-download: Download the plugin](https://github.com/ebrahimnorouzi/ontoboard/releases/latest/download/ontoboard.jar){ .md-button .md-button--primary }
[:material-pizza: Try an example](examples/pizza.md){ .md-button }
[:material-map-marker-path: Start here: the guide](guide.md){ .md-button }
</div>

<p class="ob-sub">One jar. Protégé 5.6.x or 5.5.0. Nothing else to install.</p>

</div>

<div class="ob-figures ob-reveal" markdown>
<div class="ob-figure"><strong>162</strong><span>design patterns, ready to import</span></div>
<div class="ob-figure"><strong>1</strong><span>jar - no terminal, no Docker</span></div>
<div class="ob-figure"><strong>2</strong><span>example ontologies to open and explore</span></div>
</div>

## Try it in fifteen minutes

<div class="ob-grid ob-reveal" markdown>

<div class="ob-card" markdown>
### :material-pizza: A pizza ontology
Made from three design patterns and released five times. Open it, see it on the board, add a
pizza and let the reasoner sort it. [Try it](examples/pizza.md)
</div>

<div class="ob-card" markdown>
### :material-table-large: BFO and a knowledge graph
A pizza ontology on BFO 2020, and nine spreadsheets that become a knowledge graph of which pizza
was baked by whom, where and when. [Try it](examples/pizza-bfo.md)
</div>

</div>

## What it does

<div class="ob-grid ob-reveal" markdown>

<div class="ob-card" markdown>
### :material-draw: Your ontology on a board
Every class, property and individual as soon as the tab opens. Select a term and its relations
come forward; press Tab for a new subclass. [What the board draws](canvas.md)
</div>

<div class="ob-card" markdown>
### :material-cog-play: ODK without the terminal
Create a project, build and test it, release a version, compare two releases - all from the
OntoBoard menu. [Working with ODK](odk-workflow.md)
</div>

<div class="ob-card" markdown>
### :material-shape: Patterns you can import
162 ontology design patterns, ranked against the ontology you have open, with the reason shown.
[Browse them](patterns/index.md)
</div>

<div class="ob-card" markdown>
### :material-table: Spreadsheets become a graph
One sheet per kind of thing: identifiers generated, names checked across sheets, rows turned into
individuals. [How](knowledge-graph.md)
</div>

<div class="ob-card" markdown>
### :material-help-circle: Questions become tests
Pick a rule and the terms - every pizza has a base - and OntoBoard writes the test and runs it.
No SPARQL. [How](guide.md#6-turn-your-questions-into-tests)
</div>

<div class="ob-card" markdown>
### :material-account-group: Edit together
A live session with shared edits if your group runs the server; plain git if it does not.
[Collaboration](collaboration.md)
</div>

</div>

## See a pattern, live

<div class="ob-reveal" markdown>

The **Componency** pattern, as it ships in the plugin. Drag a node, scroll to zoom.

```ontoink
source: patterns/ttl/componency.ttl
height: 460px
```

</div>

## Made with OntoBoard

<div class="ob-reveal" markdown>

Ontologies built with OntoBoard, each one a repository you can clone and open: every step a menu
action, every release a git tag. [See them all](made-with-ontoboard.md), and add yours.

</div>

<script src="assets/landing.js" defer></script>
