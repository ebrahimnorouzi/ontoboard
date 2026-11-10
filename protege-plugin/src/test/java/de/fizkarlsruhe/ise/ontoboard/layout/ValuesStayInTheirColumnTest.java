package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The individuals - the ABox - in a band below the classes: each class's individuals in a column
 * under it, in several side by side when there are many, and an individual's values right below
 * it, set in by 20px.
 *
 * <p>Found by a review of round 4 of the 1.135.1 study fixes, on boards made up at random: a value
 * on another box on 53 of 2,500, before round 4 as after it. A column was as wide as its widest
 * individual, whatever hung off them, so a value wider than that less its 20px reached into the
 * next column, onto the next class's individuals, or under the property sections. A column is now
 * as wide as what it holds reaches - that column only, and where every value fits nothing moves.
 */
class ValuesStayInTheirColumnTest {

    private static final double W = 160;
    private static final double H = 60;

    /** How far a value stands in from its individual's left edge. */
    private static final double SET_IN = 20;

    private static BoardSections.Term box(String id, BoardSections.Kind kind, double w, double h) {
        return new BoardSections.Term(id, kind, id, w, h);
    }

    private static BoardSections.Edge edge(String source, String target, BoardSections.Link link) {
        return new BoardSections.Edge(source, target, link);
    }

