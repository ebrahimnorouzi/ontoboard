package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes;
import java.util.List;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Notes &gt; All notes - everything anybody has written down, in one place.
 *
 * <p>The reason to write an editorial note is to come back to it, and an ontology of ten thousand
 * terms hides them completely: Protege will show you the note on the term you are looking at, and
 * offers no way at all to ask which terms have notes. So they accumulate, unread, which makes
 * writing them feel pointless and stops people doing it.
 *
 * <p>Read-only, and the whole list is savable from the result dialog - which is what makes it
 * usable as an agenda for a curation call.
 */
public class AllNotesAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected String operationName() {
        return "All notes";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        List<EditorNotes.Note> notes = EditorNotes.allIn(ontology);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Kind", "Term", "Note");
        if (notes.isEmpty()) {
            return result.summary("No editorial notes in this ontology. They are stored as "
                    + "IAO:0000116 editor notes and IAO:0000232 curator notes; add one with "
                    + "OntoBoard > Notes > Note on the selected term.").build();
        }

        int editor = 0;
        for (EditorNotes.Note note : notes) {
            result.row(note.getKind().getLabel(), render(note), note.getText());
            if (note.getKind() == EditorNotes.Kind.EDITOR) {
                editor++;
            }
        }
        result.note("Editor notes: " + editor);
        result.note("Curator notes: " + (notes.size() - editor));
        return result.summary(notes.size() + " note" + (notes.size() == 1 ? "" : "s")
                + " on " + distinctSubjects(notes) + " term"
                + (distinctSubjects(notes) == 1 ? "" : "s") + ".").build();
    }

    /** The term as the rest of Protege shows it, so an OBO numeric IRI is readable. */
    private String render(EditorNotes.Note note) {
        try {
            return getOWLModelManager().getRendering(
                    getOWLModelManager().getOWLDataFactory().getOWLClass(note.getSubject()));
        } catch (RuntimeException notAClass) {
            return note.getSubject().toString();
        }
    }

    private static int distinctSubjects(List<EditorNotes.Note> notes) {
        java.util.Set<org.semanticweb.owlapi.model.IRI> subjects =
                new java.util.HashSet<org.semanticweb.owlapi.model.IRI>();
        for (EditorNotes.Note note : notes) {
            subjects.add(note.getSubject());
        }
        return subjects.size();
    }
}
