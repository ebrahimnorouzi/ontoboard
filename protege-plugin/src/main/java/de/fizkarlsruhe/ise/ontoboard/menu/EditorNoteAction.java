package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Notes &gt; Note on the selected term - the durable comment that belongs in the ontology.
 *
 * <p>The request was to write comments and keep everything in the ontology. A threaded discussion
 * does not belong in one: it is unbounded, it appears in every release and every diff, and an
 * importer gains nothing from it. But what is underneath the request - <em>write down that this
 * definition needs work, where the next editor will see it</em> - is exactly what
 * {@code IAO:0000116 editor note} is for, and every OBO ontology carries them. So the note goes in
 * the ontology, where an importer can read it, and the argument goes in the issue tracker.
 *
 * <p>Notes accumulate rather than replace. Two editors each leaving one is the ordinary case, and
 * a tool that treated "the note" as a single value would overwrite the first with the second and
 * leave a diff that looks like an ordinary edit.
 */
public class EditorNoteAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_KIND = "kind";
    private static final String OPTION_TEXT = "text";

    private volatile OWLEntity subject;
    private volatile EditorNotes.Kind kind = EditorNotes.Kind.EDITOR;
    private volatile String text = "";
    private volatile String replacing;

    @Override
    protected String operationName() {
        return "Note";
    }

    /** Instant work - a single annotation assertion - so there is nothing to run in the background. */
    @Override
    protected boolean runsInBackground() {
        return false;
    }

    @Override
    protected boolean configure() {
        subject = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
        if (subject == null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Select a class, property or individual first - a note is written on a term.",
                    "Nothing selected", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        List<String> existing = EditorNotes.notesOn(ontology, subject.getIRI(),
                EditorNotes.Kind.EDITOR);
        // Prefilled only when there is exactly one, because that is the only case where "edit the
        // note" is unambiguous. With two, editing one of them would mean guessing which.
        replacing = existing.size() == 1 ? existing.get(0) : null;
        String label = getOWLModelManager().getRendering(subject);

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_KIND, "Kind", Parameter.Kind.CHOICE)
                        .choices(EditorNotes.Kind.EDITOR.getLabel(),
                                EditorNotes.Kind.CURATOR.getLabel())
                        .defaultValue(EditorNotes.Kind.EDITOR.getLabel())
                        .required()
                        .help(EditorNotes.Kind.EDITOR.getLabel() + ": "
                                + EditorNotes.Kind.EDITOR.getHelp() + "\n\n"
                                + EditorNotes.Kind.CURATOR.getLabel() + ": "
                                + EditorNotes.Kind.CURATOR.getHelp())
                        .build(),
                Parameter.of(OPTION_TEXT, "Note", Parameter.Kind.MULTILINE)
                        .defaultValue(replacing == null ? "" : replacing)
                        .help("What the next person needs to know about this term. It is stored in "
                                + "the ontology as an annotation, travels with any release, and is "
                                + "visible to anyone who imports the term - so write it for them, "
                                + "not only for yourself. Empty the box to delete the note. For an "
                                + "argument that needs replies, open an issue and link it instead.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Note on " + label,
                existingSummary(ontology, existing) + " Notes are added, not replaced - what you "
                        + "type will not overwrite anybody else's.",
                parameters);
        if (chosen == null) {
            return false;
        }
        kind = EditorNotes.Kind.EDITOR.getLabel().equals(chosen.get(OPTION_KIND))
                ? EditorNotes.Kind.EDITOR : EditorNotes.Kind.CURATOR;
        text = chosen.get(OPTION_TEXT);
        if (kind != EditorNotes.Kind.EDITOR) {
            // The box was prefilled from the editor notes; a switch to curator notes makes that
            // prefill somebody else's words in the wrong place.
            replacing = null;
        }
        return true;
    }

    private String existingSummary(OWLOntology ontology, List<String> editorNotes) {
        int curator = EditorNotes.notesOn(ontology, subject.getIRI(),
                EditorNotes.Kind.CURATOR).size();
        if (editorNotes.isEmpty() && curator == 0) {
            return "This term has no notes yet.";
        }
        if (editorNotes.size() == 1 && curator == 0) {
            // Who wrote it and when, because that is what decides whether to act on a note or
            // delete it. Advice from somebody who left the project in 2019 and advice written on
            // Tuesday read identically without it.
            String by = attributionOfTheOneNote(ontology);
            return "Editing the note already on this term"
                    + (by.isEmpty() ? "." : ", written by " + by + ".");
        }
        return "This term already has " + editorNotes.size() + " editor note"
                + (editorNotes.size() == 1 ? "" : "s")
                + (curator == 0 ? "" : " and " + curator + " curator note"
                        + (curator == 1 ? "" : "s"))
                + "; a new one will be added alongside.";
    }

    /** The attribution of the single editor note, or empty when there is none recorded. */
    private String attributionOfTheOneNote(OWLOntology ontology) {
        List<EditorNotes.Note> attributed = EditorNotes.attributedNotesOn(ontology,
                subject.getIRI(), EditorNotes.Kind.EDITOR);
        return attributed.size() == 1 ? attributed.get(0).describeAttribution() : "";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());

        // The note carries who wrote it and when. A note is advice, and advice has a shelf life:
        // "the definition needs work" from 2019 by somebody who has left the project is a
        // different thing from the same sentence written on Tuesday by the person sitting next to
        // you, and undated anonymous text cannot be told apart from either.
        ProvenanceSettings settings = ProvenanceSettings.load();
        String author = settings.shouldStamp(ontology, false) ? settings.canonicalAgent() : "";
        List<OWLOntologyChange> changes = replacing == null
                ? EditorNotes.addNote(ontology, subject.getIRI(), kind, text, author,
                        Provenance.today())
                : EditorNotes.replaceNote(ontology, subject.getIRI(), kind, replacing, text,
                        author, Provenance.today());

        // Declared before use: an undeclared annotation property is a ROBOT report violation, and
        // an ontology that relies on an import having declared it breaks when the import goes.
        List<OWLOntologyChange> declarations = EditorNotes.declareProperties(ontology);

        if (changes.isEmpty()) {
            return result.summary(replacing != null && replacing.equals(text.trim())
                    ? "Nothing changed - the note is as it was."
                    : "Nothing to record. The note was empty, already there, or had been removed "
                            + "by somebody else while you were typing it.").build();
        }
        declarations.addAll(changes);
        // Writing a note is editing the term, and "when did this last change" is the question a
        // curator asks before trusting what it says. Only when the ontology already keeps
        // provenance - see ProvenanceSettings - so an ontology that has never recorded any does
        // not start because somebody left a note in it.
        if (settings.shouldStamp(ontology, false)) {
            declarations.addAll(Provenance.declareProperties(ontology));
            declarations.addAll(Provenance.stampModified(ontology, subject.getIRI(),
                    settings.canonicalAgent(), Provenance.today()));
        }
        getOWLModelManager().applyChanges(declarations);

        String label = getOWLModelManager().getRendering(subject);
        result.note(kind.getLabel() + " on " + label);
        if (text.trim().isEmpty()) {
            return result.summary("Removed the note from " + label + ".").build();
        }
        return result.summary((replacing == null ? "Added a " : "Updated the ")
                + kind.getLabel().toLowerCase() + " on " + label + ".").build();
    }
}
