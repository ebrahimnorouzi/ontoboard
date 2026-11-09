package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The property sections stand right of the classes, or below them when the board then has more
 * nearly a screen's shape.
 *
 * <p>Found by a tester: with one pattern imported the board was a strip 3,475 pixels wide and 596
 * tall, because the properties stood right of a frame already as wide as a screen, and Fit could
 * only show it at 19%.
 */
class SectionsPlacementTest {

    private static List<BoardSections.Term> terms(int classes, int properties) {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        for (int at = 0; at < classes; at++) {
            terms.add(new BoardSections.Term("C" + at, BoardSections.Kind.CLASS, "class " + at,
                    160, 40));
        }
        for (int at = 0; at < properties; at++) {
            terms.add(new BoardSections.Term("p" + at, BoardSections.Kind.OBJECT_PROPERTY,
                    "property " + at, 160, 40));
        }
        return terms;
    }

    private static double bottomOfClasses(BoardSections.Plan plan, int classes) {
        double bottom = 0;
        for (int at = 0; at < classes; at++) {
            bottom = Math.max(bottom, plan.positions.get("C" + at)[1] + 40);
        }
        return bottom;
    }

    @Test
    void underAFrameAsWideAsAScreenTheyGoBelow() {
        // A row of pattern frames 1,800 wide and 440 tall above an outline of three classes.
        BoardSections.Plan plan = BoardSections.plan(terms(3, 12),
                Collections.<BoardSections.Edge>emptyList(), 60, 560, 1860, 60);
        assertFalse(plan.sections.isEmpty());
        BoardSections.Section section = plan.sections.get(0);
        assertTrue(section.y > bottomOfClasses(plan, 3), "below the classes: " + section.y);
        assertTrue(section.x < 1860, "not right of the frames: " + section.x);
        for (int at = 0; at < 12; at++) {
            double[] p = plan.positions.get("p" + at);
            assertTrue(p[0] >= section.x && p[1] >= section.y
                    && p[0] + 160 <= section.x + section.w && p[1] + 40 <= section.y + section.h,
                    "property " + at + " inside its section");
        }
    }

    @Test
    void besideATallOutlineTheyStayRight() {
        // Twelve classes, each under the one before: an outline taller than it is wide, with room
        // to its right. (Many unrelated classes are not that: they are laid out in a screen-shaped
        // block, and below it is where the sections give the board a screen's shape.)
        List<BoardSections.Edge> chain = new ArrayList<BoardSections.Edge>();
        for (int at = 1; at < 12; at++) {
            chain.add(new BoardSections.Edge("C" + at, "C" + (at - 1), BoardSections.Link.SUBCLASS));
        }
        BoardSections.Plan plan = BoardSections.plan(terms(12, 6), chain, 60, 60,
                Double.NEGATIVE_INFINITY, 60);
        BoardSections.Section section = plan.sections.get(0);
        double right = 0;
        for (Map.Entry<String, double[]> at : plan.positions.entrySet()) {
            if (at.getKey().startsWith("C")) {
                right = Math.max(right, at.getValue()[0] + 160);
            }
        }
        assertTrue(section.x > right, "right of the classes: " + section.x + " > " + right);
    }
}
