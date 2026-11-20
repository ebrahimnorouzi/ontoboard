# Working with ODK

What the Ontology Development Kit (ODK) workflow looks like, and which steps OntoBoard does for you.

Every project OntoBoard makes is an ODK project: the files ODK v1.6.1 writes, byte for byte, plus a few of OntoBoard's own. Build, Release, Refresh imports and Update project files run ODK's own Makefile targets - with ODK's tools installed, in ODK's container, or by OntoBoard itself inside Protégé, with nothing to install. Docker is optional; see [Who runs ODK's targets](#who-runs-odks-targets).

**Legend** — ✅ done inside Protégé · 🔶 partly · ❌ you leave Protégé for this · ➖ not applicable

---

## Before anything else: what can I do right now?

*OntoBoard > Check requirements...* lists what is on the machine and what you can do with it; **creating and building an ODK project needs nothing installed**. It checks for a native ODK environment (`odk install`, Linux and macOS only), Docker and Podman, ODK's image, GNU make, a POSIX shell, ODK Core's `odk` command and Java.

- **Container settings...** - *Automatic* (a container when one can be run, else OntoBoard itself), *Always in a container* or *Never in a container*, and the program that runs the container.
- **Pull ODK's image...** - runs `docker pull obolibrary/odkfull:v1.6.1` (about 3.4 GB) only when you press **Pull**; nothing else downloads the image.
- **Set native ODK environment...** - points OntoBoard at the folder `odk install` made.

It does not check for `git`, which *New ODK project...*, *Turn an ontology into an ODK repository...*, *Release...* and *Git...* use; that is why one of them may say the project was not recorded in git.

---

## Who runs ODK's targets

In an ODK project, *Build...*, *Release...* and *Refresh imports...* run the project's own Makefile targets (`test`, `prepare_release`, `refresh-imports`), and *Update project files...* runs ODK's update. Who runs them is decided each time, in this order:

1. **ODK's tools installed on the machine** - a native ODK environment, set in *Check requirements...* (Linux and macOS only).
2. **ODK's container** - when Docker or Podman answers and an ODK image that can run the project is already on the machine: the one the project names (`ODK_IMAGE` or `ODK_TAG` in `src/ontology/run.sh.conf`, or its `run.sh`), else the ODK its Makefile was written by, else ODK v1.6.1's. An image is never fetched for a run.
3. **OntoBoard itself** - everywhere else. Its engine reads ODK's Makefile as make does and runs the recipes inside Protégé, with ROBOT, ODK's ROBOT plugin and stand-ins for the other programs they call; only DOSDP-Tools runs as a program of its own ([below](#a-project-with-dosdp-patterns)). *Stop* ends a run between two lines.

*Check requirements... > Container settings...* decides whether the container is used at all. *Update project files...* skips the first route, and a project's own `release.sh` runs only in the container. Timings are in [Measurements](measurements.md#odk-in-docker-and-ontoboard-side-by-side).

**What OntoBoard's engine runs**: ODK's recipes and the project's own rules in `<id>.Makefile`, if the `Makefile` is the one ODK v1.6.1 writes for the configuration, every line the target reaches can run here, and nothing is written outside the project. This is checked before the first line, so a target is done or not started, never half done; otherwise it is not offered, and the reason names the rule and its line, or what the engine does not do. What still needs the container - a project's own `release.sh`, SSSOM, the `db` export, `update_patterns` - is in [limitations](limitations.md). ROBOT has the memory Protégé was started with, and the result says so where `robot_java_args` asks for more ([limitations](limitations.md)).

**Build's form** offers, where OntoBoard itself runs them, those of `test`, `test_fast`, `reason_test`, `sparql_test`, `robot_reports`, `validate_idranges` and `clean` it can run, the Target field's help saying why one is missing; with the container, every target of the Makefile and `<id>.Makefile`. ODK's targets are named in plain words, with make's command after them. Four boxes set ODK's switches, each ticked as ODK's default has it: *Build the import modules again* (`IMP`), *Download the imports' sources again* (`MIR`), *Make the DOSDP patterns' files again* (`PAT`) and *Build the components again* (`COMP`). Unticked, what the box would make again - the modules, the mirror copies, the patterns' files, the components - is used as it is, and the switch goes on make's command line as `NAME=false`, as the line under the form shows. If OntoBoard itself cannot build with the boxes as set, the form comes back with the reason; a fresh clone, for one, has no copies in `mirror/`. A Makefile that is not ODK's gets no boxes.

### The two kinds of tests

ODK's `make test` runs the reasoner (ELK), the SPARQL checks, ROBOT's quality report with the project's `profile.txt`, the ID ranges check and the OWL 2 DL profile check. `test` first brings the imports up to date, which needs the network; `test_fast` uses them as they are.

| | ODK's checks | Questions |
|---|---|---|
| file | `src/sparql/<name>-violation.sparql` | `src/sparql/<name>.rq` |
| run when | listed under `robot_report: custom_sparql_checks` in `<id>-odk.yaml` | every `.rq` in `src/sparql`, once their rule is in `<id>.Makefile` |
| asked of | the edit file **without** its imports | the ontology **with** its imports |
| run by | ODK's `sparql_test` | the rule `question_test`, which OntoBoard writes into `<id>.Makefile` and hangs on ODK's `test` |

A new project lists ODK's four default checks and OntoBoard's `check_labels` (every class the edit file says something about has an `rdfs:label`). Questions are asked with the imports, since their answer is as often in an import as in the edit file. *Check and test > Write a test from a question...* writes one, and the first time its rule; *Check and test > SPARQL...*, with **Run this project's checks (src/sparql)**, asks both kinds as `make test` does, and names any `-violation.sparql` file the configuration does not list, which ODK never runs.

**What a run says**: who ran it, how long it took and which checks passed; after a passing run with ELK, what HermiT finds that ELK does not. A failure says first what stopped it: the failed check or question and the terms that break it (for a question the model cannot answer yet, what to draw on the board), classes that can have no members, the quality report's findings at the `robot_report: fail_on` level, what takes the ontology out of OWL 2 DL, or an import that [could not be downloaded](#refresh-imports-when-a-source-cannot-be-downloaded). For one of ODK's own checks, fix the term, or take its name out of `custom_sparql_checks` and update the project's files; an edit to the check's file does not last.

### Checking an ontology without a project — *Check this ontology...*

*OntoBoard > Check and test > Check this ontology...* asks three of `test`'s questions of the open ontology, in a project or not, and writes nothing:

- **the reasoner**: ELK, with the imports that are loaded; where ELK passes over some axioms, HermiT too, for up to ten seconds (a box, ticked by default);
- **the quality report**, failing on an error: in a project with `profile.txt` and only the project's own terms, as ODK's `test` does, elsewhere with ODK's default profile (*Check and test > Quality report...* lists the findings);
- **labels**: every class the ontology describes has an `rdfs:label`.

Each gets a row, pass or FAIL, with the details in the log.

### A project with DOSDP patterns

ODK builds DOSDP patterns with DOSDP-Tools and checks them with `dosdp validate` during `test`; OntoBoard's engine runs both, so such a project builds and releases without a container too.

- **DOSDP-Tools is downloaded on first use**, as its libraries clash with ROBOT's: *Build...* and *Release...* offer **Download DOSDP-Tools 0.19.3 (119 MB) and go on**, into `.ontoboard/tools/dosdp-tools-0.19.3` in your home folder, unpacked only if every library is byte for byte the one in ODK's image. Declined, *Build...* runs what it can without it, and *Release...* stops.
- **It needs Java 11 or later**: Protégé 5.6's own, or for Protégé 5.5.0 the first Java 11 OntoBoard finds (Widoco's *Run it with*, `JAVA_HOME`, the `PATH`).
- **The pattern check is OntoBoard's own**, written from DOSDP's format, as ODK's (GPL-3.0) cannot go into OntoBoard (Apache-2.0).

`update_patterns` still needs ODK's container, and OntoBoard does not write or edit pattern files.

---

## 1. Creating a project

*OntoBoard > Project > New ODK project...* makes what ODK's `seed-via-docker.sh` makes - ODK v1.6.1's seed, byte for byte - written by ODK in its container or by OntoBoard from ODK's templates, so Docker, the script and `obolibrary/odkfull` are not needed. Projects made by OntoBoard up to 1.135 are [carried over](#projects-made-by-older-ontoboard-versions).

| ODK step | | OntoBoard |
|---|---|---|
| Go to the parent directory | ✅ | *OntoBoard > Project > New ODK project...*, **Create in folder: Choose...** |
| `./seed-via-docker.sh <ontology>` | ✅ | The wizard writes the seed and OntoBoard's six files, commits, and opens the edit file |
| Configure `<id>-odk.yaml` | ✅ | *OntoBoard > Project > Project configuration...* ([below](#step-6-the-odk-yaml-key-by-key)) |
| `git init`, create the remote, push | 🔶 | *OntoBoard > Share > Git...* commits, pulls, pushes and starts branches; *OntoBoard > Project > Open from GitHub...* clones. Creating the remote is up to you |

### What the wizard produces

The form asks for an ontology ID, title, description, base IRI, licence and folder, and optionally the GitHub owner that ODK's README, CI badge, issue links and pages name. The base IRI must be an address followed by `/<id>.owl`, as ODK names the ontology `<address>/<id>.owl` and its terms `<address>/<ID>_0000001`; OBO's `http://purl.obolibrary.org/obo/<id>.owl` is filled in from the ID, but is right only for an ontology registered with the OBO Foundry.

"Project created" says how many files were written - 44 for a new project - and with git they become the first commit on `main`, as with `odk.py seed`. OntoBoard's own six are `.gitattributes` (LF line endings), `mkdocs.yaml`, `docs/index.md`, `docs/editing.md`, `docs/release.md` and `src/sparql/check_labels-violation.sparql`.

- **Configuration**: ID, title, description, licence, `uribase` (the base IRI without `/<id>.owl`), `repo`, `github_org`, and a `robot_report` block that fails on ERROR, uses the project's `profile.txt` (ROBOT's standard profile) and lists ODK's four checks and OntoBoard's label check. Release artefacts and formats are ODK's defaults (full and base, OWL and OBO).
- **Pages**: ODK's `docs.yml` workflow publishes `docs/` to `gh-pages`; as a new GitHub repository lets workflows only read, `docs/index.md` and `docs/release.md` say how to allow writing.
- **Edit file**: ODK's, in functional syntax, with the placeholder class `<ID>_0000000`. At your first new term, OntoBoard asks which of ODK's two placeholder ID ranges is yours and writes in your name.

The project goes into `<chosen folder>/<ontology id>`, which must not exist yet; one that cannot be finished, or that you stop, is removed again.

### From an ontology you already have

Open it, then *OntoBoard > Project > New ODK project...*: the box **Start from <file> - keep its address and everything in it** fills in the ID, base IRI and title and makes the file the edit file byte for byte, under its own address, leaving your file as it was. It is not offered for a file that is already an edit file.

The configuration then also has `uribase_suffix`, where the ontology's address does not end in its ID, so ODK's modules and releases sit under that address, and `namespaces` with `use_base_iris: TRUE`, so ODK's base release and quality report hold the ontology's terms. "Project created" warns when the release will be named differently from the ontology's address, or when no term begins with an address the configuration names. The imports stay as they are, and the first *Update project files...* asks what to do with each ([section 3](#make-update_repo-and-update-project-files)).

### With its history — *Turn an ontology into an ODK repository…*, since 1.133.0

*OntoBoard > Project > Turn an ontology into an ODK repository...* makes the same project from an ontology you have - any format the OWL API reads, or a web address - with its earlier versions as the project's history. Versions can be files or addresses in any order; each is placed by its `owl:versionInfo`, else its version IRI, else a version in its file name or address. You choose whether imports become modules and whether the history goes into git. A window shows nine steps:

1. Read the ontology with its imports.
2. Read the earlier versions, oldest first, without their imports; duplicates, and versions not older than the ontology, are left out with a note.
3. Make the project, as *New ODK project...* does from a file.
4. Record each earlier version as an ODK release - its edit file, `<id>.owl` at the top and in `releases/<version>/` - with git, as a commit dated as the version says and a tag `v<version>`.
5. Turn imports into declared modules of the terms you use, or the whole ontology when you use none; one that cannot be read is taken out with a warning, one read from a file is kept as it is. A missing title, description or licence is added.
6. Run ODK's `test_fast`; a failure is shown, and the project is made anyway.
7. Commit, with a *History* table in the README.
8. If the test passed, release the current version with `CHANGES.md`, a commit and a tag; its version IRI takes the last earlier version's shape where *Release...* offers it, else ODK's.
9. Show the two commands that create the GitHub repository and push it with every tag.

**Stop** ends it after the current step, keeping what is written. Converting again gives the same files, apart from the dates they record, the same tree at every tag, and the same commits for versions that say when they were made.

Left to you: earlier versions are recorded as published, not rebuilt; an import that could not be read stays out until *OntoBoard > Reuse > Import terms from a list...* brings its terms back; after a failed test, *OntoBoard > Build and release > Release...* releases once it passes; and where the ontology's address is not shaped like `<uribase>/<id>.owl`, the release carries ODK's name, with a warning - check `<id>.owl` before you publish the tag. Without git the versions are still written under `releases/`, and step 9 says how to start a history.

### Projects made by older OntoBoard versions

Up to 1.135 the wizard wrote a project of OntoBoard's own (a short Makefile calling ROBOT directly, and a configuration nothing read), which *Build...* and *Release...* do not run. *OntoBoard > Project > Update project files...* carries it over: the configuration is converted for ODK, with `uribase` from the edit file's address and `profile.txt` as the report profile; each imported module is declared, to be rebuilt from its recorded source, or kept as it is when it came from OntoBoard's pattern library or has no recorded source; the test queries stay `.rq` files, asked by a rule added to `<id>.Makefile`; and ODK's update writes the rest. The ontology, README, pages, releases, report profile, queries, modules and term lists are kept; the `owl:versionInfo` 1.135 wrote into the edit file is taken out, since ODK's release adds its own. **Write the changes** is off by default, so the first run only shows what would change.

### ODK repositories OntoBoard did not make

*OntoBoard > Project > Open existing ODK project...* opens one. *Build...*, *Release...* and *Refresh imports...* run its own Makefile, `<id>.Makefile` and `release.sh` in the ODK image its Makefile names, if that image is on the machine; it is released as its maintainers release it, with no `releases/<version>/` copy or `CHANGES.md` unless it keeps a `releases/` folder already. OntoBoard's own engine runs only what ODK v1.6.1 writes; *Update project files...* moves a repository to ODK v1.6.1 when you ask, and refuses one written by a newer ODK.

### Step 6: the ODK YAML, key by key

*OntoBoard > Project > Project configuration...* shows every key of `<id>-odk.yaml` with its value and line number; every scalar is editable at any depth, including all twelve settings the ODK tutorial walks you through.

- Each key's tooltip is ODK v1.6.1's own description of it, or of its block.
- Values are addressed by path (`import_group.products[0].module_type`), so an edit never lands on another product's key of the same name.
- A structure as a whole (`import_group`, `robot_report`, a `|` block of text) is read-only, since replacing it would delete what is inside; its scalars are editable.
- **Add to a list...** and **Remove entry** add and remove list entries in the list's own indentation, quoting a value YAML would read as a number or a boolean.
- Edits are spliced into the text: changing `title` changes one line, and comments survive.
- A save is refused if the file changed on disk meanwhile (a text editor, a `git pull`, a `make update_repo`); the write is atomic, and a file with a duplicate top-level key is refused.

**Saving runs ODK's update**, which writes the Makefile, workflows, catalog and the edit file's imports from the configuration, so every key is honoured: ODK's own update in the project's ODK image where that is on the machine, OntoBoard's for a project on ODK v1.6.1, otherwise the file alone, with the rest following at the next *OntoBoard > Project > Update project files...*. A save never moves a project to another ODK version. ODK's [project schema](https://incatools.github.io/ontology-development-kit/project-schema/) describes each key.

---

## 2. The workspace

| Directory | | What OntoBoard does with it |
|---|---|---|
| `src/ontology/` | ✅ | The edit file, the catalog, both Makefiles, `imports/` and the `-odk.yaml` are read and acted on (section 3) |
| `src/sparql/` | ✅ | ODK's checks and your questions, run by *Check and test → SPARQL…* as `make test` runs them ([the two kinds of tests](#the-two-kinds-of-tests)) |
| `src/scripts/` | ✅ | *Project → Run a project script…* runs one (below) |
| `src/metadata/` | 🔶 | The OBO Foundry registry entry. *Check and test → Check project metadata* compares it with the ontology; nothing edits it, since for a project not in the Foundry the build never reads it - [and three of four measured projects are not](limitations.md#roadmap) |
| `src/templates/` | ✅ | ROBOT templates, opened in the **OntoBoard Sheets** tab (below) and built by *Spreadsheets → Template…* |

### The sheet editor

**Window → Tabs → OntoBoard Sheets**, or *Spreadsheets → Sheet editor*, shows a grid for each template of whatever you have open, from the first of these folders that holds a `.tsv` or `.csv`:

1. `templates/` beside the open file - for an ODK edit file, the project's `src/templates/`;
2. the project's `src/templates/`;
3. `kg/templates/`, for a knowledge graph kept beside the ontology;
4. `templates/` at the project root.

The project is the nearest folder above the file, at most four levels up, that holds `.git` or `src/ontology/`; **Project templates ▾** picks another folder, even a new one for *New sheet...* to write into. Beyond a spreadsheet:

- References complete from the open sheets and the ontology; one to something that does not exist can be created by right-clicking it, from the sheets' own evidence.
- Ten kinds of mistakes are marked as you type - ambiguous names, duplicate identifiers, near-duplicate labels, dead references, pasted whitespace, curly quotes, missing identifiers, placeholders, quoted references, columns the template ignores - and the obvious ones are fixed in one undoable step.
- The header rows (column name, ROBOT template string) are editable and checked with robot-core's `validateTemplateString`: red where ROBOT would refuse the cell, building nothing from the sheet, and amber where it would ignore it.
- *Generate IRIs...* fills every row that has content but no identifier, after a preview, from the project's ID ranges, the sheet's own numbering, a timestamp, a UUID or the label.
- *Import Excel…* turns each tab of an `.xlsx` into a `.tsv`; `.xlsx` is not written back.
- Nothing is written until you press Save.

### Running your own scripts

*Project → Run a project script…* lists `src/scripts/` (or the Makefile's `SCRIPTSDIR`) and runs the script you pick, with arguments - in the container first, then a native ODK environment, then this machine, with a build's mount, working directory and heap settings. The interpreter comes from the `#!` line or the extension; a script with neither is listed but not offered. The exact command, the route and any warning, such as Windows line endings, are shown, and nothing starts until you agree. Arguments are split as a shell splits words (`"my file.owl"` is one) but not handed to a shell (the native-ODK route quotes each one), so a semicolon reaches the script as that character.

**Stop** kills a running script, build or git command, a build's container included; an in-process ROBOT operation cannot be interrupted. A script gets 15 minutes, a build 45.

---

## 3. `src/ontology`, file by file

### `<ontology>-odk.yaml` 🔶

Edited in *OntoBoard > Project > Project configuration...* ([above](#step-6-the-odk-yaml-key-by-key)); every change OntoBoard makes to it ends with ODK's update. OntoBoard itself also reads:

| Key | What it changes |
|---|---|
| `robot_java_args` | ROBOT's memory in ODK's container - without it, nine tenths of what Docker or Podman reports, as ODK's `run.sh` computes it. OntoBoard's own engine uses Protégé's memory - see [limitations](limitations.md) |
| `robot_report.fail_on`, `robot_report.use_labels` | What *Check and test → Quality report…* counts as a failure, and whether it shows labels or IRIs |
| `robot_report.use_base_iris`, `namespaces`, `uribase` | Which terms that report covers: as in ODK, only the project's own, unless `use_base_iris` is `false` |
| `import_group.products` | Which imports *Refresh imports...* rebuilds; *Import terms from a list...* adds to it |
| `robot_report.custom_sparql_checks` | Which of ODK's checks *SPARQL...* runs |

### `make update_repo` and *Update project files...* ✅

ODK's update (`sh run.sh odk.py update`; `make update_repo` is deprecated) writes what ODK owns from `<ontology>-odk.yaml` - the `Makefile`, `run.sh`, ODK's checks and workflows, `.gitignore` and the catalog merged with the project's, and the edit file's imports - and leaves the files a person writes alone. *OntoBoard > Project > Update project files...* runs ODK v1.6.1's own update in its container where that image is on the machine, and otherwise makes the same files itself. It moves an ODK repository up to v1.6.1 to that version, refuses one written by a newer ODK, and [carries over](#projects-made-by-older-ontoboard-versions) older OntoBoard projects.

- *Write the changes* is off by default, so you first see what would change. With it on, you are asked to save first, and the ontology is read again afterwards. Commit first, so git can show the update and take it back.
- Imports it would take out are asked about first: keep what the ontology uses of one as a module, keep all of it, or take it out. One read from a file on this machine can only be kept as it is, and its path goes into no file of the project.
- When OntoBoard makes the update itself, an import that cannot be read stops it before anything is written, and a file that differs only in line endings, or an edit file that says what it said before, is left as it was.
- A new project's first update changes the last line of `.gitignore` and the first two of the catalog - ODK's update writing its own seed's files its own way.

### `Makefile` and `<ontology>.Makefile` 🔶

The `Makefile` is ODK's, written by its seed and again by its update. `<id>.Makefile` is the project's own: ODK's Makefile includes it, ODK's update never writes it, and *Build…* lists its targets. OntoBoard writes into it only the rule that asks the project's questions, between two marker lines.

OntoBoard does not help you edit the custom Makefile. An edited ODK `Makefile` is not run by OntoBoard's engine, and OntoBoard warns that *Update project files...* writes it again, throwing the edit away. Rules of your own belong in `<id>.Makefile`, which OntoBoard's engine also runs when every line can be run here.

### `<ontology>-idranges.owl` ✅

*Project → ID ranges…* shows who holds which block and allocates a new block to an editor. The first time you add a term in a project whose ranges are all placeholders, OntoBoard asks which of ODK's two ranges is yours and writes in your name as Protégé's own ID ranges know it (git's user name where Protégé's *Preferences > User details* asks for it, else the name set there, else your login). Protégé 5.6's own ID range plugin asks the same whenever the file is read again while your name is in no range - see [limitations](limitations.md). Allocation rewrites the file from a template, normalising indentation; declarations the template does not model are carried through, and if anything would still be lost, OntoBoard names the lines and leaves the file untouched.

### `profile.txt` ✅ (by location, not by configuration)

*Check and test → Quality report…* uses the `profile.txt` beside the open ontology (ROBOT's bundled profile when you clear the field), with the project's `robot_report.fail_on` and `robot_report.use_labels`. In a project OntoBoard makes, ODK's report reads the same file, so a rule relaxed there is relaxed for both; a `ROBOT_PROFILE` the Makefile points elsewhere applies to ODK's build only.

> **Not the same thing:** *Check and test → Profile…* is OWL 2 EL/QL/RL/DL validation — ODK's `validate_profile`, not `robot report --profile`.

### `<ontology>-edit.owl` ✅

*Project → Open existing ODK project…* opens the edit file under `src/ontology` - `.owl`, `.obo`, `.ofn` or `.ttl` - choosing the one the configuration names, else the single file named so, and refusing when there is none or more than one. Protégé saves your edits; ODK's update may rewrite the file in functional syntax, and Protégé then reads it again. Your board is kept beside it in `<id>-edit.owl.ontoboard.json`, yours to commit or ignore.

### `catalog-v001.xml` 🔶

*Reuse → Check imports* shows what each import maps to and why an unresolved one failed. ODK's update writes the catalog's entries; *Import terms from a list...* writes one itself only where that update cannot run now. *Reuse → Imports graph…* draws the whole imports closure, unresolved imports dashed and red, with *Copy as DOT*. Resolution is Protégé's own, as OntoBoard installs no catalog-backed IRI mapper, so if an import resolves differently from how `robot` resolves it, that is why.

---

## 4. Adding a custom import

ODK's own walkthrough is six steps; OntoBoard does five of them in one operation, the way ODK does them.

| ODK's step | | In OntoBoard |
|---|---|---|
| **1.** Declare it under `import_group` in the YAML | ✅ | *Reuse → Import terms from a list…* writes the product |
| **2.** Run `update_repo`, so the Makefile picks it up | ✅ | The declaration ends with ODK's update |
| **3.** Add term IRIs to `<import>_terms.txt`, then `make refresh-<id>` | ✅ | *Find terms…* or *Import terms from a list…* writes the list; ODK's `refresh-<id>` builds the module |
| **4.** Copy the import URI into `<ontology>-edit.owl` and `catalog-v001.xml` | ✅ | ODK's update writes both |
| **5.** Copy the generated import schema into `<ontology>.Makefile` | ❌ | By hand; only for a `custom` module whose rule you write |
| **6.** Set `module_type`, then `update_repo`, `clean`, `make` | ✅ | *Import terms…* asks what the module holds (below) |

*Import terms…* asks what the module holds in plain words, each answer one of ODK's module types:

| *The module holds* | ODK's type | With the terms you chose |
|---|---|---|
| *The terms you chose and what they need* | `slme`, BOT (ODK's default) | their parents up to the top, and what defines them |
| *Only the terms you chose, with their "is a" links* | `filter` | which of them is a kind of which, and nothing else |
| *The terms you chose, with every relation between them* | `minimal` | also their links through other relations - "a is part of d" |
| *The whole ontology* | `mirror` | all of it |

The module is extracted with BOT at once, and ODK's refresh then builds the type chosen; where that cannot run now, the result says the module is OntoBoard's extraction until *OntoBoard > Reuse > Refresh imports...* has ODK build it. ODK has no MIREOT type, so a new import in an ODK project is not offered one; `minimal` is the nearest. A pattern from OntoBoard's library, or terms from a file on this machine, are declared as kept as they are (`module_type: custom` with `mirror_type: no_mirror`), which ODK's refresh leaves alone, and no local path goes into the project. An OBO ontology needs no `mirror_from`, as ODK then downloads `http://purl.obolibrary.org/obo/<id>.owl`; any other import gets its source.

### Refresh imports, when a source cannot be downloaded

In an ODK project *Reuse → Refresh imports…* runs ODK's refresh targets for all imports or one, as declared in `import_group.products`. With **Download fresh copies first (update the mirror)** ticked, that is `refresh-imports` or `refresh-<id>`; unticked, the modules are built from the copies in `src/ontology/mirror`, and only an import with no copy is downloaded.

When a download fails, on any route, the result names the import and its address and says why: the network or the server did not answer; the server answered, but not with the ontology; or what came is not an ontology ROBOT can read, such as a page sent in the file's place. *Build...* and *Release...* say the same, with the fast target to run instead. Where an earlier copy exists, *Refresh imports...* offers **Build the module from the copy you have (`mirror/<id>.owl`, from `<date>`)**, ODK's `no-mirror-refresh-<id>`. A failed `refresh-imports` leaves no copies, as it empties `mirror/` first; a refresh of one import keeps its copy.

A module from the pattern library is never downloaded: *Refresh imports* lists it as `pattern library` and leaves it alone, so import the pattern again to change it. In a project of the older layout OntoBoard rebuilds modules itself - `slme` with BOT, TOP, STAR or MIREOT - and names the reason for any other kind (`custom`, `slme` with `SUBSET`, `mirror`, `filter`).

### An import the configuration does not declare — *Check imports*

ODK's update keeps only the imports the configuration declares ([section 3](#make-update_repo-and-update-project-files)). With an ODK project's edit file open, *Reuse → Check imports* names each undeclared import, with a button to put it right:

- **Declare it**, for one that can be read: it is declared as a module of the terms you use, or the whole ontology, and ODK's update puts the module in its place - unless there are unsaved changes, or nobody can make the update now.
- **Take it out**, for one that cannot be read and so stops ODK's update: it is taken out of the open ontology (*Edit → Undo* brings it back), and the next save brings the project's files in line.

Nothing is downloaded again: what is known of an import is what Protégé has loaded.

### An import module that calls itself by another name

Protégé leaves out an import whose file's ontology IRI is not the one it is imported by - nfdicore's
and MWO's modules, built with another `ONTBASE` - while ROBOT and ODK's make take the file the
catalog gives. OntoBoard puts such modules in when the project is opened, and again when a run
rebuilds them, without changing a file, and says so once; *Check imports* names each file;
*Refresh imports...* builds them under the names they are imported by.

### The pattern library

*Reuse → Pattern library…* browses the 162 ontology design patterns shipped with the plugin - `bfo` (4, from [BFO 2020 ‘notime’](http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl)), `odp` (122, the [ODP portal](https://ontologydesignpatterns.org/)), `nfdi` (15, [NFDI MatWerk](https://nfdi.fiz-karlsruhe.de/matwerk/patterns/)), `pmdco` (13, [PMDco](https://materialdigital.github.io/core-ontology/docs/patterns.html)) and `mwo` (8, [MWO](https://ise-fizkarlsruhe.github.io/mwo/docs/patterns/)) - and your own, by collection, publisher, category or search. Ten ODP entries are another pattern under a second name, and say which.

- **Suggested for this ontology**, the first ordering, ranks patterns by how much of each your ontology already has - an exact IRI counting double, a name once, built-ins such as `owl:Thing` not at all - with the reason, such as *"3 of its 4 terms are already in your ontology: Agent, Role, hasRole"*. A score of 1.00 means your ontology already has all of the pattern, and the library does not know what you have already imported.
- **Importing** hands the terms to *Reuse → Import terms from a list…*: a module of the terms you chose, not what the pattern itself imports. The pattern file is copied to `src/ontology/mirror/<id>.owl`, which ODK's `.gitignore` keeps out of git, the module is declared as kept as it is, and on the board the terms arrive in a frame named after the pattern, in one undo step.
- **Your own patterns** are `.owl`, `.ttl`, `.rdf`, `.owx`, `.omn` or `.ofn` files in `~/.ontoboard/patterns/<collection>/`, or in the folder *Your patterns…* points to, such as `src/patterns` in a project, so they travel with it. Name, description, competency questions and publisher are read from the file; a file that will not parse is named behind *problems*, with the count of any left unread past 200 in a folder.

### Editing an import's terms — *Imported terms…*

*OntoBoard → Reuse → Imported terms…* lists each import's terms by label - those on its term list and those that came along because a listed term needs them - with how often your axioms use each. **Add terms from …**, **Take out of the import** and **Rebuild from the source** each write the term list, re-extract the module by the project's declared method, keep its IRI, and change the open ontology at once. Term lists are edited in place, so comments survive, the trailing `# label` comments ODK and ROBOT write among them, and a line meant as a term that is not one is named, not skipped. Reordering, and editing a term or a comment in place, are not supported.

---

## 5. Proposing the change

*Share → Pull request…* lists the repository's pull requests (**Show what is open**) or proposes your branch (**Open a pull request for this branch**) with a title, a description, the branch to merge into - empty for the repository's default, which an ODK project's is not always - and whether it is a draft. It runs the GitHub CLI, `gh`, with its own login; OntoBoard keeps no credentials. It checks the branch first (a detached HEAD, the base branch itself, a branch nobody has pushed), runs `gh pr create --dry-run`, and shows the exact command, which runs only when you approve it. Without `gh`, or not logged in, it says how to fix that and gives GitHub's compare URL for your branch. Reviewing and merging stay in the browser; to compare releases, use *Build and release → Compare releases…*.

---

## 6. Releasing, in either convention

*OntoBoard > Build and release > Release...* in an ODK project is ODK's release, `make prepare_release` ([who runs it](#who-runs-odks-targets)), or the project's own `src/ontology/release.sh`. Around it OntoBoard refuses a version already released, holds the release against the last one, keeps a copy where the project keeps them, and commits and tags after showing the git commands. Nothing is pushed.

You are asked to save first, as ODK builds from the file on disk; files new to git outside `src/`, such as a page under `docs/`, are named first, with **Cancel, to commit first** and **Release anyway**. The form:

- **Version** - the next patch, or today's date for a dated version; for a first release, `1.0.0` in a project OntoBoard made, else today's date (ODK's default). It becomes the tag `v<version>`; one that has a tag or a `releases/` folder already is refused.
- **Version IRI** - ODK's `<ONTBASE>/releases/<version>/<id>.owl`, `<ONTBASE>/<version>/<id>.owl`, `<ONTBASE>/<version>` (one IRI for the whole release) or a pattern from `{ontbase}`, `{version}` and `{file}`, where `<ONTBASE>` is `<uribase>/<id>`; the last release's shape is proposed. Any shape but ODK's reaches make as `ANNOTATE_ONTOLOGY_VERSION=...`.
- **Refresh the imports first** - on, `prepare_release`, which fetches and rebuilds the imports and needs the network; off, `prepare_release_fast`. Missing, with the reason, where OntoBoard itself cannot build the full release.
- **Reasoner** - the project's own (`reasoner:` in `<id>-odk.yaml`), else HermiT; ELK, HermiT,
  JFact or Whelk. It is asked before anything is built: an inconsistent ontology, or a class that
  can have no members, stops the release with the axioms behind it; a reasoner that does not finish
  in time is said, and you decide. ELK is named with what it does not read.
- **Refuse to release when a published term is gone** - on by default.
- **Release even when it cannot be compared with the last release** - off by default, so a last release that cannot be read stops the release before anything is copied, committed or tagged.
- **Commit the project's `<n>` new files first** - on by default, shown when files under `src/` are new to git (a new import module and its term list, a new test, the board's layout file); they get their own commit first, so the release's tag holds them. Files in `tmp/`, `mirror/` and `target/` are never offered.

With a `release.sh`, OntoBoard sets only its `VERSION=` (and `PRIOR_VERSION=`, shown as **Previous version**) and runs it: in ODK's container where one runs - a script with Windows line endings as a copy without them, the file unchanged - else itself, inside Protégé, each line said as it starts and each `make` it calls run with ODK's own Makefile; a line it cannot run is named before anything runs. The version IRI is the script's; a script with no `VERSION=` line is refused.

**The run**: `make clean`, then `make prepare_release VERSION=<version>`. ODK's release runs `test` first, so a release whose test fails is not built; `clean` empties `mirror/`, so refreshed imports are fetched again. ODK writes the release files at the top of the project (by default `<id>.owl`, `<id>.obo` and their base and full variants). Then:

1. The new `<id>.owl` is held against the last release; a term that is gone rather than deprecated is named, and with the box on, nothing is copied, committed or tagged. One of the project's own (its ID ranges' prefix) is to be brought back and deprecated; one that came with an import, to be named in that import's term list where its source still has it.
2. In a project OntoBoard made, or one that keeps them already, the files are copied to `releases/<version>/`, with `CHANGES.md` from the second release on.
3. The git commands are shown, and run when you press **Commit and tag**: the new files' commit if chosen, then `release(<version>)`, with the release files added past ODK's `.gitignore`, and the tag `v<version>`. A tracked file that `clean` removed, such as `tmp/README.md`, is put back first. With **Not now**, or without git, the commands stay in the log. Pushing is yours: `git push --follow-tags`.

4. The release records itself: in its log always, and as `releases/<version>/RELEASE.md` and the tag's message where the project keeps its releases - what built it, its measures, the reasoner and what it said, ODK's report and profile check, what changed. *History → Release record...* shows it; *Compare releases* says how each side was checked.

Files the release rewrote are read again in Protégé. Stopped after ODK has built it, a release writes nothing of its own, so the version can still be released.

A release file - `<id>.owl` and the others at the top of the project - opened in Protégé is said for what it is, once, with **Open the edit file** and **Keep this file**; importing a pattern or terms, refreshing imports and releasing are refused there.

### An ontology in no project

*Release...* on an ontology outside a project releases it where it is: it runs the reasoner, writes the inferences down, stamps a version and keeps a copy under `releases/<version>/` beside the file, refusing if the quality report finds an error or a term the last release published has vanished. Your file is not modified.

You choose the version's convention; the two publish different IRIs, and a version IRI cannot be taken back once anybody has imported it:

| | Dated (OBO) | Semantic (semver) |
|---|---|---|
| version | `2026-10-12` | `1.2.0` |
| `owl:versionIRI` | `<base>/releases/2026-10-12/<id>.owl` | `<base>/1.2.0` |
| kept at | `releases/2026-10-12/<id>.owl` | `releases/1.2.0/<id>.owl` |
| next one | whatever day it is | a decision: patch, minor or major |

The dated form is what OBO publishes and ODK's Makefile defaults to; the semantic one follows [semver.org](https://semver.org/) and links `owl:priorVersion` to the release before. The default follows what the ontology already declares. A second dated release on the same day would replace `releases/<date>`, so it asks first, with **Cancel** as the default. **One self-contained file (merge the imports in)** is on when the ontology imports anything; without it, the dated copy would fetch its imports over the network when opened, and the result offers **Release it again as one self-contained file**. A `release.sh` in the folder whose `VERSION` disagrees is reported before anything is written.

To check such an ontology without releasing it, use *Check and test > Check this ontology...* ([above](#checking-an-ontology-without-a-project-check-this-ontology)).

---

## 7. Publishing the documentation

*Build and release → Documentation…* runs Widoco on the release product, `<id>.owl`, and opens the HTML page an ODK project publishes, without pushing. It writes to `build/documentation`, not `docs/`, so the page is not committed by accident. Documenting the open edit file is offered only as a preview, with a warning: it imports its modules rather than containing them, and lacks the release's version IRI. Success means the page exists, not only that Widoco exited cleanly.

Widoco is installed, not bundled. *Download…* saves `widoco-1.4.25-jar-with-dependencies_JDK-11.jar` (about 39 MB) from Widoco's v1.4.25 release on GitHub into a folder you choose, keeping it only if its SHA-256 is the one OntoBoard was built against; or fetch it with your browser and pick it with *Choose file…*. It needs Java 11: Protégé 5.6.9's own, or for Protégé 5.5.0 (Java 1.8.0_121) a JDK 11 set in *Run it with*. Protégé 5.5.0's Java cannot check GitHub's certificates, so *Download…* uses Windows' own certificate store on Windows and the system's Java certificate store on Linux, where there is one; on macOS, use the browser.
