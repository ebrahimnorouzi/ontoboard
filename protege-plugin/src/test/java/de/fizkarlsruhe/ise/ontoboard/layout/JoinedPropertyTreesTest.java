package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Property trees joined by inverse pairs, as the Outline puts them into a property section.
 *
 * <p>Round 3 of the 1.135.1 study fixes stood two partners side by side by putting every further
 * tree of a group to the right of the whole group, level with its partner. A review measured what
 * that does to a tree whose sub-properties each have an inverse of their own: one row, 1.7 to 2.3
 * times as wide as the section had been, Fit showing the board at half the scale, lines over
 * 2,000px long, and where the tree had two columns a partner standing behind a box of the second.
 * And which of several pairs put two trees level was the first the ontology listed, in the order
 * of a hash set of axioms: one more unrelated pair could move a tree nobody touched by 160px.
 */
class JoinedPropertyTreesTest {

    private static final double W = 160;
    private static final double H = 60;

    private static BoardSections.Term property(String id, String label) {
        return new BoardSections.Term(id, BoardSections.Kind.OBJECT_PROPERTY, label, W, H);
    }

    private static BoardSections.Edge sub(String child, String parent) {
        return new BoardSections.Edge(child, parent, BoardSections.Link.SUB_PROPERTY);
    }

    private static BoardSections.Edge inverse(String one, String other) {
        return new BoardSections.Edge(one, other, BoardSections.Link.INVERSE);
    }

    /** The kinds of characteristic a staircase is made of, in no order of their labels. */
    private static final String[] KINDS = {"quality", "role", "disposition", "function",
        "capability", "tendency", "power", "potential", "feature", "trait", "aspect", "facet"};

    /**
     * "has characteristic" and {@code n} sub-properties, each with its inverse a tree of its own -
     * as on the egg board, where "quality of" is a tree of its own because its super-property is not
     * on the board - and ten other properties.
     */
    private static List<BoardSections.Term> staircase(int n, List<BoardSections.Edge> edges) {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        terms.add(property("HC", "has characteristic"));
        for (int i = 0; i < n; i++) {
            terms.add(property("has" + i, "has " + KINDS[i]));
            terms.add(property("of" + i, KINDS[i] + " of"));
            edges.add(sub("has" + i, "HC"));
            edges.add(inverse("has" + i, "of" + i));
        }
        for (int i = 0; i < 10; i++) {
            terms.add(property("x" + i, "m other " + i));
        }
        return terms;
    }

    /** The same lines with the inverse pairs given no say: the Outline before 1.135.1. */
    private static List<BoardSections.Edge> withoutTheirSay(List<BoardSections.Edge> edges) {
        List<BoardSections.Edge> before = new ArrayList<BoardSections.Edge>();
        for (BoardSections.Edge edge : edges) {
            before.add(edge.link != BoardSections.Link.INVERSE ? edge
                    : new BoardSections.Edge(edge.source, edge.target, BoardSections.Link.OTHER));
        }
        return before;
    }

    /** The scale Fit shows a plan at, in a window of 1,600 by 900. */
    private static double fit(BoardSections.Plan plan, List<BoardSections.Term> terms) {
        double right = 0;
        double bottom = 0;
        for (BoardSections.Term term : terms) {
            double[] at = plan.positions.get(term.id);
            right = Math.max(right, at[0] + term.w);
            bottom = Math.max(bottom, at[1] + term.h);
        }
        for (BoardSections.Section section : plan.sections) {
            right = Math.max(right, section.x + section.w);
            bottom = Math.max(bottom, section.y + section.h);
        }
        return Math.min(1, Math.min(1600 / right, 900 / bottom));
    }

