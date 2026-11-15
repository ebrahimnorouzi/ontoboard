# Working with ODK

What the Ontology Development Kit workflow looks like, and exactly which steps OntoBoard does for
you. Every verdict on this page was checked against the code and against a real ODK repository;
where something is partial or missing it says so, because a tool that overstates itself is worse
than one that admits a gap.

**Since 1.153.0 every project OntoBoard makes is an ODK project**: the files ODK v1.6.1 itself
writes, byte for byte, and a few of OntoBoard's own beside them. Build, Release, Refresh imports
and Update project files run ODK's own Makefile targets on it, in one of three ways - with ODK's
tools installed on the machine, in ODK's container, or by OntoBoard itself inside Protégé, with
nothing to install. Docker is optional. [Who runs ODK's targets](#who-runs-odks-targets) says
which one is used when.

**Legend** — ✅ done inside Protégé · 🔶 partly · ❌ you leave Protégé for this · ➖ not applicable

---

## Before anything else: what can I do right now?

*OntoBoard > Check requirements...*

It lists what is on the machine and what you can do with it. The answer that matters first:
**creating and building an ODK project needs nothing installed** - OntoBoard writes ODK's files
from ODK's templates, which it carries, and runs ODK's Makefile itself.

The rows: a native ODK environment (ODK's tools installed with `odk install`, Linux and macOS
only), Docker and Podman (asked with `docker info`, not `--version` - the CLI answers while the
daemon is down), the program OntoBoard would run ODK's container with, **ODK's image** (which one
is on the machine), GNU make, a POSIX shell, ODK Core's `odk` command and Java. Each row that is
missing says what it is for and whether anything needs it.

Three buttons settle things since 1.153.0:

- **Container settings...** - whether ODK's targets run in a container, and which program runs
  it. The three choices are *Automatic* (in a container when one can be run, else by OntoBoard
  itself), *Always in a container* (without one, nothing runs and the reason is said) and
  *Never in a container* (OntoBoard runs what it can itself, whatever is installed). The program
  is found on the PATH and where Docker and Podman are usually installed, or named by you.
- **Pull ODK's image...** - offered where Docker or Podman answers and ODK's image is not on
  the machine. It shows the one command it runs, `docker pull obolibrary/odkfull:v1.6.1`, and the
  size, about 3.4 GB, and runs it only when you press **Pull**. Nothing else in OntoBoard ever
  downloads the image.
- **Set native ODK environment...** - points OntoBoard at the folder `odk install` made.

It does *not* probe `git`, although *New ODK project...*, *Turn an ontology into an ODK
repository...*, *Release...* and *Git...* use the git executable - if one of them says the
project was not recorded in git, that is why.

---

## Who runs ODK's targets

Since 1.153.0, *Build...*, *Release...* and *Refresh imports...* in an ODK project run targets
of the project's own Makefile - `test`, `prepare_release`, `refresh-imports` - and *Update
project files...* runs ODK's own update. Who runs them is decided each time, in this order:

1. **ODK's tools installed on the machine** - a native ODK environment, set with *Set native ODK
   environment...* in *Check requirements...*. ODK supports this on Linux and macOS only.
2. **ODK's container** - where Docker or Podman answers *and* an ODK image that can run the
   project is already on the machine: the image the project names itself (`ODK_IMAGE` or
   `ODK_TAG` in `src/ontology/run.sh.conf`, or a tag written into its own `run.sh`), else the ODK
   its Makefile was written by (`ODK_VERSION_MAKEFILE`), else ODK v1.6.1's, which the result
   then says. An image that is not on the machine is never fetched for a run.
3. **OntoBoard itself** - everywhere else, with nothing to install. Its own engine reads ODK's
   Makefile as make reads it and runs the recipes inside Protégé: ROBOT runs inside OntoBoard,
   with ODK's ROBOT plugin, and the few other programs ODK's recipes call (`odk-info`,
   `dicer-cli`, `check-rdfxml`, `curl` and the shell's own commands) have stand-ins. No make, no
   container and no other program is started. The log reads as make's own, and *Stop* ends a run
   between two lines. On a new project of a few classes, on one machine, ODK's `test` took 1 to 3
   seconds this way, and 90 to 125 seconds in ODK's container on a busy machine.

*Update project files...* skips the first: it runs ODK v1.6.1's update in its container where
that image is on the machine, and otherwise makes the same files itself. A release by the
project's own `release.sh` runs in the container only, since the script calls ODK through
`run.sh`.

**What OntoBoard's own engine runs.** ODK's recipes, and only ODK's. A target is run only when
the project's `Makefile` is the one ODK v1.6.1 writes for the project's configuration, when the
project's own `<id>.Makefile` changes nothing of what the target runs, and when every line the run
will need can run here - all asked before the first line runs, so a target is done or not
started, never half done. Otherwise the target is not offered, and the reason names the rule or
setting of the project's own and its line, or what the engine does not do, and says what else
runs it: ODK's container, or ODK's tools installed on the machine. One thing in a project's own
makefile is OntoBoard's and is run: the rule for the project's questions, as long as it stands
as OntoBoard wrote it (see [the two kinds of tests](#the-two-kinds-of-tests)).

For *Build...* the engine is asked about `test`, `test_fast`, `reason_test`, `sparql_test`,
`robot_reports`, `validate_idranges` and `clean`, and offers those it can run; the Target field's
help says why one is missing. With the container, *Build...* offers every target of the
Makefile, following its `include` into `<id>.Makefile`. Either way `test` is chosen first.

**The setting.** *Check requirements... > Container settings...* decides whether the container
is used at all: *Automatic* is the order above; *Never in a container* leaves the container out
whatever is installed; *Always in a container* runs nothing without one and says why.

### The two kinds of tests

ODK's `make test` is the check: the reasoner (`reason_test`, with ELK, as ODK's Makefile sets),
the SPARQL checks (`sparql_test`), ROBOT's quality report with the project's `profile.txt`
(`robot_reports`), the ID ranges (`validate_idranges`) and the OWL 2 DL profile. `test` brings
the imports up to date first, which needs the network; `test_fast` runs the same on the imports
as they are.

A project has two kinds of tests, since 1.153.0:

| | ODK's checks | Questions |
|---|---|---|
| file | `src/sparql/<name>-violation.sparql` | `src/sparql/<name>.rq` |
| run when | its name is listed under `robot_report: custom_sparql_checks` in `<id>-odk.yaml` | every `.rq` in `src/sparql`, once the rule below is in the project's own makefile |
| asked of | the edit file **without** its imports | the ontology **with** its imports |
| run by | ODK's `sparql_test` | the rule `question_test`, which OntoBoard writes into the project's own `<id>.Makefile` and hangs on ODK's `test` |

ODK's four default checks are listed in every project OntoBoard makes, and OntoBoard's label
check after them (`check_labels`: every class the edit file says something about has an
`rdfs:label`). A question is the other kind because what it asks is as often answered in an
import as in the edit file - asked without the imports it would change its answer without a word.
*Check and test > Write a test from a question...* writes a question and, the first time, the
rule that asks it; ODK's container, ODK's tools and OntoBoard itself all run that rule, since it
is make's like any other. *Check and test > SPARQL...*, with **Run this project's checks
(src/sparql)**, asks both kinds the way `make test` does, and names a `-violation.sparql` file
the configuration does not list - ODK never runs that one.

**What a run says.** "make test succeeded in 12s", or "test succeeded in 2s, run by OntoBoard
itself", then how many of ODK's and your checks passed, with your own tests by name. After a
passing run that reasoned with ELK, a warning says what HermiT finds that ELK does not see.

ODK's Makefile stops at the first line that fails, and make's last word is then an IRI or
"Error 1". So a failure says first what stopped the run, read off what make and ROBOT printed,
whoever ran it:

- a check or question that failed: its rule - for one of ODK's own checks in a sentence - and the
  terms that break it, by their names. For a question the model does not answer yet, what to
  draw on the board to answer it. For one of ODK's own checks: fix the term, or take the check's
  name out of `custom_sparql_checks` and update the project's files - ODK's update writes that
  file again, so an edit to it does not last;
- a check the configuration lists whose file is not there - one of ODK's own is written again by
  *Update project files...*;
- what the reasoner found: the classes that can have no members, by name, and what makes the
  first of them so;
- the quality report's findings, only when the report is what stopped the run - at the level the
  project's `robot_report: fail_on` names (ERROR in a project OntoBoard makes; a report told
  `none` never stops it);
- what takes the ontology out of OWL 2 DL, with *Check and test > Profile...* to name the axioms.

Then where make stopped, and the project's own tests that had passed by then. The whole log is
kept and can be saved, and whatever the run rewrote that Protégé has open - an import module the
full `test` built again - is read again afterwards.

---

## 1. Creating a project

ODK's own route is `seed-via-docker.sh`, which runs ODK's `odk.py seed` in ODK's container.
**Since 1.153.0 OntoBoard's route makes the same repository**: ODK v1.6.1's seed, every file of
it byte for byte, whether ODK writes it in its container or OntoBoard writes it from ODK's
templates without one. Up to 1.135 the wizard wrote a smaller project of OntoBoard's own, which
ODK's tools could not run; [such a project is carried over](#projects-made-by-older-ontoboard-versions).

ODK's seven steps, and what each one is inside Protégé:

| # | ODK step | | OntoBoard |
|---|---|---|---|
| 1 | Go to the parent directory | ✅ | *OntoBoard > Project > New ODK project...*, **Create in folder: Choose...** |
| 2 | Start Docker | ➖ | Not needed. Where Docker or Podman answers and ODK's image is on the machine, ODK writes the project in its container; otherwise OntoBoard writes the same files from ODK's templates |
| 3 | `curl -O seed-via-docker.sh`, `chmod +x` | ➖ | Not needed: OntoBoard runs `odk.py seed` in ODK's container itself, or renders ODK's templates |
| 4 | `docker pull obolibrary/odkfull` | ➖ | Not needed. OntoBoard never pulls on its own; *Check requirements...* offers **Pull ODK's image...** and asks first |
| 5 | `./seed-via-docker.sh <ontology>` | ✅ | The wizard writes ODK's seed, adds six files of OntoBoard's own, makes the first commit and opens the edit file - see below |
| 6 | Configure `<id>-odk.yaml` | ✅ | *OntoBoard > Project > Project configuration...* since 1.80.0. Every key visible, and since 1.89.0 every scalar editable at any depth — [measured table below](#step-6-the-odk-yaml-key-by-key). Since 1.153.0 saving brings the project's files in line with it, as ODK's update does |
| 7 | `git init`, create the remote, push | 🔶 | The wizard makes the project a git repository with one commit, as `odk.py seed` does. Commit, pull, push and starting a branch are under *OntoBoard > Share > Git...*, and *OntoBoard > Project > Open from GitHub...* clones. Creating the remote is left to you |

### What the wizard produces

The form asks for an ontology ID, a title, a description, a base IRI, a licence and the folder to
make the project in. The base IRI must be an address followed by `/<id>.owl`, because ODK names
the ontology `<address>/<id>.owl` and its terms `<address>/<ID>_0000001`; OBO's address,
`http://purl.obolibrary.org/obo/<id>.owl`, is filled in as you type the ID, and is right only for
an ontology registered with the OBO Foundry. Every rule is checked before anything is written,
and a refusal keeps what you typed. A box, ticked by default, makes the project a git repository.

From the answers OntoBoard writes the ODK configuration, `<id>-odk.yaml` - the one file of a new
project that is OntoBoard's - and ODK writes the rest from it: in its container where Docker or
Podman answers and ODK v1.6.1's image is on the machine, otherwise OntoBoard renders ODK's own
templates, which it carries. The two give the same bytes; a test holds them against each other.
OntoBoard then adds six files of its own, never over one of ODK's, and with git installed makes
everything the first commit, "initial commit" on the branch `main` - what `odk.py seed` does,
without the first build ODK's seed goes on to, which needs the network. "Project created" says
how many files were written - 44 for a new project - and which of the two wrote them. On a small
project, on one machine, ODK's own seed in its container took about 40 seconds.

```
README.md  CONTRIBUTING.md  issue_template.md  .gitignore
.github/workflows/  qc.yml  docs.yml
src/ontology/   <id>-edit.owl  <id>-odk.yaml  Makefile  <id>.Makefile  <id>-idranges.owl
                catalog-v001.xml  profile.txt  run.sh  run.bat  README-editors.md  tmp/
src/sparql/     ODK's checks (*-violation.sparql), its queries for reports, README.md
src/scripts/    run-command.sh
src/metadata/   <id>.md  <id>.yml  README.md

OntoBoard's own:
.gitattributes  mkdocs.yaml  docs/index.md  docs/editing.md  docs/release.md
src/sparql/check_labels-violation.sparql
```

What the configuration says: the ID, title, description, licence, `uribase` (the base IRI
without `/<id>.owl`) and `repo`; a `robot_report` block that fails on ERROR, reads the project's
own `profile.txt` (`custom_profile: TRUE`, so ODK's seed writes ROBOT's standard profile there)
and lists ODK's four checks and OntoBoard's label check. Release artefacts and export formats are
left to ODK's defaults - full and base, OWL and OBO. There is no `documentation:` block, so ODK
writes no pages of its own; `mkdocs.yaml` and the three pages under `docs/` are what ODK's
`docs.yml` workflow publishes. `.gitattributes` pins line endings to LF, so a Windows clone is the
same bytes as everyone else's.

The edit file is ODK's: functional syntax, the title, description and licence, and ODK's
placeholder class `<ID>_0000000`, "root node". The ID ranges file holds ODK's two placeholder
ranges; the first time you add a term, OntoBoard asks which one is yours and writes your name into
it.

The repository is created at `<chosen folder>/<ontology id>`, and creation is refused if that
directory already exists — there is no "seed into the folder I already made". A project that
cannot be finished, or whose making you stop, is taken away again, so the same answers can simply
be given once more.

### From an ontology you already have

**The open file, in *New ODK project...*.** Open the ontology, then
*OntoBoard > Project > New ODK project...*: the box **Start from <file> - keep its address and everything in it** fills in
the ID, the base IRI and the title from it. The file becomes the project's edit file byte for
byte, under its own address whatever its shape, and the file you opened is left as it was. The
box is not offered for a file that is a project's edit file already (`*-edit.owl`).

Since 1.153.0 the configuration of such a project says two more things. Where the ontology's
address does not end in its ID, `uribase_suffix` puts what ODK names under the project's base -
its import modules, its releases - under the ontology's own address. And `namespaces`, with
`use_base_iris: TRUE`, names the addresses the ontology's terms begin with, because ODK takes a
project's own terms to begin with `<uribase>/<ID>_` alone: without it, ODK's base release - the
file meant to be imported - and its quality report would hold none of the ontology's terms.
"Project created" says when ODK will name what it releases differently from the ontology's
address, and when none of its terms begins with an address the configuration names, with the
address most of them begin with and where to put it.

The ontology's imports stay as they are. ODK's update takes out an import its configuration does
not declare, so the first *Update project files...* asks what is to become of each - see
[section 3](#make-update_repo-and-update-project-files).

### With its history — *Turn an ontology into an ODK repository…*, since 1.133.0

*OntoBoard > Project > Turn an ontology into an ODK repository...* makes the same project from an
ontology you already have, and turns its earlier versions into the project's history. You
give:

- **the ontology** — a file in any format the OWL API reads (RDF/XML, Turtle, OWL/XML,
  functional syntax, Manchester, JSON-LD…), or a web address; the open ontology is offered;
- **its earlier versions**, if you have them — files or web addresses, in any order and any mix
  of formats. Each is placed by the version it says it is: its `owl:versionInfo`, else its
  version IRI, else a version in its file name or address;
- **the folder** to make the project in — a new folder named after the project id;
- the **id** and **title**, filled in from the ontology: its preferred prefix
  (`vann:preferredNamespacePrefix`), else the end of its IRI, else its file name, skipping words
  such as `ontology` or `core` that say nothing about which ontology it is;
- whether to **turn imports into modules** and whether to **record the history in git**.

A window then shows nine steps, each marked when it starts (▶), and when it ends (✓, or **!**
when something needs your attention), with every line it writes below them:

| # | Step | What it does |
|---|---|---|
| 1 | Read the ontology | Reads it with its imports, and says its format, IRI, version, size and any import that could not be read |
| 2 | Read the earlier versions | Each on its own — what it imported is history, not something to fetch. Oldest first; a version given twice, or one not older than the ontology's own, is left out with a note |
| 3 | Make the project | ODK's own seed with the ontology as its edit file, as *New ODK project...* makes it when it starts from a file - in ODK's container, or from ODK's templates - and OntoBoard's six files |
| 4 | Record the earlier versions | Oldest first, each as an ODK repository keeps a release: the edit file as it was, the released file `<id>.owl` at the top of the project and in `releases/<version>/`, and with git a commit dated as the version says and a tag `v<version>` |
| 5 | Turn imports into modules | Each import becomes a product the configuration declares: a module of the terms your ontology uses, or the whole ontology when its own axioms use none of them. An import that cannot be read is taken out, with a warning that names it; one read from a file is kept as it is. Then ODK's update writes the Makefile's rules, the catalog and the edit file's imports. A title, description and licence are added if the ontology has none, because the quality report asks for them |
| 6 | Build and test | ODK's `test_fast`, run by whoever runs ODK's targets on this machine. A failure is shown, and the project is made all the same |
| 7 | Record the project in git | A *History* table in the README, then a commit |
| 8 | Release the current version | Only when ODK's test passed: ODK's release, as *Release...* makes it, on the modules as they are - the files at the top of the project and under `releases/<version>/`, `CHANGES.md` against the version before, a commit and a tag. The version IRI takes the shape the last earlier version has, where it is one of the three *Release...* names, else ODK's |
| 9 | Ready to publish | The commands that push it to GitHub with every tag |

**Stop** ends it after the step it is in; what is written so far stays. **Open the project**
opens the edit file in Protégé. The commits are signed with the name and e-mail git knows you
by, or *OntoBoard* if git has none.

**Converting again gives the same repository.** The same files, apart from dates the files
record; the same tree at every tag; and, for every earlier version that says when it was made
(`dcterms:date`, `issued`, `created` or `modified`, or a dated version), the same commit. A
version that says no date is committed at the time of the conversion, so its commit differs
while its files do not.

**What it leaves to you.**

- Earlier versions are recorded as they were published. They are not rebuilt and their imports
  are not turned into modules.
- An import that cannot be read cannot be declared, and ODK keeps no import its configuration
  does not declare, so it is taken out of the edit file and the warning names it. Once it can be
  read, *OntoBoard > Reuse > Import terms from a list...* brings its terms back.
- The current version is released only when ODK's test passes, since ODK's release runs the same
  test first. When it does not, the project and its history are made all the same, and the
  result says that *OntoBoard > Build and release > Release...* makes the release once the test
  passes.
- ODK names the file it releases after the project's address, `<uribase>/<id>.owl`. Where the
  ontology's own address has another shape, the release carries ODK's name, and a warning says
  so: look at `<id>.owl` before you publish the tag.
- Creating the repository on GitHub and pushing are the two commands step 9 prints.
- Without git, the versions are still written under `releases/`, and step 9 says how to start a
  history from them.

### Projects made by older OntoBoard versions

Up to 1.135 the wizard wrote a project of OntoBoard's own: a short Makefile that called ROBOT
directly, and a configuration nothing read. 1.153.0 does not build or release such a project -
*Build...* and *Release...* say what it is and do nothing, and the first step of the in-app guide
is to carry it over. It is not handed to a make and ROBOT the machine happens to have either: one
more release made the old way would be one more to carry over.

**Since 1.153.0, *OntoBoard > Project > Update project files...* carries it over** to an ODK
project in two steps. First what is the project's own is converted: the configuration becomes one
ODK's update goes by, with `uribase` taken from the edit file's address and the project's
`profile.txt` named as its report profile; each module the edit file imports is declared in it -
one with a recorded source as a module ODK builds again from that source, one from OntoBoard's
pattern library or with no recorded source as kept as it is; and the project's test queries stay
the `.rq` files they are, with the rule that asks them with the imports added at the end of
`<id>.Makefile`. Then ODK's update writes the rest, as for any ODK project: ODK's Makefile,
`run.sh`, ODK's checks and workflows; `.gitignore` and the catalog merged with what the project
has; the edit file's imports brought in line with the configuration, and the file saved in
functional syntax, as ODK keeps it.

What is yours is kept: the ontology's content, the README, the pages under `docs/`, the releases,
the report profile with whatever was relaxed in it, the test queries, the modules and their term
lists. **Write the changes** is off by default, so the first run shows the configuration as it
will become and every file that would change, and writes nothing.

### ODK repositories OntoBoard did not make

*OntoBoard > Project > Open existing ODK project...* opens one, and its own Makefile is what
*Build...*, *Release...* and *Refresh imports...* run - its custom `<id>.Makefile` and its own
`release.sh` included - in the ODK image its Makefile names, where that image is on the machine.
Unless it keeps its releases in a `releases/` folder already, such a repository gets no
`releases/<version>/` copy and no `CHANGES.md`: it is released exactly as its maintainers release
it.

OntoBoard's own engine runs only what ODK v1.6.1 writes. For a repository made by another ODK
version, with no container, it says so; *Update project files...* moves the repository to ODK
v1.6.1 when you ask for it, and refuses one written by a newer ODK than OntoBoard brings.

---

### Step 6: the ODK YAML, key by key

The one step with a hard requirement attached: *the YAML should be completely visible and
editable inside Protégé*. **Visible: yes, every key. Editable: the scalars, not the structures.**

Measured against [`mwo-odk.yaml`](https://github.com/ISE-FIZKarlsruhe/mwo/blob/main/src/ontology/mwo-odk.yaml) — 13 top-level keys, of which 9 are
editable in place and 4 are shown read-only:

| Key | | What it controls |
|---|---|---|
| `id` | ✅ | How files are named and which default term IDs are assumed. Lowercase |
| `title` | ✅ | Seeds the README and other generated defaults |
| `github_org` | ✅ | Basic repository config |
| `repo` | ✅ | Basic repository config |
| `git_main_branch` | ✅ | |
| `uribase` | ✅ | The address ODK names everything under: the ontology `<uribase>/<id>.owl`, the terms `<uribase>/<ID>_0000001`, the releases and modules under `<uribase>/<id>`, or `<uribase>/<uribase_suffix>` where the project has one |
| `primary_release` | ✅ | Which artefact most users interact with, e.g. `full` → `<id>.owl` |
| `remove_owl_nothing` | ✅ | |
| `robot_java_args` | ✅ | RAM ROBOT may consume, e.g. `-Xmx8G` |
| `release_artefacts` | ✅ | Each item, e.g. `release_artefacts[0]` |
| `export_formats` | ✅ | Each item — `owl`, `ttl` |
| `import_group` | ✅ | Every scalar inside it: `import_group.products[2].mirror_from`, `import_group.products[0].module_type`, each `annotation_properties` entry |
| `robot_report` | ✅ | `robot_report.use_labels`, `.fail_on`, `.custom_profile`, `.report_on[0]` |

**Since 1.89.0 every scalar in the file is editable, at any depth.** Measured on that same file:
47 values, 36 of them editable, against 13 values and 9 editable before. So all twelve settings
the ODK tutorial walks you through can be changed in Protégé.

The dialog shows the nesting indented and addresses each value by path, which matters because
`module_type` appears under all four of MWO's imported products and `id` under each as well —
keying an edit by its leaf name would write one product's value into another's.

**Why this was ever shallow, and what is still refused.** It looked like a limit of the
technique and was not. Editing splices a value's own characters out of the original text, and a
scalar's span is exactly its own characters wherever it sits — so a scalar two blocks down is no
more dangerous to replace than a top-level one. The walk simply never went past the top level.

What is still refused is a **structure**, whose span covers everything inside it: replacing
`import_group` in place would delete every product under it. So `import_group` and
`robot_report` are shown read-only while their scalars are editable, which is the honest split.

**Adding or removing a list entry is a step of its own, since 1.97.0.** Changing
`export_formats[1]` from `ttl` to something else is a splice; adding a third format inserts a
line, which is a different operation - **Add to a list...** and **Remove entry** in the same
dialog (see [What this adds up to](#what-this-adds-up-to)). A key containing a `.` or a `[` is
shown and not offered, because `a.b` would then mean two things and an edit could land on a key
other than the one clicked — no ODK configuration has one.

**Why it splices rather than rewrites.** Loading that file with a YAML library and writing it
back rewrites 28 of its 41 lines — normalising a flow sequence, a nested indent, CRLF to LF and a
missing final newline — so changing one word would arrive in a pull request as a whole-file diff.
Measured: editing `title` through this editor changes **one line**, and every comment survives.

**Two guards on the disk.** The file is re-read and compared immediately before saving, so if it
changed underneath — a text editor, a `git pull`, a `make update_repo` — the save is refused
rather than discarding that change. The write goes through a temporary file in the same directory
and an atomic move, so a crash cannot leave a truncated config that breaks every ODK target at
once.

**A file with a duplicate top-level key is refused outright.** YAML accepts one silently and
keeps both, so an edit would patch whichever copy the parser reached while ODK reads the other.

The full schema is ODK's: [incatools.github.io/ontology-development-kit/project-schema](https://incatools.github.io/ontology-development-kit/project-schema/).

**Since 1.153.0 every key is honoured, because ODK honours it.** The project's Makefile,
workflows, catalog and the edit file's imports are what ODK's update writes from this file, and
saving it in an ODK project ends with that update: ODK's own in the project's own ODK image where
that is on the machine, OntoBoard's for a project on ODK v1.6.1, and otherwise the file alone,
with the sentence that the rest follows at the next
*OntoBoard > Project > Update project files...*. A save never moves a project to another ODK version; only *Update project files...*
does that, when asked.

---

## 2. The workspace

| Directory | | What OntoBoard does with it |
|---|---|---|
| `src/ontology/` | ✅ | The edit file, the catalog, both Makefiles, `imports/` and the `-odk.yaml` are all read and acted on — see section 3. *Reuse → Refresh imports* audits every import the project declares since 1.99.0, and since 1.153.0 rebuilds them with ODK's own `refresh-imports` |
| `src/sparql/` | ✅ | ODK's seed writes its full set: its checks (`*-violation.sparql`) and its queries for reports. *Check and test → SPARQL…* runs the checks the configuration lists and your questions (`*.rq`) with the same pass/fail convention `make test` uses - see [the two kinds of tests](#the-two-kinds-of-tests) |
| `src/scripts/` | ✅ | *Project → Run a project script…* lists what is there — honouring a `SCRIPTSDIR` the Makefile moves — and runs the one you pick, with arguments, inside the container. See below |
| `src/metadata/` | 🔶 | Read to check it, never written. The OBO Foundry registry entry: `<id>.yml` is the PURL configuration for `purl.obolibrary.org/config`, `<id>.md` the entry for `OBOFoundry.github.io`. *Check and test → Check project metadata* compares both against the ontology; nothing edits them, because for a project not in the Foundry the file is one ODK's seed writes and the build never reads — [and three of four measured projects are not](limitations.md#roadmap) |
| `src/templates/` | ✅ | ROBOT templates. Opened automatically in the **OntoBoard Sheets** tab — see below, which also covers a knowledge graph's `kg/templates/` — and built by *Spreadsheets → Template…* |

Three of the five are covered, and the one that is not is only needed for an OBO Foundry
submission. **Everything in the first three runs from Protégé** — no terminal, no separate
`sh run.sh`: ROBOT operations run in-process, make targets run as [Who runs ODK's
targets](#who-runs-odks-targets) says - by OntoBoard itself where nothing else is there - your
own scripts run in the container the build uses, and SPARQL checks run against the ontology you
have open.

### The sheet editor — its own tab since 1.117.0

**Window → Tabs → OntoBoard Sheets**, or *Spreadsheets → Sheet editor*, which opens it for
you. A tab you have never opened is not in the tab strip until something adds it, which is what
that menu item is for.

It opens the templates of whatever you have open, with one grid per file. Where it looks, in
this order, taking the first folder that holds a `.tsv` or `.csv` (since 1.134.0):

1. `templates/` beside the open file - or, for an ODK edit file in `src/ontology/`, the
   project's `src/templates/` instead;
2. the project's `src/templates/`;
3. `kg/templates/`, for a knowledge graph kept beside the ontology;
4. `templates/` at the project root.

The project is the nearest folder above the file - at most four levels up - that holds `.git` or
`src/ontology/`, so a project nested in another repository never picks up its host's templates,
and nothing searches subfolders. The toolbar says which folder is open and how many sheets it
has (*Folder: kg/templates (9 sheets)*); hover it to see where it looked first and which other
folders hold sheets. **Project templates ▾** lists every place it looks (three when two of them are
the same folder) with what each holds, so a different one can be chosen -
including one that does not exist yet, which then becomes the folder *New sheet...* writes into.
Opening the view creates nothing. Switching the active ontology to another file of the same
project changes nothing; switching to another project opens its templates, unless that would
replace unsaved edits or a folder you chose yourself, in which case the status line says where
the new one's templates are and waits.

What is there that a spreadsheet program is not:

- **The names come from the ontology.** A column that names another term completes from every
  open sheet *and* from the ontology, so a reference to something that exists is one keystroke
  and a reference to something that does not is visible immediately.
- **The mistakes are marked where the typing happens.** Ten kinds — ambiguous names, duplicate
  identifiers, near-duplicate labels, dead references, pasted whitespace, curly quotes, missing
  identifiers, placeholders, quoted references, columns the template ignores. On the MatWerk
  sheets that is 1,227 marked cells on a graph that builds with zero problems, which is the
  argument for the feature: ROBOT is happy and the data is not.
- **The ones with an obvious correction can be fixed in one undoable step.** 307 were applied to
  the MatWerk sheets at once, and the built graph was byte-identical afterwards.
- **A reference to something that does not exist can be made to exist.** Right-click it: the
  sheet, the type and the identifier are all worked out from the sheets’ own evidence, and it
  refuses rather than guessing when they do not say.
- **The header rows are editable.** Row 1 is your column name and row 2 is ROBOT's template
  string - the line that decides what the column *does*. They are two editable rows where the
  column header used to be, with the spreadsheet column letter above them. Every edit is checked
  against robot-core's own `validateTemplateString`, because a template string ROBOT does not
  recognise is not a bad column: it is a `ColumnException` and **nothing at all** is built from
  the sheet. A cell ROBOT would refuse is marked red; one it accepts and then quietly does
  nothing with - `II`, a `>` on a class column, an axiom annotation that follows nothing - is
  marked amber. *Add column* adds one with its template cell empty, which is ROBOT's own way of
  saying "ignore this column".
- **Identifiers are minted, not typed.** *Generate IRIs...* fills every row that has content but
  no identifier, and offers six ways to form one, each previewed with a real example before
  anything is written: a block from the project's `-idranges.owl`; continuing the sheet's own
  numbering; a timestamp; a timestamp and who is editing; a random UUID; a UUID derived from the
  label; or the label itself as a readable identifier. The one offered first is the one that
  matches what the sheet already holds.
- **An existing Excel file can be brought in.** *Import Excel…* reads an `.xlsx` and writes each
  tab into the folder as a `.tsv`. It does not write `.xlsx` back: reading that format is four
  of its parts and writing it is all of them, and `.tsv` is what ROBOT reads and what git can
  merge.
- **Nothing is written until you press Save.** These files are in git and somebody else is
  editing them.

### Running your own scripts

**Since 1.81.0** - *Project → Run a project script…* lists what is in `src/scripts/` and runs
the one you pick.

The directory is `src/scripts` unless the Makefile declares `SCRIPTSDIR`, which is read rather
than assumed - a project that moved the directory would otherwise be told it has no scripts.

**Where it runs.** The container first, then a native ODK environment, then this machine. That
order is not a fallback chain with the best option last: measured on a real ODK project, all
three of its scripts need something that exists only inside `obolibrary/odkfull` -
`run-command.sh` calls `/usr/bin/time`, `update_repo.sh` calls `/tools/odk.py`, and
`validate_id_ranges.sc` is an Ammonite script needing `amm`. Offering to run those on your
laptop would be offering a failure. The mount, working directory and heap settings are the same
ones a build gets, because they come from the same code.

**What runs it** comes from the script's `#!` line, or from its extension when it has none
(`.sh`, `.bash`, `.py`, `.pl`, `.rb`, `.sc`). A script with neither is listed but not offered -
guessing at an interpreter would produce a confident wrong answer.

**You see the command before it runs.** This is the one place in OntoBoard that runs code
somebody else wrote and OntoBoard has not inspected, so the exact argv, the route and any
warning are on screen, and nothing starts until you agree to that particular command.

**Windows line endings are called out,** because the failure they cause names the wrong thing: a
shell in a Linux container reading a script whose first line ends `\r` reports *"cannot execute:
required file not found"*, which reads as a missing interpreter. Every script in the project this
was built against has CRLF endings, usually from git's `core.autocrlf`.

**Arguments, since 1.85.0.** The chooser has an argument line, split the way a shell splits
words - `"my file.owl"` is one argument, not two. An unclosed quote is refused rather than
guessed at, because the command you approve has to be the command that runs. The confirmation
lists each argument in brackets, which is what tells you your quoting did what you meant.

Nothing is handed to a shell: the arguments become separate elements of the process command, so
a semicolon or a backtick in one is passed to the script as that character and cannot start a
second command. The single exception is the native-ODK route, which really does compose a shell
line, and every argument is quoted there.

**Stopping one, since 1.85.0.** The progress dialog's *Stop* button kills a running script
outright - and a running build, and a running git command. Before this it dismissed the dialog
and left the work going, so stopping a forty-minute ODK build freed the window and nothing else.
An in-process ROBOT operation still cannot be interrupted; the dialog distinguishes the two.
Since 1.153.0 a build in ODK's container stops with it: killing the `docker run` or
`podman run` that started a container leaves the container running, so OntoBoard names each
container it starts and removes it by that name when the run is stopped or its time is up.

**Limits.** A script gets 15 minutes rather than a build's 45 - a backstop for a runaway nobody
is watching, since the button needs somebody in front of it. Arguments are not remembered
between runs.

Also runnable, and worth knowing about:

- **Make targets** - *OntoBoard > Build and release > Build...* lists the targets from
  `src/ontology/Makefile`, following its `include` lines into `<id>.Makefile`, so your custom
  targets appear in the chooser where ODK's container or ODK's tools run them; OntoBoard itself
  offers the targets of ODK's own it can run ([Who runs ODK's targets](#who-runs-odks-targets)).
  Note that ODK's Makefile sets `SHELL = $(SCRIPTSDIR)/run-command.sh`, so in the container
  every recipe line of the build runs through a script in this directory.
- **ROBOT operations** - everything OntoBoard does with ROBOT, under *Reuse*, *Terms*,
  *Spreadsheets* and *Check and test*, in-process, on the ontology you have open.
- **SPARQL** - the checks your configuration lists and your questions in `src/sparql`, each
  asked as `make test` asks it.

---

## 3. `src/ontology`, file by file

### `<ontology>-odk.yaml` 🔶

**Shown and editable since 1.80.0** — *OntoBoard > Project > Project configuration...* lists
every key with its value and its line number. Every scalar is editable at any depth since 1.89.0,
and list entries are added and removed since 1.97.0 ([key by key above](#step-6-the-odk-yaml-key-by-key)).

A structure as a whole - a block such as `import_group` or `robot_report`, a `|` block of text -
is not replaced in place, deliberately: the editor replaces exactly the characters a value
occupies, and a structure's span covers everything inside it, so an in-place edit would delete
the contents.

Edits are **spliced, not re-serialised**. Loading a real config and writing it back rewrites 28
of its 41 lines — normalising a flow sequence, a nested indent, CRLF to LF and a missing final
newline — so a one-word change would land in a pull request as a whole-file diff. Measured on a
real project, changing `title` through this editor changes **one line**, and every comment
survives.

The file is re-read and compared immediately before saving: if it changed underneath — a text
editor, a `git pull`, a `make update_repo` — the save is refused rather than discarding that
change. The write goes through a temporary file in the same directory and an atomic move, so a
crash cannot leave a truncated config that breaks every ODK target at once.

**Since 1.153.0 the configuration is what the project is made from, as in ODK.** ODK's update
writes the Makefile, the workflows, the catalog and the edit file's import declarations from it,
and every change OntoBoard makes to it ends with that update - a save in *Project
configuration...*, a product *Import terms from a list...* declares: ODK's own update in the
project's own ODK image where that is on the machine, OntoBoard's for a project on ODK v1.6.1,
and otherwise the file alone, with a sentence saying that the rest follows at the next
*OntoBoard > Project > Update project files...*. So everything the file says is acted on - by
ODK, which OntoBoard runs.

**What OntoBoard itself reads from it**, besides running ODK:

| Key | What it changes |
|---|---|
| `robot_java_args` | The memory ODK's container gives ROBOT, as `ROBOT_JAVA_ARGS` and `JAVA_OPTS`, since 1.91.0. Without it, nine tenths of the memory Docker or Podman reports, as ODK's `run.sh` computes it. OntoBoard's own engine runs in Protégé's memory instead - see [limitations](limitations.md) |
| `robot_report.fail_on` | Which severity *Check and test → Quality report…* treats as a failure, since 1.91.0 |
| `robot_report.use_labels` | Whether that report shows labels or IRIs, since 1.91.0 |
| `robot_report.use_base_iris`, `namespaces`, `uribase` | What that report is about. As in ODK, only the project's own terms - the `namespaces` listed, or else `<uribase>/<ID>_` and `<uribase>/<id>` - unless `use_base_iris` is `false`. Since 1.132.0 |
| `import_group.products` | Which imports *Refresh imports...* audits and rebuilds, since 1.99.0; *Import terms from a list...* adds to it, since 1.100.0 |
| `robot_report.custom_sparql_checks` | Which of ODK's checks *SPARQL...* runs as the project's checks, since 1.153.0 |

### `make update_repo` and *Update project files...* ✅

ODK's `sh run.sh make update_repo` re-reads `<ontology>-odk.yaml` and brings the project's files
in line with it: the `Makefile`, `run.sh`, the checks and the workflows ODK ships are written
again, what the configuration now asks for is added, `.gitignore` and the catalog are merged with
what the project has, and the edit file's import declarations are made to match the configured
imports. The files a person writes are not touched.

**Since 1.153.0, *OntoBoard > Project > Update project files...* is that update.** ODK v1.6.1's own,
in its container, where Docker or Podman answers and that image is on the machine; otherwise
OntoBoard makes the same files itself, with ODK's templates and ODK's rules for each file. It works
on any ODK repository up to ODK v1.6.1 and moves it to that version; one written by a newer ODK is
refused, since an update would move it back. A project made by OntoBoard 1.135 or before is
[carried over](#projects-made-by-older-ontoboard-versions) by the same item.

**It shows what would change first.** *Write the changes* is off by default: the result lists
the files the update writes and which of them would change, and says who would write them. With
it on, you are asked to save unsaved changes first, and the ontology is read again afterwards,
because the update may rewrite the edit file - in functional syntax, as ODK keeps it. Commit first, and the
update is a change of its own that git can show and take back.

**Imports the update would take out are asked about first.** ODK's update gives the edit file
the imports the configuration declares, and no others. For each import it would remove, you
choose: keep what the ontology uses of it, as a module of those terms; keep all of it, as the
whole ontology; or take the import out. Nothing is written if you cancel. An import read from a
file of this machine can be kept only as it is, since there is no address ODK could fetch it
from again - and the path is written into no file of the project. A module on disk that nothing
imports is named and left alone.

Three of ODK's own ways differ, on purpose, when OntoBoard makes the update itself: the edit
file is rewritten on the side first, so an import that cannot be read stops the update before
anything is written rather than halfway; a file that differs only in its line endings - a Windows
checkout - is left alone; and an edit file that says what it said before is left as it was.

One more thing to know: a project just made shows two small changes at its first update - the
last line of `.gitignore` and the first two of the catalog. That is ODK v1.6.1's own update
writing its own seed's files its own way; after it, the update finds nothing to change.

### `Makefile` and `<ontology>.Makefile` 🔶

The `Makefile` is ODK's: ODK's seed writes it, and ODK's update writes it again from the
configuration. `<id>.Makefile` is the project's own: ODK's Makefile includes it last, and ODK's
update never writes it. OntoBoard knows the difference when *listing* targets — it reads the
`Makefile` and follows its `include` into your custom one, so your targets appear in *Build…*
where make runs them.

OntoBoard writes into `<id>.Makefile` one thing only, since 1.153.0: the rule that asks the
project's questions, between two marker lines, when the project gets its first question or is
carried over from 1.135. A new project's `<id>.Makefile` is ODK's seed, to the byte.

It does not help you *edit* the custom Makefile. An edit to ODK's `Makefile` is noticed by
OntoBoard's own engine, which then runs nothing of it - the Makefile is no longer the one ODK
writes for the configuration - and says that *Update project files...* writes it again, which
also throws the edit away. A rule of your own belongs in `<id>.Makefile`, where ODK's container
and ODK's tools run it and OntoBoard's engine names it and leaves the target to them.

### `<ontology>-idranges.owl` ✅

*Project → ID ranges…* tabulates who holds which block, and allocates a new block to an editor.

ODK's seed writes two placeholder ranges, `ONTOLOGY-CREATOR` and `ADDITIONAL EDITOR`. Since
1.153.0, the first time you add a term in a project whose ranges are all placeholders, OntoBoard
asks which one is yours and writes your name into it - ODK's own instruction. A second editor
then gets a block of their own from *ID ranges...*. Protégé's own ID range plugin asks the same
question in its own dialog whenever the file is read again while your name is in no range - see
[limitations](limitations.md).

**One thing to know.** Allocation does not insert a range — it rewrites the file from a
template, so indentation is normalised. Up to 1.76.0 that also *lost* anything the template did
not model: measured on a real repository, allocating a range would silently drop the
`Datatype: rdf:PlainLiteral` declared after the template's closing `Datatype: xsd:integer`.

Since 1.77.0 such declarations are carried through, and anything that still would not survive
stops the write: OntoBoard names the lines that would go and leaves the file untouched, rather
than subtracting from it. Formatting is still normalised, so the diff is a little larger than one
range.

### `profile.txt` ✅ (by location, not by configuration)

*Check and test → Quality report…* pre-fills its **Report profile** field with the `profile.txt`
sitting beside the open ontology, and falls back to ROBOT's bundled profile when you clear it.

It finds that file by filesystem convention. **Since 1.91.0 it also reads
`robot_report.fail_on` and `robot_report.use_labels` from the project's YAML**, so the report
agrees with the project's own build about which findings count as a failure — which is what
taking the profile from the project was always meant to achieve, and did not.

In a project OntoBoard makes, that file is ODK's own: the configuration says
`robot_report.custom_profile: TRUE`, so ODK's seed writes ROBOT's standard profile into
`src/ontology/profile.txt`, and ODK's report (`--profile profile.txt`) and this dialog read the
same file - relax a rule there and both see it. The dialog itself still finds the file by looking
rather than by being told, so a project whose Makefile points `ROBOT_PROFILE` somewhere else is
honoured by ODK's build and not by this dialog.

> **Not the same thing:** *Check and test → Profile…* is OWL 2 EL/QL/RL/DL validation — ODK's
> `validate_profile`, not `robot report --profile`. The names collide; the operations do not.

### `<ontology>-edit.owl` ✅

*Project → Open existing ODK project…* takes a folder and opens the single `*-edit.owl` under
`src/ontology`, refusing rather than guessing when there is none or more than one.

Your edits are saved by Protégé's own pipeline, so the serialisation and layout stay Protégé's
business. Since 1.153.0 the file is also rewritten on disk by ODK's update - *Update project
files...*, and the update that follows a change of the configuration - which brings its import
declarations in line with the configuration and writes it in functional syntax, as ODK keeps it;
Protégé then reads it again. OntoBoard writes one sidecar beside it,
`<id>-edit.owl.ontoboard.json`, holding your board: which terms are on it and where. That file is
yours to commit or ignore.

### `catalog-v001.xml` 🔶

Read by *Reuse → Check imports*, which shows what each import maps to and why an unresolved one
failed. In an ODK project it is ODK's: since 1.153.0 the entry for a module
*Reuse → Import terms from a list…* saves is written by ODK's update, which merges it with what the catalog already
has. Only where that update cannot run now does *Import terms from a list...* add the `<uri>`
entry itself, so the import resolves until the next *Update project files...*.

**Since 1.83.0, *Reuse → Imports graph…* draws the same structure as a picture** — the whole
closure rather than the direct imports, with unresolved ones dashed and red, and a *Copy as DOT*
button for your own layout engine. It is the only place that will tell you a module is imported
by more than one other, which is the structure `module_type: mirror` produces and which a list
of direct imports cannot show. Worth saying plainly: on a project whose imports are flat it adds
nothing the table does not already say — measured on a real ODK repository, the graph is a star
of five nodes.

**Resolution itself is Protégé's, not OntoBoard's.** The plugin installs no catalog-backed IRI
mapper; imports resolve because Protégé's own loader handles the catalog. In practice this works —
but if an import resolves differently from how `robot` resolves it, that is the reason.

---

## 4. Adding a custom import

ODK's own walkthrough is six steps. Since 1.153.0 OntoBoard does five of them for you, in one
operation, the way ODK does them.

| ODK's step | | In OntoBoard |
|---|---|---|
| **1.** Declare it under `import_group` in the YAML | ✅ | *Reuse → Import terms from a list…* writes the product itself, since 1.100.0. *Project configuration…* also edits every scalar in the block and adds or removes list entries |
| **2.** Run `update_repo`, so the Makefile picks it up | ✅ | Since 1.153.0 the declaration ends with ODK's update, which writes the import's rules into the Makefile - section 3, `<ontology>-odk.yaml`, says who runs it. *Build and release → Build…* lists the targets, following `include` into `<ontology>.Makefile` |
| **3.** Add term IRIs to `<import>_terms.txt`, then `make refresh-<id>` | ✅ | *Find terms…* or *Import terms from a list…* writes the list from the terms you picked, and adds to it when the import already exists; then ODK's own `refresh-<id>` builds the module from it, since 1.153.0. *Imported terms…* adds and takes out terms by label and rebuilds the module at once; *Reuse → Refresh imports…* runs ODK's own refresh targets for all imports or one |
| **4.** Copy the import URI into `<ontology>-edit.owl` and `catalog-v001.xml` | ✅ | ODK's update writes both, since 1.153.0, so the import resolves offline; where the update cannot run now, *Import terms…* writes them itself. *Pattern library…* feeds the same operation |
| **5.** Copy the generated import schema into `<ontology>.Makefile` | ❌ | By hand. Only needed for a `custom` module whose rule you write, which is the case ODK's own generated rule refuses to build too |
| **6.** Set `module_type`, then `update_repo`, `clean`, `make` | ✅ | *Import terms…* writes the `module_type` it actually used. Changing it later is a scalar edit in *Project configuration…*, whose save runs ODK's update; `clean` and `refresh-imports` are targets in *Build…* and *Refresh imports…* |

**What is declared how.** An extraction with BOT, TOP or STAR becomes `module_type: slme`, which
ODK builds again from its source whenever the imports are refreshed. Three kinds are declared
as kept as they are - `module_type: custom` with `mirror_type: no_mirror`, a module ODK's
refresh leaves alone and that importing again is what changes: a MIREOT extraction, for which ODK
has no module type; a pattern from OntoBoard's library; and terms taken from a file on this
machine, which is an address on no other machine - no path goes into the configuration.
Where a step cannot run, the module is OntoBoard's own extraction for now, wired up by hand, and
the result says that *OntoBoard > Reuse > Refresh imports...* has ODK build it.

**An OBO id is enough; anything else needs its source — and OntoBoard writes it the same way.**
ODK downloads `http://purl.obolibrary.org/obo/<id>.owl` when a product gives no `mirror_from`, so
a product for an OBO ontology is one line. When *Import terms…* declares what it built it omits
`mirror_from` for exactly that case and writes it for every other, which is why a declaration it
produces looks like one a person would have written:

```yaml
products:
  - id: iao            # an OBO ontology: the PURL is the default
    module_type: slme
    module_type_slme: BOT
  - id: edam           # anything else has to say where it came from
    mirror_from: https://edamontology.org/EDAM_1.25.owl
    module_type: slme
    module_type_slme: STAR
```

**BOT, TOP, STAR — and not SUBSET.** The [extraction methods](https://robot.obolibrary.org/extract)
ODK documents for `module_type_slme` are BOT, TOP and STAR, and OntoBoard runs all three plus
MIREOT. `SUBSET` is the one real projects use that it cannot: ROBOT reaches that through a
different operation, so *Refresh imports* names it rather than falling back to BOT, which would
quietly produce a module that is not the one in the repository. NFDIcore and PMDCO declare three
SUBSET products between them. That is OntoBoard's own rebuild, which a project of the older
layout uses; in an ODK project *Refresh imports...* runs ODK's own targets since 1.153.0, and the
module is built by ODK's rule for it.

### The pattern library — new in 1.82.0, four collections since 1.87.0, yours since 1.92.0

*Reuse → Pattern library…* browses **163** ontology design patterns shipped inside the plugin,
any you have added yourself, and imports terms from one.

**The BFO four, new in 1.96.0.** Extracted from the BFO that NFDIcore and MWO actually use —
which took some care, because the obvious URL is the wrong one. `purl.obolibrary.org/obo/bfo.owl`
resolves to `bfo_classes_only.owl`, which declares **zero** object properties: patterns extracted
from it would have had no relations in them at all, which is exactly what makes the 123 ODP
patterns useless to a BFO project. The artifact used is the one NFDIcore's own
`nfdicore-odk.yaml` names — `mirror_from: http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl`
— pinned by its immutable raw tag URL and checked by sha256 at extraction time. MWO 3.0.2 uses
the identical set of 66 BFO terms and declares no BFO product of its own, so its BFO is
NFDIcore's by construction.

Measured: NFDIcore uses 66 distinct BFO terms. The previous 159 patterns between them touched
**37** of those; the four new ones touch **53**, of which **20** were covered by nothing before.

| Collection | Patterns | Documented at |
|---|---|---|
| `bfo` | 4 | extracted in 1.96.0 from [BFO 2020 ‘notime’](http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl), the exact artifact NFDIcore pins and MWO agrees with |
| `odp` | 123 | the [ODP portal](https://ontologydesignpatterns.org/), harvested earlier |
| `nfdi` | 15 | [NFDI MatWerk](https://nfdi.fiz-karlsruhe.de/matwerk/patterns/) |
| `pmdco` | 13 | [PMDco](https://materialdigital.github.io/core-ontology/docs/patterns.html) |
| `mwo` | 8 | [MWO](https://ise-fizkarlsruhe.github.io/mwo/docs/patterns/) |

The 36 BFO-family patterns were added in 1.87.0 because the ODP collection and the OBO world
share no vocabulary at all — measured, **zero** of the 123 ODP patterns share a single IRI with a
real BFO-based project. For somebody working in MWO or PMDco the original library was 123
patterns about other people's upper ontology.

**Every pattern is an OWL file, and a Turtle file beside it.** Those three pages document their
patterns as prose and diagrams, so each one here was built by extracting its terms from the
ontology it belongs to — which makes it importable like any other, rather than something to read
and copy by hand. `pattern.owl` is what the plugin loads; `pattern.ttl` is the same content for
a human and for a diff.

**Not a logical module.** ROBOT's STAR and BOT extraction are right for an import and wrong for
a pattern: measured on these sources, a PMDco pattern documented with eight terms came out with
119 classes and 1,508 axioms, because a BFO-based ontology connects everything to the
upper-ontology spine and a logical module must follow it. All 36 came to 13 MB. So each pattern
is the *induced* sub-ontology instead — its own terms, every axiom that mentions only those
terms, their labels and definitions, and one level of parent above each so you can see where it
sits without importing BFO. 6 to 26 classes each, 1.5 MB in total.

**Separated by publisher as well as collection.** Within the ODP collection the portal is a
catalogue of submissions published by twelve different places — `ontologydesignpatterns.org`
has 84, the eCareAtHome smart-home set has 9, Poznań has 6. Both groupings are offered, with
category and a search box as the other ways in.

> **Corrected in 1.92.0.** Two of those ways in were not actually offered. *By collection* and
> *Suggested for this ontology* were both written in 1.87.0, both tested, both described here —
> and neither was in the dialog's dropdown, which still listed the three orderings that existed
> before them. The recommender below could not be selected by anybody for five releases. A test
> now reads the orderings off `PatternOrder` and fails if one of them is not offered.

### Suggestions for the ontology you have open — new in 1.87.0

162 patterns is too many to browse when you want one, so the browser's first ordering is
**Suggested for this ontology**: the patterns whose vocabulary your ontology already speaks,
best first, each with the reason.

The reason is the point. A ranked list with no evidence is a magic box, and somebody deciding
whether to adopt a stranger's pattern needs something checkable — *"3 of its 4 terms are already
in your ontology: Agent, Role, hasRole"* is, and `0.75` is not. Where your ontology has a label
for a matched term, its own label is used, because `BFO_0000004` tells nobody anything and
"independent continuant" does.

**Two signals, both chosen by measuring rather than reasoning.** A pattern counts as relevant
when your ontology uses the same IRIs, or has terms going by the same names; an exact IRI counts
for double, since sharing a name is good evidence and not the same thing. Scored by how much of
the *pattern* you already have, not by how many terms matched — ranking by match count put a
195-term pattern first on the strength of twenty-five generic words like "object" and "entity".

Two things the measurements overturned:

- **The builtin vocabulary is not evidence.** The first version looked like it worked: 43 of 123
  patterns "shared an IRI" with a real project. Every one of those matches was `owl:Thing`, which
  42 patterns declare. A signal that fires on everything ranks nothing.
- **It cannot read the pattern files.** Parsing all of them to answer one question took 24
  seconds, measured — a dialog that looks like it has hung. Each pattern's terms are stored in
  the index instead, which is generated from the files and checked against them by a test, so
  the stored list cannot quietly disagree. 24 seconds became 0.3.

**Importing a pattern is importing terms.** Choosing one hands it to
*Reuse → Import terms from a list…*, which is the ODK path: it extracts a module, writes
`<import>_terms.txt`, saves the module, adds the import statement and adds the catalog entry.
Nothing here re-implements those five steps.

That is also why what arrives is smaller than the pattern file. **101 of the 123 patterns
declare `owl:imports`** — 76 of them to the ODP annotation schema, four to DUL, and many to
sibling patterns. Copying a pattern file into a project would drag an upper ontology in behind
it. Extracting a module takes the terms you chose and the axioms that give them meaning, and
the details pane says so for any pattern that imports anything.

The pattern file is copied to `src/ontology/mirror/<id>.owl` first, because ROBOT needs a file
to read and a resource inside the plugin jar has no file path. `mirror/` is where ODK keeps the
upstream copies a build extracts from; ODK's `.gitignore` keeps it out of git, and since the
module is declared as kept as it is, ODK's refresh does not cut it again.

**The counts shown are read from the file, never from the metadata beside it.** That metadata
states a class count for every pattern and is wrong for 41 of the 123 — `affordance` claims two
classes and has eight, `eventprocessing` claims eight and has seventeen, `vesselspecies` claims
zero and has five — and a property count wrong for 17 more. A chooser that states a pattern's
size is making a claim, so the claim is computed when the pattern is opened.

**Ten of the 123 are another one under a second name** and say which: `agentrole` is
`agent-role`, `partof` is `part-of`, `collection`, `collectionentity` and `collection-entity`
are one pattern with three directories. Importing two of them would import the same terms twice.
So the library lists 123 entries and holds 113 patterns, which is worth knowing before counting
anything.

#### An imported pattern arrives as a group — new in 1.95.0

Choosing a pattern hands its terms to *Import terms…*, which writes the term list, the module, the
import and the catalog entry. **Since 1.95.0 the canvas also shows them together**: the terms are
laid out as one block inside a frame labelled with the pattern's name, placed clear of whatever is
already on the board.

Before this they went wherever an unpositioned node goes — a row along the top, mixed in with
everything else, with nothing to say that those eight classes arrived together or what they are.

Three things it deliberately does not do:

- **It does not move terms you have already placed.** A class you have arranged stays where you put
  it, and the status line says how many were left — so a frame holding less than the whole pattern
  is explained rather than puzzling.
- **It does not lay the pattern out cleverly.** A roughly square block, capped at five columns so a
  26-term pattern does not become a frame four thousand pixels wide. The canvas already has six
  layout algorithms on the toolbar; *Arrange* on the frame's contents does a better job than a
  one-off ever would.
- **It is one undo step.** Ctrl+Z puts the board back.

A frame carries its contents when you drag it, so the pattern stays a unit afterwards.

#### Patterns of your own — new in 1.92.0

**A pattern of your own is a file in a folder.** No index, no metadata file, no registration, no
rebuild. Drop `.owl`, `.ttl`, `.rdf`, `.owx`, `.omn` or `.ofn` files into

```
~/.ontoboard/patterns/<collection>/
```

and they are in the library the next time you open it, searchable, groupable, suggestible and
importable exactly like the 159 that ship. **A sub-folder is a collection**, so your group's
patterns sit together under its name beside `odp`, `mwo`, `pmdco` and `nfdi`.

*Your patterns…* in the dialog does both halves of this: it copies a file in for you, and it
shows and changes the folder. **Point the folder inside an ODK project** — `src/patterns`, say —
and the patterns travel with the repository, so everybody who clones it has them.

**Nothing is asked for that the file already says.** Measured across the 159 shipped patterns,
77 carry `coversRequirements`, 73 `hasIntent`, 62 `rdfs:comment` and 36 `rdfs:label` on the
ontology itself, so the name, the description, the competency questions and the publisher are
read out of the file rather than typed into a dialog. A file that documents nothing falls back to
its own filename and the host of its IRI — which is all but two of them for the publisher, since
only 2 declare one.

> **Those four numbers were wrong when first published, in 1.92.0, as 112/66/65/38.** They came
> from a regular expression matching tag names inside the `<Ontology>` element — and in OWL/XML,
> which every shipped pattern is, that element *is* the whole document, so an annotation on any
> class was counted as an annotation on the ontology. Re-measured in 1.93.0 with an XML parser
> reading only the ontology node's direct children. Two of the 159 files are not well-formed XML
> and parse in neither count, so the denominator is 157.

**Why this needs no index when the shipped library does.** A jar cannot be listed — under Felix a
classpath directory answers a `bundle:` URL that enumerates nothing, which is why
`patterns/index.tsv` exists at all. A directory on disk can be listed. The asymmetry is in the
container, not in the patterns.

**Nothing is skipped in silence.** A file that will not parse is named, with the parser's reason,
behind a *problems* button in the dialog — and so is the count of files left unread when a folder
holds more than 200, which is as many as this reads because each one has to be parsed. A library
quietly holding fewer patterns than the folder does cannot be trusted about the ones it holds.

**Your own ids never shadow a shipped pattern's.** A file called `componency.ttl` becomes
`componency-2`, because three things key on the id — lookup, the parse cache, and the filename the
import copies the pattern out as.

**Limits.** The library knows nothing about what you have already imported, so it will not warn
you that you imported this pattern last week; and note that a suggestion scoring 1.00 means your
ontology already contains all of that pattern, which is a statement that you have applied it
rather than advice to adopt it. A contributed pattern gets no category or domain, because nothing
can infer one — it is grouped by its folder and its publisher instead.

### Editing an import's terms — *Imported terms…*, since 1.132.0

*OntoBoard → Reuse → Imported terms…* (it replaced *Term lists…*)
lists each import with every term it brings, by label: the ones on its term list, the ones that
came along because a listed term needs them, and how often your own axioms use each. **Add terms
from …** opens *Find terms* on the import's own source; **Take out of the import** removes listed
terms; **Rebuild from the source** cuts it again when upstream has released. Each writes the term
list, re-extracts the module by the project's declared method, keeps the IRI the module already
has, and changes the copy Protégé has open — then saves it, so the board and the class tree show
the change at once.

Two things this fixed on the way. Re-importing terms from an ontology that was already imported
replaced its module and term list with the new terms alone; they are now added to it. And
*Refresh imports* gave a rebuilt module the address of its own file as its IRI, so the edit
file's import no longer named it; a module now keeps the IRI it declares.

**Comments survive, because the file is spliced rather than rewritten.** This is not a detail.
OntoBoard has been able to write a term list from scratch since 1.38.0, which is right for a
module it just extracted and wrong for one somebody maintains. Measured on a real
`iao_terms.txt` — 18 lines, 13 terms — reading the terms and writing them back leaves **15 of
those lines gone**: every trailing `# label` comment, both of the file's own headers, and the
order re-sorted. Across that project all 50 terms in its five populated lists carry a trailing
comment and there are 11 standalone comment lines, so there was nothing unusual about the file
that got mangled.

Adding a term keeps all 20 lines of that file and adds exactly one; removing one takes only the
line asked for. Both measured.

**What this gives you that a text editor does not:**

- each OBO number shown with the label your ontology gives it, resolved over the imports
  closure — a term list names terms from *other* ontologies, so the only place they are declared
  is inside an import;
- a line that was meant to be a term and is not, named rather than skipped — skipping means the
  module is quietly missing it;
- a term added from the Protégé selection, with its label written as the trailing comment **if
  the file already annotates every term**. A file whose convention is a label on every line
  should not acquire a bare entry, and a file with none should not acquire the project's first;
- a new entry placed after the last term rather than at the end of the file, since a list
  commonly closes with a comment and an entry under one would read as though that comment
  introduced it;
- the same two disk guards the configuration editor has: the file is re-read and compared before
  writing, so a change made underneath is not discarded, and the write goes through a temporary
  file and an atomic move.

**Not here:** reordering, editing a term in place (remove and add), and editing the comments
themselves.

### Term lists with comments — fixed in 1.77.0

Up to 1.76.0, *Refresh imports…* reported which imports had a term list and then **could not
rebuild any of them** if those lists carried trailing comments — which is the format ODK and
ROBOT both write:

```
http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum
```

Measured on a real repository at the time: five lists, 49 terms, **none** readable. They all read
now, and a line that genuinely is not a term is still reported as malformed rather than skipped
in silence.

### Where an import comes from — fixed in 1.99.0

The same shape of defect, one layer up. *Refresh imports* found its imports by listing
`imports/` and read each one's upstream source from a `# Source:` comment at the top of the term
list — a comment **only OntoBoard ever writes**. Measured across NFDIcore, MWO, PMDCO and ECTO:
39 term lists, **none** carrying it. So the rebuild worked on projects OntoBoard had created and
on no real one, and every import of every real repository reported "does not record where its
terms came from".

The project said so all along, in the place ODK itself looks:

```yaml
import_group:
  products:
    - id: bfo
      mirror_from: http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl
      module_type: mirror
    - id: iao            # no mirror_from: ODK downloads $(OBOBASE)/iao.owl
      module_type: custom
    - id: obi
      module_type: slme
      module_type_slme: BOT
      slme_individuals: exclude
```

All 35 products those four projects declare now have a source. 15 are a kind OntoBoard rebuilds —
`slme` with BOT, TOP, STAR or MIREOT, run with the project's own method and individuals setting
rather than this plugin's default. The other 20 are **named with the reason** instead:

| `module_type` | why not | count |
|---|---|---|
| `custom` | ODK's own generated rule for it prints *"This rule needs to be overwritten in `<project>.Makefile`"* and fails — the real command is hand-written there, and a generic rebuild would overwrite it | 14 |
| `slme` with `SUBSET` | ROBOT reaches that extraction another way; rebuilding with BOT would quietly produce a different module | 3 |
| `mirror` | the module *is* the mirrored ontology — ODK's rule has no term list at all, so there is nothing to extract | 2 |
| `filter` | ODK extracts BOT and then removes the axioms outside the base IRIs; without that removal the module comes out larger than the repository's | 1 |

Reading the declaration also makes two states visible that a directory scan cannot see:

- **Declared and absent** — a product in the YAML with nothing built for it. Exactly where adding
  one to the list leaves you.
- **On disk and undeclared** — a module in `imports/` that no product names. ODK builds its
  `IMPORTS` list from `import_group.products`, so `make refresh-imports` never touches that file
  and nothing regenerates it. Three of the four projects had one, and so did every project
  OntoBoard made, because *Import terms…* did not add to the `products: []` OntoBoard's own
  layout wrote. It now declares the product it builds.

Such a module is still rebuilt from its `# Source:` comment when it has one, because that is what
a refresh did before — reading the declaration must not take a working rebuild away.

**A pattern from OntoBoard's library is kept as it is.** *Pattern library… → Import terms…*
records the pattern's own IRI as the term list's `# Source:` and adds a `# Pattern: <id>` line.
The module is cut from the copy the library ships, which the pattern's IRI need not serve, and
that portal is often down — so nothing downloads it. In an ODK project it is declared, since
1.153.0, as `module_type: custom` with `mirror_type: no_mirror` - ODK's update keeps no import
its configuration does not declare, and ODK's refresh leaves such a module alone; a project of the
older layout declares no product for it. *Refresh imports* lists it as `pattern library` and
leaves it alone; to change it, import the pattern again. Until 1.130.0
the source recorded, and the `mirror_from` declared, was the library's working copy: a `file:`
path on the importing machine, committed with the project and resolvable on no other.

---

## 5. Proposing the change

*Share → Pull request…*, since 1.101.0. The last step that used to send you to a browser.

**Show what is open** lists the repository's pull requests — number, state, title, branch and
author — with each URL beside it.

**Open a pull request for this branch** proposes the branch you are on. It asks for a title, a
description, the branch to merge into (empty means the repository's default, which an ODK
project's is not always) and whether to open it as a draft.

**It runs the GitHub CLI rather than storing a token.** A pull request needs an authenticated
GitHub session; `gh` already holds one in your system keyring, put there by `gh auth login` and
scoped by you. OntoBoard keeps no credentials of its own, which is the same reason it runs real
`git` and real ROBOT rather than reimplementing them.

**Nothing is proposed without being shown first.** This is the one operation in OntoBoard that
other people see the moment it happens — it sends mail, it appears on a board, it asks for
somebody's time. So three things happen in order, cheapest first:

1. the branch is checked for being proposable at all — a detached HEAD, the base branch itself,
   or a branch nobody has pushed each get their own message rather than a `gh` error about refs;
2. `gh pr create --dry-run` validates the branch, the base and the remote **without creating
   anything**;
3. the exact command is shown, and nothing runs until you approve that text.

**Without `gh` there is still an answer.** If it is not installed or not logged in, the result
says which of the two and how to fix it — and gives you GitHub's own compare URL for your
branch, which opens the "open a pull request" form with no install and no login. It is the one
place in this plugin where leaving Protégé is the honest recommendation.

**Reviewing one is still the browser's job.** Reading a diff, commenting and merging are a larger
piece of work, and for an ontology a line diff of RDF/XML is not the diff anybody wants —
*Build and release → Compare releases…* is, and it already exists.

---

## 6. Releasing, in either convention

**Since 1.153.0, *OntoBoard > Build and release > Release...* in an ODK project is ODK's
release**: `make prepare_release`, run by whoever runs ODK's targets on this machine ([Who runs
ODK's targets](#who-runs-odks-targets)), or the project's own `src/ontology/release.sh` where it
has one. Around it OntoBoard does what a maintainer does by hand: refuses a version that has been
released, holds the new release against the last one, keeps a copy for a project that keeps
them, and commits and tags - after showing the git commands. Nothing is pushed.

**Before the form.** ODK builds the release from the file on disk, so you are asked to save
first. Files git has never been told about are named, with **Cancel, to commit first** and
**Release anyway**: a release's commit takes changed files with it and leaves new ones out, and
a tag without a new import module or test is a project that breaks for whoever fetches it.

**The form.**

- **Version.** After a release, the next one is proposed - the next patch of a semantic version,
  today's date for a dated one. Before the first release: `1.0.0` in a project OntoBoard made, and
  today's date in any other ODK repository, which is ODK's own default. A date such as
  `2026-11-11` or numbers such as `1.0.0`; it becomes the tag `v<version>`. A version that has a
  tag or a folder under `releases/` already is refused.
- **Version IRI.** How each released file's version IRI is written, shown for `<id>.owl`:

    | Choice | Version IRI of `<id>.owl` |
    |---|---|
    | ODK's | `<ONTBASE>/releases/<version>/<id>.owl` |
    | the version, then the file | `<ONTBASE>/<version>/<id>.owl` |
    | the version alone | `<ONTBASE>/<version>` - one IRI for every file of the release, as OntoBoard 1.135 wrote it |
    | *The pattern typed below* | from `{ontbase}`, `{version}` and `{file}`, for example `{ontbase}/v{version}/{file}` |

    `<ONTBASE>` is the project's `<uribase>/<id>`. The shape the last release has is proposed,
    and ODK's for a first release. A shape other than ODK's reaches ODK's build as
    `ANNOTATE_ONTOLOGY_VERSION=...` on make's command line, as ODK projects' own release scripts
    pass it; nothing is written into the project's Makefile.
- **Refresh the imports first.** On: `prepare_release`, which fetches the imported ontologies
  again and rebuilds the modules first, as ODK's release does; that needs the network. Off:
  `prepare_release_fast`, on the modules as they are. Where OntoBoard itself builds the release
  and cannot build the full one for this project, the box is not there and the form says why.
- **Refuse to release when a published term is gone** - on by default.

With a `release.sh`, OntoBoard sets its line `VERSION=` - and `PRIOR_VERSION=`, shown as
**Previous version**, where it has one - and changes nothing else in it; the version IRI is the
script's. It runs in ODK's container, each `sh run.sh make ...` line of it running make in that
same container. A script with Windows line endings, or without a `VERSION=` line to set, is
refused with the reason.

**The run.** `make clean`, then `make prepare_release VERSION=<version>`. Cleaning first takes
away what an earlier `test` built, so the release makes its own files and carries its own
version. It is not `make -B`, which would also make every import module again: ODK's rule for
a module the project keeps as it is - a pattern, a file of this machine - is an error, and
such a project could not be released. `clean` empties `mirror/`, so a release that refreshes
the imports fetches their sources again. ODK's release runs ODK's `test` first, so a release
whose test fails is not built. Then `make -s show_release_assets` names the files the release is made of,
which ODK writes at the top of the project - by default `<id>.owl` and `<id>.obo`, and the base
and full variants in both. Then, in this order:

1. The new `<id>.owl` is held against the last release - under `releases/` or at its tag. A term
   that is gone rather than marked deprecated is named; with the box on, the release is not
   copied, committed or tagged.
2. In a project OntoBoard made, or one that keeps its releases there already, the files are
   copied to `releases/<version>/`, where ODK's version IRIs point; from the second release on,
   `releases/<version>/CHANGES.md` says what changed.
3. The git commands are shown, and run when you press **Commit and tag**:

    ```
    git add -u -- .
    git add -f -- <id>.owl <id>.obo <id>-base.owl ...
    git add -- releases/<version>
    git commit -m "release(<version>)"
    git tag -a v<version> -m "<id> <version>"
    ```

    `-f` because ODK's `.gitignore` keeps the release files out of git; adding them by name is
    ODK's own documented way to commit them. `-- .` keeps the first command to the project,
    which matters where the project is a folder of a larger repository: without it, tracked
    changes anywhere in that repository would go into the release's commit. Where cleaning
    removed a file of `tmp/`, `mirror/` or `target/` that git tracks - `tmp/README.md`, which
    older ODKs seeded - a `git checkout --` line comes first and puts it back, so that the
    commit does not delete it. Files git has never tracked are not swept in. With
    **Not now**, or without git on the PATH, nothing is committed and the commands are at the
    end of the log to copy; a project that is not a git repository is told so. Pushing is
    yours: `git push --follow-tags`.

Whatever the release rewrote that Protégé has open is read again afterwards.

Stopped while ODK builds, a release ends there. Stopped once ODK has built it - while it is
held against the release before - it writes nothing of its own: no folder under `releases/`,
no commit, no tag, so the version can still be released. A release that fails says what
stopped it as *Build...* says it of the same run.

### An ontology in no project

*Release...* on an ontology that is not in a project releases it where it is, as before: from
the file you have open, it runs the reasoner, writes the inferences down, stamps a version, keeps
a copy under `releases/<version>/` beside the file that will never change again, and refuses if
the quality report finds an error or if a term the last release published has vanished. Your
file is not modified.

**The version is written in one of two conventions, and from 1.117.0 you choose which.** They
are not a formatting preference — they publish different IRIs, and a version IRI cannot be taken
back once anybody has imported it:

| | Dated (OBO) | Semantic (semver) |
|---|---|---|
| version | `2026-10-12` | `1.2.0` |
| `owl:versionIRI` | `<base>/releases/2026-10-12/<id>.owl` | `<base>/1.2.0` |
| kept at | `releases/2026-10-12/<id>.owl` | `releases/1.2.0/<id>.owl` |
| next one | whatever day it is | a decision: patch, minor or major |

The dated form is what OBO publishes and what ODK's Makefile defaults to
(`VERSION = $(TODAY)`). The semantic form numbers releases `MAJOR.MINOR.PATCH` as
[semver.org](https://semver.org/) defines it: a project at `1.2.0` publishes
`owl:versionIRI <base>/1.2.0` and `owl:versionInfo "1.2.0"`.

**The default is read from what the ontology already declares**, so releasing a project you did
not create continues its history instead of starting a second one beside it. A semantic release
also links `owl:priorVersion` to the one before it, so a consumer can walk the chain backwards.

And if the folder keeps a `release.sh` whose `VERSION` disagrees with what you are about to
cut, the result says so before anything is written — two release paths with two versions is how
a project publishes 3.0.2 twice.

---

## 7. Publishing the documentation

*Build and release → Documentation…*, since 1.103.0. Widoco turns an ontology's own annotations into
the HTML page an ODK project publishes; this runs it on the release product and opens the result,
so you can see the page without pushing and waiting for a deploy.

**Widoco is installed, not bundled, and the reason is measured.** It is not on Maven Central at
all, and its only published form is a 40,858,575-byte shaded jar containing **1,533 OWL API
entries and 2,044 Guava entries** — packages this plugin imports from Protégé. A second copy
of those inside the bundle is how an OSGi plugin stops resolving. So OntoBoard finds the jar the
way it finds Konclude, and since 1.135.0 offers to get it when it cannot. *Download…* saves the
release below into a folder you choose and keeps it only if its SHA-256 is the one OntoBoard was
built against; *Copy link* and *Open release page* are for fetching it with your browser, after
which *Choose file…* takes the jar:

```
widoco-1.4.25-jar-with-dependencies_JDK-11.jar   (about 39 MB)
https://github.com/dgarijo/Widoco/releases/download/v1.4.25/
```

**It needs Java 11.** The jar is compiled to class file version 55. Measured on both hosts:

| | bundled Java | can run Widoco |
|---|---|---|
| Protégé 5.6.9 | Temurin 11.0.25 | yes, with its own JVM |
| Protégé 5.5.0 | 1.8.0_121 | no — point the *Run it with* field at a JDK 11 |

The dialog shows which Java it found and what version it reported, so a failure says *your
Protégé's Java is too old* rather than printing a class-file error - and where to get a newer one.

**Downloading from Protégé 5.5.0.** Its Java, 1.8.0_121, predates the certificate authority
GitHub's downloads use and cannot check them. On Windows *Download…* then checks them against
Windows' own certificate store instead, which works (measured); on Linux it tries the system's
Java certificate store where there is one; on macOS it says so, and the browser is the way round.

**The release product, not the edit file.** That is `<id>.owl` at the top of the project, which
ODK's release writes there. An edit file imports its modules rather than containing them, so a page made from it covers the terms you wrote and none you reuse — and the
version IRI *Release…* stamps is not in it yet. Documenting the open ontology is offered as a
preview of unreleased work, with a warning saying exactly that.

**Success is the page existing, not the exit code.** A real run exited 0 while printing
`ERROR … Could not generate changelog`: Widoco could not fetch the previous release to diff
against and quietly left that section out. So the page is checked for directly, and the log
lines worth reading are reported beside it — the two Xerces warnings every run prints are
dropped, because nobody can act on them.

**It writes to a scratch folder,** `build/documentation`, not to `docs/`. That directory is
published, and a generated page landing there would be committed by accident.

---

## What this adds up to

OntoBoard is strongest where ODK needs ROBOT and a reasoner: building, reporting, explaining,
extracting modules, comparing releases, minting identifiers. Up to 1.135 it was weakest wherever
the ODK workflow routes through `<ontology>-odk.yaml`, because most of that file was text it kept
rather than configuration it acted on. Since 1.153.0 the file is ODK's to act on, and OntoBoard
runs ODK: every project is ODK's own, its files written by ODK from the configuration, and its
build, release, imports and update are ODK's targets - run with nothing installed when nothing
else is there.

Since 1.80.0 the YAML is shown and its scalars are editable, and since 1.81.0 the project's own
scripts are listed and runnable — the two gaps that used to head this list. What is left, in the
order it is worth doing:

1. ~~**Adding or removing a list entry in the YAML**~~ — **done in 1.97.0.**
   *Project → Project configuration…* has **Add to a list…** and **Remove entry**: pick a list, type
   the value, and it is appended; select an entry and it goes, taking its continuation lines with it
   if it is a block. Indentation is copied from the list's own last entry rather than computed,
   because ODK files in the wild indent sequences both ways and guessing would reformat the file on
   the first edit. A value YAML would read as a number or a boolean is quoted for you; an ordinary
   word, or an IRI, is not.
2. ~~**Acting on what the YAML says**~~ — **done in 1.153.0.** `robot_java_args` and two
   `robot_report` keys drove something since 1.91.0, and `import_group.products` since 1.99.0,
   when *Reuse → Refresh imports* began to read its imports from the project rather than from
   `imports/` and a `# Source:` comment only OntoBoard ever wrote - across NFDIcore, MWO, PMDCO
   and ECTO, 39 term lists and not one carried that comment. Since 1.153.0 every key counts,
   because the project's Makefile and files are what ODK's update writes from the file, and
   OntoBoard runs that update after every change it makes there: `release_artefacts` and
   `primary_release`, which OntoBoard used to keep and not obey, are what ODK's release builds.
3. **What OntoBoard's own engine does not run.** A rule a project adds in its own
   `<id>.Makefile` - other than OntoBoard's rule for questions - and a program of ODK's image the
   engine has no stand-in for. Such a target is not offered without ODK's container or ODK's tools
   installed, and the reason names the rule and its line, or the program. ODK projects that keep
   their own rules - a hand-written import, a release script - are released and built where make
   runs.
4. **Knowing what you already imported** — the pattern library will happily suggest a pattern
   whose terms are already in an import you wrote last week, and a score of 1.00 means exactly
   that without saying so. Your own patterns can be contributed without a rebuild since 1.92.0,
   which was the item that used to sit here.
