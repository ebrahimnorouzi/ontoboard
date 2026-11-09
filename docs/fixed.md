# What was fixed

Defects found in OntoBoard and the release that fixed each one. This page is the engineering
record, not documentation: nothing here is a current limitation, and nothing here needs
reading to use the plugin. [Limitations](limitations.md) is the page for what the tool cannot
do today.

It is kept because how a defect was found is usually worth more than the fact that it is
gone. Several of these were green in the test suite while being completely broken in a real
Protege - live collaboration for forty releases, the canvas losing work in two ways no test
could catch - and the entries say so. Each release's own host smoke receipt, published as its
release notes on
[the releases page](https://github.com/ebrahimnorouzi/ontoboard/releases), carries the same
record for that version.

- **A datatype stood alone in a corner of the board, fixed in 1.135.1.** Found by the tester who
  prepared the cookbook scenario: after the Price pattern, a `float` ellipse sat at the bottom left,
  outside every frame, with no line. Since ranges wait for a selection, the arrangement no longer saw
  the line from the property to its datatype, because JGraphX leaves cells it does not show out of
  the lists it hands back. The arrangement reads every line now, so a range's datatype stands with its
  property in the *Data properties* section. `DatatypeBesideItsPropertyTest`, red first.

- **Adding a subclass rearranged the frame, fixed in 1.135.1.** Two testers saw it: with each
  *New subclass…* the siblings switched between two and three columns, and a class jumped 1,640 px.
  Subclasses were spread over columns of equal height, so every new one moved where every column
  broke, and the trees of a frame went biggest first, so one growing past another swapped them.
  Subclasses now fill a column before the next starts, and trees go in label order. A subclass added
  last moves nothing; one added between others moves those after it by one place.
  `HierarchyColumnsTest`. Balanced columns stay for the trees themselves: measured on every pattern
  in the library, filling those ran lines together on two of them.

- **A class found and added landed far from its superclass, fixed in 1.135.1.** Found by a tester:
  *disposition*, added with *Find* and Enter, stood 1,880 px from *realizable entity* in a pattern's
  frame, its line crossing the board. The study's imported IAO terms did the same, with lines of about
  a thousand pixels. A class from an import, or found and added, whose superclass stands in a frame
  now stands under it there, and so does what is under it, own classes included.
  `ImportedClassesUnderTest`.

- **Frames appeared for patterns nobody imported, fixed in 1.135.1.** Found twice by testers: a
  "publication" frame, because an IAO import and NFDIcore's dataset pattern between them held most of
  that pattern's distinctive terms, and a "Classification" frame for the Concept class of *Task
  execution*. Recognising a pattern by its namespace or its terms is for a release that merged its
  imports, or an ontology opened as it is. An ontology that imports patterns is now framed by those
  imports only. `ImportedPatternsTest`.

- **An import taken back with *Edit > Undo* still looked imported, fixed in 1.135.1.** Found by a
  tester: *Imported terms...* listed the pattern's module as before, and the status line still said it
  had been imported, with no frame on the board. The list now says "not imported now", its details say
  so first, and the status line names what is no longer imported. `ImportTakenBackTest`.

- **Releases carried two labels and two definitions for some IAO and RO terms, fixed in 1.135.1.**
  Found by a modelling tester running ROBOT's report on the study's releases: 20 errors, 12 of them
  `multiple_labels` and 8 `multiple_definitions`. The bundled NFDIcore, MWO and PMDco patterns
  carried older copies of IAO's and RO's text beside the current ones: *information content entity*
  labelled with and without a language, *data entity* also labelled *data item*, superseded
  definitions of *concretizes* and *is concretized as*. In 25 patterns they now carry what IAO says
  today, with *data item* and *realized in* kept as alternative terms. ROBOT 1.9.8 also counts a term's
  labels in two languages as multiple labels, so the German labels PMDco gives and the Italian ones
  of the Place pattern are still reported on a release that carries them.

- **Ethnicgroup's properties had a path on the harvesting machine for their IRIs, fixed in
  1.135.1.** Found by a tester: its 14 *links to* properties came out as `file:/ontoboard-reading/...`,
  and *Imported terms* listed them as chosen but not in the module. The pattern file wrote `xmlns:base`
  where `xml:base` belongs. The properties now have the pattern's own namespace.

- **The first new term of a session read every release on the Swing thread, fixed in 1.135.1.**
  Found by a code review. Released identifiers are never minted again since this release, and finding
  them reads the project's releases, once each until they change. The releases are now read on a
  thread of their own when a project opens. `TermMinterTest`.

- **A line ran through an is-a arrowhead, and a label sat on a frame's border, fixed in 1.135.1.**
  Found on the cookbook board: `∃ has part` crossed the arrowhead under *data collection*, and its
  label straddled the *dataset* frame's outline. Other testers saw lines across a frame's name. The
  router now prices running into another class's arrowhead and through a frame's title band, and a
  label astride a frame's outline or on its name costs what one covering a line does.
  `EdgeRouterTest` routes the cookbook's case; `PatternBoardsTest` measures lines through arrowheads
  and draws the cookbook's three patterns together.

- ***Collection Entity Pattern* was called "same as collection", fixed in 1.135.1.** Found preparing
  the cookbook scenario: it has the class Entity, which *Collection Pattern* lacks. Patterns that only
  declare the same ontology IRI are now "another version of", and the summary says how many terms each
  has. `PatternTwinNoteTest`.

- **The mark of a defined class read as an equals sign, fixed in 1.135.1.** Found by a tester: at
  100% on an ordinary screen, `≡ publication` read as `= publication`. Measured in Helvetica Neue,
  Segoe UI and Dialog, the character is three faint bars no taller than `=` at 13 px. A defined class
  now also has a badge on its box, a disc holding three bars drawn as strokes, as Protégé's class tree
  marks one. `DefinedClassBadgeTest`.

- **ELK missed a class made unsatisfiable through an inverse property, said since 1.135.1.** Found
  by a tester: picking *quality of* for a value's relation made it unsatisfiable through the inverse
  *has quality*. HermiT saw it, but ELK, which the build uses as ODK's does, ignores inverse
  properties, so the test passed and the class was released. When the ontology goes beyond OWL 2 EL,
  the build and *Release...* now ask HermiT too, for ten seconds at most, and warn with the classes it
  finds. The build still passes, as ODK's would. `InProcessTargetsTest`.

- **A pattern without labels failed every later build, fixed in 1.135.1.** Found preparing the
  cookbook scenario: 58 of the library's patterns have terms without a label, and the two food recipe
  patterns have none at all. Imported, their boxes read `food_recipe`, and the label check a project
  starts with failed from then on. The module a pattern's import writes now labels such a term from
  its name, marks the label as made by OntoBoard, and the result lists them. `NamesAsLabelsTest`.

- **On macOS and Linux the board's right-click menu never opened, fixed in 1.135.1.** Found
  by a scripted walkthrough of a user study in Protégé 5.6.9 on macOS, replaying each platform's
  order of mouse events, and confirmed in the JDK's macOS event code. Swing raises the popup
  trigger on the press on macOS and Linux, and on the release on Windows; the board asked only at
  the release. So there it offered no *New subclass…* and no *Relate to another term…*, and said
  nothing. The board is developed and smoke-tested on Windows, where it worked. The menu now
  opens at the release on every platform, when either the press or the release was the trigger and
  the mouse did not move, so a right-drag still pans. `PopupGestureTest` replays both orders.

- **Extending an imported pattern stopped at its first relation, fixed in 1.135.1.** *Relate to
  another term…*, *Set parent or type…* and the property list of *Create relation* offered only the
  ontology's own terms, though the board has drawn imported ones since 1.131.0. After importing a
  pattern, its classes and its properties were missing from those lists, and nothing said why. Found
  rehearsing the same study's "import a pattern and extend it". The lists now take the imports'
  terms too, sorted by label instead of IRI. `ExtendingAnImportedPatternTest`,
  `RelationDialogPropertiesTest`.

- **Boxes overlapped after a pattern import, fixed in 1.135.1.** Reported before the study:
  overlaps on the board, a newly imported pattern's among them. Arranging decided which terms
  belonged in a frame by where they stood, so a class whose old position fell where a frame was
  now planned counted as inside it, stayed there and covered the frame's own boxes. Membership now
  comes from the plan. In the same release a pattern is drawn as its hierarchy instead of a grid in
  IRI order, boxes are sized to their labels, and lines are routed around boxes. `PatternBoardsTest`
  draws every pattern in the library and finds no two boxes overlapping.

- **One imported term drew eighty, fixed in 1.135.1.** Taking `report` from IAO puts some
  eighty terms in the module, the ones it depends on, individuals among them. The board drew them
  all and buried the one asked for. Terms an import holds only because a chosen term needs them are
  no longer drawn. *Imported terms* lists them, and *Find* draws any of them.

- **New ODK project said the board was open when it was not, fixed in 1.135.1.** On a fresh
  Protégé the dialog after *New ODK project...* said the new file was on the OntoBoard tab, which had
  never been opened. That was false exactly for someone reading it for the first time. The tab is
  now added if it is missing, and brought forward before that dialog.

- ***Show labels instead of IRIs* in the quality report did nothing, fixed in 1.135.1.** The option
  went to ROBOT's own report. Since `RuleRunner` took that report's place inside the OSGi bundle,
  every finding showed a bare IRI with the box ticked. A finding now shows the label and the short
  name, `label (ID)`. `QualityReportSubjectTest`.

- **A second pattern's frame looked like the whole pattern while its main class stood elsewhere,
  fixed in 1.135.1.** Found by five of eight testers before a user study: after a second PMDco
  pattern was imported, its frame held two classes, while the class the task was about stood in the
  first pattern's frame a thousand pixels away. The status line said all six were in the new frame.
  Which frame a shared class went to depended on the patterns' names in alphabetical order, so a
  pattern imported later could also take classes out of a frame already on the board and leave
  their subclasses behind. A shared class now stays with the pattern that came first. The other
  frame gets a note naming its classes drawn elsewhere and where, and the status line counts what
  is in the frame.

- **A superclass was drawn below its own subclass, fixed in 1.135.1.** *process*, brought along by
  an import as the superclass of a pattern's *planned process*, stood at the bottom of the board,
  1,200 pixels below it, because the outline is drawn below the frames. A superclass that no frame
  holds now stands in its subclass's frame, above it.

- **Dragging the board inside a frame moved the frame, fixed in 1.135.1.** Found by a code review.
  After an import the new frame fills the window, and the guide's next words were "drag the board
  to move it". The drag took the frame and every class in it, and counted as arranging by hand, so
  the board stopped arranging itself. Subclasses made after that could land across the frame's
  border. The inside of a frame now pans like empty board; a frame is moved by its title. On a
  board arranged by hand, a new subclass stays inside its parent's frame, which grows to hold it.

- **Hand-made arrangements could be thrown away by the next new class, fixed in 1.135.1.** A board
  saved before 1.135.1 did not say whether it was arranged by hand, and was arranged afresh at its
  first new class. *Undo* after *Arrange → Outline* also lost the flag. Such boards now count as
  arranged by hand, undo keeps the flag, and every automatic arrangement is a step undo takes back.

- **The board's frames jumped when a class was added, fixed in 1.135.1.** Each arrangement folded
  the frames into rows afresh, so one frame growing moved the frame being worked in by up to 1,640
  pixels. Frames now keep their rows and their order.

- **Lines ran on top of each other and labels sat on other lines, fixed in 1.135.1.** Measured by a
  tester on the study's board: subclass lines between frames shared a stretch for up to 680 pixels,
  so one class seemed to hang off another's parent, and an arrow ran 4 px beside a subclass line for
  357 pixels. The router did not see subclass lanes at all. It now routes every line on one grid,
  lanes included, keeps lines 12 px apart, and places labels after every line has its route. On a
  board of 120 boxes it left a third of the lines unrouted, drawn through boxes; it now routes them
  all, in a tenth of the time.

- **Importing again from a source already imported left Protégé holding a detached module,
  fixed in 1.135.1.** Found by a tester who imported a pattern twice, then a second term from IAO:
  selecting a term then threw *Manager on ontology ... is null* until Protégé was restarted. The
  module was taken out of the manager and loaded again, and the import that followed changed
  nothing, so Protégé kept the old one in its list. The module is now changed in place. The library
  also says when a pattern is already imported.

- ***Save and build* raised a false "changed outside of Protégé", fixed in 1.135.1.** In a project
  opened again, closing *Save first?* activated the main window while the save was still running,
  and Protégé's own check saw a file newer than its record. The save now runs before the question
  closes. *Release...* asks *Save first?* too: a release made with unsaved changes could not be
  rebuilt from the edit file.

- **A test on a deleted class failed the build for ever, fixed in 1.135.1.** A SPARQL path of zero
  steps matched the class's IRI after the class was gone, and *Write a test* refused to replace the
  test. The query now asks for a class, and a test can be replaced.

- ***Definition…* deleted a term's other definitions, fixed in 1.135.1.** Found by a code review:
  it removed every definition, in every language and with its annotations, and wrote one back in
  English. It now edits the one it shows and keeps the rest.

- **Release notes, identifiers and term lists, fixed in 1.135.1.** Release notes listed only classes
  and could show a German label where the board showed the English one; they now cover properties,
  in English first, and a removed property is refused like a removed class. An identifier deleted
  after a release could be minted again for a new term; identifiers in any release are now taken. A
  term list written with CURIEs did not count its terms as chosen, so the board left out their
  parents, and every rebuild of the module here dropped those terms; both now read a CURIE as
  ROBOT does. Modules now carry the labels their source gives the annotation properties they
  declare.

- **The `test` build reasoned without the imports, fixed in 1.135.1.** Found by a tester who ran
  the study's releases through HermiT and ELK: a class that can have no members only because of an
  imported range passed `test`, because the build reasoned on a copy whose imports nothing loaded,
  and *Release...* then refused with "Reason failed: null". The build now reasons over the imports,
  and both say which classes can have no members, by label, and that *Edit → Undo* takes the last
  relation back.

- ***Open existing ODK project...* said "Project opened" when it was not, fixed in 1.135.1.** With
  an ontology of the same IRI already open - the same project started again in another folder -
  Protégé keeps the one it has and opens nothing, and the participant went on editing the old
  project. Both *Open existing ODK project...* and *New ODK project...* now say so, and that Protégé
  has to be started again.

- **Smaller dialog defects, fixed in 1.135.1.** The pattern library's confirmation named a module
  file the import did not write for 49 of 157 patterns, and did not say when a term clicked in its
  list narrowed the import to that term. It now names the right files and offers *Add all*. Its
  search also finds a pattern by its terms' labels. Escape closes the pattern library, *Find terms*,
  *Imported terms* and *History*. *New ODK project* refuses a Base IRI with a second address in it, as
  typing after the address that is filled in gives. *Project created* fits a laptop screen. *Open
  existing ODK project* brings the OntoBoard tab forward. "1 terms" reads "1 term". The self-test
  says which OntoBoard it tested. The window with a build's or a release's result cut its message
  off at the right, because a CSS pixel is 1.3 screen pixels to Swing. A double-click inside a
  frame renamed the frame; it now makes a class there, as on empty board, and the frame's title is
  where it is renamed. A search in the pattern library kept the library's order and selected its
  first row, so a search for `place` selected a pattern about material entities with *Place Pattern*
  tenth; a pattern whose name matches now comes first. A defined class whose label is its IRI's
  name showed the name twice, `≡ Place` over `Place`. Two NFDIcore patterns' descriptions named classes NFDIcore has made
  obsolete.

- **Smaller board defects, fixed in 1.135.1.** A property inside a nested class expression was
  shown by its identifier, `BFO_0000051`, not its label. Rules from an imported module were drawn
  on the board (a module cut from RO brought four), and a property from a class to itself was drawn
  as a dot. Long labels ran out of their boxes. *New subclass…* accepted an empty name and made a
  class with no label; it now asks for a name, and asks before reusing one. The confirmation for
  deleting an axiom showed it with full IRIs and named a menu item that had been renamed. After
  *Fit*, + and 100% zoomed about empty board; they now zoom about the selection or the board's
  content. Faded text was drawn at a contrast of 1.58:1; fading is now lighter.

- **A project that passed *Build...* in Protégé failed its QC workflow on GitHub, fixed in
  1.134.2.** Found when the two example projects ran QC on GitHub for the first time. ROBOT 1.9.8
  failed `pizza`'s `check_labels` on four pattern classes - `Concept`, `Object`, `Price` and
  `Currency` - and `pizza-bfo`'s `cq5` on the four relations a baking must have, `has participant`
  to `occupies temporal region`. Each label those checks look for is in an import. *Build...*
  inside Protégé had passed the same checks, because it queries the ontology with its imports
  merged; the generated Makefile's `sparql_test` ran `robot verify` on the edit file alone, which
  sees nothing its imports say. The target now merges the imports first, as `validate_profile`
  already did, and both projects pass `make test` under the same ROBOT. A project created before
  1.134.2 gets the new Makefile from *Project → Update project files...*; until then *Build...*
  says the Makefile is not the one OntoBoard generates, and names that menu item.

- **A sheet narrower than the window was cut off part of the way across, fixed in 1.134.1.**
  Reported from Protégé 5.6.9 on `pizza-bfo`'s four-column sheets: the two header rows ran across
  the whole tab, while the rows beneath them stopped inside the second column, the rest grey - Name
  and Role of could not be seen. The panel above the grid was a plain `JPanel`, which its viewport
  stretches to the tab's width; the header-row table in it shares the grid's columns and spread
  that width across them, while the grid, which does not resize its columns, stayed as wide as
  they had asked to be - and drew them clipped at its own edge. The panel is now a `Scrollable`
  that keeps its own width, so both tables lay the columns out alike, at the widths their contents
  asked for. `SheetGridWidthTest` lays out the real grid in a 2,000 px window: 480 px of grid under
  1,945 px of columns before, all four columns shown after.

- **The sheet editor said "No templates yet" over nine templates one folder away, fixed in
  1.134.0.** Reported from `pizza-bfo`, a test project whose knowledge-graph templates are in
  `kg/templates`. Opened from its edit file, `src/ontology/pizzabfo-edit.owl`, the editor looked
  only in `src/templates`, found nothing, and said so - naming only that one folder.

  `SheetLocation.forOntologyFile` knew two answers: `src/templates` for an ODK edit file and
  `templates/` beside anything else. The folder was also worked out once, when the view was
  built; Protégé restores the last tab at startup, often before any ontology is open, so the
  view could stay pointed at nothing for the session. And the "nothing here" branch changed the
  folder while the previous project's sheets stayed on screen.

  `TemplateDiscovery` now tries four places in a fixed order - `templates/` beside the file
  (`src/templates` instead, for an ODK edit file), the project's `src/templates`, `kg/templates` and
  `templates/` at the project root - where the project is the nearest folder holding `.git` or
  `src/ontology/`, at most four levels up, so a project nested in another repository never picks
  up its host's templates. The view follows the active ontology, never replaces unsaved edits or a
  folder somebody chose, names the folder it opened and where else it looked, and offers every
  candidate under *Project templates ▾*. 29 tests on synthetic layouts, including the nested
  repository and Windows paths with spaces and drive letters.

- **Every row of a sheet failed with "could not interpret" and nothing said why, improved in
  1.134.0.** `pizza-bfo`'s sheets write their IDs as `kg:PB_0000001`; its graph file declares that
  address only as the empty prefix `:`. *Template...* passed on ROBOT's own message for each of
  the 25 rows. It now names the undeclared prefix, where in Protégé a prefix is declared, and -
  when another prefix already stands for an address ending in the same name - that one. With
  `kg:` declared, the nine sheets rebuild the committed graph exactly: 105 axioms, 25 individuals.

- **Every row of a built graph "names 2 different things", fixed in 1.134.0.** Found by
  running `pizza-bfo`'s knowledge-graph exercise end to end: once the graph held the individuals
  the sheets had made, *Check sheets...* - and the sheet editor's marks, which come from the same
  audit - reported all 25 names as ambiguous, the sheet's `kg:PB_0000001` against the graph's
  own IRI for it. The index compared the CURIE as written with the IRI it expands to. It now
  expands a CURIE under the prefixes the open ontology declares, and ROBOT's own, for that
  comparison only - what is completed and written keeps its spelling - and when the prefix is not
  declared the finding says so, and which prefix would settle it.

- ***ID ranges...* offered a block under a name nothing mints with, fixed in 1.134.0.** It
  pre-filled the provenance name from *Record who edits terms...*, while the board and the sheet
  editor mint under the collaboration display name and then the login. Somebody who had typed a
  plain name there was given a block and still told "No identifiers for you yet". All three now
  use one function, `IriGenerator.editorName()`.

- ***Template...* recorded the participant as the author of other people's terms, fixed in
  1.134.0.** Found preparing an expert test on `pizza-bfo`'s knowledge graph, which imports the
  ontology's 1.0.0 release. With *Record who edits terms...* set to *Always*, a sheet row typed
  `hawaiian` - a class only the release declares - got `hawaiian` stamped with a
  `dcterms:contributor` and a `dcterms:created` date, and so did `rdfs:label` and
  `rdf:PlainLiteral`. The run counted a term as new when the graph's own signature lacked it. It
  now counts a term as new only when neither the ontology nor anything it imports has it, and
  never OWL's built-in vocabulary - the rule the plugin's edit watcher already kept. Three tests,
  `TemplateProvenanceTest`.

- ***Release...* proposed 0.1.1 over a published 1.0.0, and would have released it, fixed in
  1.134.0.** Both of OntoBoard's own test projects declare the scaffold's `owl:versionInfo 0.1.0`
  in their edit files, because a release never writes its version back, while `releases/` goes up
  to `1.0.0`. The dialog proposed the next version from the edit file alone, and nothing compared
  what was typed with what had been released: `0.1.1` would have been published below `1.0.0`, and
  `1.0.0` again would have been written over a release somebody may have imported. It now
  proposes the version after the newest release in the same convention (`1.0.1` there), and
  refuses a semantic version at or below the newest release, offering the next patch and minor
  version. A dated release may still be cut again on the same day, as OBO's Makefile does; a date
  before the newest is refused. Ten tests, `ReleaseVersionAgainstReleasesTest`.

- **"Record who edits terms..." offered FOLLOW_THE_ONTOLOGY, ALWAYS and NEVER, fixed in
  1.134.0.** The enum's own names, because the list showed `toString()`. Saved preferences are
  still keyed by the names, so nothing anybody saved changes.

- **Documentation that worked read as a failure, fixed in 1.134.0.** A user documenting
  `pizza-bfo` got a 218 KB page and a dialog showing three ERROR lines and four configuration
  warnings. The errors were Widoco failing to download the previous release for the changelog -
  `.../pizzabfo/0.4.0` is not published - and the warnings that it has no `config.properties`.
  Each is now said once, in a sentence saying what happened to the page, and Log4j2's line
  about having no logging implementation is dropped.

- **A new row in a sheet lost the padding of the identifiers around it, fixed in 1.117.0.**
  A project whose identifiers are seven digits wide - MatWerk publishes
  `http://purls.helmholtz-metadaten.de/mwo/MWO_0001225` - got `MWO_1226` for the next row.

  `TermCreation.nextIdentifier` took the stem as everything before the trailing digits, read
  the digits as a number, added one, and appended it. The padding was in the digits it had just
  discarded. Every identifier produced that way sorts before the ones it follows, matches no
  project's `iddigits`, and **builds perfectly happily**, which is why nothing said so: ROBOT
  has no opinion about how wide an identifier is.

  Found by writing an IRI generator and asking what the existing code did with MatWerk's own
  shape. Both paths now go through `IriGenerator`, which pads to the width its neighbours use
  or to the `iddigits` in the project's `-idranges.owl`.

- **A view config with one view at its root silently cost the tab, fixed in 1.117.0.**
  `viewconfig-sheetstab.xml` began with a bare `<CNode>` rather than a splitter. That parses -
  the SAX handler ignores what it does not recognise - and then throws while the tab is being
  built.

  What Protege does with that is the interesting part. `TabbedWorkspace.addTab` catches the
  exception, **removes the tab from the tabbed pane**, puts an error panel in its place, and
  logs nothing; `addTabForPlugin` still returns the tab. The next call then fails with
  `component not found in tabbed pane`, which names neither the config nor the view.

  2,276 unit tests were green. The host smoke caught it on the first run. Protege's own
  one-view tab - `viewconfig-explanationtab.xml` - is a `VSNode` with one `splits` value, and
  `ViewConfigTest` now pins that shape for every config this plugin ships.

- **Every non-ASCII character in a spreadsheet was corrupted on the way in, fixed in
  1.110.0.** The worst defect found in this area, and it had been there since templates
  arrived in 1.19.0.

  `IOHelper.readTable(String)` opens the file with the *platform default* charset. Measured on
  a Windows machine, where `Charset.defaultCharset()` is `windows-1252`, against the MatWerk
  `city` sheet — which holds the bytes `4A C3 BC` for `Jü`:

  | | |
  |---|---|
  | the file, decoded as the UTF-8 it is | `J<U+00FC>lich` — 6 characters |
  | what `TemplateSheet.read` returned | `J<U+00C3><U+00BC>lich` — **7 characters** |
  | UTF-8 bytes decoded as ISO-8859-1 | `J<U+00C3><U+00BC>lich` — identical |

  So `Jülich`, `Saarbrücken`, `Institut für Materialwissenschaft` and `Humboldt-Universität zu
  Berlin` were all entering the ontology as mojibake through *Template… > Add the axioms*, and
  nothing reported it.

  **Two reasons it hid for ninety releases.** It is platform-dependent — on a machine whose
  default charset is already UTF-8 the same code is correct, so neither the suite nor a release
  build would show it. And it is invisible at a terminal: printing the mojibake string `fÃ¼r`
  to a cp1252 console emits the bytes `C3 BC`, which a UTF-8 terminal then renders as `ü`. The
  bug round-trips into looking correct. It was only caught by comparing code points, and only
  looked for because a write-then-read-again test produced a double-encoded file.

  The fix uses `IOHelper`'s own `readCSV(Reader)` and `readTSV(Reader)` overloads with an
  explicit UTF-8 reader — ROBOT's parser and its quoting rules, with only the decoding changed.
  An extension this does not recognise is still passed to `readTable`, so its refusal and its
  wording are unchanged. A leading byte order mark is also dropped now: three bytes that decode
  to one invisible character, which otherwise sits in front of the first heading so `ID` is not
  recognised as `ID` and nothing on screen says why.

  The tests are written in code points rather than literals, for the reason above.

- **A label column with a language tag produced no label at all, fixed in 1.108.0.** The first
  defect found by running a real knowledge graph through the plugin rather than a fixture, and
  the first one where ROBOT raises nothing, produces axioms, and gets them wrong.

  `A rdfs:label@en` looks like the obvious way to write an English label. Measured on
  robot-core 1.9.8, it produces an annotation whose **property** is
  `http://www.w3.org/2000/01/rdf-schema#label@en` — the language tag concatenated onto the
  property IRI — carrying an untagged `xsd:string`. So `isLabel()` is false and `getLang()` is
  empty: the cell is not a label in English, and not an `rdfs:label` at all. The spelling that
  works is `AL rdfs:label@en`.

  ROBOT **validates `AL` without a tag and rejects it, and does not validate `A` with one.**
  That asymmetry is the whole defect, and it means the sheet succeeds in strict mode — so none
  of the machinery added in 1.106.0 for placing problems is ever entered. The sheet is reported
  as perfect.

  Measured on the MatWerk knowledge graph, 26 sheets and 8,471 rows: its `organization` sheet
  is written this way in two columns across **81 filled cells**, so not one of its
  organizations carries a name that a reader, a query or Protégé's own label renderer can see.
  With the check in place, 25 of the 26 sheets report nothing and `organization` reports two
  problems, each naming the author's own heading and the replacement to paste in.

  The fix is a new class rather than a condition, because reading the template row is what
  several things needed. `TemplateColumns` parses row two into typed columns — kind, property,
  language, datatype, `SPLIT=`, and the `>` depth of an axiom annotation — and reports faults in
  the row itself. `TemplateSheet.run` now consults it before asking ROBOT anything, so the
  faults arrive on the **success** path, which is the only path this sheet takes.

  It deliberately refuses to report what it merely fails to recognise. ROBOT's own acceptance
  pattern for annotation columns is `^>{0,2}A[LTI]? .*` and it gains column types between
  versions, so an unknown spec parses to `OTHER` and is left alone — `DOMAIN` and `RANGE` were
  promoted out of `OTHER` only because the MatWerk sheets use them. One test asserts that
  fourteen specs taken from real PMDco, ECTO and MatWerk templates produce no fault at all; a
  check that fires on a working sheet would be worse than no check.

- **A rename pasted from a spreadsheet used to build an IRI with a tab in it, fixed in
  1.107.0.** Silent corruption reported as success, from the most ordinary thing a person can
  do with that dialog.

  *Rename terms…* takes mappings one per line, as `old -> new` or separated by a tab. Both
  splits were taken with a limit of two, so anything after the second field stayed attached to
  it. A line copied out of a spreadsheet that has a third column — a note, a date, whoever
  decided it — therefore arrived as a target IRI containing a tab and a sentence.

  Nothing downstream objected. Measured on the real code before the fix, a paste of
  `http://example.org/o#old⇥http://example.org/o#new⇥renamed for clarity` gave a plan of
  exactly two changes:

  ```
  AddAxiom(Declaration(Class(<http://example.org/o#new    renamed for clarity>)))
  RemoveAxiom(Declaration(Class(<http://example.org/o#old>)))
  ```

  with `getUnmatched()` empty and `getEntitiesAffected()` reporting 1 — so the dialog called it
  a clean rename of one term while deleting the real one and declaring a class whose IRI cannot
  be dereferenced, typed or found again.

  The check is whitespace rather than full IRI validation, because that is one rule for both
  modes: `PREFIX` takes the leading part of an IRI rather than a whole one, and that cannot
  contain a space either. The message names which side and which character, and says that a
  third column causes it — "is not a valid IRI" would send somebody looking at the IRI instead
  of at the column they pasted.

- **One bad cell used to discard a whole ROBOT template, fixed in 1.106.0.** Found by testing
  the plugin against a real 26-sheet knowledge-graph spreadsheet rather than against its own
  fixtures, which is why it had survived since templates arrived in 1.19.0.

  An `I` column is one whose cells name another term — `I http://purl.obolibrary.org/obo/RO_0001025`
  for "located in", say. It is the kind of column a knowledge graph is mostly made of: six of
  twelve columns in the MatWerk `organization` sheet are `I` columns, and eight of twelve in
  `dataportal`.

  robot-core 1.9.8 raises a bare `NullPointerException("object cannot be null")` for a cell in
  such a column that is neither an IRI, nor a CURIE with a known prefix, nor the label of a term
  that exists. It raises it in partial mode as well as strict — and partial mode is the whole
  basis of this plugin's "report every problem, keep every good row" behaviour. So
  `TemplateSheet.run` fell through to its last-resort branch and returned **no ontology at all**,
  with one problem attached to no row and no column, reading `object cannot be null`.

  Measured on a sheet of nine good rows and one typo: before, nothing was produced and the only
  thing a user was told was `object cannot be null`. After, the nine rows arrive as 36 axioms and
  the problem reads

  > row 12, column "Host institute": Nothing in this ontology is called
  > "Fraunhofer-Gesellschafft", so there is no term for this column to point at. The column is
  > "I ex:hostedBy", which holds a reference to a term: an IRI, a CURIE such as obo:BFO_0000001,
  > or the exact label of a term that already exists — here, in an import, or in another row of
  > this sheet.

  Rows are now built one at a time, repeatedly, so a row may still point at a term that another
  row introduces — the passes continue until one gets nowhere. The offending cell is found by
  experiment on the row, not by reasoning about ROBOT's rules: a cell is blamed only when the row
  still fails with that cell alone in place **and** builds when that cell alone is removed.

  Both halves of that are necessary, and the first attempt had only the second — it blamed the
  `TYPE` column of a row whose fault was four columns further along, because a row with no `TYPE`
  creates no individual, so the offending assertion is never attempted and the row "builds" by
  doing nothing. A precise, confident, wrong finding is worse than the vague one it replaced.
  `theColumnAtFaultIsTheOneNamedAndNotTheTypeColumn` exists to keep that fixed.

  Six tests, all of which fail without the change, each with the symptom above. The blast radius
  was measured rather than assumed: of `I`, `AI`, `TI`, `SC %`, `EC %`, `C %` and `A`, only `I`
  and its `SPLIT=` variant behaved this way.

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

- **Live mode cannot carry every axiom, and the number is measured.** Offering every axiom in pizza
  v5 to a live session, 96 of 141 travel and 45 do not. `SubClassOf`, class and individual
  declarations, `rdfs:label` including renames, `ClassAssertion` and every annotation cross.
  `EquivalentClasses`, `DisjointClasses`, domain, range, property chains, `owl:hasKey`, property
  declarations and every `ObjectPropertyAssertion` do not, because the shared vocabulary has
  eighteen operation types fixed by the web client and an axiom with no operation cannot cross.

  The plugin reports this rather than hiding it - the status bar shows `N changes not shared` on the
  right, with the reason in its tooltip. **Corrected in 1.93.0:** that notice used to be written into
  the same label as the connection status, so the next `Connected` erased it - and an automatic
  reconnect produces one, so a green light could sit over a session in which three of your axioms had
  gone nowhere. It has its own place now and lasts the session. Moving a node, which also does not
  travel, was reported nowhere at all; dragging one while connected now says so once.
  The practical consequence is worth saying plainly: live mode is for drawing
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

- **The canvas lost work in two ways until 1.52.0, and neither could be caught by a test.**
  `capturePositions` walked entity nodes only, so moving a sticky note or resizing a frame was
  discarded on the next refresh - which any edit anywhere in Protégé triggers. And the toolbar's
  `Arrange` never saved what it produced, while the two other callers of the same layout code did, so
  a hierarchical arrangement of thirty classes reverted the moment anything else changed.

  Both were unreachable from a test: the capture was an inline loop inside a 2,114-line view over a
  live `mxGraph`. It is a static function over a geometry lookup now, and the two tests that were
  impossible to write before are in `SchemaCanvasViewTest`. The layouts also leave notes and frames
  alone - a frame slotted into a grid encloses nothing, which destroys the only thing it is for.

  What remains, and is named in the canvas plan (an internal design document):
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

- **The property arrows were not missing, they were unread - 1.75.0.** Asked for as "object/data
  properties drawn as edges from axioms (not standalone nodes)", which reads as "stop drawing the
  boxes". Measuring the project it was reported against says the boxes are the symptom.

  Of that ontology's 74 `SubClassOf` axioms, **none** has a restriction as its superclass, while
  32 of its 34 `EquivalentClasses` axioms contain one — 51 authored property relationships in
  all. The projection read exactly three shapes, `SubClassOf(A, R some B)`, `SubClassOf(A, R only
  B)` and the data equivalent, and the ontology writes none of them. Every relationship its author
  had written was invisible, and the board showed 27 property boxes with nothing attached to them.
  Removing the boxes would have deleted the evidence and left the diagram emptier.

  So the reading came first. Measured on the same saved board: **8 property arrows before, 31
  after**, and ten duplicate cells down to none. A recursive walk over the superclass expression
  now reads restrictions inside `ObjectIntersectionOf` to any depth and inside `EquivalentClasses`,
  plus min/max/exact cardinality, `ObjectHasValue`, and `SubClassOf(ObjectSomeValuesFrom(R B) A)` —
  a scoped domain, drawn the way the user drew it. Union and complement are deliberately not
  walked: a disjunct does not hold, so an arrow for one would assert something nobody wrote, and a
  negation is not something an arrow can say at all.

  **It also closed a round trip the canvas could not complete.** `EdgeAxioms` offers six readings
  when a user draws an arrow and the projection could read back three — so choosing *scoped domain*
  or *functionality* put the axiom in the ontology and made the arrow vanish at the next refresh.
  That is the worst shape a bug can have: the edit succeeded and the evidence of it disappeared.

  **Deleting one of the new arrows is refused, by name.** They come from axioms that carry more
  than one relation. Inside a conjunction, the axiom carries the other conjuncts; inside an
  equivalence, it *is* the class's definition, and removing it would turn a defined class into a
  primitive one. Both refuse before computing anything, say what would be lost, and point at
  Protégé. The refusal subclasses `UnknownEdgeException` so the three existing catch sites still
  catch it, but its message does not claim confusion — the axiom is understood perfectly well and
  it is the deletion that is unsafe. Third release running in which a destructive path appears, and
  the first in which it ships already closed.

  Three smaller things, all measured rather than assumed: one cell per id, because an import
  closure restates axioms across modules and the renderer was inserting a cell per list entry while
  indexing only the last — the surplus cells were drawn, hit-tested and exported, and the forgotten
  ones could not be selected or deleted. Arrows carry the property's `rdfs:label`, where before the
  one part of the diagram that names a relation was the one part written in numbers. And every
  existing edge id is byte-identical, because they travel between peers in a live session, with
  everything new behind a `pe|` prefix where an older peer meets a clean refusal.

  Still to come: the boxes. 27 of that board's 172 members are properties, each with a saved
  position, so suppressing them can silently empty part of a diagram somebody has arranged — much
  safer once the arrows exist than before.

- **A false green, and a convention that has stopped working — 1.75.0.** Partway through that
  release `mvn -o -q compile` reported exit 0 on code that does not compile. The cause is new: the
  editor's Java language server compiles continuously into `target/classes`, so it had rewritten
  the class files after `rm -rf target/classes` and before Maven looked, and Maven reported
  *"Nothing to compile - all classes are up to date."* The failure surfaced only as
  `java.lang.Error: Unresolved compilation problems` thrown out of a test — the language server's
  own output running in place of javac's.

  Emptying `target/classes` is no longer sufficient. Builds now touch every source first and the
  log is checked for `Compiling N source files` rather than for exit 0.

- **The canvas crashed in 1.73.0, and the reason it shipped matters more than the crash - fixed
  in 1.74.0.** Reported within the hour of the release: *"i tried to open mwo, ontoboard gives
  this error in the canvas - An error occurred whilst creating the view. NullPointerException:
  null"*.

  The fault itself is a textbook one. `mxGraphHandler`'s constructor calls `setVisible(false)` at
  line 277; that dispatches to the override 1.73.0 added for alignment guides; and a subclass
  field initialiser has not run at that point, because it runs only after `super(...)` returns.
  The override read a null list, threw, and took the component's constructor, the view's
  `initialise()` and the whole Schema Canvas with it. The class next door already carried the
  warning — `CollaborativeGraphComponent` documents that `createGraphControl()` runs inside the
  superclass constructor and must not read this class's fields — and the lesson that was missed
  is that it applies to *every* method the superclass constructor can reach, not only to the one
  the comment names.

  **1,423 unit tests passed and both hosts smoked PASS while this was happening.** That is the
  part worth keeping. Two independent holes let it through.

  *The suite never built the component.* Ninety-nine test classes covering graphs, projections,
  layouts, gestures and guide geometry, and not one of them constructed a
  `CollaborativeGraphComponent` — everything was covered except the object a user actually gets.
  One line of test closes it, headless, and against the broken code it fails.

  *The host self-test's claim was a sentence, not a check.* It opened the OntoBoard tab and
  returned "opened, its views constructed, and closed again" having verified nothing beyond the
  tab existing. Protege's `View.createContent` catches whatever `initialise()` throws and puts an
  error label in the view's place, so from outside a dead canvas and a working one are identical.
  Worse: when the check was made real it reported that *no view had been built at all* — adding
  and removing a tab builds nothing, because Protege creates view content lazily when a view is
  shown. So the smoke run had never built the canvas in any release, while the comment beside the
  code claimed it was "the first check that the canvas can be built at all under Felix".

  `ViewHealth` fixes both: the view writes its own outcome to a register before Protege can
  swallow anything, and the self-test selects the tab and, failing that, calls `View.createUI`
  itself — the same method Protege's hierarchy listener calls. The receipt line now names what
  was built rather than asserting it.

- **Opening the canvas with no ontology open crashed it, in every release until 1.74.0.** Found
  by the first self-test run that actually built the view, which is the point of the previous
  entry. `activeOntologyFile` dereferenced a null ontology and `resetLayoutForOntology`
  dereferenced it again; either costs the whole view for the session, with nothing to click to
  bring it back. Protege has no active ontology before the first one loads and while it is
  switching between them, so this was reachable by starting Protege and opening the tab.

  Nothing open is a state the canvas has to survive, not an error. Guarded at the four places
  that decide it rather than at each call site — `activeOntologyFile` and `ontologyIriOf` return
  null, `resetLayoutForOntology` stamps an empty IRI, and `OntologyProjection.project` returns an
  empty projection — because the callers all already treat "no file" as "no sidecar".

- **An inferred edge can now be asked why it is there - 1.73.0.** The canvas has drawn the
  reasoner's conclusions since 1.20.0 and could never say where one came from. A conclusion you
  cannot trace is one you have to take on faith, which is the wrong relationship to have with a
  reasoner: the useful question about an unexpected inference is almost never "is the reasoner
  right", it is "which of my axioms did I not mean".

  Right-click a dotted edge, *Why is this inferred?* It is a real justification - a minimal set of
  axioms that forces the conclusion - not a walk up the hierarchy. The difference is not academic:
  `InferredEdges` draws to the nearest ancestor *on the board*, so "Dog is a kind of Animal" can be
  true with no axiom mentioning both, and the answer on a worked example is two justifications, one
  of them `Dog SubClassOf owns some Thing` with `owns Domain Animal` - a property domain, which no
  hierarchy walk would ever have found.

  Three things it is careful about. It explains with the reasoner **Protégé is running**, taken from
  `OWLReasonerManager`: ELK cannot see an entailment that follows from a cardinality restriction, so
  explaining HermiT's conclusion with ELK comes back empty, and empty reads as "there is no reason".
  It reports two independent justifications as two and says what that means - deleting the one axiom
  you found will not remove the edge while the other stands - because the alternative is a user
  deleting an axiom and watching the edge stay. And an empty result is reported as a gap in the
  explanation rather than as an absence of reason, since the edge is on the board precisely because
  a reasoner concluded it.

  It runs off the event thread behind an abandonable dialog, because a justification is found by
  taking axioms away and asking the reasoner whether the conclusion survives - the cost is many
  classifications, not one. `ExplainOperation.explain` is safe to call from inside the bundle:
  checked with `javap`, only `renderExplanationAsMarkdown` constructs Protégé's
  `ProtegeExplanationOrderer`, and that is the method this plugin has always avoided. There is also a
  test that the open ontology has exactly the axioms it started with, because the technique is
  subtractive and the obvious implementation of it would mutate what it was handed.

- **Docker is no longer required for anything OntoBoard can do itself - 1.71.0.** The goal was "completely
  independent of docker". This reaches it everywhere except one cell, and that cell is ODK's: native ODK
  environments are GNU/Linux and macOS only, and ODK's own README says Docker is mandatory on Windows.
  The tools in the image - owltools, Konclude, rdftab, relation-graph - are not ours to reimplement.
  **Konclude is now reachable without the image, since 1.94.0**, but by pointing at a binary you
  installed rather than by shipping one: it is LGPLv3, OntoBoard is Apache-2.0, and upstream
  publishes no arm64 build. See *ROBOT ▸ Reason with Konclude…*.

  **A scaffolded project builds with nothing installed.** `InProcessTargets` runs seven of the eight
  generated targets inside Protégé against the embedded robot-core. Only for Makefiles the plugin wrote:
  the check is byte equality through `OdkRegenerator`, so a real ODK Makefile *and* an edited generated
  one are both refused - running generated steps against a changed Makefile would execute something the
  project does not describe. `prepare_release` is deliberately absent and points at *Project ▸ Release*,
  which has more guards. Every target that reasons works on a copy, and the test asserts the open
  ontology's axiom count is unchanged afterwards.

  **A native ODK environment, and podman.** Five routes now, preferring the ones that need nothing:
  in-process, native ODK, container, host make, refuse. The native route sources the script `odk install`
  leaves behind - `.` not `source`, since dash is `/bin/sh` on Debian and has no `source`. Podman is
  accepted wherever Docker is, because its CLI is Docker's.

  **Check requirements** (*OntoBoard ▸ Project*) lists what you can do and what is installed, with the
  remedy for each. Docker is probed with `docker info` rather than `--version`, because the CLI answers
  while the daemon is down - which is exactly the state this machine was in.

  **The scaffold now publishes documentation.** It wrote no site at all before: no `mkdocs.yaml`, no
  `docs/`, no Pages workflow, while an ODK-created project published on its first push. Same layout now,
  copied from a real ODK repository. `CONFIG_FILE` is set explicitly because ODK's config is
  `mkdocs.yaml` and the action defaults to `mkdocs.yml`.

  **A new project used to fail its own `test` target.** The edit file used three `dcterms` annotation
  properties and declared none; OWL 2 DL requires declarations, so `validate_profile` found three
  violations in a project containing no terms, and the generated CI went red on the first push. Found by
  running the scaffold's own build against its own output.

  **Not verified:** the native-ODK route has never been executed - it is built to the documented layout
  with unit tests, but this is Windows, where ODK does not support it. Podman likewise. Both are marked
  unverified in the receipt.

- **The ODK build runs now, in 1.70.0 - and the advice 1.69.0 added had never once appeared.**

  **The `run.sh` lookup was looking in the wrong place.** 1.69.0 taught `MakeRun` to recognise an ODK
  repository by finding `run.sh`, and looked two levels above `src/ontology`, at the repository root,
  on an assumption. ODK puts `run.sh` *beside the Makefile*. All three real ODK repositories on the
  reporting machine - MWO, go-ontology, environmental-exposure-ontology - keep `run.sh` and `run.bat`
  in `src/ontology`. So the lookup returned null for every real project, the ODK branch never ran, and
  those users got the install-make advice: the exact instruction 1.69.0 existed to stop giving. The
  feature was inert from the moment it shipped, and the test I wrote asserted the same wrong
  assumption, because the fixture is a bare Makefile with no directory around it.

  **An ODK build now runs in Docker, started by the plugin.** Verified before shipping against the
  reporter's own MWO: `make odkversion` exit 0 in 11s, `make sparql_test` exit 0 in 13s with four
  ROBOT SPARQL checks passing, `make config_check` exit 0 in 1s - through `MakeRun`'s own code path,
  not by hand.

  The command is composed rather than delegated to the project's own `run.sh`, and each reason was
  established by trying it: their `run.sh` and `run.bat` both pass `-ti`, and `-t` allocates a
  pseudo-terminal a plugin does not have, so Docker refuses with "the input device is not a TTY";
  `run.sh` needs `sh` and `run.bat` needs a shell, and neither `sh` nor `make` is on the Windows PATH
  Protégé inherits, while `docker` is; and a composed command can be printed into the transcript and
  pasted into a terminal, which it now is, on the first line.

  The image is read from the project's own runner, because go-ontology pins `odkfull:v1.5.4` while
  MWO's is untagged, and building an ontology against a different ODK than its CI uses is how a
  release stops being reproducible. An ODK project is Docker or nothing: falling back to a host `make`
  would parse the Makefile and then die partway through a recipe wanting owltools or a ROBOT plugin
  from inside the image, which is worse than not starting. Availability is probed with `docker info`
  rather than `docker --version`, because on this machine the CLI answered while the daemon was down -
  installed and running are different questions, and the advice now distinguishes them.

  **Still open: the silent build.** "After OK it shows nothing and brings back the menu without
  warning" was not reproduced - every path traced ends in a dialog, and running `MakeRun` against that
  exact project produces one. The likeliest cause is gone, because that project now builds instead of
  refusing, and the one exit that is silent by design logs which action took it. That is weaker than a
  fix and is recorded as one.

- **Three things reported from using it, all fixed in 1.69.0 - and each one was present rather than
  missing.** Found by opening MWO and trying to do ordinary work, which no test in this repository
  could have done.

  **The new-project form threw away everything it had just asked for.** `OdkProjectConfig.validate`
  has eight rules and two of them - *base IRI must end in the ontology ID*, *an SPDX identifier such
  as `CC0-1.0` is not a licence IRI* - reject values that look right. The dialog closed on the way to
  the warning, so a wrong last field cost the other five. It re-prompts now, reusing the components
  it already built, and the message is a banner inside the form rather than a modal over it. Safe
  because `OdkScaffold.create` validates before its first `mkdirs`, so a rejected attempt has written
  nothing; a failure from the *writing* half still stops, because part of the tree may exist.

  **"Open existing ODK project" was reachable only while the board was empty.** Forty-three careful
  lines, wired to one button on the start card - which shows only when nothing is on the board. So
  the action a person looks for after opening something was reachable only before they had opened
  anything, and `OntoBoard > Project` listed *New ODK project...* and *Open from GitHub...* with
  nothing between them. Now `odk/ProjectOpener` with `odk/OpenProjectAction` over it, at SlotA-B.

  The interesting part is the test. `MenuStructureTest` asks of every entry in `plugin.xml` whether
  it leads somewhere, and every one of those tests passed throughout - nothing in `plugin.xml` was
  wrong, the entry was absent from it. The new test runs code-to-menu instead: every non-abstract
  `*Action` class must be named by some entry. Removing the new entry fails it by name.

  **"make is not on the PATH" gave the wrong instruction.** It said the build "needs make and robot
  installed - not Docker", which is right for a scaffolded project and wrong for a real ODK
  repository, which is what the report came from. ODK builds run inside `obolibrary/odkfull`: this
  repo already keeps MWO's Makefile as a fixture and it prints `sh run.sh make ... command` in its own
  help target and loads ROBOT plugin jars from `/tools/robot-plugins` inside the image. Installing
  make and robot buys a build that fails further in. `MakeRun` looks for `run.sh`/`run.bat` two levels
  up now and says one of four things, including - on Windows, for our own Makefile - that make needs a
  POSIX shell too, since the recipes use `rm -f`, `mkdir -p`, `cp`, `cat` and `date +%Y-%m-%d` and
  cmd.exe has none of them. That second requirement had never been mentioned anywhere.

  **Still open, and it is the larger half.** A project this plugin scaffolds could build with nothing
  installed at all: its Makefile shells out to nine programs and all seven of its ROBOT invocations
  already have in-process wrappers here, so the set difference is one operation. That is the next
  release. For MWO it cannot be done - 767 lines of `ifeq`, `foreach`, `define`, recursive make and a
  `SHELL` override, shelling out to wget, curl, rsync, gh, owltools and an `odk.py` this bundle cannot
  contain - and the honest answer there is ODK's own Docker wrapper, which is now what the message
  says.

- **What a plain drag does has now been decided in both directions, and is a mode as of 1.73.0.**
  Worth reading as one story rather than two releases, because the second reverses the first.

  Until 1.68.0 a plain left-drag panned the view, and selecting several terms at once needed Ctrl
  or Shift, documented in a doc comment and nowhere a user would look. 1.68.0 inverted it - a drag
  drew a selection, panning moved to space, the middle button and the right - on the reasoning that
  selecting several terms is the thing a person does most on a board.

  In use it is not. A board is bigger than the window far more often than a selection spans several
  terms, so the gesture people reached for first was the one that had just been taken away, and the
  report back was "click-drag should move the canvas". So neither gesture is behind a doc comment
  now: it is a mode, with two buttons on the toolbar, **H** and **V** to switch, and the choice
  remembered per user in `Preferences`. Panning is the default. Whichever mode you are in, the other
  gesture is on Shift or Ctrl, and space pans in both - that mirror is the only arrangement that
  does not have to be memorised.

  The decision itself is nine lines in `CanvasGesture`, shared by the two call sites that need it:
  the component's `isPanningEvent` and the view's rubberband. They have to be exact complements, and
  the failure is silent either way - if both claim a drag the board pans while a selection rectangle
  is drawn across it, and if neither does the canvas reads as frozen. A test walks all sixteen
  combinations of mode, space, modifier and cell and asserts exactly one claims each. Neither call
  site can be tested; the function between them can.

  One cost, recorded because it is not free: the cursor now says which mode the board is in, and
  `mxGraphHandler.mouseMoved` consumes the mouse-moved event whenever a handler supplies a cursor.
  Empty board therefore consumes where it did not. That already happens over every node - which is
  where anything downstream would care - so the change lands on the part of the canvas where nothing
  is listening.

  What 1.68.0 got right stands. A `ShortcutsPanel`, on **?** over the board and in
  the overflow menu, with tests that both halves of the gesture are present and that no key is listed
  twice - a stale keystroke list is worse than none, because it sends a person looking for a fault in the
  plugin rather than in their own memory. A one-time status line on first use, in `Preferences`, not the
  sidecar. And explicit tests in the rubberband handler: `mxRubberband.mousePressed` in 4.2.2
  never asks what is under the cursor or which button was pressed - `isRubberbandTrigger` is literally
  `return true` - so a plain drag selected regions *on top of nodes* too, and only worked at all because
  `mxGraphHandler` happens to be registered first and consume the event. Registration order is a thing to
  depend on deliberately or not at all.

- **A node being dragged now says what it lines up with - 1.73.0.** A grid is a texture, and the
  question it does not answer is the one that matters: not "is this on a grid point" but "is this
  edge the same as that edge". Without an answer the only way to align two terms was to compare by
  eye, so diagrams drifted by a few pixels everywhere and looked careless in a paper.

  Up to two lines, the nearest vertical and the nearest horizontal, drawn through the stationary
  node's edge and extended past both. Not more: a dense board matches a dozen edges at once and
  drawing them all turns the diagram into a cage. Left, centre and right on one axis, top, middle
  and bottom on the other, plus edge-to-edge touching, with centres tried first so a tie does not
  depend on the order the board happened to be iterated in.

  **With the grid on, a guide means exact.** The grid step is 20 and the tolerance is 4, so two
  snapped nodes are either on the same line or at least 20 apart - inside the tolerance can only
  mean zero. The tolerance earns its keep under Alt-drag, which is this canvas's "ignore the grid
  this once", and there it reads as "you are nearly there".

  Two things it deliberately does not do. It does not snap: the grid already does that, and a
  second magnet pulling against the first is how a node ends up where neither wanted it. And it
  draws nothing into an exported PNG or SVG - `CanvasExport` renders from the graph model and never
  calls the component's paint, which for a mark that exists only during a drag is correct rather
  than a trade-off.

  The geometry is `AlignmentGuides`, over plain rectangles, with fifteen tests. The part that could
  be quietly wrong is not that a line appears - that is obvious the first time anyone drags a node -
  but that it appears for the right reason and in the right place. The arithmetic that gets it there
  is worth recording: `previewBounds` is positioned from `bbox`, the bounding box *including* label
  overhang and stroke width, while the rectangles it is compared against are cell states. The two
  differ by a pixel or two, which is most of a four-pixel tolerance, so the offset
  `getPreviewLocation` adds is subtracted back off.

  **The zoom controls and the overview moved onto the board.** The outline had been pinned EAST at a
  fixed 180px, present even while the start card was showing - a blank grey rectangle beside a "nothing
  here yet" message - and on a board big enough to need an overview it fails at the one job it has, since
  a 3694x613 strip scaled into a 140px box paints as a grey smear. Both float now, bottom-right, and the
  overview collapses to its header; which state it is in lives in `Preferences` rather than the JSON
  sidecar, because collapsing a panel must not dirty a file somebody commits to a repository.

  This is where the spec expected breakage, and the mechanism is worth recording: Delete, Escape, Ctrl+Z,
  Ctrl+F, Ctrl+A and the five zoom keys are all bound on the graph component's
  `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` input map, so the moment focus moves to a *sibling* of that
  component every one of them stops firing - silently, with nothing on screen to say why. A floating
  button is exactly such a sibling. Every one is built through a single `floatingButton` helper that sets
  `focusable` and `requestFocusEnabled` false and hands focus back to the board after the action runs.

  **Dragging a frame left its contents behind.** `SchemaGraph` inserts frames, terms, edges and notes all
  under the default parent, so a frame is a sibling of what it visually encloses and `moveCells` moved
  only the rectangle: drag the "Toppings" frame and it arrived somewhere else, empty. Real parenting was
  rejected rather than overlooked - child geometry is relative to the parent, and `captureInto` reads
  `getX()` as an absolute board coordinate straight into the sidecar, so every saved board would have
  shifted by the frame origin the first time it was loaded. The frame moves its passengers by the same
  delta instead, chosen by centre point so a node overlapping the edge counts as inside. Resizing moves
  nothing, and the dialog that names a frame now says both halves of that.

- **The Collaborate button was not on the screen, and the status line erased its own warnings - both
  until 1.67.0.** The first half of Release 4 of the canvas design spec; the second half landed in
  1.68.0, held back one release because the spec's own regression table calls the floating panels "the
  most likely breakage in the whole plan".

  The toolbar wanted 1261px and began clipping at 1030. The OntoBoard tab in a 1440px Protégé window
  gives it 857, at which `Collaborate...` is laid out at x=812 - past the right edge, unpainted and
  unclickable. `JToolBar` uses a `BoxLayout`, which lays overflowing children out *beyond* the container
  rather than wrapping, and there is no chevron to switch on. The button was simply not there, and
  nothing said so. It is 777px now: the layout combo is gone (201px to choose between four entries
  nobody reopens), Legend, Export and Collaborate moved to an overflow menu, and the two widths that
  were free to grow are pinned - the Find box reached 407px on a wide bar, and the Add button,
  relabelled on every selection change, shifted everything to its right by up to 103px, including the
  Find field, which slid out from under the pointer mid-type.

  **One status label served two producers.** `setStatus()` writes transient board confirmations; the
  collaboration host writes persistent session state that needs acting on. Whichever fired last won,
  permanently - so arranging the board erased the only notice that a colleague would never see your
  edit, and nothing brought it back. Two labels now, the session one carrying a coloured dot.

  **The context menu offered an action that lied about what it acted on**: "Add selected entity to
  canvas", offered even when you had right-clicked a node, where it reads as "add the thing I clicked"
  and in fact acts on Protégé's tree selection. Deleted. The remaining items are grouped in the order a
  person asks - what is under the cursor, what it can be joined to, what can be made here, and what
  takes something away, last.

  **The empty state named a widget that does not exist**: "drag one in from the palette on the left"
  (the left column is Protégé's entity views, the first labelled Classes) and "double-click the board"
  (the card is covering it). Both corrected, plus one line nothing anywhere said before - *taking
  something off the board never deletes it from the ontology*.

  Dark mode is answered rather than themed: the canvas stays light on purpose, because it is the surface
  every exported PNG is composed on and a diagram whose colours depend on the author's IDE theme is not
  reproducible; it gains a border that follows the theme, and the start screen takes its colours from
  the look and feel. That took two attempts - `Panel.background` is the obvious token for the card and
  the wrong one, because it makes the card exactly the colour of the thing it is raised off. Caught by
  re-rendering it.

- **Five gesture defects, all fixed in 1.66.0, and one of them broke the canvas's central invariant.**
  Release 3 of the canvas design spec. Unlike 1.64.0 and 1.65.0 these were not found by looking at a
  render - they were found by reading JGraphX against this code.

  **A Ctrl+drag forged a second node claiming to be the same term.** `mxGraph` starts with
  `cellsCloneable = true` and `mxGraphComponent` with `dragEnabled = true`, and `SchemaGraph` held
  neither. So a Ctrl+drag ran Swing's DnD copy path, and `mxCell.clone` copies value, style, geometry
  *and the id*: the dropped clone was a second vertex whose id was the original IRI. Clicking it pushed
  that term to Protégé's selection; Delete on it reported one term removed while taking the original out
  of membership; nothing wrote it anywhere, so it vanished at the next refresh. One node per IRI is what
  this whole canvas rests on, and two unheld defaults were enough to break it.

  **The wheel zoomed at the viewport centre, not the cursor** - on a 3694px board the node you were
  pointing at slid off screen at every click - **and had no upper bound**, running to 800% while
  `CanvasZoom.MAX_SCALE` documented 400%.

  **A right-drag pan always ended in a context menu.** A right-drag pans by design, but the menu opens on
  any `isPopupTrigger()` release, and on Windows that *is* the button-3 release.

  **The undo debounce guarded against something that cannot happen.** It was written in 1.62.0 on the
  stated grounds that "mxGraph fires `CELLS_MOVED` repeatedly while a drag is in progress". It does not:
  `mxGraphHandler` sets `livePreview = false`, so the model is touched once, on release. What the guard
  actually did was swallow the undo step for any second drag started within 800ms of the first.

  **Alt+drag did nothing and Shift+drag discarded the selection** - Alt is claimed by a forced marquee
  that this canvas's rubberband never reaches, and `isToggleEvent` knows only Ctrl, though
  `installSelection`'s own comment says Shift adds to a selection.

  Beyond the defects: a visible 20px grid (snapping had been on and invisible at a 10px step since the
  first version), Ctrl+0/1/2 for actual size, fit and frame-the-selection, Ctrl+A, Ctrl+D to copy notes
  and frames, sticky-note and frame colours - `NoteLayout.color` had been modelled, copied, persisted and
  rendered since notes existed, and *nothing had ever written it*, so every note on every board was the
  same yellow - and double-click, which expands a term's neighbours or retypes a note. In-place editing
  is allowed for notes and frames only: a term's label is its `rdfs:label`, and letting a double click
  rewrite it would change what the board says without changing the ontology.

  The namespace palette was also wrong twice over. Its javadoc claimed the eight colours "remain
  distinguishable for the most common forms of colour blindness"; three pairs collapse - `#C2554D` and
  `#8A6D3B` are deltaE 5.2 apart under deuteranopia. And the colour was keyed to *how many* namespaces
  had been seen, so dropping one and adding another handed out a duplicate and the same vocabulary got a
  different colour on a different board. Five colours on a lightness ladder, keyed to the namespace.

  Worth recording: the palette change appeared to apply and did not. The patch tool writes a file once
  after all of a call's edits succeed, so a later bad anchor silently discarded two edits it had already
  reported as matched. A new test caught it - "expected #4A90D9 but was #3E8E5A", two colours from the
  palette that was supposed to be gone. Without it the release would have shipped the old palette under
  the new javadoc.

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
  the phase plan (an internal design document) and retires the first entry on
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

- **Renaming a term used to delete its labels in every other language.** A label change said only
  "this term's label is now X", and applying it removed every `rdfs:label` on the term. Three labels
  on one term collapsed to one at the receiving peer. The operation now carries the language tag and
  a peer replaces only that language; an operation with no tag is taken as the untagged label, which
  is what an older peer means by it, so the two versions interoperate without loss.
