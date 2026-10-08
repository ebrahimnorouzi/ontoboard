# Development Guide

Two pieces: the Protégé plugin (`protege-plugin/`, Java) and the collaboration service
(`collab/`, Node). The plugin is the product; the service is optional and only needed for live
collaboration.

---

## Protégé plugin

```bash
cd protege-plugin
mvn clean test        # 1648 tests (some need Docker or node, and skip without them)
mvn clean package     # -> target/ontoboard-<version>.jar
```

**Always `clean`.** Incremental compilation in this module has repeatedly reported passing
tests compiled against stale classes — on one occasion hiding a source file that did not
compile at all. If a result matters, delete `target/` first.

### Versions are semantic, releases are dated

The tag is a semantic version — `v1.84.0` — and the release it publishes is titled
`OntoBoard 1.84.0 (2026-10-05)`. Both, because the two audiences ask different questions: a
version answers *is this newer than what I have*, and a date answers *how old is this*, which is
the first thing an ODK user asks, since ODK's own artefacts and the OBO releases a project
publishes are all dated.

The date comes from the tagged commit rather than from the clock, so re-running the release
workflow on an old tag reproduces the title that release already has.

Tags stay semantic rather than becoming dates. Renaming the existing ones would break every
download link already written down, and the jar filename carries the version either way.

### Bump the version in the same commit as the change

The version lives in exactly one place, `protege-plugin/pom.xml`; bnd derives
`Bundle-Version` from it. Bump the minor for any release anyone else might install.

This is not bookkeeping. Twenty-six commits once shipped under an unchanged `1.18.0`,
covering ROBOT transforms, term import, git tooling, the ODK build runner, release
comparison, obsoletion, profile checking, provenance and ROBOT templates. The result was
that `ontoboard-1.18.0.jar` identified at least twenty-six different builds, nobody could
say which one they had, and — because Felix keys its bundle cache on symbolic name *and*
version — dropping a newer same-versioned jar into `plugins/` can leave the cached older
one running. Hence the `rm -f` below, and hence this paragraph.

The rule was then broken three times in a row by the person who wrote it, twice within four
commits of writing it. So it is a hook now rather than a paragraph:

```bash
git config core.hooksPath .githooks    # once per clone
```

`.githooks/pre-commit` refuses a commit that touches `protege-plugin/src/main/` without changing
the version. Use `--no-verify` for the commits it misjudges, and say why in the message.

### End-to-end run

```bash
mvn -o test -Dtest=PizzaEndToEndTest -De2e.out=<dir>
```

Builds a pizza ontology in three releases and drives it through the canvas projection, ELK,
HermiT, ROBOT and the release diff, writing every report to `<dir>`. It does not open
Protégé and draws nothing, so it catches defects in *what* the canvas would show and not in
the painting of it. Two shipped defects were found this way — `Add all` silently dropping
every property, and inferences never being computed for individuals — because both were
invisible to a unit test of the thing itself.

`PizzaProjectTest` goes further and writes a whole ODK project, one release at a time:

```bash
mvn -o test -Dtest=PizzaProjectTest -Dpizza.repo=<dir> -Dpizza.version=v1   # or v2, v3
```

### Deploy locally

```bash
rm -f "$PROTEGE/plugins"/ontoboard-*.jar     # singleton bundle: remove the old one
cp target/ontoboard-<version>.jar "$PROTEGE/plugins/"
```

**Verify the copy.** A 64 MB jar can be truncated by a copy that reports success, and the
symptom is a plugin that silently never appears:

```bash
unzip -t "$PROTEGE/plugins/ontoboard-<version>.jar"   # must report no errors
```

This has actually happened during development and cost a debugging cycle. `ZipException:
zip END header not found` in `~/.Protege/logs/protege.log` means a bad copy, not a bad
build.

### Reading the log

`~/.Protege/logs/protege.log` is authoritative when the plugin misbehaves. It is large;
search for `ontoboard`. The failures seen so far, in the order they were hit:

