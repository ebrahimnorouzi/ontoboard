# Limitations and Roadmap

Honest inventory of what each OntoBoard client does not do. Kept specific rather than
reassuring, because a quality tool that overstates itself is worse than one that admits
gaps.

## Protégé plugin

### Not implemented

The plugin is early. These exist in the web application but not yet here:

- **Live collaboration** — built as of 1.9.0, but only for the seventeen operation types
  the web application understands. Equivalence, disjointness, property characteristics,
  chains, `owl:hasKey`, negative assertions, global domain and range, datatype definitions
  and imports do **not** travel; the plugin counts them and says so in the toolbar rather
  than dropping them silently. Use git for work on those. See
  [collaboration](collaboration.md).
- **Threaded comments and discussion** — a term can be linked to its issue with
  *Notes → Discussion link…*, which writes `IAO:0000233 term tracker item`, and editorial
  notes (`IAO:0000116`/`IAO:0000232`) carry an author and date. But the argument itself
  lives in the tracker: there is no reply thread inside the ontology, deliberately, because
  an unbounded mutable conversation would appear in every release and every diff.
- **Ontology design pattern library** — the bundled ODPA patterns are not exposed.
- ~~**SPARQL query panel**~~ — **done in 1.33.0.** *ROBOT → SPARQL…* runs a query you write, or
  the project's own checks in `src/sparql` with the same pass/fail convention `make sparql_test`
  uses. `SELECT` and `ASK` only: a curator exploring a query should not be one typo away from an
  `INSERT`. Protégé 5.6.9 also ships `sparql-query-plugin` for ad-hoc querying; what OntoBoard
  adds is running *the project's own committed checks* against the ontology in front of you.
- **Pull requests** — *Git…* does status, stage, commit, pull, push and branch, and
  *Open from GitHub…* clones. Opening or reviewing a PR still means leaving Protégé.
- **Widoco HTML documentation.**
- **Entity locking** — nothing stops two live collaborators editing the same term at once.

### Known constraints

- **The bundle-resource barrier is fixed as of 1.25.0.** It is written up here rather than
  deleted, because the way it hid for nine versions is the more useful part.

  `ReportOperation` finds its queries with
  `ReportOperation.class.getClassLoader().getResource("report_queries")` and then compares
  `URL.getProtocol()` against `"file"` and `"jar"`. Inside an OSGi bundle the protocol is
  neither — Felix answers `bundle` — so it threw `IOException: Cannot access report query
  files`, and **ROBOT report failed on both hosts**, not just on 5.5.0. Every public entry
  point routes through that one private method: `getReport`, all six `report` overloads, and
  `getTDBReport`, which looked like an escape until its bytecode turned out to call
  `getReportQueries` too. (An earlier note here claimed `getViolations` was a way round it.
  There is no such method; `javap` settles it.)

  No test could fail on this. Maven runs against an exploded classpath where
  `getResource("report_queries")` returns a `file:` URL and works perfectly. The failure was
  sitting in `~/.Protege/logs/protege.log:399033` under OntoBoard 1.15.0 the whole time.

  The fix does not extract the jar, which is what this entry previously proposed. Directory
  *enumeration* is the only thing that fails; `getResourceAsStream` on a known path works. So
  `ReportQueries` reads robot-core's own `report_profile.txt` and its 33 `report_queries/*.rq`
  as streams, and `RuleRunner` runs them over a Jena model built by `OntologyDataset`. The
  rules, the severities and the SPARQL stay ROBOT's; only the plumbing is ours. The cost is
  that a robot-core upgrade which renames a rule or moves those resources breaks this where
  the CLI would not — `SelfCheck` and `OdkScaffoldTest` turn that into a failure rather than a
  silent gap.

  Two things check it, because "it works in the bundle" cannot be checked by a unit test.
  `RobotParityTest` runs `robot report` inside `obolibrary/odkfull` over the same ontology and
  the same profile and requires an identical violation set — 7/7 rows on `fixture-tiny`, 21/21
  on pizza v2. And `OntoBoardStartup` runs `SelfCheck` in the host at startup, which
  `tools/smoke.ps1` asserts; the 1.25.0 receipt records `PASS 4/4` on **both** installs.
- **Every operation is now compared against the real command line, not just the report.** Until
  1.48.0 `report` was the only one. The evidence for the rest was that they did not throw, which is
  weaker than it sounds: a wrapper that passes an option in the wrong place produces plausible
  output that nobody can tell is wrong.

  `RobotCliParityTest` runs nine operations twice — once in process through the plugin, once as the
  `robot` command a project's CI actually runs in `obolibrary/odkfull` — over the same pizza, and
  requires the answers to match: `reason` (the inferred axioms), `measure` (every metric name and
  value), `export` (the table, row for row), `extract` (the module, axiom for axiom), `diff` (the
  text), `verify` (which checks fail and with how many violations), `explain` (which classes, and
  the axioms the justification rests on) and `template` (the axioms built).

  Each comparison passes the options the plugin's own call passes, not the ones a Makefile passes —
  comparing `robot reason --exclude-tautologies` against a plugin call that asks for ROBOT's
  defaults would be comparing two different questions and reporting the difference as a defect.

  Doing this found four real defects, none of which threw: a written export joined a multi-valued
  cell with the string `tsv` instead of a bar, because the format name was passed where ROBOT wants
  the separator; the measurements panel silently omitted a whole class of metrics, including the
  axiom-type breakdown, because only two of `MeasureResult`'s three maps were read — twelve of the
  ninety-eight it reports for pizza v2; the set-valued metrics came
  out in hash order, so the panel reshuffled between runs and never matched a build's metrics file;
  and the explanation named one class two ways at once.

  The suite deliberately compares two ROBOT versions: the plugin embeds robot-core 1.9.8 and
  `odkfull` ships 1.9.10. Pinning both to one version would make it agree with itself and answer a
  question nobody asked, since what a project's CI runs is `odkfull`. The cost is that their OWL API
  versions render an axiom slightly differently — an explicit `^^xsd:string` on a plain literal, and
  a space before one closing parenthesis — and the suite normalises exactly those two renderings and
  nothing else.
