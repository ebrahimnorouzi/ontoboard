package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.obolibrary.robot.metrics.MeasureResult;
import org.obolibrary.robot.metrics.MetricsLabels;
import org.obolibrary.robot.metrics.OntologyMetrics;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT's {@code measure} against the ontology Protege has open.
 *
 * <p>The cheapest ROBOT operation to offer and one of the most useful: {@code new
 * OntologyMetrics(ontology).getEssentialMetrics()} needs no reasoner, no {@code IOHelper} and no
 * CURIE provider, so unlike {@code report} it does not touch the RDF layer that differs between
 * OWL API 4.5.9 and 4.5.29. It should therefore work on Protege 5.5.0 as well as 5.6.x - but that
 * is a claim about a code path, so {@link LinkageError} is still caught and explained rather than
 * assumed away. ("unlike {@code report}" is now only history: the report was moved off that layer
 * in 1.25.0 and runs on both hosts too.)
 *
 * <p>Metrics come back from ROBOT as an unordered map of forty-odd keys with machine names like
 * {@code tbox_axiom_count}. Presented raw that is a wall of text, so this class does the two
 * things a panel cannot: it groups the metrics a person actually asks for, and it puts them in a
 * stable order. Anything ROBOT returns that is not in a known group is still shown, under
 * "Other" - a metric silently dropped because this class had not heard of it would be worse than
 * an ugly label.
 */
public final class OntologyMeasurements {

    /** How much to compute. Essential is fast; extended walks the axioms more thoroughly. */
    public enum Depth {
        ESSENTIAL,
        EXTENDED,
        ALL
    }

    /** One metric, ready to put in a table. */
    public static final class Measurement {
        private final String group;
        private final String label;
        private final String value;

        Measurement(String group, String label, String value) {
            this.group = group;
            this.label = label;
            this.value = value;
        }

        /** The section this belongs under, for a grouped table. */
        public String getGroup() {
            return group;
        }

        /** A human-readable name, derived from ROBOT's machine key. */
        public String getLabel() {
            return label;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return group + " / " + label + " = " + value;
        }
    }

    /**
     * The groups, in display order, and the ROBOT keys that belong to each.
     *
     * <p>Keys are taken from {@link MetricsLabels} constants rather than typed as strings, so a
     * renamed key fails to compile instead of quietly falling into "Other".
     */
    private static final Object[][] GROUPS = {
        {"Size", new String[] {
            MetricsLabels.AXIOM_COUNT, MetricsLabels.LOGICAL_AXIOM_COUNT,
            MetricsLabels.TBOX_SIZE, MetricsLabels.RBOX_SIZE, MetricsLabels.ABOX_SIZE,
            MetricsLabels.TBOXRBOX_SIZE, MetricsLabels.ONTOLOGY_ANNOTATIONS_COUNT}},
        {"Entities", new String[] {
            MetricsLabels.CLASS_COUNT, MetricsLabels.OBJPROPERTY_COUNT,
            MetricsLabels.DATAPROPERTY_COUNT, MetricsLabels.ANNOTATION_PROP_COUNT,
            MetricsLabels.INDIVIDUAL_COUNT, MetricsLabels.DATATYPE_COUNT,
            MetricsLabels.SIGNATURE_SIZE}},
        {"Expressivity", new String[] {
            MetricsLabels.EXPRESSIVITY, MetricsLabels.AXIOMTYPE_COUNT,
            MetricsLabels.CLASSEXPRESSION_COUNT, MetricsLabels.GCI_COUNT,
            MetricsLabels.BOOL_PROFILE_OWL2_DL, MetricsLabels.BOOL_PROFILE_OWL2_EL,
            MetricsLabels.BOOL_PROFILE_OWL2_QL, MetricsLabels.BOOL_PROFILE_OWL2_RL}},
        {"Hierarchy", new String[] {
            MetricsLabels.MAX_NUM_NAMED_SUPERCLASS, MetricsLabels.AVG_ASSERT_N_SUPERCLASS,
            MetricsLabels.AVG_ASSERT_N_SUBCLASS, MetricsLabels.MULTI_INHERITANCE_COUNT,
            MetricsLabels.CLASS_SGL_SUBCLASS_COUNT}},
        // Not size or shape but signals worth surfacing: an undeclared entity or a cycle is
        // something a maintainer wants to see next to the counts, not buried under "Other".
        {"Warnings", new String[] {
            MetricsLabels.UNDECLARED_ENTITY_COUNT, MetricsLabels.CYCLE,
            MetricsLabels.TAUTOLOGYCOUNT, MetricsLabels.VIOLATION_PROFILE_OWL2_DL,
            MetricsLabels.VALID_IMPORTS}},
    };

