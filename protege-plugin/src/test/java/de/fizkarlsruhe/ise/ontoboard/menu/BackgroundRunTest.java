package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---------- kept running behind (1.155.0) ----------

    /** The button with this text in a window showing now, or null. */
    private static javax.swing.JButton buttonShowing(final String text) throws Exception {
        final javax.swing.JButton[] found = new javax.swing.JButton[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && found[0] == null) {
                    found[0] = buttonIn(window, text);
                }
            }
        });
        return found[0];
    }

    private static javax.swing.JButton buttonIn(java.awt.Component component, String text) {
        if (component instanceof javax.swing.JButton
                && text.equals(((javax.swing.JButton) component).getText())) {
            return (javax.swing.JButton) component;
        }
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                javax.swing.JButton found = buttonIn(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The progress window's Stop, once it shows - the window is up. */
    private static javax.swing.JButton waitForTheWindow() throws Exception {
        for (int tries = 0; tries < 100; tries++) {
            javax.swing.JButton stop = buttonShowing("Stop");
            if (stop != null) {
                return stop;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the progress window did not show");
    }

    /**
     * Build and Release may take a while: the person keeps working, and is told when the run is
     * done - the result opens, and the desktop says so (asked by the user).
     */
    @Test
    void aRunKeptBehindIsToldOfWhenItIsDone() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<OperationResult> given =
                new java.util.concurrent.atomic.AtomicReference<OperationResult>();
        final java.util.List<String> told = new java.util.concurrent.CopyOnWriteArrayList<String>();
        java.util.function.Consumer<String> desktop = BackgroundRun.whenDoneBehind;
        BackgroundRun.whenDoneBehind = told::add;
        try {
            final Callable<OperationResult> work = () -> {
                go.await(20, TimeUnit.SECONDS);
                return OperationResult.of("Build").summary("Built.").build();
            };
            SwingUtilities.invokeAndWait(() -> BackgroundRun.execute(null, "Build", work,
                    result -> {
                        given.set(result);
                        finished.countDown();
                    }, true));
            waitForTheWindow();
            final javax.swing.JButton keep = buttonShowing("Keep working");
            assertTrue(keep != null, "no Keep working on the progress window");
            SwingUtilities.invokeAndWait(keep::doClick);

            assertNull(buttonShowing("Stop"), "the progress window is still up");
            assertTrue(BackgroundRun.runningBehind().contains("Build"));
            go.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS), "the result was not given");
            assertEquals("Built.", given.get().getSummary());
            assertFalse(BackgroundRun.runningBehind().contains("Build"));
            assertEquals(1, told.size(), String.valueOf(told));
            assertTrue(told.get(0).startsWith("Build is done"), told.get(0));
        } finally {
            BackgroundRun.whenDoneBehind = desktop;
            go.countDown();
        }
    }

    /** Only a run that may go on behind offers it; the others keep their window. */
    @Test
    void aRunThatCannotGoBehindOffersNoWayToKeepWorking() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final Callable<OperationResult> work = () -> {
            go.await(20, TimeUnit.SECONDS);
            return OperationResult.of("Report").summary("Done.").build();
        };
        try {
            SwingUtilities.invokeAndWait(() -> BackgroundRun.execute(null, "Report", work,
                    result -> finished.countDown()));
            waitForTheWindow();
            assertNull(buttonShowing("Keep working"));
        } finally {
            go.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }
    }
}
