# CANCELLED - DO NOT EXECUTE

**Cancelled 2026-08-24.** This plan deletes the web application. That is now wrong.

After using the plugin, the user confirmed the requirement is **both** the ontology
tooling **and** collaborative editing. Protege cannot host collaboration, sharing,
comments or a task board - spec section 3 dropped them precisely because a desktop
plugin has no place to put them. The web application already implements all of it
(operation-based CRDT, semantic merge, comments, tasks, sharing), so deleting it would
mean re-implementing a working system in Java Swing for no gain.

The plugin is therefore a **second client**, not a replacement. `frontend/`, `backend/`,
`worker/` and `collab/` stay. The README still needs rewriting to describe both products
honestly, but that is a documentation task, not a retirement - it will be re-planned.

Superseded by: spec section 2 decision D3 (reversed).

---

# OntoBoard Protégé Plugin — Plan 6: Retirement of the Web Application

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `main` contain only the Protégé plugin, at the repository root, with README and documentation that describe the plugin truthfully.

**Architecture:** Three mechanical steps (safety tag, flatten, delete) followed by a documentation rewrite, then a squash merge to `main`. No production code changes — if any test fails after flattening, the flattening is wrong, not the code.

**Tech Stack:** git, Maven, Markdown.

**Spec:** [`docs/superpowers/specs/2026-08-14-ontoboard-protege-plugin-design.md`](../specs/2026-08-14-ontoboard-protege-plugin-design.md) §12 phase 7.

**Prerequisite:** Plan 1 must be complete (Tasks 1–9 done, final whole-branch review clean). Do not start this plan before that.

## User decisions (recorded 2026-08-20)

| Decision | Choice |
|---|---|
| Layout | **Flatten** — the plugin becomes the repository root; `protege-plugin/` ceases to exist |
| Merge to `main` | **Squash to a single commit** |
| Push | Not requested. Do **not** push. Leave `main` local for the user to inspect. |

## Global Constraints

- **Do not push anything.** No `git push`, no PR. `main` stays local until the user pushes it themselves.
- **Do not change production Java code or tests.** This plan moves files and rewrites prose. The only permitted code-adjacent edits are path fixes that flattening genuinely requires.
- **The test suite must pass identically before and after flattening.** Record the count both times; any change means something broke.
- **Documentation must describe only what exists.** Plan 1 delivered the canvas, Protégé model binding, opt-in membership, sidecar layout, selection bridge, layouts, minimap and export. ROBOT commands, ODK, the pattern library, the CSV/template wizard, SPARQL and quality reports are **not built** (Plans 3–5). Never describe unbuilt features as available.
- **Never claim the plugin has been run in Protégé.** No task in Plan 1 could drive a GUI. Documentation must say the GUI is unverified.
- `paper/`, `patterns-repository/`, `mwo301.ttl` and `docs/superpowers/` are **kept**.

---

### Task R1: Safety tag and flatten the module to the repository root

**Files:**
- Move: every path under `protege-plugin/` up one level
- Modify: `.gitignore` (absorb `protege-plugin/.gitignore`)
- Delete: `protege-plugin/` (as a directory level)

**Interfaces:**
- Consumes: the completed Plan 1 tree.
- Produces: `pom.xml`, `src/main/`, `src/test/`, `INSTALL.md` at the repository root. Maven is thereafter run from the repository root.

- [ ] **Step 1: Record the pre-move baseline**

```bash
cd protege-plugin && mvn -q clean test 2>&1 | tail -5
```

Record the exact test total. Every later step compares against it.

- [ ] **Step 2: Create the safety tag**

The web application is what `paper/` describes. Tag it before deleting so the paper's artifact remains reachable at a stable reference.

```bash
git tag -a v1-webapp -m "OntoBoard web application, as described in paper/ontoboard-iswc2026.tex" HEAD
git tag -l
```

Local only — do not push it. The user can delete it with `git tag -d v1-webapp` if unwanted.

- [ ] **Step 3: Move the module contents to the root**

Use `git mv` so history follows the files:

```bash
git mv protege-plugin/pom.xml .
git mv protege-plugin/src .
git mv protege-plugin/INSTALL.md .
ls protege-plugin/
```

