package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What the hover says about a term, taken from OntoGraf's tooltip sections.
 *
 * <p>The hover used to say what a term is called, what kind it is and whether it is imported -
 * everything except what the ontology says about it. Disjointness is the section that earns its
 * place most, because the canvas draws none anywhere: this is the only surface it appears on.
 */
class TermSummaryTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology ontology;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private void add(org.semanticweb.owlapi.model.OWLAxiom axiom) {
        manager.addAxiom(ontology, axiom);
    }

    /** Named parents are listed, sorted. */
    @Test
    void superClassesAreListed() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), cls("Food")));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), cls("Baked")));

        TermSummary summary = TermSummary.of(ontology, cls("Pizza"));

        assertEquals(Arrays.asList("Baked", "Food"), summary.getSuperClasses());
        assertFalse(summary.isEmpty());
    }

    /** owl:Thing is not worth a line of a tooltip. */
    @Test
    void owlThingIsNotListed() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), factory.getOWLThing()));

        assertTrue(TermSummary.of(ontology, cls("Pizza")).getSuperClasses().isEmpty());
    }

    /**
     * Disjointness appears, and it appears nowhere else in the whole plugin.
     *
     * <p>The canvas draws no disjointness at all, so without this a term can be declared
     * disjoint with six others and the board gives no hint of it.
     */
    @Test
    void disjointsAreListedBecauseNothingElseShowsThem() {
        add(factory.getOWLDisjointClassesAxiom(cls("Pizza"), cls("Dessert")));

        assertEquals(Arrays.asList("Dessert"), TermSummary.of(ontology, cls("Pizza"))
                .getDisjoints());
    }

    /** Equivalent named classes too. */
    @Test
    void equivalentsAreListed() {
        add(factory.getOWLEquivalentClassesAxiom(cls("Pizza"), cls("Pizza2")));

        assertEquals(Arrays.asList("Pizza2"), TermSummary.of(ontology, cls("Pizza"))
                .getEquivalents());
    }

    /**
     * An anonymous relative is counted, not listed.
     *
     * <p>It is a restriction, and since 1.75.0 those are drawn as the arrows leaving the node.
     * Repeating them here would say twice what the diagram says once - but saying nothing at all
     * would hide that the term has more to it, so the count is reported.
     */
    @Test
    void anonymousRelativesAreCountedRatherThanListed() {
        OWLObjectProperty hasTopping = factory.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectSomeValuesFrom(hasTopping, cls("Topping"))));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), cls("Food")));

        TermSummary summary = TermSummary.of(ontology, cls("Pizza"));

        assertEquals(Arrays.asList("Food"), summary.getSuperClasses());
        assertEquals(1, summary.getUnnamedCount());
    }

    /** Parents from an import count: a term's parents are usually upstream. */
    @Test
    void superClassesFromAnImportAreFound() throws Exception {
        OWLOntology upstream = manager.createOntology(IRI.create("http://example.org/up"));
        manager.applyChange(new AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/up"))));
        manager.addAxiom(upstream, factory.getOWLSubClassOfAxiom(cls("Pizza"), cls("Food")));

        assertEquals(Arrays.asList("Food"),
                TermSummary.of(ontology, cls("Pizza")).getSuperClasses());
    }

    /** A term nobody says anything about has an empty summary rather than a null one. */
    @Test
    void aTermWithNothingSaidAboutItIsEmpty() {
        TermSummary summary = TermSummary.of(ontology, cls("Lonely"));

        assertTrue(summary.isEmpty());
        assertTrue(summary.getSuperClasses().isEmpty());
        assertEquals(0, summary.getUnnamedCount());
    }

    /** Nulls do not throw: this runs on the refresh path. */
    @Test
    void nullsAreSafe() {
        assertTrue(TermSummary.of(null, cls("Pizza")).isEmpty());
        assertTrue(TermSummary.of(ontology, null).isEmpty());
        assertTrue(TermSummary.empty().isEmpty());
    }

    /** A long list is cut with a count, because a tooltip that fills the screen is dismissed. */
    @Test
    void aLongListIsTruncatedWithACount() {
        java.util.List<String> many = new java.util.ArrayList<String>();
        for (int at = 1; at <= 10; at++) {
            many.add("Term" + at);
        }

        String joined = TermSummary.joined(many);

        assertTrue(joined.startsWith("Term1, Term2"), joined);
        assertTrue(joined.endsWith("and 4 more"), joined);
        assertEquals("", TermSummary.joined(null));
        assertEquals("One", TermSummary.joined(Arrays.asList("One")));
    }

    /** The summary survives being marked imported, which every imported term is. */
    @Test
    void theSummarySurvivesTheImportedMarker() {
        TermSummary summary = TermSummary.of(ontology, cls("Pizza"));
        CanvasNode node = new CanvasNode(NS + "Pizza", NodeKind.CLASS, "Pizza")
                .withCurie("o:Pizza")
                .withSummary(summary);

        CanvasNode imported = node.asImported();

        assertEquals(summary, imported.getSummary());
        assertEquals("o:Pizza", imported.getCurie(),
                "the identifier was dropped here from 1.72.0 until 1.79.0");
        assertEquals("o:Pizza", node.asUnsatisfiable().getCurie());
        assertEquals(summary, node.asUnsatisfiable().getSummary());
    }
}
