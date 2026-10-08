package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

/**
 * Keeps a dialog's form reachable on a small screen.
 *
 * <p>A {@code JOptionPane} sizes itself to its content's preferred size and then the window
 * manager clips whatever does not fit the display. Nothing warns about it: the form is laid out
 * correctly, every row exists, and the ones past the bottom edge - <b>including the OK button
 * underneath them</b> - simply cannot be seen or reached. The dialog looks complete and is
 * unusable, which is the worst shape a bug can take.
 *
 * <p>Reported from a 1138x640 desktop, against the collaboration dialog: five fields, three
 * notices and a Reset button came to more than 640px, so the notices and the buttons were below
 * the screen. The same thing had already been fixed once inside {@link ParameterDialog}, which is
 * why this is a class rather than a second copy of the logic - the next dialog to grow a row
 * should not have to rediscover it.
 *
 * <p>Nothing changes for a form that fits, so every dialog that was designed at its current size
 * keeps exactly the layout it was designed with and no scrollbar appears where none is needed.
 */
public final class TallForm {

    private TallForm() {
    }

    /**
     * How much of the screen a dialog's form may occupy before it is scrolled.
     *
     * <p>A fraction rather than a constant, because the screens this runs on differ by a factor
     * of three and a number tuned for one is wrong on the others. The remainder is for the things
     * around the form that also need to fit: the title bar, the explanation above it, the buttons
     * below it, and the taskbar.
     */
    static final double SHARE_OF_SCREEN = 0.62;

    /** Used when the screen cannot be measured - headless, or a display that answers nothing. */
    static final int FALLBACK_HEIGHT = 420;

    /** The tallest a form may be on this machine before scrolling. */
    public static int tallestUnscrolled() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return FALLBACK_HEIGHT;
            }
            int screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds().height;
            if (screen <= 0) {
                return FALLBACK_HEIGHT;
            }
            // Never below the fallback: on a very short display, scrolling everything into a
            // 200px window would be worse than the clipping it replaces.
            return Math.max(FALLBACK_HEIGHT, (int) (screen * SHARE_OF_SCREEN));
        } catch (RuntimeException cannotAsk) {
            return FALLBACK_HEIGHT;
        }
    }

    /**
     * The form itself when it fits, or the form in a scroll pane when it does not.
     *
     * <p>Vertical only. A horizontal scrollbar on a form would mean labels sliding out of view
     * beside their fields, which is harder to use than a narrow dialog.
     */
    public static Component scrolledIfTall(JComponent form) {
        if (form == null) {
            return form;
        }
        Dimension wanted = form.getPreferredSize();
        int ceiling = tallestUnscrolled();
        if (wanted.height <= ceiling) {
            return form;
        }
        JScrollPane scroller = new JScrollPane(form,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        // A mouse wheel notch should move a row, not a pixel.
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        scroller.setPreferredSize(new Dimension(
                wanted.width + scroller.getVerticalScrollBar().getPreferredSize().width,
                ceiling));
        return scroller;
    }
}
