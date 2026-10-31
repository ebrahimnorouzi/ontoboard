# Start here: from an idea to a released ontology

You do not need to know the Ontology Development Kit (ODK), ROBOT or the eXtreme Design method
to build an ontology the way they do. OntoBoard runs their steps inside Protégé, from its menus,
with nothing else installed — no Docker, no command line.

In Protégé, **OntoBoard → Start here: the guide...** shows the steps below, ticks the ones your
project has done, marks the next one, and has a button for each. The empty board offers it too.

## Where are you starting?

| You have... | Your first step | In OntoBoard |
|---|---|---|
| Nothing yet | Create a project | *Project → New ODK project...* |
| An ontology file | Create a project, then bring your file into its edit file | *Project → New ODK project...*, then Protégé's *Refactor → Merge ontologies...* |
| An ODK project on this computer | Open its edit file, `src/ontology/<id>-edit.owl` | *Project → Open existing ODK project...* |
| A project on GitHub | Clone it and open it | *Project → Open from GitHub...* |

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

**What you do.** Answer a few questions — a short id, a title, a web address for your terms, a
licence. OntoBoard writes the project: the edit file you work in, folders for imports and tests,
a build, a release folder and a documentation site.

**Why.** A project makes everything after it repeatable: anyone who opens it gets the same
build, the same tests and the same releases — you next year included.

**Instead of.** ODK's `seed` command, which needs Docker. The layout is ODK's, so other ontology
developers recognise it.

**Done when** the ontology you have open is `src/ontology/<id>-edit.owl`.

### 2. Say what it is for

**What you do.** Write a few user stories — who needs the ontology, and for what — and the
*competency questions* it must answer, such as "Which products are suitable for children?". The
guide's **Write the requirements** button puts `docs/requirements.md` into your project with the
sections eXtreme Design asks for, and opens it.

**Why.** The questions decide what to model and what to leave out. Later they become the tests
that tell you the ontology does its job.

**Done when** the project has `docs/requirements.md` (or `docs/xd/requirements.md`).

### 3. Start from a design pattern

**What you do.** *OntoBoard → Pattern library...*, search with the words of your questions, pick
a pattern, *Import terms...*, OK.

**Why.** A pattern is a small, proven piece of ontology for a problem many people have had —
parts and wholes, roles, classifications, prices. You start from a design others have tested,
not a blank page.

**What OntoBoard does.** It extracts the pattern's terms as a module of your project, records
where it came from, and shows its classes on the board in a frame named after the pattern. *Add
all* frames every pattern an ontology uses, including in a release, where the imports are merged.

**Done when** the ontology imports a pattern from the library.

### 4. Reuse terms from other ontologies *(optional)*

**What you do.** *OntoBoard → Find terms in another ontology...*: give its web address or a file,
**Load**, then type part of a label. Tick the terms you want — the ticks stay while you search for
more — and **Import**. Terms already in your ontology are marked, and obsolete ones are hidden
unless asked for. A term the ontology only refers to, rather than defines, comes after its own
terms and says where it is from — `schema.org/Person` beside a vocabulary's own *Person* — and
the whole address is in its tooltip. If one of the ontology's imports cannot be fetched, the rest
still loads and the missing import is named.

**Why.** Shared terms make your data join up with everyone else's, and spare you defining what
has been defined well before.

**Instead of.** ROBOT's `extract`, which needs the terms' IRIs. OntoBoard keeps the module, its
term list and the import in step.

### 5. Model it

**What you do.** Specialise the patterns and add your own classes, relations and examples — on
the OntoBoard board or in Protégé's usual tabs. Give every term a label; a definition helps the
next reader.

**Why.** Drawing the model makes gaps and odd relations obvious, and the board can be shared live
with your team.

**Done when** the ontology has classes of its own.

### 6. Turn your questions into tests

**What you do.** Write each competency question as a SPARQL query in `src/sparql/` that lists
what *breaks* the rule — "every pizza has a base" becomes "the pizzas with no base". An empty
answer passes. The guide's **Write a test** button starts you off with a test that explains its
own shape and passes until you write your rule into it.

**Why.** A test catches next month's mistake before a user does, and passing tests are how you
know the ontology answers what it was built for.

**Done when** `src/sparql/` holds a test of your own, besides the label check every project
starts with.

### 7. Build and test *(every time)*

**What you do.** *OntoBoard → Project → Build...* → `test`. It reasons, runs the quality report,
runs your tests and checks the OWL profile, and names the terms that break a rule.

**Instead of.** ODK's `make test` — here, inside Protégé, in seconds.

### 8. Release a version

**What you do.** *OntoBoard → Project → Release...*, and choose a version — `1.0.0`. OntoBoard
stamps it in, keeps a copy under `releases/1.0.0/`, and writes the merged, reasoned files people
use.

**Why.** A released version never changes under the people who depend on it, and its number tells
them what kind of change to expect: a patch for a fix, a minor version for new terms, a major one
for anything removed or redefined.

**Instead of.** ODK's `prepare_release`.

**Done when** `releases/` holds a version.

### 9. See how it evolved

**What you do.** *OntoBoard → Project → History...* lists every release, newest first, with your
working copy above them: how many classes, properties and individuals each holds, and what
changed since the one before — "+3 classes, +4 properties, 1 obsoleted". Choose one to see every
change, term by term; compare any two with **Compare with**; open any version in a window of its
own with **Open in a new window**.

**Why.** Before a release, it shows whether you removed a term others use — removed classes are
called out, because obsoleting is the safe way to retire one. After a release, it is what you tell
your users changed.

**Instead of.** ROBOT's `diff`, which lists axioms; History lists terms.

**Done when** the project has two releases or more.

### 10. Share it

**What you do.** *OntoBoard → Git...* to commit and push, *Pull request...* to propose a change
for review — or *Collaboration...* to edit the board together, live.

**Done when** the project is a git repository.

## Why OntoBoard

- **Nothing to install.** No Docker, no command line: the build, the tests and the release run
  inside Protégé.
- **Your questions become tests**, and the build runs them in seconds.
- **Proven patterns, not a blank page**, shown on the board as what they are.
- **Every release versioned, kept and compared**, term by term.
- **One board your team can share**, live.
