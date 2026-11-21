package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.obolibrary.robot.metrics.MeasureResult;
import org.obolibrary.robot.metrics.MetricsLabels;
import org.obolibrary.robot.metrics.OntologyMetrics;
import org.obolibrary.robot.providers.CURIEShortFormProvider;
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
        private final String key;
        private final String label;
        private final String value;

        Measurement(String group, String key, String label, String value) {
            this.group = group;
            this.key = key;
            this.label = label;
            this.value = value;
        }

        /** The section this belongs under, for a grouped table. */
        public String getGroup() {
            return group;
        }

        /**
         * ROBOT's own name for this metric, as {@code robot measure} writes it - {@code
         * class_count}, {@code owl2_dl}.
         *
         * <p>Kept alongside the humanised label because this is the name that appears in a build's
         * output and in anything downstream of it. A curator comparing this panel with a metrics
         * file their CI produced needs the name both of them use, and it is the only name a test
         * can compare against the command line without reimplementing {@link #humanise}.
         */
        public String getKey() {
            return key;
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
     * The groups, in display order, and the ROBOT keys that belong to each with the words a
     * person reads for it.
     *
     * <p>What the ontology is with its imports leads (1.155.0): the user measured nfdicore and
     * read its edit file's own counts first - 14 axioms, no class - while what it is, with its
     * imports, stood under "Other" as "Obj property count incl". ROBOT's profile checks read
     * the imports closure, so they are with its imports too, and so are the warnings: a term
     * the edit file uses is declared where it is imported from.
     *
     * <p>Keys are taken from {@link MetricsLabels} constants rather than typed as strings, so a
     * renamed key fails to compile instead of quietly falling into "Other".
     */
    private static final Object[][] GROUPS = {
        {"With its imports", new String[][] {
            {MetricsLabels.CLASS_COUNT_INCL, "Classes"},
            {MetricsLabels.OBJPROPERTY_COUNT_INCL, "Object properties"},
            {MetricsLabels.DATAPROPERTY_COUNT_INCL, "Data properties"},
            {MetricsLabels.ANNOTATION_PROP_COUNT_INCL, "Annotation properties"},
            {MetricsLabels.INDIVIDUAL_COUNT_INCL, "Individuals"},
            {MetricsLabels.DATATYPE_COUNT_INCL, "Datatypes"},
            {MetricsLabels.LOGICAL_AXIOM_COUNT_INCL, "Logical axioms"},
            {MetricsLabels.AXIOM_COUNT_INCL, "Axioms"},
            {MetricsLabels.EXPRESSIVITY_INCL, "Expressivity (description logic)"},
            {MetricsLabels.BOOL_PROFILE_OWL2_DL, "In OWL 2 DL"},
            {MetricsLabels.BOOL_PROFILE_OWL2_EL, "In OWL 2 EL"},
            {MetricsLabels.BOOL_PROFILE_OWL2_QL, "In OWL 2 QL"},
            {MetricsLabels.BOOL_PROFILE_OWL2_RL, "In OWL 2 RL"},
            {MetricsLabels.NS_USE_SIGNATURE_INCL, "Terms by prefix"},
            {MetricsLabels.AXIOMTYPE_COUNT_INCL, "Axioms by type"},
            {MetricsLabels.CLASSEXPRESSION_COUNT_INCL, "Class expressions by type"},
            {MetricsLabels.VALID_IMPORTS_INCL, "Imports read"}}},
        {"In this file", new String[][] {
            {MetricsLabels.CLASS_COUNT, "Classes"},
            {MetricsLabels.OBJPROPERTY_COUNT, "Object properties"},
            {MetricsLabels.DATAPROPERTY_COUNT, "Data properties"},
            {MetricsLabels.ANNOTATION_PROP_COUNT, "Annotation properties"},
            {MetricsLabels.INDIVIDUAL_COUNT, "Individuals"},
            {MetricsLabels.DATATYPE_COUNT, "Datatypes"},
            {MetricsLabels.LOGICAL_AXIOM_COUNT, "Logical axioms"},
            {MetricsLabels.AXIOM_COUNT, "Axioms"},
            {MetricsLabels.ONTOLOGY_ANNOTATIONS_COUNT, "Annotations of the ontology"},
            {MetricsLabels.EXPRESSIVITY, "Expressivity (description logic)"},
            {MetricsLabels.NS_USE_SIGNATURE, "Terms by prefix"},
            {MetricsLabels.AXIOMTYPE_COUNT, "Axioms by type"},
            {MetricsLabels.VALID_IMPORTS, "Imports"}}},
        {"Hierarchy, with its imports", new String[][] {
            {MetricsLabels.MAX_NUM_NAMED_SUPERCLASS_INCL, "Most named superclasses of a class"},
            {MetricsLabels.AVG_ASSERT_N_SUPERCLASS_INCL, "Named superclasses of a class, on "
                    + "average"},
            {MetricsLabels.AVG_ASSERT_N_SUBCLASS_INCL, "Named subclasses of a class, on average"},
            {MetricsLabels.MULTI_INHERITANCE_COUNT_INCL, "Classes with more than one named "
                    + "superclass"},
            {MetricsLabels.CLASS_SGL_SUBCLASS_COUNT_INCL, "Classes with one subclass"}}},
        // Not size or shape but signals worth surfacing: an undeclared entity or a cycle is
        // something a maintainer wants to see next to the counts, not buried under "Other".
        {"Warnings", new String[][] {
            {MetricsLabels.UNDECLARED_ENTITY_COUNT_INCL, "Terms used and not declared"},
            {MetricsLabels.CYCLE_INCL, "A class among its own superclasses"},
            {MetricsLabels.TAUTOLOGYCOUNT_INCL, "Axioms that say nothing"},
            {MetricsLabels.VIOLATION_PROFILE_OWL2_DL, "Outside OWL 2 DL"}}},
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
        try {
            return measured(new OntologyMetrics(ontology), depth);
        } catch (LinkageError incompatible) {
            throw new RobotException(
                    "ROBOT's metrics could not run against this Protege's OWL API. "
                            + "Protege 5.6 or newer is known to work.", incompatible);
        }
    }

    private static List<Measurement> measured(OntologyMetrics metrics, Depth depth) {
        MeasureResult result;
        try {
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

    /**
     * Measures {@code ontology}, its own prefixes beside ROBOT's (1.155.0): nfdicore's 235 terms
     * were counted under "prefix_unknown", since ROBOT knows OBO's prefixes and not the
     * ontology's. A prefix ROBOT has already is left as ROBOT has it.
     *
     * @param ownName the name for the ontology's default prefix - {@code :} - in the counts by
     *                prefix, as the file is called; null leaves it out
     */
    public static List<Measurement> run(OWLOntology ontology, Depth depth, String ownName) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to measure");
        }
        CURIEShortFormProvider prefixes;
        try {
            prefixes = new CURIEShortFormProvider(prefixesOf(ontology, ownName));
        } catch (RuntimeException | LinkageError cannot) {
            // ROBOT's own map is the one it reads; without it, ROBOT's way.
            return run(ontology, depth);
        }
        try {
            return measured(new OntologyMetrics(ontology, prefixes), depth);
        } catch (LinkageError incompatible) {
            throw new RobotException(
                    "ROBOT's metrics could not run against this Protege's OWL API. "
                            + "Protege 5.6 or newer is known to work.", incompatible);
        }
    }

    /** ROBOT's prefixes, then the ontology's own that ROBOT has not. */
    static Map<String, String> prefixesOf(OWLOntology ontology, String ownName) {
        Map<String, String> prefixes;
        try {
            prefixes = new LinkedHashMap<String, String>(
                    new org.obolibrary.robot.IOHelper().getPrefixes());
        } catch (java.io.IOException unread) {
            throw new java.io.UncheckedIOException("ROBOT's prefixes could not be read", unread);
        }
        // The open ontology's first, then those it imports: nfdicore's edit file declares its
        // default prefix as .../ontology#, which no term uses, and nfdicore-main the
        // .../ontology/ its terms are under. The name goes to the default prefix terms use.
        List<OWLOntology> ontologies = new ArrayList<OWLOntology>();
        ontologies.add(ontology);
        for (OWLOntology imported : ontology.getImportsClosure()) {
            if (!imported.equals(ontology)) {
                ontologies.add(imported);
            }
        }
        String own = ownName == null ? "" : ownName.trim();
        for (OWLOntology one : ontologies) {
            org.semanticweb.owlapi.model.OWLDocumentFormat format =
                    one.getOWLOntologyManager().getOntologyFormat(one);
            if (format == null || !format.isPrefixOWLOntologyFormat()) {
                continue;
            }
            for (Map.Entry<String, String> declared : format.asPrefixOWLOntologyFormat()
                    .getPrefixName2PrefixMap().entrySet()) {
                String name = declared.getKey().endsWith(":")
                        ? declared.getKey().substring(0, declared.getKey().length() - 1)
                        : declared.getKey();
                if (name.isEmpty()) {
                    if (own.isEmpty() || !usedBy(ontology, declared.getValue())) {
                        continue;
                    }
                    name = own;
                }
                if (!prefixes.containsKey(name) && !prefixes.containsValue(declared.getValue())) {
                    prefixes.put(name, declared.getValue());
                }
            }
        }
        return prefixes;
    }

    /** Whether a term of the ontology, with its imports, is under this namespace. */
    private static boolean usedBy(OWLOntology ontology, String namespace) {
        for (org.semanticweb.owlapi.model.OWLEntity entity : ontology.getSignature(
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            if (entity.getIRI().toString().startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The sentence a person reads first: the ontology, with its imports, in four counts and
     * whether it is in OWL 2 DL - "nfdicore-edit.owl with its imports: 271 classes, 62 object
     * properties, 46 individuals and 521 logical axioms - in OWL 2 DL." Empty when ROBOT gave
     * none of them.
     */
    public static String summary(List<Measurement> rows, String name) {
        Map<String, String> byKey = new LinkedHashMap<String, String>();
        for (Measurement row : rows) {
            byKey.put(row.getKey(), row.getValue());
        }
        List<String> counts = new ArrayList<String>();
        String[][] said = {
            {MetricsLabels.CLASS_COUNT_INCL, "class", "classes"},
            {MetricsLabels.OBJPROPERTY_COUNT_INCL, "object property", "object properties"},
            {MetricsLabels.INDIVIDUAL_COUNT_INCL, "individual", "individuals"},
            {MetricsLabels.LOGICAL_AXIOM_COUNT_INCL, "logical axiom", "logical axioms"}};
        for (String[] one : said) {
            String value = byKey.get(one[0]);
            if (value != null && !value.trim().isEmpty()) {
                counts.add(value.trim() + " " + ("1".equals(value.trim()) ? one[1] : one[2]));
            }
        }
        if (counts.isEmpty()) {
            return "";
        }
        String listed = counts.size() == 1 ? counts.get(0)
                : String.join(", ", counts.subList(0, counts.size() - 1)) + " and "
                        + counts.get(counts.size() - 1);
        String dl = byKey.get(MetricsLabels.BOOL_PROFILE_OWL2_DL);
        return name + " with its imports: " + listed + (dl == null ? "."
                : "true".equalsIgnoreCase(dl.trim()) ? " - in OWL 2 DL." : " - not in OWL 2 DL.");
    }

    /** Turns ROBOT's map into ordered, grouped, labelled rows. */
    static List<Measurement> present(MeasureResult result) {
        List<Measurement> rows = new ArrayList<Measurement>();
        if (result == null) {
            return rows;
        }
        Map<String, Object> data = result.getData();
        Map<String, List<Object>> lists = result.getListData();
        Map<String, Map<String, Object>> maps = result.getMapData();
        Set<String> placed = new LinkedHashSet<String>();

        for (Object[] group : GROUPS) {
            String name = (String) group[0];
            for (String[] metric : (String[][]) group[1]) {
                String key = metric[0];
                String value = data.containsKey(key) ? text(data.get(key))
                        : lists.containsKey(key) ? joinSorted(lists.get(key))
                        : maps.containsKey(key) ? joinMap(maps.get(key)) : null;
                if (value != null) {
                    rows.add(new Measurement(name, key, metric[1], value));
                    placed.add(key);
                }
            }
        }
        // Whatever is left, in ROBOT's own order. A metric this class has not heard of is still
        // information; hiding it would make the panel quietly incomplete as ROBOT grows.
        for (String key : new ArrayList<String>(data.keySet())) {
            if (!placed.contains(key)) {
                rows.add(new Measurement(OTHER_GROUP, key, humanise(key), text(data.get(key))));
            }
        }
        // List-valued metrics (axiom types used, profile violations) matter but do not fit a
        // single cell, so they are joined rather than omitted.
        for (Map.Entry<String, List<Object>> entry : lists.entrySet()) {
            if (!placed.contains(entry.getKey())) {
                rows.add(new Measurement(OTHER_GROUP, entry.getKey(), humanise(entry.getKey()),
                        joinSorted(entry.getValue())));
            }
        }
        // And the third shape. ROBOT returns metrics in three maps, not two: simple values, lists,
        // and name-to-number breakdowns - how many of each axiom type, how many entities per
        // namespace, the CURIE map. Reading only the first two dropped eleven metrics from this
        // panel on every ontology, silently, including the axiom-type breakdown that is the most
        // informative thing measure produces. `robot measure -m all` writes all three.
        for (Map.Entry<String, Map<String, Object>> entry : maps.entrySet()) {
            if (!placed.contains(entry.getKey())) {
                rows.add(new Measurement(OTHER_GROUP, entry.getKey(), humanise(entry.getKey()),
                        joinMap(entry.getValue())));
            }
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

    /**
     * A name-to-number breakdown as one cell, each entry written the way ROBOT writes it.
     *
     * <p>{@code robot measure} gives each entry a row of its own with the name and the number in
     * one field, separated by a space - {@code AnnotationAssertion 30}. That spelling is kept so a
     * value here can be found in a metrics file a build produced. The entries are joined rather
     * than given a row each for the same reason the list-valued ones are: a breakdown with fifty
     * namespaces in it would otherwise crowd out every other metric in the panel.
     */
    private static String joinMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<Object> entries = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : new TreeMap<String, Object>(values).entrySet()) {
            entries.add(entry.getKey() + " " + text(entry.getValue()));
        }
        return join(entries);
    }

    /**
     * The entries of a set-valued metric, in a fixed order.
     *
     * <p>ROBOT collects these with {@code putSet}, so the list it hands back has no meaningful
     * order at all - it is whatever the set iterated in. Rendering that straight gave this panel a
     * different ordering of the axiom types and the OWL constructs from one run to the next, and
     * one that never matched the metrics file a build writes, because {@code robot measure} sorts
     * them. Sorting here makes the cell both stable and the same text.
     */
    private static String joinSorted(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<String>();
        for (Object value : values) {
            sorted.add(text(value));
        }
        Collections.sort(sorted);
        return join(new ArrayList<Object>(sorted));
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
            for (String[] metric : (String[][]) group[1]) {
                keys.add(metric[0]);
            }
        }
        return keys;
    }
}
