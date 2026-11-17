package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.LooseCheck;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Check and test &gt; Check this ontology... - the reasoner, ROBOT's quality report with ODK's
 * default profile and the label check, asked of the ontology that is open: in a project or not,
 * a loose {@code .owl} file too ({@link LooseCheck}). What Build checks of a project, without
 * one - the testers of 1.132.0: "Today Build needs a project." Nothing is written.
 */
public class CheckOntologyAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_SECOND = "second";

    private volatile boolean secondOpinion = true;

    @Override
    protected String operationName() {
        return "Check this ontology";
    }

    @Override
    protected boolean configure() {
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), operationName(),
                "Checks the ontology as it is open - with what it imports, where that is "
                        + "loaded - with the reasoner, ROBOT's quality report with ODK's default "
                        + "profile, and the label check of a project OntoBoard makes. No project "
                        + "is needed, and nothing is written.",
                Collections.singletonList(Parameter.of(OPTION_SECOND,
                        "Ask HermiT too, for up to ten seconds", Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("ELK reasons as an ODK release does, and passes over the axioms "
                                + "outside OWL EL. Where the ontology has such axioms, HermiT is "
                                + "asked as well, for up to ten seconds, and what it finds that "
                                + "ELK did not is said - as Build says it.")
                        .build()));
        if (chosen == null) {
            return false;
        }
        secondOpinion = !"false".equalsIgnoreCase(chosen.get(OPTION_SECOND));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        LooseCheck.Options options = LooseCheck.Options.defaults();
        if (!secondOpinion) {
            options = options.withoutSecondOpinion();
        }
        return reported(operationName(), LooseCheck.run(ontology, options), fileOf(ontology));
    }

    /**
     * The checks as a result: a row each, every verdict in the log - a failed one as a
     * warning - with what each found under it, and what was checked.
     *
     * @param file the ontology's file, or null when it has never been saved
     */
    static OperationResult reported(String operation, LooseCheck.Result checked, File file) {
        OperationResult.Builder result = OperationResult.of(operation)
                .columns("Check", "Result", "Verdict");
        int failed = 0;
        for (LooseCheck.Line line : checked.lines()) {
            result.row(line.check(), line.passed() ? "pass" : "FAIL", line.verdict());
            if (line.passed()) {
                result.note(line.check() + ": " + line.verdict());
            } else {
                failed++;
                result.warn(line.check() + ": " + line.verdict());
            }
            for (String detail : line.details()) {
                // A dash first: a note is kept without the blanks it starts with.
                result.note("- " + detail);
            }
        }
        result.note(file == null ? "Checked as it is open: it has not been saved."
                : "Checked as it is open: " + file.getAbsolutePath());
        int all = checked.lines().size();
        return result.summary(failed == 0 ? "All " + all + " checks passed."
                : failed + " of " + all + " checks found something to put right.").build();
    }
}
