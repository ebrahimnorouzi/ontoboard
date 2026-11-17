# Limitations and Roadmap

Honest inventory of what OntoBoard does not do. Kept specific rather than
reassuring, because a quality tool that overstates itself is worse than one that admits
gaps.

## Protégé plugin

### Not implemented

What the plugin does not do today. The pattern library (1.82.0, extensible since 1.92.0) and the
SPARQL panel (1.33.0) used to be on this list and are now features - they are described in
[working with ODK](odk-workflow.md) and the [pattern library](patterns/index.md).

- **What five testers asked for in 1.132.0, and is not built yet** - a student, a domain expert,
  an ontology engineer, a reviewer and somebody new to ODK, each using the plugin on their own
  task. Built in 1.132.0: starting a project from the open ontology, *Show only this and its
  neighbours*, `≡` on defined classes, *Imported terms…*, *Write a test from a question…*, the
  Rules layer and Enter for a sibling. Built in 1.154.0: *Check this ontology…* without a
  project - the reasoner, the quality report and the label check on a loose `.owl` file; ODK's
  own words beside each key of *Project configuration…* (a button there for *Update project
  files…* is not needed: since 1.153.0 a save brings the project's files in line); a button on
  the warning that applies its fix - *Declare it* or *Take it out* for an import the
  configuration does not declare, in *Check imports*, and *Release it again as one
  self-contained file* for imports not merged, in *Release…*; and ODK's filter and minimal
  module types, in plain words, when importing terms. Still open, roughly in the order they were
  asked for:
    - *History* and *Compare releases* for properties and individuals as they are for classes
      (relabelled, redefined).
    - *Obsolete…* offering to move a term's subclasses to its parents and point references at its
      replacement, instead of only listing what will change.
    - A mode for adding terms to somebody else's ontology (a contributor's view), creating the
      repository on GitHub and pushing without a terminal, and a beginner mode. (The menus have
      been grouped by job since 1.133.0.) Since 1.133.0, *Turn an ontology into an ODK
      repository…* runs `git init`, commits and tags every version, and prints the two commands
      that publish it; since 1.153.0 *New ODK project…* makes the first commit too.
    - Bring to front and send to back for frames and notes on the board.
- **Mappings, DOSDP pattern editing and pattern mining are not built, and the assistant edits
  little.** There is no SSSOM
  support: a mapping can be written only as an annotation - in Protégé's annotation editor, or
  as an `AI skos:exactMatch` column of a sheet. Since 1.154.0 a project's DOSDP patterns are
  built as ODK builds them (below), but nothing in OntoBoard writes or edits a pattern file, and
  the pattern library does not offer them.
  Nothing extracts design patterns across ontologies. The [assistant](assistant.md) (since
  1.136.0) answers questions and explains reasoner problems. Since 1.137.0 it suggests
  definitions, wordings and changes to axioms - a removal, a replacement or an addition, written
  from a sentence with **Write as OWL…** - as cards you accept or reject. It does not build
  hierarchies or import terms. On Protégé 5.5.0 on macOS it can reach only local models.
- **A reasoner problem is checked again by running the reasoner.** Once the ontology's logic has
  changed, the assistant asks the reasoner again about a problem it explained, before the next
  question goes with it (since 1.151.0): on a large ontology, that question waits for the
  reasoner, as **Explain the reasoner problem…** does.
