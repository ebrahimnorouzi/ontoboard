# OntoBoard Documentation

A Protégé Desktop plugin for running the ODK and ROBOT pipeline inside the editor. See the
[README](../README.md) for what it is and how to install it.

## Start here

- [Getting started](getting-started.md) — install and first session
- [Features](features.md) — what the plugin does, feature by feature
- [Working with ODK](odk-workflow.md) — the ODK workflow step by step, and exactly which steps
  OntoBoard does for you. Start here if you already maintain an ODK repository
- [Collaboration](collaboration.md) — live sessions, and how to run the server
- [Testing collaboration locally](testing-collaboration-locally.md) — two Protégé instances on
  one machine

## Honest accounting

- [Limitations and roadmap](limitations.md) — what it does not do, specifically, with the
  reasoning behind each gap and the defects found along the way
- [Feature parity](feature-parity.md) — the feature inventory, measured against the OntoBoard
  web application the plugin grew out of

## Going deeper

- [Development](development.md) — building, testing, and the OSGi constraints that are
  load-bearing
- [Architecture](architecture.md) — how the pieces fit together

## Design record

[superpowers/](superpowers/) holds the specs and implementation plans, including the platform
evaluation that chose Protégé over a Miro app, and decisions that were later reversed.

Each release also ships a receipt under
[`protege-plugin/tools/smoke-receipt/`](../protege-plugin/tools/smoke-receipt/) recording what was
verified in a real Protégé, on which hosts, and what was not.

---

> **A note on the web application.** OntoBoard began as a browser application with a Python
> backend, and this repository used to carry both. It is now the plugin alone. The web
> application, its services and the compose files are still in the history, at the tag
> [`web-app-final`](https://github.com/ebrahimnorouzi/ontoboard/tree/web-app-final). Several
> documents here still measure the plugin against it, which is why its name appears: it was the
> yardstick, not a dependency.