    private static double[] rect(BoardSections.Plan plan, BoardSections.Term term) {
        double[] at = plan.positions.get(term.id);
        return new double[] {at[0], at[1], term.w, term.h};
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static boolean isProperty(BoardSections.Term term) {
        return term.kind == BoardSections.Kind.OBJECT_PROPERTY
                || term.kind == BoardSections.Kind.DATA_PROPERTY;
    }

    /** Each box on another, and each box that is not a property on a section's frame. */
    private static List<String> onEachOther(BoardSections.Plan plan,
            List<BoardSections.Term> terms) {
        List<String> found = new ArrayList<String>();
        for (int a = 0; a < terms.size(); a++) {
            double[] one = rect(plan, terms.get(a));
            for (int b = a + 1; b < terms.size(); b++) {
                if (overlap(one, rect(plan, terms.get(b)))) {
                    found.add(terms.get(a).id + " on " + terms.get(b).id);
                }
            }
            for (BoardSections.Section section : plan.sections) {
                if (!isProperty(terms.get(a)) && overlap(one,
                        new double[] {section.x, section.y, section.w, section.h})) {
                    found.add(terms.get(a).id + " on the frame of " + section.label);
                }
            }
        }
        return found;
    }

    /** "Pizza" with "Diavola" and "Margherita" under it, so the two stand one under the other. */
    private static List<BoardSections.Term> pizzas(List<BoardSections.Edge> edges) {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        terms.add(box("Pizza", BoardSections.Kind.CLASS, W, H));
        terms.add(box("Diavola", BoardSections.Kind.CLASS, W, H));
        terms.add(box("Margherita", BoardSections.Kind.CLASS, W, H));
        edges.add(edge("Diavola", "Pizza", BoardSections.Link.SUBCLASS));
        edges.add(edge("Margherita", "Pizza", BoardSections.Link.SUBCLASS));
        return terms;
    }

    /** {@code count} individuals of {@code cls}, {@code prefix}00 on, as the band sorts them. */
    private static void individuals(String cls, String prefix, int count,
            List<BoardSections.Term> terms, List<BoardSections.Edge> edges) {
        for (int i = 0; i < count; i++) {
            String id = String.format(Locale.ROOT, "%s%02d", prefix, i);
            terms.add(box(id, BoardSections.Kind.INDIVIDUAL, W, H));
            edges.add(edge(id, cls, BoardSections.Link.TYPE));
        }
    }

    /** A value {@code w} pixels wide hanging off {@code individual}, as a data assertion does. */
    private static void value(String individual, double w, List<BoardSections.Term> terms,
            List<BoardSections.Edge> edges) {
        terms.add(box(individual + " value", BoardSections.Kind.VALUE, w, 40));
        edges.add(edge(individual, individual + " value", BoardSections.Link.OTHER));
    }

    private static double x(BoardSections.Plan plan, String id) {
        return plan.positions.get(id)[0];
    }

    private static double y(BoardSections.Plan plan, String id) {
        return plan.positions.get(id)[1];
    }

    /**
     * 24 individuals of one class, two columns of twelve: the first one's value, 300px wide,
     * reached 125px into the second column, onto the individual second from the top.
     */
    @Test
    void aWideValueDoesNotReachIntoTheNextColumn() {
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        terms.add(box("Specimen", BoardSections.Kind.CLASS, W, H));
        individuals("Specimen", "s", 24, terms, edges);
        value("s00", 300, terms, edges);

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        assertEquals(new ArrayList<String>(), onEachOther(plan, terms));
    }

    /** A Diavola's value, 300px wide, reached onto the Margherita standing next in the band. */
    @Test
    void aWideValueDoesNotReachOntoTheNextClasssIndividuals() {
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        List<BoardSections.Term> terms = pizzas(edges);
        individuals("Diavola", "d", 1, terms, edges);
        individuals("Margherita", "m", 2, terms, edges);
        value("d00", 300, terms, edges);

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        assertEquals(new ArrayList<String>(), onEachOther(plan, terms));
    }

    /**
     * The property sections stand 220px right of the band's widest individual. Drawn on the board,
     * a value is at most 320px wide and stops short of them; the plan takes any width, and a value
     * 1,000px wide reached under the section.
     */
    @Test
    void aWideValueDoesNotReachUnderThePropertySections() {
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        terms.add(box("Thing", BoardSections.Kind.CLASS, W, H));
        individuals("Thing", "alice", 1, terms, edges);
        value("alice00", 1000, terms, edges);
        terms.add(box("has part", BoardSections.Kind.OBJECT_PROPERTY, W, H));
        for (int i = 1; i <= 6; i++) {
            terms.add(box("has part " + i, BoardSections.Kind.OBJECT_PROPERTY, W, H));
            edges.add(edge("has part " + i, "has part", BoardSections.Link.SUB_PROPERTY));
        }

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        assertEquals(new ArrayList<String>(), onEachOther(plan, terms));
    }

    /**
     * Three columns of Diavolas and a column of Margheritas: the column holding a value 300px wide
     * is widened by as much as the value reaches past it, and no other column is.
     */
    @Test
    void aColumnIsAsWideAsWhatItHoldsReachesAndNoWider() {
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        List<BoardSections.Term> terms = pizzas(edges);
        individuals("Diavola", "d", 25, terms, edges);
        individuals("Margherita", "m", 2, terms, edges);
        value("d00", W - SET_IN - 60, terms, edges);
        value("d13", 300, terms, edges);
        value("m00", 400, terms, edges);

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        assertEquals(W + BoardSections.ACROSS / 2, x(plan, "d12") - x(plan, "d00"), 0.001,
                "the first column, whose value fits, as wide as its individuals");
        assertEquals(SET_IN + 300 + BoardSections.ACROSS / 2, x(plan, "d24") - x(plan, "d12"),
                0.001, "the second as wide as its value reaches");
        assertEquals(W + BoardSections.ACROSS, x(plan, "m00") - x(plan, "d24"), 0.001,
                "the third, which holds no value, as wide as its individual");
        assertEquals(x(plan, "m00") + SET_IN, x(plan, "m00 value"), 0.001,
                "a value set in under its individual");
        assertEquals(new ArrayList<String>(), onEachOther(plan, terms));
    }

    /**
     * Where every value fits - the widest exactly, 20px narrower than its column - the band is
     * exactly as it was: the columns as wide as their class's widest individual, 35px apart, the
     * next class 70px on, each value 20px in, right below its individual.
     */
    @Test
    void whereEveryValueFitsTheBandIsExactlyAsItWas() {
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        List<BoardSections.Term> terms = pizzas(edges);
        individuals("Diavola", "d", 14, terms, edges);
        individuals("Margherita", "m", 2, terms, edges);
        value("d00", W - SET_IN, terms, edges);
        value("d12", 100, terms, edges);
        value("m00", 120, terms, edges);

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        double classesBottom = 0;
        for (String cls : new String[] {"Pizza", "Diavola", "Margherita"}) {
            classesBottom = Math.max(classesBottom, y(plan, cls) + H);
        }
        double top = classesBottom + BoardSections.APART * 2;
        double left = x(plan, "Diavola");
        Map<String, double[]> expected = new TreeMap<String, double[]>();
        expected.put("d00", new double[] {left, top});
        expected.put("d00 value", new double[] {left + 20, top + 76});
        for (int i = 1; i < 12; i++) {
            expected.put(String.format(Locale.ROOT, "d%02d", i),
                    new double[] {left, top + 132 + (i - 1) * 76});
        }
        expected.put("d12", new double[] {left + 195, top});
        expected.put("d12 value", new double[] {left + 215, top + 76});
        expected.put("d13", new double[] {left + 195, top + 132});
        expected.put("m00", new double[] {left + 425, top});
        expected.put("m00 value", new double[] {left + 445, top + 76});
        expected.put("m01", new double[] {left + 425, top + 132});
        List<String> moved = new ArrayList<String>();
        for (Map.Entry<String, double[]> at : expected.entrySet()) {
            double[] now = plan.positions.get(at.getKey());
            if (now[0] != at.getValue()[0] || now[1] != at.getValue()[1]) {
                moved.add(at.getKey() + " at " + now[0] + "," + now[1] + " not "
                        + at.getValue()[0] + "," + at.getValue()[1]);
            }
        }
        assertEquals(new ArrayList<String>(), moved);
    }

    /**
     * A board made up at random, as the review made them and more: up to 13 classes in trees; up
     * to 40 individuals, now and then of no class on the board; a third of them with up to three
     * values, now and then a class expression, {@code narrowest} to {@code widest} pixels wide; and
     * object and data properties in trees.
     */
    private static List<BoardSections.Term> randomBoard(Random random, int narrowest, int widest,
            List<BoardSections.Edge> edges) {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        int classes = 1 + random.nextInt(13);
        for (int i = 0; i < classes; i++) {
            terms.add(new BoardSections.Term("C" + i, BoardSections.Kind.CLASS, "class " + i,
                    100 + 20 * random.nextInt(8), 40 + 20 * random.nextInt(2)));
            if (i > 0 && random.nextBoolean()) {
                edges.add(edge("C" + i, "C" + random.nextInt(i), BoardSections.Link.SUBCLASS));
            }
        }
        int individuals = random.nextInt(3) == 0 ? 13 + random.nextInt(28) : random.nextInt(8);
        for (int i = 0; i < individuals; i++) {
            String id = String.format(Locale.ROOT, "I%02d", i);
            terms.add(new BoardSections.Term(id, BoardSections.Kind.INDIVIDUAL, "individual " + i,
                    120 + 20 * random.nextInt(4), 40));
            edges.add(edge(id, random.nextInt(10) == 0 ? "not on the board"
                    : "C" + random.nextInt(classes), BoardSections.Link.TYPE));
            int values = random.nextInt(3) == 0 ? 1 + random.nextInt(3) : 0;
            for (int v = 0; v < values; v++) {
                String value = id + "V" + v;
                terms.add(new BoardSections.Term(value, random.nextInt(8) == 0
                        ? BoardSections.Kind.EXPRESSION : BoardSections.Kind.VALUE,
                        "value " + value, narrowest + random.nextInt(widest - narrowest + 1),
                        40 + 20 * random.nextInt(2)));
                edges.add(edge(id, value, BoardSections.Link.OTHER));
            }
        }
        int properties = random.nextInt(21);
        for (int i = 0; i < properties; i++) {
            terms.add(new BoardSections.Term("P" + i, BoardSections.Kind.OBJECT_PROPERTY,
                    "property " + i, 80 + 20 * random.nextInt(20), 40 + 20 * random.nextInt(3)));
            if (i > 0 && random.nextInt(10) < 6) {
                edges.add(edge("P" + i, "P" + random.nextInt(i), BoardSections.Link.SUB_PROPERTY));
            }
        }
        int datas = random.nextInt(5);
        for (int i = 0; i < datas; i++) {
            terms.add(new BoardSections.Term("D" + i, BoardSections.Kind.DATA_PROPERTY,
                    "data " + i, 100 + 20 * random.nextInt(6), 40));
            if (i > 0 && random.nextBoolean()) {
                edges.add(edge("D" + i, "D" + random.nextInt(i), BoardSections.Link.SUB_PROPERTY));
            }
        }
        return terms;
    }

    /** Values from 20px to 700px wide: no box on another, and none on a section's frame. */
    @Test
    void onAnyBoardNoBoxOfTheBandIsOnAnother() {
        List<String> found = new ArrayList<String>();
        int boards = 0;
        for (long seed = 1; seed <= 1500; seed++) {
            List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
            List<BoardSections.Term> terms = randomBoard(new Random(seed), 20, 700, edges);
            List<String> wrong = onEachOther(BoardSections.plan(terms, edges, 40, 40), terms);
            if (!wrong.isEmpty()) {
                boards++;
                if (found.size() < 10) {
                    found.add("seed " + seed + ": " + wrong.get(0)
                            + (wrong.size() > 1 ? " and " + (wrong.size() - 1) + " more" : ""));
                }
            }
        }
        assertEquals(new ArrayList<String>(), found, boards + " boards of 1500");
    }

    /**
     * Values and class expressions at least 20px narrower than every individual have no say in
     * where anything goes: the plan is exactly the plan of the same board with each of them 1px
     * wide - which, before a value had a say, was the plan whatever their widths.
     */
    @Test
    void onAnyBoardValuesThatFitHaveNoSayInWhereAnythingGoes() {
        List<String> different = new ArrayList<String>();
        int withValues = 0;
        for (long seed = 1; seed <= 1500; seed++) {
            List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
            List<BoardSections.Term> terms = randomBoard(new Random(seed), 1, 100, edges);
            List<BoardSections.Term> thin = new ArrayList<BoardSections.Term>();
            boolean any = false;
            for (BoardSections.Term term : terms) {
                boolean hangs = term.kind == BoardSections.Kind.VALUE
                        || term.kind == BoardSections.Kind.EXPRESSION;
                any |= hangs;
                thin.add(hangs ? new BoardSections.Term(term.id, term.kind, term.label, 1, term.h)
                        : term);
            }
            withValues += any ? 1 : 0;
            if (!describe(BoardSections.plan(terms, edges, 40, 40))
                    .equals(describe(BoardSections.plan(thin, edges, 40, 40)))) {
                different.add("seed " + seed);
            }
        }
        assertTrue(withValues > 600, "boards with values: " + withValues);
        assertEquals(new ArrayList<String>(), different);
    }

    private static String describe(BoardSections.Plan plan) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, double[]> at : new TreeMap<String, double[]>(plan.positions)
                .entrySet()) {
            out.append(at.getKey()).append('=').append(at.getValue()[0]).append(',')
                    .append(at.getValue()[1]).append('\n');
        }
        for (BoardSections.Section section : plan.sections) {
            out.append(section.label).append(' ').append(section.x).append(',').append(section.y)
                    .append(' ').append(section.w).append('x').append(section.h).append('\n');
        }
        return out.toString();
    }
}
