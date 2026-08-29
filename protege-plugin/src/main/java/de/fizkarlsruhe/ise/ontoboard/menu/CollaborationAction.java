package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.collab.CollabDialog;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSettings;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Collaboration &gt; Configure - the server, board and token for live editing.
 *
 * <p>The canvas has its own Collaborate button, which connects. This is the same settings dialog
 * reachable without opening the board, because a user setting a team up wants to check the
 * configuration before there is anything to draw.
 */
public class CollaborationAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected boolean runsInBackground() {
        // This action's work is a modal dialog, and opening one from a worker thread is a Swing
        // threading violation with intermittent, miserable symptoms.
        return false;
    }

    @Override
    protected String operationName() {
        return "Collaboration settings";
    }

    @Override
    protected boolean needsAnOntology() {
        // Configuration is worth checking before anything is open.
        return false;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        CollabSettings chosen = CollabDialog.show(getOWLWorkspace());
        if (chosen == null) {
            return null;
        }
        OperationResult.Builder result = OperationResult.of(operationName());
        if (chosen.isLive()) {
            result.summary("Ready to collaborate on board '" + chosen.getBoard() + "' at "
                    + chosen.getBridgeUrl() + ". Open the OntoBoard tab and press Collaborate to "
                    + "connect.");
            result.note("Your name comes from the access token, not from this dialog.");
        } else {
            result.summary("Working through git. " + chosen.explainWhyNotLive());
            result.note("This is a choice, not a failure - commit and push as usual.");
        }
        return result.build();
    }
}
