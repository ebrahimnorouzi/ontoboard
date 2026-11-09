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
            double[] box = forest(members, parent, x + APART / 2, y + TITLE, plan);
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
