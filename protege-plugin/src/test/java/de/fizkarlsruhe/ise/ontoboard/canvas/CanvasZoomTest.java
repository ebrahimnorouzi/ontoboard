package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
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

    /**
     * Framing a selection is allowed to magnify, and fitting the board is not.
     *
     * <p>The pair is the point: one 160x60 node in a 1200px window should fill it, because framing it
     * is the whole reason for asking; a board that already fits should stay where it is, because
     * drawing it four times bigger is a zoom nobody requested.
     */
    @Test
    void framingASelectionMagnifiesWhereFittingTheBoardDoesNot() {
        assertEquals(1.0, CanvasZoom.scaleToFit(160, 60, 1200, 800), 1e-9);

        double filled = CanvasZoom.scaleToFill(160, 60, 1200, 800);

        assertTrue(filled > 1.0, "framing one node should magnify it, got " + filled);
        // Width is the binding dimension: (1200 - 48) / 160 = 7.2, clamped to the ceiling.
        assertEquals(CanvasZoom.MAX_SCALE, filled, 1e-9);
    }

    @Test
    void framingObeysTheSameLimitsAsFitting() {
        assertEquals(CanvasZoom.MIN_SCALE, CanvasZoom.scaleToFill(1e9, 1e9, 1000, 800), 1e-9);
        assertEquals(CanvasZoom.MIN_SCALE, CanvasZoom.scaleToFill(2000, 1000, 40, 40), 1e-9);
        assertEquals(1.0, CanvasZoom.scaleToFill(0, 0, 1000, 800), 1e-9);
        assertEquals(1.0, CanvasZoom.scaleToFill(Double.NaN, 100, 500, 500), 1e-9);
    }

    /** On something too big to frame, the two agree - neither may magnify what already overflows. */
    @Test
    void thereIsNoDifferenceWhenTheContentIsTooBig() {
        assertEquals(CanvasZoom.scaleToFit(2000, 1000, 1048, 2000),
                CanvasZoom.scaleToFill(2000, 1000, 1048, 2000), 1e-9);
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

    /**
     * With nothing selected, a zoom keeps a box still rather than the empty band between two
     * frames (1.135.1, Windows study rehearsal): three presses of + after Fit slid the classes
     * out of view around a point where nothing was.
     */
    @Test
    void withNothingSelectedAZoomKeepsABoxStillRatherThanTheGapBetweenTwoFrames() {
        Rectangle view = new Rectangle(0, 0, 710, 480);
        Rectangle content = new Rectangle(10, 10, 660, 360);
        java.util.List<Rectangle> boxes = Arrays.asList(new Rectangle(40, 30, 50, 15),
                new Rectangle(380, 150, 50, 15), new Rectangle(60, 250, 50, 15),
                new Rectangle(420, 330, 50, 15));
        assertEquals(new Point(405, 157), CanvasZoom.anchor(view, null, content, boxes));
    }

    @Test
    void aSelectionInViewStaysStillAndTheAnswerIsInTheWindowsPixels() {
        Rectangle view = new Rectangle(300, 200, 710, 480);
        assertEquals(new Point(280, 230), CanvasZoom.anchor(view,
                new Rectangle(500, 400, 160, 60), new Rectangle(300, 200, 2000, 2000),
                Arrays.<Rectangle>asList()));
        assertEquals(new Point(125, 107), CanvasZoom.anchor(new Rectangle(0, 0, 710, 480),
                new Rectangle(2000, 2000, 160, 60), new Rectangle(0, 0, 3000, 3000),
                Arrays.asList(new Rectangle(100, 100, 50, 15))));
        assertEquals(new Point(355, 240), CanvasZoom.anchor(new Rectangle(0, 0, 710, 480), null,
                null, Arrays.<Rectangle>asList()));
    }

    /** The status line that named a zoom names the one the board is at now. */
    @Test
    void aStatusLineThatNamedAZoomNamesTheOneTheBoardIsAt() {
        assertEquals("Zoomed to 46%.", CanvasZoom.afterZoom("Fitted the board to the window at 26%.", 0.4577));
        assertEquals("Zoomed to 37%.", CanvasZoom.afterZoom("Zoomed to 31%.", 0.3744));
        assertEquals("Zoomed to 100%.", CanvasZoom.afterZoom(
                "Arranged and fitted to the window at 26%: Outline - classes as a tree, properties apart.",
                1.0));
        assertEquals("Fitted the board to the window at 26%.",
                CanvasZoom.afterZoom("Fitted the board to the window at 26%.", 0.2649));
    }

    /** Every line Fit and Arrange write about the zoom follows it, as the zoom's own line does. */
    @Test
    void everyLineFitAndArrangeWriteFollowsTheZoom() {
        for (String said : new String[] {"Fitted the selection to the window at 120%.",
            "Fitted 3 selected to the window at 80%.",
            "Fitted the frame Measurement and setpoint to the window at 64%.",
            "Arranged and fitted to the window at 31%: Hierarchical (SubClassOf tree).",
            "Arranged and fitted to the window at 31%: Organic (force-directed)."
                    + " The 4 terms inside frames stay where they are.",
            "Arranged, at 50% so it can be read; Fit shows all of it: Grid."
                    + " The term inside a frame stays where it is.",
            "Arranged, at 50% so it can be read; Fit shows all of it: Circle.",
            CanvasZoom.fitted("the frame Heated to 100%", 0.64), CanvasZoom.zoomed(0.31)}) {
            assertEquals("Zoomed to 90%.", CanvasZoom.afterZoom(said, 0.9), said);
        }
        assertEquals("Fitted the frame Heated to 100% to the window at 64%.",
                CanvasZoom.fitted("the frame Heated to 100%", 0.64));
        assertEquals("Fitted the frame Heated to 100% to the window at 64%.",
                CanvasZoom.afterZoom("Fitted the frame Heated to 100% to the window at 64%.", 0.64),
                "the zoom it names, not the label's");
    }

    /**
     * A zoom leaves a line it did not write alone, even one that names a zoom (1.135.1 review).
     * After Relate the line said "...both ends in view at 80%. File > Save keeps it."; the next turn
     * of the wheel made it "Zoomed to 90%.", and File > Save then had no line asking for a save to
     * turn into "Saved.". A Find line quoting a label such as "heated to 100%" went the same way.
     */
    @Test
    void aZoomLeavesALineItDidNotWriteAloneThoughItNamesAZoom() {
        java.util.List<String> rewritten = new java.util.ArrayList<String>();
        for (String said : new String[] {
            "Related egg boiling to raw egg by has specified input: both ends in view at 80%."
                    + " File > Save keeps it.",
            "(1 of 2) heated to 100% - https://example.org/eggboil/EGGBOIL_0001007",
            "heated to 100% - https://example.org/eggboil/EGGBOIL_0001007",
            "heated to 100% is in this ontology but not on the board - Ctrl+Enter adds it.",
            "Added heated to 100% to the board.",
            "No term matching \"cooled to 4%\" - not on the board, and not in this ontology."}) {
            String after = CanvasZoom.afterZoom(said, 0.9);
            if (!said.equals(after)) {
                rewritten.add("\"" + said + "\" became \"" + after + "\"");
            }
        }
        assertTrue(rewritten.isEmpty(), String.join("; ", rewritten));
    }

    @Test
    void aZoomLeavesEveryOtherStatusLineAlone() {
        for (String said : new String[] {"Defined egg boiling. File > Save keeps it.",
            "(1 of 3) egg boiling - https://example.org/eggboil/EGGBOIL_0001000",
            "Added 50% fat milk to the board.", " "}) {
            assertEquals(said, CanvasZoom.afterZoom(said, 0.46));
        }
        assertEquals(null, CanvasZoom.afterZoom(null, 0.46));
    }
}
