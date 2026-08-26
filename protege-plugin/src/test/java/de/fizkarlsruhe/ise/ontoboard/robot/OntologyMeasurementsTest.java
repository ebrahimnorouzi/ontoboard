package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.obolibrary.robot.metrics.MeasureResult;
import org.obolibrary.robot.metrics.MetricsLabels;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * ROBOT's measure, run against a real ontology rather than a stub.
 *
 * <p>Two things are worth asserting and neither is obvious. First, that the operation runs at all
 * on the OWL API this bundle compiles against - {@code report} does not, and the reason measure
 * should is that it never touches the RDF layer, which is a claim about a code path rather than a
 * fact until it is executed. Second, that the presentation drops nothing: a grouping table that
 * silently hides metrics ROBOT has started returning would make the panel quietly incomplete, and
 * nobody would notice.
 */
class OntologyMeasurementsTest {

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntologyWithSomethingToMeasure() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();

        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://example.org/o#Dog")),
                factory.getOWLClass(IRI.create("http://example.org/o#Animal"))));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://example.org/o#Cat")),
                factory.getOWLClass(IRI.create("http://example.org/o#Animal"))));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://example.org/o#Dog")),
                factory.getOWLObjectSomeValuesFrom(
                        factory.getOWLObjectProperty(IRI.create("http://example.org/o#ownedBy")),
                        factory.getOWLClass(IRI.create("http://example.org/o#Person")))));
        manager.addAxiom(ontology, factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(IRI.create("http://example.org/o#Dog")),
                factory.getOWLNamedIndividual(IRI.create("http://example.org/o#rex"))));
    }

    private static Map<String, String> byLabel(List<OntologyMeasurements.Measurement> rows) {
        Map<String, String> found = new LinkedHashMap<String, String>();
        for (OntologyMeasurements.Measurement row : rows) {
            found.put(row.getLabel(), row.getValue());
        }
        return found;
    }

    private static List<String> groupsOf(List<OntologyMeasurements.Measurement> rows) {
        List<String> groups = new ArrayList<String>();
        for (OntologyMeasurements.Measurement row : rows) {
            if (!groups.contains(row.getGroup())) {
                groups.add(row.getGroup());
            }
        }
        return groups;
    }

    // ---------- it actually runs ----------

    /**
     * The claim that justifies offering measure on Protege 5.5.0 where report fails. Executed
     * rather than reasoned about.
     */
    @Test
    void measureRunsAgainstTheOwlApiThisBundleCompilesAgainst() {
        List<OntologyMeasurements.Measurement> rows =
                OntologyMeasurements.run(ontology, OntologyMeasurements.Depth.ESSENTIAL);

        assertFalse(rows.isEmpty(), "ROBOT returned no metrics at all");
    }

    @Test
    void theCountsMatchTheOntologyItWasGiven() {
        Map<String, String> found = byLabel(
                OntologyMeasurements.run(ontology, OntologyMeasurements.Depth.ESSENTIAL));

        assertEquals("4", found.get("Class count"),
                "Dog, Cat, Animal and Person - ROBOT counts declared classes and does not "
                        + "include owl:Thing");
        assertEquals("1", found.get("Individual count"));
        assertEquals("1", found.get("Obj property count"));
        assertEquals("4", found.get("Logical axiom count"));
    }

    @Test
    void everyDepthProducesSomething() {
        for (OntologyMeasurements.Depth depth : OntologyMeasurements.Depth.values()) {
            assertFalse(OntologyMeasurements.run(ontology, depth).isEmpty(),
                    depth + " produced no metrics");
        }
    }

    @Test
    void extendedMeasuresAtLeastAsMuchAsEssential() {
        int essential = OntologyMeasurements
                .run(ontology, OntologyMeasurements.Depth.ESSENTIAL).size();
        int extended = OntologyMeasurements
                .run(ontology, OntologyMeasurements.Depth.EXTENDED).size();

        assertTrue(extended >= essential,
                "extended returned fewer metrics (" + extended + ") than essential ("
                        + essential + ")");
    }

    @Test
    void anEmptyOntologyMeasuresWithoutFailing() throws Exception {
        OWLOntology empty = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/empty"));

        assertFalse(OntologyMeasurements.run(empty, OntologyMeasurements.Depth.ESSENTIAL)
                .isEmpty());
    }

    @Test
    void noOntologyIsRejectedRatherThanReturningAnEmptyTable() {
        assertThrows(IllegalArgumentException.class,
                () -> OntologyMeasurements.run(null, OntologyMeasurements.Depth.ESSENTIAL));
    }

    // ---------- presentation ----------

    /**
     * The property that keeps the panel honest as ROBOT grows: every key ROBOT returns appears
     * somewhere, whether or not this class has a group for it.
     */
    @Test
    void everyMetricRobotReturnsIsShown() {
        MeasureResult result = new MeasureResult();
        result.put(MetricsLabels.CLASS_COUNT, 12);
        result.put(MetricsLabels.EXPRESSIVITY, "ALC");
        result.put("a_metric_from_a_future_robot", 99);

        List<OntologyMeasurements.Measurement> rows = OntologyMeasurements.present(result);

        Set<String> labels = new HashSet<String>(byLabel(rows).keySet());
        assertTrue(labels.contains("Class count"), labels.toString());
        assertTrue(labels.contains("Expressivity"), labels.toString());
        assertTrue(labels.contains("A metric from a future robot"),
                "an unknown metric was dropped instead of shown under Other: " + labels);
        assertEquals(3, rows.size());
    }

    @Test
    void anUnknownMetricGoesUnderOtherAndAKnownOneDoesNot() {
        MeasureResult result = new MeasureResult();
        result.put(MetricsLabels.CLASS_COUNT, 1);
        result.put("something_new", 2);

        for (OntologyMeasurements.Measurement row : OntologyMeasurements.present(result)) {
            if (row.getLabel().equals("Class count")) {
                assertEquals("Entities", row.getGroup());
            } else {
                assertEquals(OntologyMeasurements.OTHER_GROUP, row.getGroup());
            }
        }
    }

    /**
     * Order has to be the group order, not the map's. ROBOT hands back a HashMap, so without this
     * the panel's rows would reshuffle between runs on the same ontology.
     */
    @Test
    void groupsAppearInDisplayOrderRegardlessOfWhatOrderRobotUsed() {
        MeasureResult result = new MeasureResult();
        result.put(MetricsLabels.UNDECLARED_ENTITY_COUNT, 0);
        result.put(MetricsLabels.CLASS_COUNT, 1);
        result.put(MetricsLabels.AXIOM_COUNT, 2);

        assertEquals(java.util.Arrays.asList("Size", "Entities", "Warnings"),
                groupsOf(OntologyMeasurements.present(result)));
    }

    @Test
    void theSameOntologyMeasuresToTheSameRowsInTheSameOrder() {
        List<OntologyMeasurements.Measurement> first =
                OntologyMeasurements.run(ontology, OntologyMeasurements.Depth.ESSENTIAL);
        List<OntologyMeasurements.Measurement> second =
                OntologyMeasurements.run(ontology, OntologyMeasurements.Depth.ESSENTIAL);

        assertEquals(first.toString(), second.toString(),
                "a table that reorders between runs looks like the ontology changed");
    }

    @Test
    void listValuedMetricsAreJoinedRatherThanOmitted() {
        MeasureResult result = new MeasureResult();
        Set<String> types = new java.util.LinkedHashSet<String>();
        types.add("SubClassOf");
        types.add("ClassAssertion");
        result.putSet(MetricsLabels.AXIOM_TYPES, types);

        Map<String, String> found = byLabel(OntologyMeasurements.present(result));
        assertNotNull(found.get("Axiom types"), found.toString());
        assertTrue(found.get("Axiom types").contains("SubClassOf"), found.toString());
        assertTrue(found.get("Axiom types").contains("ClassAssertion"), found.toString());
    }

    @Test
    void anEmptyResultProducesNoRowsRatherThanThrowing() {
        assertTrue(OntologyMeasurements.present(new MeasureResult()).isEmpty());
        assertTrue(OntologyMeasurements.present(null).isEmpty());
    }

    // ---------- labels ----------

    @Test
    void machineKeysBecomeReadableLabels() {
        assertEquals("Tbox axiom count", OntologyMeasurements.humanise("tbox_axiom_count"));
        assertEquals("Class count", OntologyMeasurements.humanise("class_count"));
        assertEquals("Owl2 dl", OntologyMeasurements.humanise("owl2_dl"));
        assertEquals("", OntologyMeasurements.humanise(""));
        assertEquals("", OntologyMeasurements.humanise(null));
    }

    /** No group may name a key that ROBOT does not have; a typo would hide the metric forever. */
    @Test
    void everyGroupedKeyIsARealRobotMetricKey() {
        MeasureResult everything =
                new org.obolibrary.robot.metrics.OntologyMetrics(ontology).getAllMetrics();
        Set<String> real = new HashSet<String>(everything.getData().keySet());
        real.addAll(everything.getListData().keySet());
        real.addAll(everything.getMapData().keySet());

        List<String> unknown = new ArrayList<String>();
        for (String key : OntologyMeasurements.groupedKeys()) {
            if (!real.contains(key)) {
                unknown.add(key);
            }
        }
        assertTrue(unknown.isEmpty(),
                "these grouped keys are never produced by ROBOT for any ontology, so the metric "
                        + "they were meant to place would fall into Other forever: " + unknown);
    }

    @Test
    void theGroupOrderIsPublishedForAPanelToUse() {
        List<String> order = OntologyMeasurements.groupOrder();

        assertEquals("Size", order.get(0));
        assertEquals(OntologyMeasurements.OTHER_GROUP, order.get(order.size() - 1),
                "Other belongs last, or unknown metrics would push known ones down");
    }
}
