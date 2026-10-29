# What is supported, and what is not

Every capability that has been asked of OntoBoard, with an honest answer for each. The point of
this page is that it can be disagreed with: each row says *where* the thing is, so a claim that
turns out to be wrong can be found and corrected rather than argued about.

**How to read the status.** **Yes** means it is implemented and covered by tests, and in most
cases measured against a real ontology — NFDIcore, MWO, PMDCO or the 26-sheet MatWerk knowledge
graph. **Partly** means it works within a limit, and the limit is written out. **No** means it
is not there; where it is not there on purpose, the reason is given.

Current release: **1.118.0**. 2,347 tests; both supported hosts (Protégé 5.5.0 on Java 8 and
5.6.9 on Java 11) start the bundle, pass the self-check 9/9 and the self-test 11/11.

---

## 1. The board and the canvas

| Asked for | Status | Where, or what is missing |
|---|---|---|
| A Protégé plugin rather than a Miro board | Yes | The whole plugin. Miro was considered and rejected: the editing has to be in the tool that owns the model. |
| A diagram of the ontology you can arrange | Yes | **OntoBoard** tab → Schema Canvas. Drag, group, frame, align, zoom, search. |
| Drag and drop, interactive editing | Yes | Nodes are moved and connected on the canvas; an edge drawn becomes an axiom. |
| Add the selected entity to the canvas | Yes | Right-click, or the selection bridge keeps canvas and Protégé selection in step. |
| Properties and individuals shown, not only classes | Yes | Object, data and annotation properties, individuals, datatypes. |
| Inferences shown on the canvas | Yes | Inferred edges are drawn differently from asserted ones, and each can be explained. |
| It should look like Miro, not like a 1998 applet | Partly | Legend, prefix colours, alignment guides, pinned tooltips, shortcuts panel, layouts. It is a Swing canvas inside Protégé and will not be mistaken for a web whiteboard. |
| Export the diagram | Yes | PNG, SVG and DOT. |
| Layout saved between sessions | Yes | Per ontology, with a version guard. |

## 2. ODK projects

The steps below are the ODK workflow as it was given to OntoBoard, in order.

| Step | Status | Where, or what is missing |
|---|---|---|
| Create a project without Docker | Yes | **Project → New ODK project…**. Docker is not required by the plugin at all: the generated Makefile calls `robot` directly. Docker is still the easiest way to get the full ODK toolchain, and `seed-via-docker.sh` is not run from here. |
| Open an existing ODK project | Yes | **Project → Open existing ODK project…**, and **Open from GitHub…** clones one. |
| `<ontology>-odk.yaml` visible and editable inside Protégé | Yes | **Project → Project configuration…** reads and writes it; `OdkYaml` preserves what it does not model rather than rewriting the file. |
| `id`, `title`, `github_org`, `repo` | Yes | In that editor, and used by the scaffold. |
| `release_artefacts` / `primary_release` | Partly | `export_formats` is honoured by **Release…**; which *artefacts* (base, full, simple) are built is the project's own Makefile, not this menu. |
| `import_group` — declaring imports | Yes | **Project → Imports**, **Import terms…**, and the YAML editor. |
| Module type BOT / TOP / STAR / custom | Yes | **ROBOT → Import terms…** offers the extraction method and writes the catalog entry. |
| `robot_report` settings, `custom_profile` | Yes | **ROBOT → Quality report…** uses the project's own `profile.txt` when there is one, and says which profile it used. |
| `sh run.sh make …` targets | Partly | **Project → Build…** runs targets through `make` when it is on PATH, and a set of them in-process when it is not. `update_repo` is modelled by **Update project files…**, which regenerates the files OntoBoard generates — it is not ODK's own `update_repo`. |
| `src/scripts/` — run your own scripts | Yes | **Project → Run a project script…**, with arguments and the output captured. |
| `<ontology>-idranges.owl` | Yes | **Project → ID ranges…** shows every allocation and can allocate a block. Used for minting on the canvas and, since 1.117.0, in the sheets. |
| `profile.txt` | Yes | Generated, and read by the quality report. |
| `catalog-v001.xml` | Yes | Generated, honoured by every `robot` call in the generated Makefile, and written to by **Import terms…**. |
| `src/metadata` for OBO Foundry submission | No | Not generated and not checked. |
| A form that returns to itself when a value is rejected | Yes | Every parameter dialog re-opens with what was typed, including the project wizard. |
| `make` not on PATH should not be a dead end | Yes | **Project → Check requirements…** says what is missing and how to get it; several targets run in-process without `make`. |

