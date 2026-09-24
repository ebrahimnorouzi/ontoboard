# OntoBoard

Collaborative, visual ontology engineering — as a **Protégé Desktop plugin** and as a
**web application**.

**Developed by [Ebrahim Norouzi](https://ebrahimnorouzi.github.io/)** at
[ISE / FIZ Karlsruhe](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering) ·
[ebrahim.norouzi@fiz-karlsruhe.de](mailto:ebrahim.norouzi@fiz-karlsruhe.de)

---

## Two clients, one purpose

Ontology engineering pulls in two directions. Domain experts want to sketch, discuss and
work together; maintainers want reasoners, quality reports and a reproducible release
pipeline. No single host does both well, so OntoBoard ships as two clients.

| | Protégé plugin | Web application |
|---|---|---|
| Runs in | Protégé Desktop | Browser + Docker |
| Best at | OWL depth, reasoning, ODK/ROBOT pipeline | Real-time collaboration, discussion |
| Multi-user | yes — live via your own server, or git | yes — CRDT, cursors, comments, tasks |
| Needs a server | no | yes |
| Status | early, actively developed | complete |

They are not alternatives to choose between: the plugin runs inside the editor ontologists
already use, and the web application is where a team works together.

---

## Protégé plugin

### Requirements

- **Protégé Desktop 5.6.x recommended.** It loads on 5.5.0 too, but with a reduced ROBOT
  surface — see [Host support](#host-support).
- Java 8 or newer (Protégé bundles its own JRE).

The jar is ~64 MB because ROBOT and its dependencies are embedded so the pipeline runs
in-process, with no Docker and no subprocess.

### Install

1. Download `ontoboard-<version>.jar` from the releases page.
2. **Delete any older `ontoboard-*.jar` from Protégé's `plugins/` folder first.** The bundle
   is `singleton:=true`, so two versions side by side prevent it resolving and the tab
   silently never appears.
3. Copy the jar into `plugins/`.
4. Restart Protégé and open **Window → Tabs → OntoBoard**.

See [INSTALL.md](protege-plugin/INSTALL.md) for details and troubleshooting.

### What it does today

**Start a project** — *Tools → New ODK Project…* asks for an ontology ID, title, base IRI
and license, writes a complete ODK repository (edit and release files, ODK YAML, the
generated/custom Makefile pair, catalog, ID ranges, ROBOT report profile, SPARQL checks, a
GitHub Actions workflow, README), then opens the edit file so you start editing immediately.
No Docker needed to create it; running its `make` targets needs `make` and ROBOT on PATH.

**Work in one place** — the tab puts Protégé's own entity views in a tabbed column on the
left — Classes, Object properties, Data properties, Annotation properties, Datatypes,
Individuals — with the canvas beside them. Select something in a tree and click *Add selected* on
the canvas toolbar; the button names what it will add, so there is no guessing.

**Draw the schema** — an opt-in canvas that starts empty and grows as you add entities,
because Protégé routinely opens ontologies with 100,000+ classes and rendering all of them
would hang. Double-click empty canvas to create a class there, drag existing terms in from
Protégé's own trees, or press *Add all* for a whole small ontology — classes, individuals,
object properties and data properties alike. Expand a node's neighbours one hop at a time,
and arrange with hierarchical, organic, circle or grid layouts. Nodes are labelled from
`rdfs:label` and coloured by namespace, so imported vocabulary is distinguishable at a
glance.

**See what the reasoner concluded** — press *Inferences* with a reasoner running and the
board also draws what it worked out: subsumptions the ontology does not state, and the
classes an individual turns out to belong to. Grey and dotted, never mistaken for an
asserted axiom, and unsatisfiable classes go red.

**Edit real axioms** — drawing a relation asks which property and *how the arrow should be
read*, offering the six OWLAx candidate forms (existential, scoped/global domain and range,
functionality) with a plain-language explanation of each. Every change goes through
Protégé's `OWLModelManager`, so it appears instantly in the class hierarchy and undoes with
Protégé's own undo.

**Keep the ontology clean** — canvas layout lives in a sidecar file
(`<ontology>.ontoboard.json`) beside the ontology, never inside it, so ROBOT `report`,
`diff` and release artifacts are unaffected. Removing a node from the canvas never deletes
axioms; deleting an axiom is a separate, confirmed action.

**Work together** — either live or through git, and both are first-class. Live needs a server
your team runs: point each person's plugin at it and you share the board, seeing each other's
edits and cursors in the same session as any browser users. No server, or you would rather not
run one? Leave the fields empty and collaborate through git — commit and push as usual. Setup,
and what the live vocabulary can and cannot carry, are in
[docs/collaboration.md](docs/collaboration.md).

**Run ROBOT and ODK without leaving Protégé** — the *OntoBoard* menu carries twenty
commands against the ontology you have open:

| Group | Commands |
|---|---|
| Project | New ODK project, Open from GitHub, ID ranges, Build, Imports, Release, Compare releases |
| ROBOT | Measure, Quality report, Profile, Transform (relax/reduce/repair/merge), Import terms, Template |
| Terms | Obsolete the selected term |
| Notes | Note on the selected term, All notes, Discussion link |
| Other | Git, Provenance, Collaboration |

*Template* turns a spreadsheet into axioms and reports every bad row at once rather than
stopping at the first. *Import terms* extracts a module from another ontology and wires up
the import, catalog entry and all, recording which upstream release it came from. *Compare
releases* says what changed term by term — added, obsoleted, redefined, moved — and writes
the release notes from it. *Obsolete* retires a term the OBO way so everything that
referenced it still resolves.

### Not built yet

The ontology design pattern library, SPARQL query panel, Widoco documentation, pull-request
support in the git tooling, and threaded comments (a term can be *linked* to its issue, but
the discussion lives there, not in the ontology). Full list, with the host constraints that
matter more, in [docs/limitations.md](docs/limitations.md).

### Host support

ROBOT runs in-process against the ontology Protégé has open. Which ROBOT operations are
available depends on the host's OWL API, because OWL API moved its RDF layer from Sesame to
RDF4J at 4.5.25 and `robot-core` targets the newer form:

| Protégé | OWL API | ROBOT |
|---|---|---|
| **5.6.x** | 4.5.29 | all operations, including report, query and export |
| 5.5.0 | 4.5.9 | loading, reasoning, saving and conversion only |

One jar serves both — OWL API is imported from the host rather than embedded, so the
available surface simply follows the host. On 5.5.0 the plugin explains which operations are
unavailable and why rather than failing obscurely.

### Building

```bash
cd protege-plugin
mvn clean package          # -> target/ontoboard-<version>.jar
mvn test                   # 1048 tests
```

Java 8 bytecode is emitted deliberately (`maven.compiler.release=8`) so the bundle loads on
Protégé's bundled JRE. See [docs/development.md](docs/development.md) for the OSGi
constraints — several are load-bearing and guarded by tests.

---

## Web application

The original OntoBoard: a browser-based environment with real-time collaboration built on an
ontology-aware operation CRDT, plus the full ODK/ROBOT pipeline behind a REST API.

```bash
./run.sh          # build and start everything
```

Then open [http://localhost:3000](http://localhost:3000) and log in with `admin` / `admin`.
Requires Docker and Docker Compose.

It provides multi-user editing with cursor sharing and semantic merge, threaded comments
with @mentions, a Kanban task board, sharing and invite links, the ODP pattern library, the
CSV → ROBOT template wizard, SPARQL, quality reports and Widoco documentation.

Architecture and API details: [docs/architecture.md](docs/architecture.md),
[docs/api-reference.md](docs/api-reference.md).

---

## Documentation

- [Getting started](docs/getting-started.md)
- [Architecture](docs/architecture.md)
- [Features](docs/features.md)
- [Development](docs/development.md)
- [Collaboration](docs/collaboration.md) — live and git modes, server setup
- [Testing collaboration locally](docs/testing-collaboration-locally.md)
- [Feature parity](docs/feature-parity.md) — plugin vs web application, gap by gap
- [Limitations and roadmap](docs/limitations.md)
- [API reference](docs/api-reference.md) — web application
- Design specs and implementation plans: [docs/superpowers/](docs/superpowers/)

## Related work

[CoModIDE](https://comodide.com/) brought graphical, pattern-driven ontology modelling into
Protégé, and [OWLAx](https://arxiv.org/abs/1808.10105) established the candidate-axiom
vocabulary this plugin's relation editor uses. OntoBoard's plugin is aimed at a different
gap: bringing the ODK/ROBOT release pipeline into Protégé without a Docker prerequisite.

Note that canvas layout is stored in a sidecar file rather than as OPLa-SD annotations, so
diagrams are **not** interchangeable with CoModIDE — a deliberate trade to keep the ontology
byte-clean.

## License

[Apache License 2.0](LICENSE). Third-party components and their licenses are listed in
[NOTICE](NOTICE).

---

OntoBoard is a research prototype developed at the
[Information Service Engineering](https://www.fiz-karlsruhe.de/en/forschung/information-service-engineering)
group of [FIZ Karlsruhe](https://www.fiz-karlsruhe.de).
