# Getting Started

New to ODK, ROBOT or eXtreme Design? Open **OntoBoard → Start here: the guide...** - or read
[the guide](guide.md) - for the steps from an idea to a released ontology. The
[glossary](glossary.md) explains the words.

!!! tip "Only want the board?"
    Open your `.owl` file in Protégé and go to the **OntoBoard** tab. A project is only needed for
    builds, tests and releases.

## Protégé plugin

### Install

[:material-download: **Download the latest OntoBoard**](https://github.com/ebrahimnorouzi/ontoboard/releases/latest/download/ontoboard.jar){ .md-button .md-button--primary }
[All releases](https://github.com/ebrahimnorouzi/ontoboard/releases){ .md-button }

1. **Remove any older `ontoboard-*.jar`** from Protégé's `plugins/` folder - two versions side by
   side stop the tab from appearing.
2. Copy the jar into `plugins/`.
3. Restart Protégé, then **Window → Tabs → OntoBoard**.

Protégé **5.6.x** is recommended; 5.5.0 works with a few
[known constraints](limitations.md#known-constraints).

### A first session

**Start a project.** *OntoBoard → Project → New ODK project…* (also under *Tools*). Give it an ID
(lowercase), a title, a folder and a **Base IRI** - an address on a domain you control, such as
`https://w3id.org/<id>/<id>.owl`, unless the ontology is registered with the OBO Foundry. It
writes the project ODK itself writes, makes it a git repository and opens the edit file. To start
from an ontology you have, tick **Start from**. A team's existing ODK repository is opened with
*OntoBoard > Project > Open existing ODK project...*; a project made by OntoBoard 1.135 or before
is carried over with *OntoBoard > Project > Update project files...*.

**The board** shows every class, property and individual: imported design patterns in frames
along the top, the classes as a tree read left to right, the properties in frames of their own.
Select a term to bring its relations forward. Press **Tab** for a new subclass, double-click empty
space for a new class, right-click a term to relate it, collapse it or hide it. The mouse wheel or
a pinch zooms. [What the board draws](canvas.md) lists everything it shows.

**Relate two classes.** Right-click a node, *Relate to another term…*, pick a target and a
property; the dialog explains how the arrow is read. The axioms appear in Protégé at once and
Protégé's undo reverses them. The layout is saved beside the ontology in
`<ontology>.ontoboard.json`; the ontology file is not touched by the diagram.

**Build it.** *OntoBoard > Build and release > Build...*, then *Check everything - make test*:
the reasoner, ODK's checks and your questions, the quality report and the OWL profile. When it
fails, the result names the rule and the terms. See
[Working with ODK](odk-workflow.md#who-runs-odks-targets) for who runs the targets.

### The OntoBoard menu

| | What is in it |
|---|---|
| *Start here: the guide...* | The steps from an idea to a released ontology |
| *Project* | Start or open a project, turn an ontology into one; its configuration, ID ranges and scripts; *Update project files...* |
| *Reuse* | Design patterns; terms from other ontologies; what each import brings |
| *Terms* | Notes and discussion links; obsoleting, renaming and transforming; exporting terms |
| *Knowledge graph* | Sheets of terms or data, a row each: edit, check, build terms or a knowledge graph from them |
| *Check and test* | Tests from your questions, the quality report, explanations, profiles, measures, SPARQL, Konclude, project metadata |
| *Build and release* | Build, release, history, comparing releases, the documentation page |
| *Share* | Git, pull requests, editing live together |
| *Record who edits terms...* | Your ORCID, recorded with the date on each term you add or change |
| *Check requirements...*, *Run self-test* | What this computer can do, and proof the plugin works on it |

### What works with nothing installed

Only Protégé is needed - it brings its own Java.

| Works with nothing installed | Needs something else |
|---|---|
| New ODK project, Turn an ontology into an ODK repository, Update project files, Build, Release, Refresh imports - ODK's own targets, run by OntoBoard itself, and a project's own rules where every line of them can run here | A rule OntoBoard cannot run, a project's own `release.sh`, SSSOM or the `db` export: ODK's container (Docker or Podman) or, on Linux and macOS, ODK's tools |
| Everything under *Reuse*, *Terms*, *Knowledge graph* and *Check and test*, the board, and *Record who edits terms* | Git, for commits, tags, pull and push; the network for imports fetched from the web |
| | Widoco, Konclude and DOSDP-Tools: OntoBoard offers to download each the first time it is needed |

*OntoBoard → Run self-test* checks this on your machine; *OntoBoard → Check requirements...* lists
the rest. Give the version the self-test names when you report a problem.

## The web application, archived

The browser application OntoBoard began as is kept at the tag
[`web-app-final`](https://github.com/ebrahimnorouzi/ontoboard/tree/web-app-final).
