package de.fizkarlsruhe.ise.ontoboard.prov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** A definition written from the board, the way a quality report will look for it. */
class TermDefinitionsTest {

    private static final IRI TERM = IRI.create("http://example.org/mine#Plate");

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/mine"));
        manager.applyChange(new AddAxiom(ontology,
                factory.getOWLDeclarationAxiom(factory.getOWLClass(TERM))));
    }

    @Test
    void writingOneMakesItTheDefinitionAndLabelsTheProperty() {
        manager.applyChanges(TermDefinitions.set(ontology, TERM, " A flat piece of metal. "));
        assertEquals("A flat piece of metal.", TermDefinitions.of(ontology, TERM));
        assertTrue(ontology.containsAxiom(factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), TermDefinitions.DEFINITION,
                factory.getOWLLiteral("definition", "en"))), "reads as definition afterwards");
    }

    @Test
    void writingAgainReplacesItRatherThanAddingASecond() {
        manager.applyChanges(TermDefinitions.set(ontology, TERM, "First."));
        manager.applyChanges(TermDefinitions.set(ontology, TERM, "Second."));
        assertEquals("Second.", TermDefinitions.of(ontology, TERM));
        long definitions = ontology.getAnnotationAssertionAxioms(TERM).stream()
                .filter(a -> TermDefinitions.DEFINITION.equals(a.getProperty().getIRI())).count();
        assertEquals(1, definitions);
    }

    @Test
    void blankRemovesIt() {
        manager.applyChanges(TermDefinitions.set(ontology, TERM, "First."));
        manager.applyChanges(TermDefinitions.set(ontology, TERM, "   "));
        assertEquals("", TermDefinitions.of(ontology, TERM));
    }

    @Test
    void aPropertyAnImportAlreadyLabelsIsNotDeclaredAgain() throws Exception {
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/imported"));
        manager.applyChange(new AddAxiom(imported, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), TermDefinitions.DEFINITION,
                factory.getOWLLiteral("definition", "en"))));
        manager.applyChange(new AddImport(ontology, factory.getOWLImportsDeclaration(
                IRI.create("http://example.org/imported"))));
        List<OWLOntologyChange> changes = TermDefinitions.set(ontology, TERM, "Text.");
        assertEquals(1, changes.size());
    }

    private void define(String text, String language, org.semanticweb.owlapi.model.OWLAnnotation
            ... annotations) {
        manager.applyChange(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(TermDefinitions.DEFINITION), TERM,
                factory.getOWLLiteral(text, language),
                new java.util.HashSet<org.semanticweb.owlapi.model.OWLAnnotation>(
                        java.util.Arrays.asList(annotations)))));
    }

    private java.util.Set<org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom>
            definitions() {
        java.util.Set<org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom> found =
                new java.util.HashSet<org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom>();
        for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(TERM)) {
            if (TermDefinitions.DEFINITION.equals(axiom.getProperty().getIRI())) {
                found.add(axiom);
            }
        }
        return found;
    }

    /**
     * One definition is edited; the others, their languages and their sources stay.
     *
     * <p>Editing removed every definition the term had and wrote back one tagged English: a code
     * review's term defined in English with a source, and in German, lost both and the source,
     * and the German text - shown because it happened to come first - came back tagged English.
     */
    @Test
    void editingOneDefinitionLeavesTheOthers() {
        org.semanticweb.owlapi.model.OWLAnnotation source = factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(IRI.create(
                        "http://www.geneontology.org/formats/oboInOwl#hasDbXref")),
                factory.getOWLLiteral("ISBN:0000000000"));
        define("A flat piece of material.", "en", source);
        define("Ein flaches Stueck Material.", "de");

        assertEquals("A flat piece of material.", TermDefinitions.of(ontology, TERM),
                "the English one is shown, every time");
        manager.applyChanges(TermDefinitions.set(ontology, TERM,
                "A flat piece of material. Edited."));

        assertEquals(2, definitions().size(), String.valueOf(definitions()));
        assertTrue(ontology.containsAxiom(factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(TermDefinitions.DEFINITION), TERM,
                factory.getOWLLiteral("A flat piece of material. Edited.", "en"),
                java.util.Collections.singleton(source))), "edited, its source kept");
        assertTrue(ontology.containsAxiom(factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(TermDefinitions.DEFINITION), TERM,
                factory.getOWLLiteral("Ein flaches Stueck Material.", "de"))),
                "the German one untouched");
    }

    /** A definition in another language keeps its language when it is the one edited. */
    @Test
    void theLanguageOfTheEditedDefinitionIsKept() {
        define("Ein flaches Stueck Material.", "de");
        manager.applyChanges(TermDefinitions.set(ontology, TERM, "Ein flaches Stueck Metall."));
        assertEquals(java.util.Collections.singleton(factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(TermDefinitions.DEFINITION), TERM,
                factory.getOWLLiteral("Ein flaches Stueck Metall.", "de"))), definitions());
    }

    /** Clearing the box removes the definition that was shown, and no other. */
    @Test
    void blankRemovesOnlyTheOneShown() {
        define("A flat piece of material.", "en");
        define("Ein flaches Stueck Material.", "de");
        manager.applyChanges(TermDefinitions.set(ontology, TERM, ""));
        assertEquals("Ein flaches Stueck Material.", TermDefinitions.of(ontology, TERM));
        assertEquals(1, definitions().size());
    }

    @Test
    void itIsFoundInAnImportToo() throws Exception {
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/imported"));
        manager.applyChange(new AddAxiom(imported, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(TermDefinitions.DEFINITION), TERM,
                factory.getOWLLiteral("From the import."))));
        manager.applyChange(new AddImport(ontology, factory.getOWLImportsDeclaration(
                IRI.create("http://example.org/imported"))));
        assertEquals("From the import.", TermDefinitions.of(ontology, TERM));
    }
}
