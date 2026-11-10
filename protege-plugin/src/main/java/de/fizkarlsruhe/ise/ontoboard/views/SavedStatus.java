package de.fizkarlsruhe.ise.ontoboard.views;

import java.util.List;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * What the board's status line says about saving, and when that stops being true.
 *
 * <p>"Saved." is a claim about the open ontology, and it used to outlive the next change to it. A
 * tester (egg B, 1.135.1) pressed File &gt; Save, then Edit &gt; Undo: the line went on saying
 * "Saved." while Build asked "Save first?". Protégé's undo reaches the board only as an ontology
 * change, like an edit in any other view, so it is the change that ends the claim, whoever made it.
 *
 * <p>Only a change to the open ontology does. A module rebuilt in memory - Refresh imports, a term
 * added to an import - changes another ontology, which the action then marks clean; the file being
 * edited is as saved as it was.
 */
final class SavedStatus {

    /** What the line says once the open ontology is saved. */
    static final String SAVED = "Saved.";

    /**
     * What it says once a change follows. It asks for a save the way the board's own messages do,
     * so the next save turns it back into {@link #SAVED}.
     */
    static final String CHANGED = "Changed since the last save. File > Save keeps it.";

    private SavedStatus() {
    }

    /** A line that asks for a save: "Defined egg boiling. File > Save keeps it.", and {@link #CHANGED}. */
    static boolean asksForASave(String status) {
        return status != null && (status.contains("File > Save keeps")
                || status.contains("File > Save to keep"));
    }

    /**
     * The line after a save. A line that asked for one says {@link #SAVED} - unless the open
     * ontology is still unsaved, which is what saving a module looks like: Imported terms... saves
     * the module it rebuilt, and Protégé reports that as a save like any other.
     */
    static String afterSave(String status, boolean openStillUnsaved) {
        return !openStillUnsaved && asksForASave(status) ? SAVED : status;
    }

    /** The line after these changes: {@link #SAVED} no longer holds once one of them is to {@code open}. */
    static String afterChanges(String status, List<? extends OWLOntologyChange> changes,
            OWLOntology open) {
        if (!SAVED.equals(status) || open == null || changes == null) {
            return status;
        }
        for (OWLOntologyChange change : changes) {
            if (open.equals(change.getOntology())) {
                return CHANGED;
            }
        }
        return status;
    }
}
