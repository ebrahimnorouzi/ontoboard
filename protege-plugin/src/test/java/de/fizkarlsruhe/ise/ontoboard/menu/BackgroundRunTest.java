package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A run in the background, and the questions its work asks on the dispatch thread.
 *
 * <p>Found by hand in 1.154.0's release check: Refresh imports asked whether to build a module
 * from the copy the project keeps - and asked it within the quarter second after which the
 * progress window comes up, since the source refused the connection at once. The window came
 * up on top of the question, inside its modal loop, and once the question was answered neither
 * could close: the question waited for the window, the window for the run, the run for the
 * question. "Refresh imports is running..." stayed until Stop, and the refresh's result was
 * never shown.
 */
class BackgroundRunTest {

    @Test
    void aQuestionAskedAtOnceIsAnsweredAndTheRunFinishes() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
        final CountDownLatch finished = new CountDownLatch(1);
        // The question: modal, and answered - closed - well after the progress window's delay,
        // as a person answers.
        final Callable<OperationResult> work = () -> {
            String failure = OntoBoardAction.onDispatchThread(() -> {
                final JDialog question = new JDialog((java.awt.Frame) null, "A question", true);
                javax.swing.Timer answer = new javax.swing.Timer(
                        BackgroundRun.SHOW_AFTER_MILLIS * 4, e -> question.dispose());
                answer.setRepeats(false);
                answer.start();
                question.setVisible(true);
            });
            return OperationResult.of("Asked")
                    .summary(failure == null ? "Answered." : failure).build();
        };
        SwingUtilities.invokeAndWait(() -> BackgroundRun.execute(null, "Asked", work,
                result -> finished.countDown()));
        try {
            assertTrue(finished.await(10, TimeUnit.SECONDS),
                    "the run did not finish after its question was answered");
        } finally {
            // Whatever is still open - with the defect, the progress window over the question.
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window.isShowing()) {
                        window.dispose();
                    }
                }
            });
        }
    }
}
