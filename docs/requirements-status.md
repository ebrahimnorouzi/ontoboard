# What is supported, and what is not

Every capability asked of OntoBoard, with where it is. **Yes** is implemented and tested;
**Partly** works within the limit written beside it; **No** is not there.

## 1. The board

| Asked for | Status | Where, or what is missing |
|---|---|---|
| A diagram of the ontology you can arrange, in Protégé | Yes | The **OntoBoard** tab: drag, group, frame, align, zoom, search |
| Interactive editing | Yes | An arrow drawn becomes an axiom |
| Properties, individuals and inferences shown | Yes | Inferred edges drawn apart, each explainable |
| It should look like Miro | Partly | Legend, colours, guides, pinned tooltips, layouts - a Swing board, not a web whiteboard |
| Export the diagram; layout saved | Yes | PNG, SVG, DOT; per ontology |

## 2. ODK projects

| Step | Status | Where, or what is missing |
|---|---|---|
| Create a project without Docker | Yes | **Project → New ODK project…** - ODK v1.6.1's own seed |
| Open an existing project | Yes | **Project → Open existing ODK project…**, **Open from GitHub…** |
| Edit `<ontology>-odk.yaml` in Protégé | Yes | **Project → Project configuration…**, with ODK's own description of each key |
| Declare imports and their module types | Yes | **Reuse → Import terms from a list…**, **Reuse → Refresh imports…** |
| ODK's `make` targets | Yes | **Build and release → Build…** - by OntoBoard itself, in ODK's container or with ODK's tools |
| Run your own scripts | Yes | **Project → Run a project script…** |
| ID ranges | Yes | **Project → ID ranges…** |
| `src/metadata` for OBO Foundry | Partly | ODK writes it; **Check and test → Check project metadata** compares it; nothing edits it |
| `make` not installed | Yes | **OntoBoard → Check requirements…**; OntoBoard runs ODK's targets without it |

## 3. ROBOT operations

| Operation | Status | Where |
|---|---|---|
| measure, report, validate-profile, explain, query | Yes | **Check and test → Measure…**, **Quality report…**, **Profile…**, **Explain…**, **SPARQL…** |
| relax, reduce, materialize | Yes | **Terms → Transform…** |
| export, rename | Yes | **Terms → Export terms…**, **Terms → Rename IRIs…** |
| extract, template, diff | Yes | **Reuse → Import terms from a list…**, **Knowledge graph → Build from sheets…**, **Build and release → Compare releases…** |
| reason, merge, annotate | Partly | Inside Build and Release; not menu items of their own |

## 4. Releases

| Asked for | Status | Where |
|---|---|---|
| A published version apart from the edit file | Yes | **Build and release → Release…** - ODK's `prepare_release`, committed and tagged |
| Dated or semantic versions, the user's choice | Yes | The Release dialog; the default follows what the ontology already declares |
| Refuse to publish something broken | Yes | ODK's test first; a release that drops a published term stops |
| Release notes; compare releases | Yes | `CHANGES.md`; **Build and release → Compare releases…** |

## 5. Knowledge graphs and spreadsheets

| Asked for | Status | Where |
|---|---|---|
| A graph from ROBOT templates | Yes | **Knowledge graph → Build from sheets…** |
| Edit sheets inside Protégé | Yes | **Knowledge graph → Edit sheets**: cells, fill-down, undo, completion, findings marked in the cell |
| Identifiers generated | Yes | *Generate IRIs...* - ID range, numbering, timestamp, UUID, slug |
| Edit the template rows | Yes | Checked against ROBOT's own validation as you type |
| An Excel workbook | Partly | *Import Excel…* reads `.xlsx` into `.tsv`; it does not write `.xlsx` |
| Who added what, when | Yes | *Record who*, and **Record who edits terms…** for terms |

## 6. Patterns

| Asked for | Status | Where |
|---|---|---|
| A design-pattern library, with MWO, PMDco and NFDIcore | Yes | **Reuse → Pattern library…** - 162 patterns |
| Search, suggestions, your own patterns | Partly | Suggestions saturate on a large ontology |
| DOSDP templates | Partly | Built as ODK builds them; nothing writes or edits one |

## 7. Collaboration and git

| Asked for | Status | Where |
|---|---|---|
| Several people live, with cursors | Partly | **Collaboration…** - four kinds of axiom travel |
| Warn when two people edit one term | Partly | An *editing* badge; nothing prevents it |
| git, pull requests | Yes | **Git…**, **Pull request…** |
| Review a pull request | No | The browser's job |

## 8. Reasoning and documentation

| Asked for | Status | Where |
|---|---|---|
| Protégé's reasoners, Konclude | Yes | **Check and test → Reason with Konclude…** |
| Koncludix `materialize` | Partly | Offered; not verified end to end |
| Explain an inference | Yes | **Check and test → Explain…**, and per edge on the board |
| Ontology documentation | Yes | **Build and release → Documentation…** (Widoco); not multi-version |

## Not supported

Writing `.xlsx`; SSSOM mappings; editing DOSDP patterns; editing OBO Foundry metadata; reviewing
pull requests in Protégé. The [assistant](assistant.md) suggests definitions and axioms as cards
you accept; it does not build hierarchies.

## Reported, not yet re-checked

- *Import terms…* can overwrite a hand-maintained term list.
- A *Rename IRIs…* can leave references behind in a sheet that was not open.
- An `AI` column whose prefix cannot be resolved.
- Several patterns collapsing onto one import module.
