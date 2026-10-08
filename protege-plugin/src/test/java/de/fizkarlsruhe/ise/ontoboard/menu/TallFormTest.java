package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.Test;

/**
 * A dialog taller than the screen, which is a bug that looks like a finished dialog.
 *
 * <p>Reported against the collaboration dialog on a 1138x640 display: five fields, three notices
 * and a Reset button came to more than 640px, so the notices and the OK button sat below the
 * bottom edge - laid out, present, and impossible to see or click. {@code JOptionPane} sizes
 * itself to its content and the window manager clips the rest without a word.
 *
 * <p>These run headless, which is also the case {@link TallForm#tallestUnscrolled} has to answer
 * for without a display to measure.
 */
class TallFormTest {

    private static JPanel formOfHeight(int height) {
        JPanel form = new JPanel();
        form.setPreferredSize(new Dimension(400, height));
        return form;
    }

    /** A form that fits is handed back untouched, so no existing dialog changes. */
    @Test
    void aFormThatFitsIsNotWrapped() {
        JPanel form = formOfHeight(120);

        assertSame(form, TallForm.scrolledIfTall(form),
                "wrapping a short form would put a scroll pane where none is needed");
    }

    /** A form that does not fit is scrolled rather than clipped. */
    @Test
    void aTallFormIsScrolled() {
        JPanel form = formOfHeight(TallForm.tallestUnscrolled() + 400);

        Component wrapped = TallForm.scrolledIfTall(form);

        assertTrue(wrapped instanceof JScrollPane, "a tall form must become reachable");
        assertSame(form, ((JScrollPane) wrapped).getViewport().getView());
    }

    /**
     * The scrolled dialog is no taller than the ceiling.
     *
     * <p>The whole point: if the scroll pane kept the form's own height the dialog would be
     * exactly as tall, and exactly as clipped, as before.
     */
    @Test
    void theScrolledFormIsBoundedByTheCeiling() {
        JScrollPane wrapped = (JScrollPane) TallForm.scrolledIfTall(
                formOfHeight(TallForm.tallestUnscrolled() * 3));

        assertEquals(TallForm.tallestUnscrolled(), wrapped.getPreferredSize().height);
    }

    /** Wide enough for the form plus the scrollbar, so nothing is cut off sideways instead. */
    @Test
    void theScrollbarDoesNotEatTheForm() {
        JPanel form = formOfHeight(TallForm.tallestUnscrolled() + 200);
        JScrollPane wrapped = (JScrollPane) TallForm.scrolledIfTall(form);

        assertTrue(wrapped.getPreferredSize().width >= form.getPreferredSize().width,
                "the scrollbar must be added to the width, not taken out of it");
    }

    /**
     * No horizontal scrollbar.
     *
     * <p>A form that scrolls sideways slides labels out of view beside their fields, which is
     * harder to use than a narrow dialog.
     */
    @Test
    void itNeverScrollsSideways() {
        JScrollPane wrapped = (JScrollPane) TallForm.scrolledIfTall(
                formOfHeight(TallForm.tallestUnscrolled() + 200));

        assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                wrapped.getHorizontalScrollBarPolicy());
    }

    /**
     * The ceiling is a share of the screen, never absurd, and always answerable.
     *
     * <p>Headless is the case this test actually runs in, and it is also what a display that
     * answers nothing looks like - so the fallback is the normal path here, not an edge case.
     */
    @Test
    void theCeilingIsAlwaysUsable() {
        int ceiling = TallForm.tallestUnscrolled();

        assertTrue(ceiling >= TallForm.FALLBACK_HEIGHT,
                "scrolling a form into a very short window is worse than the clipping it "
                        + "replaces: " + ceiling);
        assertTrue(ceiling < 10000, "that is not a screen: " + ceiling);
    }

    /** Null is handed back rather than throwing, because a dialog must still open. */
    @Test
    void nothingToWrapIsNotACrash() {
        assertEquals(null, TallForm.scrolledIfTall(null));
    }
}
