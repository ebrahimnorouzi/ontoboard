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
 *       centred over them, each level of the hierarchy one row. Many leaves under one class
 *       stand in a grid below it. Trees are packed onto shelves about a screen wide.
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
        double area = 0;
        for (Term term : classes) {
            area += (term.w + ACROSS) * (term.h + BELOW);
        }
        double width = Math.max(1600, Math.sqrt(area * 1.6) * 1.2);
        double[] tbox = forest(classes, parent, left, top, width, plan);
        double right = tbox[2];
        if (!individuals.isEmpty()) {
            double aboxTop = (classes.isEmpty() ? top : tbox[3]) + APART * 2;
            right = Math.max(right, placeIndividuals(individuals, parent, byId, plan, left,
                    aboxTop));
        }
        double x = (classes.isEmpty() && individuals.isEmpty() ? left : right) + APART * 2;
        double y = top;
        for (Object[] section : new Object[][] {
            {"Object properties", objectProperties}, {"Data properties", dataProperties}}) {
            @SuppressWarnings("unchecked")
            List<Term> members = (List<Term>) section[1];
            if (members.isEmpty()) {
                continue;
            }
            double[] box = forest(members, parent, x + APART / 2, y + TITLE, 1200, plan);
            double w = box[2] - x + APART / 2;
            double h = box[3] - y + APART / 2;
            plan.sections.add(new Section((String) section[0], x, y, w, h));
            y += h + APART;
        }
        return plan;
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
     * One forest laid out top down from ({@code left}, {@code top}): each class above its
     * subclasses, centred over them, siblings side by side. Roots are packed onto shelves about
     * {@code width} wide, so a wide ontology wraps instead of running off to the right. Returns
     * the bounds {minX, minY, maxX, maxY}.
     */
    private static double[] forest(List<Term> members, Map<String, String> allParents,
            double left, double top, double width, Plan plan) {
        Map<String, Term> here = new LinkedHashMap<String, Term>();
        for (Term term : members) {
            here.put(term.id, term);
        }
        Map<String, List<Term>> children = new HashMap<String, List<Term>>();
        List<Term> roots = new ArrayList<Term>();
        for (Term term : members) {
            String up = allParents.get(term.id);
            if (up != null && here.containsKey(up) && !up.equals(term.id)) {
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
        // Trees first, biggest first; terms with nothing under them after, together.
        Map<String, double[]> size = new HashMap<String, double[]>();
        for (Term root : roots) {
            measure(root, children, size, width);
        }
        List<Term> trees = new ArrayList<Term>();
        List<Term> loose = new ArrayList<Term>();
        for (Term root : roots) {
            (children.containsKey(root.id) ? trees : loose).add(root);
        }
        Collections.sort(trees, (one, other) -> Double.compare(
                size.get(other.id)[0] * size.get(other.id)[1],
                size.get(one.id)[0] * size.get(one.id)[1]));
        double x = left;
        double y = top;
        double shelf = 0;
        double maxX = left;
        double maxY = top;
        for (Term root : trees) {
            double[] box = size.get(root.id);
            if (x > left && x + box[0] > left + width) {
                x = left;
                y += shelf + APART;
                shelf = 0;
            }
            put(root, x, y, children, size, plan, width);
            x += box[0] + APART;
            shelf = Math.max(shelf, box[1]);
            maxX = Math.max(maxX, x - APART);
            maxY = Math.max(maxY, y + box[1]);
        }
        if (!loose.isEmpty()) {
            double[] grid = grid(loose, trees.isEmpty() ? left : left, trees.isEmpty() ? top
                    : maxY + APART, Math.max(width, widestOf(loose)), plan);
            maxX = Math.max(maxX, grid[0]);
            maxY = Math.max(maxY, grid[1]);
        }
        return new double[] {left, top, maxX, maxY};
    }

    /** More leaves than this under one class stand in a grid below it rather than in a row. */
    static final int ROW_OF_LEAVES = 6;

    /** Between a class and the row of its subclasses: room for the lines down. */
    static final double BELOW = 60;

    /** {width, height} of a term's subtree laid out top down. */
    private static double[] measure(Term term, Map<String, List<Term>> children,
            Map<String, double[]> size, double limit) {
        List<Term> below = children.get(term.id);
        double[] own;
        if (below == null || below.isEmpty()) {
            own = new double[] {term.w, term.h};
        } else if (allLeaves(below, children) && below.size() > ROW_OF_LEAVES) {
            int columns = columnsFor(below.size());
            double cellW = widestOf(below) + ACROSS / 2;
            double cellH = tallestOf(below) + DOWN;
            int rows = (below.size() + columns - 1) / columns;
            own = new double[] {Math.max(term.w, columns * cellW - ACROSS / 2),
                term.h + BELOW + rows * cellH - DOWN};
        } else {
            double w = 0;
            double h = 0;
            for (List<Term> row : rows(below, children, size, limit)) {
                double rowW = 0;
                double rowH = 0;
                for (Term child : row) {
                    double[] c = size.get(child.id);
                    rowW += c[0] + ACROSS;
                    rowH = Math.max(rowH, c[1]);
                }
                w = Math.max(w, rowW - ACROSS);
                h += rowH + BELOW;
            }
            own = new double[] {Math.max(term.w, w), term.h + h};
        }
        size.put(term.id, own);
        return own;
    }

    /** Places a measured subtree with its top-left at ({@code x}, {@code y}). */
    private static void put(Term term, double x, double y, Map<String, List<Term>> children,
            Map<String, double[]> size, Plan plan, double limit) {
        double[] box = size.get(term.id);
        plan.positions.put(term.id, new double[] {x + (box[0] - term.w) / 2, y});
        List<Term> below = children.get(term.id);
        if (below == null || below.isEmpty()) {
            return;
        }
        double rowTop = y + term.h + BELOW;
        if (allLeaves(below, children) && below.size() > ROW_OF_LEAVES) {
            int columns = columnsFor(below.size());
            double cellW = widestOf(below) + ACROSS / 2;
            double cellH = tallestOf(below) + DOWN;
            double gridW = columns * cellW - ACROSS / 2;
            double start = x + (box[0] - gridW) / 2;
            for (int at = 0; at < below.size(); at++) {
                plan.positions.put(below.get(at).id, new double[] {
                    start + (at % columns) * cellW, rowTop + (at / columns) * cellH});
            }
            return;
        }
        for (List<Term> row : rows(below, children, size, limit)) {
            double used = 0;
            double rowH = 0;
            for (Term child : row) {
                used += size.get(child.id)[0] + ACROSS;
                rowH = Math.max(rowH, size.get(child.id)[1]);
            }
            double cx = x + (box[0] - (used - ACROSS)) / 2;
            for (Term child : row) {
                put(child, cx, rowTop, children, size, plan, limit);
                cx += size.get(child.id)[0] + ACROSS;
            }
            rowTop += rowH + BELOW;
        }
    }

    /**
     * A class's children in rows: side by side while they fit in {@code limit}, the width of a shelf, then on
     * the next row down - so one class with twelve large subtrees is not twenty screens wide.
     */
    private static List<List<Term>> rows(List<Term> below, Map<String, List<Term>> children,
            Map<String, double[]> size, double limit) {
        List<List<Term>> rows = new ArrayList<List<Term>>();
        List<Term> row = new ArrayList<Term>();
        double w = 0;
        for (Term child : below) {
            double[] c = size.containsKey(child.id) ? size.get(child.id)
                    : measure(child, children, size, limit);
            if (!row.isEmpty() && w + c[0] > limit) {
                rows.add(row);
                row = new ArrayList<Term>();
                w = 0;
            }
            row.add(child);
            w += c[0] + ACROSS;
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    /** Terms laid out in rows no wider than {@code width}; returns {maxX, maxY}. */
    private static double[] grid(List<Term> terms, double left, double top, double width,
            Plan plan) {
        double x = left;
        double y = top;
        double row = 0;
        double maxX = left;
        double maxY = top;
        for (Term term : terms) {
            if (x > left && x + term.w > left + width) {
                x = left;
                y += row + DOWN;
                row = 0;
            }
            plan.positions.put(term.id, new double[] {x, y});
            x += term.w + ACROSS / 2;
            row = Math.max(row, term.h);
            maxX = Math.max(maxX, x - ACROSS / 2);
            maxY = Math.max(maxY, y + term.h);
        }
        return new double[] {maxX, maxY};
    }

    private static boolean allLeaves(List<Term> terms, Map<String, List<Term>> children) {
        for (Term term : terms) {
            if (children.containsKey(term.id)) {
                return false;
            }
        }
        return true;
    }

    /** About twice as wide as tall, for a grid of {@code n} leaves. */
    private static int columnsFor(int n) {
        return Math.max(ROW_OF_LEAVES, (int) Math.ceil(Math.sqrt(n * 2.0)));
    }

    private static double widestOf(List<Term> terms) {
        double widest = 0;
        for (Term term : terms) {
            widest = Math.max(widest, term.w);
        }
        return widest;
    }

    private static double tallestOf(List<Term> terms) {
        double tallest = 0;
        for (Term term : terms) {
            tallest = Math.max(tallest, term.h);
        }
        return tallest;
    }

    /**
     * The individuals, in a band below the classes: each class's individuals under that class,
     * in a column - or several, for many - starting at the class's left edge, never overlapping
     * the group before. What hangs off an individual, a value, stands right below it.
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
        Collections.sort(classes, (one, other) -> Double.compare(xOf(plan, one, left),
                xOf(plan, other, left)));
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
            for (Term term : group) {
                if (inColumn == perColumn) {
                    x += colW + ACROSS / 2;
                    y = top;
                    inColumn = 0;
                }
                plan.positions.put(term.id, new double[] {x, y});
                y += term.h + DOWN;
                List<Term> values = hanging.get(term.id);
                if (values != null) {
                    for (Term value : values) {
                        plan.positions.put(value.id, new double[] {x + 20, y});
                        y += value.h + DOWN;
                    }
                }
                bottom = Math.max(bottom, y);
                inColumn++;
            }
            cursor = x + colW + ACROSS;
        }
        return Math.max(cursor - ACROSS, left);
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