## 3. ROBOT operations

| Operation | Status | Where |
|---|---|---|
| measure | Yes | **ROBOT → Measure…** — 60 metrics |
| report | Yes | **ROBOT → Quality report…** — 32 rules, project profile honoured |
| validate-profile | Yes | **ROBOT → Profile…** |
| explain | Yes | **ROBOT → Explain…** |
| query / SPARQL | Yes | **ROBOT → SPARQL…** |
| reason | Yes | In **Release…**, and the Protégé reasoners on the canvas |
| relax / reduce / materialize | Yes | **ROBOT → Transform…** |
| export | Yes | **ROBOT → Export terms…** |
| extract | Yes | **ROBOT → Import terms…** |
| rename | Yes | **ROBOT → Rename IRIs…**, previewed before it applies |
| template | Yes | **Knowledge graph → Template…** |
| diff | Yes | **Project → Compare releases…** |
| merge, annotate | Partly | Used inside Release and Build; not exposed as their own menu items. |
| Parameters adjustable, logs visible | Yes | Every operation has a parameter dialog with help on each field, and the result dialog lists what it did. |

## 4. Releases and versioning

| Asked for | Status | Where, or what is missing |
|---|---|---|
| A published version distinct from the edit file | Yes | **Project → Release…** |
| A dated release IRI, OBO style | Yes | `…/releases/2026-10-12/mwo.owl`, with a dated copy kept. |
| **Semantic versioning, as MWO publishes** | **Yes (1.117.0)** | Choose the convention in the dialog. Semantic publishes `…/mwo/3.0.2` with `owl:versionInfo "3.0.2"` — the exact shape MWO's own `release.sh` produces — and links `owl:priorVersion` to the release before it. |
| The convention should be the user's choice | Yes | A *Versioning* field with both. The default is read from what the ontology already declares, so releasing somebody else's project continues their history rather than starting a second one. |
| The build should be able to do both too | Yes | The generated Makefile takes `VERSION` and `VERSION_IRI` as overrides (`make VERSION=3.0.2 VERSION_IRI=… prepare_release`), which is how MWO's `release.sh` drives it. |
| Refuse to publish something broken | Yes | The quality report at error level, and a check that no term the last release published has vanished. |
| Release notes | Yes | `CHANGES.md` written beside the dated copy, generated from the diff. |
| Compare two releases | Partly | **Project → Compare releases…** compares dated or semantic directories under `releases/`. A project that publishes by git tag and PURL without keeping a `releases/` folder — which MWO does — has nothing for it to list. |

## 5. Knowledge graphs, templates and spreadsheets

