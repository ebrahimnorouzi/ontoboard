package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The shapes that had nowhere to point, and now have a node to point at.
 *
 * <p>An arrow needs a box at each end. An anonymous class expression has no IRI, so there was no
 * box, so {@code A SubClassOf (R some (B or C))} drew nothing at all - and nor did a union, a
 * complement, an enumeration or a restriction on an inverse property. Measured over MWO 3.0.1:
 * 39 of its 116 restriction occurrences, 34%, were invisible, and the diagram said nothing about
 * it. These are the cases, one test each, because each was a separate silent drop.
 *
 * <p>Every test asserts BOTH directions: nothing is drawn with expressions off, so the default
 * for the two-argument {@code project} is unchanged and the boards the other tests describe are
 * the boards they were written against.
 */
class ExpressionNodesTest {

    private static final String NS = "http://example.org/expr#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology ontology;

    @BeforeEach
    void emptyOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/expr"));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private void add(org.semanticweb.owlapi.model.OWLAxiom axiom) {
        manager.addAxiom(ontology, axiom);
    }

    private Set<String> board(String... names) {
        Set<String> on = new HashSet<String>();
        for (String name : names) {
            on.add(NS + name);
        }
        return on;
    }

    private List<CanvasNode> expressionsIn(Projection projection) {
        List<CanvasNode> found = new ArrayList<CanvasNode>();
        for (CanvasNode node : projection.getNodes()) {
            if (node.getKind() == NodeKind.EXPRESSION) {
                found.add(node);
            }
        }
        return found;
    }

    private List<String> labelsOfExpressionsIn(Projection projection) {
        List<String> labels = new ArrayList<String>();
        for (CanvasNode node : expressionsIn(projection)) {
            labels.add(node.getLabel());
        }
        return labels;
    }

    /** {@code A subClassOf (B or C)} - a union, which used to draw nothing. */
    @Test
    void aUnionGetsANodeAndItsOperandsHangOffIt() {
        add(factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectUnionOf(cls("B"), cls("C"))));
        Set<String> on = board("A", "B", "C");

        assertEquals(0, expressionsIn(OntologyProjection.project(ontology, on)).size(),
                "with expressions off, a union is still not drawn");

        Projection drawn = OntologyProjection.project(ontology, on, true);
        assertEquals(Arrays.asList("\u2294"), labelsOfExpressionsIn(drawn));
        assertEquals(3, drawn.getEdges().size(),
                "A to the union, and the union to each of B and C: " + idsOf(drawn));
    }

    /** {@code A subClassOf (not B)} - a negation, which an arrow cannot express on its own. */
    @Test
    void aComplementGetsANode() {
        add(factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectComplementOf(cls("B"))));
        Set<String> on = board("A", "B");

        assertEquals(0, expressionsIn(OntologyProjection.project(ontology, on)).size());

        Projection drawn = OntologyProjection.project(ontology, on, true);
        assertEquals(Arrays.asList("\u00AC"), labelsOfExpressionsIn(drawn));
        assertEquals(2, drawn.getEdges().size(), idsOf(drawn).toString());
    }

    /**
     * {@code A equivalentTo {x, y}} - an enumeration.
     *
     * <p>Its members are individuals, so the edges out of the node are rdf:type edges rather than
     * subclass ones: the node IS the class whose instances they are.
     */
    @Test
    void anEnumerationPointsAtItsIndividuals() {
        add(factory.getOWLEquivalentClassesAxiom(cls("A"),
                factory.getOWLObjectOneOf(
                        factory.getOWLNamedIndividual(IRI.create(NS + "x")),
                        factory.getOWLNamedIndividual(IRI.create(NS + "y")))));
        Set<String> on = board("A", "x", "y");

        assertEquals(0, expressionsIn(OntologyProjection.project(ontology, on)).size());

        Projection drawn = OntologyProjection.project(ontology, on, true);
        assertEquals(Arrays.asList("{ \u2026 }"), labelsOfExpressionsIn(drawn));
        int types = 0;
        for (CanvasEdge edge : drawn.getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.TYPE) {
                types++;
            }
        }
        assertEquals(2, types, "one per member: " + idsOf(drawn));
    }

    /**
     * {@code A subClassOf (R some (B or C))} - the shape the report named, and the hardest one.
     *
     * <p>The restriction keeps its arrow and its label; what changes is that the arrow now has
     * somewhere to land. Before, {@code objectEdge} returned the moment it saw an anonymous
     * filler and the whole axiom vanished.
     */
    @Test
    void aRestrictionWithAnAnonymousFillerKeepsItsArrowAndGainsATarget() {
        add(factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectSomeValuesFrom(property("R"),
                        factory.getOWLObjectUnionOf(cls("B"), cls("C")))));
        Set<String> on = board("A", "B", "C");

        assertEquals(0, OntologyProjection.project(ontology, on).getEdges().size(),
                "with expressions off this axiom draws nothing at all, as it always did");

        Projection drawn = OntologyProjection.project(ontology, on, true);
        assertEquals(Arrays.asList("\u2294"), labelsOfExpressionsIn(drawn));
        CanvasEdge arrow = null;
        for (CanvasEdge edge : drawn.getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.OBJECT_PROPERTY) {
                arrow = edge;
            }
        }
        assertNotNull(arrow, "the restriction's own arrow: " + idsOf(drawn));
        assertEquals("\u2203 R", arrow.getLabel());
        assertTrue(arrow.getTargetId().startsWith(OntologyProjection.EXPRESSION_PREFIX),
                "it points at the expression node: " + arrow.getTargetId());
    }

    /**
     * A restriction on an inverse property, which was dropped for the other half of the same
     * guard - {@code property.isAnonymous()}.
     */
    @Test
    void anInversePropertyRestrictionIsDrawnAndSaysSo() {
        add(factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectSomeValuesFrom(
                        factory.getOWLObjectInverseOf(property("R")), cls("B"))));
        Set<String> on = board("A", "B");

        assertEquals(0, OntologyProjection.project(ontology, on).getEdges().size());

        Projection drawn = OntologyProjection.project(ontology, on, true);
        assertEquals(1, drawn.getEdges().size(), idsOf(drawn).toString());
        assertEquals("\u2203 R\u207B", drawn.getEdges().get(0).getLabel(),
                "a reader has to be able to tell which way round it is");
        assertEquals(0, expressionsIn(drawn).size(),
                "the filler is named, so no scaffolding node is needed for it");
    }

    /**
     * The same expression twice is the same node, and the id does not move between runs.
     *
     * <p>Minting them in encounter order would renumber the whole board whenever an axiom was
     * added above them, which loses every saved position and every selection.
     */
    @Test
    void theSameExpressionIsOneNodeAndKeepsItsIdentity() {
        add(factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectUnionOf(cls("B"), cls("C"))));
        add(factory.getOWLSubClassOfAxiom(cls("D"),
                factory.getOWLObjectUnionOf(cls("B"), cls("C"))));
        Set<String> on = board("A", "B", "C", "D");

        Projection once = OntologyProjection.project(ontology, on, true);
        Projection twice = OntologyProjection.project(ontology, on, true);

        assertEquals(1, expressionsIn(once).size(),
                "one expression, written twice, is one node: " + labelsOfExpressionsIn(once));
        assertEquals(expressionsIn(once).get(0).getId(), expressionsIn(twice).get(0).getId(),
                "and the id is the same on the next render");
    }

    /** An expression node is not a term: nothing may mistake its id for an IRI. */
    @Test
    void anExpressionNodeIsNotATerm() {
        add(factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectUnionOf(cls("B"), cls("C"))));

        Projection drawn = OntologyProjection.project(ontology, board("A", "B", "C"), true);
        String id = expressionsIn(drawn).get(0).getId();

        assertTrue(id.startsWith(OntologyProjection.EXPRESSION_PREFIX), id);
        assertFalse(id.startsWith("http"), "not an IRI, so AxiomRemoval can refuse it by name");
        assertEquals(null, PropertyEdgeId.propertyIn(id),
                "and it stands for no property, so a click on it selects nothing");
    }

    private List<String> idsOf(Projection projection) {
        List<String> ids = new ArrayList<String>();
        for (CanvasEdge edge : projection.getEdges()) {
            ids.add(edge.getId());
        }
        return ids;
    }
}
