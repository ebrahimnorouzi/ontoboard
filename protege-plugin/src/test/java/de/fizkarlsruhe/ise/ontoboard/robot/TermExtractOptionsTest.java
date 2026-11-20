package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * ROBOT extract's own options, each for the methods it is for (1.155.0, asked by the user: "are
 * all the methods considered ... each may have some unique parameters and fields that should be
 * used in the field that users can input their own"). The source: D above C above B above A,
 * E below A, and an individual of A.
 */
class TermExtractOptionsTest {

    private static final String NS = "https://example.org/src#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology source;

    private OWLClass named(String local) {
        OWLClass cls = factory.getOWLClass(IRI.create(NS + local));
        manager.addAxiom(source, factory.getOWLDeclarationAxiom(cls));
        manager.addAxiom(source, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                cls.getIRI(), factory.getOWLLiteral(local.toLowerCase(), "en")));
        return cls;
    }

    @BeforeEach
    void aSource() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        source = manager.createOntology(IRI.create("https://example.org/src.owl"));
        OWLClass a = named("A");
        OWLClass b = named("B");
        OWLClass c = named("C");
        OWLClass d = named("D");
        OWLClass e = named("E");
        manager.addAxiom(source, factory.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(source, factory.getOWLSubClassOfAxiom(b, c));
        manager.addAxiom(source, factory.getOWLSubClassOfAxiom(c, d));
        manager.addAxiom(source, factory.getOWLSubClassOfAxiom(e, a));
        manager.addAxiom(source, factory.getOWLClassAssertionAxiom(a,
                factory.getOWLNamedIndividual(IRI.create(NS + "i"))));
    }

    private static IRI iri(String local) {
        return IRI.create(NS + local);
    }

    private static boolean has(OWLOntology module, String local) {
        return module.containsEntityInSignature(iri(local), Imports.EXCLUDED);
    }

    private OWLOntology extract(TermExtract.Method method, TermExtract.Options options,
            String... terms) {
        java.util.List<IRI> asked = new java.util.ArrayList<IRI>();
        for (String term : terms) {
            asked.add(iri(term));
        }
        return TermExtract.run(source, asked, method, IRI.create("https://example.org/m.owl"),
                options).getModule();
    }

    @Test
    void aModuleLeavesTheIndividualsOutWhenAsked() {
        assertTrue(has(extract(TermExtract.Method.BOT, TermExtract.Options.defaults(), "A"),
                "i"), "ROBOT's default includes them");
        assertFalse(has(extract(TermExtract.Method.BOT, TermExtract.Options.defaults()
                .withIndividuals("exclude"), "A"), "i"));
    }

    @Test
    void mireotStopsAtTheUpperTermGiven() {
        OWLOntology stopped = extract(TermExtract.Method.MIREOT, TermExtract.Options.defaults()
                .withUpperTerms(Collections.singletonList(iri("B"))), "A");

        assertTrue(has(stopped, "A") && has(stopped, "B"));
        assertFalse(has(stopped, "C") || has(stopped, "D"), "above the upper term");
    }

    @Test
    void mireotBringsABranchWithEverythingUnderIt() {
        OWLOntology branch = extract(TermExtract.Method.MIREOT, TermExtract.Options.defaults()
                .withBranchTerms(Collections.singletonList(iri("A"))), "D");

        assertTrue(has(branch, "E"), "E is under A");
        assertTrue(has(branch, "D"));
    }

    /** ROBOT's subset: the terms asked for, linked directly, and nothing between them. */
    @Test
    void aSubsetKeepsOnlyTheTermsAskedLinkedDirectly() {
        OWLOntology subset = extract(TermExtract.Method.SUBSET, TermExtract.Options.defaults(),
                "A", "C");

        assertTrue(has(subset, "A") && has(subset, "C"));
        assertFalse(has(subset, "B"), "B is between them");
        assertTrue(subset.containsAxiom(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(iri("A")), factory.getOWLClass(iri("C")))),
                String.valueOf(subset.getAxioms()));
    }

    @Test
    void mireotLeavesTheIntermediatesOutWhenAsked() {
        OWLOntology none = extract(TermExtract.Method.MIREOT, TermExtract.Options.defaults()
                .withUpperTerms(Collections.singletonList(iri("D"))).withIntermediates("none"),
                "A");
        OWLOntology all = extract(TermExtract.Method.MIREOT, TermExtract.Options.defaults()
                .withUpperTerms(Collections.singletonList(iri("D"))), "A");

        assertTrue(has(none, "A") && has(none, "D"));
        assertFalse(has(none, "B") || has(none, "C"), "no intermediates");
        assertTrue(has(all, "B") && has(all, "C"), "ROBOT's default: all of them");
    }

    @Test
    void eachTermCanBeMarkedWithItsSource() {
        OWLOntology marked = extract(TermExtract.Method.BOT, TermExtract.Options.defaults()
                .annotatingWithSource(true), "A");

        assertTrue(marked.getAnnotationAssertionAxioms(iri("A")).stream()
                .anyMatch(axiom -> axiom.getProperty().getIRI().toString()
                        .equals("http://www.w3.org/2000/01/rdf-schema#isDefinedBy")),
                String.valueOf(marked.getAnnotationAssertionAxioms(iri("A"))));
    }

    @Test
    void aValueRobotDoesNotKnowIsRefusedInWords() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TermExtract.Options.defaults().withIndividuals("some"));
        assertTrue(refused.getMessage().contains("include, minimal, definitions or exclude"),
                refused.getMessage());
        assertTrue(Arrays.asList(TermExtract.Method.values()).contains(TermExtract.Method.SUBSET));
    }
}
