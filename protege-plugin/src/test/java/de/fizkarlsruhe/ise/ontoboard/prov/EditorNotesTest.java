package de.fizkarlsruhe.ise.ontoboard.prov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Editorial notes, and the two ways an editing tool destroys somebody else's words.
 *
 * <p>The first is replacing instead of adding: two editors each leave a note, and a tool that
 * treats "the note" as one value overwrites the first with the second, leaving a diff that looks
 * like an ordinary edit. The second is editing across a race: you open a note, somebody deletes it,
 * you save - and a tool matching by position rather than by content resurrects what they removed.
 *
 * <p>Both are silent. Neither throws, neither shows up in a review, and both are only noticed much
 * later by whoever wrote the words that are gone.
 */
class EditorNotesTest {

    private static final IRI TERM = IRI.create("http://example.org/o#Polymer");

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntologyWithATerm() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology,
                factory.getOWLDeclarationAxiom(factory.getOWLClass(TERM)));
    }

    private void apply(List<OWLOntologyChange> changes) {
        manager.applyChanges(changes);
    }

    // ---------- notes accumulate ----------

    @Test
    void aNoteCanBeReadBackAfterItIsAdded() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR,
                "The definition needs work."));

        assertEquals(Arrays.asList("The definition needs work."),
                EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.EDITOR));
    }

    /**
     * The one that matters. Two editors each leave a note; a tool that treats "the note" as a
     * single value overwrites the first, and the diff looks like an ordinary edit.
     */
    @Test
    void aSecondNoteIsAddedRatherThanReplacingTheFirst() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Alice: parent looks wrong."));
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Bob: agreed, see issue 12."));

        List<String> notes = EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.EDITOR);

        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.contains("Alice: parent looks wrong."), notes.toString());
        assertTrue(notes.contains("Bob: agreed, see issue 12."), notes.toString());
    }

    /** Two identical annotations are indistinguishable to a reader and unexplained by a diff. */
    @Test
    void theSameNoteTwiceIsNotAddedTwice() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Needs work."));

        assertTrue(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Needs work.")
                .isEmpty());
        assertTrue(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "  Needs work.  ")
                .isEmpty(), "surrounding space does not make it a different note");
    }

    @Test
    void ablankNoteIsNotStored() {
        assertTrue(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "").isEmpty());
        assertTrue(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "   ").isEmpty());
        assertTrue(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, null).isEmpty());
    }

    // ---------- the two kinds are separate ----------

    @Test
    void aCuratorNoteDoesNotShowUpAmongTheEditorNotes() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.CURATOR, "Taken from ChEBI."));

        assertTrue(EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.EDITOR).isEmpty());
        assertEquals(Arrays.asList("Taken from ChEBI."),
                EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.CURATOR));
    }

    @Test
    void theTwoKindsUseTheIaoPropertiesTheyClaimTo() {
        assertEquals(IRI.create("http://purl.obolibrary.org/obo/IAO_0000116"),
                EditorNotes.Kind.EDITOR.getIri());
        assertEquals(IRI.create("http://purl.obolibrary.org/obo/IAO_0000232"),
                EditorNotes.Kind.CURATOR.getIri());
        assertEquals(IRI.create("http://purl.obolibrary.org/obo/IAO_0000233"),
                EditorNotes.TERM_TRACKER_ITEM);
    }

    /** The canvas marker is on the entity, not on a kind. */
    @Test
    void anEntityWithEitherKindOfNoteIsMarked() {
        assertFalse(EditorNotes.hasNote(ontology, TERM));

        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.CURATOR, "Taken from ChEBI."));

        assertTrue(EditorNotes.hasNote(ontology, TERM));
    }

    // ---------- editing without destroying ----------

    @Test
    void replacingChangesOnlyTheNoteThatWasEdited() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Alice: parent wrong."));
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Bob: see issue 12."));

        apply(EditorNotes.replaceNote(ontology, TERM, EditorNotes.Kind.EDITOR,
                "Alice: parent wrong.", "Alice: parent fixed."));

        List<String> notes = EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.EDITOR);
        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.contains("Alice: parent fixed."), notes.toString());
        assertTrue(notes.contains("Bob: see issue 12."), notes.toString());
        assertFalse(notes.contains("Alice: parent wrong."), notes.toString());
    }

    /**
     * The race. You open a note to edit it, somebody else deletes it, you save. Matching by
     * position would put their deleted note back; matching by content does nothing.
     */
    @Test
    void editingANoteSomebodyElseDeletedDoesNothingRatherThanResurrectingIt() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Bob: still here."));

        List<OWLOntologyChange> changes = EditorNotes.replaceNote(ontology, TERM,
                EditorNotes.Kind.EDITOR, "Alice: already deleted.", "Alice: my edit.");

        assertTrue(changes.isEmpty(), changes.toString());
        assertEquals(Arrays.asList("Bob: still here."),
                EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.EDITOR));
    }

    @Test
    void replacingANoteWithNothingRemovesIt() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Done with this."));

        apply(EditorNotes.removeNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Done with this."));

        assertTrue(EditorNotes.notesOn(ontology, TERM, EditorNotes.Kind.EDITOR).isEmpty());
        assertFalse(EditorNotes.hasNote(ontology, TERM));
    }

    @Test
    void replacingANoteWithItselfChangesNothing() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Same."));

        assertTrue(EditorNotes.replaceNote(ontology, TERM, EditorNotes.Kind.EDITOR,
                "Same.", "Same.").isEmpty());
    }

    // ---------- the discussion lives elsewhere, and the term points at it ----------

    /**
     * The other half of "keep the comments in the ontology". A note travels with every release;
     * the argument about it is unbounded, so it stays where conversations work and the ontology
     * holds a link. IAO:0000233 is what OBO uses for exactly that.
     */
    @Test
    void aTermCanPointAtTheIssueItIsArguedAboutIn() {
        apply(EditorNotes.addTrackerItem(ontology, TERM,
                "https://github.com/ISE-FIZKarlsruhe/mwo/issues/12"));

        assertEquals(Arrays.asList("https://github.com/ISE-FIZKarlsruhe/mwo/issues/12"),
                EditorNotes.trackerItemsOn(ontology, TERM));
    }

    /** Written as an IRI, so anything reading the ontology can follow it, not only a person. */
    @Test
    void theLinkIsStoredAsAnIriRatherThanAString() {
        apply(EditorNotes.addTrackerItem(ontology, TERM, "https://example.org/issues/1"));

        boolean storedAsIri = false;
        for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(TERM)) {
            if (EditorNotes.TERM_TRACKER_ITEM.equals(axiom.getProperty().getIRI())) {
                storedAsIri = axiom.getValue() instanceof IRI;
            }
        }
        assertTrue(storedAsIri, "an IAO:0000233 written as a literal is not resolvable");
    }

    /** A term picks up more than one issue over its life. */
    @Test
    void aTermCanPointAtSeveralIssues() {
        apply(EditorNotes.addTrackerItem(ontology, TERM, "https://example.org/issues/1"));
        apply(EditorNotes.addTrackerItem(ontology, TERM, "https://example.org/issues/2"));

        assertEquals(2, EditorNotes.trackerItemsOn(ontology, TERM).size());
    }

    @Test
    void theSameIssueIsNotLinkedTwice() {
        apply(EditorNotes.addTrackerItem(ontology, TERM, "https://example.org/issues/1"));

        assertTrue(EditorNotes.addTrackerItem(ontology, TERM,
                "https://example.org/issues/1").isEmpty());
    }

    @Test
    void unlinkingTakesOffTheOneNamedAndLeavesTheRest() {
        apply(EditorNotes.addTrackerItem(ontology, TERM, "https://example.org/issues/1"));
        apply(EditorNotes.addTrackerItem(ontology, TERM, "https://example.org/issues/2"));

        apply(EditorNotes.removeTrackerItem(ontology, TERM, "https://example.org/issues/1"));

        assertEquals(Arrays.asList("https://example.org/issues/2"),
                EditorNotes.trackerItemsOn(ontology, TERM));
    }

    /** Some projects write it as a string; read both, write only the resolvable form. */
    @Test
    void aLinkSomebodyElseWroteAsAStringIsStillRead() {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(EditorNotes.TERM_TRACKER_ITEM), TERM,
                factory.getOWLLiteral("https://example.org/issues/3")));

        assertEquals(Arrays.asList("https://example.org/issues/3"),
                EditorNotes.trackerItemsOn(ontology, TERM));
    }

    /**
     * A tracker item that does not resolve is worse than none: it looks like a link, so nobody
     * looks for the discussion anywhere else, and it goes nowhere.
     */
    @Test
    void somethingThatIsNotAnAddressIsRefusedWithAnExample() {
        assertTrue(EditorNotes.rejectTrackerItem("issue 12").contains("resolvable"),
                EditorNotes.rejectTrackerItem("issue 12"));
        assertTrue(EditorNotes.rejectTrackerItem("issue 12").contains("github.com"),
                "showing what one looks like is most of the help");
        assertNotNull(EditorNotes.rejectTrackerItem(""));
        assertNotNull(EditorNotes.rejectTrackerItem("https://example.org/a b"));
    }

    @Test
    void arealIssueUrlIsAccepted() {
        assertNull(EditorNotes.rejectTrackerItem(
                "https://github.com/ISE-FIZKarlsruhe/mwo/issues/12"));
        assertNull(EditorNotes.rejectTrackerItem("http://example.org/tracker/7"));
    }

    @Test
    void aTermWithNoLinksHasNone() {
        assertTrue(EditorNotes.trackerItemsOn(ontology, TERM).isEmpty());
        assertTrue(EditorNotes.trackerItemsOn(null, TERM).isEmpty());
        assertTrue(EditorNotes.addTrackerItem(ontology, TERM, "").isEmpty());
    }

    // ---------- release ----------

    /**
     * IAO:0000116's own definition says a note "may not be included in the publication version".
     * That is permission, so stripping is available and nothing calls it unasked.
     */
    @Test
    void strippingRemovesBothKindsOfNoteAndNothingElse() {
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Needs work."));
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.CURATOR, "From ChEBI."));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), TERM, factory.getOWLLiteral("polymer")));
        int before = ontology.getAxiomCount();

        apply(EditorNotes.strip(ontology));

        assertFalse(EditorNotes.hasNote(ontology, TERM));
        assertEquals(before - 2, ontology.getAxiomCount(),
                "stripping took something other than the two notes");
        assertEquals(1, ontology.getAnnotationAssertionAxioms(TERM).size(),
                "the label should survive a strip");
    }

    @Test
    void strippingAnOntologyWithNoNotesChangesNothing() {
        assertTrue(EditorNotes.strip(ontology).isEmpty());
    }

    // ---------- declarations ----------

    /**
     * An undeclared annotation property is a ROBOT report violation, and using one without
     * declaring it relies on an import having done so - which stops being true the moment
     * somebody drops the import.
     */
    @Test
    void thePropertiesAreDeclaredWhenTheyAreNotAlready() {
        apply(EditorNotes.declareProperties(ontology));

        for (IRI iri : new IRI[] {EditorNotes.EDITOR_NOTE, EditorNotes.CURATOR_NOTE,
                EditorNotes.TERM_TRACKER_ITEM}) {
            assertTrue(ontology.isDeclared(factory.getOWLAnnotationProperty(iri)),
                    iri + " was not declared");
        }
    }

    @Test
    void declaringTwiceAddsNothingTheSecondTime() {
        apply(EditorNotes.declareProperties(ontology));

        assertTrue(EditorNotes.declareProperties(ontology).isEmpty());
    }

    // ---------- finding the notes again ----------

    /**
     * The reason to write a note is to come back to it, and an ontology of ten thousand terms hides
     * them completely - there is no view in Protege that lists them.
     */
    @Test
    void everyNoteInTheOntologyCanBeListedWithItsSubject() {
        IRI other = IRI.create("http://example.org/o#Ceramic");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(factory.getOWLClass(other)));
        apply(EditorNotes.addNote(ontology, TERM, EditorNotes.Kind.EDITOR, "Polymer needs work."));
        apply(EditorNotes.addNote(ontology, other, EditorNotes.Kind.CURATOR, "Ceramic from ChEBI."));

        List<EditorNotes.Note> all = EditorNotes.allIn(ontology);

        assertEquals(2, all.size(), all.toString());
        boolean sawPolymer = false;
        boolean sawCeramic = false;
        for (EditorNotes.Note note : all) {
            sawPolymer |= TERM.equals(note.getSubject())
                    && note.getKind() == EditorNotes.Kind.EDITOR
                    && "Polymer needs work.".equals(note.getText());
            sawCeramic |= other.equals(note.getSubject())
                    && note.getKind() == EditorNotes.Kind.CURATOR;
        }
        assertTrue(sawPolymer, all.toString());
        assertTrue(sawCeramic, all.toString());
    }

    @Test
    void listingIgnoresAnnotationsThatAreNotNotes() {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSComment(), TERM, factory.getOWLLiteral("a comment")));

        assertTrue(EditorNotes.allIn(ontology).isEmpty());
    }

    // ---------- nothing to look at ----------

    @Test
    void nothingToLookAtIsNotAFailure() {
        assertTrue(EditorNotes.notesOn(null, TERM, EditorNotes.Kind.EDITOR).isEmpty());
        assertTrue(EditorNotes.notesOn(ontology, null, EditorNotes.Kind.EDITOR).isEmpty());
        assertTrue(EditorNotes.allIn(null).isEmpty());
        assertTrue(EditorNotes.strip(null).isEmpty());
        assertTrue(EditorNotes.declareProperties(null).isEmpty());
        assertFalse(EditorNotes.hasNote(null, TERM));
    }

    // ---------- the choice is explained ----------

    @Test
    void bothKindsExplainWhenToUseWhich() {
        for (EditorNotes.Kind kind : EditorNotes.Kind.values()) {
            assertTrue(kind.getHelp().length() > 120,
                    kind + " needs enough explanation to choose by: " + kind.getHelp());
            assertFalse(kind.getHelp().toLowerCase().startsWith(kind.getLabel().toLowerCase()),
                    kind + " restates its own label instead of explaining");
        }
        assertFalse(EditorNotes.Kind.EDITOR.getHelp().equals(EditorNotes.Kind.CURATOR.getHelp()));
    }
}