- **Turning an ontology into an ODK repository records earlier versions as they were
  published.** They are not rebuilt, and their imports are not turned into modules. A version
  that says no date is committed at the time of the conversion, so converting again gives a
  different commit for it, though the same files. The current version is released only when
  ODK's test passes on it; otherwise the project and its history are made, and *Release...*
  makes the release once the test passes. An import that cannot be read is taken out of the
  edit file, by name. The project is ODK's own, the same as *New ODK project…* writes from a
  file - see [working with ODK](odk-workflow.md#with-its-history-turn-an-ontology-into-an-odk-repository-since-11330).
- **Find terms reads what a server sends.** A server that never answers is given up on after
  30 seconds, and one that is down cannot be read at all: load a copy instead - a downloaded file,
  or the one in the ontology's repository, whose documentation page usually links to it.
- **Live collaboration carries four kinds of axiom.** Built as of 1.9.0, with a vocabulary of
  eighteen operation types — but `OperationMapper` reaches an operation for exactly four axiom
  classes: declarations, `SubClassOf`, class assertions and annotation assertions. Everything
  else is counted and reported in the status bar rather than dropped silently. Use git for work
  on the rest. See [collaboration](collaboration.md).

    **Ordered by what people actually write**, counted in NFDIcore, MWO and PMDCO rather than
    listed alphabetically — because the gap that matters is not the one that sounds most
    technical:

    | Not carried | nfdicore | mwo | pmdco |
    |---|---|---|---|
    | equivalence | 16 | 36 | **260** |
    | disjointness | 18 | 18 | 44 |
    | `owl:hasKey` | 0 | 0 | 0 |
    | negative assertions | 0 | 0 | 0 |
    | datatype definitions | 0 | 0 | 0 |

    Equivalence and disjointness are the whole of the practical loss; the three that read as
    serious OWL gaps do not occur once in three real ontologies. A list that gives all nine equal
    weight — as this entry did until 1.102.0 — misdirects whoever reads it next, including
    whoever decides what to implement.
- **Threaded comments and discussion** — a term can be linked to its issue with
  *Terms → Discussion link…*, which writes `IAO:0000233 term tracker item`, and editorial
  notes (`IAO:0000116`/`IAO:0000232`) carry an author and date. But the argument itself
  lives in the tracker: there is no reply thread inside the ontology, deliberately, because
  an unbounded mutable conversation would appear in every release and every diff.
- **Reviewing a pull request** — *Pull request…* opens one and lists what is open, since
  1.101.0. Reading a diff, leaving review comments and merging are still the browser's job, and
  are a larger piece of work than opening one: a review needs the diff rendered, and an OWL diff
  that means anything is *Build and release → Compare releases…*, not a line diff of RDF/XML.
- **A multi-version documentation site.** *Build and release → Documentation…* generates the page
  for one ontology and opens it, since 1.103.0. Widoco's own multi-language and versioned-site
  features are not driven from here, and the generated CI workflow does not publish the page —
  the three real projects that publish one do it with a hand-written job.
- **Nothing prevents two people editing one term** — and nothing in OntoBoard can.
  Protégé owns the edit model, so this plugin learns of a change after it has happened; a lock
  it could not enforce would be worse than none, because somebody would rely on it. What it does
  instead, since 1.104.0, is tell you in time: a peer who has changed an axiom on a term within
  the last five minutes is shown as *editing* it. See [collaboration](collaboration.md).

### Known constraints

- **The ROBOT inside OntoBoard is 1.9.8; ODK v1.6.1's image has 1.9.10.** 1.154.0 tried 1.9.10
  and kept 1.9.8. From 1.9.9 on, ROBOT writes an `.obo` file through a call that the OWL API of
  Protégé 5.5.0 (4.5.9) does not have - its OBO format is not one with prefixes there - and ODK's
  release options take that path, so every release that writes OBO, as ODK's default release
  does, failed on Protégé 5.5.0 with 1.9.10 inside. `RobotDependenciesTest` writes an `.obo` file
  with ODK's options on that OWL API, so the next upgrade meets the reason in a test. What 1.9.9
  and 1.9.10 changed that ODK's recipes meet, OntoBoard's own engine does already: the report's
  queries, and `query --update`, run as 1.9.10 runs it. On the projects compared, the files the
  two ROBOTs write are the same; an ontology whose OBO identifiers need prefixes of its own may be
  written with whole IRIs where the image writes short ones (no recording has one). OntoBoard's
  own engine runs the shell commands ODK's recipes use, `robot`, and stand-ins for `odk-info`,
  `dicer-cli`, `check-rdfxml`, `curl`, and since 1.154.0 `sed -i` substitutions and ODK's DOSDP
  pattern check; DOSDP-Tools itself runs as a program of its own (below). A recipe that calls
  another program of ODK's image - SSSOM's, `semsql` for the `db` export (and `relation-graph`
  through it), `wget`, ODK's Python scripts - is not run by the engine: see "What still needs
  ODK's container" below.
- **Build passes ODK's four switches, and no other variable.** Since 1.154.0 *Build...* in an ODK
  project has a box for each of ODK's own switches - *Build the import modules again* (`IMP`),
  *Download the imports' sources again* (`MIR`), *Make the DOSDP patterns' files again* (`PAT`)
  and *Build the components again* (`COMP`) - each ticked as ODK's default has it, and shows the
  make command they come to under the form, such as `make test IMP=false MIR=false`. No other
  variable can be set from *Build...*; *Release...* sets the version. A plain Makefile, not ODK's,
  gets no boxes. Where OntoBoard itself builds, the targets offered are those it runs with ODK's
  defaults, so a target refused with every box ticked is not offered even where some boxes
  unticked would let it run.
- **ODK's `test` fetches the imports again on every run.** That is ODK's Makefile, in every
  route: a project with an import from the web needs the network for each `test`, and the
  module is written again with the day as its version, so git shows it as changed. *Build...*
  offers ODK's ways round it, in plain words since 1.154.0: *Check quickly (keeps the imports as
  they are) - make test_fast*, or `test` with *Build the import modules again* unticked
  (`IMP=false`). A release runs `make clean` first, which empties `mirror/`, so a release that
  refreshes the imports downloads every source again.
- **A release commits the project's new files first, and leaves the others out.** Since 1.154.0
  *Release...* has a box, ticked by default, *Commit the project's `<n>` new files first*: what git
  has never been told about under `src/` - a new import module and its term list, a new test,
  the board's layout file - is committed before the release, in a commit of its own, "Files of
  the project before release `<version>`", so that the release's tag holds it. Unticked, those
  files are left out of the release's commit, as before. A new file elsewhere in the project - a
  page under `docs/`, a file at the top - is still named before the form, and is not in the
  release's commit if you go on. New files in ODK's `tmp/`, `mirror/` and `target/` are neither
  offered nor committed.
