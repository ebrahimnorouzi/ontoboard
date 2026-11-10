package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where every term goes on a board that shows a whole ontology.
 *
 * <p>Measured before this on a published ontology of 660 terms: the layered layout every other
 * arrangement uses put them on a board 32,335px by 10,078px - one tall tangle for the classes,
 * every leaf in one row across the full width, and the properties in a corner. A layered layout
 * gives each relation a say in where things go, and an ontology has a thousand relations.
 *
 * <p>Here only the hierarchy decides where a term goes:
 *
 * <ul>
 *   <li><b>The classes - the TBox - are trees read top down</b>: a class above its subclasses,
 *       which stand one under the other, in more columns when there are more of them - see
 *       {@link HierarchyColumns}.
 *   <li><b>The individuals - the ABox - are a band below the classes</b>, each class's
 *       individuals under that class, and an individual's values right below it. Asked for:
 *       "separate TBox from ABox, ABox below TBox, instances below the class they are typed".
 *   <li><b>What hangs off a class</b> - a restriction it is drawn with, a datatype - is placed
 *       below it with its subclasses.
 *   <li><b>Object and data properties are sections of their own</b>, to the right, each its
 *       property hierarchy laid out the same way.
 * </ul>
 *
 * <p>Pure geometry: no Swing, no JGraphX, no OWL API.
 */
public final class BoardSections {

    /** What a term is, as far as where it goes is concerned. */
    public enum Kind {
        CLASS, INDIVIDUAL, EXPRESSION, VALUE, OBJECT_PROPERTY, DATA_PROPERTY
    }

    /**
     * Which links decide the tree. An inverse pair has a say in one thing, that the two stand side
     * by side (1.135.1); everything else is drawn but has no say.
     */
    public enum Link {
        SUBCLASS, TYPE, SUB_PROPERTY, INVERSE, OTHER
    }

    /** A term to place: its id, kind, label (for the order of siblings) and size. */
    public static final class Term {
        final String id;
        final Kind kind;
        final String label;
        final double w;
        final double h;

        public Term(String id, Kind kind, String label, double w, double h) {
            this.id = id;
            this.kind = kind;
            this.label = label == null ? "" : label;
            this.w = w;
            this.h = h;
        }
    }

    /** A drawn relation from {@code source} to {@code target}. */
    public static final class Edge {
        final String source;
        final String target;
        final Link link;

        public Edge(String source, String target, Link link) {
            this.source = source;
            this.target = target;
            this.link = link;
        }
    }

    /** A labelled region the board should frame: the properties. */
    public static final class Section {
        public final String label;
        public final double x;
        public final double y;
        public final double w;
        public final double h;

