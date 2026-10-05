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

| ODK step | | OntoBoard |
|---|---|---|
| Go to the parent directory | ✅ | *Project → New ODK project…*, **Create in folder → Choose…** |
| Start Docker | ➖ | Not needed. The scaffold is written directly, with nothing installed |
| `curl -O seed-via-docker.sh`, `chmod +x` | ❌ | OntoBoard never downloads or runs ODK's seeder |
| `docker pull obolibrary/odkfull` | ➖ | Not needed for OntoBoard's own scaffold |
| `./seed-via-docker.sh <ontology>` | 🔶 | The wizard writes 17 files and opens the edit file |
| Configure `<id>-odk.yaml` | ❌ | **No viewer and no editor.** Use a text editor |
| `git init`, create the remote, push | 🔶 | Commit/pull/push/branch work; `git init` and creating the remote do not |

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

**Limits.** A script gets 15 minutes rather than a build's 45, and there is still no way to
cancel a running one - the timeout is what ends a runaway. Arguments cannot be passed yet: a
script that needs them is better reached as a make target.

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

**So for a list or a nested block, still open the file in a text editor** — and for anything
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

**Resolution itself is Protégé's, not OntoBoard's.** The plugin installs no catalog-backed IRI
mapper; imports resolve because Protégé's own loader handles the catalog. In practice this works —
but if an import resolves differently from how `robot` resolves it, that is the reason.

---

## 4. Adding a custom import

| ODK step | | OntoBoard |
|---|---|---|
| Declare it under `import_group` in the YAML | 🔶 | *Project configuration…* shows the block but will not edit a nested one, and nothing reads `import_group` |
| Check the Makefile | 🔶 | *Build…* lists targets; nothing shows the import rules |
| Add term IRIs to `<import>_terms.txt` | ❌ | **No viewer and no editor for term files** |
| `sh run.sh make refresh-imports` | 🔶 | *Project → Refresh imports…* audits, and rebuilds from term lists |
| Copy the import URI into the edit file and catalog | ✅ | *ROBOT → Import terms…* writes both, and *Pattern library…* feeds it |
| Copy the import schema into `<ontology>.Makefile` | ❌ | By hand |
| Set `module_type: custom`, `update_repo`, `clean`, `make` | 🔶 | Only via the YAML in a text editor, then *Build…* |

### The pattern library — new in 1.82.0

*ROBOT → Pattern library…* browses 123 ontology design patterns shipped inside the plugin and
imports terms from one. They were in the repository before and reachable from nothing.

**Separated by publisher, not by collection.** All 123 came from one harvest of the ODP portal,
so grouping them by where OntoBoard got them would put everything in a single bucket. The portal
is a catalogue of submissions, and the patterns in it were published by thirteen different
places — `ontologydesignpatterns.org` has 81, the eCareAtHome smart-home set has 9, Poznań has
6, and three have no publisher at all because their only recorded IRI is an absolute path from
the machine the harvest ran on. That is the division the browser groups by; category and a
search box are the other two ways in.

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

**Limits.** The collection is fixed at build time — there is no way to add your own pattern or
point at another repository yet. Nothing reads a pattern's competency questions back into the
ontology. And the library knows nothing about what you have already imported, so it will not
warn you that you imported this pattern last week.

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

1. **Editing a list or a nested block in the YAML** — `import_group`, `release_artefacts` and
   `robot_report` are shown but not editable, which is where most of section 4 still routes
   through a text editor.
2. **A term-list editor** — view and edit `<import>_terms.txt`, then refresh. They read correctly
   now; nothing in Protégé writes one.
3. **Acting on what the YAML says** — the file is read and written faithfully, but only five
   scalars drive anything, so most keys are still just text OntoBoard preserves.
4. **Passing arguments to a script, and cancelling a running one** — a script runs bare today,
   and only the 15-minute timeout stops it.
