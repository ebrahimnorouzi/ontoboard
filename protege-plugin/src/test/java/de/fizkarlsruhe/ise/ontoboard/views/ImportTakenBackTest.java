package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * An import taken back is said to be gone.
 *
 * <p>Found by a tester: after Edit &gt; Undo of a pattern import, the status line still said the
 * pattern had been imported, with no frame on the board, and Imported terms... listed its module as
 * if it were imported.
 */
class ImportTakenBackTest {

    @Test
    void theModuleIsNamedAsTheProjectNamesIt() {
        assertEquals("nfdi-dataset-description", SchemaCanvasView.takenBackName(
                "https://example.org/hardtest/hardtest/imports/nfdi-dataset-description_import.owl"));
        assertEquals("iao", SchemaCanvasView.takenBackName("http://example.org/x/imports/iao_import.owl"));
        assertEquals("bfo", SchemaCanvasView.takenBackName("http://purl.obolibrary.org/obo/bfo.owl"));
    }

    /** Undo of an import takes it away; a module rebuilt removes and adds it in one step, and has not. */
    @Test
    void anImportAddedBackInTheSameStepHasNotGone() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory df = manager.getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLOntology active = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/x/x.owl"));
        org.semanticweb.owlapi.model.OWLOntology other = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/y/y.owl"));
        org.semanticweb.owlapi.model.OWLImportsDeclaration price = df.getOWLImportsDeclaration(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/x/imports/price_import.owl"));
        org.semanticweb.owlapi.model.OWLImportsDeclaration iao = df.getOWLImportsDeclaration(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/x/imports/iao_import.owl"));

        assertEquals(Arrays.asList("price"), SchemaCanvasView.importsTakenBack(Arrays.asList(
                new org.semanticweb.owlapi.model.RemoveImport(active, price)), active));
        assertEquals(Arrays.asList(), SchemaCanvasView.importsTakenBack(Arrays.asList(
                new org.semanticweb.owlapi.model.RemoveImport(active, iao),
                new org.semanticweb.owlapi.model.AddImport(active, iao)), active),
                "a module rebuilt in place");
        assertEquals(Arrays.asList(), SchemaCanvasView.importsTakenBack(Arrays.asList(
                new org.semanticweb.owlapi.model.RemoveImport(other, price)), active),
                "another ontology's import is not this one's");
    }

    @Test
    void theStatusSaysItIsNoLongerImportedAndWhereItsFilesAre() {
        String status = SchemaCanvasView.takenBackStatus(Arrays.asList("price", "iao"));
        assertTrue(status.startsWith("No longer imported: price, iao."), status);
        assertTrue(status.contains("Imported terms..."), status);
        assertTrue(status.length() <= 120, "fits a status line at 1024 px: " + status.length());
    }
}
