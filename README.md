# OntoBoard

**Run your whole ODK and ROBOT pipeline inside Protégé.** No terminal, no `make`, no Docker -
for the projects OntoBoard makes, and for an ODK repository wherever OntoBoard can run each line of
its build; the rest builds through Docker, Podman or a native ODK install
([what needs what](docs/getting-started.md#what-works-with-nothing-installed)).

A Protégé Desktop plugin for people who maintain OBO-style ontologies, with a board that draws the
whole ontology. New to it? *OntoBoard → Start here: the guide...*, or read
[the guide](docs/guide.md).

[![Download](https://img.shields.io/badge/download-latest%20release-blue)](https://github.com/ebrahimnorouzi/ontoboard/releases/latest)

## Install

1. Download `ontoboard-<version>.jar` from the [latest release](https://github.com/ebrahimnorouzi/ontoboard/releases/latest).
2. **Delete any older `ontoboard-*.jar` from Protégé's `plugins/` folder.**
3. Put the jar into `plugins/` and restart Protégé.
4. Open the board with *Window → Tabs → OntoBoard*.

Protégé Desktop **5.6.x or 5.5.0**; Protégé brings its own Java. *OntoBoard → Check requirements*
says what this computer can do.

## What it does

- **Projects** - make an ODK project, empty or from an ontology you have; open one, or clone it
  from GitHub; turn an ontology and its earlier versions into an ODK repository with a tag per
  release.
- **Build and release** - ODK's own targets, run by OntoBoard itself inside Protégé, or in ODK's
  container; compare releases; obsolete terms; stamp provenance.
- **ROBOT, embedded** - report, measure, profile, explain, SPARQL, transform, export, template,
  rename and extract, on the ontology Protégé has open.
- **A board of the whole ontology** - every class, property and individual, drawn from the axioms
  you wrote, with the reasoner's conclusions and why they hold.
- **Reuse** - 162 design patterns, ranked against your ontology; terms from other ontologies found
  by label and imported as ODK modules.
- **Spreadsheets to a knowledge graph** - one ROBOT template per kind of thing
  ([how](docs/knowledge-graph.md)).
- **Questions become tests**, notes and discussion links travel with the ontology, and
  **collaboration** works live or through git.

## Documentation

**[ebrahimnorouzi.github.io/ontoboard](https://ebrahimnorouzi.github.io/ontoboard/)**, the same
pages as in [`docs/`](docs/): [the guide](docs/guide.md), [getting started](docs/getting-started.md),
[what the board draws](docs/canvas.md), [working with ODK](docs/odk-workflow.md),
[collaboration](docs/collaboration.md), [limitations](docs/limitations.md) and
[development](docs/development.md).

## Status

Actively developed: about 6,000 unit tests, every release smoke-tested in both Protégé versions
and on Windows in CI, and a receipt per release in
[`protege-plugin/tools/smoke-receipt/`](protege-plugin/tools/smoke-receipt/).

---

Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/) at
[ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering).
Licensed under [Apache 2.0](LICENSE).