        Section(String label, double x, double y, double w, double h) {
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** Where each term goes, and the sections to frame. */
    public static final class Plan {
        public final Map<String, double[]> positions = new LinkedHashMap<String, double[]>();
        public final List<Section> sections = new ArrayList<Section>();
    }

    /** Between two columns of individuals. */
    static final double ACROSS = 70;

    /** Between two individuals. */
    static final double DOWN = 16;

    /** Between two blocks, and around a section inside its frame. */
    static final double APART = 110;

    /** Room inside a section's frame for its title. */
    static final double TITLE = 44;

    private BoardSections() {
    }

    /** Every term placed, the top-left of the board at ({@code left}, {@code top}). */
    public static Plan plan(List<Term> terms, List<Edge> edges, double left, double top) {
        return plan(terms, edges, left, top, Double.NEGATIVE_INFINITY);
    }

    /**
     * The same, the property sections kept right of {@code framesRight} too - the right edge of
     * the frames above the outline. A section standing under a frame is crossed by every line
     * from the outline up to a class in that frame.
     */
    public static Plan plan(List<Term> terms, List<Edge> edges, double left, double top,
            double framesRight) {
        return plan(terms, edges, left, top, framesRight, top);
    }

    /**
     * The same, the property sections starting at {@code sectionsTop}: beside the frames along the
     * top of the board rather than down below them with nothing above.
     */
    public static Plan plan(List<Term> terms, List<Edge> edges, double left, double top,
            double framesRight, double sectionsTop) {
        Map<String, Term> byId = new LinkedHashMap<String, Term>();
        for (Term term : terms) {
            byId.put(term.id, term);
        }
        Map<String, String> parent = parents(byId, edges);
        Plan plan = new Plan();

        List<Term> classes = new ArrayList<Term>();
        List<Term> individuals = new ArrayList<Term>();
        List<Term> objectProperties = new ArrayList<Term>();
        List<Term> dataProperties = new ArrayList<Term>();
        for (Term term : terms) {
            Kind section = sectionOf(term, parent, byId);
            if (section == Kind.OBJECT_PROPERTY) {
                objectProperties.add(term);
            } else if (section == Kind.DATA_PROPERTY) {
                dataProperties.add(term);
            } else if (isAbox(term, parent, byId)) {
                individuals.add(term);
            } else {
                classes.add(term);
            }
        }
        double[] tbox = forest(classes, parent, left, top, plan);
        double right = tbox[2];
        double bottom = classes.isEmpty() ? top : tbox[3];
        if (!individuals.isEmpty()) {
            double aboxTop = (classes.isEmpty() ? top : tbox[3]) + APART * 2;
            right = Math.max(right, placeIndividuals(individuals, parent, byId, plan, left,
                    aboxTop));
            for (Term individual : individuals) {
                double[] at = plan.positions.get(individual.id);
                if (at != null) {
                    bottom = Math.max(bottom, at[1] + individual.h);
                }
            }
        }
        double x = Math.max((classes.isEmpty() && individuals.isEmpty() ? left : right),
                framesRight) + APART * 2;
        double y = sectionsTop;
        List<List<Term>> laidOut = new ArrayList<List<Term>>();
        for (Object[] section : new Object[][] {
            {"Object properties", objectProperties}, {"Data properties", dataProperties}}) {
            @SuppressWarnings("unchecked")
            List<Term> members = (List<Term>) section[1];
            if (members.isEmpty()) {
                continue;
            }
            double[] box = propertyForest(members, parent, edges, x + APART / 2, y + TITLE, plan);
            double w = box[2] - x + APART / 2;
            double h = box[3] - y + APART / 2;
            plan.sections.add(new Section((String) section[0], x, y, w, h));
            laidOut.add(members);
            y += h + APART;
        }
        if (!plan.sections.isEmpty()) {
            putSectionsBelowIfThatFitsAScreen(plan, laidOut, left,
                    Math.max(right, framesRight), Math.max(bottom, top));
        }
        return plan;
    }

    /** The shape of the window a board is mostly seen in: wider than tall. */
    private static final double SCREEN = 1.6;

    /**
     * The property sections moved from the right of the classes to below them, side by side, when
     * the board then has more nearly a screen's shape - so that Fit shows it larger.
     *
     * <p>Found by a tester: one pattern imported, and the board was a strip 3,475 pixels wide and
     * 596 tall, because the properties stood right of a frame already as wide as a screen. Fit
     * made it 19%, at which no label can be read, and three quarters of the window was empty.
     */
    private static void putSectionsBelowIfThatFitsAScreen(Plan plan, List<List<Term>> members,
            double left, double classesRight, double classesBottom) {
        double rightW = classesRight;
        double rightH = classesBottom;
        double belowW = left;
        double belowH = 0;
        for (Section section : plan.sections) {
            rightW = Math.max(rightW, section.x + section.w);
            rightH = Math.max(rightH, section.y + section.h);
            belowW += section.w + APART;
            belowH = Math.max(belowH, section.h);
        }
        belowW = Math.max(classesRight, belowW - APART);
        belowH += classesBottom + APART;
        double asTheyAre = Math.max(rightW / SCREEN, rightH);
        double below = Math.max(belowW / SCREEN, belowH);
        if (below >= asTheyAre * 0.95) {
            return;
        }
        List<Section> moved = new ArrayList<Section>();
        double x = left;
        double y = classesBottom + APART;
        for (int at = 0; at < plan.sections.size(); at++) {
            Section section = plan.sections.get(at);
            double dx = x - section.x;
            double dy = y - section.y;
            for (Term term : members.get(at)) {
                double[] position = plan.positions.get(term.id);
                if (position != null) {
                    plan.positions.put(term.id, new double[] {position[0] + dx, position[1] + dy});
                }
            }
            moved.add(new Section(section.label, x, y, section.w, section.h));
            x += section.w + APART;
        }
        plan.sections.clear();
        plan.sections.addAll(moved);
    }

    /** An individual, or what hangs off one - the ABox, which goes below the classes. */
    private static boolean isAbox(Term term, Map<String, String> parent, Map<String, Term> byId) {
        Term at = term;
        Set<String> seen = new HashSet<String>();
        while (at != null && seen.add(at.id)) {
            if (at.kind == Kind.INDIVIDUAL) {
                return true;
            }
            if (at.kind == Kind.CLASS) {
                return false;
            }
            at = parent.containsKey(at.id) ? byId.get(parent.get(at.id)) : null;
        }
        return term.kind == Kind.VALUE;
    }

    /**
     * Each term's parent in the tree: a class's first superclass on the board, an individual's
     * first class, a property's first super-property, and for anything else - a restriction's
     * node, a datatype - the first term with an edge to it. Never a cycle.
     */
    static Map<String, String> parents(Map<String, Term> byId, List<Edge> edges) {
        Map<String, String> parent = new HashMap<String, String>();
        for (Edge edge : edges) {
            Term from = byId.get(edge.source);
            Term to = byId.get(edge.target);
            if (from == null || to == null || from == to) {
                continue;
            }
            String child = null;
            String candidate = null;
            if (edge.link == Link.SUBCLASS && from.kind == Kind.CLASS && to.kind == Kind.CLASS) {
                child = from.id;
                candidate = to.id;
            } else if (edge.link == Link.TYPE && from.kind == Kind.INDIVIDUAL
                    && to.kind == Kind.CLASS) {
                child = from.id;
                candidate = to.id;
            } else if (edge.link == Link.SUB_PROPERTY && from.kind == to.kind
                    && (from.kind == Kind.OBJECT_PROPERTY || from.kind == Kind.DATA_PROPERTY)) {
                child = from.id;
                candidate = to.id;
            } else if ((to.kind == Kind.EXPRESSION || to.kind == Kind.VALUE)
                    && from.kind != Kind.VALUE) {
                child = to.id;
                candidate = from.id;
            }
            if (child != null && !parent.containsKey(child)
                    && !isAncestor(child, candidate, parent)) {
                parent.put(child, candidate);
            }
        }
        return parent;
    }

    private static boolean isAncestor(String maybe, String of, Map<String, String> parent) {
        Set<String> seen = new HashSet<String>();
        for (String at = of; at != null && seen.add(at); at = parent.get(at)) {
            if (at.equals(maybe)) {
                return true;
            }
        }
        return false;
    }

    /** The section a term belongs to: its own, or for what hangs off another, its root's. */
    private static Kind sectionOf(Term term, Map<String, String> parent, Map<String, Term> byId) {
        Term root = term;
        Set<String> seen = new HashSet<String>();
        while (parent.containsKey(root.id) && seen.add(root.id)) {
            root = byId.get(parent.get(root.id));
        }
        return root.kind == Kind.OBJECT_PROPERTY || root.kind == Kind.DATA_PROPERTY
                ? root.kind : Kind.CLASS;
    }

    /**
     * One forest from ({@code left}, {@code top}): each class with its subclasses below it, one under
     * the other, in more columns when there are more - see {@link HierarchyColumns}. Returns the
     * bounds {minX, minY, maxX, maxY}.
     */
    private static double[] forest(List<Term> members, Map<String, String> allParents,
            double left, double top, Plan plan) {
        List<HierarchyColumns.Term> boxes = new ArrayList<HierarchyColumns.Term>();
        for (Term term : members) {
            boxes.add(new HierarchyColumns.Term(term.id, term.label, term.w, term.h));
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(boxes, allParents, left, top);
        plan.positions.putAll(placed.positions);
        return new double[] {left, top, placed.right, placed.bottom};
    }

    /** What a group of trees joined by inverse pairs stands in for among the section's trees. */
    private static final String JOINED = "\u0000inverse pair:";

    /** The shape {@link HierarchyColumns} gives a block of trees: 1.8 times as wide as tall. */
    private static final double SHAPE = 1.8;

    /** Between one band of a block and the next: room for a line with an arrowhead at each end. */
    private static final double BAND = HierarchyColumns.UNDER + HierarchyColumns.SIBLINGS;

    /**
     * A property section's forest, where two properties that are each other's inverse stand side
     * by side - and exactly {@link #forest} where no such pair joins two trees.
     *
     * <p>Found on the egg-boiling study board (1.135.1). In label order "has quality", in the has
     * characteristic tree, and "quality of", a tree of its own, stood three columns apart, and the
     * dashed line between them left the frame over its top border and came back in; "concretizes"
     * and "is concretized as" were joined by a loop along the frame's bottom. Trees joined by an
     * inverse pair now go into the section as one block - see {@link #sideBySide} - so that the line
     * between two partners is short and straight and stays in the frame.
     *
     * <p>Which pairs join the trees, and in what order they have their say, follows from what each
     * pair is - see {@link #pairOrder} - and never from the order the lines are listed in. That
     * order is the OWL API's, a hash set of axioms, and a review of round 3 found one unrelated pair
     * more reordering it: the tree levelled on another pair, and moved 160px with nothing changed in
     * it.
     */
    private static double[] propertyForest(List<Term> members, Map<String, String> allParents,
            List<Edge> edges, double left, double top, Plan plan) {
        Map<String, Term> byId = new LinkedHashMap<String, Term>();
        for (Term term : members) {
            byId.put(term.id, term);
        }
        Map<String, String> rootOf = new HashMap<String, String>();
        for (Term term : members) {
            rootOf.put(term.id, rootOf(term.id, allParents, byId));
        }
        // The pairs that join two trees of this section, each from its lesser IRI.
        List<String[]> inverses = new ArrayList<String[]>();
        for (Edge edge : edges) {
            if (edge.link == Link.INVERSE && byId.containsKey(edge.source)
                    && byId.containsKey(edge.target)
                    && !rootOf.get(edge.source).equals(rootOf.get(edge.target))) {
                inverses.add(edge.source.compareTo(edge.target) <= 0
                        ? new String[] {edge.source, edge.target}
                        : new String[] {edge.target, edge.source});
            }
        }
        if (inverses.isEmpty()) {
            return forest(members, allParents, left, top, plan);
        }
        // Each tree as the section would lay it out on its own, its top-left at (0, 0).
        Map<String, HierarchyColumns.Placed> trees = new HashMap<String, HierarchyColumns.Placed>();
        for (Term term : members) {
            String root = rootOf.get(term.id);
            if (!trees.containsKey(root)) {
                trees.put(root, tree(root, members, rootOf, allParents));
            }
        }
        Collections.sort(inverses, pairOrder(rootOf, trees));
        // Which trees go together: each tree's root, pointing on to the first of its group.
        Map<String, String> joined = new HashMap<String, String>();
        List<String[]> pairs = new ArrayList<String[]>();
        for (String[] pair : inverses) {
            String one = groupOf(rootOf.get(pair[0]), joined);
            String other = groupOf(rootOf.get(pair[1]), joined);
            if (!one.equals(other)) {
                joined.put(other, one);
                pairs.add(pair);
            }
        }
        // The trees of each group, in the order the section lists them.
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        for (Term term : members) {
            String root = rootOf.get(term.id);
            String group = groupOf(root, joined);
            List<String> roots = groups.get(group);
            if (roots == null) {
                roots = new ArrayList<String>();
                groups.put(group, roots);
            }
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        List<HierarchyColumns.Term> outer = new ArrayList<HierarchyColumns.Term>();
        Map<String, Map<String, double[]>> blocks = new HashMap<String, Map<String, double[]>>();
        for (Term term : members) {
            String group = groupOf(rootOf.get(term.id), joined);
            if (groups.get(group).size() == 1) {
                outer.add(new HierarchyColumns.Term(term.id, term.label, term.w, term.h));
            } else if (!blocks.containsKey(JOINED + group)) {
                double[] size = new double[2];
                Map<String, double[]> block = sideBySide(groups.get(group), pairs, byId, rootOf,
                        trees, room(groups.get(group), trees), size);
                blocks.put(JOINED + group, block);
                List<String> roots = new ArrayList<String>(groups.get(group));
                Collections.sort(roots, (one, other) -> byId.get(one).label.toLowerCase(Locale.ROOT)
                        .compareTo(byId.get(other).label.toLowerCase(Locale.ROOT)));
                outer.add(new HierarchyColumns.Term(JOINED + group, byId.get(roots.get(0)).label,
                        size[0], size[1]));
            }
        }
        HierarchyColumns.Placed placed = HierarchyColumns.place(outer, allParents, left, top);
        for (Map.Entry<String, double[]> at : placed.positions.entrySet()) {
            Map<String, double[]> block = blocks.get(at.getKey());
            if (block == null) {
                plan.positions.put(at.getKey(), at.getValue());
                continue;
            }
            for (Map.Entry<String, double[]> inside : block.entrySet()) {
                plan.positions.put(inside.getKey(), new double[] {
                    at.getValue()[0] + inside.getValue()[0], at.getValue()[1] + inside.getValue()[1]});
            }
        }
        return new double[] {left, top, placed.right, placed.bottom};
    }

    /**
     * How wide the block of these trees grows before the next tree starts a band below it: as wide
     * as a block of their size has a screen's shape, {@link #SHAPE} times as wide as it is tall, as
     * {@link HierarchyColumns} shapes a block of trees. Its height is taken in steps of half a
     * column, so that one box more or less does not move where the bands break, and is at least a
     * column - so that two or three partners always have room in a row.
     *
     * <p>Not the whole section's width: on a review's random boards two large groups of joined trees
     * made two blocks, each as wide as the section, that stood side by side. Not from the tallest
     * tree's height either: a block that wide beside it took the width of the columns the section's
     * other trees would have filled. And rounded up, not down as a column's height is: a block
     * narrower than its shape is a tower of bands.
     */
    private static double room(List<String> roots, Map<String, HierarchyColumns.Placed> trees) {
        double area = 0;
        for (String root : roots) {
            HierarchyColumns.Placed tree = trees.get(root);
            area += (tree.right + HierarchyColumns.COLUMNS)
                    * (tree.bottom + HierarchyColumns.SIBLINGS);
        }
        double step = HierarchyColumns.COLUMN_HEIGHT / 2;
        return SHAPE * Math.ceil(Math.max(HierarchyColumns.COLUMN_HEIGHT,
                Math.sqrt(area / SHAPE)) / step) * step;
    }

    /**
     * The order inverse pairs have their say in, by what each pair is: a pair of two roots first -
     * mirrored hierarchies stand level on their tops - then the pair standing highest in its two
     * trees, then by IRI. Each pair runs from its lesser IRI, so the order is total.
     */
    private static Comparator<String[]> pairOrder(final Map<String, String> rootOf,
            final Map<String, HierarchyColumns.Placed> trees) {
        return new Comparator<String[]>() {
            @Override
            public int compare(String[] one, String[] other) {
                int roots = Integer.compare(roots(one), roots(other));
                if (roots != 0) {
                    return roots;
                }
                int lower = Double.compare(Math.max(row(one[0]), row(one[1])),
                        Math.max(row(other[0]), row(other[1])));
                if (lower != 0) {
                    return lower;
                }
                int higher = Double.compare(Math.min(row(one[0]), row(one[1])),
                        Math.min(row(other[0]), row(other[1])));
                if (higher != 0) {
                    return higher;
                }
                int first = one[0].compareTo(other[0]);
                return first != 0 ? first : one[1].compareTo(other[1]);
            }

            /** 0 for a pair of two roots, 1 for any other. */
            private int roots(String[] pair) {
                return rootOf.get(pair[0]).equals(pair[0]) && rootOf.get(pair[1]).equals(pair[1])
                        ? 0 : 1;
            }

            /** How far down its own tree a property stands. */
            private double row(String id) {
                return trees.get(rootOf.get(id)).positions.get(id)[1];
            }
        };
    }

    /**
     * The trees of one group as one block, its top-left at (0, 0). The first in label order goes
     * first. Then, pair by pair in {@link #pairOrder}, each other tree goes where the property in it
     * stands level with its partner: in the first gap right of the partner that no other tree
     * stands in, or failing that the first on its left - so long as no box stands between the two
     * and the block keeps within {@code room} across and its shape down. Where neither is free, the
     * tree goes into a band below the block, under its partner if there is room there and else in
     * the band's first gap; a new band starts below everything only when that one is full. Its
     * line is then routed like any other.
     *
     * <p>Round 3 put each tree right of all the block before it. A review measured a tree whose
     * sub-properties each have an inverse of their own come out as one row 1.7 to 2.3 times as wide
     * as the section had been, lines 2,000px long across an empty frame - and from seven
     * sub-properties on, two columns, a partner standing behind a box of the second, its line round
     * that box. A property with two inverses stood its second partner behind the first. Now such a
     * tree has its partners in a column on either side of it, every line straight.
     *
     * <p>Two hierarchies that mirror each other stand side by side, as round 3 put them: the
     * second tree goes right of the first whatever the room and whatever stands between, where the
     * pair joining them stands as far down the one tree as the other and is either a pair of two
     * roots or the pair that has its say first. A review of round 4 found the library's aquatic
     * resource observation pattern - "has parameter" and "is parameter of", each sub-property's
     * inverse under the other, labels of about 320px - with "is parameter of" in a band under "has
     * parameter": the two side by side were wider than a block of their size in a screen's shape,
     * and every sub-property's line went round, two of them out of the frame. The roots' pair is
     * declared in the ontology the pattern imports; without it, the first sub-properties' pair
     * joins the two, and its line, passing the first tree's second column, is routed round it. Two
     * trees are only as wide as they are, so no strip grows from this; from the third tree on, the
     * room holds. A third tree that comes first in label order is placed first, and the mirror's
     * second tree is then held to the room like any other.
     *
     * <p>Returns where each term goes; {@code size} gets the block's width and height.
     */
    private static Map<String, double[]> sideBySide(List<String> roots, List<String[]> pairs,
            final Map<String, Term> byId, Map<String, String> rootOf,
            Map<String, HierarchyColumns.Placed> trees, double room, double[] size) {
        List<String> order = new ArrayList<String>(roots);
        Collections.sort(order, (one, other) -> {
            int byLabel = byId.get(one).label.toLowerCase(Locale.ROOT)
                    .compareTo(byId.get(other).label.toLowerCase(Locale.ROOT));
            return byLabel != 0 ? byLabel : one.compareTo(other);
        });
        Map<String, double[]> at = new LinkedHashMap<String, double[]>();
        // Where each tree placed so far stands: x, y, w, h.
        List<double[]> placed = new ArrayList<double[]>();
        // The top of the band below the block being filled, none yet.
        double[] band = {Double.NaN};
        // How tall the block may grow with trees level with their partners: its shape's height,
        // or its tallest tree's - a partner deep in a tall tree still stands beside it.
        double high = room / SHAPE;
        for (String root : order) {
            high = Math.max(high, trees.get(root).bottom);
        }
        Set<String> placedRoots = new HashSet<String>();
        put(trees.get(order.get(0)), 0, 0, at, placed);
        placedRoots.add(order.get(0));
        // The pair of these trees that has its say first.
        String[] leading = null;
        for (String[] pair : pairs) {
            if (roots.contains(rootOf.get(pair[0]))) {
                leading = pair;
                break;
            }
        }
        while (placedRoots.size() < order.size()) {
            String partner = null;
            String placing = null;
            String[] joining = null;
            for (String[] pair : pairs) {
                boolean first = placedRoots.contains(rootOf.get(pair[0]));
                if (first != placedRoots.contains(rootOf.get(pair[1]))) {
                    partner = first ? pair[0] : pair[1];
                    placing = first ? pair[1] : pair[0];
                    joining = pair;
                    break;
                }
            }
            if (placing == null) {
                break;
            }
            HierarchyColumns.Placed tree = trees.get(rootOf.get(placing));
            // Mirrored hierarchies: the second tree, joined to the first by a pair standing as far
            // down the one tree as the other - two roots, or the pair that has its say first.
            boolean mirror = placedRoots.size() == 1
                    && at.get(partner)[1] == tree.positions.get(placing)[1]
                    && (joining == leading || partner.equals(rootOf.get(partner))
                            && placing.equals(rootOf.get(placing)));
            double[] spot = spotFor(partner, placing, tree, byId, at, placed, room, high, band,
                    mirror);
            put(tree, spot[0], spot[1], at, placed);
            placedRoots.add(rootOf.get(placing));
        }
        // Never so: the pairs are what joined these trees. But a term must not go missing.
        for (String root : order) {
            if (placedRoots.add(root)) {
                double[] block = bounds(placed);
                put(trees.get(root), block[0], block[3] + BAND, at, placed);
            }
        }
        double[] block = bounds(placed);
        for (double[] position : at.values()) {
            position[0] -= block[0];
            position[1] -= block[1];
        }
        size[0] = block[2] - block[0];
        size[1] = block[3] - block[1];
        return at;
    }

    /**
     * Where a tree goes - its top-left - so that {@code placing}, in it, stands level with and
     * beside {@code partner}, already placed: see {@link #sideBySide}. Where it cannot, it goes in
     * the band below the block, {@code band}, filled from under its partner on and then from the
     * left; a new band is started below everything only when that one is full. With
     * {@code beside}, the tree goes right of the block, level, however wide the block grows and
     * whatever stands between the two: their line is routed round it.
     */
    private static double[] spotFor(String partner, String placing, HierarchyColumns.Placed tree,
            Map<String, Term> byId, Map<String, double[]> at, List<double[]> placed, double room,
            double high, double[] band, boolean beside) {
        double[] p = at.get(partner);
        double[] q = tree.positions.get(placing);
        Term from = byId.get(partner);
        Term to = byId.get(placing);
        double[] block = bounds(placed);
        double wide = Math.max(room, block[2] - block[0]);
        double y = p[1] - q[1];
        // Level, the tree may reach above or below the block, as far as the block may grow.
        boolean fits = Math.max(block[3], y + tree.bottom) - Math.min(block[1], y)
                <= Math.max(high, block[3] - block[1]);
        double level = Math.min(from.h, to.h);
        double right = slide(p[0] + from.w + HierarchyColumns.COLUMNS - q[0], y, tree, placed, 1);
        if (fits && (beside || Math.max(block[2], right + tree.right) - block[0] <= wide
                && clear(p[0] + from.w, right + q[0], p[1], p[1] + level, partner, placing, at,
                        byId, tree, right, y))) {
            return new double[] {right, y};
        }
        double left = slide(p[0] - HierarchyColumns.COLUMNS - to.w - q[0], y, tree, placed, -1);
        if (fits && block[2] - Math.min(block[0], left) <= wide
                && clear(left + q[0] + to.w, p[0], p[1], p[1] + level, partner, placing, at, byId,
                        tree, left, y)) {
            return new double[] {left, y};
        }
        double end = block[0] + wide;
        double under = Math.max(block[0], Math.min(p[0] - q[0], end - tree.right));
        if (!Double.isNaN(band[0])) {
            for (double start : new double[] {under, block[0]}) {
                double x = slide(start, band[0], tree, placed, 1);
                if (x + tree.right <= end) {
                    return new double[] {x, band[0]};
                }
            }
        }
        band[0] = block[3] + BAND;
        return new double[] {under, band[0]};
    }

    /**
     * Where a tree at height {@code y} goes from {@code x} on: moved on in {@code direction} - 1 to
     * the right, -1 to the left - past every tree placed that it would stand closer to than a
     * column's gap, side by side, or a sibling's gap, one above the other.
     */
    private static double slide(double x, double y, HierarchyColumns.Placed tree,
            List<double[]> placed, int direction) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (double[] other : placed) {
                if (y < other[1] + other[3] + HierarchyColumns.SIBLINGS
                        && other[1] < y + tree.bottom + HierarchyColumns.SIBLINGS
                        && x < other[0] + other[2] + HierarchyColumns.COLUMNS
                        && other[0] < x + tree.right + HierarchyColumns.COLUMNS) {
                    double past = direction > 0 ? other[0] + other[2] + HierarchyColumns.COLUMNS
                            : other[0] - HierarchyColumns.COLUMNS - tree.right;
                    // Always on, never back: the loop ends whatever the rounding.
                    if (direction > 0 ? past > x : past < x) {
                        x = past;
                        moved = true;
                    }
                }
            }
        }
        return x;
    }

    /**
     * Whether a straight line from {@code x1} to {@code x2}, between {@code y1} and {@code y2}, is
     * clear of every box but its two ends: those placed so far, and those of the tree being placed
     * at ({@code dx}, {@code dy}).
     */
    private static boolean clear(double x1, double x2, double y1, double y2, String partner,
            String placing, Map<String, double[]> at, Map<String, Term> byId,
            HierarchyColumns.Placed tree, double dx, double dy) {
        for (Map.Entry<String, double[]> box : at.entrySet()) {
            Term term = byId.get(box.getKey());
            double[] p = box.getValue();
            if (!box.getKey().equals(partner)
                    && p[0] < x2 && p[0] + term.w > x1 && p[1] < y2 && p[1] + term.h > y1) {
                return false;
            }
        }
        for (Map.Entry<String, double[]> box : tree.positions.entrySet()) {
            Term term = byId.get(box.getKey());
            double x = dx + box.getValue()[0];
            double y = dy + box.getValue()[1];
            if (!box.getKey().equals(placing)
                    && x < x2 && x + term.w > x1 && y < y2 && y + term.h > y1) {
                return false;
            }
        }
        return true;
    }

    /** A tree put with its top-left at ({@code x}, {@code y}). */
    private static void put(HierarchyColumns.Placed tree, double x, double y,
            Map<String, double[]> at, List<double[]> placed) {
        for (Map.Entry<String, double[]> one : tree.positions.entrySet()) {
            at.put(one.getKey(), new double[] {x + one.getValue()[0], y + one.getValue()[1]});
        }
        placed.add(new double[] {x, y, tree.right, tree.bottom});
    }

    /** What the trees placed so far cover: {minX, minY, maxX, maxY}. */
    private static double[] bounds(List<double[]> placed) {
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            -Double.MAX_VALUE};
        for (double[] one : placed) {
            bounds[0] = Math.min(bounds[0], one[0]);
            bounds[1] = Math.min(bounds[1], one[1]);
            bounds[2] = Math.max(bounds[2], one[0] + one[2]);
            bounds[3] = Math.max(bounds[3], one[1] + one[3]);
        }
        return bounds;
    }

