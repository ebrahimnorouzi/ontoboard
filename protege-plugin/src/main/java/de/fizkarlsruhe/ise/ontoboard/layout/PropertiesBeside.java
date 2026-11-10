package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A property stands beside the one class its domain names (1.135.1).
 *
 * <p>Found by the maintainer on the pizza ontologies: "the properties that are not connected,
 * and they just stray in the canvas". Every property stood in a section of its own - on
 * pizza-bfo about 1,800 px below pizza - joined to nothing until it was clicked, because its
 * domain line waited for a selection as its range does. Asked how a property should look
 * connected, the maintainer chose: beside its domain class, inside that class's frame, joined
 * to it by a short line that is always drawn.
 *
 * <p>Which properties is decided here, once - see {@link #classes} - from the domain lines a
 * board draws or will draw, so that a pattern's frame, planned from the ontology before the board
 * is drawn, and the Outline, read off the drawn board, put a property in the same place.
 *
 * <p>Pure geometry and ids: no Swing, no JGraphX, no OWL API.
 */
public final class PropertiesBeside {

    /**
     * Between a class's box and the properties beside it: room for the domain line, its
     * arrowhead and its label, "domain", side by side. Measured with the board's router on a
     * class with one to five properties beside it: at 40 the label had no room on the straight
     * line and the line went up over the class to find some; at 60 the label lay on the
     * arrowhead; at 80 each line and its label are clear of everything. On the 20 px grid.
     */
    public static final double GAP = 80;

    /** Between one property beside a class and the next, as between two individuals. */
    static final double DOWN = BoardSections.DOWN;

    /**
     * How far from its place beside its class a property may stand and still stand beside it:
     * one step of the board's 20 px grid, as a nudge by hand leaves it.
     */
    public static final double NEAR = 20;

    /** How {@code OntologyProjection} begins the id of the line from a property to its domain. */
    private static final String DOMAIN_LINE = "domain|";

    private PropertiesBeside() {
    }

    /**
     * Whether a line is a property's domain line, by its id: a domain and a range are drawn alike,
     * in one style, from the property's box, and only the id the projection writes - "domain|",
     * the property, the class - tells them apart, as {@code AxiomOnBoard} reads it too. A union's
     * lines to its classes, "domain, one of", begin "domain-or|".
     */
    public static boolean isDomainLine(String id) {
        return id != null && id.startsWith(DOMAIN_LINE);
    }

    /**
     * Whether a property's box stands beside a class's box as {@link #stack} puts it there: its
     * left edge {@link #GAP} right of the class's right edge, its top no higher than the class's
     * and no lower than the class's properties reach, one under the other with what hangs off
     * each - a property and two rows under it, at most - give or take {@link #NEAR} (1.135.1).
     *
     * <p>Found by a review: whether the two boxes stood in one frame was all that was asked, and
     * on a board with no frames - arranged by Hierarchical, Organic, Circle or Grid, or saved
     * before there were sections - every property whose domain names one class counted as beside
     * it wherever it stood, its domain line drawn across the board for good.
     *
     * @param property the property's box, {x, y, w, h}
     * @param cls the class's box, {x, y, w, h}
     * @param heights the height of each property that stands beside the class by {@link #classes}
     */
    public static boolean standsBeside(double[] property, double[] cls,
            Collection<Double> heights) {
        double reach = 0;
        for (double h : heights) {
            reach += 3 * (h + DOWN);
        }
        return Math.abs(property[0] - (cls[0] + cls[2] + GAP)) <= NEAR
                && property[1] >= cls[1] - NEAR && property[1] <= cls[1] + reach + NEAR;
    }

    /**
     * Each property whose domain lines reach exactly one class, with that class: the class it
     * stands beside.
     *
     * <p>A domain line runs from a property to a class one of its asserted domain axioms names,
     * in the imports closure, while that class is on the board and is not owl:Thing. So a
     * property with no domain, with owl:Thing, a class expression or a class off the board, has
     * none and stays in its section; one whose domains name two classes on the board has two, and
     * stays there too. A union's lines to its classes and a line to an expression's node are not
     * lines to a class the domain names - the domain there is the expression - whether class
     * expressions are drawn or not. A super-property's domain draws no line from a sub-property.
     *
     * <p>Where a property's super-property stands does not move it: it stands beside its own
     * class, and the is-a line between two properties that stand apart waits for a selection, as
     * a range does - see {@code SchemaGraph}.
     *
     * @param domainLines each domain line from a property to a class: {property, class}
     * @return the class each property stands beside, by property, in id order
     */
    public static Map<String, String> classes(Collection<String[]> domainLines) {
        Map<String, Set<String>> domains = new TreeMap<String, Set<String>>();
        for (String[] line : domainLines) {
            Set<String> classes = domains.get(line[0]);
            if (classes == null) {
                classes = new TreeSet<String>();
                domains.put(line[0], classes);
            }
            classes.add(line[1]);
        }
        Map<String, String> beside = new TreeMap<String, String>();
        for (Map.Entry<String, Set<String>> property : domains.entrySet()) {
            if (property.getValue().size() == 1) {
                beside.put(property.getKey(), property.getValue().iterator().next());
            }
        }
        return beside;
    }

    /**
     * One property beside a class, and what hangs off it - its datatype, a range's expression -
     * which stands under it as it does in the property's section.
     */
    public static final class Item {
        final String id;
        final BoardSections.Kind kind;
        final String label;
        final List<HierarchyColumns.Term> boxes = new ArrayList<HierarchyColumns.Term>();
        final Map<String, String> parents = new HashMap<String, String>();
        /** Its super-properties, by id: one beside the same class stands above it. */
        final Set<String> supers = new TreeSet<String>();

        public Item(String id, BoardSections.Kind kind, String label, double w, double h) {
            this.id = id;
            this.kind = kind;
            this.label = label == null ? "" : label;
            boxes.add(new HierarchyColumns.Term(id, this.label, w, h));
        }

        /** What hangs off the property, under {@code parent}: it or what hangs off it. */
        public Item with(String id, String label, double w, double h, String parent) {
            boxes.add(new HierarchyColumns.Term(id, label, w, h));
            parents.put(id, parent);
            return this;
        }

        public String getId() {
            return id;
        }

        /** The property's id, then what hangs off it. */
        public List<String> getIds() {
            List<String> ids = new ArrayList<String>();
            for (HierarchyColumns.Term box : boxes) {
                ids.add(box.id);
            }
            return ids;
        }

        /** The size of one of its boxes, {w, h}; null for one it does not have. */
        double[] sizeOf(String box) {
            for (HierarchyColumns.Term term : boxes) {
                if (term.id.equals(box)) {
                    return new double[] {term.w, term.h};
                }
            }
            return null;
        }
    }

    /**
     * Where a class's properties stand beside it, each box's top-left from the class's: right of
     * the class's box by {@link #GAP}, the first level with its top, one under the other
     * {@link #DOWN} apart in the order the board gives siblings - object properties, then data
     * properties, each by label - and what hangs off each under it, as in its section. A
     * sub-property stands under its super-property when both stand beside the class: see
     * {@link #inOrder}.
     *
     * <p>The class and its properties are one block for whoever places the class: {@code size}
     * gets its width - the class's box, the gap and the widest property with what hangs off it -
     * and its height, the taller of the class and its properties. Placed as one, the class's
     * subclasses stand below its properties, and its siblings and the next column keep clear.
     */
    public static Map<String, double[]> stack(double w, double h, List<Item> items,
            double[] size) {
        List<Item> ordered = inOrder(items);
        Map<String, double[]> at = new LinkedHashMap<String, double[]>();
        double x = w + GAP;
        double y = 0;
        double widest = 0;
        for (Item item : ordered) {
            HierarchyColumns.Placed tree = HierarchyColumns.place(item.boxes, item.parents, 0, 0);
            for (Map.Entry<String, double[]> box : tree.positions.entrySet()) {
                at.put(box.getKey(), new double[] {x + box.getValue()[0], y + box.getValue()[1]});
            }
            widest = Math.max(widest, tree.right);
            y += tree.bottom + DOWN;
        }
        size[0] = ordered.isEmpty() ? w : w + GAP + widest;
        size[1] = ordered.isEmpty() ? h : Math.max(h, y - DOWN);
        return at;
    }

    /**
     * The board's order of siblings - see {@link #ORDER} - but each property after its
     * super-properties that stand beside the same class, right under the last of them: the
     * hierarchy reads top down, as in a property's section.
     *
     * <p>Found on pizza-bfo arranged by the beside build of 1.135.1: by label "inheres in" stood
     * above "specifically depends on", its super-property, both beside specifically dependent
     * continuant, and the is-a line between them pointed down - the one is-a line on the board
     * whose parent stood below its child. Properties each other's sub-properties - equivalent -
     * have no first, and the board's order puts one of them first.
     */
    private static List<Item> inOrder(List<Item> items) {
        List<Item> sorted = new ArrayList<Item>(items);
        Collections.sort(sorted, ORDER);
        Set<String> here = new HashSet<String>();
        for (Item item : sorted) {
            here.add(item.id);
        }
        List<Item> ordered = new ArrayList<Item>();
        Set<String> placed = new HashSet<String>();
        while (ordered.size() < sorted.size()) {
            int before = ordered.size();
            for (Item item : sorted) {
                putNext(item, false, sorted, here, placed, ordered);
            }
            if (ordered.size() == before) {
                for (Item item : sorted) {
                    if (!placed.contains(item.id)) {
                        putNext(item, true, sorted, here, placed, ordered);
                        break;
                    }
                }
            }
        }
        return ordered;
    }

    /**
     * Puts {@code item} next once every super-property of it standing here is placed - or
     * {@code anyway}, to break a cycle - and then, under it, what has it as a super-property.
     */
    private static void putNext(Item item, boolean anyway, List<Item> sorted, Set<String> here,
            Set<String> placed, List<Item> ordered) {
        if (placed.contains(item.id)) {
            return;
        }
        for (String up : item.supers) {
            if (!anyway && here.contains(up) && !up.equals(item.id) && !placed.contains(up)) {
                return;
            }
        }
        placed.add(item.id);
        ordered.add(item);
        for (Item next : sorted) {
            if (next.supers.contains(item.id)) {
                putNext(next, false, sorted, here, placed, ordered);
            }
        }
    }

    /** The board's order of siblings: by kind, then by label - and by id, so it is total. */
    private static final Comparator<Item> ORDER = new Comparator<Item>() {
        @Override
        public int compare(Item one, Item other) {
            if (one.kind != other.kind) {
                return one.kind.compareTo(other.kind);
            }
            int byLabel = one.label.toLowerCase(Locale.ROOT).compareTo(
                    other.label.toLowerCase(Locale.ROOT));
            return byLabel != 0 ? byLabel : one.id.compareTo(other.id);
        }
    };
}