| Message | Cause |
|---|---|
| `Importing java.* packages not allowed` | bnd emitted `java.*` imports; `!java.*` is required |
| `missing requirement osgi.ee JavaSE 11` | an embedded jar has Java 11 classes; `<_noee>true</_noee>` suppresses it |
| `ClassNotFoundException` on a `plugin.xml` class | that class's package is not in `Import-Package` |
| `zip END header not found` | truncated jar — re-copy |

### Constraints that are load-bearing

Several `pom.xml` settings look arbitrary and are not. `BundleConfigurationTest` asserts
each one, with a failure message explaining the consequence, so they cannot be removed by
accident.

- **`<maven.compiler.release>8</maven.compiler.release>`** — not `source`/`target`. Only
  `release` restricts the *API surface*; `source`/`target` set the bytecode version while
  still allowing calls to Java 9+ methods that fail at runtime on Protégé's JRE.
- **`!java.*` first in `Import-Package`** — OSGi forbids importing `java.*`, and Felix
  rejects the whole bundle at install time if any are present.
- **`*;resolution:=optional` last** — ROBOT's dependency tree references Saxon, logback,
  POI, javaparser, bouncycastle and Scala. Mandatory imports for code that is never called
  would make the bundle unresolvable.
- **Explicit clauses for packages named only in `plugin.xml`** — bnd cannot see a class
  referenced from XML, and a wildcard will not force the import. A wildcard *filters*
  packages bnd already decided it needs; it does not add new ones.
- **`<_noee>true</_noee>`** — bnd derives an execution-environment requirement from the
  highest class-file version anywhere in the bundle, including embedded jars. Three of
  ROBOT's transitive dependencies carry Java 11 classes.
- **OWL API and Guava are `provided`, everything else embedded** — the host exports both.
  Embedding our own Guava would cause `ClassCastException` at every OWL API boundary that
  returns a Guava `Optional`, which OWL API 4 does.
- **`owlapi-osgidistribution` is pinned** — it is a fat jar containing every OWL API class,
  and if left unpinned it shadows correctly-pinned siblings.

### The tab layout, and one trap in it

`viewconfig-ontoboardtab.xml` describes the OntoBoard tab in Protégé's mdock format.
`ViewConfigTest` asserts its structure, because nothing here fails loudly: Protégé parses the
file with a SAX handler that ignores what it does not recognise, so a mistake produces a tab
that *looks* fine.

- **`VSNode` puts its children side by side; `HSNode` stacks them.** The names describe the
  divider, not the arrangement. This is the opposite of most people's first reading, and the
  plugin shipped once with the hierarchy underneath the canvas because of it. `VerticalSplitter`
  sets a `W_RESIZE` cursor and `HorizontalSplitter` an `N_RESIZE` one, which settles the
  question; Protégé's own `viewconfig-classestab.xml` uses `VSNode` for its left-hand column.
- **A `CNode` holding several `Component`s renders them as tabs.** One `CNode` per view would
  give slivers instead. This is how the entity column fits six views into 28% of the width.
- **A `pluginId` is `<bundle symbolic name>.<extension id>`**, resolved at runtime against the
  extension registry. A typo yields an empty panel and no log line, so
  `ViewConfigTest.everyReferencedViewIsDeclaredByABundleOnTheClasspath` resolves every id
  against the `plugin.xml` files actually on the classpath.
- **`${project.artifactId}` is substituted by resource filtering**, which is why
  `src/main/resources` is filtered in the pom. With filtering off, the token reaches the jar and
  the canvas panel comes up empty.

Reusing Protégé's views rather than writing our own is deliberate: the class hierarchy has
search, rendering options, deprecation handling and a context menu that would take months to
match, and reusing it means selection in the tree and selection on the canvas are the same
selection.

### Testing rules

- Tests run **headless**. Never construct `mxGraphComponent`, `mxGraphOutline` or
  `SchemaCanvasView` in a test — all need Swing or a live `OWLEditorKit`.
- Put decisions in plain classes so they are testable, and keep Swing as a thin shell.
  `EdgeAxioms`, `EntityFactory`, `OdkProjectConfig` and `QualityReport.parse` exist in that
  shape for this reason.
