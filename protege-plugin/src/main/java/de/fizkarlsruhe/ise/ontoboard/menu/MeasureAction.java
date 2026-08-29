package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologyMeasurements;
import java.util.List;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Measure - size, expressivity and shape of the open ontology.
 *
 * <p>The cheapest useful thing on the menu, and the only ROBOT operation that works on Protege
 * 5.5.0: it needs no reasoner and never touches the RDF layer that differs between OWL API 4.5.9
 * and 4.5.29, which is what stops {@code report} running there.
 */
public class MeasureAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected String operationName() {
        return "Measure";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        List<OntologyMeasurements.Measurement> measurements =
                OntologyMeasurements.run(ontology, OntologyMeasurements.Depth.EXTENDED);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Group", "Metric", "Value")
                .summary(measurements.size() + " metrics for "
                        + ontology.getOntologyID().toString());
        for (OntologyMeasurements.Measurement measurement : measurements) {
            result.row(measurement.getGroup(), measurement.getLabel(), measurement.getValue());
            // Surfaced as warnings rather than left in the table: an undeclared entity or a cycle
            // is a modelling error, and a user scanning forty rows will not spot the one that
            // matters.
            if ("Warnings".equals(measurement.getGroup())
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
