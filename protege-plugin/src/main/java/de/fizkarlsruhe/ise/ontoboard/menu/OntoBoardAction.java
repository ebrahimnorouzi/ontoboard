package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.event.ActionEvent;
import javax.swing.JOptionPane;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for everything on the OntoBoard menu.
 *
 * <p>Exists so that no menu entry can fail badly. Protege swallows an exception thrown out of an
 * action into its log, where a user will never look, and the menu item simply appears to do
 * nothing - which is the single most common way a plugin feature is reported as "broken" when it
 * is in fact throwing. Subclasses implement {@link #run(OWLOntology)} and return a result; this
 * class catches everything, turns it into a failed result, and shows it.
 *
 * <p>It also settles the question every action would otherwise answer differently: what to do when
 * no ontology is open. Overriding {@link #needsAnOntology()} is the only choice a subclass has, and
 * the message is written once.
 */
public abstract class OntoBoardAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(OntoBoardAction.class);

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    /** What to call this in a result dialog and in the log. */
    protected abstract String operationName();

    /**
     * Does the work.
     *
     * <p>Return a result rather than opening a dialog: the caller shows it, so every operation is
     * reported the same way and can be saved. Returning null means the action handled its own UI
     * and there is nothing to show - a wizard the user cancelled, for instance.
     *
     * @param ontology the active ontology, already checked when {@link #needsAnOntology()}
     */
    protected abstract OperationResult run(OWLOntology ontology);

    /** Whether to refuse when nothing is open. Most operations need an ontology; a few do not. */
    protected boolean needsAnOntology() {
        return true;
    }

    /**
     * Asks for whatever the operation needs before it runs.
     *
     * <p>Called on the event dispatch thread, so it may open a dialog; {@link #run} then executes
     * in the background with whatever was chosen. Separating the two is what lets an operation be
     * both configurable and non-blocking - asking on the worker thread would be a Swing threading
     * violation, and asking after the work started would be pointless.
     *
     * @return false to abandon the operation, which is what a cancelled dialog means
     */
    protected boolean configure() {
        return true;
    }

    /**
     * Whether the work runs on a background thread.
     *
     * <p>True for anything that computes. An action whose {@code run} only opens a dialog must
     * override this to false: a modal dialog opened from a worker thread is a Swing threading
     * violation, and the symptoms are intermittent and horrible to diagnose.
     */
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    public final void actionPerformed(ActionEvent event) {
        OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        if (needsAnOntology() && ontology == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Open an ontology first - " + operationName() + " works on the one you have "
                            + "open.",
                    "Nothing is open", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final OWLOntology target = ontology;
        try {
            if (!configure()) {
                // Cancelled at the parameter dialog. Nothing ran, so there is nothing to report.
                return;
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("OntoBoard: {} could not be configured", operationName(), failure);
            ResultDialog.show(getOWLWorkspace(),
                    OperationResult.failed(operationName(), describe(failure)));
            return;
        }
        if (runsInBackground()) {
            // Off the EDT, and the result comes back through a callback rather than a blocking
            // get(). ROBOT's report was measured taking over ten minutes on a 582-axiom ontology:
            // an action doing that on the dispatch thread freezes all of Protege with no repaint
            // and no way out, and an action that blocks the EDT *waiting* for it deadlocks
            // outright against any operation that needs the EDT to apply its changes.
            BackgroundRun.execute(getOWLWorkspace(), operationName(), () -> run(target),
                    result -> {
                        if (result != null) {
                            ResultDialog.show(getOWLWorkspace(), result);
                        }
                    });
            return;
        }
        OperationResult result;
        try {
            result = run(target);
        } catch (RuntimeException | LinkageError failure) {
            // LinkageError as well as RuntimeException: several ROBOT operations fail that way on
            // Protege 5.5's older OWL API, and a NoSuchMethodError escaping into Protege's log is
            // exactly the silent nothing this class exists to prevent.
            LOGGER.warn("OntoBoard: {} failed", operationName(), failure);
            result = OperationResult.failed(operationName(), describe(failure));
        }
        if (result != null) {
            ResultDialog.show(getOWLWorkspace(), result);
        }
    }

    /**
     * A failure as a sentence rather than a class name.
     *
     * <p>{@code NoSuchMethodError} on its own tells a user nothing; the message underneath usually
     * does, and when it does not, the type at least says what kind of wrong it was.
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        if (failure instanceof LinkageError) {
            return "This operation needs a newer OWL API than this Protege supplies. Protege 5.6 "
                    + "or later is known to work. (" + failure.getClass().getSimpleName() + ")";
        }
        return "It failed with " + failure.getClass().getSimpleName() + " and no message.";
    }
}
