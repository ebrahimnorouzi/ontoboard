<!--
  Written for: somebody who has used Protégé but not ODK, ROBOT or eXtreme Design, meeting these
  words in the guide, a dialog or a message. One plain sentence each, then where it shows up.
-->

# Glossary

The words OntoBoard's menus, dialogs and docs use, in plain terms. You do not need any of them to
start - the [guide](guide.md) uses each one only when it gets to it.

## The project

**Project, project folder**
:   A folder that holds an ontology together with everything around it: its tests, the terms it
    imports, its releases. *Project → New ODK project...* makes one. A single `.owl` file works on
    the board without one; builds, tests and releases need it.

**Edit file**
:   The file you work in, `src/ontology/<id>-edit.owl` in a project. Releases are copies made
    from it; you never edit those by hand.

**ODK (Ontology Development Kit)**
:   A standard way of organising an ontology project - the folder layout, the build, the release -
    used by many ontologies. It normally runs on the command line inside Docker. OntoBoard uses the
    same layout and does the same steps from Protégé's menus.

**ROBOT**
:   The tool ODK uses to check, convert and extract parts of ontologies. OntoBoard has it built in,
    so its menu items (under *ROBOT*) run inside Protégé - nothing to install.

**Build**
:   Checking the ontology: *Project → Build...*, and choose `test`. It runs the reasoner, the
    quality report, your tests and the OWL profile check, and lists what is wrong.

**ID range**
:   A block of term numbers kept for one editor, so two people never give two terms the same
    identifier. Needed only in a project whose terms are numbered; *Project → ID ranges...*.

**Project configuration (`<id>-odk.yaml`)**
:   The project's settings file, in ODK's format. *Project → Project configuration...* edits it.

## Releases

**Release**
:   A finished version of the ontology that others can use: *Project → Release...*. It is the edit
    file, reasoned, with its version recorded, kept under `releases/<version>/`.

**Version**
:   The number of a release. With semantic versions, `1.4.0` then `1.5.0` means terms were added;
    `2.0.0` means something others relied on was removed or changed.

**Base IRI**
:   The web address the ontology and its terms are named under, chosen when the project is made -
    for example `https://w3id.org/myont/myont.owl`. Every term's address starts with it, so it is
    hard to change later.

**Version IRI**
:   The permanent web address of one release, so anyone can point at exactly that version. For
    an ontology at `https://w3id.org/myont.owl`, a semantic version is
    `https://w3id.org/myont/1.4.0` and a dated release, as ODK names them,
    `https://w3id.org/myont/releases/2026-11-01/myont.owl`. *Release...* makes the right one
    for the scheme you choose.

**Obsolete (deprecated)**
:   A term that is retired but kept, marked so that users know to stop using it and what to use
    instead. Removing a term breaks whoever still refers to it; making it obsolete does not.
    *Obsolete the selected term...*.

## Reusing other ontologies

**Import**
:   Using terms from another ontology inside yours. OntoBoard copies only the terms you chose,
    with what they need, into a small file of your project - an import module.

**Import module**
:   That small file, under `src/ontology/imports/`. *Refresh imports...* rebuilds it from its
    source when the source changes.

**Term list**
:   The list of terms a module is made from, one per line, next to the module.

**Imported terms**
:   *OntoBoard → Imported terms...*: every import and the terms it brings, by label - the ones you
    chose and the ones that came along because a chosen term needs them. Terms are added and
    taken out there, and the module is rebuilt at once.

**Catalog**
:   A file (`catalog-v001.xml`) telling Protégé where to find each imported file on disk, so it
    does not download them.

**Mirror**
:   A saved copy of a source ontology, so rebuilding a module does not have to download it again.

**BOT, TOP, STAR, MIREOT**
:   Ways of cutting the terms you chose out of a source ontology: with everything above them
    (BOT, the usual choice), everything below them (TOP), the smallest piece that keeps their
    meaning (STAR), or just their parents and labels (MIREOT).

**Design pattern**
:   A small, tried model of something ontologies often need to say - parts and wholes, roles,
    prices. Starting from one gives you its terms and its reasoning. *Pattern library...*.

## Checking

**Reasoner**
:   The program that works out what follows from what you wrote, and finds contradictions. ELK is
    the one used by default; it is fast and built in.

**Quality report**
:   A list of common problems - a term with no label, two definitions for one term - by
    severity. Part of every build. It is about your own terms: a problem in an imported term
    belongs to the ontology it comes from, and the build says how many it left out.

**Defined class**
:   A class with an equivalence - *cheesy pizza ≡ pizza that has a cheese topping* - so the
    reasoner can find its members without anybody saying so. The board marks one with `≡`.

**OWL profile**
:   A restricted form of OWL some reasoners need. The profile check says whether the ontology
    stays within one (OWL 2 DL, EL...).

**Competency question**
:   A question the ontology must be able to answer, written before modelling - "Which pizzas
    have a meat topping?". It is how eXtreme Design starts.

**Test (SPARQL test)**
:   A competency question turned into a query that lists what breaks a rule, saved under
    `src/sparql/`. An empty answer means the test passes. Every build runs them.

**eXtreme Design (XD)**
:   A method for building ontologies in small steps: write the questions, pick a design pattern
    for each, model, test against the questions, release. The guide follows it.

## Data

**Knowledge graph**
:   The things themselves - this pizzeria, that baker - described with the ontology's terms, in a
    file of its own that imports the ontology. [From spreadsheets to a knowledge
    graph](knowledge-graph.md)

**ROBOT template**
:   A spreadsheet whose first row is your headings and whose second row says what each column is
    (`ID`, `TYPE`, `AL rdfs:label@en`, `I <property>`); every row after that becomes a term or an
    individual. *Knowledge graph → Template...*.

**Sheet editor**
:   *Knowledge graph → Sheet editor*: the templates of a folder, open for editing inside Protégé,
    with the problems marked where you type and identifiers generated for new rows.
