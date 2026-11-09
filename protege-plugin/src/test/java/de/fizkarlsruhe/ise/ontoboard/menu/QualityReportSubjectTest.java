package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** The "Show labels instead of IRIs" box, which had stopped doing anything. */
class QualityReportSubjectTest {

    private static final String LABELLED = "http://example.org/mine/MINE_0001000";
    private static final String UNLABELLED = "http://example.org/mine/MINE_0001001";

    private OWLOntology ontology;

    @BeforeEach
    void anOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/mine"));
        OWLClass labelled = factory.getOWLClass(IRI.create(LABELLED));
        manager.applyChange(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(labelled)));
        manager.applyChange(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), labelled.getIRI(),
                factory.getOWLLiteral("hardness test", "en"))));
        manager.applyChange(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(UNLABELLED)))));
    }

    @Test
    void aLabelledTermShowsItsLabelAndItsName() {
        assertEquals("hardness test (MINE_0001000)",
                QualityReportAction.subjectShown(ontology, LABELLED, true));
    }

    @Test
    void theIriStaysWhenTheBoxIsUnticked() {
        assertEquals(LABELLED, QualityReportAction.subjectShown(ontology, LABELLED, false));
    }

    @Test
    void aTermWithNoLabelKeepsItsIri() {
        assertEquals(UNLABELLED, QualityReportAction.subjectShown(ontology, UNLABELLED, true));
    }

    /** A finding about the ontology as a whole has no subject. */
    @Test
    void noSubjectIsLeftAlone() {
        assertEquals("", QualityReportAction.subjectShown(ontology, "", true));
    }

    @Test
    void anIriNotInTheOntologyIsLeftAlone() {
        assertEquals("http://example.org/elsewhere#X",
                QualityReportAction.subjectShown(ontology, "http://example.org/elsewhere#X", true));
    }

    // ---------- the message, which went on showing IRIs ----------

    private static final String DEFINITION = "http://purl.obolibrary.org/obo/IAO_0000115";

    private void aLabelledDefinitionProperty() {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.applyChange(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLAnnotationProperty(IRI.create(DEFINITION)))));
        manager.applyChange(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), IRI.create(DEFINITION),
                factory.getOWLLiteral("definition", "en"))));
    }

    /**
     * A tester saw the IRI of IAO_0000115 in the Message column with the box ticked, beside a
     * Subject column that showed labels.
     */
    @Test
    void anIriInTheMessageIsShownAsTheSubjectIs() {
        aLabelledDefinitionProperty();

        assertEquals("definition (IAO_0000115)",
                QualityReportAction.messageShown(ontology, DEFINITION, true));
        assertEquals("definition (IAO_0000115): hardness test (MINE_0001000)",
                QualityReportAction.messageShown(ontology, DEFINITION + ": " + LABELLED, true));
    }

    @Test
    void theMessageIsLeftAsItIsWhenTheBoxIsUnticked() {
        aLabelledDefinitionProperty();

        assertEquals(DEFINITION + ": " + LABELLED,
                QualityReportAction.messageShown(ontology, DEFINITION + ": " + LABELLED, false));
    }

    /** A web address inside a definition is text, not a term. */
    @Test
    void anAddressThatIsNotATermIsLeftInTheMessage() {
        aLabelledDefinitionProperty();

        assertEquals("definition (IAO_0000115): as defined at https://example.org/glossary.",
                QualityReportAction.messageShown(ontology,
                        DEFINITION + ": as defined at https://example.org/glossary.", true));
        assertEquals(UNLABELLED + ": x",
                QualityReportAction.messageShown(ontology, UNLABELLED + ": x", true));
    }
}