- **Spreadsheets and the knowledge graph do not round-trip.** *Spreadsheets → Template...* adds the
  axioms a sheet produces to the ontology open in Protégé and never removes or updates any: run it
  again after changing a label and the graph holds both labels. Nothing links an individual back to
  its row, and nothing keeps the two in step. Rebuild by undoing the earlier run in the same
  session or by building into a fresh copy of the graph file -
  [how](knowledge-graph.md#4-build-the-graph).
- **The identifiers' prefix has to be declared in the graph file.** A sheet whose IDs say
  `kg:PB_0000001` builds nothing - every row fails with ROBOT's *could not interpret* - until the
  ontology the axioms go into declares `kg:` (*Active ontology → Ontology Prefixes*). Since
  1.134.0 *Template...* names the prefix and, where another prefix already stands for a matching
  address, which one. *Check sheets...* catches it only once the graph already holds the rows:
  then each row's name is reported as naming two different things, with the prefix to declare.
  On a graph without the rows, only *Template...* finds it. OntoBoard names a *missing* prefix,
  not a *wrong* address: declare it exactly, final slash included.
- ***SPARQL...* queries what is asserted.** It runs over the open ontology and its imports, not
  over the reasoner's conclusions: a question about a defined class - "which baked pizzas are
  spicy pizzas?" - returns nothing until the inferred types are written down. Ask such
  questions in Protégé's own *DL Query* tab while a reasoner runs. (The board's *Inferences*
  draws inferred subclass relations between classes, dotted.)
- **The sheet editor looks in four places.** `templates/` beside the open file, the project's
  `src/templates/`, `kg/templates/` and `templates/` at the project root - the first holding a
  sheet is opened, and *Project templates ▾* offers the others. Templates anywhere else are opened
  with *Open folder...*, and that choice lasts while the view is open, not across sessions.
- **What *Check requirements...* checks.** A native ODK environment, Docker, Podman, the program
  ODK's container would run with, ODK's image, GNU make, a POSIX shell, ODK Core's `odk` and Java.
  Not git, the GitHub CLI, Widoco or Konclude, which *New ODK project...* and *Release...* (for
  their commits), *Git...*, *Pull request...*, *Documentation...* and *Reason with Konclude...*
  need, nor DOSDP-Tools, which *Build...* and *Release...* offer to download for a project with
  DOSDP patterns.
- **Documentation has no changelog until the previous release is published.** Widoco builds the
  changelog by downloading the release the ontology names as `owl:priorVersion`; when nothing
  answers there it writes the rest of the page and leaves the word `null` in the changelog
  section. Since 1.134.0 *Documentation...* says so in one sentence instead of showing Widoco's
  ERROR lines.
- **Some clones get no exclude rules for the assistant's private conversations.** OntoBoard
  writes them into every clone it can find where git would list the conversations, but not into
  one that holds a link to the project you did not open it through, nor into one passed through
  only by a junction's target as written in it (a junction made to `B\x\j2\sub`, where
  `B\x\j2` is itself a junction): Java gives OntoBoard a junction's target only fully resolved.
  A directory symbolic link's target is read as written, so since 1.151.0 a junction it names
  gives its clone the rules. A clone that holds a directory symbolic link to the project gets no rules and
  needs none: git does not go into such a link. A project opened through a `subst` drive, or a network drive or share whose root lies
  inside a clone, is not followed above that root, and nothing says so; open it by its own path.
  On Linux, a bind mount is not seen at all. Such a clone lists your private conversations only
  after someone deletes the shared `assistant/.gitignore`.
- **A built-in role's file named in other capitals is a role of its own.** A hand-named
  `Ontology-Expert.md` next to the ontology is listed beside the built-in Ontology expert, and on
  Windows and macOS **Save** of the built-in writes into that file while the assistant keeps using
  the shipped text. Name role files as OntoBoard does, in lower case.
- **Authorship of edits made outside the board is stamped by the board.** The listener that stamps
  `dcterms:contributor` and dates on edits made in Protégé's own editors belongs to the Schema
  Canvas view, so edits made before the OntoBoard tab has been shown in a session are not stamped.
  Read from the code (`SchemaCanvasView`), not yet observed in the host. The stamp is a second
  change after the edit, so *Edit → Undo* removes the stamp first and a second *Undo* the edit
  (also read from the code).
- **The board creates a term only from your own identifier block**, in a project with an
  `*-idranges.owl`. Since 1.154.0 your name is the one Protégé's own ID ranges know you by: git's
  user name where Protégé's *Preferences > User details* asks for it, else the user name set
  there, else your login. Up to 1.153.0 it was the collaboration display name
  (*Share → Collaboration...*), and a block claimed under that name stays yours on the board
  while none is allocated to Protégé's name. Anybody without a block gets *No identifiers for you
  yet* until
  *Project → ID ranges...* allocates one.
- **A project made by OntoBoard 1.135 or before is not built or released until it is carried
  over.** Its Makefile is OntoBoard's own, and *Build...* and *Release...* say so and run nothing,
  not even with a `make` and ROBOT the machine has. 
  *OntoBoard > Project > Update project files...* (tick *Write the changes*) makes it an ODK project, and keeps the ontology, its
  imports and its tests.
