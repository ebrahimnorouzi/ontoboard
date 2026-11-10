package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Frames keep the rows a board already has when it is arranged again.
 *
 * <p>Found by a tester adding subclasses one after another to one pattern: the frame being worked in
 * jumped 1,640 pixels between two of them, because each arrangement folded every frame into rows
 * afresh and one frame growing moved where the rows broke.
 */
class FrameRowsTest {

    private static FramedGroup.Placement frame(String label, double w, double h) {
        CanvasLayout board = new CanvasLayout();
        FramedGroup.Placement placement = FramedGroup.plan(label,
                Collections.singletonList("http://example.org/" + label.replace(' ', '_')), board,
                Collections.singletonMap("http://example.org/" + label.replace(' ', '_'),
                        new double[] {w - 2 * FramedGroup.PADDING,
                                h - 2 * FramedGroup.PADDING - FramedGroup.LABEL_BAND}));
        return placement;
    }

    private static Map<String, Integer> rows(Object... labelThenRow) {
        Map<String, Integer> rows = new HashMap<String, Integer>();
        for (int at = 0; at < labelThenRow.length; at += 2) {
            rows.put((String) labelThenRow[at], (Integer) labelThenRow[at + 1]);
        }
        return rows;
    }

    @Test
    void eachFrameGoesBackInItsRowInItsOrder() {
        FramedGroup.Placement a = frame("A", 600, 300);
        FramedGroup.Placement b = frame("B", 600, 500);
        FramedGroup.Placement c = frame("C", 600, 200);
        FramedGroup.wrap(Arrays.asList(a, b, c), rows("A", 0, "B", 0, "C", 1));

        assertEquals(a.getFrame().y, b.getFrame().y, "A and B share a row");
        assertTrue(a.getFrame().x < b.getFrame().x, "A left of B");
        assertEquals(b.getFrame().y + b.getFrame().h + FramedGroup.MARGIN, c.getFrame().y,
                "C under the row, below its tallest frame");
        assertEquals(FramedGroup.MARGIN, c.getFrame().x);
    }

    @Test
    void aFrameThatGrewKeepsItsNeighboursInTheirRows() {
        FramedGroup.Placement a = frame("A", 600, 300);
        FramedGroup.Placement b = frame("B", 600, 300);
        FramedGroup.Placement c = frame("C", 600, 300);
        // Folded afresh, three frames this wide fill one row; A grown to 1,400 would push B and C
        // onto rows of their own. Kept, they stay where the board had them.
        FramedGroup.Placement grown = frame("A", 1400, 700);
        FramedGroup.wrap(Arrays.asList(grown, b, c), rows("A", 0, "B", 0, "C", 0));

        assertEquals(grown.getFrame().y, b.getFrame().y);
        assertEquals(grown.getFrame().y, c.getFrame().y);
        assertEquals(grown.getFrame().x + grown.getFrame().w + FramedGroup.MARGIN, b.getFrame().x);
        assertTrue(a.getFrame() != grown.getFrame());
    }

    @Test
    void aNewFrameJoinsTheLastRowWhenItFitsAndStartsOneWhenItDoesNot() {
        FramedGroup.Placement a = frame("A", 600, 300);
        FramedGroup.Placement fits = frame("New", 400, 200);
        FramedGroup.wrap(Arrays.asList(a, fits), rows("A", 0));
        assertEquals(a.getFrame().y, fits.getFrame().y, "joins A's row");

        FramedGroup.Placement wide = frame("Wide", 1700, 300);
        FramedGroup.Placement again = frame("A", 600, 300);
        FramedGroup.wrap(Arrays.asList(again, wide), rows("A", 0));
        assertTrue(wide.getFrame().y > again.getFrame().y, "a row of its own");
        assertEquals(FramedGroup.MARGIN, wide.getFrame().x);
    }

    @Test
    void theTermsGoWithTheirFrame() {
        FramedGroup.Placement a = frame("A", 600, 300);
        FramedGroup.Placement b = frame("B", 600, 300);
        List<FramedGroup.Placement> both = Arrays.asList(a, b);
        FramedGroup.wrap(both, rows("A", 0, "B", 1));
        for (FramedGroup.Placement placement : both) {
            for (CanvasLayout.NodeLayout node : placement.getNodes().values()) {
                CanvasLayout.FrameLayout frame = placement.getFrame();
                assertTrue(node.x >= frame.x && node.y >= frame.y
                        && node.x + node.w <= frame.x + frame.w
                        && node.y + node.h <= frame.y + frame.h, placement.getFrame().label);
            }
        }
    }

    @Test
    void withNoRowsItFoldsAsBefore() {
        FramedGroup.Placement a = frame("A", 600, 300);
        FramedGroup.Placement b = frame("B", 600, 300);
        FramedGroup.Placement a2 = frame("A", 600, 300);
        FramedGroup.Placement b2 = frame("B", 600, 300);
        FramedGroup.wrap(Arrays.asList(a, b));
        FramedGroup.wrap(Arrays.asList(a2, b2), Collections.<String, Integer>emptyMap());
        assertEquals(b.getFrame().x, b2.getFrame().x);
        assertEquals(b.getFrame().y, b2.getFrame().y);
    }
}
