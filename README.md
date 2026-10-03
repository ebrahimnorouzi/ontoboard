# OntoBoard

**Run your whole ODK and ROBOT pipeline inside Protégé.** No terminal, no `make`, no Docker.

A Protégé Desktop plugin for people who maintain OBO-style ontologies: scaffold an ODK project,
build it, run ROBOT's report, explain what the reasoner concluded, refresh your imports and cut a
release — all from the editor you already have open, against the ontology already in front of you.

[![Download](https://img.shields.io/badge/download-latest%20release-blue)](https://github.com/ebrahimnorouzi/ontoboard/releases/latest)

---

## Why

The ODK is excellent and it lives in a container. The usual day looks like this: edit in Protégé,
switch to a terminal, `sh run.sh make test`, read a log, switch back, find the term, fix it, run it
again. Every loop crosses a tool boundary, and on Windows it crosses Docker as well.

OntoBoard embeds **ROBOT itself** — not a wrapper around a binary, the library — so the same
operations run in-process, on the ontology Protégé has open, and report into a dialog you can read
and save. A project OntoBoard scaffolded runs seven of its eight generated build targets with
**nothing installed at all**: no make, no robot, no shell, no container, on any platform.

Where a container genuinely is needed, OntoBoard uses the one your project declares — or Podman,
or a native `odk install` environment. *OntoBoard → Project → Check requirements* tells you what
you can do right now and what to install for the rest.

## Install

1. Download `ontoboard-<version>.jar` from the [latest release](https://github.com/ebrahimnorouzi/ontoboard/releases/latest).
2. **Delete any older `ontoboard-*.jar` from Protégé's `plugins/` folder.** Two copies will not
   resolve.
3. Drop the jar into `plugins/` and restart Protégé.
4. Open the board with *Window → Tabs → OntoBoard*.

Protégé Desktop **5.6.x or 5.5.0**, both smoke-tested in the real host on every release. Java 8+
(Protégé bundles its own). The jar is large because ROBOT and its dependencies are inside it.

## What it does

**Project** — scaffold a new ODK project from a wizard, or open an existing one. Check what
tooling you have. Edit ID ranges. Audit and refresh imports. Clone a project from GitHub.

**Build and release** — run the project's build targets, in-process where possible, in its own
container or a native ODK environment where not. Compare two releases axiom by axiom. Stamp
provenance. Obsolete a term properly.

**ROBOT, embedded** — report, measure, profile, explain, SPARQL, transform, export terms, template,
rename IRIs, extract import modules. Nine of these are driven end to end in the real Protégé host
on every release, and nine have been compared against the real `robot` command to make sure the
answers match.

**A canvas that reads your axioms** — classes, individuals and the relations between them, drawn
from the axioms you actually wrote: restrictions inside `EquivalentClasses` and conjunctions, not
only the three shapes most tools read. Switch on the reasoner's conclusions, then right-click a
dotted edge and ask **why** — it answers with the axioms that force it.

**Notes and provenance** — editorial notes that travel with the ontology, discussion links to your
tracker, and authorship stamped on what you change.

**Collaboration** — live shared sessions with cursors, or git: status, stage, commit, pull, push,
branch, without leaving the editor.

## Documentation

- [Getting started](docs/getting-started.md)
- [Features](docs/features.md)
- [Working with ODK](docs/odk-workflow.md) — the ODK workflow step by step, and exactly which
  steps OntoBoard does for you
- [Collaboration](docs/collaboration.md)
- [Limitations and roadmap](docs/limitations.md) — an honest inventory of what it does not do
- [Architecture](docs/architecture.md) · [Development](docs/development.md)

## Status

Early and actively developed, but held to a standard: every release is smoke-tested in both
Protégé versions, the plugin self-checks on startup, and each release ships a receipt in
[`protege-plugin/tools/smoke-receipt/`](protege-plugin/tools/smoke-receipt/) recording what was
verified and what was not.

---

Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/) at
[ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering).
Licensed under [Apache 2.0](LICENSE).
