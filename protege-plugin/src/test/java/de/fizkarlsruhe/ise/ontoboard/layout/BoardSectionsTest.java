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
    void subclassesStandInTheRowBelowTheirSuperclassCentredUnderIt() {
        List<BoardSections.Term> terms = Arrays.asList(term("Thing", BoardSections.Kind.CLASS),
                term("A", BoardSections.Kind.CLASS), term("B", BoardSections.Kind.CLASS),
                term("C", BoardSections.Kind.CLASS));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("A", "Thing", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("B", "Thing", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("C", "Thing", BoardSections.Link.SUBCLASS));

        Map<String, double[]> at = BoardSections.plan(terms, edges, 40, 40).positions;

        assertEquals(40, at.get("Thing")[1], 0.001);
        for (String child : new String[] {"A", "B", "C"}) {
            assertEquals(40 + 60 + BoardSections.BELOW, at.get(child)[1], 0.001,
                    child + " in the row below");
        }
        assertTrue(at.get("A")[0] < at.get("B")[0] && at.get("B")[0] < at.get("C")[0],
                "alphabetically, side by side");
        assertEquals(at.get("B")[0], at.get("Thing")[0], 0.001, "the superclass centred above");
    }

    /** Asked for: the ABox below the TBox, each individual under the class it is typed with. */
    @Test
    void individualsAreABandBelowEveryClassUnderTheirOwnClass() {
        List<BoardSections.Term> terms = Arrays.asList(term("Pizza", BoardSections.Kind.CLASS),
                term("Margherita", BoardSections.Kind.CLASS),
                term("Diavola", BoardSections.Kind.CLASS),
                term("m1", BoardSections.Kind.INDIVIDUAL),
                term("d1", BoardSections.Kind.INDIVIDUAL),
                term("d2", BoardSections.Kind.INDIVIDUAL),
                term("lit|1", BoardSections.Kind.VALUE));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("Margherita", "Pizza", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("Diavola", "Pizza", BoardSections.Link.SUBCLASS),
                new BoardSections.Edge("m1", "Margherita", BoardSections.Link.TYPE),
                new BoardSections.Edge("d1", "Diavola", BoardSections.Link.TYPE),
                new BoardSections.Edge("d2", "Diavola", BoardSections.Link.TYPE),
                new BoardSections.Edge("d1", "lit|1", BoardSections.Link.OTHER));

        Map<String, double[]> at = BoardSections.plan(terms, edges, 0, 0).positions;

        double classesBottom = 0;
        for (String cls : new String[] {"Pizza", "Margherita", "Diavola"}) {
            classesBottom = Math.max(classesBottom, at.get(cls)[1] + 60);
        }
        for (String individual : new String[] {"m1", "d1", "d2", "lit|1"}) {
            assertTrue(at.get(individual)[1] > classesBottom, individual + " below the classes");
        }
        assertEquals(at.get("Margherita")[0], at.get("m1")[0], 0.001, "under its class");
        assertEquals(at.get("Diavola")[0], at.get("d1")[0], 0.001, "under its class");
        assertTrue(at.get("d2")[1] > at.get("d1")[1], "a column under the class");
        assertTrue(at.get("lit|1")[1] > at.get("d1")[1] && at.get("lit|1")[1] < at.get("d2")[1],
                "a value right below its individual");
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

        assertTrue(plan.positions.get("exp|1")[1] > plan.positions.get("Person")[1],
                "the restriction below its class");
        assertTrue(plan.positions.get("alice")[1] > plan.positions.get("exp|1")[1],
                "the individual below the classes and what hangs off them");
        assertEquals(2, plan.sections.size(), "object and data properties, framed apart");
        assertEquals("Object properties", plan.sections.get(0).label);
        BoardSections.Section objects = plan.sections.get(0);
        for (String property : new String[] {"knows", "knowsWell"}) {
            double[] p = plan.positions.get(property);
            assertTrue(objects.x < p[0] && p[0] + 160 < objects.x + objects.w
                    && objects.y < p[1] && p[1] + 60 < objects.y + objects.h,
                    property + " inside its section");
        }
        assertTrue(plan.positions.get("knowsWell")[1] > plan.positions.get("knows")[1],
                "a sub-property below its super-property");
        for (String outside : new String[] {"Person", "alice", "exp|1"}) {
            assertTrue(plan.positions.get(outside)[0] + 160 < objects.x,
                    outside + " is not under a section");
        }
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

    /**
     * A class with 300 individuals was one column 22,800px tall, and every other column of the
     * board grew to match it. The leaves now stand in columns side by side.
     */
    @Test
    void manyLeavesUnderOneClassStandInColumnsSideBySide() {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        terms.add(term("Specimen", BoardSections.Kind.CLASS));
        for (int i = 0; i < 300; i++) {
            terms.add(term("s" + i, BoardSections.Kind.INDIVIDUAL));
            edges.add(new BoardSections.Edge("s" + i, "Specimen", BoardSections.Link.TYPE));
        }

        Map<String, double[]> at = BoardSections.plan(terms, edges, 0, 0).positions;

        assertEquals(301, at.size());
        List<Rectangle2D> boxes = new ArrayList<Rectangle2D>();
        double bottom = 0;
        double right = 0;
        for (double[] p : at.values()) {
            Rectangle2D box = new Rectangle2D.Double(p[0], p[1], 160, 60);
            for (Rectangle2D other : boxes) {
                assertFalse(box.intersects(other), box + " overlaps " + other);
            }
            boxes.add(box);
            bottom = Math.max(bottom, p[1] + 60);
            right = Math.max(right, p[0] + 160);
        }
        assertTrue(bottom < 300 * 76 / 3, "no longer one column: " + right + "x" + bottom);
        assertTrue(at.get("s0")[1] > at.get("Specimen")[1], "the individuals below the class");
    }

    /** An individual whose class is not on the board went to x = 2^31, and the sections after. */
    @Test
    void anIndividualOfNoClassOnTheBoardStaysOnTheBoard() {
        List<BoardSections.Term> terms = Arrays.asList(term("Pizza", BoardSections.Kind.CLASS),
                term("orphan", BoardSections.Kind.INDIVIDUAL),
                term("knows", BoardSections.Kind.OBJECT_PROPERTY));

        BoardSections.Plan plan = BoardSections.plan(terms,
                java.util.Collections.<BoardSections.Edge>emptyList(), 0, 0);

        assertTrue(plan.positions.get("orphan")[0] < 1000, "" + plan.positions.get("orphan")[0]);
        assertTrue(plan.sections.get(0).x < 2000, "" + plan.sections.get(0).x);
    }
}
