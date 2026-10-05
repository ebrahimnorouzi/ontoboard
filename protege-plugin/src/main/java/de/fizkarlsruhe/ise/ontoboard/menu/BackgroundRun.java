package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Runs an operation off the event dispatch thread, behind a dialog that can be abandoned.
 *
 * <p>Not a nicety. ROBOT's report was measured taking <em>over ten minutes</em> on a 582-axiom
 * ontology in one classpath configuration - and a menu action that does its work on the EDT freezes
 * all of Protege for the duration, with no window repainting, no cancel, and nothing to say why.
 * A user would reasonably conclude the application had crashed and kill it, losing unsaved work.
 *
 * <p><b>The EDT is never blocked on the worker.</b> This class used to end in
 * {@code return worker.get()}, which reads as harmless because {@code done()} disposes the dialog
 * first - but the cancel button disposed the dialog <em>without</em> cancelling, so
 * {@code isCancelled()} was false and the EDT fell into {@code get()} with no modal event pump
 * left running. An operation that then called {@code SwingUtilities.invokeAndWait} from the
 * worker - which Transform, Import terms and Open from GitHub all do, to apply changes through
 * the model manager - queued a Runnable onto an EDT that would never run it again. The worker
 * waited on the EDT, the EDT waited on the worker, and Protege froze permanently with no window
 * on screen and every unsaved edit lost to the kill that followed.
 *
 * <p>So the result arrives through a callback instead. The EDT starts the work and returns to the
 * event loop; the worker's {@code done()} hands the result back on the EDT. Nothing blocks
 * anything, and {@code invokeAndWait} from a worker is safe because the EDT is always pumping.
 *
 * <p><b>Abandoning stops waiting, and since 1.85.0 it also stops an external command.</b> The two
 * halves are different and the dialog says which is which.
 *
 * <p>An in-process ROBOT operation is not interruptible - there is no cancellation hook in
 * {@code ReportOperation} - so for those the button returns Protege to the user while the thread
 * finishes in the background. What it can still do is stop the work taking effect:
 * {@link #abandoned()} lets an operation check, before it writes anything, whether the user has
 * walked away. An operation that applied changes to the ontology after the user cancelled would
 * be worse than one that could not be cancelled at all.
 *
 * <p>An <em>external</em> command - a build, a project script, a git command - is a child process,
 * and that is now killed outright through
 * {@link de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Cancellation}. Before this, pressing the
 * button on a forty-minute ODK build dismissed the dialog and left the container running to
 * completion: the one case where "cannot be cancelled" cost machine time rather than patience.
 */
public final class BackgroundRun {

    /** Below this, a result feels immediate and a progress dialog is just a flash. */
    static final int SHOW_AFTER_MILLIS = 250;

    /**
     * Set on the worker thread for the duration of the work, so the work can ask.
     *
     * <p>A thread-local rather than a parameter because the work is a {@code Callable} handed in
     * by {@link OntoBoardAction}, and threading a token through every operation to reach the two
     * that need it would be worse than asking.
     */
    private static final ThreadLocal<AtomicBoolean> ABANDONED = new ThreadLocal<AtomicBoolean>();

    private BackgroundRun() {
    }

    /**
     * Whether the user has stopped waiting for the work running on this thread.
     *
     * <p>Checked by anything that is about to modify the ontology or write a file. Returns false
     * when nothing is running here, so it is safe to call from anywhere.
     */
    public static boolean abandoned() {
        AtomicBoolean flag = ABANDONED.get();
        return flag != null && flag.get();
    }

    /**
     * Runs {@code work} off the EDT and hands the result to {@code onFinished} on the EDT.
     *
     * <p>Returns immediately. {@code onFinished} is called exactly once - with the result, with a
     * cancellation result if the user stopped waiting, or with a failed result if the work threw.
     *
     * @param parent the component to centre the dialog on
     * @param what the operation's name, for the dialog and for any failure
     * @param work the operation; runs on a worker thread and must not touch Swing directly
     * @param onFinished called on the EDT; a null result means the operation reported itself
     */
    public static void execute(final Component parent, final String what,
            final Callable<OperationResult> work, final Consumer<OperationResult> onFinished) {
        final JDialog dialog = createDialog(parent, what);
        final long started = System.currentTimeMillis();
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        // Guarantees onFinished runs once. Both the abandon button and done() race to deliver.
        final AtomicBoolean delivered = new AtomicBoolean(false);

        // Created here, on the EDT, so the button can hold it before the worker has started. A
        // press in the gap between "run the build" and the container actually launching would
        // otherwise cancel nothing.
        final de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Cancellation cancellation =
                new de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Cancellation();

        final SwingWorker<OperationResult, Void> worker =
                new SwingWorker<OperationResult, Void>() {
            @Override
            protected OperationResult doInBackground() throws Exception {
                ABANDONED.set(abandoned);
                de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.cancelWith(cancellation);
                try {
                    return work.call();
                } finally {
                    ABANDONED.remove();
                    de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.stopCancelling();
                }
            }

            @Override
            protected void done() {
                dialog.dispose();
                if (!delivered.compareAndSet(false, true)) {
                    // The user already stopped waiting and has their answer. Whatever this
                    // produced is discarded rather than appearing minutes later over their work.
                    return;
                }
                onFinished.accept(resultOf(this, what));
            }
        };

        abandonButton(dialog).addActionListener(a -> {
            abandoned.set(true);
            // Kills the external command if there is one. An in-process ROBOT operation keeps
            // going - it has no cancellation hook - but a build, a script or a git command stops
            // here rather than holding a container for the rest of its run.
            cancellation.cancel();
            dialog.dispose();
            if (delivered.compareAndSet(false, true)) {
                onFinished.accept(cancelled(what, started));
            }
        });

        worker.execute();

        // Shown only if the work outlasts the threshold: a modal dialog shown and disposed within
        // a frame or two reads as a glitch. The timer is what enforces the delay - the EDT is
        // free in the meantime, which is the whole point.
        javax.swing.Timer reveal = new javax.swing.Timer(SHOW_AFTER_MILLIS, e -> {
            if (!worker.isDone() && !abandoned.get()) {
                dialog.setVisible(true);
            }
        });
        reveal.setRepeats(false);
        reveal.start();
    }

    /** The worker's outcome as a result, with a thrown exception turned into a failed one. */
    private static OperationResult resultOf(SwingWorker<OperationResult, Void> worker,
            String what) {
        try {
            return worker.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OperationResult.failed(what, "Interrupted before it finished.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            String message = cause.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = cause instanceof LinkageError
                        ? "This operation needs a newer OWL API than this Protege supplies. "
                                + "Protege 5.6 or later is known to work. ("
                                + cause.getClass().getSimpleName() + ")"
                        : "It failed with " + cause.getClass().getSimpleName()
                                + " and no message.";
            }
            return OperationResult.failed(what, message);
        } catch (RuntimeException wrongState) {
            // get() on a worker that is done cannot normally throw this; reporting beats
            // vanishing.
            return OperationResult.failed(what, wrongState.toString());
        }
    }

    private static OperationResult cancelled(String what, long started) {
        return OperationResult.of(what)
                .failed("Stopped after " + ((System.currentTimeMillis() - started) / 1000) + "s.")
                .note("An external command - a build, a script, a git command - was killed. An "
                        + "in-process ROBOT operation cannot be interrupted and may still be "
                        + "finishing in the background, but nothing it produces will be applied "
                        + "or shown.")
                .build();
    }

    /** The dialog's abandon button, so the caller can wire it after the worker exists. */
    private static JButton abandonButton(JDialog dialog) {
        return (JButton) dialog.getRootPane().getClientProperty("ontoboard.abandon");
    }

    private static JDialog createDialog(Component parent, String what) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "OntoBoard", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(what + " is running...");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        JLabel note = new JLabel("Large ontologies can take minutes.");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        text.add(note);
        content.add(text, BorderLayout.NORTH);

        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        content.add(progress, BorderLayout.CENTER);

        JButton abandon = new JButton("Stop");
        abandon.setToolTipText("<html>Kills an external command - a build, a script, a git "
                + "command - outright.<br>An in-process ROBOT operation cannot be interrupted "
                + "and may keep running,<br>but nothing it produces will be applied.</html>");
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        buttons.add(abandon);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().putClientProperty("ontoboard.abandon", abandon);

        dialog.setContentPane(content);
        dialog.setPreferredSize(new Dimension(420, 160));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }
}
