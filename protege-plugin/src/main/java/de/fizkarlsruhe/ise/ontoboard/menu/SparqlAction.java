package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.net.URI;
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
        List<File> checks = SparqlQuery.checksIn(projectRoot());
        String checksHelp = checks.isEmpty()
                ? "This project has no src/sparql directory, or nothing with a .rq extension in "
                        + "it. The ODK wizard writes check_labels.rq there, and the generated "
                        + "sparql_test target runs everything it finds."
                : checks.size() + " check" + (checks.size() == 1 ? "" : "s")
                        + " found: " + names(checks) + ". Each is a SELECT whose rows are the "
                        + "violations - it passes by returning nothing, which is the same "
                        + "convention robot verify and the build's sparql_test target use.";

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
        File root = projectRoot();
        List<File> files = SparqlQuery.checksIn(root);
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Check", "Result", "Detail");

        if (files.isEmpty()) {
            return result.failed("No .rq files under " + SparqlQuery.CHECKS_DIRECTORY
                    + (root == null ? "" : " in " + root.getAbsolutePath())
                    + ". The ODK wizard writes check_labels.rq there.").build();
        }

        int failed = 0;
        int unrunnable = 0;
        for (SparqlQuery.Check check : SparqlQuery.verify(ontology, files)) {
            if (check.getFailure() != null) {
                unrunnable++;
                result.row(check.getName(), "could not run", check.getFailure());
                result.warn(check.getName() + " could not run: " + check.getFailure());
            } else if (check.isPassed()) {
                result.row(check.getName(), "pass", "no violations");
            } else {
                failed++;
                result.row(check.getName(), "FAIL",
                        check.getViolations() + " violations");
                result.warn(check.getName() + " found " + check.getViolations() + " violations, so "
                        + "this project's `make sparql_test` would fail.");
                // One TSV per failing check, the way `robot verify --output-dir` does it - the
                // violations are what somebody has to work through, and a count is not a worklist.
                if (output != null) {
                    File into = new File(output, check.getName().replaceAll("\\.rq$", "") + ".tsv");
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

        result.note("Checks live in " + SparqlQuery.CHECKS_DIRECTORY + " and are what the "
                + "generated sparql_test target runs.");
        String summary = files.size() + " checks: " + (files.size() - failed - unrunnable)
                + " passed, " + failed + " failed"
                + (unrunnable > 0 ? ", " + unrunnable + " could not run" : "");
        return result.summary(summary).build();
    }

    /** The ODK project the open ontology belongs to, or null when it has never been saved. */
    private File projectRoot() {
        try {
            OWLOntology ontology = getOWLModelManager().getActiveOntology();
            if (ontology == null) {
                return null;
            }
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            if (!"file".equalsIgnoreCase(documentUri.getScheme())) {
                return null;
            }
            return ReleaseAction.projectRootOf(new File(documentUri));
        } catch (RuntimeException notAFile) {
            return null;
        }
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
