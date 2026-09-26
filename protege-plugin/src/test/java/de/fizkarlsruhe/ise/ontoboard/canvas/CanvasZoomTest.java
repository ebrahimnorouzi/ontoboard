package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Fit to window, and the number in the toolbar.
 *
 * <p>Worth arithmetic tests because the two failures this replaces are both silent. Zoomed far out
 * the canvas is an empty grey grid, indistinguishable from an empty board or a broken plugin, and
 * before this there was no readout to say which and no Fit to get back. And a fit computed from
 * {@code mxGraphView.getGraphBounds()} without dividing by the current scale is right at 100% and
 * wrong everywhere else - the kind of bug that passes every manual test done from 100%.
 */
class CanvasZoomTest {

    @Test
    void contentLargerThanTheWindowIsScaledToFitWithAMargin() {
        // 2000 wide into 1048 of window: 1000 usable after 24 either side, so 0.5.
        assertEquals(0.5, CanvasZoom.scaleToFit(2000, 1000, 1048, 2000), 1e-9);
    }

    @Test
    void theTighterOfTheTwoDimensionsWins() {
        // Width would allow 0.5, height only 0.25. Fitting means fitting both.
        assertEquals(0.25, CanvasZoom.scaleToFit(2000, 2000, 1048, 548), 1e-9);
    }

    @Test
    void fitNeverMagnifies() {
        // Four nodes in a big window. Fit means "show everything", and everything is already shown;
        // jumping to 300% is a zoom level the user did not ask for and then has to undo.
        assertEquals(1.0, CanvasZoom.scaleToFit(200, 150, 1600, 1200), 1e-9);
    }

    @Test
    void anEmptyBoardSitsAtOneToOne() {
        assertEquals(1.0, CanvasZoom.scaleToFit(0, 0, 1000, 800), 1e-9);
        assertEquals(1.0, CanvasZoom.scaleToFit(500, 400, 0, 0), 1e-9);
    }

    @Test
    void aWindowTooSmallToFitAnythingStillPaints() {
        // The panel dragged narrower than the margins. Returning 0 here would divide by nothing
        // downstream and paint an empty canvas - reported as "OntoBoard went blank".
        double scale = CanvasZoom.scaleToFit(2000, 1000, 40, 40);

        assertEquals(CanvasZoom.MIN_SCALE, scale, 1e-9);
        assertTrue(scale > 0);
    }

    @Test
    void theResultStaysInsideTheRangeTheCanvasSupports() {
        assertEquals(CanvasZoom.MIN_SCALE, CanvasZoom.scaleToFit(1e9, 1e9, 1000, 800), 1e-9);
        assertEquals(CanvasZoom.MIN_SCALE, CanvasZoom.clamp(0.0001), 1e-9);
        assertEquals(CanvasZoom.MAX_SCALE, CanvasZoom.clamp(99), 1e-9);
        assertEquals(1.0, CanvasZoom.clamp(1.0), 1e-9);
    }

    @Test
    void nonsenseInputsDoNotProduceANonsenseScale() {
        assertEquals(1.0, CanvasZoom.scaleToFit(Double.NaN, 100, 500, 500), 1e-9);
        assertEquals(1.0, CanvasZoom.scaleToFit(100, 100, Double.POSITIVE_INFINITY, 500), 1e-9);
        assertEquals(CanvasZoom.MIN_SCALE, CanvasZoom.clamp(Double.NaN), 1e-9);
        assertEquals(CanvasZoom.MIN_SCALE, CanvasZoom.clamp(-2), 1e-9);
    }

    @Test
    void theReadoutIsWholePercent() {
        assertEquals("100%", CanvasZoom.readout(1.0));
        assertEquals("75%", CanvasZoom.readout(0.7513148));
        assertEquals("10%", CanvasZoom.readout(CanvasZoom.MIN_SCALE));
        assertEquals("400%", CanvasZoom.readout(CanvasZoom.MAX_SCALE));
    }

    @Test
    void theReadoutNeverShowsZeroOrNothing() {
        // A scale of 0 reaching the toolbar means something upstream is wrong; "0%" in the corner
        // reads as a broken widget rather than as the state it is reporting.
        assertEquals("100%", CanvasZoom.readout(0));
        assertEquals("100%", CanvasZoom.readout(Double.NaN));
    }
}
