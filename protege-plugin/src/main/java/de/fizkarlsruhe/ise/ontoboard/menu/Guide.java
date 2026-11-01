package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import de.fizkarlsruhe.ise.ontoboard.pattern.ImportedPatterns;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The path from an idea to a released ontology, as steps, and how far along it a project is.
 *
 * <p>Asked for: "what if the user does not know ODK and ROBOT and eXtreme Design - they would be
 * lost, not knowing where to start or what the steps to a release are. Assist them, in the plugin:
 * steps they can see and click, what each does, why it is important, what comes next." The menus
 * name operations - Build, Release, Refresh imports - and say nothing about the order they come
 * in, which is the part a newcomer does not know.
 *
 * <p>Pure: the steps are data and the progress is read from files and the ontology, so both are
 * tested without Protege. {@link GuideAction} draws them.
 */
public final class Guide {

    private Guide() {
    }

    /** Where somebody is starting from, which changes the first step. */
    public enum Start {
        NEW("A new ontology"),
        FILE("An ontology file I already have"),
        PROJECT("An ODK project on this computer"),
        GITHUB("A project on GitHub");

        private final String label;

        Start(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How a step stands for the project that is open. */
    public enum State {
        DONE, NEXT, TO_DO, OPTIONAL, EVERY_TIME
    }

    /** One step: what it is for, and the menu item that does it. */
    public static final class Step {
        private final String id;
        private final String title;
        private final String what;
        private final String why;
        private final String instead;
        private final String doneWhen;
        private final String action;
        private final String actionLabel;
        private final boolean optional;
        private final boolean everyTime;

        Step(String id, String title, String what, String why, String instead, String doneWhen,
                String action, String actionLabel, boolean optional, boolean everyTime) {
            this.id = id;
            this.title = title;
            this.what = what;
            this.why = why;
            this.instead = instead;
            this.doneWhen = doneWhen;
            this.action = action;
            this.actionLabel = actionLabel;
            this.optional = optional;
            this.everyTime = everyTime;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        /** What the step is, in plain words. */
        public String getWhat() {
            return what;
        }

        /** Why it is worth doing. */
        public String getWhy() {
            return why;
        }

        /** What OntoBoard does here that would otherwise take ODK or ROBOT on a command line. */
        public String getInstead() {
            return instead;
        }

        /** How the guide knows it has been done. */
        public String getDoneWhen() {
            return doneWhen;
        }

        /** The action class to run, or one of the guide's own: requirements, board, test. */
        public String getAction() {
            return action;
        }

        /** What the button that runs it says. */
        public String getActionLabel() {
            return actionLabel;
        }

        public boolean isOptional() {
            return optional;
        }

        /** A step that is never finished, only repeated - Build. */
        public boolean isEveryTime() {
            return everyTime;
        }
    }

    private static final String MENU = "de.fizkarlsruhe.ise.ontoboard.menu.";
    private static final String ODK = "de.fizkarlsruhe.ise.ontoboard.odk.";

    /** The steps, in order, for somebody starting from {@code start}. */
    public static List<Step> steps(Start start) {
        List<Step> steps = new ArrayList<Step>();
        String setUpAction;
        String setUpLabel;
        String setUpWhat;
        switch (start == null ? Start.NEW : start) {
            case FILE:
                setUpAction = ODK + "NewProjectAction";
                setUpLabel = "New ODK project...";
                setUpWhat = "Create a project, open its edit file, and bring your ontology into "
                        + "it with Protege's Refactor > Merge ontologies.... From then on you "
                        + "work in the edit file, and the project keeps everything else beside it.";
                break;
            case PROJECT:
                setUpAction = ODK + "OpenProjectAction";
                setUpLabel = "Open existing ODK project...";
                setUpWhat = "Open the project's edit file - src/ontology/<id>-edit.owl - and "
                        + "OntoBoard finds the rest of the project around it.";
                break;
            case GITHUB:
                setUpAction = MENU + "OpenFromGitHubAction";
                setUpLabel = "Open from GitHub...";
                setUpWhat = "Give the repository's address. OntoBoard clones it and opens its "
                        + "edit file, ready to work on.";
                break;
            default:
                setUpAction = ODK + "NewProjectAction";
                setUpLabel = "New ODK project...";
                setUpWhat = "Answer a few questions - a short id, a title, a web address for your "
                        + "terms, a licence - and OntoBoard writes the project: the edit file you "
                        + "work in, folders for imports and tests, a build, a release folder and a "
                        + "documentation site.";
                break;
        }
        steps.add(new Step("project", "Set up a project", setUpWhat,
                "A project is what makes everything after it repeatable. Anyone who opens it "
                        + "gets the same build, the same tests and the same releases - you next "
                        + "year included.",
                "It is the layout the Ontology Development Kit (ODK) uses, so other ontology "
                        + "developers recognise it - but there is no Docker and no command line "
                        + "to set up. OntoBoard writes it from one dialog.",
                "The ontology you have open is a project's edit file: src/ontology/<id>-edit.owl.",
                setUpAction, setUpLabel, false, false));
        steps.add(new Step("requirements", "Say what it is for",
                "Write a few user stories - who needs the ontology, and for what - and the "
                        + "competency questions it must be able to answer, such as \"Which "
                        + "products are suitable for children?\".",
                "The questions decide what to model and what to leave out, and later they become "
                        + "the tests that tell you the ontology does its job. This is how the "
                        + "eXtreme Design (XD) method starts every ontology.",
                "OntoBoard writes the requirements document into your project with the sections "
                        + "XD asks for, and opens it for you to fill in.",
                "The project has docs/requirements.md (or docs/xd/requirements.md).",
                "requirements", "Write the requirements", false, false));
        steps.add(new Step("pattern", "Start from a design pattern",
                "A design pattern is a small, proven piece of ontology for a problem many "
                        + "people have had: parts and wholes, roles, classifications, prices. "
                        + "Search the library with the words of your competency questions.",
                "A pattern gives you a design others have already tested and terms others "
                        + "already use, instead of a blank page.",
                "The library of patterns ships inside OntoBoard. Importing one extracts just the "
                        + "terms you choose as a module of your project, and the board shows them "
                        + "in a frame named after the pattern.",
                "The ontology imports at least one pattern from the library.",
                MENU + "PatternLibraryAction", "Pattern library...", false, false));
        steps.add(new Step("terms", "Reuse terms from other ontologies",
                "Search another ontology by what its terms are called, tick the ones you need, "
                        + "and import them - with what they depend on.",
                "Terms others already use make your data join up with theirs, and spare you "
                        + "defining what has been defined well before.",
                "This is what ROBOT's extract command does, from a search box: OntoBoard keeps "
                        + "the module, its list of terms and the import in step for you.",
                "The project has at least one imported module. Skip this if you need none.",
                MENU + "FindTermsAction", "Find terms in another ontology...", true, false));
        steps.add(new Step("model", "Model it",
                "Specialise the patterns and add your own classes, relations and examples - on "
                        + "the OntoBoard board, or in Protege's usual tabs. Every term needs a "
                        + "label, and a definition helps the next reader.",
                "Drawing the model makes gaps and odd relations obvious, and the board can be "
                        + "shared live with the rest of your team.",
                "The board keeps its layout beside the ontology, so a diagram never ends up in "
                        + "a release.",
                "The ontology has classes of its own.",
                "board", "Open the board", false, false));
        steps.add(new Step("tests", "Turn your questions into tests",
                "Write each competency question as a SPARQL query in src/sparql that lists what "
                        + "breaks the rule - \"every pizza has a base\" becomes \"the pizzas with "
                        + "no base\". An empty answer passes.",
                "A test catches next month's mistake before a user does, and a set of passing "
                        + "tests is how you know the ontology answers what it was built for.",
                "Build runs every query in src/sparql inside Protege, and names the terms that "
                        + "break a rule.",
                "src/sparql holds a test of your own, besides the label check every project "
                        + "starts with.",
                "tests", "Write a test", false, false));
        steps.add(new Step("build", "Build and test",
                "Reason, check quality, run your tests and check the OWL profile - in one go, "
                        + "after every change worth keeping.",
                "A passing build is how you know a change broke nothing.",
                "This is ODK's make test, run inside Protege in seconds - no ROBOT, no make, no "
                        + "Docker.",
                "Never finished: run it before every release, and after any change you doubt.",
                MENU + "BuildAction", "Build...", false, true));
        steps.add(new Step("release", "Release a version",
                "Give the ontology a version - 1.0.0 - and OntoBoard stamps it in, keeps a copy "
                        + "under releases/, and writes the merged, reasoned files people use.",
                "A released version never changes under the people who depend on it, and its "
                        + "number tells them what kind of change to expect.",
                "This is ODK's release step, with the version scheme explained as you choose "
                        + "it.",
                "releases/ holds at least one version.",
                MENU + "ReleaseAction", "Release...", false, false));
        steps.add(new Step("history", "See how it evolved",
                "Every release side by side: what each holds, what was added, obsoleted or "
                        + "removed, and any of them opened in a window of its own.",
                "Before a release, it shows whether you removed something others use; after "
                        + "one, it is what you tell them changed.",
                "A term-by-term comparison, where ROBOT gives an axiom diff.",
                "The project has two releases or more.",
                MENU + "HistoryAction", "History...", false, false));
        steps.add(new Step("share", "Share it",
                "Commit and push the project, and propose changes for review - or edit the "
                        + "board together, live.",
                "Every change gets a reviewer and a history, and the project gets more than one "
                        + "pair of eyes.",
                "Git from the menu, with no command line to learn.",
                "The project is a git repository.",
                MENU + "GitAction", "Git...", false, false));
        return Collections.unmodifiableList(steps);
    }

    /** What the guide reads about a project to say how far along it is. */
    public static final class Facts {
        boolean inProject;
        boolean hasRequirements;
        int patterns;
        int importModules;
        int ownClasses;
        int ownTests;
        int releases;
        boolean gitRepository;
    }

    /** The facts about the project {@code editFile} belongs to, and {@code ontology}. */
    public static Facts factsOf(File editFile, OWLOntology ontology) {
        Facts facts = new Facts();
        if (ontology != null) {
            facts.patterns = ImportedPatterns.in(ontology).size();
            int own = 0;
            for (org.semanticweb.owlapi.model.OWLClass owlClass
                    : ontology.getClassesInSignature(Imports.EXCLUDED)) {
                if (!owlClass.isBuiltIn() && !ontology.getDeclarationAxioms(owlClass).isEmpty()) {
                    own++;
                }
            }
            facts.ownClasses = own;
        }
        if (editFile == null) {
            return facts;
        }
        File ontologyDirectory = editFile.getParentFile();
        File root = ReleaseAction.projectRootOf(editFile);
        facts.inProject = editFile.getName().endsWith("-edit.owl") && ontologyDirectory != null
                && "ontology".equals(ontologyDirectory.getName());
        // Written, not merely present: the guide's own button writes the template, and ticking
        // the step for an empty table told a newcomer the work was done when it had not begun.
        facts.hasRequirements = hasAQuestion(new File(root, "docs/requirements.md"))
                || hasAQuestion(new File(root, "docs/xd/requirements.md"));
        facts.importModules = ImportModules.modulesIn(root).size();
        File[] sparql = new File(root, "src/sparql").listFiles();
        if (sparql != null) {
            for (File query : sparql) {
                if (query.getName().endsWith(".rq") && !query.getName().equals("check_labels.rq")
                        && !isTheTemplate(query)) {
                    facts.ownTests++;
                }
            }
        }
        facts.releases = CompareReleasesAction.releasesOf(editFile).size();
        facts.gitRepository = new File(root, ".git").exists();
        return facts;
    }

    /** How each step stands: done, the next one to do, to do later, or never finished. */
    public static Map<String, State> progress(List<Step> steps, Facts facts) {
        Map<String, State> states = new LinkedHashMap<String, State>();
        boolean nextGiven = false;
        for (Step step : steps) {
            State state;
            if (step.isEveryTime()) {
                state = State.EVERY_TIME;
            } else if (isDone(step, facts)) {
                state = State.DONE;
            } else if (step.isOptional()) {
                state = State.OPTIONAL;
            } else if (!nextGiven) {
                state = State.NEXT;
                nextGiven = true;
            } else {
                state = State.TO_DO;
            }
            states.put(step.getId(), state);
        }
        return states;
    }

    private static boolean isDone(Step step, Facts facts) {
        switch (step.getId()) {
            case "project":
                return facts.inProject;
            case "requirements":
                return facts.hasRequirements;
            case "pattern":
                return facts.patterns > 0;
            case "terms":
                return facts.importModules > facts.patterns;
            case "model":
                return facts.ownClasses > 0;
            case "tests":
                return facts.ownTests > 0;
            case "release":
                return facts.releases > 0;
            case "history":
                return facts.releases > 1;
            case "share":
                return facts.gitRepository;
            default:
                return false;
        }
    }

    /** The requirements document a project starts with: XD's sections, ready to fill in. */
    public static String requirementsTemplate(String title) {
        return "# Requirements" + (title == null || title.isEmpty() ? "" : " - " + title) + "\n\n"
                + "What this ontology is for, written before it is modelled. Each competency "
                + "question\nbelow becomes a test in `src/sparql/` that Build runs.\n\n"
                + "## User stories\n\n"
                + "| Story | As a... | I want to... | so that... |\n"
                + "|---|---|---|---|\n"
                + "| S1 | | | |\n\n"
                + "## Competency questions\n\n"
                + "The questions the ontology must be able to answer.\n\n"
                + "| CQ | Question | Story | Test |\n"
                + "|---|---|---|---|\n"
                + "| CQ1 | | S1 | `src/sparql/cq1_....rq` |\n\n"
                + "## Contextual statements\n\n"
                + "Facts about the domain the model must respect - \"every order has exactly one "
                + "customer\".\n\n- \n\n"
                + "## Patterns chosen\n\n"
                + "| Pattern | Answers | Specialised as |\n"
                + "|---|---|---|\n"
                + "| | | |\n";
    }

    /**
     * A first test: the shape of one, with what Build does with it said in comments.
     *
     * <p>It passes until it is edited. A starter that failed would fail the build the moment the
     * guide wrote it, before anybody had written a rule into it.
     */
    /** Whether a requirements document has a competency question with words in it. */
    static boolean hasAQuestion(File document) {
        if (!document.isFile()) {
            return false;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(document.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.matches("\\s*\\|\\s*CQ\\d+\\s*\\|\\s*[^|\\s][^|]*\\|.*")) {
                    return true;
                }
            }
        } catch (java.io.IOException unreadable) {
            return false;
        }
        return false;
    }

    /** Whether a test is still the guide's template, its rule not yet written. */
    static boolean isTheTemplate(File query) {
        try {
            return new String(java.nio.file.Files.readAllBytes(query.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).contains(":ReplaceThisWithYourRule");
        } catch (java.io.IOException unreadable) {
            return false;
        }
    }

    public static String testTemplate() {
        return testTemplate("https://example.org/your-ontology#");
    }

    /** The template, with {@code :} standing for the namespace this ontology's terms are in. */
    public static String testTemplate(String namespace) {
        return "# CQ1: <the rule, in one line - Build reports this line when the test fails>\n"
                + "#\n"
                + "# A test lists what BREAKS its rule, so an empty answer passes. This one lists\n"
                + "# nothing until its WHERE clause is replaced. For \"every pizza has a base\":\n"
                + "#\n"
                + "#   ?pizza rdfs:subClassOf+ :Pizza .\n"
                + "#   FILTER NOT EXISTS { ?pizza rdfs:subClassOf* ?named .\n"
                + "#                       ?named rdfs:subClassOf [ owl:onProperty :hasBase ] }\n"
                + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "PREFIX : <" + namespace + ">\n\n"
                + "SELECT ?term WHERE {\n"
                + "  ?term a :ReplaceThisWithYourRule .\n"
                + "}\n";
    }
}