- ***Build...* and *Release...* check consistency with ELK, as ODK's Makefile does.** ELK does not
  reason over inverse properties, data ranges, cardinalities or negation, so an ontology only a DL
  reasoner finds inconsistent passes both, as it does in ODK. When a build that reasoned with ELK
  passes, the ontology uses them and has at most 20,000 logical axioms, HermiT is asked as well,
  for ten seconds, and the result warns with any class it finds can have no members; the verdict
  stays ELK's. *Release...* of an ontology in no project uses ELK unless another reasoner is
  chosen, and asks HermiT the same way. For a larger ontology, run HermiT, or
  *OntoBoard > Check and test > Explain...*, before releasing.
- ***Template...* types a value only when the cell says so** (`2026-11-01T18:00:00^^xsd:dateTime`).
  It warns about an untyped value only when the property declares a datatype range, so a date
  typed without its datatype becomes a plain string, silently, under a property with no range -
  NFDIcore's *has value* among them.
- ***Share → Git... → Commit everything* commits every file git does not ignore.** The `.gitignore`
  ODK's seed writes ignores neither `build/`, where *Documentation...* writes the page, nor the
  board's layout file, so a commit after *Documentation...* includes the generated site.

- **What the board draws of an ontology, measured.** On two published ontologies built on BFO,
  with every term shown and class expressions drawn (the default): every logical axiom - 921 of
  921 in one, 529 of 529 in the other - appears as a box, a line or a word in a box's label;
  [What the board draws](canvas.md) lists how. Literals and annotations are not drawn. On a board
  with more than 150 relations, disjointness, domains and ranges are drawn for the term in focus
  rather than all at once, and the other relations faintly until one of their ends is selected.
  A board shows up to 1,500 terms, the ontology's own first.
- **Protégé 6 is not supported, measured.** Protégé 6.0.0-alpha-1 refuses to start OntoBoard:
  *missing requirement `org.protege.editor.owl.ui` version ≥ 5.5.0, < 6.0.0*. Widening that range
  would move the failure, not fix it: Protégé 6 ships OWL API 5.5.1, OntoBoard is built against
  OWL API 4.5's interfaces (Guava's `Optional` among them), and the ROBOT library inside it has no
  OWL API 5 build. Supporting Protégé 6 is a port to OWL API 5 and a replacement for ROBOT's
  in-process operations - worth doing when Protégé 6 is released, not against an alpha.
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
- **Guava is pinned to 18.0**, the version Protégé ships. ROBOT paths beyond reasoning are
  not individually verified against it.
- **Canvas layout is not interoperable with CoModIDE**, which stores positions as OPLa-SD
  annotations inside the ontology. OntoBoard uses a sidecar file to keep the ontology
  byte-clean, so ROBOT `report` and `diff` stay unaffected. The trade is deliberate.
- **Sidecar writes are not atomic.** A crash mid-write can lose diagram layout — never the
  ontology.
- **A punned IRI** (declared as both a class and an individual) resolves
  non-deterministically for canvas selection.
- **Canvas scale.** A board shows up to 1,500 terms, the ontology's own first; past that, the
  others are dragged in from the entity trees. Measured on a release of 660 terms: the board is
  built in 1.9 s and redrawn in 44 ms after an edit, focus takes 0.2 s. A 100,000-class ontology is
  not drawn whole.
- **A board of more than 150 lines draws its relations straight, through boxes too.** Its
  hierarchy is routed and its relations keep the straight connector, drawn faintly until one of
  their ends is selected. Routing them is not the hard part: made-up boards of 192 to 495 lines
  were routed with at most 3 lines left over. Keeping them routed is - the whole board is routed
  again, on the Swing thread, whenever a box moves, and on a board of 270 lines every drag then
  held the board for about two seconds. A 1.135.2 candidate routed boards up to 500 lines and was
  taken back for that. Routing only the lines a move touches would lift it, and is not built.
- **The bundle is ~64 MB**, because ROBOT and its dependencies are embedded to run
  in-process.
