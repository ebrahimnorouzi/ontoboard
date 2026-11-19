package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.LooseCheck;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Check and test &gt; Check this ontology... - the reasoner, ROBOT's quality report (in a project
 * with its profile and on its own terms, else with ODK's default profile) and the label check,
 * asked of the ontology that is open: in a project or not,
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

    private static final String OPTION_REASONER = "reasoner";

    private volatile String reasoner;

    /**
     * The form: the reasoner - the person's choice, offered first as the project's own, else
     * HermiT, as Release offers it (the user, 2026-11-19: "check this ontology, is not offering
     * the choice for reasoner") - and HermiT's second opinion where the choice is ELK or Whelk.
     *
     * @param file the ontology's file, or null when it has never been saved
     */
    static java.util.List<Parameter> parameters(File file) {
        return java.util.Arrays.asList(
                Parameter.of(OPTION_REASONER, "Reasoner", Parameter.Kind.CHOICE)
                        .choices(de.fizkarlsruhe.ise.ontoboard.odk.ReleaseReasoners.names()
                                .toArray(new String[0]))
                        .defaultValue(de.fizkarlsruhe.ise.ontoboard.odk.ReleaseReasoners
                                .preselected(file))
                        .help("The reasoner that checks whether the ontology, with its imports, "
                                + "is consistent and every class can have members; where not, "
                                + "the axioms that cause it are listed. ELK and Whelk reason in "
                                + "OWL 2 EL: they ignore inverse properties, universal "
                                + "restrictions, cardinalities, unions and negation, so a "
                                + "contradiction that needs one of them goes unseen. HermiT and "
                                + "JFact read all of OWL 2 DL; on a large ontology they take "
                                + "longer. Offered first: the project's own reasoner, else "
                                + "HermiT, as Release offers it.")
                        .build(),
                Parameter.of(OPTION_SECOND, "With ELK or Whelk, ask HermiT too, for up to ten "
                        + "seconds", Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("ELK and Whelk pass over the axioms outside OWL EL. Where the "
                                + "ontology has such axioms, HermiT is asked as well, for up to "
                                + "ten seconds, and what it finds that they did not is said - as "
                                + "Build says it. Nothing is asked twice when HermiT or JFact is "
                                + "the reasoner chosen.")
                        .build());
    }

    @Override
    protected boolean configure() {
        File file = fileOf(getOWLModelManager().getActiveOntology());
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), operationName(),
                "Checks the ontology as it is open - with what it imports, where that is "
                        + "loaded - with the reasoner you choose, ROBOT's quality report and "
                        + "the label check of a project OntoBoard makes. The report is asked as "
                        + "Quality report... asks it: in a project with the project's profile, "
                        + "on its own terms, as ODK's test counts them; otherwise with ODK's "
                        + "default profile. No project is needed, and nothing is written.",
                parameters(file));
        if (chosen == null) {
            return false;
        }
        reasoner = chosen.get(OPTION_REASONER);
        secondOpinion = !"false".equalsIgnoreCase(chosen.get(OPTION_SECOND));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        LooseCheck.Options options = LooseCheck.Options.forFile(fileOf(ontology));
        if (reasoner != null) {
            options = options.withReasoner(reasoner);
        }
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