- **Live collaboration did not work at all before 1.50.0, and its tests all passed.** Worth reading
  as a method failure rather than three bugs, because the method is reusable and the bugs are not.

  The plugin's collaboration tests drive the client against a fake transport. The server's tests
  drive the bridge against a document loader they supply themselves. Both suites are thorough - 130
  Java tests and 41 JavaScript ones - and between them they establish that each side is
  self-consistent. Neither can say anything about the join, and the join was where all three defects
  were:

  1. `server.mjs` called `openDirectConnection` on the `Server` wrapper rather than on the
     `Hocuspocus` instance it holds. Resolving a board threw, the bridge answered "could not open
     board" and closed the socket, so **no Protégé peer could join any board in any release**.
  2. `CollabMessages` flattened nested payload fields to strings, with a comment saying nested
     structures were "stringified rather than lost". They were lost. A label travels as
     `data.updates.label`, the code applying it asks `updates instanceof Map`, and a String is not a
     Map - so **renaming or labelling a term never reached a peer**, silently. Outbound was fine,
     which is why no round trip inside one language could show it.
  3. `livePeers` in the bridge left the `ontology` field out of the payload it broadcast, though the
     connection record carried it. So every peer's ontology arrived empty and
     `CollabSession.peerOntologyWarning` - the warning that two people on a colliding board id are
     editing unrelated ontologies - **could never fire**.

  All three are now pinned by tests that cross the socket: `CollabLiveTest` starts the shipped
  server, connects two sessions and requires edits, labels, renames, notes and the mismatch warning
  to arrive; `collab/__tests__/server-boot.test.mjs` does the same from the JavaScript side. Each was
  confirmed to fail against the unfixed code before being kept.

  The lesson, stated so it outlives the fix: **a test that injects a dependency cannot check the
  wiring.** Where two implementations meet, one test has to run both.
- **A live session belongs to one ontology, and did not before 1.51.0.** It resolved "the
  ontology" from Protégé's current selection on every operation, which broke in both directions: an
  edit to any other loaded ontology - an ODK project has every import module loaded alongside the
  edit file - was published to the board and applied to every peer's edit file, and switching the
  active ontology while connected redirected peers' edits into whatever file was now in front of the
  user. A session is now bound at start; changes to another ontology are skipped silently, because
  there is nothing wrong with the axiom and counting it as unshareable would send the user hunting
  for a protocol limitation that does not exist.
- **Renaming a term used to delete its labels in every other language.** A label change said only
  "this term's label is now X", and applying it removed every `rdfs:label` on the term. Three labels
  on one term collapsed to one at the receiving peer. The operation now carries the language tag and
  a peer replaces only that language; an operation with no tag is taken as the untagged label, which
  is what an older peer means by it, so the two versions interoperate without loss.
- **Live mode cannot carry every axiom, and the number is measured.** Offering every axiom in pizza
  v5 to a live session, 96 of 141 travel and 45 do not. `SubClassOf`, class and individual
  declarations, `rdfs:label` including renames, `ClassAssertion` and every annotation cross.
  `EquivalentClasses`, `DisjointClasses`, domain, range, property chains, `owl:hasKey`, property
  declarations and every `ObjectPropertyAssertion` do not, because the shared vocabulary has
  eighteen operation types fixed by the web client and an axiom with no operation cannot cross.

  The plugin reports this rather than hiding it - the toolbar shows `N changes not shared` with the
  reason in its tooltip. The practical consequence is worth saying plainly: live mode is for drawing
  a hierarchy, naming terms and leaving notes. It is not a way to build a release, because most of
  the logical content is in the second list. `reports/v5/collaboration.md` in the pizza project
  carries the per-axiom-type table.
- **Reason previews what the build will write, which it did not until 1.49.0.** The comparison
  above is against the options each call passes, and for `reason` that exposed something the option
  check could not: the plugin was passing ROBOT's bare defaults while the Makefile OntoBoard itself
  generates passes `--equivalent-classes-allowed asserted-only --exclude-tautologies structural`.

  Both calls were running ROBOT correctly. They were asking different questions. On pizza v2 the
  preview showed seven new axioms where `make reason` writes two, the other five being
  `SubClassOf owl:Thing` tautologies that the build drops and that were never going to reach a
  release. A curator reviewing inferences before cutting one was reading five lines of noise and
  might reasonably have concluded the ontology said more than it does.

  `RobotTransform.reasonOptions()` now holds those two options, `OdkScaffold` builds the Makefile's
  flags from the same constants, and the parity test runs the command line with them - so the
  preview, the generated build and the test cannot drift apart without one of them failing.

  Worth noting what this says about the older tests: not one of them failed when this changed. The
  tautologies were in the shipped output for twenty-four versions and nothing had ever pinned them
  either way.
