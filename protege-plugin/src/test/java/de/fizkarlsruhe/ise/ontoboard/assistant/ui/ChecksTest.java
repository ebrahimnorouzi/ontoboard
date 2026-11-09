package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChecksTest {

    @BeforeEach
    void needsADisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static int[] pixels(JComponent row) {
        BufferedImage image = new BufferedImage(row.getWidth(), row.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        row.paint(g);
        g.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0,
                image.getWidth());
    }

    /** As a focus change would: the focus manager names the box, and its listeners hear it. */
    private static void focus(JCheckBox box, boolean gained) {
        for (FocusListener listener : box.getFocusListeners()) {
            FocusEvent event = new FocusEvent(box,
                    gained ? FocusEvent.FOCUS_GAINED : FocusEvent.FOCUS_LOST);
            if (gained) {
                listener.focusGained(event);
            } else {
                listener.focusLost(event);
            }
        }
    }

    /**
     * Review 1.144.0 settings-ui: with the words beside the box, Protégé's look and feel - which
     * draws a check box's focus around its text - showed no focus on five switches, and a
     * keyboard user pressing Space could not see what it would change.
     */
    @Test
    void aFocusedBoxShowsItsFocus() throws Exception {
        boolean[] seen = onEdt(() -> {
            JCheckBox box = new JCheckBox();
            JComponent row = Checks.labelled(box, "Save conversations in this project");
            JFrame frame = new JFrame();
            KeyboardFocusManager real = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            try {
                frame.setContentPane(row);
                frame.setSize(320, 80);
                frame.addNotify();
                frame.validate();
                int[] unfocused = pixels(row);
                KeyboardFocusManager.setCurrentKeyboardFocusManager(
                        new DefaultKeyboardFocusManager() {
                            @Override
                            public Component getFocusOwner() {
                                return box;
                            }
                        });
                focus(box, true);
                int[] focused = pixels(row);
                KeyboardFocusManager.setCurrentKeyboardFocusManager(real);
                focus(box, false);
                int[] again = pixels(row);
                return new boolean[] {java.util.Arrays.equals(unfocused, focused),
                    java.util.Arrays.equals(unfocused, again)};
            } finally {
                KeyboardFocusManager.setCurrentKeyboardFocusManager(real);
                frame.dispose();
            }
        });

        assertFalse(seen[0], "the focused row looks like the unfocused one");
        assertTrue(seen[1], "the focus stays drawn after it went");
    }
}
