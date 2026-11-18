package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkQuestions;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkYaml;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; SPARQL - run a query, or run the checks the project already commits.
 *
 * <p>This closes the gap the roadmap called "the wrong way round". OntoBoard's ODK wizard writes
 * {@code src/sparql/check_labels.rq} and a {@code sparql_test} target that runs it, and until now
 * nothing in the plugin could run the file it had just written: a curator editing that query had to
 * leave Protege and start Docker to find out whether it did anything.
 *
 * <p>Two modes, because they are two different jobs. Typing a query is exploration - "which classes
 * have no definition" - and wants a table. Running the project's checks is the build's own gate,
 * asks nothing, and wants a pass or fail per file, the same verdict {@code make sparql_test} gives.
 *
 * <p>Read-only by construction: {@link SparqlQuery} accepts {@code SELECT} and {@code ASK} and
 * refuses anything that would change the ontology. A curator exploring a query should not be one
 * typo away from an {@code INSERT}.
 */
public class SparqlAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_MODE = "mode";
    private static final String OPTION_QUERY = "query";
    private static final String OPTION_OUTPUT = "output";

    private static final String MODE_QUERY = "Run a query I write";
    private static final String MODE_CHECKS = "Run this project's checks (src/sparql)";

    /** A table nobody can read is not more useful than a count; the check mode is unbounded. */
    private static final int MAX_ROWS = 500;

    private volatile boolean runChecks;
    private volatile String sparql = SparqlQuery.exampleQuery();
    private volatile File output;

    @Override
    protected String operationName() {
        return "SPARQL";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        String checksHelp = help(checksOf(fileOf(getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology())));

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "SPARQL",
                "Runs SPARQL over the ontology you have open, including its imports. Nothing is "
                        + "modified - SELECT and ASK only.",
                Arrays.asList(
                        Parameter.of(OPTION_MODE, "What to run", Parameter.Kind.CHOICE)
                                .choices(MODE_QUERY, MODE_CHECKS)
                                .defaultValue(MODE_QUERY)
                                .required()
                                .help(checksHelp)
                                .build(),
                        Parameter.of(OPTION_QUERY, "Query", Parameter.Kind.MULTILINE)
                                .defaultValue(SparqlQuery.exampleQuery())
                                .help("Used only in the first mode. The example finds every class "
                                        + "with no rdfs:label; it runs as-is, so it is a working "
                                        + "starting point for the prefixes.\n\nCONSTRUCT, DESCRIBE "
                                        + "and the update forms are refused: this panel reads.")
                                .build(),
                        Parameter.of(OPTION_OUTPUT, "Save results to (optional)",
                                Parameter.Kind.FILE)
                                .help("Writes the answer as TSV. This is what ODK's "
                                        + "custom_reports target does - "
                                        + "`robot query -f tsv -s <query>.sparql <report>.tsv` for "
                                        + "each export a project configures - and the file is the "
                                        + "point: something a spreadsheet opens, or a script diffs "
                                        + "between releases.\n\nWhen running the project's "
                                        + "checks, name a DIRECTORY instead: one TSV is written "
                                        + "per check that found violations, which is what "
                                        + "`robot verify --output-dir` produces.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        runChecks = MODE_CHECKS.equals(chosen.get(OPTION_MODE));
        sparql = chosen.get(OPTION_QUERY);
        String path = chosen.get(OPTION_OUTPUT);
        output = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        return runChecks ? runProjectChecks(ontology) : runOneQuery(ontology);
    }

    private OperationResult runOneQuery(OWLOntology ontology) {
        SparqlQuery.Answer answer = SparqlQuery.run(ontology, sparql);
        OperationResult.Builder result = OperationResult.of(operationName());

        if (answer.getColumns().isEmpty()) {
            return result.summary("The query selected no columns.").build();
        }
        result.columns(answer.getColumns().toArray(new String[0]));

        int shown = 0;
        for (List<String> row : answer.getRows()) {
            if (shown >= MAX_ROWS) {
                result.note("Showing the first " + MAX_ROWS + " of " + answer.size() + " rows.");
                break;
            }
            result.row(row.toArray(new String[0]));
            shown++;
        }
        if (output != null) {
            SparqlQuery.writeTsv(answer, output);
            result.wrote(output);
        }
        return result.summary(answer.size() + (answer.size() == 1 ? " row" : " rows")
                + (output == null ? "" : ", written to " + output.getName())).build();
    }

    private OperationResult runProjectChecks(OWLOntology ontology) {
        return runChecks(checksOf(fileOf(ontology)), ontology, output);
    }


    /**
     * The checks "Run this project's checks" runs, and what is said about them: read from the
     * project's files, so it is tested without Protege.
     */
    static final class ProjectChecks {
        private final List<File> files;
        private final List<File> alone;
        private final List<File> questions;
        private final String noneFound;
        private final String emptyHelp;
        private final String foundAs;
        private final String target;
        private final String where;
        private final String notRun;
        private final String missing;

        /** A project of the older layout, or a loose file: every check is asked with the imports. */
        ProjectChecks(List<File> files, String noneFound, String emptyHelp, String foundAs,
                String target, String where, String notRun, String missing) {
            this(files, new ArrayList<File>(), new ArrayList<File>(), noneFound, emptyHelp,
                    foundAs, target, where, notRun, missing);
        }

        /**
         * @param files     everything to run, in the order it runs
         * @param alone     those of them ODK asks of the ontology by itself: its listed checks
         * @param questions those of them that are the project's questions, asked with the imports
         */
        ProjectChecks(List<File> files, List<File> alone, List<File> questions, String noneFound,
                String emptyHelp, String foundAs, String target, String where, String notRun,
                String missing) {
            this.files = files;
            this.alone = alone;
            this.questions = questions;
            this.noneFound = noneFound;
            this.emptyHelp = emptyHelp;
            this.foundAs = foundAs;
            this.target = target;
            this.where = where;
            this.notRun = notRun;
            this.missing = missing;
        }

        /** The checks to run, in the order they run. */
        List<File> files() {
            return files;
        }

        /**
         * In an ODK project, the project's questions among {@link #files}: the .rq files, asked
         * of the ontology with its imports. The others are ODK's checks, asked of the ontology
         * by itself, as ODK's test asks them.
         */
        List<File> questions() {
            return questions;
        }

        /** Whether ODK asks this check of the ontology by itself, without what it imports. */
        boolean isAskedAlone(File check) {
            return alone.contains(check);
        }

        /** Why there is nothing to run, when there is not. */
        String noneFound() {
            return noneFound;
        }

        /** Where the checks come from, and what runs them. */
        String where() {
            return where;
        }

        /** In an ODK project, the queries in src/sparql that are not run and why; else null. */
        String notRun() {
            return notRun;
        }

        /** In an ODK project, the listed checks with no file; else null. */
        String missing() {
            return missing;
        }
    }

    /**
     * The checks of the project {@code editFile} is in. In an ODK project: the ones its
     * configuration lists under {@code robot_report: custom_sparql_checks} whose file is there,
     * in the list's order - what ODK's sparql_test runs, on the edit file without its imports
     * - and then the project's questions, the {@code .rq} files of {@code src/sparql}, which
     * the rule in the project's own makefile asks of the ontology with its imports
     * ({@link OdkQuestions}). In a project of the older layout, or for a loose file, every
     * {@code .rq} file in {@code src/sparql}, as before.
     */
    /** Where a first check comes from, for an ontology that has none. */
    private static final String WRITES_ONE =
            "OntoBoard > Check and test > Write a test from a question... writes one.";

    static ProjectChecks checksOf(File editFile) {
        File root = editFile == null ? null : ReleaseAction.projectRootOf(editFile);
        if (editFile == null || !OdkRelease.isOdkProject(editFile)) {
            return new ProjectChecks(SparqlQuery.checksIn(root),
                    "No .rq files under " + SparqlQuery.CHECKS_DIRECTORY
                            + (root == null ? "" : " in " + root.getAbsolutePath())
                            + ". " + WRITES_ONE,
                    "This project has no src/sparql directory, or nothing with a .rq extension in "
                            + "it. " + WRITES_ONE,
                    " found: ", "the build's sparql_test target",
                    "Checks live in " + SparqlQuery.CHECKS_DIRECTORY + ": every .rq file there "
                            + "is one.",
                    null, null);
        }
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        File yaml = OdkBuildSettings.yamlIn(ontologyDirectory);
        String config = yaml == null ? "the project's <id>-odk.yaml" : yaml.getName();
        String listed = "listed under robot_report: custom_sparql_checks in " + config;
        String target = "ODK's sparql_test target";
        List<File> questions = OdkQuestions.filesIn(root);
        boolean kept = OdkQuestions.isKeptIn(root);
        File own = OdkQuestions.ownMakefileOf(root);
        String makefile = own == null ? "the project's own makefile" : own.getName();
        String where = "ODK's checks are src/sparql/<name>-violation.sparql for each name "
                + listed + "; ODK's sparql_test target, part of make test, asks them of the "
                + "edit file without its imports. The project's questions are the .rq files "
                + "in src/sparql, asked of the ontology with its imports" + (kept
                        ? " by the rule in " + makefile + ", as part of make test." : ".");
        String unknown = null;
        String text = null;
        if (yaml == null) {
            unknown = "There is no single <id>-odk.yaml in " + ontologyDirectory.getAbsolutePath()
                    + ", so which checks ODK's test runs is not known.";
        } else {
            try {
                text = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
                OdkChecks.declaredIn(text);
            } catch (IOException | OdkYaml.UnreadableException unreadable) {
                unknown = config + " could not be read, so which checks ODK's test runs is not "
                        + "known: " + unreadable.getMessage();
            }
        }
        if (unknown != null) {
            return new ProjectChecks(new ArrayList<File>(), unknown, unknown, ": ", target,
                    where, null, null);
        }
        List<File> odks = OdkChecks.filesIn(root, text);
        List<File> files = new ArrayList<File>(odks);
        files.addAll(questions);
        List<File> undeclared = OdkChecks.undeclaredIn(root, text);
        List<String> missing = OdkChecks.missingIn(root, text);
        String none = OdkChecks.declaredIn(text).isEmpty()
                ? config + " lists no checks under robot_report: custom_sparql_checks, so ODK's "
                        + "test runs none."
                : "None of the checks " + listed + " has its file in src/sparql (ODK runs "
                        + "src/sparql/<name>-violation.sparql).";
        String notRun = undeclared.isEmpty() ? null
                : "Not run: " + names(undeclared) + " - not listed under robot_report: "
                        + "custom_sparql_checks in " + config + ", so ODK's test does not run "
                        + (undeclared.size() == 1 ? "it." : "them.");
        if (!questions.isEmpty() && !kept) {
            // Asked here, and by nothing when the project's test runs.
            notRun = (notRun == null ? "" : notRun + "\n\n") + "Not asked by ODK's test: "
                    + names(questions) + " - the rule that asks a project's questions is not "
                    + "in " + makefile + (OdkQuestions.isChangedIn(root) ? " as OntoBoard "
                            + "wrote it" : "") + ". "
                    + "OntoBoard > Check and test > Write a test from a question... "
                    + "writes it, with the next question.";
        }
        String noFile = missing.isEmpty() ? null
                : "Listed under robot_report: custom_sparql_checks in " + config + " with no file "
                        + "in src/sparql: " + String.join(", ", missing) + ". ODK's test stops "
                        + "on a listed check whose src/sparql/<name>-violation.sparql is not there.";
        return new ProjectChecks(files, odks, questions, none, none, " - ODK's, " + listed
                + (questions.isEmpty() ? "" : ", and the project's questions") + ": ", target,
                where, notRun, noFile);
    }

    /** What the dialog says under "What to run" about the project's checks. */
    static String help(ProjectChecks checks) {
        List<File> files = checks.files();
        String help = files.isEmpty() ? checks.emptyHelp
                : files.size() + " check" + (files.size() == 1 ? "" : "s") + checks.foundAs
                        + names(files) + ". Each is a SELECT whose rows are the violations - it "
                        + "passes by returning nothing, which is the same convention robot "
                        + "verify and " + checks.target + " use.";
        for (String said : new String[] {checks.notRun(), checks.missing()}) {
            if (said != null) {
                help += "\n\n" + said;
            }
        }
        return help;
    }

    /**
     * Runs the project's checks, the way {@code make sparql_test} does, and says what was not
     * run. One TSV per failing check goes to {@code output} when it names a folder.
     */
    static OperationResult runChecks(ProjectChecks checks, OWLOntology ontology, File output) {
        List<File> files = checks.files();
        OperationResult.Builder result = OperationResult.of("SPARQL")
                .columns("Check", "Result", "Detail");

        if (files.isEmpty()) {
            result.failed(checks.noneFound());
            result.note(checks.notRun());
            result.note(checks.missing());
            return result.build();
        }

        int failed = 0;
        int unrunnable = 0;
        List<SparqlQuery.Check> ran = new ArrayList<SparqlQuery.Check>();
        for (File file : files) {
            // One of ODK's checks is asked as ODK's test asks it: of the ontology by itself.
            ran.addAll(checks.isAskedAlone(file)
                    ? SparqlQuery.verifyAlone(ontology, java.util.Collections.singletonList(file))
                    : SparqlQuery.verify(ontology, java.util.Collections.singletonList(file)));
        }
        for (int at = 0; at < ran.size(); at++) {
            SparqlQuery.Check check = ran.get(at);
            boolean question = checks.questions().contains(files.get(at));
            if (check.getFailure() != null) {
                unrunnable++;
                result.row(check.getName(), "could not run", check.getFailure());
                result.warn(check.getName() + " could not run: " + check.getFailure());
            } else if (check.isPassed()) {
                result.row(check.getName(), "pass", "no violations");
            } else {
                failed++;
                String found = check.getViolations()
                        + (check.getViolations() == 1 ? " violation" : " violations");
                result.row(check.getName(), "FAIL", found);
                result.warn(check.getName() + " found " + found + ", so "
                        + "this project's " + (question ? "`make test`" : "`make sparql_test`")
                        + " would fail.");
                // One TSV per failing check, the way `robot verify --output-dir` does it - the
                // violations are what somebody has to work through, and a count is not a worklist.
                if (output != null) {
                    File into = new File(output,
                            check.getName().replaceAll("\\.(rq|sparql)$", "") + ".tsv");
                    try {
                        if (output.isDirectory() || output.mkdirs()) {
                            SparqlQuery.writeTsv(check.getAnswer(), into);
                            result.wrote(into);
                        } else {
                            result.warn("Could not write violations to " + output.getAbsolutePath()
                                    + ": it is not a directory. Name a directory when running the "
                                    + "project's checks.");
                        }
                    } catch (RuntimeException cannotWrite) {
                        result.warn("Could not write " + into.getName() + ": "
                                + cannotWrite.getMessage());
                    }
                }
            }
        }

        result.note(checks.notRun());
        if (checks.missing() != null) {
            result.warn(checks.missing());
        }
        result.note(checks.where());
        String summary = files.size() + " checks: " + (files.size() - failed - unrunnable)
                + " passed, " + failed + " failed"
                + (unrunnable > 0 ? ", " + unrunnable + " could not run" : "");
        return result.summary(summary).build();
    }

    private static String names(List<File> files) {
        StringBuilder text = new StringBuilder();
        for (File file : files) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(file.getName());
        }
        return text.toString();
    }
}