- **The plugin's OWL 2 DL check and `robot validate-profile` disagree, on purpose.** The OWL API
  adds a missing annotation-property declaration while parsing. So an ontology that is outside DL
  for exactly that reason — a curator typed a property name into Protégé and never declared it — is
  inside DL again by the time it has been written and read back. `robot validate-profile` only ever
  sees the file and reports it in profile; the plugin checks the ontology being edited and reports
  the violation.

  The plugin is the more useful of the two here, because the point of a profile check in an editor
  is to catch this while it can still be fixed by hand. But a curator who runs both and gets two
  answers deserves to know which is which, so `RobotCliParityTest` pins the disagreement rather than
  leaving it as a surprise — and so nobody later makes the plugin agree with the command line by
  deleting the check that does the work.
- **The Rio barrier applies to both hosts, and it is an OSGi barrier rather than a version
  one.** This was described here for five versions as a 5.5.0 problem, and that was wrong.

  The version story is true as far as it goes: OWL API moved its RDF layer from Sesame to
  RDF4J at 4.5.25, `robot-core` targets the newer form, so `RioRenderer`'s constructor takes
  an `org.eclipse.rdf4j.rio.RDFHandler` on 4.5.29 and an `org.openrdf.rio.RDFHandler` on
  4.5.9. But inside a bundle that difference never gets a chance to matter. **Neither
  `owlapi-osgidistribution` exports any `org.openrdf.*` or `org.eclipse.rdf4j.*` package** —
  4.5.9 keeps 16 such jars and 4.5.29 keeps 17 on their own private `Bundle-ClassPath`, and
  the `Export-Package` header of each lists 75 and 82 packages with not one of them among
  these — and OntoBoard embeds no rdf4j jar either. So an rdf4j type is simply invisible to
  our bundle, on 5.6.9 exactly as on 5.5.0, and a Rio path fails with
  `NoClassDefFoundError` before any signature is compared.

  Two consequences worth stating plainly. `OntologyDataset` is not a 5.5.0 workaround; it is
  the only way this plugin can get a Jena model at all, on either host. And a robot-core
  operation whose *static initialiser* touches rdf4j — `ExpandOperation` reads
  `org.eclipse.rdf4j.model.vocabulary.DCTERMS.SOURCE` there — cannot be wrapped at all,
  because merely loading the class throws.

  Nothing the plugin ships touches Rio, and the report no longer does, which is why it passes
  on 4.5.9. `export` was also listed as blocked by this and never was: its constant pool
  contains no match for `rdf4j`, `openrdf` or `rio`. And `QueryOperation` cannot be called at
  all - not one method. Its constant pool carries
  `RioRenderer.<init>(OWLOntology, org.eclipse.rdf4j.rio.RDFHandler, ...)`, so resolving the class
  throws `NoClassDefFoundError` before any method body runs. That was established by calling
  `execQuery`, after a per-method disassembly wrongly suggested only the loaders were affected.
  `SparqlQuery` therefore executes with Jena directly, the way `RuleRunner` does.
- **ROBOT cannot write `.xlsx` output from inside the bundle.** `log4j-api` is excluded
  because it declares its own OSGi `Bundle-Activator` (`org.apache.logging.log4j.util.Activator`,
  in its manifest) and bnd rejects a second one, which leaves Apache POI without the logging
  backend POI 5.x expects. The bundle does embed `log4j-over-slf4j`, but that implements the
  log4j **1.x** API, not the 2.x one POI calls.

  This entry used to say "ROBOT's XLSX *template* path is unavailable", which is wrong twice
  over. robot-core 1.9.8 has no XLSX template-reading path at all: `IOHelper.readTable`
  delegates to `TemplateHelper.readTable`, whose bytecode contains no POI reference of any
  kind, and the only POI users in robot-core are `export.Cell`, `export.Row`, `export.Table`
  and `ReportOperation` — all of them *writing* spreadsheets. So templates are unaffected, but
  for a different reason than was given, and what is actually unavailable is spreadsheet
  output. The plugin's report no longer goes near `Table`, so it is further from POI than the
  version that claimed to be safe from it.
- **Guava is pinned to 18.0**, the version Protégé ships. ROBOT paths beyond reasoning are
  not individually verified against it.
- **Canvas layout is not interoperable with CoModIDE**, which stores positions as OPLa-SD
  annotations inside the ontology. OntoBoard uses a sidecar file to keep the ontology
  byte-clean, so ROBOT `report` and `diff` stay unaffected. The trade is deliberate.
- **Sidecar writes are not atomic.** A crash mid-write can lose diagram layout — never the
  ontology.
- **A punned IRI** (declared as both a class and an individual) resolves
  non-deterministically for canvas selection.
- **Canvas scale.** The canvas is opt-in precisely because rendering a 100,000-class
  ontology is not viable; expect to work with tens of entities at a time, not thousands.
