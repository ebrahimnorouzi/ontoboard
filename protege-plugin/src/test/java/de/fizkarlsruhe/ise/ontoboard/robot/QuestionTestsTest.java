package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Each template, run on a small pizza ontology, returns exactly the terms that break its rule. */
class QuestionTestsTest {

    private static final String NS = "http://example.org/pizza#";

    private OWLOntology pizza;
    private OWLDataFactory df;
    private OWLOntologyManager manager;

    private OWLClass cls(String name, String label) {
        OWLClass c = df.getOWLClass(IRI.create(NS + name));
        manager.addAxiom(pizza, df.getOWLDeclarationAxiom(c));
        if (label != null) {
            manager.addAxiom(pizza, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    c.getIRI(), df.getOWLLiteral(label)));
        }
        return c;
    }

    private OWLNamedIndividual ind(String name, OWLClass type) {
        OWLNamedIndividual i = df.getOWLNamedIndividual(IRI.create(NS + name));
        manager.addAxiom(pizza, df.getOWLClassAssertionAxiom(type, i));
        return i;
    }

    private OWLObjectProperty hasBase;
    private OWLObjectProperty hasTopping;
    private OWLClass thing;

    @BeforeEach
    void ontology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        pizza = manager.createOntology(IRI.create("http://example.org/pizza"));
        hasBase = df.getOWLObjectProperty(IRI.create(NS + "hasBase"));
        hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        thing = cls("Pizza", "pizza");
        OWLClass base = cls("Base", "base");
        OWLClass margherita = cls("Margherita", "margherita");
        OWLClass unlabelled = cls("Unlabelled", null);
        OWLClass meaty = cls("Meaty", "meaty");
        OWLClass veggie = cls("Veggie", "veggie");
        manager.addAxiom(pizza, df.getOWLSubClassOfAxiom(margherita, thing));
        manager.addAxiom(pizza, df.getOWLSubClassOfAxiom(unlabelled, thing));
        manager.addAxiom(pizza, df.getOWLSubClassOfAxiom(margherita,
                df.getOWLObjectSomeValuesFrom(hasBase, base)));
        OWLNamedIndividual one = ind("one", margherita);
        OWLNamedIndividual two = ind("two", thing);
        manager.addAxiom(pizza, df.getOWLObjectPropertyAssertionAxiom(hasBase, one,
                ind("b1", base)));
        manager.addAxiom(pizza, df.getOWLClassAssertionAxiom(meaty, two));
        manager.addAxiom(pizza, df.getOWLClassAssertionAxiom(veggie, two));
        manager.addAxiom(pizza, df.getOWLObjectPropertyAssertionAxiom(hasTopping, one,
                ind("t1", base)));
        manager.addAxiom(pizza, df.getOWLObjectPropertyAssertionAxiom(hasTopping, one,
                ind("t2", base)));
    }

    private List<String> broken(QuestionTests.Template template, IRI cls, IRI property,
            IRI second, int most) {
        String sparql = QuestionTests.query(template, "Q?", cls, property, second, most);
        assertTrue(sparql.startsWith("# Q?\n"), sparql);
        List<String> terms = new ArrayList<String>();
        for (List<String> row : SparqlQuery.run(pizza, sparql).getRows()) {
            terms.add(row.get(0).replace(NS, ""));
        }
        Collections.sort(terms);
        return terms;
    }

    @Test
    void everyKindHasSomeFindsTheClassesThatDoNotSaySo() {
        assertEquals(java.util.Arrays.asList("Pizza", "Unlabelled"), broken(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, thing.getIRI(),
                hasBase.getIRI(), null, 0));
    }

    @Test
    void everyIndividualHasFindsTheDataWithoutAValue() {
        assertEquals(java.util.Arrays.asList("two"), broken(
                QuestionTests.Template.EVERY_INDIVIDUAL_HAS, thing.getIRI(), hasBase.getIRI(),
                null, 0));
    }

    @Test
    void nothingIsBothFindsWhatIs() {
        assertEquals(java.util.Arrays.asList("two"), broken(
                QuestionTests.Template.NOTHING_IS_BOTH, IRI.create(NS + "Meaty"), null,
                IRI.create(NS + "Veggie"), 0));
    }

    @Test
    void everyOneHasALabelFindsTheUnlabelled() {
        assertEquals(java.util.Arrays.asList("Unlabelled", "one", "two"), broken(
                QuestionTests.Template.EVERY_ONE_HAS_A_LABEL, thing.getIRI(), null, null, 0));
    }

    @Test
    void noMoreThanFindsTooMany() {
        assertEquals(java.util.Arrays.asList("one"), broken(QuestionTests.Template.NO_MORE_THAN,
                thing.getIRI(), hasTopping.getIRI(), null, 1));
        assertTrue(broken(QuestionTests.Template.NO_MORE_THAN, thing.getIRI(),
                hasTopping.getIRI(), null, 2).isEmpty());
    }

    @Test
    void theFileIsNamedAfterTheQuestion() {
        assertEquals("does_every_pizza_have_a_base.rq",
                QuestionTests.fileNameFor("Does every pizza have a base?"));
    }
}
