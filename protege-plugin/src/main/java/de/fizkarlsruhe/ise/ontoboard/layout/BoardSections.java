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
 *   <li><b>Classes are a tree read left to right</b>, as in Protege's own class hierarchy:
 *       a class, then its subclasses stacked in the next column. Every edge from a child to its
 *       parent leaves the child's side, so siblings share one line back to their parent and no
 *       line runs through a box.
 *   <li><b>What hangs off a class goes beside it</b> - its individuals, the restrictions it is
 *       drawn with, a datatype - in the column after it.
 *   <li><b>A subtree too tall for a screen is a block of its own</b>, and blocks are stacked in
 *       columns, so a deep hierarchy is a few screens wide rather than one screen high and
 *       twenty long.
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

    /** Which links decide the tree. Everything else is drawn but has no say. */
    public enum Link {
        SUBCLASS, TYPE, SUB_PROPERTY, OTHER
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

    /** Between a column of the tree and the next: room for the line back to the parent. */
    static final double ACROSS = 70;

    /** Between two siblings. */
    static final double DOWN = 16;

    /** Between two blocks, and around a section inside its frame. */
    static final double APART = 110;

    /** Room inside a section's frame for its title. */
    static final double TITLE = 44;

    private BoardSections() {
    }

    /** Every term placed, the top-left of the board at ({@code left}, {@code top}). */
    public static Plan plan(List<Term> terms, List<Edge> edges, double left, double top) {
        Map<String, Term> byId = new LinkedHashMap<String, Term>();
        for (Term term : terms) {
            byId.put(term.id, term);
        }
        Map<String, String> parent = parents(byId, edges);
        Plan plan = new Plan();

        List<Term> classes = new ArrayList<Term>();
        List<Term> objectProperties = new ArrayList<Term>();
        List<Term> dataProperties = new ArrayList<Term>();
        for (Term term : terms) {
            Kind section = sectionOf(term, parent, byId);
            if (section == Kind.OBJECT_PROPERTY) {
                objectProperties.add(term);
            } else if (section == Kind.DATA_PROPERTY) {
                dataProperties.add(term);
            } else {
                classes.add(term);
            }
        }
        double[] classBox = place(classes, parent, left, top, plan);
        double x = classes.isEmpty() ? left : classBox[2] + APART * 2;
        double y = top;
        for (Object[] section : new Object[][] {
            {"Object properties", objectProperties}, {"Data properties", dataProperties}}) {
            @SuppressWarnings("unchecked")
            List<Term> members = (List<Term>) section[1];
            if (members.isEmpty()) {
                continue;
            }
            double[] box = place(members, parent, x + APART / 2, y + TITLE, plan);
            double w = box[2] - x + APART / 2;
            double h = box[3] - y + APART / 2;
            plan.sections.add(new Section((String) section[0], x, y, w, h));
            y += h + APART;
        }
        return plan;
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
     * Lays out one section's forest from ({@code left}, {@code top}); returns its bounds as
     * {minX, minY, maxX, maxY}.
     */
    private static double[] place(List<Term> members, Map<String, String> allParents,
            double left, double top, Plan plan) {
        Map<String, Term> here = new LinkedHashMap<String, Term>();
        for (Term term : members) {
            here.put(term.id, term);
        }
        Map<String, List<Term>> children = new HashMap<String, List<Term>>();
        List<Term> roots = new ArrayList<Term>();
        for (Term term : members) {
            String up = allParents.get(term.id);
            if (up != null && here.containsKey(up)) {
                List<Term> siblings = children.get(up);
                if (siblings == null) {
                    siblings = new ArrayList<Term>();
                    children.put(up, siblings);
                }
                siblings.add(term);
            } else {
                roots.add(term);
            }
        }
        for (List<Term> siblings : children.values()) {
            Collections.sort(siblings, ORDER);
        }
        Collections.sort(roots, ORDER);

        // Every term's subtree height, then blocks: a subtree taller than a screen or two is
        // cut off its parent and laid out as a block of its own.
        Map<String, Double> height = new HashMap<String, Double>();
        for (Term root : roots) {
            measure(root, children, height);
        }
        double total = 0;
        for (Term term : members) {
            total += (term.w + ACROSS) * (term.h + DOWN);
        }
        double tallest = Math.max(900, Math.sqrt(total / 1.6) * 1.15);
        List<Term> blocks = new ArrayList<Term>();
        Set<String> cut = new HashSet<String>();
        List<Term> loose = new ArrayList<Term>();
        for (Term root : roots) {
            if (!children.containsKey(root.id)) {
                loose.add(root);
            } else {
                cutBlocks(root, children, height, tallest, blocks, cut);
            }
        }

        // Every block laid out, then stacked in columns as tall as makes the whole board about
        // as wide as a screen is, relative to its height: a column per block, which is what the
        // cut threshold alone gives, made a board eight times wider than tall.
        List<Map<String, double[]>> laid = new ArrayList<Map<String, double[]>>();
        List<double[]> sizes = new ArrayList<double[]>();
        double area = 0;
        double highest = 0;
        for (Term block : blocks) {
            Map<String, double[]> at = new HashMap<String, double[]>();
            double h = layoutBlock(block, children, cut, at, tallest);
            double w = widthOf(at, block, children, cut);
            laid.add(at);
            sizes.add(new double[] {w, h});
            area += (w + APART) * (h + APART);
            highest = Math.max(highest, h);
        }
        for (Term term : loose) {
            area += (term.w + ACROSS) * (term.h + DOWN);
        }
        double column = Math.max(highest, Math.sqrt(area / 1.6));
        double x = left;
        double y = top;
        double columnRight = left;
        double maxX = left;
        double maxY = top;
        List<double[]> boxes = new ArrayList<double[]>();
        for (int b = 0; b < blocks.size(); b++) {
            Map<String, double[]> at = laid.get(b);
            double w = sizes.get(b)[0];
            double h = sizes.get(b)[1];
            if (y > top && y + h > top + column) {
                x = columnRight + APART;
                y = top;
            }
            for (Map.Entry<String, double[]> placed : at.entrySet()) {
                plan.positions.put(placed.getKey(),
                        new double[] {x + placed.getValue()[0], y + placed.getValue()[1]});
            }
            boxes.add(new double[] {x, y, x + w, y + h});
            columnRight = Math.max(columnRight, x + w);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
            y += h + APART;
        }
        // Terms with no hierarchy to place them by, in columns after the rest.
        if (!loose.isEmpty()) {
            x = blocks.isEmpty() ? left : columnRight + APART;
            y = top;
            double widest = 0;
            for (Term term : loose) {
                if (y > top && y + term.h > top + column) {
                    x += widest + ACROSS;
                    y = top;
                    widest = 0;
                }
                plan.positions.put(term.id, new double[] {x, y});
                widest = Math.max(widest, term.w);
                maxX = Math.max(maxX, x + term.w);
                maxY = Math.max(maxY, y + term.h);
                y += term.h + DOWN;
            }
        }
        return new double[] {left, top, maxX, maxY};
    }

    /** Height of a term's subtree laid out left to right. */
    private static double measure(Term term, Map<String, List<Term>> children,
            Map<String, Double> height) {
        List<Term> below = children.get(term.id);
        double span = 0;
        if (below != null) {
            for (Term child : below) {
                span += measure(child, children, height) + DOWN;
            }
            span -= DOWN;
        }
        double h = Math.max(term.h, span);
        height.put(term.id, h);
        return h;
    }

    /** Cuts subtrees taller than {@code tallest} into blocks of their own, depth first. */
    private static void cutBlocks(Term root, Map<String, List<Term>> children,
            Map<String, Double> height, double tallest, List<Term> blocks, Set<String> cut) {
        blocks.add(root);
        List<Term> later = new ArrayList<Term>();
        collectCuts(root, children, height, tallest, later, cut, true);
        for (Term next : later) {
            cutBlocks(next, children, height, tallest, blocks, cut);
        }
    }

    private static void collectCuts(Term term, Map<String, List<Term>> children,
            Map<String, Double> height, double tallest, List<Term> later, Set<String> cut,
            boolean isBlockRoot) {
        List<Term> below = children.get(term.id);
        if (below == null) {
            return;
        }
        if (!isBlockRoot && height.get(term.id) > tallest) {
            cut.add(term.id);
            later.add(term);
            return;
        }
        for (Term child : below) {
            collectCuts(child, children, height, tallest, later, cut, false);
        }
        remeasure(term, below, height, cut);
        // Still too tall, though no branch is on its own: twelve branches of thirty leaves
        // each fit a screen, and one under another they made a strip 27,000px long. The
        // biggest go to blocks of their own until what is left fits.
        List<Term> branches = new ArrayList<Term>();
        for (Term child : below) {
            if (!cut.contains(child.id) && children.containsKey(child.id)) {
                branches.add(child);
            }
        }
        Collections.sort(branches, new Comparator<Term>() {
            @Override
            public int compare(Term one, Term other) {
                return Double.compare(height.get(other.id), height.get(one.id));
            }
        });
        for (Term branch : branches) {
            if (height.get(term.id) <= tallest) {
                break;
            }
            cut.add(branch.id);
            later.add(branch);
            remeasure(term, below, height, cut);
        }
    }

    /** A term's height counting only what is still under it. */
    private static void remeasure(Term term, List<Term> below, Map<String, Double> height,
            Set<String> cut) {
        double span = 0;
        for (Term child : below) {
            if (!cut.contains(child.id)) {
                span += height.get(child.id) + DOWN;
            }
        }
        height.put(term.id, Math.max(term.h, span - DOWN));
    }

    /** Positions relative to the block's top-left; returns the block's height. */
    private static double layoutBlock(Term root, Map<String, List<Term>> children,
            Set<String> cut, Map<String, double[]> at, double tallest) {
        Map<Integer, Double> widest = new HashMap<Integer, Double>();
        depths(root, 0, children, cut, widest, tallest);
        Map<Integer, Double> column = new HashMap<Integer, Double>();
        double x = 0;
        for (int depth = 0; widest.containsKey(depth); depth++) {
            column.put(depth, x);
            x += widest.get(depth) + ACROSS;
        }
        return stack(root, 0, 0, children, cut, column, at, tallest);
    }

    /** The children of {@code term} still under it in this block. */
    private static List<Term> drawnUnder(Term term, Map<String, List<Term>> children,
            Set<String> cut) {
        List<Term> below = new ArrayList<Term>();
        if (children.containsKey(term.id)) {
            for (Term child : children.get(term.id)) {
                if (!cut.contains(child.id)) {
                    below.add(child);
                }
            }
        }
        return below;
    }

    /** The children with nothing under them - the ones a grid can hold. */
    private static List<Term> leavesOf(List<Term> below, Map<String, List<Term>> children,
            Set<String> cut) {
        List<Term> leaves = new ArrayList<Term>();
        for (Term child : below) {
            if (drawnUnder(child, children, cut).isEmpty()) {
                leaves.add(child);
            }
        }
        return leaves;
    }

    /**
     * How many columns a term's leaves take: one, unless one column of them would be taller
     * than a screen - a class with 300 individuals was one column 22,800px tall, and every other
     * column of the board grew to match it.
     */
    private static int gridColumns(List<Term> leaves, double tallest) {
        double span = -DOWN;
        for (Term leaf : leaves) {
            span += leaf.h + DOWN;
        }
        return leaves.size() > 1 && span > tallest ? (int) Math.ceil(span / tallest) : 1;
    }

    private static double widestOf(List<Term> terms) {
        double widest = 0;
        for (Term term : terms) {
            widest = Math.max(widest, term.w);
        }
        return widest;
    }

    private static void depths(Term term, int depth, Map<String, List<Term>> children,
            Set<String> cut, Map<Integer, Double> widest, double tallest) {
        Double known = widest.get(depth);
        widest.put(depth, known == null ? term.w : Math.max(known, term.w));
        List<Term> below = drawnUnder(term, children, cut);
        List<Term> leaves = leavesOf(below, children, cut);
        int grid = gridColumns(leaves, tallest);
        if (grid > 1) {
            double across = grid * widestOf(leaves) + (grid - 1) * ACROSS / 2;
            Double next = widest.get(depth + 1);
            widest.put(depth + 1, next == null ? across : Math.max(next, across));
        }
        for (Term child : below) {
            depths(child, depth + 1, children, cut, widest, tallest);
        }
    }

    /** Places a subtree with its top at {@code y}; the term centred on its children. */
    private static double stack(Term term, int depth, double y, Map<String, List<Term>> children,
            Set<String> cut, Map<Integer, Double> column, Map<String, double[]> at,
            double tallest) {
        List<Term> below = drawnUnder(term, children, cut);
        if (below.isEmpty()) {
            at.put(term.id, new double[] {column.get(depth), y});
            return term.h;
        }
        double next = y;
        List<Term> leaves = leavesOf(below, children, cut);
        int grid = gridColumns(leaves, tallest);
        List<Term> stacked = below;
        if (grid > 1) {
            // The leaves first, in columns side by side; the branches below them.
            int perColumn = (int) Math.ceil(leaves.size() / (double) grid);
            double pitch = widestOf(leaves) + ACROSS / 2;
            double bottom = y;
            double[] down = new double[grid];
            java.util.Arrays.fill(down, y);
            for (int i = 0; i < leaves.size(); i++) {
                int col = i / perColumn;
                Term leaf = leaves.get(i);
                at.put(leaf.id, new double[] {column.get(depth + 1) + col * pitch, down[col]});
                down[col] += leaf.h + DOWN;
                bottom = Math.max(bottom, down[col]);
            }
            next = bottom;
            stacked = new ArrayList<Term>(below);
            stacked.removeAll(leaves);
        }
        for (Term child : stacked) {
            next += stack(child, depth + 1, next, children, cut, column, at, tallest) + DOWN;
        }
        double span = next - DOWN - y;
        double h = Math.max(term.h, span);
        at.put(term.id, new double[] {column.get(depth), y + (span - term.h) / 2});
        if (term.h > span) {
            // A term taller than everything under it: its children centred on it instead.
            double shift = (term.h - span) / 2;
            at.get(term.id)[1] = y;
            for (Term child : below) {
                shiftSubtree(child, shift, children, cut, at);
            }
        }
        return h;
    }

    private static void shiftSubtree(Term term, double dy, Map<String, List<Term>> children,
            Set<String> cut, Map<String, double[]> at) {
        at.get(term.id)[1] += dy;
        if (children.containsKey(term.id)) {
            for (Term child : children.get(term.id)) {
                if (!cut.contains(child.id)) {
                    shiftSubtree(child, dy, children, cut, at);
                }
            }
        }
    }

    private static double widthOf(Map<String, double[]> at, Term root,
            Map<String, List<Term>> children, Set<String> cut) {
        double[] right = {0};
        widthWalk(root, at, children, cut, right);
        return right[0];
    }

    private static void widthWalk(Term term, Map<String, double[]> at,
            Map<String, List<Term>> children, Set<String> cut, double[] right) {
        right[0] = Math.max(right[0], at.get(term.id)[0] + term.w);
        if (children.containsKey(term.id)) {
            for (Term child : children.get(term.id)) {
                if (!cut.contains(child.id)) {
                    widthWalk(child, at, children, cut, right);
                }
            }
        }
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
