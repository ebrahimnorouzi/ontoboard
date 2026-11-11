package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.HierarchyColumns;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
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
 * A class defined as "a pizza and has topping some cheese topping" stands under pizza - in pizza's
 * frame when pizza stands in one - as a class stated to be a subclass of pizza does (1.135.2).
 *
 * <p>Found on the pizza and pizza-bfo boards after Arrange &gt; Outline: cheesy pizza, meaty pizza,
 * spicy pizza and spicy topping stood apart at the bottom left, below every frame, their lines
 * running a thousand pixels and more up the board. The board drew their is-a line to pizza from the
 * definition, but where a class stands was decided from SubClassOf(C, A) axioms only, so a class
 * whose superclass is named in its definition - or in an intersection it is a subclass of - had
 * none, and the outline put it among the classes with no superclass at all.
 */
class DefinedClassStandsUnderItsGenusTest {

    private static final String NS = "http://example.org/pizza#";

    private OWLOntologyManager manager;
    private OWLDataFactory df;
    private OWLOntology pizza;
    private OWLObjectProperty hasTopping;

    private OWLClass cls(String name) {
        OWLClass c = df.getOWLClass(IRI.create(NS + name));
        manager.addAxiom(pizza, df.getOWLDeclarationAxiom(c));
        return c;
    }

    private String iri(String name) {
        return NS + name;
    }

    @BeforeEach
    void ontology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        pizza = manager.createOntology(IRI.create("http://example.org/pizza"));
        hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        manager.addAxiom(pizza, df.getOWLDeclarationAxiom(hasTopping));
        OWLClass food = cls("Food");
        OWLClass pizzaClass = cls("Pizza");
        OWLClass namedPizza = cls("NamedPizza");
        OWLClass cheese = cls("CheeseTopping");
        OWLClass tomato = cls("TomatoTopping");
        manager.addAxiom(pizza, df.getOWLSubClassOfAxiom(pizzaClass, food));
        manager.addAxiom(pizza, df.getOWLSubClassOfAxiom(namedPizza, pizzaClass));
        // A definition: its genus is Pizza.
        manager.addAxiom(pizza, df.getOWLEquivalentClassesAxiom(cls("CheesyPizza"),
                df.getOWLObjectIntersectionOf(pizzaClass,
                        df.getOWLObjectSomeValuesFrom(hasTopping, cheese))));
        // A subclass of an intersection: NamedPizza is named in it.
        manager.addAxiom(pizza, df.getOWLSubClassOfAxiom(cls("Margherita"),
                df.getOWLObjectIntersectionOf(namedPizza,
                        df.getOWLObjectSomeValuesFrom(hasTopping, tomato))));
    }

    @Test
    void aDefinitionsGenusIsASuperclassOfTheClassItDefines() {
        assertEquals(Arrays.asList(iri("Pizza")),
                OntologyProjection.namedSuperclasses(pizza, iri("CheesyPizza")));
    }

    @Test
    void aClassNamedInAnIntersectionItIsASubclassOfIsASuperclass() {
        assertEquals(Arrays.asList(iri("NamedPizza")),
                OntologyProjection.namedSuperclasses(pizza, iri("Margherita")));
    }

    @Test
    void definedAndIntersectedClassesStandInTheFrameTheirSuperclassStandsIn() {
        Map<String, String> under = OntologyProjection.ownClassesUnder(pizza,
                new HashSet<String>(Collections.singletonList(iri("Pizza"))));

        assertEquals(iri("Pizza"), under.get(iri("CheesyPizza")), "cheesy pizza under pizza");
        assertEquals(iri("Pizza"), under.get(iri("Margherita")), "margherita, through named pizza");
    }

    /**
     * In the frame, drawn as a tree under its genus. Found live after the first fix: on the
     * pizza-bfo board the defined pizzas stood in pizza's frame, but in a column at its right
     * edge, 1,800 px from pizza, their is-a lines across the frame - the frame's tree read only
     * SubClassOf(C, A).
     */
    @Test
    void inAFrameADefinedClassIsDrawnUnderItsGenus() {
        Map<String, String> parents = OntologyProjection.superclassesAmong(pizza, Arrays.asList(
                iri("Pizza"), iri("NamedPizza"), iri("CheesyPizza"), iri("Margherita")));

        assertEquals(iri("Pizza"), parents.get(iri("CheesyPizza")), "cheesy pizza under pizza");
        assertEquals(iri("NamedPizza"), parents.get(iri("Margherita")),
                "margherita under named pizza");
    }

    /** A defined class new to a board arranged by hand goes under its genus, as a subclass does. */
    @Test
    void aDefinedClassNewToTheBoardGoesUnderItsGenus() {
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(iri("Pizza"), new CanvasLayout.NodeLayout(400, 300));

        double[] spot = OntologyProjection.besideItsParent(pizza, iri("CheesyPizza"), layout);

        assertNotNull(spot, "placed by its genus");
        assertEquals(400 + HierarchyColumns.INDENT, spot[0], 0.001, "indented under pizza");
        assertTrue(spot[1] >= 300 + 60, "below pizza");
    }

    /**
     * Two named classes said to be equivalent are not one above the other, and a union names
     * classes below the one it defines, not above it: neither gives a superclass.
     */
    @Test
    void aNamedEquivalenceAndAUnionGiveNoSuperclass() {
        OWLClass a = cls("A");
        OWLClass b = cls("B");
        manager.addAxiom(pizza, df.getOWLEquivalentClassesAxiom(a, b));
        OWLClass either = cls("Either");
        manager.addAxiom(pizza, df.getOWLEquivalentClassesAxiom(either,
                df.getOWLObjectUnionOf(cls("Left"), cls("Right"))));

        assertEquals(Collections.<String>emptyList(), OntologyProjection.namedSuperclasses(pizza,
                iri("A")));
        assertEquals(Collections.<String>emptyList(), OntologyProjection.namedSuperclasses(pizza,
                iri("Either")));
    }
}
