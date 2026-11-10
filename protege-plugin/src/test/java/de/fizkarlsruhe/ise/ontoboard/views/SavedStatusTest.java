package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * The status line does not say "Saved." over a change that is not.
 *
 * <p>Found by a tester (egg B, 1.135.1): "Defined egg boiling report. File &gt; Save keeps it.",
 * File &gt; Save, "Saved.", then Edit &gt; Undo took the definition back - and the line still said
 * "Saved." while Build asked "Save first?". In a probe's loop of Undo and Build, two rounds of six
 * read "Saved." after the Undo.
 */
class SavedStatusTest {

    private static final String EGG = "https://example.org/eggboil/";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLAxiom definitionOfTheReport() {
        return df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(IRI.create("http://purl.obolibrary.org/obo/IAO_0000115")),
                IRI.create(EGG + "EGGBOIL_0001003"),
                df.getOWLLiteral("A report that is about an egg boiling."));
    }

    /** Edit > Undo of the definition is a RemoveAxiom on the open ontology: not saved. */
    @Test
    void anUndoAfterASaveIsNotSaved() throws Exception {
        OWLOntology open = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        List<OWLOntologyChange> undo = Collections.<OWLOntologyChange>singletonList(
                new RemoveAxiom(open, definitionOfTheReport()));

        String after = SavedStatus.afterChanges("Saved.", undo, open);

        assertTrue(!"Saved.".equals(after), "an undone definition is not saved: " + after);
        assertTrue(after.contains("File > Save"), "and the line says how to keep it: " + after);
        assertTrue(after.length() <= 120, "fits a status line at 1024 px: " + after.length());
    }

    /** An edit in another view, or a redo, is the same: a change to the open ontology. */
    @Test
    void anyChangeToTheOpenOntologyEndsSaved() throws Exception {
        OWLOntology open = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        OWLAxiom declared = df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(EGG + "EGGBOIL_0001004")));

        assertEquals(SavedStatus.CHANGED, SavedStatus.afterChanges("Saved.",
                Collections.<OWLOntologyChange>singletonList(new AddAxiom(open, declared)), open));
    }

    /** The next save says "Saved." again, as it does after any message that asks for one. */
    @Test
    void theNextSaveSaysSavedAgain() throws Exception {
        OWLOntology open = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        String changed = SavedStatus.afterChanges("Saved.", Collections.<OWLOntologyChange>singletonList(
                new RemoveAxiom(open, definitionOfTheReport())), open);

        assertEquals("Saved.", SavedStatus.afterSave(changed, false));
        assertEquals("Saved.", SavedStatus.afterSave("Defined egg boiling. File > Save keeps it.", false));
        assertEquals("Saved.", SavedStatus.afterSave("Imported dataset: 2 classes in its frame."
                + " Right-click a class for New subclass... to extend it, and File > Save to keep the"
                + " import.", false));
    }

    /**
     * A module rebuilt in memory is not the file being edited: Refresh imports applies its changes
     * to the module and marks it clean, and the edit file is as saved as it was.
     */
    @Test
    void aModuleRebuiltBehindTheBoardLeavesSavedAlone() throws Exception {
        OWLOntology open = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        OWLOntology module = manager.createOntology(IRI.create(EGG + "eggboil/imports/iao_import.owl"));
        List<OWLOntologyChange> rebuilt = Arrays.<OWLOntologyChange>asList(
                new AddAxiom(module, definitionOfTheReport()));

        assertEquals("Saved.", SavedStatus.afterChanges("Saved.", rebuilt, open));
        assertEquals("Saved.", SavedStatus.afterChanges("Saved.", rebuilt, null), "no open ontology");
        assertEquals("Saved.", SavedStatus.afterChanges("Saved.",
                Collections.<OWLOntologyChange>emptyList(), open));
    }

    /** Whatever else the line says is the board's to say: only "Saved." is taken back. */
    @Test
    void aMessageOfTheBoardsOwnIsLeftAsItIs() throws Exception {
        OWLOntology open = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        List<OWLOntologyChange> undo = Collections.<OWLOntologyChange>singletonList(
                new RemoveAxiom(open, definitionOfTheReport()));

        for (String board : new String[] {"Defined egg boiling report. File > Save keeps it.",
            "No longer imported: iao. Its terms left the board; its files stay in the project"
                    + " (see Imported terms...).", "(1 of 3) egg boiling - " + EGG + "EGGBOIL_0001000",
            " ", null}) {
            assertEquals(board, SavedStatus.afterChanges(board, undo, open));
        }
    }

    /**
     * Saving a module is not saving the ontology. Imported terms... saves the module it rebuilt,
     * Protégé reports a save, and the line said "Saved." over an edit file that was not.
     */
    @Test
    void savingAModuleWhileTheOntologyIsUnsavedIsNotSaved() {
        assertEquals("Defined egg boiling. File > Save keeps it.",
                SavedStatus.afterSave("Defined egg boiling. File > Save keeps it.", true));
        assertEquals(SavedStatus.CHANGED, SavedStatus.afterSave(SavedStatus.CHANGED, true));
    }
}
