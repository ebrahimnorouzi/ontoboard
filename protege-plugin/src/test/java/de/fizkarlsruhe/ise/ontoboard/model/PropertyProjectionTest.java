package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Properties as nodes, and the property hierarchy between them.
 *
 * <p>None of this existed: {@code CanvasEdge.Kind} had no case for {@code rdfs:subPropertyOf} and
 * {@code NodeKind} had no case for a property, so a property hierarchy was invisible on a canvas
 * that drew the class hierarchy prominently. Somebody looking for it would have concluded their
 * ontology had none.
 *
 * <p>The behaviour most worth pinning is the opt-in rule. A property arrives on the board only
 * because a user put it there, and an edge appears only when both of its ends are present -
 * otherwise showing a property hierarchy would quietly drag unrequested nodes onto a diagram that
 * is deliberately not a picture of the whole ontology.
 */
class PropertyProjectionTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void aPropertyHierarchy() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();

        manager.addAxiom(ontology, factory.getOWLSubObjectPropertyOfAxiom(
                objectProperty("worksAt"), objectProperty("employedBy")));
        manager.addAxiom(ontology, factory.getOWLSubDataPropertyOfAxiom(
                dataProperty("birthYear"), dataProperty("year")));
    }

    private OWLObjectProperty objectProperty(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private OWLDataProperty dataProperty(String name) {
        return factory.getOWLDataProperty(IRI.create(NS + name));
    }

    private static Set<String> onCanvas(String... names) {
        Set<String> iris = new HashSet<String>();
        for (String name : names) {
            iris.add(NS + name);
        }
        return iris;
    }

    private Projection project(String... onCanvasNames) {
        return OntologyProjection.project(ontology, onCanvas(onCanvasNames));
    }

    private static List<String> nodeKinds(Projection projection, NodeKind kind) {
        List<String> found = new ArrayList<String>();
        for (CanvasNode node : projection.getNodes()) {
            if (node.getKind() == kind) {
                found.add(node.getId().replace(NS, ""));
            }
        }
        Collections.sort(found);
        return found;
    }

    private static List<String> edgesOfKind(Projection projection, CanvasEdge.Kind kind) {
        List<String> found = new ArrayList<String>();
        for (CanvasEdge edge : projection.getEdges()) {
            if (edge.getKind() == kind) {
                found.add(edge.getSourceId().replace(NS, "") + " -> "
                        + edge.getTargetId().replace(NS, ""));
            }
        }
        Collections.sort(found);
        return found;
    }

    // ---------- property nodes ----------

    @Test
    void anObjectPropertyOnTheBoardBecomesANode() {
        Projection projection = project("worksAt");

        assertEquals(Arrays.asList("worksAt"),
                nodeKinds(projection, NodeKind.OBJECT_PROPERTY));
    }

    @Test
    void aDataPropertyGetsItsOwnKindRatherThanBeingLumpedInWithObjectProperties() {
        Projection projection = project("birthYear");

        assertEquals(Arrays.asList("birthYear"),
                nodeKinds(projection, NodeKind.DATA_PROPERTY));
        assertTrue(nodeKinds(projection, NodeKind.OBJECT_PROPERTY).isEmpty(),
                "the two are drawn differently, so conflating them would mislead");
    }

    /** The rule the whole canvas is built on: nothing appears unless it was asked for. */
    @Test
    void aPropertyNotOnTheBoardIsNotDrawn() {
        Projection projection = project("worksAt");

        assertFalse(nodeKinds(projection, NodeKind.OBJECT_PROPERTY).contains("employedBy"),
                "employedBy was never added to the board");
    }

    @Test
    void anEmptyBoardProjectsNoPropertyNodes() {
        Projection projection = project();

        assertTrue(nodeKinds(projection, NodeKind.OBJECT_PROPERTY).isEmpty());
        assertTrue(nodeKinds(projection, NodeKind.DATA_PROPERTY).isEmpty());
    }

    // ---------- the hierarchy edge ----------

    @Test
    void subPropertyOfIsDrawnWhenBothPropertiesAreOnTheBoard() {
        Projection projection = project("worksAt", "employedBy");

        assertEquals(Arrays.asList("worksAt -> employedBy"),
                edgesOfKind(projection, CanvasEdge.Kind.SUB_PROPERTY));
    }

    @Test
    void aDataPropertyHierarchyIsDrawnToo() {
        Projection projection = project("birthYear", "year");

        assertEquals(Arrays.asList("birthYear -> year"),
                edgesOfKind(projection, CanvasEdge.Kind.SUB_PROPERTY));
    }

    /**
     * Half a hierarchy is not drawn. Drawing the edge anyway would need a node for the missing end,
     * and inventing one would put an entity on the diagram that nobody asked for.
     */
    @Test
    void noEdgeAppearsWhenOnlyOneEndIsOnTheBoard() {
        assertTrue(edgesOfKind(project("worksAt"), CanvasEdge.Kind.SUB_PROPERTY).isEmpty());
        assertTrue(edgesOfKind(project("employedBy"), CanvasEdge.Kind.SUB_PROPERTY).isEmpty());
    }

    @Test
    void theEdgeIsLabelledWithTheConstructItStandsFor() {
        for (CanvasEdge edge : project("worksAt", "employedBy").getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.SUB_PROPERTY) {
                assertEquals("rdfs:subPropertyOf", edge.getLabel(),
                        "the label is what tells a reader which relation this is");
            }
        }
    }

    /**
     * The id has to name the axiom precisely enough for AxiomRemoval to retract exactly it, in the
     * same shape as the other edge ids.
     */
    @Test
    void theEdgeIdIdentifiesTheAxiomItDraws() {
        List<String> ids = new ArrayList<String>();
        for (CanvasEdge edge : project("worksAt", "employedBy").getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.SUB_PROPERTY) {
                ids.add(edge.getId());
            }
        }
        assertEquals(Arrays.asList("subprop|" + NS + "worksAt|" + NS + "employedBy"), ids);
    }

    /**
     * An inverse has no IRI, so there is nothing to draw and nothing to identify an edge by.
     * Skipping it must not cost the well-formed axioms in the same ontology.
     */
    @Test
    void anAnonymousPropertyExpressionIsSkippedWithoutLosingTheRest() {
        manager.addAxiom(ontology, factory.getOWLSubObjectPropertyOfAxiom(
                factory.getOWLObjectInverseOf(objectProperty("worksAt")),
                objectProperty("employedBy")));

        assertEquals(Arrays.asList("worksAt -> employedBy"),
                edgesOfKind(project("worksAt", "employedBy"), CanvasEdge.Kind.SUB_PROPERTY),
                "the inverse contributes nothing and the named axiom still appears");
    }

    // ---------- it does not disturb what was there ----------

    @Test
    void classDiagramsAreUnaffectedByPropertiesExisting() {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create(NS + "Dog")),
                factory.getOWLClass(IRI.create(NS + "Animal"))));

        Projection projection = project("Dog", "Animal");

        assertEquals(Arrays.asList("Dog -> Animal"),
                edgesOfKind(projection, CanvasEdge.Kind.SUBCLASS));
        assertTrue(nodeKinds(projection, NodeKind.OBJECT_PROPERTY).isEmpty(),
                "a class diagram should not gain property nodes it did not ask for");
    }
}