    /** Metrics not in any group above. Shown, not dropped. */
    static final String OTHER_GROUP = "Other";

    private OntologyMeasurements() {
    }

    /**
     * Measures {@code ontology}.
     *
     * @throws RobotException if ROBOT cannot run here, with a message
     *     naming the reason - the same contract as the quality report, so a panel can show
     *     either failure the same way
     */
    public static List<Measurement> run(OWLOntology ontology, Depth depth) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to measure");
        }
        MeasureResult result;
        try {
            OntologyMetrics metrics = new OntologyMetrics(ontology);
            switch (depth) {
                case ALL:
                    result = metrics.getAllMetrics();
                    break;
                case EXTENDED:
                    result = metrics.getExtendedMetrics();
                    break;
                case ESSENTIAL:
                default:
                    result = metrics.getEssentialMetrics();
                    break;
            }
        } catch (LinkageError incompatible) {
            throw new RobotException(
                    "ROBOT's metrics could not run against this Protege's OWL API. "
                            + "Protege 5.6 or newer is known to work.", incompatible);
        } catch (RuntimeException failed) {
            throw new RobotException(
                    "ROBOT could not measure this ontology: " + failed.getMessage(), failed);
        }
        return present(result);
    }

    /** Turns ROBOT's map into ordered, grouped, labelled rows. */
    static List<Measurement> present(MeasureResult result) {
        List<Measurement> rows = new ArrayList<Measurement>();
        if (result == null) {
            return rows;
        }
        Map<String, Object> data = result.getData();
        Set<String> placed = new LinkedHashSet<String>();

        for (Object[] group : GROUPS) {
            String name = (String) group[0];
            for (String key : (String[]) group[1]) {
                if (data.containsKey(key)) {
                    rows.add(new Measurement(name, humanise(key), text(data.get(key))));
                    placed.add(key);
                }
            }
        }
        // Whatever is left, in ROBOT's own order. A metric this class has not heard of is still
        // information; hiding it would make the panel quietly incomplete as ROBOT grows.
        List<String> leftovers = new ArrayList<String>(data.keySet());
        for (String key : leftovers) {
            if (!placed.contains(key)) {
                rows.add(new Measurement(OTHER_GROUP, humanise(key), text(data.get(key))));
            }
        }
        // List-valued metrics (axiom types used, profile violations) matter but do not fit a
        // single cell, so they are joined rather than omitted.
        for (Map.Entry<String, List<Object>> entry : result.getListData().entrySet()) {
            rows.add(new Measurement(OTHER_GROUP, humanise(entry.getKey()),
                    join(entry.getValue())));
        }
        return rows;
    }

    /** {@code tbox_axiom_count} to {@code Tbox axiom count}. */
    static String humanise(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String spaced = key.replace('_', ' ').trim();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String join(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (Object value : values) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value);
        }
        return joined.toString();
    }

    /** The group names in display order, for a panel that wants to render sections. */
    public static List<String> groupOrder() {
        List<String> names = new ArrayList<String>();
        for (Object[] group : GROUPS) {
            names.add((String) group[0]);
        }
        names.add(OTHER_GROUP);
        return names;
    }

    /** Every ROBOT key this class knows how to group, for tests and diagnostics. */
    static Set<String> groupedKeys() {
        Set<String> keys = new LinkedHashSet<String>();
        for (Object[] group : GROUPS) {
            keys.addAll(Arrays.asList((String[]) group[1]));
        }
        return keys;
    }
}
