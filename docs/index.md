# OntoBoard Documentation

OntoBoard ships two clients: a **Protégé Desktop plugin** and a **web application**. See the
[README](../README.md) for what each is for.

## Start here

- [Getting started](getting-started.md) — install and first session, for both clients
- [Features](features.md) — what each client actually does today
- [Feature parity](feature-parity.md) — what the web app does, what the plugin does,
  and for each gap whether it is worth building
- [Limitations and roadmap](limitations.md) — what they do not do, specifically

## Going deeper

- [Architecture](architecture.md) — how the plugin binds to Protégé's model, and how the
  web application's collaboration works
- [Development](development.md) — building, testing, and the OSGi constraints that are
  load-bearing
- [API reference](api-reference.md) — the web application's REST API

## Design record

[superpowers/](superpowers/) holds the specs and implementation plans, including the
platform evaluation that chose Protégé over a Miro app, and the decisions that were later
reversed — the plugin was briefly intended to replace the web application, and does not.