- **Ask whether a test would still fail if the code under test were deleted.** Four tests in
  this project passed while measuring nothing — one depended on hash iteration order, one
  never populated the map it asserted on. When fixing a bug, break the fix deliberately and
  confirm the test fails before trusting it.

### Layout of the plugin

```
de.fizkarlsruhe.ise.ontoboard
├── views/      SchemaCanvasView — the Protégé ViewComponent
├── canvas/     JGraphX rendering, styles, legend, layouts, export, selection bridge
├── model/      OWL -> canvas projection, labels
├── axiom/      entity creation, OWLAx edge axioms, axiom removal
├── layout/     sidecar persistence
├── odk/        project wizard and ODK scaffolding
└── robot/      in-process ROBOT operations
```

The central rule: **the canvas is a view over Protégé's model, never a parallel model.**
Every mutation becomes `OWLOntologyChange` objects applied through `OWLModelManager`, which
is what makes Protégé's undo and its other tabs stay correct for free.

---

## The documentation site

Built with MkDocs Material and published by `.github/workflows/pages.yml`. Two things about
building it locally that are not obvious:

```bash
pip install -r docs-requirements.txt
python tools/generate-pattern-pages.py      # writes the 159 pattern pages into docs/patterns/
ONTOINK_REASONER=none mkdocs build --strict
```

**The generator is not optional.** `docs/patterns/` is gitignored and generated from
`patterns/index.tsv` at build time, so a checkout has no pattern pages until you run it. The nav
entry points at `patterns/index.md`, so `--strict` will complain if you skip it.

**`ONTOINK_REASONER=none` is the difference between 7 seconds and a quarter of an hour.** If
`owlready2` happens to be importable in your environment, ontoink runs a HermiT consistency check
per fence — a JVM per page — measured at about 4.7s each across 159 pages. `docs-requirements.txt`
deliberately installs ontoink *without* its `[reasoning]` extra so CI never has owlready2, but a
developer who has it for something else will hit this. The variable switches it off explicitly.

## The collaboration service

The one part of OntoBoard that is not inside Protégé. It lives in `collab/` and is a small
Node server:

```bash
cd collab
npm install
npm test                                   # 55 tests
SECRET_KEY=dev-secret node server.mjs      # it refuses to start without one
```

It opens two ports onto **one** `Y.Doc`: Hocuspocus on `COLLAB_PORT` (1234) speaks the binary
Yjs protocol, and the JSON bridge on `BRIDGE_PORT` (1235) speaks to the plugin, which has no
Yjs implementation. The bridge attaches through `openDirectConnection`, so both kinds of client
are in one session.

**Because they share the document, both ports must demand the same credential.** Until 1.86.0
the Hocuspocus handler returned an "anonymous" "viewer" for a missing or invalid token, and
Hocuspocus authenticates whenever that handler returns rather than throws — so it accepted
every connection, and the bridge's own check could be stepped around by using the other port.
The two decisions now share `hocuspocusAuth`, next to `authenticate` in `bridge.mjs`.

It carries **awareness and an operation log only**. No ontology is stored: each editor's copy
lives in their own files, and the log is how edits reach the other people in the session. It
needs no database and no volume.

### Collaboration internals

The vocabulary is the eighteen types in `OPERATION_TYPES` (`bridge.mjs`) and
`OntologyOperation.TYPES` (Java). Both are gates, not descriptions — an operation whose type is
in neither is refused — so a new type has to be added to both or the edit silently never
arrives.

The plugin speaks JSON onto the same `Y.Doc` rather than reimplementing a merge engine in Java.
`bridge-interop.test.mjs` asserts the storage format from the Yjs side, because the two ends
once disagreed about whether operations were JSON strings or objects while every unit test on
both sides passed.

---

## Specs and plans

`docs/superpowers/` holds the design specs and implementation plans, including decisions
that were later reversed and why. Worth reading before changing architecture — several
constraints above look removable until you read what they cost.
