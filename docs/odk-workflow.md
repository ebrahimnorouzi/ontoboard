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
| `src/scripts/` | ❌ | Not scaffolded, and **OntoBoard cannot run a project's own scripts** |

### Running your own scripts

You asked for this specifically, so it deserves a straight answer: **you cannot run an arbitrary
project script from inside Protégé today.** What you *can* run is narrower and worth knowing
exactly:

- **Make targets** — *Project → Build…* lists the targets from `src/ontology/Makefile`, following
  its `include` lines into `<id>.Makefile`, so your custom targets appear in the chooser. Picking
  one runs it, by the best route available: in-process against the embedded ROBOT for a project
  OntoBoard scaffolded, otherwise in the project's own container or a native ODK environment.
- **ROBOT operations** — the whole *ROBOT* menu, in-process, on the ontology you have open.
- **SPARQL** — your committed `src/sparql/*.rq`, with the same pass/fail convention
  `make sparql_test` uses.

So if your script is reachable as a make target, OntoBoard can run it. A bare
`src/scripts/my-thing.sh` is not, and running one is a gap rather than a decision.

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

**To configure the file, open it in a text editor.** Making it visible and editable inside
Protégé is the next piece of work on this part of the plugin.

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
| Declare it under `import_group` in the YAML | ❌ | No editor for the YAML; `import_group` is not read |
| Check the Makefile | 🔶 | *Build…* lists targets; nothing shows the import rules |
| Add term IRIs to `<import>_terms.txt` | ❌ | **No viewer and no editor for term files** |
| `sh run.sh make refresh-imports` | 🔶 | *Project → Refresh imports…* audits, and rebuilds from term lists |
| Copy the import URI into the edit file and catalog | ✅ | *ROBOT → Import terms…* writes both |
| Copy the import schema into `<ontology>.Makefile` | ❌ | By hand |
| Set `module_type: custom`, `update_repo`, `clean`, `make` | 🔶 | Only via the YAML in a text editor, then *Build…* |

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

The three things that would close most of the remaining distance, in the order they are worth
doing:

1. **A YAML editor** — show the file, validate it, write it back. It is the hinge every other gap
   on this page turns on.
2. **A term-list editor** — view and edit `<import>_terms.txt`, then refresh. Plus the comment bug
   above.
3. **Running project scripts** — so `src/scripts/` is reachable without a terminal.
