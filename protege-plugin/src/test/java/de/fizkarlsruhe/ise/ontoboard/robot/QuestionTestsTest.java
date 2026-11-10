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

    /**
     * A test whose class was deleted has nothing left to break its rule.
     *
     * <p>It listed the deleted class's own IRI as breaking it: {@code rdfs:subClassOf*} matches
     * its starting IRI in zero steps whether or not the ontology says anything about it. The
     * build failed on that bare IRI from then on, and nothing in Protege removes a test.
     */
    @Test
    void aTestOnADeletedClassFindsNothing() {
        IRI deleted = IRI.create(NS + "Deleted");
        assertEquals(java.util.Collections.<String>emptyList(), broken(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, deleted, hasBase.getIRI(),
                null, 0));
        assertEquals(java.util.Collections.<String>emptyList(), broken(
                QuestionTests.Template.EVERY_ONE_HAS_A_LABEL, deleted, null, null, 0));
    }

    @Test
    void theFileIsNamedAfterTheQuestion() {
        assertEquals("does_every_pizza_have_a_base.rq",
                QuestionTests.fileNameFor("Does every pizza have a base?"));
    }

    /** A failed build tells a question's test from the label check by the query it wrote (1.135.1). */
    @Test
    void everyTemplateIsReadBackFromTheQueryItWrote() {
        IRI cls = IRI.create("http://example.org/egg#dataset");
        IRI property = IRI.create("http://example.org/egg#hasPart");
        IRI second = IRI.create("http://example.org/egg#report");
        for (QuestionTests.Template template : QuestionTests.Template.values()) {
            String query = QuestionTests.query(template, "Q?", cls, property, second, 2);
            for (String text : new String[] {query, query.replace("\n", "\r\n")}) {
                assertEquals(template, QuestionTests.templateOf(text), template.name());
                assertEquals(cls, QuestionTests.classIn(text), template.name());
            }
        }
        for (QuestionTests.Template template : new QuestionTests.Template[] {
            QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME,
            QuestionTests.Template.EVERY_INDIVIDUAL_HAS, QuestionTests.Template.NO_MORE_THAN}) {
            assertEquals(property, QuestionTests.propertyIn(
                    QuestionTests.query(template, "Q?", cls, property, null, 2)), template.name());
        }
        assertEquals(null, QuestionTests.templateOf(null));
        assertEquals(null, QuestionTests.templateOf("# Every class should carry an rdfs:label\n"
                + "SELECT ?term WHERE { ?term a owl:Class . FILTER NOT EXISTS { ?term rdfs:label ?l } }"));
    }
}
