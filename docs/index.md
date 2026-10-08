---
hide:
  - navigation
---

<div class="ob-hero ob-reveal" markdown>

# Build an ontology without leaving Protégé

OntoBoard runs the **ODK** and **ROBOT** pipeline inside Protégé Desktop - building, reasoning,
reporting, releasing, and a canvas you can actually draw on. No terminal, no Docker command to
remember, no switching windows to find out whether your ontology is sound.

<div class="ob-buttons" markdown>
[:material-download: Download the plugin](https://github.com/ebrahimnorouzi/ontoboard/releases/latest/download/ontoboard.jar){ .md-button .md-button--primary }
[Getting started](getting-started.md){ .md-button }
[Browse 159 patterns](patterns/index.md){ .md-button }
</div>

<p class="ob-sub">One jar. Protégé 5.6.x or 5.5.0. Nothing else to install.</p>

</div>

<div class="ob-figures ob-reveal" markdown>
<div class="ob-figure"><strong>159</strong><span>design patterns, ready to import</span></div>
<div class="ob-figure"><strong>30+</strong><span>ROBOT and ODK operations in the menu</span></div>
<div class="ob-figure"><strong>2</strong><span>Protégé versions, both smoke-tested every release</span></div>
<div class="ob-figure"><strong>1744</strong><span>tests behind it</span></div>
</div>

## Why you would want it

<div class="ob-compare ob-reveal" markdown>

<div class="ob-without" markdown>
### Without OntoBoard
- Edit in Protégé, then switch to a terminal to build
- `docker run` with the right mounts, every time
- A quality report you read as text in a console
- Find a design pattern by reading somebody's web page and retyping its terms
- Discover the release is broken after you tagged it
</div>

<div class="ob-with" markdown>
### With OntoBoard
- Build, reason and report from the menu bar
- The project's own `<id>-odk.yaml` read and honoured
- Findings in a sortable table, by *your* project's `profile.txt`
- Pick a pattern, and its terms are extracted and imported the ODK way
- A release that refuses to publish without a host smoke receipt
</div>

</div>

## What it does

<div class="ob-grid ob-reveal" markdown>

<div class="ob-card" markdown>
### :material-draw: A canvas you can draw on
Classes, properties, individuals and restrictions as a diagram you arrange by hand - with frames,
sticky notes, six layouts, and an arrangement that survives the next edit. Draw a class by
double-clicking empty space.
</div>

<div class="ob-card" markdown>
### :material-cog-play: ODK without the terminal
Create a project, run the build, edit every key in the ODK YAML at any depth, manage ID ranges,
run the project's own scripts, refresh imports, compare releases.
[See the workflow](odk-workflow.md)
</div>

<div class="ob-card" markdown>
### :material-check-decagram: ROBOT, in process
`robot-core` runs inside the plugin against the live ontology - report, measure, explain, extract,
template, SPARQL, profile. No external binary, no temporary files.
</div>

<div class="ob-card" markdown>
### :material-shape: Patterns you can import
159 ontology design patterns from the ODP portal, MWO, PMDco and NFDI MatWerk - ranked against
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
[159 pattern pages](patterns/index.md). Drag a node, scroll to zoom, search inside it. In Protégé,
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
| [Getting started](getting-started.md) | Install, and a first session end to end |
| [Pattern library](patterns/index.md) | All 159 patterns, grouped by source, with a graph each |
| [Working with ODK](odk-workflow.md) | The ODK workflow step by step, and which steps OntoBoard does for you |
| [Collaboration](collaboration.md) | Live sessions, and how to run the server |
| [Testing collaboration locally](testing-collaboration-locally.md) | Two Protégé instances on one machine |
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