- **The bundle is ~64 MB**, because ROBOT and its dependencies are embedded to run
  in-process.
- **`make` targets need `make` and ROBOT on PATH.** The ODK wizard creates a project without
  Docker, but building it is a normal ODK build.

- **The canvas lost work in two ways until 1.52.0, and neither could be caught by a test.**
  `capturePositions` walked entity nodes only, so moving a sticky note or resizing a frame was
  discarded on the next refresh - which any edit anywhere in Protégé triggers. And the toolbar's
  `Arrange` never saved what it produced, while the two other callers of the same layout code did, so
  a hierarchical arrangement of thirty classes reverted the moment anything else changed.

  Both were unreachable from a test: the capture was an inline loop inside a 2,114-line view over a
  live `mxGraph`. It is a static function over a geometry lookup now, and the two tests that were
  impossible to write before are in `SchemaCanvasViewTest`. The layouts also leave notes and frames
  alone - a frame slotted into a grid encloses nothing, which destroys the only thing it is for.

  What remains, and is named in [the canvas plan](superpowers/specs/2026-09-25-canvas-interface-plan.md):
  nothing. The internal-id tooltips were replaced in 1.53.0, the last of the coordinate conversions
  unified into one function in 1.54.0, search arrived in 1.58.0, and undo for board-owned state in
  1.62.0 - which closes the last item of that plan's first two sections.

- **A term's editorial note is readable and writable on the canvas since 1.56.0, and readable on
  hover since 1.59.0.** The canvas drew a term carrying a note with a heavier border and offered no way
  to learn what it said - hovering gave the IRI, right-clicking offered nothing about notes, and the
  route was up to the main menu bar. A marker that raises a question the interface then refuses to
  answer is worse than no marker.

  The reason the text was missing for three releases is worth recording: `CanvasNode` carried a
  *boolean*. Tooltips are built from the projection, the projection knew only that a note existed, and
  so the tooltip could only say "Has an editorial note". It carries the words now, as a list - a term
  can hold one note per editor and OBO ontologies do - and `EditorNotes.hasNote` delegates to the same
  reading, so a term drawn as noted always has something to quote.

  On hover: the first note, wrapped and cut at 220 characters, and a count of the rest. An editorial
  note is prose rather than a phrase, so it is the one tooltip here that wraps. Three paragraphs from
  three editors is not a tooltip; the Notes dialog shows them all with authors and dates.

- **The stylesheet named a font that was never used, and the legend described a board that was never
  drawn - both until 1.65.0.** Release 2 of the canvas design spec. Four of these are defects rather
  than taste, and all four were invisible without a render.

  `FONT` was the string `"Segoe UI, Helvetica Neue, Arial, sans-serif"` - a CSS fallback chain handed to
  `java.awt.Font`, which takes one family name. `new Font("Segoe UI, Helvetica Neue, Arial,
  sans-serif", BOLD, 13).getFamily()` returns `"Dialog"`. No board, screenshot or export had ever been
  drawn in the typeface the code named, and because the SVG writer emits the string verbatim, the PNG
  and the SVG of the same board were in different fonts. It resolves one installed family now.

  **The namespace colour was applied to every node kind**, overwriting the kind's own stroke - so an
  individual was drawn in the class blue where the legend promised lilac, and both property hexagons in
  that same blue. The key was wrong for four of its six node rows. It is classes only now, which is the
  channel's point: classes are the bulk of a schema.

  **The unsatisfiable fill was registered and never drawn.** `styleFor` overrode the stroke alone, so a
  contradictory class was a white card with a red outline while the key showed a pink one - and a red
  outline is precisely the channel colour vision deficiency removes.

  **An imported term was a grey slab with full-strength black text.** `opacity` fades the shape and not
  the label, because `drawLabel` reads a different key; and JGraphX's drop shadow is an opaque mid-grey
  offset copy with no blur, which composited through the 55% fill to `#DEDEDF`. The shadow is now
  translucent and the imported label has its own secondary ink at 5.84:1 - `textOpacity` would have
  composited to 3.87:1, under the floor for text.

  Beyond the defects: is-a edges carry a hollow UML generalisation triangle and are solid, which frees
  the dash to mean "the reasoner derived this" - previously asserted and inferred differed only by dash
  length and one step of grey, on the one distinction this canvas must never blur. Inferred edges also
  gained an open-circle tail, so the two are separated on two channels rather than one. The palette was
  re-measured: nothing in it is below 4.4:1 now, where three strokes sat at 3.1 and the sticky note's
  outline was 1.69:1 - the lowest-contrast line on the board, on the one object whose purpose is to be
  noticed. Datatype and data-property nodes had been *exactly* the same fill and stroke, so the key drew
  two rows with one swatch.

  And the individual stopped being a rhombus. A 160x60 rhombus offers about 77px of interior where a
  two-line ontology label needs 128, and no amount of padding fixes it - at the padding the geometry
  demands, "Cheesey vegetable topping" wraps to three lines. It is a card with an underlined name, which
  is UML's instance convention. The underline does not survive SVG export; the lilac fill does.

