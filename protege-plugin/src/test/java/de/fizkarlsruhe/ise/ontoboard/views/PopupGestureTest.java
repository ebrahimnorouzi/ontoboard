package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Each platform's order of events, spelled out, because the machine a test runs on only ever produces
 * its own: macOS and Linux raise the popup trigger on the press, Windows on the release.
 */
class PopupGestureTest {

    private static final int TOLERANCE = 4;
    private final JPanel source = new JPanel();

    private MouseEvent event(int id, int x, boolean trigger, int button) {
        return new MouseEvent(source, id, 0L,
                id == MouseEvent.MOUSE_PRESSED ? InputEvent.getMaskForButton(button) : 0,
                x, 10, 1, trigger, button);
    }

    private boolean click(boolean onPress, boolean onRelease, int releasedAt, int button) {
        PopupGesture gesture = new PopupGesture();
        gesture.pressed(event(MouseEvent.MOUSE_PRESSED, 10, onPress, button));
        return gesture.opensMenu(event(MouseEvent.MOUSE_RELEASED, releasedAt, onRelease, button),
                TOLERANCE);
    }

    @Test
    void aRightClickOpensTheMenuOnMacOsAndLinux() {
        assertTrue(click(true, false, 10, MouseEvent.BUTTON3));
    }

    @Test
    void aRightClickOpensTheMenuOnWindows() {
        assertTrue(click(false, true, 10, MouseEvent.BUTTON3));
    }

    @Test
    void aControlClickOpensTheMenuOnMacOs() {
        assertTrue(click(true, false, 10, MouseEvent.BUTTON1));
    }

    @Test
    void aLeftClickDoesNot() {
        assertFalse(click(false, false, 10, MouseEvent.BUTTON1));
    }

    /** A right-drag pans the board; it must not end in a menu on any platform. */
    @Test
    void aRightDragDoesNot() {
        assertFalse(click(true, false, 200, MouseEvent.BUTTON3));
        assertFalse(click(false, true, 200, MouseEvent.BUTTON3));
    }

    @Test
    void aTriggerOnThePressIsNotRememberedForTheNextClick() {
        PopupGesture gesture = new PopupGesture();
        gesture.pressed(event(MouseEvent.MOUSE_PRESSED, 10, true, MouseEvent.BUTTON3));
        assertTrue(gesture.opensMenu(
                event(MouseEvent.MOUSE_RELEASED, 10, false, MouseEvent.BUTTON3), TOLERANCE));
        gesture.pressed(event(MouseEvent.MOUSE_PRESSED, 10, false, MouseEvent.BUTTON1));
        assertFalse(gesture.opensMenu(
                event(MouseEvent.MOUSE_RELEASED, 10, false, MouseEvent.BUTTON1), TOLERANCE));
    }
}
