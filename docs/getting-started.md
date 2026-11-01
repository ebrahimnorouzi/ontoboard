# Getting Started

New to ODK, ROBOT or eXtreme Design? After installing, open **OntoBoard → Start here: the
guide...** — or read [the guide](guide.md) — for the steps from an idea to a released ontology.
The [glossary](glossary.md) says what each word on the way means.

!!! tip "Only want the board?"
    Open your `.owl` file in Protégé and go to the **OntoBoard** tab: the whole ontology is on it,
    and you can draw, edit and arrange straight away. A project - below - is only needed for builds,
    tests and versioned releases.

## Protégé plugin

### Install

[:material-download: **Download the latest OntoBoard**](https://github.com/ebrahimnorouzi/ontoboard/releases/latest/download/ontoboard.jar){ .md-button .md-button--primary }
[All releases](https://github.com/ebrahimnorouzi/ontoboard/releases){ .md-button }

That first link always serves the newest build — it is an unversioned copy of the same jar every
release publishes, so the URL never has to be rewritten. If you need a specific version, or want
to read what was verified in it, take the versioned `ontoboard-<version>.jar` from
[the releases page](https://github.com/ebrahimnorouzi/ontoboard/releases); every release carries
its host smoke receipt as its release notes, recording which Protégés it was checked on and what
was *not* checked.

Then:

1. **Remove any older `ontoboard-*.jar`** from Protégé's `plugins/` folder. The bundle is
   `singleton:=true`; two versions side by side stop it resolving and the tab silently never
   appears.
2. Copy the jar into `plugins/`.
3. Restart Protégé, then **Window → Tabs → OntoBoard**.

Protégé **5.6.x** is recommended. It runs on 5.5.0 with fewer ROBOT operations available —
see [Known constraints](limitations.md#known-constraints).

### A first session

**Start a project.** *OntoBoard → Project → New ODK project…* (also under *Tools*). Give it an
ontology ID - a lowercase letter first, then lowercase letters, digits or underscores; it names
the files - a title, and a folder. The **Base IRI** is the web address your ontology and its terms
are named under, and it ends in the ID: OBO's address is filled in, which is right only for an
ontology registered with the OBO Foundry; otherwise use an address on a domain you or your group
control, such as `https://w3id.org/<id>/<id>.owl`. It writes a project folder in ODK's layout -
which OntoBoard builds itself, so nothing else is needed - and opens the edit file, the file you
work in. If your team already uses ODK, open its repository instead (*Open existing ODK
project…*).

**The board shows the whole ontology.** Open the OntoBoard tab and every class, object property,
data property and individual is on the board, laid out as an outline:

- each design pattern the ontology imports from OntoBoard's library — or has merged in, as a
  release does — in a frame named after it, along the top;
- the classes below, as a tree read left to right — a class, then its subclasses stacked in the
  next column — with each class's individuals and the restrictions it is defined with beside it;
- the object and data properties in frames of their own, each as its property hierarchy.

A term made anywhere in Protégé appears next to its superclass as soon as it exists. Right-click
a term and *Hide from board* to take it off; it stays off until *More → Show every term*. A
board saved by an OntoBoard before 1.131.0 keeps the terms chosen then, and says so; *More → Show
every term* switches it over. Above 1,500 terms the board shows the ontology's own first, and
the entity trees on the left drag any other in.

**Focus.** Select a term and it comes forward with everything it is connected to; the rest fades.
On a large board — more than 150 relations — the lines that would cross it in every direction
wait for this: disjointness, domains and ranges appear for the term in focus, and the other
relations are drawn faintly until one of their ends is selected. *Focus* on the toolbar turns
it off. *More → Show on the board* switches object properties, data properties, individuals and
imported terms on and off, and *Arrange → Outline* lays the board out again. An edge between
terms in two frames goes around any frame in between.

**Work on it like a whiteboard.** Right-click a class and *Collapse subclasses* to fold away
everything under it - the class then says how many terms it holds - and *Expand subclasses* to
bring them back. *Frames* on the toolbar lists every frame on the board and jumps to the one you
pick. Select a class and press **Tab** for a new subclass beside it, named in one dialog, as in a
mind map; Ctrl+Tab moves the focus off the board. Double-click empty space for a new class there.

Everything the ontology says is drawn - restrictions, unions, domains and ranges, disjointness,
property chains, SWRL rules - and [What the board draws](canvas.md) lists each, with how it looks.

**Zoom.** The mouse wheel zooms about the pointer; on a Mac trackpad, so does a pinch.

**Grow the diagram.** Right-click a node and *Expand neighbours (1 hop)* to pull in what it
is directly related to. Drag nodes to arrange them, or use the toolbar's layout algorithms.

**Relate two classes.** Right-click a node, *Create relation from this node…*, pick a target
and a property, then choose how the arrow should be read. The default — existential — means
"every A is related to some B", which is usually what an arrow on a diagram means. The
dialog explains each alternative.

**Check where it went.** The new axioms are in Protégé's class hierarchy immediately, and
Protégé's undo reverses them. The canvas layout is saved beside the ontology in
`<ontology>.ontoboard.json`; the ontology file itself is untouched by the diagram.

**Build it.** *OntoBoard → Project → Build...* and pick a target: `test` runs `reason`, `report`,
`sparql_test` and `validate_profile`. For a project OntoBoard created, every target runs inside
Protégé against the embedded ROBOT. When one fails, the result names the rule and the terms that
break it - for example a class created without an `rdfs:label`, which the generated
`src/sparql/check_labels.rq` rejects. The same Makefile also runs from a terminal
(`make test`) wherever `make` and `robot` are installed, which is what the generated CI does.

### What works with nothing installed

Only Protégé is needed - it brings its own Java. No ROBOT, no ODK, no Docker, no `make`:

| Works with nothing installed | Needs something else |
|---|---|
| New ODK project, Build (`all`, `test`, `reason`, `report`, `sparql_test`, `validate_profile`, `clean`), Release with dated or semantic versions | Building a repository created by ODK's own seed (`run.sh` beside the Makefile): Docker, Podman or a native ODK install, because its recipes call tools that exist only in the ODK image |
| Every *ROBOT* menu item, the pattern library, the canvas, sheets, notes and provenance | Git: the `git` command (on macOS it comes with the Command Line Tools, on Windows with Git for Windows), plus the network for Open from GitHub, pull and push |
| | Imports fetched from their URLs, which need the network |
| | WIDOCO and Konclude, which OntoBoard downloads on first use |

*OntoBoard → Run self-test* checks this on your machine: it builds a throwaway project with the
`test` target and lists whether `robot`, `make`, `docker`, `podman` and `odk` are on the PATH -
none of them is used. *OntoBoard → Project → Check requirements...* lists the rest.

## The web application, archived

OntoBoard began as a browser application. It is no longer developed or shipped; its last state
is kept at the tag [`web-app-final`](https://github.com/ebrahimnorouzi/ontoboard/tree/web-app-final).