`INSTALL.md` exists only if Plan 1 Task 9 created it; skip that line if absent. Then handle the ignore file — append `target/` to the root `.gitignore` rather than moving the module one:

```bash
printf '\n# Maven\ntarget/\n' >> .gitignore
git rm protege-plugin/.gitignore
rmdir protege-plugin 2>/dev/null; ls -d protege-plugin 2>/dev/null || echo "protege-plugin/ is gone"
```

- [ ] **Step 4: Verify the build is identical from the new root**

```bash
mvn -q clean test 2>&1 | tail -5
```

Expected: the **same** test total as Step 1. If it differs, or if a test that reads `pom.xml` or `src/test/resources/...` now fails, the cause is a relative path assumption — fix the path, not the assertion, and report what you changed.

Then confirm the bundle still builds and is still correct:

```bash
mvn -q clean package && ls -l target/ontoboard-*.jar
unzip -p target/ontoboard-*.jar META-INF/MANIFEST.MF | tr -d '\r' | grep -E "^Bundle-(SymbolicName|Version)"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: promote the Protege plugin module to the repository root"
```

---

### Task R2: Delete the retired web application

**Files:**
- Delete: `frontend/`, `backend/`, `worker/`, `collab/`, `data/`, `docker-compose.yml`, `docker-compose.dev.yml`, `run.sh`, `build.sh`, `harvest_patterns.py`, `REQUIREMENTS.md`, `test_upload.ttl`, `docs/api-reference.md`

**Interfaces:**
- Consumes: the flattened tree from R1.
- Produces: a tree containing only the plugin, `paper/`, `patterns-repository/`, `docs/`, `mwo301.ttl`.

- [ ] **Step 1: Confirm nothing the plugin needs lives in the doomed directories**

Before deleting, prove the plugin does not reference them:

```bash
grep -rn "backend/\|frontend/\|worker/\|collab/" src/ pom.xml 2>/dev/null || echo "no references - safe"
```

`backend/seed/patterns/` holds the 13 ODPA seed patterns. Plan 1 does **not** use them (the pattern library is Plan 4), but they are referenced by spec §7.2 as future input. Copy them out before deleting the backend:

```bash
mkdir -p seed-patterns && cp -r backend/seed/patterns/* seed-patterns/ 2>/dev/null && ls seed-patterns | head
```

If `backend/seed/patterns/` does not exist, note that and continue.

- [ ] **Step 2: Delete**

```bash
git rm -r --quiet frontend backend worker collab docker-compose.yml docker-compose.dev.yml run.sh build.sh harvest_patterns.py REQUIREMENTS.md test_upload.ttl docs/api-reference.md
git rm -r --quiet data 2>/dev/null || true
git status --short | head -20
```

`data/` is gitignored, so it may not be tracked; the `|| true` covers that. Remove any leftover untracked `data/` directory from disk only if it is empty.

- [ ] **Step 3: Prune the ignore file**

The Python and Node entries are now dead. Rewrite `.gitignore` to:

```gitignore
# Maven
target/

# IDE
.idea/
*.iml
.classpath
.project
.settings/

# OS
.DS_Store
Thumbs.db
```

- [ ] **Step 4: Verify the build still passes with the web stack gone**

```bash
mvn -q clean test 2>&1 | tail -5
```

Expected: the same total as R1 Step 4. A failure here means a test depended on a deleted fixture — report it rather than deleting the test.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor!: remove the retired web application