- **OntoBoard's own engine runs ODK's Makefile and a project's own rules, when every line of
  them can run here.** Without ODK's container or ODK's tools installed, OntoBoard runs ODK's
  targets itself (since 1.153.0) - only where the Makefile is the one ODK v1.6.1 writes for the
  project's configuration. Since 1.154.0 it also runs the rules and settings a project adds in
  its own `<id>.Makefile`, as make takes them: when every line the target reaches can be read
  here, every program it calls is one the engine has, and nothing is written outside the project -
  all asked before the first line runs. Up to 1.153.0 any rule or setting of the project's own
  that a target reached left the target to the container. Import modules built by rules of a
  project's own - ROBOT chains with `query --update`, `if [ $(IMP) = true ]`, `sed -i` - are of
  this kind: nfdicore and mwo, two real projects that build theirs so, refreshed those imports
  and ran `test_fast` by OntoBoard itself and in ODK's container with the same files - nfdicore on
  Protégé 5.6.9's OWL API, for the reason in "an import whose address moves" below. A line that
  cannot be read here, or a program the engine has no stand-in for, is not run: the target is not
  offered, and the reason names the rule and its line, or the program. Measured over ODK's own 79
  sample configurations, each seeded as ODK seeds it: 74 run every target of *Build*, *Release*
  and *Refresh imports* by OntoBoard itself, 3 stop only where ODK's own make stops too, and 2
  need the container - for SSSOM and for the `db` export - counted with DOSDP-Tools downloaded and
  on Java 11, as in Protégé 5.6 ([how, and target by target](measurements.md#what-ontoboards-own-engine-runs-of-odks-79-sample-configurations)).
  At 1.153.0 it was 48. Those are fresh seeds: a real project's own rules decide for it. A Makefile
  another ODK version wrote is brought to ODK v1.6.1 by *Update project files...*.
  [Who runs ODK's targets](odk-workflow.md#who-runs-odks-targets) has the order.
- **What still needs ODK's container.** These run in ODK's container (Docker or Podman, with
  ODK's image) or, on Linux and macOS, with ODK's tools installed - on Windows the container is
  the only other way: a project's own `release.sh`, which calls ODK through `run.sh`; ODK's SSSOM
  targets (`sssom`, `sssom-cli`, `tsvalid`, ROBOT's SSSOM plugin); the `db` export format
  (`semsql`, and `relation-graph` through it); `update_patterns`, which fetches DOSDP patterns
  from elsewhere with `wget` and `find`; and `public_release`, which publishes a GitHub release.
  *Update project files...* runs ODK's update by OntoBoard's own code where there is no container.
- **On Protégé 5.5.0, OntoBoard itself does not write ROBOT's JSON.** The library that writes it
  needs Java 11, and Protégé 5.5.0 runs on Java 8: a target that writes JSON - a release with
  `json` among its export formats - is refused there with that reason. Use Protégé 5.6, or ODK's
  container.
- **DOSDP patterns are built by DOSDP-Tools, downloaded on first use.** Since 1.154.0 a project
  with DOSDP patterns is built and released by OntoBoard itself as ODK builds it. ODK's pattern
  pipeline calls DOSDP-Tools 0.19.3, a program of 192 libraries, 131 MB unpacked, whose
  libraries clash with ROBOT's, so it cannot be put into OntoBoard's bundle: *Build...* and
  *Release...* offer to download it - 119 MB, the release ODK's image carries - into
  `.ontoboard/tools` in your home folder. It is unpacked only when every library is byte for
  byte the one in ODK's image, and runs as a program of its own, as Widoco does, on Java 11 or
  later: Protégé 5.6 runs it with its own Java, while Protégé 5.5.0 needs a Java 11 on the
  machine - on `JAVA_HOME` or the `PATH`, or chosen as *Run it with* in
  *Build and release > Documentation...*, which Widoco and DOSDP-Tools share. ODK's check of the
  pattern files, `dosdp validate`, is a Python program under the GPL-3.0, which cannot go into
  OntoBoard (Apache-2.0); OntoBoard's engine has its own, written from DOSDP's format, whose
  verdicts were held against ODK's checker on 191 recorded cases. On a pattern unlike those, the
  two may disagree, and its words are OntoBoard's, not ODK's.
- **Run by OntoBoard itself, ROBOT has Protégé's memory.** In ODK's container ROBOT gets the
  project's `robot_java_args`, or nine tenths of the container's memory. OntoBoard's own engine
  runs ROBOT inside Protégé, so a large import - refreshed by `test`, `prepare_release` or
  *Refresh imports...* - has only the memory Protégé was started with. Raise it in Protégé's own
  setting: for Protégé 5.6 `max_heap_size=8G` in `conf/jvm.conf` in Protégé's folder; for
  Protégé 5.5.0 on Windows the `-Xmx` line of `Protege.l4j.ini` beside `Protege.exe`, or the
  `-Xmx` of `run.bat` when Protégé is started with it. Then start Protégé again. Since 1.154.0
  *Build...*, *Release...* and *Refresh imports...*, run by OntoBoard itself, say so at the head of
  their result when the project's `robot_java_args` asks for more than Protégé has, with the
  setting of the Protégé that runs.
- **Protégé 5.6 asks for your ID range until the board has claimed one.** Protégé's own ID range
  plugin looks in `<id>-idranges.owl` for a range allocated to the name it knows you by - git's
  user name where *Preferences > User details* asks for it, else the user name set there, else
  your login - and asks a user it finds none for to choose one ("No Id Range Found For Current
  User") when a project's edit file is opened, and again each time the file is read again - after
  *Update project files...* or an import, for example, even in the middle of OntoBoard's run. It
  is Protégé's dialog, not OntoBoard's. Since 1.154.0 the board claims and mints under that same
  name, so once it has claimed your range - at your first new term on the board, in a project
  whose ranges are ODK's placeholders - Protégé finds it and stops asking; up to 1.153.0 the board
  claimed under the collaboration display name, which Protégé does not know, and the question
  came back. Before the claim, choose your range in Protégé's dialog or cancel it. A range
  claimed under the display name by an earlier OntoBoard keeps the question coming until
  *User details* names you as the range does, or a range is allocated to Protégé's name.
  Protégé 5.5.0 has no ID ranges of its own and does not ask.
- **On Protégé 5.5.0 a release made by OntoBoard itself differs from ODK's in a few lines.** How a
  file is written is the OWL API's, and that is the host's: Protégé 5.6.9 carries OWL API 4.5.29,
  the same as ROBOT in ODK v1.6.1's image, and Protégé 5.5.0 carries 4.5.9. On 4.5.9 an OBO
  file's header has no `idspace:` lines and names Dublin Core properties by their whole IRIs, an
  RDF/XML file writes `owl:versionInfo` with an explicit string datatype, and the annotations
  within an element can come in another order. Measured since 1.154.0 on ten of ODK's own sample
  configurations, three more: an OBO file's header lists its values in another order, a
  `Typedef` for a property the header names by a short name has the property's whole IRI as its
  id, and where the edit file is OBO, `oboInOwl:id` has no label. The content is the same; on
  Protégé 5.6.9 the files are ODK's. A project released alternately on Protégé 5.5.0 without a
  container and in ODK's container sees those lines change back and forth in its release
  commits.
- **On Protégé 5.5.0, an import whose address moves from http to https cannot be loaded.** Some
  of W3C's and purl.org's addresses move so - SKOS's, PROV-O's and DC Terms' among them. The OWL
  API of Protégé 5.5.0 (4.5.9) does not follow such a move, and reads the page about the move
  instead; Protégé 5.6.9's (4.5.29), the one in ODK's image, follows it. Since 1.154.0
  OntoBoard's engine follows the move itself for the address a ROBOT line is given (`-I`), but
  the imports of the ontology fetched there are the OWL API's to load - nfdicore's DCAT import
  brings all three - so such a refresh fails on Protégé 5.5.0 with the OWL API's parse error.
  Protégé 5.6.9 loads them.
- **On Protégé 5.5.0, a page sent in place of an import's source is taken for an empty
  ontology.** Where a server sends an HTML page of its own in the file's place, the OWL API of
  Protégé 5.5.0 reads the page as an empty ontology (TriX), so OntoBoard's engine writes that over
  `mirror/<id>.owl` and stops at the module ("Ontology IRI cannot be null"). ODK, and Protégé
  5.6.9, refuse the page where it is downloaded and keep the copy before it. ROBOT reads with
  Protégé's own OWL API, so this is not put right here.
- **A refresh of every import downloads them all again, and keeps no copy when one fails.** With
  *Download fresh copies first (update the mirror)* ticked and every import chosen,
  *Refresh imports...* runs ODK's `refresh-imports`, which runs `make clean` first and so empties
  `mirror/` before it downloads anything: a source that cannot be downloaded then leaves no copy
  to build its module from, and the result says so. A refresh of one import, ODK's
  `refresh-<id>`, keeps the copy it had, and when its download fails *Refresh imports...* offers
  to build the module from that copy (since 1.154.0).
- **On Windows, a gzipped JSON file keeps Windows line ends.** ROBOT's JSON writer ends its lines
  as the platform does. Since 1.154.0 OntoBoard's engine gives ROBOT's JSON, and DOSDP-Tools' text,
  ODK's line ends (LF) on Windows too; a `.json.gz` is left as it is written, since gzip's own
  header makes its bytes differ from ODK's anyway.
- **A project just made shows two small changes at its first *Update project files...*.** The
  last line of `.gitignore` and the first two lines of the catalog: ODK v1.6.1's own update
  writes its own seed's files that way. After that update the project's files are what ODK
  writes, and the next update changes nothing. *Turn an ontology into an ODK repository...* runs
  the update itself, so a converted project does not show them.
- **ODK names the released file after the project's address.** ODK's release gives the ontology
  the IRI `<uribase>/<id>.owl`, whatever the edit file says. An ontology adopted with another
  address - started from a file, converted, or carried over from 1.135 - keeps its own address in
  the edit file, and its releases carry ODK's name unless `src/ontology/<id>.Makefile` says
  otherwise. OntoBoard warns when the two differ; look at `<id>.owl` before you publish the
  release's tag.
- **On Windows an import that is not RDF/XML may stay locked for a while.** The OWL API keeps a
  file it read as an import open until Java's garbage collector comes by, unless the file is
  RDF/XML. After a conversion or an update, a local import in Turtle or another syntax can then
  refuse to be moved or deleted for a while; wait a moment and try again, or keep local imports in
  RDF/XML. The same holds for an edit file saved in Turtle after OntoBoard itself has built
  the project (seen in a test of 1.153.0): ROBOT reads it through the OWL API. An edit file
  in functional syntax, which is what ODK seeds, or in RDF/XML is not held. On Protégé 5.5.0 an
  OBO file is held whoever reads it (measured in a test of 1.154.0; Protégé 5.6.9's OWL API
  holds none): since 1.154.0 OntoBoard's engine has Java collect after each ROBOT line that reads
  one, and an OBO file Protégé itself read stays held until Java collects.
- **ODK's `.gitignore` leaves the release files out of git.** The files a release writes at the
  top of the project - `<id>.owl`, `<id>-base.owl` and the rest - are ignored by the `.gitignore`
  ODK's seed writes, so *Share → Git... → Commit everything* does not take them in and `git
  status` does not show them after a build. *Release...* takes them in by name with `git add -f`,
  as ODK's own instructions do, in the release's commit.

### What used to be here

Thirty entries describing defects that were found and fixed - the canvas losing work, live
collaboration being inert for forty releases, arrows that were unread rather than missing -
have moved to [what was fixed](fixed.md). They are kept because how a defect was found is
worth more than the fact it is gone, but they are not limitations, and a page called
Limitations should answer what the tool cannot do today.

### Not covered by tests

For 1.153.0's and 1.154.0's ODK routes: Podman was not tried, only Docker Desktop on Windows;
ODK's own tools installed on Linux or macOS were not tried live; OntoBoard's own engine was run
live on Windows only, and its unit tests run on Windows and on CI's Linux. The comparison with
ODK's container was made on small projects, ten of ODK's own sample configurations and two real
projects, not on a large ontology.

The unit tests cover projection, axiom construction, layout persistence, ODK's seed, the
report's rule execution and the OSGi configuration. Some of them reach outside the JVM:
`RobotParityTest` compares the report against real ROBOT in `obolibrary/odkfull`,
`RobotCliParityTest` compares nine more operations against the same image's `robot` command,
`SeedLiveParityTest` has ODK v1.6.1's image seed projects and compares them with what OntoBoard
writes from ODK's templates, and `ReleaseLiveTest` releases a new project with ODK's own container
and git. All are *skipped*, not failed, where Docker is absent — so a green run on a machine
without Docker proves considerably less than it looks, and now says nothing at all about whether
this plugin agrees with ROBOT. What does run without Docker is the comparison with recordings:
since 1.153.0 the seed is held byte for byte against ODK's own seeds, and OntoBoard's own engine
against ODK's own runs of `test_fast`, `prepare_release_fast` and the import targets, recorded
in ODK's image - since 1.154.0 also ten of ODK's sample configurations, rules of a project's own,
ROBOT's `query --update`, GNU `sed` and ODK's DOSDP pattern check. A project with DOSDP patterns
is held against ODK's runs only where DOSDP-Tools is installed.

Up to 1.135 a test ran the project OntoBoard generated in that image, and found three defects in
the generated build that had survived a 30-assertion test class — a `--` inside an XML comment in
the catalog, which made `make` die on the first robot call; no robot invocation passing the
catalog at all; and a release gate weaker than the CI gate. Asserting what a generated file
*says* is not the same as running it. They cover little of the Swing UI: the toolbar, the minimap, drop handling,
the views as Protégé hosts them and every visual choice need a display and are verified by hand.
Treat visual behaviour as unverified after each change.

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
throwaway ODK project — made by *New ODK project...*'s own code from ODK's templates into a temp
directory and deleted afterwards — logging what each reported: Measure, Quality report, Explain,
SPARQL, Export terms, Profile, Check imports, Refresh imports, All notes. Since 1.153.0 it also
runs ODK's `test` on that project, by OntoBoard itself, without starting any program. A person can run the same thing from
*OntoBoard → Run self-test*; both go through one implementation, because a self-test with two
implementations grows a version nobody runs.

**On Windows, on every push, since 1.131.0.** A receipt is a run on somebody's machine, and for
1.129.0 and 1.130.0 that machine was a Mac - while the two defects users had just reported, frames
piled on top of each other and a build that would not run, were found on Windows. CI's Windows
jobs now run the unit tests on a Windows file system, and `smoke.ps1 -SelfTest` itself in
Protégé 5.6.9's and 5.5.0's own Windows downloads, keeping `protege.log` as that job's receipt.
A Windows runner has what the smoke needs and a Linux one does not: Protégé's Windows download
carries its JRE, and the runner has a desktop. The Mac receipt stays a local command.

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
project with dated releases, which a freshly made one has none of. The note-editing and
collaboration items are Swing surfaces and nothing here opens a window.

**The canvas is the exception, as of 1.74.0, and the story of how is worth the paragraph.** The
tab check was added in 1.44.0 with a comment saying it was "the first check that the canvas can
be built at all under Felix". It was not. It added the tab, removed it, and returned "opened, its
views constructed" without asking anything — and Protege builds a view's content lazily, when the
view is shown, so adding and removing a tab builds *nothing*. Two things were wrong at once: the
check verified nothing, and there was nothing to verify because the work never happened. Both were
invisible, because Protege's `View.createContent` catches whatever `initialise()` throws and puts
an error label in the view's place, so a canvas that crashed on every open produced exactly the
same PASS as one that worked. 1.73.0 shipped with the canvas crashing on every open and a receipt
recording PASS 10/10.

It is real now. `ViewHealth` is a register the views write to themselves before Protege can
swallow anything; the self-test clears it, opens the tab, selects it, and — if nothing reports in
— calls `View.createUI` on each view directly, the same method Protege's own hierarchy listener
calls. The receipt names what was built. The first run that did this found a second crash, older
than 1.73.0 and present in every release the view has existed in: with no ontology open, the view
threw on construction and was lost for the session.

This is still not a check that the canvas *draws* anything. Nothing here looks at a pixel. It is a
check that the largest class in the plugin can be constructed inside Felix, which is the failure
class that had been shipping.

Those could now be covered — the scratch project makes it possible — but each needs its own
fixture: a release history, a git remote, an upstream to import from. Inventing those badly would
be worse than the gap. So this still does not "drive the menu", and says so in its own output.

The export check is there for a specific reason. Its safety rested on a chain of inferences — POI
is reached only by `Table.asWorkbook`, which `write` calls only for `xlsx`, which the plugin does
not offer — and a chain of inferences about a classloader is exactly what was wrong about the
report for nine versions.

## Web application

The web application is no longer in this repository; its last state is the tag `web-app-final`
(see [feature parity](feature-parity.md)).

## Roadmap

Ordered by what is being worked on:

1. ~~**Pull requests**~~ — **opening one shipped in 1.101.0.** *Share → Pull request…* runs
   the GitHub CLI: it lists what is open and proposes the branch you are on, after `gh`'s own
   `--dry-run` has checked it and you have approved the exact command. Reviewing one is still the
   browser's job — see above.
2. ~~**Widoco**~~ — **shipped in 1.103.0.** *Build and release → Documentation…* runs it on the
   release product and opens the page.

    It is installed rather than embedded, and that was measured: Widoco is not on Maven Central
    at all, and its only published form is a 40,858,575-byte shaded jar carrying 1,533 OWL API
    entries and 2,044 Guava entries — packages this bundle imports from the host, where a
    second copy is how an OSGi plugin stops resolving. It also needs Java 11 (class file version
    55), so Protégé 5.6.9 runs it with its own JVM and 5.5.0, on Java 8, needs another one
    pointed at. Since 1.135.0 *Documentation…* offers to download the jar when it is missing,
    checked against its SHA-256.
3. ~~**Entity locking**~~ — **answered in 1.104.0, by not building it.** A lock cannot be
   enforced from here, so what ships is an advisory claim: "Bob is editing Calzone", made by
   actually changing an axiom rather than by announcing intent, expiring five minutes after the
   last edit, and dying with its holder because it rides the ten-second presence heartbeat.
   Selection already travelled and is deliberately not treated as editing — every click would
   otherwise look like one.
4. **Collaboration vocabulary** — equivalence and disjointness, which are the whole of the
   practical loss. `OperationMapper` reaches an operation for four axiom classes; of the nine
   kinds this page used to list as equals, `owl:hasKey`, negative assertions and datatype
   definitions occur **zero** times across NFDIcore, MWO and PMDCO, while equivalence occurs 312
   times and disjointness 80. A first plan for this was rejected on review for resting on a
   justification the code contradicted, and it needs rescoping before anything is built.
5. ~~**`src/metadata`**~~ — **closed as not planned in 1.102.0, and the evidence is why.**
   An editor for it was the plan; measuring it showed the file is inert for almost everyone.

    | | registry | PURL config | ontology IRI |
    |---|---|---|---|
    | ECTO | 200 | 200 | `purl.obolibrary.org/obo` |
    | NFDIcore | **404** | **404** | `nfdi.fiz-karlsruhe.de` |
    | MWO | **404** | **404** | `purls.helmholtz-metadaten.de` |
    | PMDCO | **404** | **404** | `w3id.org/pmd` |

    Three of four declare `base_url: /obo/<id>`, configuring a PURL namespace they do not own.
    For ECTO, which does own one, the committed copy has already drifted from the deployed one,
    so editing it locally reaches nobody. The Foundry's intake is now a 15-field GitHub issue
    form rather than these two files, and the directory is generator-owned — MWO shows ODK
    putting all three back after a maintainer deleted them. An editor would have made editing a
    dead file feel productive.

    **What shipped instead** is *Check and test → Check project metadata*, which reports where a
    project's statements about itself disagree. Every check in it fires on a real project: all
    three registry entries declare CC-BY while their ontologies annotate CC0, three of four
    disagree on the title between the ontology and the ODK YAML, and all four annotate a licence
    their ODK YAML omits. When the registry entry is one nothing reads, it gets a single finding
    saying so rather than an itemised list of faults inside it.

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

The specs and plans, including the reasoning behind decisions that were reversed, are internal
and not part of the repository — the plugin was briefly intended to replace the web
application, and then did, when the repository became plugin-only.

An expert evaluation on 2026-10-08 walked six personas through the plugin against NFDIcore, MWO,
PMDco, ECTO and a real 26-sheet knowledge-graph spreadsheet; 72 findings survived an adversarial
pass and 49 claims were thrown out. Its triage list is internal working material and is no longer
published (earlier versions remain in the repository's history). It is not a substitute for this page, which describes the tool as shipped: the
findings that still describe it belong here, as limitations, and the fixed ones in
[what was fixed](fixed.md).
