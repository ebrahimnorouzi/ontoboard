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
 * A hierarchy drawn the way it is read: a class, and its subclasses below it, one under the other,
 * indented - in more columns when there are more of them than a column holds.
 *
 * <p>Asked for: "clear hierarchy, subclasses below each other in multiple columns if there are
 * more". A pattern's classes used to arrive in a grid in IRI order, so a parent could stand anywhere
 * relative to its subclasses and every is-a line crossed the block; the outline put siblings side by
 * side, so one class with a dozen subclasses was a row wider than the screen. Here nothing but the
 * hierarchy decides where a box goes, no two boxes can overlap, and the lines between a class and its
 * subclasses have a lane of their own to the left of them.
 *
 * <p>Pure geometry: no Swing, no JGraphX, no OWL API.
 */
public final class HierarchyColumns {

    /** How far subclasses stand to the right of their class: room for the line down to them. */
    public static final double INDENT = 40;

    /** Between one subclass and the next. On the board's 20px grid, as the boxes' heights are. */
    public static final double SIBLINGS = 20;

    /** Between a class and its first subclass: room for the arrowhead and the bus to its columns. */
    public static final double UNDER = 40;

    /** Between two columns: room for property arrows and their labels. */
    public static final double COLUMNS = 120;

    /** How tall a column of subclasses grows before the next one starts. */
    public static final double COLUMN_HEIGHT = 520;

    /** How wide a block of trees is, for how tall: a screen's shape rather than a tower. */
    private static final double ASPECT = 1.8;

    /** A term to place: its id, its label (the order of siblings) and its size. */
    public static final class Term {
        final String id;
        final String label;
        final double w;
        final double h;

        public Term(String id, String label, double w, double h) {
            this.id = id;
            this.label = label == null ? "" : label;
            this.w = w;
            this.h = h;
        }
    }

    /** Where each term's top-left goes, and how far right and down the whole thing reaches. */
    public static final class Placed {
        public final Map<String, double[]> positions = new LinkedHashMap<String, double[]>();
        public double right;
        public double bottom;
    }

    private static final class Block {
        final Term term;
        final List<List<Block>> columns = new ArrayList<List<Block>>();
        double w;
        double h;

        Block(Term term) {
            this.term = term;
        }
    }

    private HierarchyColumns() {
    }

