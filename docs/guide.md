# Start here: from an idea to a released ontology

OntoBoard runs the steps of the Ontology Development Kit (ODK), ROBOT and eXtreme Design inside
Protégé, from its menus, with nothing else installed. **OntoBoard → Start here: the guide...**
shows the steps below, ticks the ones your project has done and has a button for each. Words are
explained in the [glossary](glossary.md); what needs git or Docker is under
[What works with nothing installed](getting-started.md#what-works-with-nothing-installed).

## Where are you starting?

| You have... | Your first step | In OntoBoard |
|---|---|---|
| Nothing yet | Create a project | *OntoBoard > Project > New ODK project...* |
| An ontology file | Make a project of it: tick **Start from** your file | *OntoBoard > Project > New ODK project...* |
| An ontology file and its earlier versions | Turn it into a project with its history | *OntoBoard > Project > Turn an ontology into an ODK repository...* |
| A project made by OntoBoard 1.135 or before | Carry it over first | *OntoBoard > Project > Update project files...* |
| An ODK project on this computer | Open its edit file, `src/ontology/<id>-edit.owl` | *Project → Open existing ODK project...* |
| A project on GitHub | Clone it and open it | *Project → Open from GitHub...* |
| Terms to add to somebody else's ontology | Fork it, open your fork, get an ID range, add terms, build, propose them | *Project → Open from GitHub...*, *Project → ID ranges...*, *Build...*, *Share → Pull request...* |

To only look at an ontology and draw on it, open the file and go to the **OntoBoard** tab - no
project needed.

## The path

1. Set up a project
2. Say what it is for
3. Start from a design pattern
4. Reuse terms from other ontologies *(optional)*
5. Model it
6. Turn your questions into tests
7. Build and test *(every time)*
8. Release a version
9. See how it evolved
10. Share it

Steps 5 to 8 are then a loop: model, test, release.

### 1. Set up a project

Answer a few questions - a short id, a title, a web address for your terms, a licence. OntoBoard
writes the project ODK itself writes: the edit file `src/ontology/<id>-edit.owl`, its
configuration `<id>-odk.yaml`, ODK's Makefile and checks - and, with git installed, a first
commit. To start from an ontology you have, tick **Start from** your file; with its earlier
versions, *OntoBoard > Project > Turn an ontology into an ODK repository...* makes each one a
release and a git tag.

**Done when** the ontology you have open is `src/ontology/<id>-edit.owl`.

### 2. Say what it is for

Write a few user stories and the *competency questions* the ontology must answer, such as "Which
products are suitable for children?". The guide's **Write the requirements** button creates
`docs/requirements.md` with the sections eXtreme Design asks for.

**Done when** the project has `docs/requirements.md`.

### 3. Start from a design pattern

*OntoBoard → Reuse → Pattern library...*, search with the words of your questions, pick a
pattern, *Import terms...*, *Add them*. Its terms become a module of your project, shown on the
board in a frame named after the pattern.

**Done when** the ontology imports a pattern from the library.

### 4. Reuse terms from other ontologies *(optional)*

*OntoBoard → Reuse → Find terms in another ontology...*: give its web address or a file, or pick
one from the list, **Load**, type part of a name, tick the terms you want and **Import**. Every
label and synonym is searched. In a project the import is made as ODK makes one - declared in the
configuration, with ODK's rules, catalog entry and module. *OntoBoard → Reuse → Imported terms...*
shows what came from where, and adds or takes out terms.

### 5. Model it

Specialise the patterns and add your own classes, relations and examples, on the board or in
Protégé's usual tabs. Give every term a label. On the board: double-click empty space for a class;
select a class and press **Tab** for a subclass or **Enter** for a sibling; right-click for more;
drag from a box's edge to another box to relate them. The **?** on the toolbar has every shortcut.

**Done when** the ontology has classes of its own.

### 6. Turn your questions into tests

Each competency question becomes a SPARQL query in `src/sparql/` that lists what *breaks* the
rule - an empty answer passes. You do not write the SPARQL: the guide's **Write a test** button,
or *Check and test → Write a test from a question...*, offers common rules (every pizza has some
base, everything has a label, ...), lets you pick the terms by label, saves the test and runs it.
Your questions are asked of the ontology with its imports; ODK's own checks of the edit file alone
([the two kinds](odk-workflow.md#the-two-kinds-of-tests)).

**Done when** `src/sparql/` holds a question of your own.

### 7. Build and test *(every time)*

*OntoBoard > Build and release > Build...* → *Check everything - make test*: the reasoner, the
quality report, ODK's checks and your questions, and the OWL profile. A failure names the terms
that break the rule. It is ODK's own `make test`, run by OntoBoard itself or in ODK's container.

### 8. Release a version

*OntoBoard > Build and release > Release...*, choose a version - `1.0.0`. ODK's release writes the
files people use, `<id>.owl` among them; OntoBoard shows the git commands - the project's new
files first, then the release's commit and the tag `v1.0.0` - and runs them when you agree.
Nothing is pushed.

**Done when** the project has a release tag such as `v1.0.0`.

### 9. See how it evolved

*OntoBoard → Build and release → History...* lists every release with what changed since the one
before - "+3 classes, 1 obsoleted" - term by term; **Compare with** compares any two, and
**Open in a new window** opens one.

**Done when** the project has two releases or more.

### 10. Share it

*OntoBoard → Share → Git...* to commit and push, *Pull request...* to propose a change, or
*Collaboration...* to edit the board together, live.

**Done when** the project is a git repository.