- **Arrange drew the class hierarchy upside down until 1.64.0, and nobody noticed for thirteen
  releases.** `SchemaGraph` draws a subclass edge from the subclass *to* the superclass, and
  `mxHierarchicalLayout` ranks by following edges from source to target - so with the library's default
  orientation the leaves ranked first and `owl:Thing` ended up at the *bottom* of the board. Every
  ontology tool there is puts the superclass above its subclasses.

  It survived thirteen releases because nothing in this project had ever looked at the canvas. The tests
  asserted that a subclass and its superclass had *different* y coordinates, which is true upside down;
  the in-host self-test proves the views construct and inspects no pixel; and every human review happened
  on a board of three or four terms, where a small tree the wrong way up reads as a small tree.

  What found it was rendering the board to a PNG and opening it. `CanvasDesignProofTest` does that
  headlessly through `mxCellRenderer` - the board, a 31-term board, the legend and the empty state, into
  `target/design/`. Three of the four defects fixed in 1.64.0 were found by looking at those images and
  none of them by reading the code.

  Four other things the renders showed, all fixed in the same release:

  - A 31-term tree was **4195x403** - a ten-to-one strip nobody can read. Now 4069x613, fitted to the
    window by Arrange itself.
  - Every edge was a **diagonal sweep**, though the stylesheet has asked for orthogonal routing since the
    first version: `mxGraphHierarchyModel` stamps `noEdgeStyle=1` onto every edge while
    `isDisableEdgeStyle()` is true, which is the default. Turning it off is one line, and it is the
    single biggest readability gain in the release.
  - A term with **no axioms was exiled** a thousand pixels to the right - `findRoots` only accepts a
    vertex with `fanIn == 0` and `fanOut > 0`, so an isolated term became a hierarchy of its own laid out
    beside everything else. A class nobody has related to anything yet is the normal state of a term five
    minutes old; they are parked in a block under the diagram now.
  - The **legend printed "Relationships" twice**, because the two inferred rows sit after the modifier
    rows and the panel emits a heading whenever the kind changes. Pre-existing since the legend was built
    in 1.53.0, and visible the first time anybody rendered the panel.

  Two things this release corrected in the spec that produced it, rather than in the code: a suggested
  test asserting "a note keeps its offset from a named term" cannot hold, because a layout rearranges the
  terms; and `mxFastOrganicLayout` turns out to be deterministic *except* when two nodes occupy the same
  point, where it separates them randomly - three runs of a board with both terms at (0,0) gave (-1,-1),
  (139,-1) and (-1,139). Any test comparing one organic arrangement with another has to start from a
  board where that tie cannot arise.

- **Presence made a live session look emptier than it was, until 1.63.0.** Four separate things, all
  of them in the "it works, but" category that no test and no error message reports.

  *Presence was published only from mouse motion.* Selecting a term told nobody until the mouse happened
  to move afterwards - so clicking a node and reading it, or finding it with Ctrl+F, was invisible to
  collaborators. And somebody who joined a board and read the diagram without moving the mouse never
  appeared at all, which to everybody else was indistinguishable from nobody having joined. Selection
  changes and joining now both announce, with the position taken from the selected node's centre, the
  last cursor position, or the middle of the visible canvas - in that order, and never the origin, which
  for anybody who has scrolled is somewhere the canvas is not.

  *Geometry was published and then discarded.* Every operation carries the node's geometry -
  `canvasHints` exists for no other purpose - and the receiving side never read it, so a class created by
  a colleague landed wherever an unpositioned node goes. It is now adopted under two rules, both about
  not fighting the local user: a position this board already has is never replaced, since the wire has no
  "moved" operation and an inbound position is just the sender's geometry at the moment of some edit; and
  the origin is read as "no hint" rather than as a coordinate, because that is what `putGeometry` writes
  when the sender had none - which is every change made from Protégé's tabs rather than the canvas.

  *A refused session was still held.* A refusal and a dropped connection were both reported as
  `onStatus(reason, false)`, and the toolbar took its button label from that flag. So after a refusal the
  button read "Collaborate..." while a live session object was still held - clicking it disconnected
  instead of opening the dialog - and during an ordinary reconnect it said the same thing while the
  session was perfectly alive. The label now follows whether a session exists, which is what the button
  will *do*, and a refusal ends the session so the two agree.

  Two of the four are verified over a real socket rather than against a fake: joining announces itself,
  and a geometry value published by one peer arrives at the other's host. A unit test can only show the
  callback is called; the wire test shows the number survives being serialised, gated by the bridge's
  type check, and read back.

- **Nothing the board owned could be undone until 1.62.0.** Protégé's undo covers the ontology and
  nothing else, which left out everything this canvas is for: an arrangement of forty classes, which
  terms are on the board, a sticky note's text, a frame's size. A misdirected *Arrange* replaced a layout
  somebody had spent an afternoon on with no way back but to do it again by hand; *Add all* on a large
  ontology was the same in reverse, one click with no way out but removing terms one at a time.

  Ctrl+Z on the canvas now steps the board back, Ctrl+Shift+Z or Ctrl+Y forward, over arranging, adding,
  removing, moving, expanding, collapsing, and every sticky note and frame. Snapshots rather than
  commands: a board's whole state is one small DTO, so copying it costs less than an inverse operation
  per action and cannot drift - a new board action gets undo the moment it records, and there is no
  second implementation of "the opposite of expanding" to get wrong. Twenty-five steps, bounded because
  each entry is a whole board.

  Two things it deliberately does not do. It does not touch the ontology: restoring a term to the board
  does not restore an axiom retracted in between, and the status line says so every time, because a
  partial undo that looked total would be worse than none. And it is not persisted - reopening a project
  starts with an empty history, since a stack from a previous session would offer to restore a board of
  identifiers the ontology may no longer declare.

  Also worth knowing: a drag produces one undo step, not one per event. mxGraph fires `CELLS_MOVED`
  continuously while a drag is in progress, and the existing 800 ms save timer is what already knows a
  burst is under way.

