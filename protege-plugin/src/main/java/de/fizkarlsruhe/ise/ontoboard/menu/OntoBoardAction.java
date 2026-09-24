package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.event.ActionEvent;
import java.io.File;
import javax.swing.JOptionPane;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
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
    /**
     * Applies changes through the model manager, on the dispatch thread.
     *
     * <p>Both halves matter and for different reasons. Through the model manager, so Protege
     * records them for Edit &gt; Undo and every view hears about them - applying to the OWL API
     * manager directly changes the ontology behind Protege's back. On the dispatch thread, because
     * applying fires listeners that rebuild Swing components, and doing that from a worker thread
     * is the kind of threading bug that shows up as an occasional blank panel weeks later.
     *
     * <p>{@code invokeAndWait}, not {@code invokeLater}: the result reports what was applied, and
     * reporting a change that has not happened yet would be a lie the user could act on.
     *
     * <p>Three actions had written this separately before it moved here.
     */
    /**
     * The file an ontology was loaded from, or null when it has never been saved.
     *
     * <p>Seven actions carried a private copy of this, each asking the <em>model manager's</em>
     * manager for the document IRI. That works for the ontology Protege has open and for nothing
     * else, which made every project-aware action unusable against an ontology built in memory -
     * including by the self-test, which is why they were excluded from it.
     *
     * <p>Asking the ontology's own manager first is also simply more correct: a document IRI is a
     * property of the manager that loaded the ontology, and for anything Protege owns that manager
     * <em>is</em> the model manager's, so nothing changes for the normal case. The fallback is kept
     * because an ontology can be handed about between managers.
     */
    protected File fileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        File own = fileFrom(ontology.getOWLOntologyManager(), ontology);
        if (own != null) {
            return own;
        }
        return getOWLModelManager() == null
                ? null
                : fileFrom(getOWLModelManager().getOWLOntologyManager(), ontology);
    }

    /**
     * The ODK project an ontology belongs to, or null when it has never been saved.
     *
     * <p>Takes the ontology rather than reading the open one, so an action can be run against
     * something other than the user's session - which is what lets the self-test drive the
     * project-aware actions against a scratch project instead of skipping them.
     */
    protected File projectRootOf(OWLOntology ontology) {
        File file = fileOf(ontology);
        return file == null ? null : ReleaseAction.projectRootOf(file);
    }

    private static File fileFrom(org.semanticweb.owlapi.model.OWLOntologyManager manager,
            OWLOntology ontology) {
        if (manager == null) {
            return null;
        }
        try {
            java.net.URI documentUri = manager.getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }

    protected void applyOnEventThread(final java.util.List<OWLOntologyChange> changes) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            getOWLModelManager().applyChanges(changes);
            return;
        }
        final java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        try {
            javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        getOWLModelManager().applyChanges(changes);
                    } catch (RuntimeException thrown) {
                        failure.set(thrown);
                    }
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while applying the changes");
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            throw new IllegalStateException(thrown.getCause() == null ? thrown.toString()
                    : String.valueOf(thrown.getCause().getMessage()));
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
