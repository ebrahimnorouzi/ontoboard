package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class DisplayLabelsTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology ontology;

    @BeforeEach
    void setUp() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
    }

    private OWLClass cls(String localName) {
        OWLClass c = factory.getOWLClass(IRI.create(NS + localName));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(c));
        return c;
    }

    private void label(OWLClass c, String text, String language) {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), c.getIRI(),
                language == null ? factory.getOWLLiteral(text)
                        : factory.getOWLLiteral(text, language)));
    }

    @Test
    void fallsBackToTheShortNameWhenThereIsNoLabel() {
        assertEquals("MWO_0000042", DisplayLabels.forEntity(ontology, cls("MWO_0000042")));
    }

    @Test
    void usesRdfsLabelWhenPresent() {
        OWLClass c = cls("MWO_0000042");
        label(c, "Tensile Test", null);
        assertEquals("Tensile Test", DisplayLabels.forEntity(ontology, c));
    }

    @Test
    void prefersEnglishOverOtherLanguages() {
        OWLClass c = cls("MWO_0000042");
        label(c, "Zugversuch", "de");
        label(c, "Tensile Test", "en");
        assertEquals("Tensile Test", DisplayLabels.forEntity(ontology, c));
    }

    @Test
    void prefersAnUnlanguagedLabelOverANonEnglishOne() {
        OWLClass c = cls("MWO_0000042");
        label(c, "Zugversuch", "de");
        label(c, "Tensile Test", null);
        assertEquals("Tensile Test", DisplayLabels.forEntity(ontology, c));
    }

    /**
     * OWL API returns annotations in an unordered collection, so "the first one" would make
     * the diagram's text change between runs. Two English labels must resolve the same way
     * every time.
     */
    @Test
    void choiceAmongEquallyPreferredLabelsIsDeterministic() {
        OWLClass c = cls("MWO_0000042");
        label(c, "Zeta Test", "en");
        label(c, "Alpha Test", "en");

        String first = DisplayLabels.forEntity(ontology, c);
        assertEquals("Alpha Test", first, "expected the lexicographically smallest");
        for (int i = 0; i < 20; i++) {
            assertEquals(first, DisplayLabels.forEntity(ontology, c),
                    "label selection must not vary between calls");
        }
    }

    @Test
    void blankLabelsAreIgnoredRatherThanShownAsAnEmptyNode() {
        OWLClass c = cls("MWO_0000042");
        label(c, "   ", "en");
        assertEquals("MWO_0000042", DisplayLabels.forEntity(ontology, c));
    }

    @Test
    void shortNameHandlesTrailingDelimitersAndMissingFragments() {
        assertEquals("Person", DisplayLabels.shortNameOf(IRI.create(NS + "Person")));
        assertEquals("foo", DisplayLabels.shortNameOf(IRI.create("http://example.org/foo/")));
        assertEquals("foo", DisplayLabels.shortNameOf(IRI.create("http://example.org/foo#")));
        assertEquals("foo", DisplayLabels.shortNameOf(IRI.create("http://example.org/foo")));
    }
}
