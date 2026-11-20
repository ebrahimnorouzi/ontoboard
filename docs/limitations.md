# Limitations and Roadmap

What OntoBoard does not do today, and the constraints of what it does.

## Protégé plugin

### Not implemented

- **Asked for by testers, not built yet:** *History* and *Compare releases* for properties and
  individuals; *Obsolete…* moving a term's subclasses and references to its replacement; a
  contributor's mode, creating the GitHub repository and pushing without a terminal, and a
  beginner mode; bring to front and send to back for frames and notes.
- **No mappings, no DOSDP pattern editing, no pattern mining.** There is no SSSOM support: a
  mapping is an annotation only. A project's DOSDP patterns are built as ODK builds them, but
  nothing in OntoBoard writes or edits a pattern file.
- **The [assistant](assistant.md) suggests, it does not build.** It answers questions, explains
  reasoner problems and suggests changes as cards; it does not build hierarchies or import terms.
  On Protégé 5.5.0 on macOS it can reach only local models. A reasoner problem it explained is
  checked again by running the reasoner, which takes as long as the reasoner does.
- **Turning an ontology into an ODK repository records earlier versions as published.** They are
  not rebuilt and their imports are not turned into modules; the current version is released only
  when ODK's test passes on it - see [working with ODK](odk-workflow.md#with-its-history-turn-an-ontology-into-an-odk-repository-since-11330).
- **Find terms gives up on a server after 30 seconds.** Load a downloaded copy instead.
- **Live collaboration carries four kinds of axiom:** declarations, `SubClassOf`, class assertions
  and annotation assertions. Equivalence and disjointness are the practical loss; the rest is
  counted in the status bar. Use git for those. See [collaboration](collaboration.md).
- **No discussion threads inside the ontology.** A term links to its issue with
  *Terms → Discussion link…*; the discussion stays in the tracker.
- **Pull requests are opened, not reviewed.** *Pull request…* opens one and lists what is open;
  reviewing and merging happen in the browser.
- **One documentation page per ontology.** *Build and release → Documentation…* makes and opens
  the page; Widoco's versioned and multi-language sites are not driven from here, and the CI
  workflow does not publish it.
- **Nothing prevents two people editing one term.** A peer who changed a term in the last five
  minutes is shown as *editing* it.

### Known constraints

**ROBOT and ODK**

- **ROBOT inside OntoBoard is 1.9.8; ODK v1.6.1's image has 1.9.10.** 1.9.10 cannot write OBO on
  Protégé 5.5.0's OWL API. On the projects compared, both write the same files.
