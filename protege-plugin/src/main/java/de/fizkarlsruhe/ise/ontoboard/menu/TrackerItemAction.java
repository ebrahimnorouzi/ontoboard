package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes;
import java.awt.Desktop;
import java.util.ArrayList;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Notes &gt; Discussion link - point a term at the issue where it is being argued about.
 *
 * <p>The other half of the answer to "keep the comments in the ontology". A note says what is
 * wrong with a term and travels with every release; the argument about it - replies, mentions,
 * somebody changing their mind twice - is unbounded, and putting that in a released file would
 * mean every consumer downloads it and every diff carries it. {@code IAO:0000233 term tracker
 * item} is what OBO uses instead: the ontology holds a link, and the conversation stays where
 * conversations work.
 *
 * <p>The constant has been in this codebase since editorial notes were added, and was declared
 * into users' ontologies by the note action while nothing could write or read one.
 */
public class TrackerItemAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_WHAT = "what";
    private static final String OPTION_URL = "url";

    private static final String LINK = "Link this term to an issue";
    private static final String OPEN = "Open the linked issue";
    private static final String UNLINK = "Remove a link";

    private volatile OWLEntity subject;
    private volatile String what = LINK;
    private volatile String url = "";

    @Override
    protected String operationName() {
        return "Discussion link";
    }

    /** An annotation assertion and possibly a browser launch; nothing worth a worker thread. */
    @Override
    protected boolean runsInBackground() {
        return false;
    }

    @Override
    protected boolean configure() {
        subject = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
        if (subject == null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Select a term first - a discussion link belongs to one.",
                    "Nothing selected", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        List<String> existing = EditorNotes.trackerItemsOn(ontology, subject.getIRI());

        List<String> choices = existing.isEmpty()
                ? Arrays.asList(LINK)
                : Arrays.asList(LINK, OPEN, UNLINK);

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_WHAT, "Do what", Parameter.Kind.CHOICE)
                        .choices(choices.toArray(new String[0]))
                        .defaultValue(existing.isEmpty() ? LINK : OPEN)
                        .required()
                        .help("A term can carry several links - a term often has more than one "
                                + "issue behind it over its life. Opening uses your normal "
                                + "browser. Removing takes off the exact link you name and "
                                + "leaves any others.")
                        .build(),
                Parameter.of(OPTION_URL, "Issue address", Parameter.Kind.TEXT)
                        .defaultValue(existing.isEmpty() ? "" : existing.get(0))
                        .help("The URL of the issue, for example "
                                + "https://github.com/owner/repo/issues/12. It is stored as an "
                                + "IAO:0000233 term tracker item, which is what OBO ontologies "
                                + "use, so anything reading the ontology can follow it - not only "
                                + "a person looking at this window.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Discussion link for " + getOWLModelManager().getRendering(subject),
                existing.isEmpty()
                        ? "This term has no discussion linked to it yet."
                        : "This term links to " + existing.size() + " issue"
                                + (existing.size() == 1 ? "" : "s") + ".",
                parameters);
        if (chosen == null) {
            return false;
        }
        what = chosen.get(OPTION_WHAT);
        url = chosen.get(OPTION_URL) == null ? "" : chosen.get(OPTION_URL).trim();
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());
        String label = getOWLModelManager().getRendering(subject);

        if (OPEN.equals(what)) {
            return open(result, label);
        }
        if (UNLINK.equals(what)) {
            List<OWLOntologyChange> changes = EditorNotes.removeTrackerItem(ontology,
                    subject.getIRI(), url);
            if (changes.isEmpty()) {
                return result.failed("No link with that address is on " + label + ".").build();
            }
            getOWLModelManager().applyChanges(changes);
            return result.summary("Unlinked " + url + " from " + label + ".").build();
        }

        String rejected = EditorNotes.rejectTrackerItem(url);
        if (rejected != null) {
            return result.failed(rejected).build();
        }
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(
                EditorNotes.declareProperties(ontology));
        List<OWLOntologyChange> link = EditorNotes.addTrackerItem(ontology, subject.getIRI(), url);
        if (link.isEmpty()) {
            return result.summary(label + " already links to that issue.").build();
        }
        changes.addAll(link);
        getOWLModelManager().applyChanges(changes);
        result.note("Stored as IAO:0000233, so it travels with the ontology and any release.");
        return result.summary("Linked " + label + " to " + url + ".").build();
    }

    /**
     * Opens the issue in the user's browser.
     *
     * <p>Failure is reported rather than swallowed: on a headless or restricted desktop
     * {@code Desktop} is simply unavailable, and a menu item that appears to do nothing is the
     * worst outcome - so the address is put in the result where it can be copied.
     */
    private OperationResult open(OperationResult.Builder result, String label) {
        if (url.isEmpty()) {
            return result.failed("No address to open.").build();
        }
        result.note(url);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return result.summary("Opened the discussion for " + label + ".").build();
            }
        } catch (Exception cannotOpen) {
            return result.failed("Could not open a browser: " + cannotOpen.getMessage()
                    + " The address is above - copy it.").build();
        }
        return result.failed("This desktop cannot open a browser from Protege. The address is "
                + "above - copy it.").build();
    }
}
