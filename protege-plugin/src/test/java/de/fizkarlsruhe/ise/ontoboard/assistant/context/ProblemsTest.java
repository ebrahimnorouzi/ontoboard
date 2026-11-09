package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The reasoner problem a domain expert arrives with, as a context item: an inconsistent ontology
 * or an unsatisfiable class, with the justifications {@code Explanations} computes.
 */
class ProblemsTest {

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLOntologyManager manager = pizza.getOWLOntologyManager();
    private final OWLDataFactory factory = manager.getOWLDataFactory();
    private final OWLReasonerFactory hermit = Reasoners.Choice.HERMIT.newFactory();

    private OWLClass cls(String name) {
        return factory.getOWLClass(MiniPizza.iri(name));
    }

    @Test
    void anInconsistentOntologyIsExplained() {
        OWLNamedIndividual x = factory.getOWLNamedIndividual(MiniPizza.iri("x"));
        manager.addAxiom(pizza, factory.getOWLClassAssertionAxiom(cls("VegetarianTopping"), x));
        manager.addAxiom(pizza, factory.getOWLClassAssertionAxiom(cls("MeatTopping"), x));

        Explanations.Result result = Problems.diagnose(pizza, hermit);
        ContextItem item = Problems.inconsistency(pizza, result, "HermiT");

        assertFalse(result.isConsistent());
        assertEquals(ContextItem.Kind.PROBLEM, item.getKind());
        assertEquals("Why the ontology is inconsistent", item.getTitle());
        assertTrue(item.getText().startsWith("Reasoner: HermiT."), item.getText());
        assertTrue(item.getText().contains("VegetarianTopping"), item.getText());
        assertTrue(item.getText().contains("MeatTopping"), item.getText());
    }

    @Test
    void anUnsatisfiableClassIsExplained() {
        OWLClass bad = cls("Bad");
        manager.addAxiom(pizza, factory.getOWLSubClassOfAxiom(bad, cls("VegetarianTopping")));
        manager.addAxiom(pizza, factory.getOWLSubClassOfAxiom(bad, cls("MeatTopping")));

        ContextItem item = Problems.unsatisfiable(pizza, hermit, bad, "HermiT");

        assertEquals("Why Bad is unsatisfiable", item.getTitle());
        assertTrue(item.getText().contains("Justification 1"), item.getText());
        assertTrue(item.getText().contains("DisjointWith")
                || item.getText().contains("DisjointClasses"), item.getText());
    }

    /** The pizza example's own clash, small: a float range and a decimal value. */
    @Test
    void theJustificationShowsTheLiteralsType() {
        org.semanticweb.owlapi.model.OWLDataProperty price =
                factory.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        manager.addAxiom(pizza, factory.getOWLDataPropertyRangeAxiom(price,
                factory.getOWLDatatype(
                        org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_FLOAT.getIRI())));
        manager.addAxiom(pizza, factory.getOWLDataPropertyAssertionAxiom(price,
                factory.getOWLNamedIndividual(MiniPizza.iri("Naples")),
                factory.getOWLLiteral("9.50",
                        org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_DECIMAL)));

        ContextItem item = Problems.inconsistency(pizza, Problems.diagnose(pizza, hermit),
                "HermiT");

        assertTrue(item.getText().contains("\"9.50\"^^xsd:decimal"), item.getText());
        assertTrue(item.getText().contains("xsd:float"), item.getText());
    }