    /**
     * Every term placed, the top-left of the block at ({@code left}, {@code top}).
     *
     * @param parent each term's parent in the tree, by id; one that is not among {@code terms} makes
     *     the term a root. A cycle cannot lose a term: what no root reaches stands on its own.
     */
    public static Placed place(List<Term> terms, Map<String, String> parent, double left,
            double top) {
        Map<String, Term> byId = new LinkedHashMap<String, Term>();
        for (Term term : terms) {
            byId.put(term.id, term);
        }
        Map<String, List<Term>> children = new HashMap<String, List<Term>>();
        List<Term> roots = new ArrayList<Term>();
        for (Term term : byId.values()) {
            String up = parent == null ? null : parent.get(term.id);
            if (up != null && byId.containsKey(up) && !up.equals(term.id)) {
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
            Collections.sort(siblings, BY_LABEL);
        }
        Set<String> reached = new HashSet<String>();
        // In label order, trees and single terms alike. Big trees used to come first: one new
        // subclass could make a tree bigger than the one before it, or turn a single term into a
        // tree, and the two swapped places - a tester saw a class jump 1,640 px when a subclass
        // was added to it, and another the whole frame rearranged by the guide's first step.
        List<Block> all = new ArrayList<Block>();
        Collections.sort(roots, BY_LABEL);
        for (Term root : roots) {
            all.add(block(root, children, reached));
        }
        // Only a cycle reaches here: its members still have to be on the board.
        for (Term term : byId.values()) {
            if (!reached.contains(term.id)) {
                all.add(block(term, new HashMap<String, List<Term>>(), reached));
            }
        }

        double area = 0;
        double tallest = 0;
        for (Block block : all) {
            area += (block.w + COLUMNS) * (block.h + SIBLINGS);
            tallest = Math.max(tallest, block.h);
        }
        // As tall as the tallest tree, rounded up to half a column: exactly as tall, the limit
        // grew with each subclass added to that tree, and every other tree moved with it.
        double step = COLUMN_HEIGHT / 2;
        double limit = Math.max(Math.max(Math.ceil(tallest / step) * step, 300), inSteps(area));
        Placed placed = new Placed();
        placed.right = left;
        placed.bottom = top;
        double x = left;
        for (List<Block> column : balanced(all, limit)) {
            double y = top;
            double width = 0;
            for (Block block : column) {
                put(block, x, y, placed);
                y += block.h + SIBLINGS;
                width = Math.max(width, block.w);
            }
            placed.right = Math.max(placed.right, x + width);
            placed.bottom = Math.max(placed.bottom, y - SIBLINGS);
            x += width + COLUMNS;
        }
        return placed;
    }

    private static Block block(Term term, Map<String, List<Term>> children, Set<String> reached) {
        Block block = new Block(term);
        reached.add(term.id);
        List<Block> under = new ArrayList<Block>();
        for (Term child : children.getOrDefault(term.id, Collections.<Term>emptyList())) {
            if (!reached.contains(child.id)) {
                under.add(block(child, children, reached));
            }
        }
        block.w = term.w;
        block.h = term.h;
        if (!under.isEmpty()) {
            // Tall enough for a screen's shape: twelve subtrees of thirty classes each, one column
            // to a subtree, came out 17,000px wide and 700 tall.
            double area = 0;
            for (Block one : under) {
                area += (one.w + COLUMNS) * (one.h + SIBLINGS);
            }
            block.columns.addAll(filled(under, Math.max(COLUMN_HEIGHT, inSteps(area))));
            double across = -COLUMNS;
            double down = 0;
            for (List<Block> column : block.columns) {
                double width = 0;
                double height = -SIBLINGS;
                for (Block one : column) {
                    width = Math.max(width, one.w);
                    height += one.h + SIBLINGS;
                }
                across += width + COLUMNS;
                down = Math.max(down, height);
            }
            block.w = Math.max(term.w, INDENT + across);
            block.h = term.h + UNDER + down;
        }
        return block;
    }

    /**
     * The trees side by side: in as few columns as hold them under {@code limit}, about as tall as
     * each other. Kept in order, so reading down one column and on to the next is reading the list.
     * Measured on every pattern in the library: columns filled one at a time ran lines together
     * on two of them, where balanced columns did not.
     */
    private static List<List<Block>> balanced(List<Block> blocks, double limit) {
        double total = -SIBLINGS;
        double tallest = 0;
        for (Block block : blocks) {
            total += block.h + SIBLINGS;
            tallest = Math.max(tallest, block.h);
        }
        double most = Math.max(limit, tallest);
        int columns = (int) Math.ceil(total / most);
        return filled(blocks, columns <= 1 ? most
                : Math.max(tallest, Math.min(most, total / columns + 1)));
    }

    /**
     * A class's subclasses in columns no taller than {@code limit}, each filled before the next
     * starts; a block taller than that stands in a column of its own. Kept in order, so reading
     * down one column and on to the next is reading the list.
     *
     * <p>Filled, not balanced. Columns of equal height moved where every column broke each time a
     * subclass was added: testers saw subclasses switch between two and three columns with each
     * New subclass, the frame rearranged under the reader. Filled, a subclass added last moves
     * nothing, and one added in between moves only those after it, by one place.
     */
    private static List<List<Block>> filled(List<Block> blocks, double limit) {
        List<List<Block>> flowed = new ArrayList<List<Block>>();
        List<Block> column = new ArrayList<Block>();
        double used = 0;
        for (Block block : blocks) {
            if (!column.isEmpty() && used + block.h > limit) {
                flowed.add(column);
                column = new ArrayList<Block>();
                used = 0;
            }
            column.add(block);
            used += block.h + SIBLINGS;
        }
        if (!column.isEmpty()) {
            flowed.add(column);
        }
        return flowed;
    }

    /**
     * The column height that gives blocks of this area a screen's shape, rounded down to half a
     * column: a board grows a box at a time, and the height moving with each box moved where the
     * columns broke.
     */
    private static double inSteps(double area) {
        double step = COLUMN_HEIGHT / 2;
        return Math.floor(Math.sqrt(area / ASPECT) / step) * step;
    }

    private static void put(Block block, double x, double y, Placed placed) {
        placed.positions.put(block.term.id, new double[] {x, y});
        placed.right = Math.max(placed.right, x + block.term.w);
        placed.bottom = Math.max(placed.bottom, y + block.term.h);
        double cx = x + INDENT;
        double top = y + block.term.h + UNDER;
        for (List<Block> column : block.columns) {
            double cy = top;
            double width = 0;
            for (Block one : column) {
                put(one, cx, cy, placed);
                cy += one.h + SIBLINGS;
                width = Math.max(width, one.w);
            }
            cx += width + COLUMNS;
        }
    }

    private static final Comparator<Term> BY_LABEL = new Comparator<Term>() {
        @Override
        public int compare(Term one, Term other) {
            return one.label.toLowerCase(Locale.ROOT).compareTo(
                    other.label.toLowerCase(Locale.ROOT));
        }
    };
}