- **An imported term could not be put on the board until 1.61.0, and the attempt left no trace.**
  Dragging `bfo:continuant` across did nothing visible: `OntologyProjection.project` looked only at the
  edit file's own signature, so no node was drawn - and `pruneStaleMembers` then deleted the sidecar
  entry, so the board did not even remember the attempt. For an ODK project, which is what this plugin
  scaffolds, most of the terms a curator refers to are imported, so the canvas could not draw the
  ordinary case.

  Imported terms are now drawn faded, said to be imported on hover, explained in the legend, and found
  by the Find box - Ctrl+Enter puts one on the board. Opacity is the marker because every other channel
  on a node already says something: shape for the kind, stroke colour for the namespace, border weight
  for a note, and a dash would read as "inferred". It is a cell style rather than an overlay, so it
  survives a PNG or SVG export - which matters, because the distinction is *which terms a curator must
  not edit*, and a published diagram that draws imported terms like local ones invites the mistake.

  **An OWL API defect found on the way, and worth knowing about.** In 4.5.29, calling
  `getSignature(Imports.INCLUDED)` permanently pollutes the same ontology's cached
  `Imports.EXCLUDED` signature:

  ```
  getSignature(EXCLUDED)  -> [edit#Mine]
  getSignature(INCLUDED)  -> [edit#Mine, obo/BFO_0000002]
  getSignature(EXCLUDED)  -> [edit#Mine, obo/BFO_0000002]      <- wrong
  ```

  Two callers here ask opposite questions of one ontology: the search box asks with imports, *Add all*
  asks without. On the first version of this work, one search therefore made *Add all* offer every term
  in every import for the rest of the session - tens of thousands of nodes on one click. The per-kind
  queries (`getClassesInSignature(imports)` and its three siblings) are not affected, and everything
  here now uses them. `ImportedSignatureTest` pins both the library's behaviour and the plugin's
  immunity, so the workaround cannot be removed as superstition.

  It was caught by a test asserting the *boring* half of the pair - that Add all stays local - rather
  than by any test of the feature being built.

- **An edge could not be drawn until 1.60.0, and the docs said otherwise.** `setConnectable(false)`
  sat in `SchemaCanvasView` with the comment "Task 4 turns this on with real axiom writing", while
  `docs/feature-parity.md` advertised authoring edges from the canvas. There was no connection handle to
  find, so people looked for the gesture every diagram tool has, did not find it, and concluded the
  feature was missing rather than that it was two levels into a context menu.

  The gesture writes nothing by itself. mxGraph inserts an edge, that edge is deleted immediately, and a
  popup at the drop point offers only what those two ends can legally assert - the one hierarchy link
  that applies, a restriction when both ends are classes, or a sentence saying why neither does.
  Dismissing it leaves the ontology and the board exactly as they were. The reason for deleting the
  drawn edge is the property the canvas rests on: every line on the board is a projection of an axiom,
  so a line that is only a drawing would be the one that means nothing while looking like the ones that
  do.

  One library default had to be overridden to avoid trading the most-used gesture for the newest.
  `mxConstants.CONNECT_HANDLE_ENABLED` is `false` in JGraphX 4.2.2, and with the handle off
  `mxConnectionHandler.isHighlighting()` returns true, which makes a press inside a node start a
  connection instead of moving the node. Enabling the handle gives a small square on hover that starts
  an edge, and leaves the node itself draggable.

  What is not covered: the rules a drawn edge has to pass and the options a pair of ends offers are in
  `DrawnEdge` with 13 tests. The drag itself is not tested, and cannot be from JUnit.

- **Expanding a term's neighbours made the diagram worse until 1.59.0.** *Expand neighbours* added
  the right terms and placed them nowhere. With no stored geometry `SchemaGraph.render` laid them in a
  row along the top of the board - overlapping whatever was up there, nowhere near the term they
  neighbour - nothing was saved, so the next refresh moved them again; nothing was said, so an
  expansion that found nothing looked exactly like one that worked; and nothing remembered what had
  been added, so there was no way back.

  Four symptoms, one cause: `expandOneHop` returned a count and the caller discarded it. It returns the
  identifiers now, and each of the four follows - a ring around the source term
  (`CanvasLayouts.ringOffsets`, filling outward once a ring is full so nodes cannot overlap by
  construction), the positions written before the refresh rather than after, a sentence saying how many
  arrived, and a *Collapse* item that takes back exactly what that expansion added.

  Collapse is not undo. It is held for the session and not written to the sidecar, because it answers
  "I have just expanded this and it was too much" - asked seconds later, never after reopening a
  project. Real undo for board-owned state is A5 in the canvas plan and is still not done.

