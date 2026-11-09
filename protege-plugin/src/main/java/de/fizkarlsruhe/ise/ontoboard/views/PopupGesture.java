package de.fizkarlsruhe.ise.ontoboard.views;

import java.awt.Point;
import java.awt.event.MouseEvent;

/**
 * Tells a right-click on the board from a right-drag, on every platform.
 *
 * <p>Swing raises the popup trigger on the press on macOS and Linux, and on the release on Windows.
 * The board asked only at the release, so on macOS and Linux its context menu never opened: no
 * <i>New subclass...</i>, no <i>Relate to another term...</i>, and nothing to say why. Found when a
 * walkthrough for a user study replayed each platform's order of events; the Windows machine this is
 * developed on could not have shown it.
 *
 * <p>The menu still opens at the release everywhere. A right-drag pans this board, and a menu opened
 * on the press would swallow the drag.
 */
final class PopupGesture {

    private Point pressedAt;
    private boolean triggeredOnPress;

    void pressed(MouseEvent event) {
        pressedAt = event.getPoint();
        triggeredOnPress = event.isPopupTrigger();
    }

    /** Whether this release ends a right-click, rather than a left-click or a right-drag. */
    boolean opensMenu(MouseEvent released, int tolerance) {
        boolean triggered = triggeredOnPress || released.isPopupTrigger();
        triggeredOnPress = false;
        return triggered
                && (pressedAt == null || pressedAt.distance(released.getPoint()) <= tolerance);
    }
}
