# OntoBoard Documentation

OntoBoard ships two clients: a **Protégé Desktop plugin** and a **web application**. See the
[README](../README.md) for what each is for.

## Start here

- [Getting started](getting-started.md) — install and first session, for both clients
- [Features](features.md) — the **web application**, feature by feature. For the plugin,
  see "What it does today" in the [README](../README.md) and
  [feature parity](feature-parity.md)
- [Collaboration](collaboration.md) — the two modes, and how to set up a server
- [Testing collaboration locally](testing-collaboration-locally.md) — two Protégé instances on one machine
- [Feature parity](feature-parity.md) — what the web app does, what the plugin does,
  and for each gap whether it is worth building
- [Limitations and roadmap](limitations.md) — what they do not do, specifically

## Going deeper

- [Architecture](architecture.md) — the **web application's** services and collaboration.
  The plugin's binding to Protégé, and the OSGi constraints behind it, are in
  [development](development.md)
- [Development](development.md) — building, testing, and the OSGi constraints that are
  load-bearing
- [API reference](api-reference.md) — the web application's REST API

## Design record

[superpowers/](superpowers/) holds the specs and implementation plans, including the
platform evaluation that chose Protégé over a Miro app, and the decisions that were later
reversed — the plugin was briefly intended to replace the web application, and does not.
