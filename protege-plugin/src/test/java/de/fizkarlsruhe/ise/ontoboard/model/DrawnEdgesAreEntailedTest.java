package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Every arrow the board draws says something the ontology entails.
 *
 * <p>Asked for by an ontology engineer after finding the board stating the opposite of a
 * definition: CheesyPizza = Pizza and (hasTopping some Cheese) drew Pizza as a subclass of
 * CheesyPizza as well, 46 times on one published ontology. Each subclass, type and existential
 * arrow is turned back into the axiom it claims and checked with HermiT, which is complete for
 * OWL 2 DL - so a drawing that says more than the ontology fails here, whatever shape caused it.
 */
class DrawnEdgesAreEntailedTest {

    private static final String NS = "http://example.org/tricky#";

    @Test
    void thePizzaOntologyIsDrawnFaithfullyInEveryVersion() throws Exception {
        for (OWLOntology version : Arrays.asList(PizzaOntology.v1(), PizzaOntology.v2(),
                PizzaOntology.v3())) {
            assertFaithful(version);
        }
    }

    /** The shapes that have been drawn wrongly before, each in one ontology. */
    @Test
    void theShapesThatWereDrawnWronglyAreDrawnFaithfully() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/tricky"));
        OWLClass pizza = df.getOWLClass(IRI.create(NS + "Pizza"));
        OWLClass cheesy = df.getOWLClass(IRI.create(NS + "CheesyPizza"));
        OWLClass cheese = df.getOWLClass(IRI.create(NS + "Cheese"));
        OWLClass topping = df.getOWLClass(IRI.create(NS + "Topping"));
        OWLClass food = df.getOWLClass(IRI.create(NS + "Food"));
        OWLClass meal = df.getOWLClass(IRI.create(NS + "Meal"));
        OWLClass dish = df.getOWLClass(IRI.create(NS + "Dish"));
        OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(cheese, topping));
        // A definition with a genus: CheesyPizza is a Pizza, not the other way round.
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(cheesy,
                df.getOWLObjectIntersectionOf(pizza, df.getOWLObjectSomeValuesFrom(hasTopping,
                        cheese))));
        // A plain equivalence: both ways round is right.
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(meal, dish));
        // A scoped domain: anything with a topping is food - not "every food has a topping".
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
                df.getOWLObjectSomeValuesFrom(hasTopping, topping), food));
        // A disjoint union, and an existential restriction.
        manager.addAxiom(o, df.getOWLDisjointUnionAxiom(food,
                new HashSet<OWLClassExpression>(Arrays.asList(pizza, topping))));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(pizza,
                df.getOWLObjectSomeValuesFrom(hasTopping, topping)));
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(pizza,
                df.getOWLNamedIndividual(IRI.create(NS + "margherita"))));

        int checked = assertFaithful(o);

        assertTrue(checked >= 8, "the arrows were read back, not skipped: " + checked);
        // And the check would have caught the arrow that started this.
        List<CanvasEdge> wrong = new ArrayList<CanvasEdge>();
        wrong.add(new CanvasEdge("sub|" + pizza.getIRI() + "|" + cheesy.getIRI(),
                pizza.getIRI().toString(), cheesy.getIRI().toString(), "",
                CanvasEdge.Kind.SUBCLASS));
        assertEquals(1, unfaithful(o, wrong, new int[1]).size());
    }

    /** Fails on any arrow the ontology does not entail; returns how many arrows were checked. */
    private static int assertFaithful(OWLOntology ontology) {
        Projection drawn = OntologyProjection.project(ontology,
                OntologyProjection.termIdentifiers(ontology, Imports.INCLUDED), true);
        int[] checked = new int[1];
        List<String> unfaithful = unfaithful(ontology, drawn.getEdges(), checked);
        assertTrue(unfaithful.isEmpty(), "arrows that say more than the ontology: " + unfaithful);
        return checked[0];
    }

    private static List<String> unfaithful(OWLOntology ontology, List<CanvasEdge> edges,
            int[] checked) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLReasoner reasoner = Reasoners.Choice.HERMIT.newFactory().createReasoner(ontology);
        List<String> unfaithful = new ArrayList<String>();
        try {
            for (CanvasEdge edge : edges) {
                OWLAxiom claimed = claimOf(edge, df, ontology);
                if (claimed == null) {
                    continue;
                }
                checked[0]++;
                if (!reasoner.isEntailed(claimed)) {
                    unfaithful.add(edge.getId() + " claims " + claimed);
                }
            }
        } finally {
            reasoner.dispose();
        }
        return unfaithful;
    }

    /**
     * The axiom an arrow claims, or null for one this test cannot read back - an arrow to an
     * expression node, a rule's part, a domain or a range, which are not entailments of this kind.
     */
    private static OWLAxiom claimOf(CanvasEdge edge, OWLDataFactory df, OWLOntology ontology) {
        String source = edge.getSourceId();
        String target = edge.getTargetId();
        if (source.startsWith(OntologyProjection.EXPRESSION_PREFIX)
                || target.startsWith(OntologyProjection.EXPRESSION_PREFIX)) {
            return null;
        }
        IRI from = IRI.create(source);
        IRI to = IRI.create(target);
        switch (edge.getKind()) {
            case SUBCLASS:
                if (ontology.containsClassInSignature(from, Imports.INCLUDED)
                        && ontology.containsClassInSignature(to, Imports.INCLUDED)) {
                    return df.getOWLSubClassOfAxiom(df.getOWLClass(from), df.getOWLClass(to));
                }
                return null;
            case TYPE:
                if (ontology.containsIndividualInSignature(from, Imports.INCLUDED)
                        && ontology.containsClassInSignature(to, Imports.INCLUDED)) {
                    return df.getOWLClassAssertionAxiom(df.getOWLClass(to),
                            df.getOWLNamedIndividual(from));
                }
                return null;
            case OBJECT_PROPERTY:
                // A plain existential: "A --∃ R--> B", read from its id.
                String property = PropertyEdgeId.propertyIn(edge.getId());
                boolean plainSome = property != null && edge.getLabel().startsWith("\u2203 ")
                        && !edge.getLabel().contains("(") && !edge.getLabel().contains(".")
                        && ontology.containsClassInSignature(from, Imports.INCLUDED)
                        && ontology.containsClassInSignature(to, Imports.INCLUDED);
                return plainSome ? df.getOWLSubClassOfAxiom(df.getOWLClass(from),
                        df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(IRI.create(property)),
                                df.getOWLClass(to))) : null;
            default:
                return null;
        }
    }
}