| Asked for | Status | Where, or what is missing |
|---|---|---|
| Build a graph from ROBOT templates | Yes | **Knowledge graph → Template…**. Measured on the whole 26-sheet MatWerk graph: 8,471 rows, 36,761 axioms, 6,771 individuals, 0 problems, about 7 seconds. |
| Every individual created and visible | Yes | They are ordinary individuals in the ontology, in Protégé's own views. |
| Export per sheet or all together | Yes | A sheet at a time, or merged with the ontology. |
| Open the sheets inside Protégé | Yes | **OntoBoard Sheets** tab, or **Knowledge graph → Sheet editor**. |
| **Edit like a spreadsheet** | Yes | Cells, rows, fill-down, undo, cut-down keyboard behaviour. Row numbers down the side are the sheet's own numbers, which is what every message about these files quotes. |
| Auto-completion | Yes | Columns that name another term complete from every open sheet and from the ontology. |
| Cleaning and suggestions | Yes | Ten kinds of finding, marked in the cell; the ones with an obvious correction can be fixed in a single undoable step. 307 were applied to the MatWerk sheets with the build byte-identical afterwards. |
| Duplicate detection | Yes | Duplicate identifiers, near-duplicate labels, ambiguous names. |
| Create an instance from a name typed into an object-property column | Yes | Right-click the cell. The sheet, the type and the identifier are all decided from the sheets' own evidence, and it refuses rather than guessing when they do not say. |
| **An IRI generator** | **Yes (1.117.0)** | *Generate IRIs...* fills every row that has content but no identifier. Right-click a cell for one row. |
| **More ways to form an identifier** | **Yes (1.118.0)** | Six, each previewed with a real example before anything is written: a block from `-idranges.owl`; continuing the sheet's own numbering; a **timestamp** (epoch milliseconds plus a counter - the scheme 8,406 of the 8,471 live MatWerk identifiers already use); a **timestamp and the editor**; a **random UUID**; a **UUID derived from the label** (version 5 under the same namespace the MatWerk harvester uses, so a row filled in by hand and the same row harvested later agree); and the **label as a readable slug**. |
| **Editing the ROBOT template row** | **Yes (1.118.0)** | Rows 1 and 2 are two editable rows where the column header was. Every edit is checked against robot-core's own `validateTemplateString`: a string ROBOT would refuse is marked, because it costs the whole sheet rather than the column. *Add column* adds one with its template cell empty, which is ROBOT's way of saying "ignore this". |
| Search the data | Yes (1.117.0) | The Find box searches every sheet and wraps. |
| Pop-up menus | Yes | Right-click: fix, create, mint, insert/duplicate/delete a row, find this value everywhere, explain the mark. |
| Add further sheets | Yes | *New sheet…*, with starter columns and identifiers continuing the project's numbering. |
| **Work with an existing Excel file** | **Partly (1.117.0)** | *Import Excel…* reads a real `.xlsx` — shared strings, styles, dates, formulas, rich text — and converts each tab to a `.tsv` in the folder. Verified cell-by-cell against openpyxl on seven workbooks (11,389 cells, 19 tabs, six written by Excel itself). **It does not write `.xlsx`**: edits are saved to the `.tsv` files, which is what ROBOT reads and what git can merge. |
| Templates in the ontology's own directory | Yes | `src/templates` is opened automatically for the project you have open. |
| Provenance: who added what, when | Yes | *Record who* adds the contributor and date columns and stamps rows; on the ontology side **Provenance…** does the same per term. |
| Logs from ROBOT and the reasoner | Yes | Both reported in the result dialog; ELK over the built graph was measured at 3.0s for 34,773 axioms. |

## 6. Patterns

| Asked for | Status | Where |
|---|---|---|
| A library of design patterns | Yes | **Pattern library…** — 162 patterns, in the main menu |
| Patterns from MWO, PMDco, NFDIcore | Yes | Included, each labelled with its source |
| Search and browse | Partly | Search covers name, description and source; a few fields are not searched |
| Recommend patterns for this ontology | Partly | A recommender ranks them; on a large ontology its scores saturate and the ranking becomes less useful |
| Import a pattern's terms | Yes | From the library dialog |
| Contribute your own | Yes | A folder of your own patterns is read alongside the bundled ones |
| DOSDP templates | No | Not read or generated |

## 7. Collaboration and git

| Asked for | Status | Where, or what is missing |
|---|---|---|
| Several people on one ontology, live | Partly | **Collaboration…** — a host starts a session and shares a link; peers see cursors, selections and edits. It carries declarations, `SubClassOf`, class assertions and annotation assertions; equivalence and disjointness are counted and reported rather than carried. |
| See other people's cursors | Yes | Peer cursors and selections on the canvas |
| Warn when two people edit one term | Partly | A peer who changed a term in the last five minutes is shown as editing it. Nothing can *prevent* it — Protégé owns the edit model, and a lock that could not be enforced would be worse than none. |
| git: clone, branch, commit, push, pull | Yes | **Git…** |
| Open a pull request | Yes | **Pull request…**, which also lists what is open |
| Review a pull request | No | The browser's job; an OWL review is **Compare releases…**, not a line diff of RDF/XML |
| Documented so two people can try it locally | Yes | [testing collaboration locally](testing-collaboration-locally.md) |

