# Development Guide

Two pieces: the Protégé plugin (`protege-plugin/`, Java) and the optional collaboration service
(`collab/`, Node), needed only for live collaboration.

---

## Protégé plugin

Built and tested on **Temurin 25**; the plugin stays Java 8-compatible for Protégé 5.5.0 and 5.6.x
because `pom.xml` sets `maven.compiler.release` to `8`.

```bash
cd protege-plugin
mvn clean test        # about 6,000 tests (some need Docker or node, and skip without them)
mvn clean package     # -> target/ontoboard-<version>.jar
```

- **Always `clean`.** Incremental builds have passed tests against stale classes.
- **One Maven at a time.** Two runs sharing `target/` corrupt each other; a failure you cannot
  reproduce alone is this until proven otherwise.

### Versions

Tags are semantic (`v1.84.0`); a release is titled with its date, taken from the tagged commit
(`OntoBoard 1.84.0 (2026-10-05)`). The version lives only in `protege-plugin/pom.xml`. **Bump the
minor in the same commit as any change to `src/main/`** - Felix caches bundles by name and
version, so two builds under one version can leave the older one running. A hook enforces it:

```bash
git config core.hooksPath .githooks    # once per clone
```

`--no-verify` for a commit it misjudges, with the reason in the message.

### End-to-end runs

```bash
mvn -o test -Dtest=PizzaEndToEndTest -De2e.out=<dir>
mvn -o test -Dtest=PizzaProjectTest -Dpizza.repo=<dir> -Dpizza.version=v1   # or v2, v3
```

The first drives a pizza ontology in three releases through the board's projection, ELK, HermiT,
ROBOT and the release diff; the second writes a whole ODK project, one release at a time.

### Deploy locally

```bash
rm -f "$PROTEGE/plugins"/ontoboard-*.jar     # singleton bundle: remove the old one
cp target/ontoboard-<version>.jar "$PROTEGE/plugins/"
unzip -t "$PROTEGE/plugins/ontoboard-<version>.jar"   # a truncated copy never appears
```

### Reading the log

`~/.Protege/logs/protege.log`; search for `ontoboard`.

| Message | Cause |
|---|---|
| `Importing java.* packages not allowed` | bnd emitted `java.*` imports; `!java.*` is required |
| `missing requirement osgi.ee JavaSE 11` | an embedded jar has Java 11 classes; `<_noee>true</_noee>` suppresses it |
| `ClassNotFoundException` on a `plugin.xml` class | that class's package is not in `Import-Package` |
| `zip END header not found` | truncated jar - copy again |

### Constraints that are load-bearing

`BundleConfigurationTest` asserts each of these:

- **`<maven.compiler.release>8</maven.compiler.release>`**, not `source`/`target` - only
  `release` restricts the API to Java 8's.
- **`!java.*` first and `*;resolution:=optional` last in `Import-Package`.**
- **Explicit clauses for packages named only in `plugin.xml`** - bnd cannot see them.
- **`<_noee>true</_noee>`** - some of ROBOT's dependencies carry Java 11 classes.
- **OWL API and Guava are `provided`, everything else embedded** - the host exports both.
- **`owlapi-osgidistribution` is pinned.**

### The tab layout

`viewconfig-ontoboardtab.xml` describes the tab; `ViewConfigTest` checks it, because Protégé
ignores what it does not recognise. `VSNode` puts its children side by side and `HSNode` stacks
them; a `CNode` with several `Component`s shows them as tabs; a `pluginId` is
`<bundle symbolic name>.<extension id>`; `${project.artifactId}` is filled in by resource
filtering.

### Testing rules

- Tests run headless: never construct `mxGraphComponent`, `mxGraphOutline` or
  `SchemaCanvasView` in a test.
- Put decisions in plain classes and keep Swing a thin shell.
- Ask whether a test would still fail if the code were deleted: break a fix and watch the test
  fail before trusting it.

### Layout of the plugin

```
de.fizkarlsruhe.ise.ontoboard
├── views/      SchemaCanvasView — the Protégé ViewComponent
├── canvas/     JGraphX rendering, styles, legend, layouts, export, selection bridge
├── model/      OWL -> canvas projection, labels
├── axiom/      entity creation, OWLAx edge axioms, axiom removal
├── layout/     sidecar persistence
├── odk/        ODK projects: the wizard, ODK's seed from its bundled templates (odk/seed),
│               OntoBoard's own engine for ODK's Makefile (odk/make), build, release, update
└── robot/      in-process ROBOT operations
```

**The board is a view over Protégé's model, never a parallel model:** every change is an
`OWLOntologyChange` applied through `OWLModelManager`, so Protégé's undo and tabs stay right.

---

## The documentation site

MkDocs Material, published by `.github/workflows/pages.yml`:

```bash
pip install -r docs-requirements.txt
python tools/generate-pattern-pages.py      # docs/patterns/ is generated, not committed
ONTOINK_REASONER=none mkdocs build --strict # without the variable, a page can take seconds to reason
```

## The collaboration service

```bash
cd collab
npm install
npm test
SECRET_KEY=dev-secret node server.mjs      # it refuses to start without one
```

Two ports on one `Y.Doc`: Hocuspocus on `COLLAB_PORT` (1234) for Yjs, and a JSON bridge on
`BRIDGE_PORT` (1235) for the plugin; both demand the same credential (`hocuspocusAuth` beside
`authenticate` in `bridge.mjs`). It carries awareness and an operation log only - no ontology, no
database. The operation types are listed twice, in `OPERATION_TYPES` (`bridge.mjs`) and
`OntologyOperation.TYPES` (Java); a new type goes into both.

---

## Records for maintainers

Not in the menu: [what is supported](requirements-status.md),
[feature parity](feature-parity.md) with the web application, [measurements](measurements.md),
and [compared with OntoGraf](compared-with-ontograf.md). Design specs and plans are kept by the
maintainer outside the repository; ask before changing the architecture.
