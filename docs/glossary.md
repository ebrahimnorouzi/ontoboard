<!--
  Written for: somebody who has used Protégé but not ODK, ROBOT or eXtreme Design, meeting these
  words in the guide, a dialog or a message. One plain sentence each, then where it shows up.
-->

# Glossary

The words OntoBoard's menus, dialogs and docs use, in plain terms.

## The project

**Project, project folder**
:   A folder that holds an ontology with its tests, imports and releases.
    *Project → New ODK project...* makes one. The board works on a single `.owl` file without one.

**Edit file**
:   The file you work in, `src/ontology/<id>-edit.owl`. Releases are made from it.

**ODK (Ontology Development Kit)**
:   A standard layout, build and release for ontology projects, normally run on the command line
    in Docker. OntoBoard does the same steps from Protégé's menus.

**ROBOT**
:   The tool ODK uses to check, convert and extract ontologies. OntoBoard has it built in.

**Build**
:   Checking the ontology: *Build and release → Build...*, then `test` - the reasoner, the quality
    report, your tests and the OWL profile.

**ID range**
:   A block of term numbers kept for one editor, so two people never mint the same identifier.
    *Project → ID ranges...*.

**Project configuration (`<id>-odk.yaml`)**
:   The project's settings file, in ODK's format. *Project → Project configuration...* edits it.

## Releases

**Release**
:   A finished version others can use: *Build and release → Release...* - ODK's release files,
    a git commit and a tag `v<version>`.

**Version**
:   The number of a release. With semantic versions, `1.5.0` after `1.4.0` means terms were added;
    `2.0.0` means something was removed or changed.

**Base IRI**
:   The web address the ontology and its terms are named under, such as
    `https://w3id.org/myont/myont.owl`. Hard to change later.

**Version IRI**
:   The permanent address of one release, such as
    `https://w3id.org/myont/releases/1.4.0/myont.owl`.

**Release file**
:   A file a release writes - `<id>.owl` and the others at the top of an ODK project. It is made
    from the edit file, never edited by hand.

**Release record**
:   What a release records of itself: what built it, its measures, the reasoner and what it said,
    the quality report, what changed. `releases/<version>/RELEASE.md` and the tag's message.

**Obsolete (deprecated)**
:   A retired term that is kept and marked, with what to use instead. Removing a term breaks whoever
    uses it; obsoleting does not. *Obsolete the selected term...*.

## Reusing other ontologies

**Import**
:   Using terms from another ontology: OntoBoard copies the terms you chose, with what they need,
    into an import module.

**Import module**
:   That small file under `src/ontology/imports/`, rebuilt by *Refresh imports...*.

**Term list**
:   The terms a module is made from, one per line, next to the module.

**Imported terms**
:   *OntoBoard → Reuse → Imported terms...*: each import and the terms it brings; add or take out
    terms there.

**Catalog**
:   `catalog-v001.xml`, telling Protégé where each imported file is on disk.

**Mirror**
:   A saved copy of a source ontology, so a module can be rebuilt without downloading it.

**BOT, TOP, STAR, MIREOT**
:   Ways of cutting chosen terms out of a source: with everything above them (BOT, the usual),
    everything below (TOP), the smallest piece that keeps their meaning (STAR), or just their
    parents and labels (MIREOT).

**Design pattern**
:   A small, tried model of something ontologies often need - parts and wholes, roles, prices.
    *Pattern library...*.

## Checking

**Reasoner**
:   The program that works out what follows from what you wrote, and finds contradictions. ELK is
    the default.

**Quality report**
:   Common problems - a term without a label, two definitions - by severity, about your own terms.

**Defined class**
:   A class with an equivalence, so the reasoner finds its members. The board marks one with `≡`.

**OWL profile**
:   A restricted form of OWL (OWL 2 DL, EL...) some reasoners need.

**Competency question**
:   A question the ontology must answer - "Which pizzas have a meat topping?".

**Test (SPARQL test)**
:   A competency question as a query that lists what breaks a rule, under `src/sparql/`. An empty
    answer passes.

**eXtreme Design (XD)**
:   Building an ontology in small steps: questions, a pattern for each, model, test, release.

## Data

**Knowledge graph**
:   The things themselves, described with the ontology's terms in a file that imports it.
    [From spreadsheets to a knowledge graph](knowledge-graph.md)

**ROBOT template**
:   A spreadsheet whose second row says what each column is (`ID`, `TYPE`,
    `AL rdfs:label@en`); every row after it becomes a term or an individual.
    *Knowledge graph → Build from sheets...*.

**Sheet editor**
:   *Knowledge graph → Edit sheets*: a folder's templates, edited inside Protégé.
