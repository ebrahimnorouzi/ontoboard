package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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
 * Runs an operation off the event dispatch thread, behind a dialog that can be cancelled.
 *
 * <p>Not a nicety. ROBOT's report was measured taking <em>over ten minutes</em> on a 582-axiom
 * ontology in one classpath configuration - and a menu action that does its work on the EDT freezes
 * all of Protege for the duration, with no window repainting, no cancel, and nothing to say why.
 * A user would reasonably conclude the application had crashed and kill it, losing unsaved work.
 *
 * <p>So every OntoBoard menu operation runs here instead. The dialog appears only after a short
 * delay, because flashing a progress dialog for an operation that finishes in 80 milliseconds is
 * its own kind of unpleasant - {@link #SHOW_AFTER_MILLIS} is the threshold below which a user
 * perceives the result as immediate.
 *
 * <p><b>Cancel stops waiting; it cannot always stop working.</b> ROBOT operations are not
 * interruptible - there is no cancellation hook in {@code ReportOperation} - so cancelling
 * abandons the result and returns Protege to the user while the thread finishes in the background.
 * The dialog says exactly that rather than implying a clean stop, because a cancel button that
 * silently does nothing is worse than none.
 */
public final class BackgroundRun {

    /** Below this, a result feels immediate and a progress dialog is just a flash. */
    static final int SHOW_AFTER_MILLIS = 250;

    private BackgroundRun() {
    }

    /**
     * Runs {@code work} off the EDT, showing progress if it takes long enough to notice.
     *
     * @param parent the component to centre the dialog on
     * @param what the operation's name, for the dialog and for any failure
     * @param work the operation; runs on a worker thread and must not touch Swing
     * @return the result, a cancellation result if the user gave up, or a failed result
     */
    public static OperationResult execute(Component parent, String what,
            Callable<OperationResult> work) {
        final JDialog dialog = createDialog(parent, what);
        final long started = System.currentTimeMillis();

        SwingWorker<OperationResult, Void> worker = new SwingWorker<OperationResult, Void>() {
            @Override
            protected OperationResult doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                dialog.dispose();
            }
        };
        worker.execute();

        // Only show the dialog if the work outlasts the threshold. A modal dialog shown and
        // disposed within a frame or two reads as a glitch.
        javax.swing.Timer reveal = new javax.swing.Timer(SHOW_AFTER_MILLIS, e -> {
            if (!worker.isDone()) {
                dialog.setVisible(true);
            }
        });
        reveal.setRepeats(false);
        reveal.start();

        try {
            // setVisible on a modal dialog blocks the EDT until dispose(), which done() calls.
            // If the work finished first the dialog was never shown and get() returns at once.
            if (!worker.isDone()) {
                dialog.setVisible(true);
            }
            reveal.stop();
            if (worker.isCancelled()) {
                return cancelled(what, started);
            }
            return worker.get();
        } catch (CancellationException gaveUp) {
            return cancelled(what, started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OperationResult.failed(what, "Interrupted before it finished.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return OperationResult.failed(what, cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage());
        } finally {
            reveal.stop();
            dialog.dispose();
        }
    }

    private static OperationResult cancelled(String what, long started) {
        return OperationResult.of(what)
                .failed("Cancelled after " + ((System.currentTimeMillis() - started) / 1000)
                        + "s.")
                .note("ROBOT operations cannot be interrupted, so the work may still be running "
                        + "in the background. It will finish and be discarded.")
                .build();
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
        JLabel note = new JLabel("Protege stays responsive. Large ontologies can take minutes.");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        text.add(note);
        content.add(text, BorderLayout.NORTH);

        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        content.add(progress, BorderLayout.CENTER);

        JButton cancel = new JButton("Stop waiting");
        cancel.setToolTipText("Abandon the result. ROBOT cannot be interrupted, so the work "
                + "itself may continue in the background until it finishes.");
        cancel.addActionListener(a -> dialog.dispose());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setPreferredSize(new Dimension(420, 160));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }
}