- **A term on the board could not be found by name until 1.58.0, and the zoom had no readout and
  no way home.** Two absences rather than two bugs, and the larger one is the first. On a board with a
  hundred terms - what *Add all* produces on the pizza ontology, and small for the ontologies this
  plugin is for - locating `Margherita` meant dragging the canvas until it appeared, or leaving the
  canvas for the class hierarchy, finding it there, and coming back. People did the second, which made
  the canvas a thing to look at rather than to work in.

  The Find box matches label and IRI as you type, ranked so an exact name beats a longer one that
  contains it, centres the best match and selects it - and the selection goes out through
  `SelectionBridge` to Protégé's own selection, so finding a term also brings up its annotations in
  the panels beside the canvas. Enter steps through matches, and Ctrl+Enter adds a match that is in
  the ontology but not yet on the board, which is the case where "no match" was the least useful true
  answer available.

  The second absence was smaller and easier to hit: three turns of the wheel past the last node leaves
  a blank grey grid, and nothing on screen distinguished *zoomed out into empty space* from *the board
  is empty* or *the plugin has stopped working*. There is now a percentage in the status bar that
  returns to 100% when clicked, and a *Fit* that scales the board into the window.

  The status line moved out of the toolbar into a status bar along the bottom while doing this. It had
  been a line of prose that grows and shrinks - "3 changes not shared", "Disconnected - you switched
  ontology" - sitting in a row of buttons, pushing them sideways as it changed and squeezing them off
  a narrow panel.

  Not covered: the ranking and the fit arithmetic are unit-tested (`CanvasSearchTest`,
  `CanvasZoomTest`, 21 tests), and the wiring between them and Swing is not. The in-host self-test
  constructs the toolbar, the Find box and the status bar on both Protégés; it does not type into
  them.

- **The OntoBoard tab opens and its views construct inside both Protégés, checked automatically
  since 1.57.0.** This closes the last clause of F1 in
  [the phase plan](superpowers/specs/2026-09-24-next-phase-plan.md) and retires the first entry on
  that plan's own list of what was "genuinely unverified".

  It was unmet for a duller reason than the smoke script gave. The script looked for
  `Saved tab state for 'OntoBoard' tab`, which Protégé logs at *shutdown* - and the smoke kills the
  process, so that line could never appear whatever the tab did. Measured: the tab is not mentioned
  anywhere in a smoke run's log, because the self-test runs from the editor-kit hook and nothing in
  that path asks for a tab. The self-test now asks for it, on the event thread, and removes it again.

  Worth stating precisely: the views **construct**, on Java 8 with OWL API 4.5.9 and on Java 11 with
  4.5.29. Nothing here looks at a pixel, so "the canvas paints" is still not asserted. Construction is
  the half where a missing OSGi import or a bad classfile version shows up - `SchemaCanvasView` is
  2,114 lines and was until now exercised only by tests that construct no view - and the rest still
  rests on somebody looking at it.

### Not covered by tests

1207 tests cover projection, axiom construction, layout persistence, ODK scaffolding, the
report's rule execution and the OSGi configuration. Fourteen of them reach outside the JVM:
`RobotParityTest` compares the report against real ROBOT in `obolibrary/odkfull`,
`RobotCliParityTest` compares nine more operations against the same image's `robot` command, and
`OdkBuildTest` runs a freshly scaffolded project's own `make test` and `make prepare_release` in
that image. All are *skipped*, not failed, where Docker is absent — so a green run on a machine
without Docker proves considerably less than it looks, and now says nothing at all about whether
this plugin agrees with ROBOT.

`OdkBuildTest` earns its keep: three defects in the generated build had survived a 30-assertion
test class and were found the first time anyone executed it — a `--` inside an XML comment in the
catalog, which made `make` die on the first robot call; no robot invocation passing the catalog at
all; and a release gate weaker than the CI gate. Asserting what a generated file *says* is not the
same as running it. They do **not** cover any Swing UI:
dialogs, toolbar, minimap, drop handling, and every visual choice need a display and are
verified by hand. Treat visual behaviour as unverified after each change.

Behaviour *inside the bundle* is a third category, and the one that has cost the most. No test
can see it, because Maven's classpath is not Felix's. `OntoBoardStartup` now runs `SelfCheck`
at startup and `tools/smoke.ps1` asserts the verdict, so seven things are checked in the host on
every release: robot-core's profile is readable, all 32 of its queries are readable, Jena can
read what the OWL API writes, the report produces findings end to end, the export produces terms
end to end, the explanation of a deliberately unsatisfiable class produces a justification, and
every class `plugin.xml` names loads *and constructs*.

That last one is half of what the plan calls Phase 2. `PluginXmlTest` already calls
`Class.forName` on each of those classes — on Maven's classpath, where everything resolves. Felix
is a different class space, and a menu action referencing a Protégé type the bundle never imported
passes that test and dies on click, invisibly, because an action that fails to load simply does
nothing. All 29 are now loaded *and constructed* in the host.

The other half arrived in 1.40.0 and grew in 1.44.0. `tools/smoke.ps1 -SelfTest` sets
`-Dontoboard.selftest=true`, and the startup hook then drives **nine** menu items against a
throwaway ODK project — scaffolded by the wizard's own code into a temp directory and deleted
afterwards — logging what each reported: Measure, Quality report, Explain, SPARQL, Export terms,
Profile, Imports, Refresh imports, All notes. A person can run the same thing from *OntoBoard →
Run self-test*; both go through one implementation, because a self-test with two implementations
grows a version nobody runs.

