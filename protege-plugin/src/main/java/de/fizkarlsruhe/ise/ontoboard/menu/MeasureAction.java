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
        // Made first: its clock starts here. Made after the measuring, it said "0 ms" for
        // nfdicore (1.155.0).
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Group", "Metric", "Value");
        java.io.File file = fileOf(ontology);
        List<OntologyMeasurements.Measurement> measurements = OntologyMeasurements.run(ontology,
                depth, file == null ? null : ownName(file));

        String name = file == null ? ontology.getOntologyID().toString() : file.getName();
        String said = OntologyMeasurements.summary(measurements, name);
        result.summary(said.isEmpty() ? measurements.size() + " metrics for " + name : said);
        // An import that did not arrive is missing from every count with its imports: said
        // before the counts are read (1.155.0).
        java.io.File catalog = file == null || file.getParentFile() == null ? null
                : new java.io.File(file.getParentFile(), "catalog-v001.xml");
        List<de.fizkarlsruhe.ise.ontoboard.odk.ImportHealth.Missing> missing =
                de.fizkarlsruhe.ise.ontoboard.odk.ImportHealth.missingFrom(ontology,
                        getOWLModelManager().getOWLOntologyManager(),
                        catalog != null && catalog.isFile() ? catalog : null);
        if (!missing.isEmpty()) {
            result.warn(missing.size() + (missing.size() == 1 ? " import" : " imports")
                    + " of this ontology did not arrive, and the counts with its imports leave "
                    + "out what " + (missing.size() == 1 ? "it holds" : "they hold") + ": "
                    + missing + ". OntoBoard > Reuse > Check imports says why.");
        }
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

    /**
     * The name the ontology's default prefix goes by in the counts by prefix: the file's, as ODK
     * names a project's edit file - nfdicore-edit.owl is "nfdicore".
     */
    static String ownName(java.io.File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.endsWith("-edit") ? name.substring(0, name.length() - "-edit".length())
                : name;
    }

    /**
     * A count above nought, a "true", or a breakdown that names something - ROBOT says why
     * nfdicore is not in OWL 2 DL as "UseOfUndeclaredAnnotationProperty 1" (1.155.0).
     */
    static boolean isConcerning(String label, String value) {
        String said = value == null ? "" : value.trim();
        if (said.isEmpty() || "false".equalsIgnoreCase(said)) {
            return false;
        }
        if ("true".equalsIgnoreCase(said)) {
            return true;
        }
        try {
            return Long.parseLong(said) > 0;
        } catch (NumberFormatException notANumber) {
            return true;
        }
    }
}
