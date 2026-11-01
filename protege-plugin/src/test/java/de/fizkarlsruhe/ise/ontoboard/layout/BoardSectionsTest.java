package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The outline a board that shows a whole ontology is laid out as.
 *
 * <p>Measured on a published ontology of 660 terms before this: the layered layout put them on a
 * board 32,335px by 10,078px, every leaf in one row across the whole width.
 */
class BoardSectionsTest {

    private static BoardSections.Term term(String id, BoardSections.Kind kind) {
        return new BoardSections.Term(id, kind, id, 160, 60);
    }

    @Test
    void subclassesStackInTheColumnRightOfTheirSuperclass() {
        List<BoardSections.Term> terms = Arrays.asList(term("Thing", BoardSections.Kind.CLASS),
                term("A", BoardSections.Kind.CLASS), term("B", BoardSections.Kind.CLASS),
                term("C", BoardSections.Kind.CLASS));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("A", "Thing", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("B", "Thing", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("C", "Thing", BoardSections.Link.SUBCLASS));

        Map<String, double[]> at = BoardSections.plan(terms, edges, 40, 40).positions;

        assertEquals(40, at.get("Thing")[0], 0.001);
        for (String child : new String[] {"A", "B", "C"}) {
            assertEquals(40 + 160 + BoardSections.ACROSS, at.get(child)[0], 0.001,
                    child + " in the next column");
        }
        assertTrue(at.get("A")[1] < at.get("B")[1] && at.get("B")[1] < at.get("C")[1],
                "alphabetically, one under another");
        double middle = (at.get("A")[1] + at.get("C")[1]) / 2;
        assertEquals(middle, at.get("Thing")[1], 0.001, "the superclass centred on them");
    }

    @Test
    void anIndividualAndARestrictionHangOffTheirClassAndPropertiesHaveTheirOwnSection() {
        List<BoardSections.Term> terms = Arrays.asList(term("Person", BoardSections.Kind.CLASS),
                term("alice", BoardSections.Kind.INDIVIDUAL),
                term("exp|1", BoardSections.Kind.EXPRESSION),
                term("knows", BoardSections.Kind.OBJECT_PROPERTY),
                term("knowsWell", BoardSections.Kind.OBJECT_PROPERTY),
                term("age", BoardSections.Kind.DATA_PROPERTY));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("alice", "Person", BoardSections.Link.TYPE),
                new BoardSections.Edge("Person", "exp|1", BoardSections.Link.OTHER),
                new BoardSections.Edge("knowsWell", "knows", BoardSections.Link.SUB_PROPERTY),
                new BoardSections.Edge("knows", "Person", BoardSections.Link.OTHER));

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        double column = 40 + 160 + BoardSections.ACROSS;
        assertEquals(column, plan.positions.get("alice")[0], 0.001);
        assertEquals(column, plan.positions.get("exp|1")[0], 0.001);
        assertEquals(2, plan.sections.size(), "object and data properties, framed apart");
        assertEquals("Object properties", plan.sections.get(0).label);
        BoardSections.Section objects = plan.sections.get(0);
        for (String property : new String[] {"knows", "knowsWell"}) {
            double[] p = plan.positions.get(property);
            assertTrue(objects.x < p[0] && p[0] + 160 < objects.x + objects.w
                    && objects.y < p[1] && p[1] + 60 < objects.y + objects.h,
                    property + " inside its section");
        }
        assertTrue(plan.positions.get("knowsWell")[0] > plan.positions.get("knows")[0],
                "a sub-property right of its super-property");
    }

    /** Hundreds of terms: no two boxes overlap, and the board is not one long strip. */
    @Test
    void aLargeHierarchyIsCutIntoBlocksThatNeitherOverlapNorRunOff() {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        terms.add(term("root", BoardSections.Kind.CLASS));
        for (int branch = 0; branch < 12; branch++) {
            terms.add(term("b" + branch, BoardSections.Kind.CLASS));
            edges.add(new BoardSections.Edge("b" + branch, "root", BoardSections.Link.SUBCLASS));
            for (int leaf = 0; leaf < 30; leaf++) {
                String id = "b" + branch + "l" + leaf;
                terms.add(term(id, BoardSections.Kind.CLASS));
                edges.add(new BoardSections.Edge(id, "b" + branch, BoardSections.Link.SUBCLASS));
            }
        }

        Map<String, double[]> at = BoardSections.plan(terms, edges, 40, 40).positions;

        assertEquals(terms.size(), at.size());
        List<Rectangle2D> boxes = new ArrayList<Rectangle2D>();
        double right = 0;
        double bottom = 0;
        for (double[] p : at.values()) {
            Rectangle2D box = new Rectangle2D.Double(p[0], p[1], 160, 60);
            for (Rectangle2D other : boxes) {
                assertFalse(box.intersects(other), box + " overlaps " + other);
            }
            boxes.add(box);
            right = Math.max(right, p[0] + 160);
            bottom = Math.max(bottom, p[1] + 60);
        }
        double aspect = right / bottom;
        assertTrue(aspect > 0.4 && aspect < 4, "roughly screen-shaped: " + right + "x" + bottom);
    }

    @Test
    void aCycleInTheHierarchyDoesNotLoseATerm() {
        List<BoardSections.Term> terms = Arrays.asList(term("A", BoardSections.Kind.CLASS),
                term("B", BoardSections.Kind.CLASS));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("A", "B", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("B", "A", BoardSections.Link.SUBCLASS));

        assertEquals(2, BoardSections.plan(terms, edges, 0, 0).positions.size());
    }
}
