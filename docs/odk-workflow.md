# Working with ODK

What the Ontology Development Kit workflow looks like, and exactly which steps OntoBoard does for
you. Every verdict on this page was checked against the code and against a real ODK repository;
where something is partial or missing it says so, because a tool that overstates itself is worse
than one that admits a gap.

**Legend** — ✅ done inside Protégé · 🔶 partly · ❌ you leave Protégé for this · ➖ not applicable

---

## Before anything else: what can I do right now?

**OntoBoard → Project → Check requirements…**

It probes Docker (with `docker info`, not `--version` — the CLI answers while the daemon is
down), Podman, `make`, `sh`, a native `odk` environment and Java, then lists what you can do today
and what to install for the rest. It does *not* probe `git`, although three menu items wrap the
git executable — if *Git…* misbehaves on a machine with no git, that is why.

---

## 1. Creating a project

ODK's own route is `seed-via-docker.sh`. OntoBoard has its own, and **the two produce different
repositories** — this is the single most important thing on this page.

ODK's seven steps, and what each one is inside Protégé:

| # | ODK step | | OntoBoard |
|---|---|---|---|
| 1 | Go to the parent directory | ✅ | *Project → New ODK project…*, **Create in folder → Choose…** |
| 2 | Start Docker | ➖ | Not needed for the wizard. Needed later, to **build** a real ODK repo |
| 3 | `curl -O seed-via-docker.sh`, `chmod +x` | ❌ | OntoBoard never downloads or runs ODK's seeder |
| 4 | `docker pull obolibrary/odkfull` | ➖ | Not needed for the wizard. OntoBoard never pulls — but `docker run` fetches a missing image itself, so the first build of a real ODK repo is slow rather than broken |
| 5 | `./seed-via-docker.sh <ontology>` | 🔶 | The wizard writes 17 files and opens the edit file — **a different repository**, see below |
| 6 | Configure `<id>-odk.yaml` | ✅ | *Project → Project configuration…* since 1.80.0. Every key visible, and since 1.89.0 every scalar editable at any depth — [measured table below](#step-6-the-odk-yaml-key-by-key) |
| 7 | `git init`, create the remote, push | 🔶 | Commit, pull, push, branch, checkout and clone work; `git init` and creating the remote do not |

### What the wizard produces

Ontology ID, title, description, base IRI and licence, validated, then 17 files written with no
Docker and no network:

```
src/ontology/   <id>-edit.owl  <id>-odk.yaml  <id>.Makefile  <id>-idranges.owl
                catalog-v001.xml  profile.txt  Makefile
src/sparql/     check_labels.rq
.github/workflows/  qc.yml  docs.yml
                .gitattributes  .gitignore  mkdocs.yaml  docs/…  README.md
```

The repository is created at `<chosen folder>/<ontology id>`, and creation is refused if that
directory already exists — there is no "seed into the folder I already made".

### The thing to decide before you start

**An OntoBoard-scaffolded project and a real ODK repository cannot adopt each other.**

- *Project → Update project files…* refuses any repository carrying `src/ontology/run.sh` or an
  `ODK_VERSION_MAKEFILE` marker — i.e. every genuine ODK repo. It will not rewrite a Makefile it
  did not write: OntoBoard's generated one is eight targets, ODK's is several hundred lines.
- ODK's own `sh run.sh make update_repo` cannot run on an OntoBoard-scaffolded project, because
  there is no `run.sh` and the YAML is missing most of what ODK's generator needs.

So choose deliberately. Want the full ODK pipeline, imports machinery and OBO release artefacts?
**Seed with ODK, then open the result in OntoBoard** — everything in sections 3 and 4 below still
applies, and the build runs in ODK's own container. Want a small project that builds with nothing
installed, on any platform including Windows? **Use the wizard.**

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
| `uribase` | ✅ | Base IRI. Read but **not used** — OntoBoard recovers the base from the edit file, because `uribase` is lossy |
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

**Adding or removing a list entry is not here.** Changing `export_formats[1]` from `ttl` to
something else is a splice; adding a third format inserts a line, which is a different
operation. A key containing a `.` or a `[` is shown and not offered, because `a.b` would then
mean two things and an edit could land on a key other than the one clicked — no ODK
configuration has one.

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

**Reading and writing is not the same as honouring.** Only `id`, `title`, `description`,
`license` and `robot_version` drive anything in OntoBoard, and only *Update project files…*,
which refuses a real ODK repository. Everything else is text OntoBoard preserves faithfully and
does not act on — what ODK does with it next is ODK's business, by way of `update_repo`.

---

## 2. The workspace

| Directory | | Notes |
|---|---|---|
| `src/ontology/` | 🔶 | Edit file, catalog and Makefiles are read and used; see section 3. `imports/` is listed and audited but cannot currently be *rebuilt* from term lists — see the defect in section 4 |
| `src/sparql/` | 🔶 | Scaffolded with one check, and *ROBOT → SPARQL…* runs the project's committed queries. The scaffold writes one query, not ODK's full set |
| `src/metadata/` | ❌ | Not scaffolded and not read. Needed for an OBO Foundry submission; create it by hand |
| `src/scripts/` | 🔶 | Not scaffolded, but the scripts a project already has are listed and can be run; see below |

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

**Limits.** A script gets 15 minutes rather than a build's 45 - a backstop for a runaway nobody
is watching, since the button needs somebody in front of it. Arguments are not remembered
between runs.

Also runnable, and worth knowing about:

- **Make targets** - *Project → Build…* lists the targets from `src/ontology/Makefile`,
  following its `include` lines into `<id>.Makefile`, so your custom targets appear in the
  chooser. Note that a real ODK Makefile sets `SHELL = $(SCRIPTSDIR)/run-command.sh`, so every
  recipe line of the build already runs through a script in this directory.
- **ROBOT operations** - the whole *ROBOT* menu, in-process, on the ontology you have open.
- **SPARQL** - your committed `src/sparql/*.rq`, with the same pass/fail convention
  `make sparql_test` uses.

---

## 3. `src/ontology`, file by file

### `<ontology>-odk.yaml` 🔶

**Shown and editable since 1.80.0** — *Project → Project configuration…* lists every top-level
key with its value and its line number, and lets you change the scalar ones.

Lists and nested blocks (`release_artefacts`, `import_group`, `robot_report`) are shown but not
editable there, deliberately: the editor replaces exactly the characters a value occupies, and a
structure's span covers everything inside it, so an in-place edit would delete the contents. The
same goes for a `|` block of text.

Edits are **spliced, not re-serialised**. Loading a real config and writing it back rewrites 28
of its 41 lines — normalising a flow sequence, a nested indent, CRLF to LF and a missing final
newline — so a one-word change would land in a pull request as a whole-file diff. Measured on a
real project, changing `title` through this editor changes **one line**, and every comment
survives.

The file is re-read and compared immediately before saving: if it changed underneath — a text
editor, a `git pull`, a `make update_repo` — the save is refused rather than discarding that
change. The write goes through a temporary file in the same directory and an atomic move, so a
crash cannot leave a truncated config that breaks every ODK target at once.

**Reading and writing are not the same as honouring.** Five scalars drive the generated-project
regenerator; the rest — `import_group`, `release_artefacts`, `robot_report`, `uribase` — are
shown and saved but not yet acted on by anything in the plugin.

Five scalars are ever read — `id`, `title`, `description`, `license`, `robot_version` — and only
by *Update project files…*, which refuses to run on a real ODK repository at all. *Open existing
ODK project…* reads `title` and nothing else.

Everything else is ignored, including every key you are most likely to want to change:

> `uribase` · `github_org` · `repo` · `git_main_branch` · `release_artefacts` · `primary_release`
> · `export_formats` · `import_group` (products, `mirror_from`, `module_type`) ·
> `robot_java_args` · `documentation` · the whole `robot_report` block, including
> `custom_profile`, `fail_on` and `use_labels`

Two consequences worth stating plainly. On a real ODK repository such as MWO, exactly **one** key
(`title`) is ever read — the ontology id comes from the edit file's name instead. And five of the
keys OntoBoard's *own* scaffold writes are read by nothing, including `uribase`, which is
decorative: the base IRI is recovered from the edit file, because `uribase` is lossy.

**To add or remove a list entry, still open the file in a text editor** — and for anything
OntoBoard does not act on, remember that changing it here only changes the file. What ODK does
with it next is ODK's business, by way of `update_repo`.

### `Makefile` and `<ontology>.Makefile` 🔶

OntoBoard knows the difference when *listing* targets — it reads the generated `Makefile` and
follows its `include` into your custom one, so your targets appear in *Build…*. It writes the
generated `Makefile` only for projects it scaffolded, and never touches either file on a real ODK
repo.

It does not help you *edit* the custom Makefile, and will not warn you if you edit the generated
one — which ODK will overwrite the next time you run `update_repo`.

### `<ontology>-idranges.owl` ✅

*Project → ID ranges…* tabulates who holds which block, and allocates a new block to an editor.

**One thing to know.** Allocation does not insert a range — it rewrites the file from a
template, so indentation is normalised. Up to 1.76.0 that also *lost* anything the template did
not model: measured on a real repository, allocating a range would silently drop the
`Datatype: rdf:PlainLiteral` declared after the template's closing `Datatype: xsd:integer`.

Since 1.77.0 such declarations are carried through, and anything that still would not survive
stops the write: OntoBoard names the lines that would go and leaves the file untouched, rather
than subtracting from it. Formatting is still normalised, so the diff is a little larger than one
range.

### `profile.txt` ✅ (by location, not by configuration)

*ROBOT → Quality report…* pre-fills its **Report profile** field with the `profile.txt` sitting
beside the open ontology, and falls back to ROBOT's bundled profile when you clear it.

It finds that file by filesystem convention. `robot_report.custom_profile: TRUE` — the key that
*declares* your project ships one — is never read, and neither are `fail_on` or `use_labels`. A
project whose Makefile points `ROBOT_PROFILE` somewhere else will not be honoured.

> **Not the same thing:** *ROBOT → Profile…* is OWL 2 EL/QL/RL/DL validation — ODK's
> `validate_profile`, not `robot report --profile`. The names collide; the operations do not.

### `<ontology>-edit.owl` ✅

*Project → Open existing ODK project…* takes a folder and opens the single `*-edit.owl` under
`src/ontology`, refusing rather than guessing when there is none or more than one.

OntoBoard never writes the edit file. Every save goes through Protégé's own pipeline, so the
serialisation and layout stay Protégé's business. It does write one sidecar beside it,
`<id>-edit.owl.ontoboard.json`, holding your board: which terms are on it and where. That file is
yours to commit or ignore.

### `catalog-v001.xml` 🔶

Read by *Project → Imports…*, which shows what each import maps to and why an unresolved one
failed. Written by *ROBOT → Import terms…*, which adds a `<uri>` entry for each module it saves.

**Since 1.83.0, *Project → Imports graph…* draws the same structure as a picture** — the whole
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

| ODK step | | OntoBoard |
|---|---|---|
| Declare it under `import_group` in the YAML | 🔶 | *Project configuration…* edits every scalar in the block since 1.89.0, but cannot add a new product, and nothing reads `import_group` |
| Check the Makefile | 🔶 | *Build…* lists targets; nothing shows the import rules |
| Add term IRIs to `<import>_terms.txt` | ❌ | **No viewer and no editor for term files** |
| `sh run.sh make refresh-imports` | 🔶 | *Project → Refresh imports…* audits, and rebuilds from term lists |
| Copy the import URI into the edit file and catalog | ✅ | *ROBOT → Import terms…* writes both, and *Pattern library…* feeds it |
| Copy the import schema into `<ontology>.Makefile` | ❌ | By hand |
| Set `module_type: custom`, `update_repo`, `clean`, `make` | 🔶 | Only via the YAML in a text editor, then *Build…* |

### The pattern library — new in 1.82.0, four collections since 1.87.0

*ROBOT → Pattern library…* browses **159** ontology design patterns shipped inside the plugin and
imports terms from one.

| Collection | Patterns | Documented at |
|---|---|---|
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
catalogue of submissions published by thirteen different places — `ontologydesignpatterns.org`
has 81, the eCareAtHome smart-home set has 9, Poznań has 6, and three have no publisher at all
because their only recorded IRI is an absolute path from the machine the harvest ran on. Both
groupings are offered, with category and a search box as the other ways in.

### Suggestions for the ontology you have open — new in 1.87.0

159 patterns is too many to browse when you want one, so the browser's first ordering is
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

**Importing a pattern is importing terms.** Choosing one hands it to *ROBOT → Import terms…*,
which is the ODK path: it extracts a module, writes `<import>_terms.txt`, saves the module,
adds the import statement and adds the catalog entry. Nothing here re-implements those five
steps.

That is also why what arrives is smaller than the pattern file. **101 of the 123 patterns
declare `owl:imports`** — 76 of them to the ODP annotation schema, four to DUL, and many to
sibling patterns. Copying a pattern file into a project would drag an upper ontology in behind
it. Extracting a module takes the terms you chose and the axioms that give them meaning, and
the details pane says so for any pattern that imports anything.

The pattern file is copied to `src/ontology/mirror/<id>.owl` first, because ROBOT needs a file
to read and a resource inside the plugin jar has no file path. `mirror/` is where ODK keeps the
upstream copies a build extracts from, so the next `refresh-imports` finds it.

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

**Limits.** The collections are fixed at build time — there is no way to add your own pattern or
point at a fourth repository from inside Protégé. The library knows nothing about what you have
already imported, so it will not warn you that you imported this pattern last week; and note
that a suggestion scoring 1.00 means your ontology already contains all of that pattern, which
is a statement that you have applied it rather than advice to adopt it.

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

---

## What this adds up to

OntoBoard is strongest where ODK needs ROBOT and a reasoner: building, reporting, explaining,
extracting modules, comparing releases, minting identifiers. It is weakest wherever the ODK
workflow routes through `<ontology>-odk.yaml`, because that file is effectively write-once.

Since 1.80.0 the YAML is shown and its scalars are editable, and since 1.81.0 the project's own
scripts are listed and runnable — the two gaps that used to head this list. What is left, in the
order it is worth doing:

1. **Adding or removing a list entry in the YAML** — since 1.89.0 every scalar is editable at
   any depth, including inside `import_group` and `robot_report`; inserting a new product or a
   release artefact still means a text editor.
2. **A term-list editor** — view and edit `<import>_terms.txt`, then refresh. They read correctly
   now; nothing in Protégé writes one.
3. **Acting on what the YAML says** — the file is read and written faithfully, but only five
   scalars drive anything, so most keys are still just text OntoBoard preserves.
4. **Adding your own pattern** — the library ships four collections and there is no way to
   contribute a fifth, or a single pattern, without rebuilding the plugin.