A real project rather than an ontology in memory, because half the menu is project-aware — it
looks for `src/ontology`, a catalog, `imports/`. What unlocked that was removing seven private
copies of `fileOf(OWLOntology)`, each of which asked the *model manager's* manager for the document
IRI and so answered only for the ontology Protégé has open.

It earned itself immediately: on its first run it found `QualityReportAction` throwing
`NullPointerException`, because its `options` field had no initial value and `run()` therefore
worked only after `configure()`. From the menu `configure()` always runs, so no user could reach
it — a latent trap rather than a live bug — but it was invisible to 1133 unit tests and to anyone
clicking the item.

**Nine of roughly twenty items, and the rest are excluded for one stated reason each.**
*Transform* defaults to applying its changes and would push them through the live session's model
manager. *Rename IRIs*, *Import terms*, *Obsolete*, *Release*, *Build*, *Git*, *Open from GitHub*
and *New ODK project* write files, run `make`, or reach the network. *Compare releases* needs a
project with dated releases, which a freshly scaffolded one has none of. The canvas, note-editing
and collaboration items are Swing surfaces and nothing here opens a window.

Those could now be covered — the scratch project makes it possible — but each needs its own
fixture: a release history, a git remote, an upstream to import from. Inventing those badly would
be worse than the gap. So this still does not "drive the menu", and says so in its own output.

The export check is there for a specific reason. Its safety rested on a chain of inferences — POI
is reached only by `Table.asWorkbook`, which `write` calls only for `xlsx`, which the plugin does
not offer — and a chain of inferences about a classloader is exactly what was wrong about the
report for nine versions.

## Web application

- **No offline support.** The operation log is ephemeral; edits made while disconnected are
  not queued for replay.
- **Tested to roughly 10–15 concurrent users.** Beyond that, network overhead and
  `Y.Array` growth are unvalidated.
- **Entity locking is advisory**, not enforced at the API level.
- **Position conflicts use last-write-wins**, which can cause a brief visual jump.
- **Consistency checking is manual** — reasoning must be triggered explicitly.
- **7 of 24 ROBOT commands** are fully implemented and tested.
- **No SHACL validation**, no OAuth/OIDC, no 2FA, no self-service password reset.
- **SQLite in development**; PostgreSQL is untested.
- OWL constructs not supported: custom datatypes, `owl:hasKey`, negative property
  assertions, annotations on axioms.

## Roadmap

Ordered by what is being worked on:

1. **Pull requests** — the rest of the git surface ships; opening and reviewing a PR does not.
2. **Pattern library and Widoco.**
3. **Entity locking** — once live collaboration has been used in anger.
4. **Collaboration vocabulary** — the nine axiom kinds the live protocol cannot carry.

Done, with the release that did it: live collaboration and cursors (1.9.0); the entity-view
column and tab layout (1.8.0); ROBOT transforms, term import and the quality report
(1.10.0–1.18.0); git tooling, the ODK build runner, ID range editing, provenance stamping,
editorial notes, frames and sticky notes, obsoletion, OWL 2 profile checking, release
comparison and ROBOT templates (1.19.0); properties and inferred individual types on the
canvas (1.20.0); ROBOT's quality report actually running inside the bundle, on both hosts,
checked against real ROBOT and against a startup self-check in the host (1.25.0); ROBOT term
export as TSV, CSV, JSON, YAML or HTML, with the same in-host check (1.26.0); explanations for
unsatisfiable classes and inconsistency, with ROBOT's axiom-impact summary (1.31.0); SPARQL,
including the project's own committed checks (1.33.0); ROBOT's axiom-level diff beside the
term-level one, and a startup check that every class plugin.xml names really loads under Felix
(1.34.0); ROBOT's materialize, which asserts inferred relations rather than inferred subclass
axioms, bulk IRI renaming for moving a namespace, and SPARQL results written as TSV the way ODK's
`custom_reports` does, and committed term lists so an import module can be rebuilt from the
repository rather than kept as a blob, a mirror so a rebuild works offline, a diff against the published release, and re-rendering an
existing project's generated files so a generator fix reaches projects that already exist
(1.35.0-1.45.0); an OWL 2 DL profile gate in the generated build, and a collaboration fix so an
incoming annotation declares its property rather than pushing the ontology outside DL (1.46.0);
nine operations compared against the real `robot` command rather than only the report, and the four
defects that comparison found (1.48.0); a Reason preview that passes the same options as the
generated build, so it shows what a release will contain (1.49.0); live collaboration verified over a
real socket for the first time, and the three defects that had made it inert in every release
(1.50.0); a generated ODK
build that honours its own catalog, rejects equivalences nobody asserted, and gates a release on
the same checks CI runs (1.28.0).

Those middle releases all shipped under one unchanged version number, which is why the list
above names versions at all — see the note in [development](development.md) about what that
cost.

Specs and plans live in [superpowers/](superpowers/), including the reasoning behind
decisions that were reversed — the plugin was briefly intended to replace the web
application, and does not.
