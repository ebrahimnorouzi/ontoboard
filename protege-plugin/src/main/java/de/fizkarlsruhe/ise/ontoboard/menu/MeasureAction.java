package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologyMeasurements;
import java.util.List;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Measure - size, expressivity and shape of the open ontology.
 *
 * <p>The cheapest useful thing on the menu: it needs no reasoner and touches no RDF layer, so it is
 * the one operation with nothing that could go wrong in a bundle. This used to add "and the only
 * ROBOT operation that works on Protege 5.5.0", which stopped being true in 1.25.0 - the report runs
 * on both hosts now, and the barrier it named was never the reason anyway.
 */
public class MeasureAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected String operationName() {
        return "Measure";
    }

    /** What was chosen in the parameter dialog, read on the EDT before the work starts. */
    private volatile OntologyMeasurements.Depth depth = OntologyMeasurements.Depth.EXTENDED;
    private volatile boolean warnOnFindings = true;

    /**
     * Asks how much to measure, before anything runs.
     *
     * <p>On the EDT deliberately - a modal dialog from a worker thread is a Swing threading
     * violation. The work itself still runs in the background; only the question is asked here.
     *
     * @return false when the user cancelled, so nothing runs
     */
    @Override
    protected boolean configure() {
        java.util.List<Parameter> parameters = java.util.Arrays.asList(
                Parameter.of("depth", "How much to measure", Parameter.Kind.CHOICE)
                        .choices("Essential", "Extended", "All")
                        .defaultValue("Extended")
                        .help("Essential counts axioms and entities and is instant. Extended also "
                                + "walks the class hierarchy for depth and sibling statistics. All "
                                + "adds everything ROBOT can compute without a reasoner, and is "
                                + "the slowest on a large ontology.")
                        .build(),
                Parameter.of("warn", "Raise modelling problems as warnings",
                        Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("Undeclared entities, class cycles, tautologies and OWL2-DL "
                                + "violations are counted among forty other metrics, where nobody "
                                + "finds them. With this on they are also listed as warnings at "
                                + "the top of the result.")
                        .build());

        java.util.Map<String, String> values = ParameterDialog.show(getOWLWorkspace(),
                "Measure", "ROBOT measures the size, expressivity and shape of the ontology you "
                        + "have open. Nothing is modified.", parameters);
        if (values == null) {
            return false;
        }
        depth = OntologyMeasurements.Depth.valueOf(
                values.get("depth").toUpperCase(java.util.Locale.ROOT));
        warnOnFindings = "true".equalsIgnoreCase(values.get("warn"));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        List<OntologyMeasurements.Measurement> measurements =
                OntologyMeasurements.run(ontology, depth);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Group", "Metric", "Value")
                .summary(measurements.size() + " metrics for "
                        + ontology.getOntologyID().toString());
        // Recorded so a saved report says what produced it. A table of numbers with no record of
        // the settings behind them cannot be compared with another run.
        result.note("Depth: " + depth);
        for (OntologyMeasurements.Measurement measurement : measurements) {
            result.row(measurement.getGroup(), measurement.getLabel(), measurement.getValue());
            // Surfaced as warnings rather than left in the table: an undeclared entity or a cycle
            // is a modelling error, and a user scanning forty rows will not spot the one that
            // matters.
            if (warnOnFindings && "Warnings".equals(measurement.getGroup())
                    && isConcerning(measurement.getLabel(), measurement.getValue())) {
                result.warn(measurement.getLabel() + ": " + measurement.getValue());
            }
        }
        return result.build();
    }

    /** A count above zero, or an explicit "true" for the boolean ones. */
    private static boolean isConcerning(String label, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        try {
            return Long.parseLong(value.trim()) > 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }
}