## 8. Reasoning

| Asked for | Status | Where |
|---|---|---|
| Use Protégé's reasoners | Yes | ELK, HermiT and the rest, with inferences drawn on the canvas |
| Konclude | Yes | **ROBOT → Reason with Konclude…** — classify, realise, consistency |
| **Koncludix `materialize`** | Partly | Offered as *Materialise everything (needs Koncludix)*, and a stock Konclude that rejects it is told apart from a missing binary. **Not verified end to end**: there is no Koncludix binary on the development machine, so the command line and the reader are tested and the round trip is not. |
| Explain an inference | Yes | **ROBOT → Explain…**, and per-edge on the canvas |

## 9. Documentation and the project page

| Asked for | Status | Where |
|---|---|---|
| Generate ontology documentation | Yes | **Project → Documentation…** (Widoco) |
| A documentation site for OntoBoard itself | Yes | MkDocs, published by GitHub Pages |
| A fixed header and a logo on that site | Yes | In the theme overrides |
| A multi-version, multi-language ontology site | No | Widoco can; it is not driven from here |

## 10. Appearance and usability

| Asked for | Status | Notes |
|---|---|---|
| A menu grouping every feature | Yes | One **OntoBoard** menu with Project, ROBOT, Knowledge graph, Notes and the rest |
| KG builder and pattern library in the main menu | Yes | Both are top-level |
| Forms that explain themselves | Yes | Every field has a help line saying what it changes and why |
| Dialogs that do not stretch off the screen | Yes | Tall forms scroll; there is a rendering test for it |
| The sheet editor should have room | Yes (1.117.0) | Its own workspace tab, the whole window |

---

## Not supported, and why

- **Writing `.xlsx`.** Reading a workbook is four parts of the format; writing one Excel will
  open is the whole of it. Imported tabs become `.tsv`, which is what ROBOT reads.
- **An LLM assistant.** Discussed, not built. It would need a key per user, a prompt that does
  not leak the ontology, and a way to show what it is unsure about.
- **SSSOM mappings.** The most-converged gap in the earlier review and still open.
- **DOSDP templates.** Separate from the pattern library and not read.
- **OBO Foundry submission metadata.** Not generated.
- **Reviewing pull requests in Protégé.** See above.

## Known reports not yet re-checked

Found by a review pass and not since re-verified. Listed because a defect nobody wrote down is
one nobody fixes:

- *Import terms…* can overwrite a hand-maintained term list.
- A *Rename IRIs…* that leaves references behind in a sheet that was not open.
- An `AI` column whose prefix cannot be resolved.
- Several patterns collapsing onto one import module.
- The *Getting started* page still describing the removed web application.

## Fixed in 1.118.0

- `JTable` reinstates its own column header when it is added to a scroll pane, so the editable
  header rows laid out at 0 x 0 while the rendered picture still looked plausible. Caught by
  asserting the layout rather than the appearance.
- Column widths were fitted to the data and the heading but never to the template string, so the
  columns whose spec explains the most - `AT ...^^xsd:anyURI` - were the ones whose spec clipped.
- `IriGenerator.fillBlanks` returned 0 both when there was nothing to do and when it could not
  find the ID column. Those are different answers and it now says which.

## Fixed in 1.117.0 while auditing this list

- New rows in a sheet lost their zero padding: a project with seven-digit identifiers got
  `MWO_1226` after `MWO_0001225`. It sorts wrongly, matches no project's `iddigits`, and builds
  perfectly happily, so nothing said so.
- The project wizard invited you to leave *Base IRI* empty "to use the OBO convention", which
  mints every term under a namespace you do not own. The field is now filled in visibly and the
  hint says when to change it.
- A view config with a single view at its root threw while the tab was built;
  Protégé removed the tab and logged nothing. Found by the host smoke, not by 2,276 tests.