- **OntoBoard's own engine runs ODK's Makefile and a project's own rules when every line of them
  can run here.** A target with a line it cannot run is not offered, and the reason names the
  line or the program. Of ODK's 79 sample configurations, 74 run every target of *Build*,
  *Release* and *Refresh imports* by OntoBoard itself
  ([details](measurements.md#what-ontoboards-own-engine-runs-of-odks-79-sample-configurations)).
  [Who runs ODK's targets](odk-workflow.md#who-runs-odks-targets) has the order.
- **What still needs ODK's container** (or ODK's own tools, on Linux and macOS): ODK's SSSOM
  targets, the `db` export, `update_patterns` and `public_release`. A project's own `release.sh`
  runs by OntoBoard itself when every line of it can; a line it cannot run is named before
  anything runs.
- **Build passes ODK's four switches and no other variable:** `IMP`, `MIR`, `PAT` and `COMP`.
- **ODK's `test` fetches the imports again on every run.** Use *Check quickly (keeps the imports
  as they are) - make test_fast*, or untick *Build the import modules again*.
- **Refreshing every import keeps no copy when a download fails**, because ODK's
  `refresh-imports` empties `mirror/` first. Refreshing one import keeps its copy, and
  *Refresh imports...* offers to build from it.
- **DOSDP-Tools is downloaded on first use** (119 MB, into `.ontoboard/tools` in your home
  folder) and needs Java 11: Protégé 5.5.0 needs a Java 11 on the machine. OntoBoard's own check
  of pattern files agrees with ODK's on 191 recorded cases; on other patterns they may differ.
- **Run by OntoBoard itself, ROBOT has Protégé's memory.** For a large import raise Protégé's
  heap: `max_heap_size` in `conf/jvm.conf` for Protégé 5.6; the `-Xmx` line of
  `Protege.l4j.ini` (or `run.bat`) for Protégé 5.5.0.
- **Build checks consistency with ELK**, as ODK does; HermiT is asked as well, for ten seconds,
  on ontologies of up to 20,000 logical axioms. **Release asks the reasoner you choose** (the
  project's own, else HermiT) before anything is built, within a time limit; one that does not
  finish in it is said, and you decide.

**Releases**

- **A release commits the project's new files under `src/` first**, in a commit of their own; new
  files elsewhere are named but not committed.
- **ODK's `.gitignore` leaves the release files out of git**; *Release...* adds them by name.
- **ODK names the released file after the project's address** (`<uribase>/<id>.owl`), whatever
  the edit file says. OntoBoard warns when the two differ.
- **A project made by OntoBoard 1.135 or before is not built or released until it is carried
  over** with *OntoBoard > Project > Update project files...* (tick *Write the changes*).
- **A project just made shows two small changes at its first *Update project files...***, as
  ODK v1.6.1's own update writes them.

**Protégé 5.5.0** (OWL API 4.5.9)

- **Releases differ from ODK's in a few lines** - the OBO header, string datatypes, the order of
  annotations - with the same content. On Protégé 5.6.9 the files are ODK's.
- **An import whose address moves from http to https cannot be loaded.**
- **A web page sent in place of an import's source is read as an empty ontology.**
- **OntoBoard itself does not write ROBOT's JSON** (it needs Java 11): use Protégé 5.6 or ODK's
  container.
- **On Windows an import file that is not RDF/XML may stay locked for a while** after it was read.

**ID ranges**

- **Protégé 5.6 asks for your ID range until you have one.** Its own "No Id Range Found For
  Current User" comes up when the project's ranges name nobody of your name. Where they are ODK's
  placeholders, the board claims one at your first new term; where they name other editors
  (nfdicore, MWO), the range chosen in Protégé's question is not one the board numbers from:
  *Project → ID ranges...* allocates yours, and then Protégé stops asking.
- **The board creates terms only from your own identifier block.** Without one, *No identifiers
  for you yet* until *Project → ID ranges...* allocates one.

**History and Compare**

- **A working copy is compared as ODK's full release would hold it** - its imports folded in,
  reasoned with ELK, relaxed and reduced. A project whose primary release is the base or the
  simple one, or one released with HermiT, may read moves its release does not hold.
- **History names a property or an individual by its identifier**; *Compare releases* names both.

**Spreadsheets and the knowledge graph**

- **No round trip.** *Spreadsheets → Template...* adds what a sheet produces and never removes or
  updates it; rebuild into a fresh copy instead - [how](knowledge-graph.md#4-build-the-graph).
- **The identifiers' prefix must be declared in the graph file**
  (*Active ontology → Ontology Prefixes*), exactly, final slash included.
- ***Template...* types a value only when the cell says so**
  (`2026-11-01T18:00:00^^xsd:dateTime`).
- **The sheet editor looks for templates in four places**; others are opened with *Open folder...*.

**Other**

- ***SPARQL...* queries what is asserted, not what the reasoner concludes.** Use Protégé's
  *DL Query* tab for that.
- ***Check requirements...* does not check** git, the GitHub CLI, Widoco, Konclude or
  DOSDP-Tools.
- **Documentation has no changelog until the previous release is published.**
- ***Share → Git... → Commit everything* commits every file git does not ignore**, the page
  *Documentation...* writes in `build/` included.
- **Edits made in Protégé's own editors are stamped with their author** only once the OntoBoard
  tab has been shown in the session.
- **Some clones get no exclude rules for the assistant's private conversations** - one reached
  through a junction, a `subst` drive or a bind mount.
- **A built-in role's file named in other capitals is a role of its own.** Name role files in
  lower case.
- **Protégé 6 is not supported.** It ships OWL API 5; OntoBoard and the ROBOT inside it are built
  on OWL API 4.
- **The plugin's OWL 2 DL check and `robot validate-profile` disagree on an undeclared annotation
  property**, on purpose: the plugin checks the ontology as edited.
- **ROBOT operations that need RDF4J (Rio) cannot run inside the bundle**, on either Protégé.
- **Guava is pinned to 18.0**, the version Protégé ships.
- **The board:** layout is kept in a sidecar file, so it is not interoperable with CoModIDE; a
  crash while writing it can lose layout, never the ontology; a punned IRI resolves either way for
  selection; a board shows up to 1,500 terms, and on one with more than 150 relations they are
  drawn straight; literals and annotations are not drawn; a board's settings for the project, kept
  in its board file, are dropped when OntoBoard 1.154.0 saves that board. See
  [what the board draws](canvas.md).
- **The bundle is about 64 MB**, because ROBOT runs inside it.

### Not covered by tests

- Podman, and ODK's own tools on Linux or macOS, were not tried. OntoBoard's own engine was run
  live on Windows only; the unit tests run on Windows and on CI's Linux.
- The comparisons with ROBOT and ODK in ODK's image are skipped where Docker is absent; the
  comparisons with their recorded runs are not.
- Most of the Swing UI is checked by hand. Inside Protégé, a startup self-check and
  `tools/smoke.ps1 -SelfTest` run nine menu items, ODK's test and both tabs on every release.

## Web application

The web application is no longer in this repository; its last state is the tag `web-app-final`
(see [feature parity](feature-parity.md)).

## Roadmap

- **Collaboration vocabulary:** equivalence and disjointness in live sessions. Needs rescoping
  before anything is built.

Done: opening pull requests (1.101.0), Widoco documentation (1.103.0), an advisory *editing*
claim instead of locks (1.104.0), and *Check and test → Check project metadata* in place of an
editor for `src/metadata` (1.102.0).