The Protege plugin replaces the React/FastAPI/Docker stack. The web
application remains available at the v1-webapp tag, which is what
paper/ontoboard-iswc2026.tex describes."
```

---

### Task R3: Rewrite README.md

**Files:**
- Rewrite: `README.md`

This is the repository's front door and the most likely thing a paper reader opens. It must be accurate about what the plugin does today.

- [ ] **Step 1: Read what actually exists before writing a word**

Do not write from the old README or from this plan's summary. Establish the truth from the source:

```bash
ls src/main/java/de/fizkarlsruhe/ise/ontoboard/*/
grep -h "public " src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasLayouts.java | head
cat src/main/resources/plugin.xml
mvn -q clean test 2>&1 | tail -3
```

- [ ] **Step 2: Write the README**

Required sections, in this order:

1. **Title and one-sentence description.** A Protégé Desktop plugin for visual ontology engineering. Keep the ISE / FIZ Karlsruhe attribution and the author contact from the old README.
2. **Status.** State plainly that this is an early release: the visual canvas and Protégé integration work; the ROBOT/ODK pipeline, pattern library, CSV import, SPARQL and quality tooling are planned but not implemented. Link to `docs/limitations.md`.
3. **Requirements.** Protégé Desktop 5.5.0 or later (also loads on 5.6.x), Java 8 or later. Note that the bundle is ~62 MB because ROBOT and its dependencies are embedded for later releases.
4. **Installation.** Copy the jar into Protégé's `plugins/` directory, restart, then **Window → Tabs → OntoBoard**. State explicitly that the bundle is `singleton:=true`, so an older `ontoboard-*.jar` must be **deleted** before installing a new one, and that duplicate versions of any plugin can prevent bundles resolving. Reference `INSTALL.md` if it exists.
5. **Usage.** The canvas is opt-in and starts empty — that is deliberate, because Protégé opens ontologies with 100,000+ classes. Then: how to add a selected entity, how to expand neighbours one hop, that removing a node from the canvas never deletes axioms, that selection is synchronised with Protégé's own views both ways, the four layout algorithms by their menu names, the minimap, and PNG/SVG export.
6. **How layout is stored.** A sidecar JSON file named by appending to the ontology file name (`myont.owl` → `myont.owl.ontoboard.json`). The ontology itself is never modified by the canvas, so ROBOT `report`/`diff` and release artifacts are unaffected. Mention that this means layout is not interoperable with CoModIDE, which stores positions as OPLa-SD annotations inside the ontology.
7. **Building from source.** `mvn clean package`, output path, and the Java 8 bytecode / OWL API 4.5.9 constraints and why they exist (the host ships a Java 8 JRE and OWL API 4.5.9).
8. **Relationship to prior work.** One short, fair paragraph: CoModIDE brought graphical ODP modelling to Protégé; this project's intended contribution is bringing the ODK/ROBOT release pipeline into Protégé. Do not disparage it.
9. **Known limitations.** Link `docs/limitations.md`. Must include that the plugin has **not** been verified running in a Protégé GUI — every check so far has been static or headless.
10. **License.** If no `LICENSE` file exists, say so plainly rather than implying a license. Do **not** invent one.

Do not include: a REST API section, Docker instructions, a collaboration section, or a feature comparison table claiming unbuilt capabilities.

- [ ] **Step 3: Verify every claim**

Re-read the README against the source tree. For each capability claimed, name the class that implements it. Delete any claim you cannot trace to code. Report the list of things you removed for this reason.

- [ ] **Step 4: Commit**

```bash
git add README.md && git commit -m "docs: rewrite README for the Protege plugin"
```

---

### Task R4: Rewrite docs/

**Files:**
- Rewrite: `docs/index.md`, `docs/getting-started.md`, `docs/architecture.md`, `docs/features.md`, `docs/development.md`, `docs/limitations.md`
- Already deleted in R2: `docs/api-reference.md`

- [ ] **Step 1: `docs/architecture.md`**

The central idea, and the thing most worth explaining: **the canvas is a view over Protégé's model, never a parallel model.** Cover: edits become `OWLOntologyChange` objects applied through `OWLModelManager`, so Protégé's undo, dirty-tracking and every other tab stay in sync for free; `OntologyProjection` turns an ontology plus a membership set into plain node/edge value objects; `SchemaGraph` renders them with JGraphX; `CanvasLayoutStore` keeps presentation state in a sidecar. Include the package layout under `de.fizkarlsruhe.ise.ontoboard`. Note the three listeners the view registers and that all three are removed on dispose.

- [ ] **Step 2: `docs/features.md`**

Only what exists. For each feature name the implementing class. Include the projection rules: which OWL axiom shapes become which edges, that both existential restrictions and legacy `rdfs:domain`/`rdfs:range` pairs are rendered (the latter for compatibility with ontologies the retired web app produced), and that an edge appears only when both endpoints are on the canvas.

- [ ] **Step 3: `docs/getting-started.md`**

Install, then a worked first session using `mwo301.ttl` from the repository root: open it, add a class, expand one hop, arrange, export. Say where the sidecar file appears afterwards.

- [ ] **Step 4: `docs/development.md`**

Build and test commands run from the repository root. The constraints and their reasons: Java 8 bytecode via `maven.compiler.release=8` (the host bundles a Java 8 JRE, and `release` rather than `source`/`target` is required because only `release` restricts the API surface); OWL API pinned to 4.5.9 to match the host, with `robot-core` forced down from its declared 4.5.29; OWL API and Guava imported from Protégé rather than embedded, because embedding Guava would cause `ClassCastException` at every OWL API boundary that returns a Guava `Optional`; `log4j-api` excluded from the embed because it declares its own `Bundle-Activator` and bnd rejects two. Explain that `BundleConfigurationTest` guards these so they cannot silently regress. Note that tests must remain headless — no `mxGraphComponent`, `mxGraphOutline` or `SchemaCanvasView` in test code.

- [ ] **Step 5: `docs/limitations.md`**

Honest and specific:
- The GUI has never been verified running; all verification was static or headless. The toolbar, minimap, context menu and selection highlighting are unexercised.
- Not implemented: ROBOT commands, ODK scaffolding and Makefile execution, the pattern library, the CSV/ROBOT-template wizard, SPARQL, quality reports (ROBOT report, OOPS!, OQuaRE), Widoco documentation, import resolution, ID ranges, provenance stamping.
- No collaboration, accounts, sharing, comments or task board — deliberately dropped; the plugin is single-user.
- Canvas layout is not interoperable with CoModIDE (sidecar JSON vs OPLa-SD annotations).
- A punned IRI (declared as both class and individual) resolves non-deterministically for selection.
- Sidecar writes are not atomic; a crash mid-write can lose the layout, though never the ontology.
- ROBOT's XLSX template path would fail because `log4j-api` is excluded; TSV templates are unaffected.
- Guava 18.0 is only verified against ELK reasoning; other ROBOT code paths are untested against it.
- The host's default `-Xmx500M` is too low for the ROBOT operations planned in later releases.

- [ ] **Step 6: `docs/index.md`** — an accurate table of contents for the above. Remove the api-reference entry.

- [ ] **Step 7: Verify and commit**

Check every internal link resolves and that no document references `frontend/`, `backend/`, Docker, or a REST API:

```bash
grep -rn "backend\|frontend\|docker\|REST API\|localhost:8000\|localhost:3000" docs/*.md README.md || echo "clean"
git add docs/ && git commit -m "docs: rewrite documentation for the Protege plugin"
```

---

### Task R5: Squash-merge to main

Do this only after R1–R4 are committed and reviewed.

- [ ] **Step 1: Confirm the branch is clean and green**

```bash
git status --short
mvn -q clean test 2>&1 | tail -3
git log --oneline main..HEAD | wc -l
```

- [ ] **Step 2: Squash onto main**

```bash
git checkout main
git merge --squash feat/protege-plugin
git status --short | head -20
```

- [ ] **Step 3: Commit with the provenance the squash would otherwise discard**

The squash collapses every task and fix-round commit. The commit message is the only place that history survives, since the review ledger is gitignored. Write a message that records: what was built, that the web application moved to the `v1-webapp` tag, and the substantive defects the review process caught — the unenforced Java 8 API surface, the `return`-instead-of-`continue` that silently dropped subclass edges, the missing active-ontology listener, the incomplete layout reset, and the four tests that would have passed against an empty method body.

- [ ] **Step 4: Verify main builds, then STOP**

```bash
mvn -q clean test 2>&1 | tail -3
git log --oneline -3
```

**Do not push.** Report the state and hand back to the user.

---

## Definition of Done

- `main` contains the plugin at the repository root, builds, and passes the same test count as `feat/protege-plugin`.
- `frontend/`, `backend/`, `worker/`, `collab/` and the Docker/shell tooling are gone from `main`.
- The web application is reachable at the `v1-webapp` tag.
- README and every file in `docs/` describe only implemented behaviour, and state that the GUI is unverified.
- Nothing has been pushed.