    /**
     * The live run on 1.137.0: qwen2.5:7b read '"11.00"^^xsd:decimal' against 'Range: xsd:float'
     * and said a float "should be able to accommodate" decimals. OntoBoard states the fact the
     * reasoner applied.
     */
    @Test
    void aDecimalAgainstAFloatRangeIsStatedAsAFact() {
        org.semanticweb.owlapi.model.OWLDataProperty price =
                factory.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        manager.addAxiom(pizza, factory.getOWLDataPropertyRangeAxiom(price,
                factory.getOWLDatatype(
                        org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_FLOAT.getIRI())));
        manager.addAxiom(pizza, factory.getOWLDataPropertyAssertionAxiom(price,
                factory.getOWLNamedIndividual(MiniPizza.iri("Naples")),
                factory.getOWLLiteral("9.50",
                        org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_DECIMAL)));

        ContextItem item = Problems.inconsistency(pizza, Problems.diagnose(pizza, hermit),
                "HermiT");

        assertTrue(item.getText().contains("Note (OWL 2): xsd:decimal and xsd:float have disjoint"
                + " value spaces"), item.getText());
    }

    /** An integer is a decimal in OWL 2: no note where there is no clash of that kind. */
    @Test
    void anIntegerAgainstADecimalRangeIsNoClash() {
        assertTrue(Problems.datatypeNotes(java.util.Arrays.asList("hasCount Range: xsd:decimal",
                "Naples hasCount \"3\"^^xsd:integer")).isEmpty());
        assertEquals(1, Problems.datatypeNotes(java.util.Arrays.asList(
                "hasName Range: xsd:string", "Naples hasName \"3\"^^xsd:integer")).size());
    }

    /**
     * Asked by the person (1.151.0): the assistant told the model the ontology was inconsistent
     * without asking the reasoner whether it still was. The problem as the reasoner finds it now:
     * none where it no longer holds; the inconsistency, which leaves no class to ask about, where
     * the ontology is inconsistent; a class's problem with its class, to be asked about again.
     */
    @Test
    void aProblemIsTakenAsTheReasonerFindsItNow() {
        OWLClass bad = cls("Bad");
        manager.addAxiom(pizza, factory.getOWLSubClassOfAxiom(bad, cls("VegetarianTopping")));
        org.semanticweb.owlapi.model.OWLAxiom meat =
                factory.getOWLSubClassOfAxiom(bad, cls("MeatTopping"));
        assertEquals(null, Problems.now(pizza, hermit, null, "HermiT"), "consistent");
        assertEquals(null, Problems.now(pizza, hermit, bad.getIRI(), "HermiT"), "satisfiable");

        manager.addAxiom(pizza, meat);
        ContextItem unsatisfiable = Problems.now(pizza, hermit, bad.getIRI(), "HermiT");
        assertEquals("Why Bad is unsatisfiable", unsatisfiable.getTitle());
        assertEquals(bad.getIRI(), unsatisfiable.getSubject());
        assertTrue(unsatisfiable.getText().contains("Justification 1"), unsatisfiable.getText());
        assertEquals(null, Problems.now(pizza, hermit, null, "HermiT"),
                "consistent, though a class is unsatisfiable");
        assertEquals(bad.getIRI(), Problems.unsatisfiable(pizza, hermit, bad, "HermiT")
                .getSubject());

        OWLNamedIndividual x = factory.getOWLNamedIndividual(MiniPizza.iri("x"));
        manager.addAxiom(pizza, factory.getOWLClassAssertionAxiom(cls("VegetarianTopping"), x));
        manager.addAxiom(pizza, factory.getOWLClassAssertionAxiom(cls("MeatTopping"), x));
        ContextItem inconsistent = Problems.now(pizza, hermit, bad.getIRI(), "HermiT");
        assertEquals(Problems.INCONSISTENT, inconsistent.getTitle());
        assertEquals(null, inconsistent.getSubject());
        assertTrue(inconsistent.getText().startsWith("Reasoner: HermiT.\nThe ontology is"
                + " inconsistent"), inconsistent.getText());
        assertEquals(Problems.INCONSISTENT, Problems.now(pizza, hermit, null, "HermiT")
                .getTitle());
    }

    @Test
    void noJustificationIsNotNoReason() {
        String text = Problems.describe("ELK", "Bad is unsatisfiable.",
                Collections.<Explanations.Justification>emptyList());

        assertTrue(text.contains("The reasoner found the problem but no justification within the"
                + " limit; this does not mean there is no reason."), text);
    }
}
