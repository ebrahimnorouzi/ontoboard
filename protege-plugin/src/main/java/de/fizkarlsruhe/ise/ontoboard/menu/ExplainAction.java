package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Explain - which axioms make a class unsatisfiable, or the ontology inconsistent.
 *
 * <p>Protege ships an explanation workbench and this is not a replacement for it. That one explains
 * a single entailment you have already located and clicked. This one starts from the question a
 * curator actually has when the reasoner goes red - <em>what did I break, and with which axiom</em> -
 * and answers it for every unsatisfiable class at once, ending with ROBOT's summary of which single
 * axiom appears in the most justifications. Twelve unsatisfiable classes is twelve investigations
 * there and one table here.
 *
 * <p>Runs in the background: explanation is the most expensive thing on this menu. Each
 * justification is a search for a minimal subset of axioms that still entails the problem, and the
 * cost multiplies by the number of unsatisfiable classes and the number of justifications asked for.
 */
public class ExplainAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_REASONER = "reasoner";
    private static final String OPTION_DEPTH = "depth";

    /** Enough rows to see the pattern; the impact summary is what scales. */
    private static final int MAX_ROWS = 400;

    private volatile Reasoners.Choice reasoner = Reasoners.Choice.HERMIT;
    private volatile int perClass = Explanations.DEFAULT_PER_CLASS;

    @Override
    protected String operationName() {
        return "Explain";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Explain",
                "Finds the axioms responsible for every unsatisfiable class, or for an "
                        + "inconsistency. Nothing is modified.",
                Arrays.asList(
                        Parameter.of(OPTION_REASONER, "Reasoner", Parameter.Kind.CHOICE)
                                .choices(Reasoners.labels().toArray(new String[0]))
                                .defaultValue(Reasoners.Choice.HERMIT.getLabel())
                                .required()
                                .help("HermiT by default, not ELK. ELK covers only the EL profile "
                                        + "and silently ignores axioms outside it, so it can call "
                                        + "an ontology satisfiable when a full reasoner would not "
                                        + "- which is the worst possible answer from a tool whose "
                                        + "job is to explain what is wrong.\n\n"
                                        + Reasoners.help())
                                .build(),
                        Parameter.of(OPTION_DEPTH, "Justifications per problem",
                                Parameter.Kind.NUMBER)
                                .defaultValue(String.valueOf(Explanations.DEFAULT_PER_CLASS))
                                .required()
                                .help("An axiom can be the culprit in one justification and "
                                        + "irrelevant in another, so one is rarely enough and the "
                                        + "count is what makes the impact summary meaningful. "
                                        + "Beyond a handful the cost grows sharply and the extra "
                                        + "justifications repeat each other.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        reasoner = Reasoners.byLabel(chosen.get(OPTION_REASONER));
        perClass = number(chosen.get(OPTION_DEPTH), Explanations.DEFAULT_PER_CLASS);
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        Explanations.Result explained =
                Explanations.run(ontology, reasoner.newFactory(), perClass);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Explains", "Axiom");
        result.note("Reasoner: " + reasoner.getLabel());
        result.note("Justifications per problem: " + perClass);

        if (explained.isClean()) {
            // Not a warning and not an empty table: "nothing to explain" is the good outcome, and a
            // curator who ran this because the reasoner went red needs to be told plainly that it
            // is no longer red.
            return result.summary("Consistent, with no unsatisfiable classes - nothing to explain.")
                    .build();
        }

        if (!explained.isConsistent()) {
            result.warn("The ontology is inconsistent. Every class is unsatisfiable until this is "
                    + "fixed, so the axioms below are the ones worth reading first.");
        } else {
            result.warn(explained.getUnsatisfiable().size() + " unsatisfiable "
                    + (explained.getUnsatisfiable().size() == 1 ? "class" : "classes") + ": "
                    + join(explained.getUnsatisfiable()));
        }

        int rows = 0;
        for (Explanations.Justification justification : explained.getJustifications()) {
            for (String axiom : justification.getAxioms()) {
                if (rows >= MAX_ROWS) {
                    result.note("Listing the first " + MAX_ROWS + " axioms. Ask for fewer "
                            + "justifications per problem to see a smaller set.");
                    break;
                }
                result.row(justification.getEntailment(), axiom);
                rows++;
            }
            if (rows >= MAX_ROWS) {
                break;
            }
        }

        if (!explained.getImpactSummary().trim().isEmpty()) {
            result.note("Axioms by how many justifications they appear in - fix the top one first:");
            for (String line : explained.getImpactSummary().split("\\r?\\n")) {
                if (!line.trim().isEmpty()) {
                    result.note("  " + line.trim());
                }
            }
        }

        return result.summary(explained.getJustifications().size() + " justifications across "
                + (explained.isConsistent()
                        ? explained.getUnsatisfiable().size() + " unsatisfiable classes"
                        : "an inconsistent ontology")).build();
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(value);
        }
        return text.toString();
    }

    private static int number(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed < 1 ? fallback : parsed;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
