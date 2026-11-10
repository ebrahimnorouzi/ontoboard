package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** "Clear hierarchy: subclasses below each other, in multiple columns if there are more." */
class HierarchyColumnsTest {

    private static final double W = 160;
    private static final double H = 40;

    private static HierarchyColumns.Term term(String id) {
        return new HierarchyColumns.Term(id, id, W, H);
    }

    private static double[] at(HierarchyColumns.Placed placed, String id) {
        return placed.positions.get(id);
    }

    @Test
    void subclassesStandBelowTheirClassIndentedOneUnderTheOther() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        terms.add(term("process"));
        for (String child : new String[] {"a", "b", "c"}) {
            terms.add(term(child));
            parent.put(child, "process");
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 100, 200);

        assertEquals(100, at(placed, "process")[0]);
        assertEquals(200, at(placed, "process")[1]);
        for (String child : new String[] {"a", "b", "c"}) {
            assertEquals(100 + HierarchyColumns.INDENT, at(placed, child)[0], child);
        }
        assertTrue(at(placed, "a")[1] >= 200 + H);
        assertEquals(at(placed, "a")[1] + H + HierarchyColumns.SIBLINGS, at(placed, "b")[1]);
        assertEquals(at(placed, "b")[1] + H + HierarchyColumns.SIBLINGS, at(placed, "c")[1]);
    }

    @Test
    void siblingsAreInLabelOrderWhateverOrderTheyCameIn() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        terms.add(term("root"));
        for (String child : new String[] {"zinc", "Alloy", "iron"}) {
            terms.add(term(child));
            parent.put(child, "root");
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 0, 0);
        assertTrue(at(placed, "Alloy")[1] < at(placed, "iron")[1]);
        assertTrue(at(placed, "iron")[1] < at(placed, "zinc")[1]);
    }

    @Test
    void manySubclassesFlowIntoMoreColumnsAndNoneOverlap() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        terms.add(term("root"));
        for (int i = 0; i < 30; i++) {
            String id = String.format("c%02d", i);
            terms.add(term(id));
            parent.put(id, "root");
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 0, 0);

        java.util.Set<Double> columns = new HashSet<Double>();
        double lowest = 0;
        for (int i = 0; i < 30; i++) {
            double[] box = at(placed, String.format("c%02d", i));
            columns.add(box[0]);
            lowest = Math.max(lowest, box[1] + H);
        }
        assertTrue(columns.size() > 1, "more than one column");
        assertTrue(lowest <= H + HierarchyColumns.UNDER + HierarchyColumns.COLUMN_HEIGHT + H,
                "no column runs past the limit: " + lowest);
        assertNoOverlap(terms, placed);
    }

    @Test
    void aDeepHierarchyStepsRightAndStaysClear() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        terms.add(term("l0"));
        for (int i = 1; i < 12; i++) {
            terms.add(term("l" + i));
            parent.put("l" + i, "l" + (i - 1));
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 0, 0);
        for (int i = 1; i < 12; i++) {
            assertEquals(at(placed, "l" + (i - 1))[0] + HierarchyColumns.INDENT,
                    at(placed, "l" + i)[0]);
            assertTrue(at(placed, "l" + i)[1] > at(placed, "l" + (i - 1))[1]);
        }
        assertNoOverlap(terms, placed);
    }

    @Test
    void aForestOfMixedTreesFitsTogetherWithNoOverlap() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        for (int tree = 0; tree < 12; tree++) {
            String root = "t" + tree;
            terms.add(new HierarchyColumns.Term(root, root, 160 + tree * 10, tree % 3 == 0 ? 60 : 40));
            for (int leaf = 0; leaf < tree; leaf++) {
                String id = root + "-" + leaf;
                terms.add(new HierarchyColumns.Term(id, id, 200 - leaf, 40));
                parent.put(id, root);
            }
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 40, 40);
        assertEquals(terms.size(), placed.positions.size(), "every term is placed");
        assertNoOverlap(terms, placed);
        assertTrue(placed.right > 40 && placed.bottom > 40);
    }

    @Test
    void aCycleDoesNotLoseATerm() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        terms.add(term("a"));
        terms.add(term("b"));
        parent.put("a", "b");
        parent.put("b", "a");
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 0, 0);
        assertEquals(2, placed.positions.size());
        assertNoOverlap(terms, placed);
    }

    @Test
    void aParentOutsideTheGroupMakesARoot() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        terms.add(term("a"));
        Map<String, String> parent = new HashMap<String, String>();
        parent.put("a", "elsewhere");
        HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parent, 7, 9);
        assertEquals(7, at(placed, "a")[0]);
        assertEquals(9, at(placed, "a")[1]);
        assertFalse(placed.positions.containsKey("elsewhere"));
    }

    /**
     * A subclass added moves none of its siblings when it comes last, and only those after it, by
     * one place, when it comes between. Found by two testers: with each New subclass, the siblings
     * switched between two and three columns.
     */
    @Test
    void aNewSubclassMovesOnlyTheSiblingsAfterIt() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        terms.add(term("root"));
        for (int i = 1; i <= 14; i++) {
            String id = String.format("c%02d", i);
            terms.add(term(id));
            parent.put(id, "root");
        }
        HierarchyColumns.Placed before = HierarchyColumns.place(terms, parent, 0, 0);

        List<HierarchyColumns.Term> last = new ArrayList<HierarchyColumns.Term>(terms);
        last.add(term("c15"));
        Map<String, String> lastParent = new HashMap<String, String>(parent);
        lastParent.put("c15", "root");
        HierarchyColumns.Placed afterLast = HierarchyColumns.place(last, lastParent, 0, 0);
        for (int i = 1; i <= 14; i++) {
            String id = String.format("c%02d", i);
            assertEquals(before.positions.get(id)[0], afterLast.positions.get(id)[0], id);
            assertEquals(before.positions.get(id)[1], afterLast.positions.get(id)[1], id);
        }

        List<HierarchyColumns.Term> between = new ArrayList<HierarchyColumns.Term>(terms);
        between.add(term("c05b"));
        Map<String, String> betweenParent = new HashMap<String, String>(parent);
        betweenParent.put("c05b", "root");
        HierarchyColumns.Placed afterBetween = HierarchyColumns.place(between, betweenParent, 0, 0);
        for (int i = 1; i <= 5; i++) {
            String id = String.format("c%02d", i);
            assertEquals(before.positions.get(id)[0], afterBetween.positions.get(id)[0], id);
            assertEquals(before.positions.get(id)[1], afterBetween.positions.get(id)[1], id);
        }
        for (int i = 6; i <= 13; i++) {
            // One place on: where the sibling after it stood.
            String id = String.format("c%02d", i);
            double[] next = before.positions.get(String.format("c%02d", i + 1));
            assertEquals(next[0], afterBetween.positions.get(id)[0], id + " moved one place");
            assertEquals(next[1], afterBetween.positions.get(id)[1], id + " moved one place");
        }
    }

    /**
     * A class that gets its first subclass keeps its turn among the others, in label order. Bigger
     * trees used to go first, so it jumped in front of them - a tester saw a class move 1,640 px.
     */
    @Test
    void aClassThatGetsASubclassKeepsItsTurn() {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, String> parent = new HashMap<String, String>();
        for (String root : new String[] {"assay", "entity", "process", "role"}) {
            terms.add(term(root));
        }
        terms.add(term("object"));
        parent.put("object", "entity");
        terms.add(term("material"));
        parent.put("material", "entity");
        HierarchyColumns.Placed before = HierarchyColumns.place(terms, parent, 0, 0);

        terms.add(term("hardness test"));
        parent.put("hardness test", "process");
        HierarchyColumns.Placed after = HierarchyColumns.place(terms, parent, 0, 0);

        for (HierarchyColumns.Placed placed : new HierarchyColumns.Placed[] {before, after}) {
            assertTrue(readsBefore(placed, "assay", "entity"));
            assertTrue(readsBefore(placed, "entity", "process"));
            assertTrue(readsBefore(placed, "process", "role"));
        }
        assertEquals(before.positions.get("assay")[0], after.positions.get("assay")[0]);
        assertEquals(before.positions.get("assay")[1], after.positions.get("assay")[1]);
        assertEquals(before.positions.get("entity")[1], after.positions.get("entity")[1]);
    }

    /** Down a column, then on to the next: the order a reader takes. */
    private static boolean readsBefore(HierarchyColumns.Placed placed, String one, String other) {
        double[] a = placed.positions.get(one);
        double[] b = placed.positions.get(other);
        return a[0] < b[0] || (a[0] == b[0] && a[1] < b[1]);
    }

    static void assertNoOverlap(List<HierarchyColumns.Term> terms, HierarchyColumns.Placed placed) {
        for (int i = 0; i < terms.size(); i++) {
            for (int j = i + 1; j < terms.size(); j++) {
                HierarchyColumns.Term one = terms.get(i);
                HierarchyColumns.Term other = terms.get(j);
                double[] a = placed.positions.get(one.id);
                double[] b = placed.positions.get(other.id);
                boolean apart = a[0] + one.w <= b[0] || b[0] + other.w <= a[0]
                        || a[1] + one.h <= b[1] || b[1] + other.h <= a[1];
                assertTrue(apart, one.id + " overlaps " + other.id);
            }
        }
    }
}