    private static double[] box(BoardSections.Plan plan, BoardSections.Term term) {
        double[] at = plan.positions.get(term.id);
        return new double[] {at[0], at[1], term.w, term.h};
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /**
     * What is wrong with how the plan puts each inverse pair: partners neither level nor in one
     * column, a box between them, a box on another, a property outside its section.
     */
    private static List<String> wrong(BoardSections.Plan plan, List<BoardSections.Term> terms,
            List<BoardSections.Edge> edges) {
        List<String> wrong = new ArrayList<String>();
        BoardSections.Section section = plan.sections.get(0);
        for (int a = 0; a < terms.size(); a++) {
            double[] one = box(plan, terms.get(a));
            for (int b = a + 1; b < terms.size(); b++) {
                if (overlap(one, box(plan, terms.get(b)))) {
                    wrong.add(terms.get(a).label + " on " + terms.get(b).label);
                }
            }
            if (one[0] < section.x + BoardSections.APART / 2 || one[1] < section.y + BoardSections.TITLE
                    || one[0] + one[2] > section.x + section.w - BoardSections.APART / 2 + 1e-6
                    || one[1] + one[3] > section.y + section.h - BoardSections.APART / 2 + 1e-6) {
                wrong.add(terms.get(a).label + " outside its section");
            }
        }
        java.util.Map<String, BoardSections.Term> byId =
                new java.util.HashMap<String, BoardSections.Term>();
        for (BoardSections.Term term : terms) {
            byId.put(term.id, term);
        }
        for (BoardSections.Edge edge : edges) {
            if (edge.link != BoardSections.Link.INVERSE) {
                continue;
            }
            double[] a = box(plan, byId.get(edge.source));
            double[] b = box(plan, byId.get(edge.target));
            String names = byId.get(edge.source).label + " / " + byId.get(edge.target).label;
            boolean level = a[1] == b[1];
            boolean column = a[0] < b[0] + b[2] && b[0] < a[0] + a[2];
            if (!level && !column) {
                wrong.add(names + ": neither level nor in one column");
                continue;
            }
            // The space a straight line between their facing sides runs through.
            double[] between = level
                    ? new double[] {Math.min(a[0] + a[2], b[0] + b[2]), a[1],
                        Math.abs(a[0] - b[0]) - (a[0] < b[0] ? a[2] : b[2]), Math.min(a[3], b[3])}
                    : new double[] {Math.max(a[0], b[0]), Math.min(a[1] + a[3], b[1] + b[3]),
                        Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]),
                        Math.abs(a[1] - b[1]) - (a[1] < b[1] ? a[3] : b[3])};
            for (BoardSections.Term term : terms) {
                if (!term.id.equals(edge.source) && !term.id.equals(edge.target)
                        && overlap(between, box(plan, term))) {
                    wrong.add(names + ": " + term.label + " stands between them");
                }
            }
        }
        return wrong;
    }

    /**
     * Each sub-property's partner beside it, and the section about as wide as it was before inverse
     * pairs had a say: Fit shows the board at no less than 80% of the scale it did then. Round 3
     * put the trees in one row, 2,000 to 3,500px wide, and from eight sub-properties on - two
     * columns - stood a partner behind a box of the second column.
     */
    @Test
    void aTreeWhoseSubPropertiesEachHaveAnInverseDoesNotBecomeAStrip() {
        List<String> found = new ArrayList<String>();
        for (int n : new int[] {2, 4, 6, 8, 12}) {
            List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
            List<BoardSections.Term> terms = staircase(n, edges);
            BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);
            BoardSections.Plan before = BoardSections.plan(terms, withoutTheirSay(edges), 40, 40);
            double now = fit(plan, terms);
            double then = fit(before, terms);
            if (now < then * 0.8) {
                BoardSections.Section a = plan.sections.get(0);
                BoardSections.Section b = before.sections.get(0);
                found.add(n + " sub-properties: Fit at " + Math.round(now * 100) + "% against "
                        + Math.round(then * 100) + "%, the section " + Math.round(a.w) + "x"
                        + Math.round(a.h) + " against " + Math.round(b.w) + "x" + Math.round(b.h));
            }
            for (String one : wrong(plan, terms, edges)) {
                found.add(n + " sub-properties: " + one);
            }
        }
        assertEquals(new ArrayList<String>(), found);
    }

    /**
     * A property with two inverses, each a tree of its own: round 3 stood the second behind the
     * first, on the same row, and the line to it went round the first.
     */
    @Test
    void aPropertyWithTwoInversesHasNeitherStandBehindTheOther() {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>(Arrays.asList(
                property("P", "a has part"), property("Q", "b part of"),
                property("R", "c is part of")));
        for (int i = 0; i < 6; i++) {
            terms.add(property("x" + i, "m other " + i));
        }
        List<BoardSections.Edge> edges = Arrays.asList(inverse("P", "Q"), inverse("P", "R"));

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        assertEquals(new ArrayList<String>(), wrong(plan, terms, edges));
    }

    /**
     * Two hierarchies that mirror each other, joined by three pairs whose labels sort in opposite
     * orders: whichever pair the ontology lists first, the two trees stand level on the pair of
     * their roots. Round 3 levelled them on the first pair listed - the order of a hash set of
     * axioms, which one more unrelated pair can change.
     */
    @Test
    void theSameTermsAreLaidOutTheSameWhateverOrderTheirInversePairsAreListedIn() {
        List<BoardSections.Term> terms = Arrays.asList(property("HP", "has part"),
                property("HA", "has alpha part"), property("HZ", "has zeta part"),
                property("PO", "part of"), property("AO", "z alpha part of"),
                property("ZO", "a zeta part of"));
        List<BoardSections.Edge> tree = Arrays.asList(sub("HA", "HP"), sub("HZ", "HP"),
                sub("AO", "PO"), sub("ZO", "PO"));
        List<BoardSections.Edge> pairs = Arrays.asList(inverse("HA", "AO"), inverse("HZ", "ZO"),
                inverse("HP", "PO"));
        String first = null;
        List<String> different = new ArrayList<String>();
        for (List<BoardSections.Edge> order : permutations(pairs)) {
            for (int flip = 0; flip < 8; flip++) {
                List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>(tree);
                for (int i = 0; i < order.size(); i++) {
                    BoardSections.Edge one = order.get(i);
                    edges.add((flip >> i & 1) == 0 ? one : inverse(one.target, one.source));
                }
                String plan = describe(BoardSections.plan(terms, edges, 40, 40));
                if (first == null) {
                    first = plan;
                } else if (!plan.equals(first)) {
                    different.add(order.get(0).source + "-" + order.get(0).target
                            + " listed first, flip " + flip);
                }
            }
        }
        assertEquals(new ArrayList<String>(), different);
        List<BoardSections.Edge> listed = new ArrayList<BoardSections.Edge>(tree);
        listed.addAll(pairs);
        BoardSections.Plan plan = BoardSections.plan(terms, listed, 40, 40);
        assertEquals(plan.positions.get("HP")[1], plan.positions.get("PO")[1], 0.001,
                "the two trees level on the pair of their roots");
    }

    /**
     * "has parameter" and "is parameter of", each of the {@code n} sub-properties of the one the
     * inverse of a sub-property of the other, their labels {@code wide} pixels long - and "has
     * observation" with its inverse beside them. The two roots are each other's inverse only with
     * {@code roots}.
     */
    private static List<BoardSections.Term> mirrored(int n, double wide, boolean roots,
            List<BoardSections.Edge> edges) {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        terms.add(property("HP", "has parameter"));
        terms.add(property("IP", "is parameter of"));
        if (roots) {
            edges.add(inverse("HP", "IP"));
        }
        for (int i = 0; i < n; i++) {
            terms.add(new BoardSections.Term("of" + i, BoardSections.Kind.OBJECT_PROPERTY,
                    String.format(Locale.ROOT, "of kind %02d", i), wide, H));
            terms.add(new BoardSections.Term("inv" + i, BoardSections.Kind.OBJECT_PROPERTY,
                    String.format(Locale.ROOT, "kind %02d of observation", i), wide, H));
            edges.add(sub("of" + i, "HP"));
            edges.add(sub("inv" + i, "IP"));
            edges.add(inverse("of" + i, "inv" + i));
        }
        terms.add(property("HO", "has observation"));
        terms.add(property("IO", "is observation of"));
        edges.add(inverse("HO", "IO"));
        return terms;
    }

    /**
     * Two hierarchies that mirror each other stand side by side, their roots level and each
     * sub-property level with its inverse, however long the labels: Fit shows the board at the
     * scale it did before inverse pairs had a say. A review of round 4 found the library's aquatic
     * resource observation pattern - eleven such pairs under "has parameter" and "is parameter
     * of", labels of about 320px - with "is parameter of" in a band under "has parameter", because
     * the two side by side were wider than a block of their size in a screen's shape. Every
     * sub-property's line went round, and two left the frame.
     */
    @Test
    void mirroredHierarchiesStandSideBySideHoweverLongTheirLabels() {
        assertEquals(new ArrayList<String>(), mirrorsNotSideBySide(true));
    }

    /**
     * The same where only the sub-properties are declared each other's inverse, not the two roots.
     * So the aquatic resource observation pattern reaches a board that does not have the ontology
     * it imports - its roots' pair is declared there - and so do ontologies that declare inverses
     * only for the properties they use. Then the pair standing highest, the first sub-properties'
     * one, joins the two trees, its line passing the second column of the first tree: round 3 put
     * the trees side by side all the same, and so does this.
     */
    @Test
    void mirroredHierarchiesStandSideBySideWhereOnlyTheirSubPropertiesAreInverses() {
        assertEquals(new ArrayList<String>(), mirrorsNotSideBySide(false));
    }

    /** What stands wrong in mirrored hierarchies of every size, their roots a pair or not. */
    private static List<String> mirrorsNotSideBySide(boolean roots) {
        List<String> found = new ArrayList<String>();
        for (double wide : new double[] {160, 240, 320, 400}) {
            for (int n = 1; n <= 14; n++) {
                List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
                List<BoardSections.Term> terms = mirrored(n, wide, roots, edges);
                BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);
                String at = Math.round(wide) + "px labels, " + n + " sub-properties: ";
                if (plan.positions.get("HP")[1] != plan.positions.get("IP")[1]) {
                    found.add(at + "has parameter and is parameter of not level");
                }
                int pairs = 0;
                List<String> notLevel = new ArrayList<String>();
                for (BoardSections.Edge edge : edges) {
                    if (edge.link == BoardSections.Link.INVERSE) {
                        pairs++;
                        if (plan.positions.get(edge.source)[1]
                                != plan.positions.get(edge.target)[1]) {
                            notLevel.add(edge.source + " / " + edge.target);
                        }
                    }
                }
                if (!notLevel.isEmpty()) {
                    found.add(at + notLevel.size() + " of " + pairs + " pairs not level, "
                            + notLevel.get(0) + " the first");
                }
                double now = fit(plan, terms);
                double then = fit(BoardSections.plan(terms, withoutTheirSay(edges), 40, 40),
                        terms);
                if (now < then - 1e-9) {
                    found.add(at + "Fit at " + Math.round(now * 100) + "% against "
                            + Math.round(then * 100) + "%");
                }
                for (String one : wrong(plan, terms, new ArrayList<BoardSections.Edge>())) {
                    found.add(at + one);
                }
            }
        }
        return found;
    }

    /**
     * The same on boards made up at random: properties in trees, joined by inverse pairs, with
     * those pairs listed in another order and each the other way round.
     */
    @Test
    void onAnyBoardTheOrderOfTheInversePairsDoesNotMatter() {
        List<String> different = new ArrayList<String>();
        int joined = 0;
        for (long seed = 1; seed <= 400; seed++) {
            Random random = new Random(seed);
            List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
            List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
            List<BoardSections.Edge> pairs = new ArrayList<BoardSections.Edge>();
            randomBoard(random, terms, edges, pairs);
            List<BoardSections.Edge> listed = new ArrayList<BoardSections.Edge>(edges);
            listed.addAll(pairs);
            List<BoardSections.Edge> otherwise = new ArrayList<BoardSections.Edge>(edges);
            List<BoardSections.Edge> shuffled = new ArrayList<BoardSections.Edge>(pairs);
            Collections.shuffle(shuffled, random);
            for (BoardSections.Edge pair : shuffled) {
                otherwise.add(random.nextInt(otherwise.size() + 1),
                        random.nextBoolean() ? pair : inverse(pair.target, pair.source));
            }
            BoardSections.Plan plan = BoardSections.plan(terms, listed, 40, 40);
            joined += describe(plan).equals(describe(BoardSections.plan(terms,
                    withoutTheirSay(listed), 40, 40))) ? 0 : 1;
            if (!describe(plan).equals(describe(BoardSections.plan(terms, otherwise, 40, 40)))) {
                different.add("seed " + seed);
            }
        }
        assertTrue(joined > 150, "boards where a pair joins two trees: " + joined);
        assertEquals(new ArrayList<String>(), different);
    }

    /**
     * On the same boards: no two boxes on each other, every property inside its section, and the
     * board shown by Fit at no less than 55% of the scale it was before inverse pairs had a say.
     * Round 3's one row went down to 51% on these boards and to 40% on a review's. Partners side by
     * side cost some of it - a section of several small pairs is wider than one of the same trees
     * in columns - and the worst here is 59%.
     */
    @Test
    void onAnyBoardJoinedTreesStayInsideTheirSectionAndKeepItsShape() {
        List<String> found = new ArrayList<String>();
        double worst = 1;
        for (long seed = 1; seed <= 400; seed++) {
            Random random = new Random(seed);
            List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
            List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
            List<BoardSections.Edge> pairs = new ArrayList<BoardSections.Edge>();
            randomBoard(random, terms, edges, pairs);
            edges.addAll(pairs);
            BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);
            for (String one : wrong(plan, terms, new ArrayList<BoardSections.Edge>())) {
                found.add("seed " + seed + ": " + one);
            }
            double ratio = fit(plan, terms)
                    / fit(BoardSections.plan(terms, withoutTheirSay(edges), 40, 40), terms);
            worst = Math.min(worst, ratio);
            if (ratio < 0.55) {
                found.add("seed " + seed + ": Fit at " + Math.round(ratio * 100)
                        + "% of the scale before");
            }
        }
        assertEquals(new ArrayList<String>(), found, "worst Fit " + worst);
    }

    /**
     * A section of up to 31 properties in trees, with up to twelve inverse pairs at random: most
     * join two trees, some stand inside one, and a property may be its own inverse.
     */
    private static void randomBoard(Random random, List<BoardSections.Term> terms,
            List<BoardSections.Edge> edges, List<BoardSections.Edge> pairs) {
        String[] words = {"has part", "part of", "is about", "has quality", "quality of",
            "realizes", "concretizes", "denoted by", "a", "b", "zeta"};
        int count = 2 + random.nextInt(30);
        for (int i = 0; i < count; i++) {
            terms.add(new BoardSections.Term("P" + i, BoardSections.Kind.OBJECT_PROPERTY,
                    words[random.nextInt(words.length)] + " " + i, 80 + 10 * random.nextInt(20),
                    40 + 20 * random.nextInt(3)));
            if (i > 0 && random.nextInt(10) < 6) {
                edges.add(sub("P" + i, "P" + random.nextInt(i)));
            }
        }
        for (int i = 0; i < 1 + random.nextInt(12); i++) {
            pairs.add(inverse("P" + random.nextInt(count), "P" + random.nextInt(count)));
        }
    }

    /**
     * Where no inverse pair joins two trees - none at all, one inside a tree, a property its own
     * inverse, a partner in the other section or not on the board - the plan is exactly the plan
     * of the same board without them, as it was before inverse pairs had a say.
     */
    @Test
    void whereNoPairJoinsTwoTreesThePlanIsExactlyAsBefore() {
        List<String> different = new ArrayList<String>();
        for (long seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
            List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
            for (int i = 0; i < 6 + random.nextInt(10); i++) {
                terms.add(new BoardSections.Term("C" + i, BoardSections.Kind.CLASS, "class " + i,
                        120, 40));
                if (i > 0 && random.nextBoolean()) {
                    edges.add(new BoardSections.Edge("C" + i, "C" + random.nextInt(i),
                            BoardSections.Link.SUBCLASS));
                }
            }
            int count = 2 + random.nextInt(20);
            for (int i = 0; i < count; i++) {
                terms.add(new BoardSections.Term("P" + i, BoardSections.Kind.OBJECT_PROPERTY,
                        "property " + random.nextInt(50), 80 + 10 * random.nextInt(20), 40));
                if (i > 0 && random.nextInt(10) < 6) {
                    edges.add(sub("P" + i, "P" + random.nextInt(i)));
                }
            }
            terms.add(new BoardSections.Term("D0", BoardSections.Kind.DATA_PROPERTY, "value", 120,
                    40));
            java.util.Map<String, String> parent = new java.util.HashMap<String, String>();
            for (BoardSections.Edge edge : edges) {
                if (edge.link == BoardSections.Link.SUB_PROPERTY) {
                    parent.put(edge.source, edge.target);
                }
            }
            for (int i = 0; i < random.nextInt(6); i++) {
                String one = "P" + random.nextInt(count);
                String root = one;
                while (parent.containsKey(root)) {
                    root = parent.get(root);
                }
                // Inside one tree: the property and its root, or itself; or out of the section.
                String[] others = {root, one, "D0", "not on the board"};
                edges.add(random.nextInt(edges.size() + 1),
                        inverse(one, others[random.nextInt(others.length)]));
            }
            String now = describe(BoardSections.plan(terms, edges, 40, 40));
            String before = describe(BoardSections.plan(terms, withoutTheirSay(edges), 40, 40));
            if (!now.equals(before)) {
                different.add("seed " + seed);
            }
        }
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

    private static <T> List<List<T>> permutations(List<T> items) {
        List<List<T>> all = new ArrayList<List<T>>();
        if (items.size() <= 1) {
            all.add(new ArrayList<T>(items));
            return all;
        }
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<T>(items);
            T first = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                tail.add(0, first);
                all.add(tail);
            }
        }
        return all;
    }
}
