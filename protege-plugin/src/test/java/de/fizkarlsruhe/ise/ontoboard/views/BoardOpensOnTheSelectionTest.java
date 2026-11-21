package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * A board shows the term Protege has selected when it is first built, as Protege's own views show
 * the selection they open with. The hand check of 1.155.0: a quality report's row clicked before
 * the board had ever been shown in that window selected the term, and the board, opened after,
 * showed another part of itself. Protege builds a view the first time it is shown, before it is
 * laid out, so the term is brought into view once the board has its size.
 */
class BoardOpensOnTheSelectionTest {

    @Test
    void aBoardNotYetLaidOutWaitsForItsSize() throws Exception {
        JViewport port = new JViewport();
        AtomicInteger shown = new AtomicInteger();

        SchemaCanvasView.onceSized(port, shown::incrementAndGet);
        drain();
        assertEquals(0, shown.get());

        port.setSize(400, 300);
        drain();
        assertEquals(1, shown.get());

        port.setSize(500, 300);
        drain();
        assertEquals(1, shown.get(), "once, not at every resize");
    }

    @Test
    void aBoardAlreadyLaidOutShowsItAtOnce() {
        JViewport port = new JViewport();
        port.setSize(400, 300);
        AtomicInteger shown = new AtomicInteger();

        SchemaCanvasView.onceSized(port, shown::incrementAndGet);

        assertEquals(1, shown.get());
    }

    /** The resize is told on the event queue, after what is waiting there. */
    private static void drain() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }
}