    /** One tree of the section, its top-left at (0, 0), placed as {@link #forest} would place it. */
    private static HierarchyColumns.Placed tree(String root, List<Term> members,
            Map<String, String> rootOf, Map<String, String> allParents) {
        List<HierarchyColumns.Term> boxes = new ArrayList<HierarchyColumns.Term>();
        for (Term term : members) {
            if (root.equals(rootOf.get(term.id))) {
                boxes.add(new HierarchyColumns.Term(term.id, term.label, term.w, term.h));
            }
        }
        return HierarchyColumns.place(boxes, allParents, 0, 0);
    }

    /** The root of the term's tree as {@link HierarchyColumns} reads it: its parent's, if placed. */
    private static String rootOf(String id, Map<String, String> parent, Map<String, Term> placed) {
        String at = id;
        Set<String> seen = new HashSet<String>();
        while (seen.add(at)) {
            String up = parent.get(at);
            if (up == null || up.equals(at) || !placed.containsKey(up)) {
                break;
            }
            at = up;
        }
        return at;
    }

    /** The first tree of the group this tree has been put in. */
    private static String groupOf(String root, Map<String, String> joined) {
        String at = root;
        while (joined.containsKey(at)) {
            at = joined.get(at);
        }
        return at;
    }

    /**
     * The individuals, in a band below the classes: each class's individuals under that class,
     * in a column - or several, for many - starting at the class's left edge, never overlapping
     * the group before. What hangs off an individual, a value, stands right below it, set in by
     * 20px.
     *
     * <p>A column is as wide as its class's widest individual, or as far as a value in it reaches
     * when that is further (1.135.1). A review of round 4 found a value wider than its column less
     * the 20px reaching into the next column, or onto the next class's individuals, on 53 boards
     * of 2,500 made up at random; wider still, a value reached under the property sections. Only
     * the column holding such a value is widened, and where every value fits, nothing moves.
     */
    private static double placeIndividuals(List<Term> individuals, Map<String, String> parent,
            Map<String, Term> byId, Plan plan, double left, double top) {
        Map<String, List<Term>> byClass = new LinkedHashMap<String, List<Term>>();
        Map<String, List<Term>> hanging = new HashMap<String, List<Term>>();
        for (Term term : individuals) {
            String up = parent.get(term.id);
            Term above = up == null ? null : byId.get(up);
            if (term.kind != Kind.INDIVIDUAL && above != null
                    && above.kind == Kind.INDIVIDUAL) {
                List<Term> values = hanging.get(up);
                if (values == null) {
                    values = new ArrayList<Term>();
                    hanging.put(up, values);
                }
                values.add(term);
                continue;
            }
            String key = above != null && plan.positions.containsKey(up) ? up : "";
            List<Term> group = byClass.get(key);
            if (group == null) {
                group = new ArrayList<Term>();
                byClass.put(key, group);
            }
            group.add(term);
        }
        List<String> classes = new ArrayList<String>(byClass.keySet());
        // Across the board, and down it where classes stand one under the other.
        Collections.sort(classes, (one, other) -> {
            int across = Double.compare(xOf(plan, one, left), xOf(plan, other, left));
            return across != 0 ? across : Double.compare(yOf(plan, one), yOf(plan, other));
        });
        double cursor = left;
        double bottom = top;
        for (String cls : classes) {
            List<Term> group = byClass.get(cls);
            Collections.sort(group, ORDER);
            double colW = 0;
            for (Term term : group) {
                colW = Math.max(colW, term.w);
            }
            int perColumn = 12;
            // Individuals of no class on the board go after the others, not at "no position".
            double x = plan.positions.containsKey(cls) ? Math.max(cursor, xOf(plan, cls, left))
                    : cursor;
            double y = top;
            int inColumn = 0;
            // How far right of x the column's boxes reach.
            double reach = colW;
            for (Term term : group) {
                if (inColumn == perColumn) {
                    x += reach + ACROSS / 2;
                    y = top;
                    inColumn = 0;
                    reach = colW;
                }
                plan.positions.put(term.id, new double[] {x, y});
                y += term.h + DOWN;
                List<Term> values = hanging.get(term.id);
                if (values != null) {
                    for (Term value : values) {
                        plan.positions.put(value.id, new double[] {x + 20, y});
                        reach = Math.max(reach, 20 + value.w);
                        y += value.h + DOWN;
                    }
                }
                bottom = Math.max(bottom, y);
                inColumn++;
            }
            cursor = x + reach + ACROSS;
        }
        return Math.max(cursor - ACROSS, left);
    }

    private static double yOf(Plan plan, String id) {
        double[] at = plan.positions.get(id);
        return at == null ? Double.MAX_VALUE / 4 : at[1];
    }

    private static double xOf(Plan plan, String id, double left) {
        double[] at = plan.positions.get(id);
        return at == null ? Double.MAX_VALUE / 4 : at[0];
    }

    /** Classes first, then what hangs off them; alphabetically within each. */
    private static final Comparator<Term> ORDER = new Comparator<Term>() {
        @Override
        public int compare(Term one, Term other) {
            if (one.kind != other.kind) {
                return one.kind.compareTo(other.kind);
            }
            return one.label.toLowerCase(Locale.ROOT).compareTo(
                    other.label.toLowerCase(Locale.ROOT));
        }
    };
}
