package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Putting a group of newly arrived terms on the board together, inside a frame that names them.
 *
 * <p>Asked for: "imported patterns from library of patterns should be shown in the canvas within
 * the frame everything with what is connected together inside the frame and the frame is named
 * the pattern".
 *
 * <p>Without this, importing a pattern put its terms wherever {@code SchemaGraph} puts a node it
 * has no position for - a row along the top of the board, mixed in with whatever was already
 * there, with nothing to say that these eight classes arrived together and mean something as a
 * set. The pattern is the unit the user chose; it should still be a unit once it lands.
 *
 * <p><b>Pure.</b> It is handed the existing layout and gives back positions; it does not touch
 * the canvas, Swing or an ontology. That is what makes the two decisions in it testable: where
 * the group goes, and what happens when it will not fit.
 */
public final class FramedGroup {

    private FramedGroup() {
    }

    /** Node size, matching {@link CanvasLayout.NodeLayout}'s own defaults. */
    static final double NODE_W = 160;
    static final double NODE_H = 60;

    /**
     * Space between nodes, and between the outermost node and the frame's edge.
     *
     * <p><b>40, because that is what the rest of the board uses.</b> Reported as "so compact and
     * lots of concepts are packed", and measuring it showed the imported frame was the only
     * multi-node placement on the canvas that was tighter than every other one. {@code
     * CanvasLayouts} uses {@code GRID_GAP = 40}, {@code LOOSE_PITCH_X = 200} with {@code
     * LOOSE_PITCH_Y = 100}, {@code NODE_PITCH = 200} and {@code RING_GAP = 200} - all of which are
     * a 160x60 node with 40 around it. The grid layout, the unconnected-terms block, the circle,
     * the expand-neighbours rings and a drag from Protégé's tree therefore all land on a 200x100
     * stride. This was 28, giving 188x88: 30% less clear space, and the only stride off the
     * canvas's 20px snap grid, so a pattern's nodes lined up with nothing else on the board and
     * jumped the first time anybody dragged one.
     *
     * <p>It also matters for text. A quarter of the labels in the shipped patterns do not fit on
     * one line inside a 160px node, and 82 of 2695 terms paint taller than the 60px box, spilling
     * up to 17.6px above and below it. Two such nodes 28 apart <em>overlap</em> by 7.2px; 40 apart
     * they clear. That the complaint was about crowding rather than about clipping is what this
     * number fixes - the labels not fitting their box is a separate, board-wide thing, recorded in
     * the limitations rather than worked around here by making pattern nodes a different size from
     * every other node.
     */
    static final double GAP = 40;
    public static final double PADDING = 40;

    /** Room at the top of the frame for its label, so the first row does not sit under it. */
    public static final double LABEL_BAND = 40;

    /** Clear of whatever is already on the board. */
    static final double MARGIN = 60;

    /** Where everything goes. */
    public static final class Placement {
        private final CanvasLayout.FrameLayout frame;
        private final Map<String, CanvasLayout.NodeLayout> nodes;
        private final List<String> alreadyOnTheBoard;

        Placement(CanvasLayout.FrameLayout frame, Map<String, CanvasLayout.NodeLayout> nodes,
                List<String> alreadyOnTheBoard) {
            this.frame = frame;
            this.nodes = Collections.unmodifiableMap(nodes);
            this.alreadyOnTheBoard = Collections.unmodifiableList(alreadyOnTheBoard);
        }

        /** The frame to add, labelled with the pattern's name. */
        public CanvasLayout.FrameLayout getFrame() {
            return frame;
        }

        /** Positions for the terms, by IRI, all of them inside the frame. */
        public Map<String, CanvasLayout.NodeLayout> getNodes() {
            return nodes;
        }

        /**
         * Terms the board already had a position for, which were left where they were.
         *
         * <p>Reported rather than silently moved. Somebody who has already arranged a term is
         * telling you where they want it, and a pattern import is not a reason to overrule that -
         * but it does mean the frame does not contain the whole pattern, which the caller should
         * be able to say.
         */
        public List<String> getAlreadyOnTheBoard() {
            return alreadyOnTheBoard;
        }

        public boolean isEmpty() {
            return nodes.isEmpty();
        }
    }

    /**
     * Lays the terms out in a block and wraps a frame round them.
     *
     * <p>A grid, roughly square, because the alternative - a hierarchy drawn from the axioms - is
     * a layout algorithm, and the board already has six of those on the toolbar. The point here is
     * that the group arrives <em>together</em> and <em>named</em>; arranging it is the user's,
     * and {@code Arrange} on a frame's contents does it better than this ever would.
     *
     * <p>Terms the board already positions are left alone and reported. Re-placing them would
     * drag somebody's existing diagram into a new box because they imported a pattern that
     * happens to mention one of its classes.
     *
     * @param label what the frame is called - the pattern's name
     * @param iris the terms that arrived, in the order they should be laid out
     * @param existing the board as it is now; not modified
     */
    public static Placement plan(String label, List<String> iris, CanvasLayout existing) {
        return plan(label, iris, existing, Collections.<String, double[]>emptyMap());
    }

    /**
     * The same, each box as big as {@code sizes} says - {w, h} by IRI, the size its label fits
     * in - and {@link #NODE_W} by {@link #NODE_H} for any it does not mention. Columns are as
     * wide as their widest box: a fixed 160px put {@code classification:Concept} on two lines,
     * broken inside the word.
     */
    public static Placement plan(String label, List<String> iris, CanvasLayout existing,
            Map<String, double[]> sizes) {
        return plan(label, iris, existing, sizes, Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap());
    }

    /**
     * The same, drawn as the hierarchy it is: each class above its subclasses, by {@code parents} -
     * a term's parent among them, by IRI - rather than in a grid. See {@link HierarchyColumns}. A
     * group in which nothing is the parent of anything has no hierarchy to draw, and is a grid.
     *
     * @param names what each box says, which decides the order of siblings
     */
    public static Placement plan(String label, List<String> iris, CanvasLayout existing,
            Map<String, double[]> sizes, Map<String, String> parents, Map<String, String> names) {
        Map<String, CanvasLayout.NodeLayout> placed =
                new LinkedHashMap<String, CanvasLayout.NodeLayout>();
        List<String> already = new ArrayList<String>();
        List<String> fresh = new ArrayList<String>();
        for (String iri : iris == null ? Collections.<String>emptyList() : iris) {
            if (iri == null || iri.trim().isEmpty()) {
                continue;
            }
            if (existing != null && existing.nodes.containsKey(iri)) {
                already.add(iri);
            } else if (!fresh.contains(iri)) {
                fresh.add(iri);
            }
        }
        if (fresh.isEmpty()) {
            return new Placement(null, placed, already);
        }
        if (anyIsAParent(fresh, parents)) {
            return asHierarchy(label, fresh, already, existing, sizes, parents, names);
        }

        int columns = columnsFor(fresh.size());
        int rows = (fresh.size() + columns - 1) / columns;
        double[] columnW = new double[columns];
        double[] rowH = new double[rows];
        for (int at = 0; at < fresh.size(); at++) {
            double[] size = sizes.get(fresh.get(at));
            // Rounded up to the 20px snap grid, so every box still starts on it.
            columnW[at % columns] = Math.max(columnW[at % columns],
                    Math.ceil((size == null ? NODE_W : size[0]) / 20) * 20);
            rowH[at / columns] = Math.max(rowH[at / columns],
                    Math.ceil((size == null ? NODE_H : size[1]) / 20) * 20);
        }
        double innerW = (columns - 1) * GAP;
        for (double w : columnW) {
            innerW += w;
        }
        double innerH = (rows - 1) * GAP;
        for (double h : rowH) {
            innerH += h;
        }

        double[] origin = freeSpotFor(existing, innerW + 2 * PADDING,
                innerH + 2 * PADDING + LABEL_BAND);

        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.label = label == null || label.trim().isEmpty() ? "Imported pattern" : label.trim();
        frame.x = origin[0];
        frame.y = origin[1];
        frame.w = innerW + 2 * PADDING;
        frame.h = innerH + 2 * PADDING + LABEL_BAND;

        for (int at = 0; at < fresh.size(); at++) {
            int column = at % columns;
            int row = at / columns;
            double x = frame.x + PADDING;
            for (int before = 0; before < column; before++) {
                x += columnW[before] + GAP;
            }
            double y = frame.y + PADDING + LABEL_BAND;
            for (int before = 0; before < row; before++) {
                y += rowH[before] + GAP;
            }
            double[] size = sizes.get(fresh.get(at));
            CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
            node.w = size == null ? NODE_W : size[0];
            node.h = size == null ? NODE_H : size[1];
            placed.put(fresh.get(at), node);
        }
        return new Placement(frame, placed, already);
    }

    private static boolean anyIsAParent(List<String> terms, Map<String, String> parents) {
        for (String term : terms) {
            String parent = parents.get(term);
            if (parent != null && !parent.equals(term) && terms.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    private static Placement asHierarchy(String label, List<String> fresh, List<String> already,
            CanvasLayout existing, Map<String, double[]> sizes, Map<String, String> parents,
            Map<String, String> names) {
        List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
        Map<String, double[]> size = new LinkedHashMap<String, double[]>();
        for (String iri : fresh) {
            double[] fitted = sizes.get(iri);
            double[] wh = {fitted == null ? NODE_W : fitted[0], fitted == null ? NODE_H : fitted[1]};
            size.put(iri, wh);
            terms.add(new HierarchyColumns.Term(iri, names.containsKey(iri) ? names.get(iri) : iri,
                    wh[0], wh[1]));
        }
        HierarchyColumns.Placed inside = HierarchyColumns.place(terms, parents, 0, 0);
        double innerW = inside.right;
        double innerH = inside.bottom;
        double[] origin = freeSpotFor(existing, innerW + 2 * PADDING,
                innerH + 2 * PADDING + LABEL_BAND);
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.label = label == null || label.trim().isEmpty() ? "Imported pattern" : label.trim();
        frame.x = origin[0];
        frame.y = origin[1];
        frame.w = innerW + 2 * PADDING;
        frame.h = innerH + 2 * PADDING + LABEL_BAND;
        Map<String, CanvasLayout.NodeLayout> placed =
                new LinkedHashMap<String, CanvasLayout.NodeLayout>();
        for (String iri : fresh) {
            double[] at = inside.positions.get(iri);
            CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(
                    frame.x + PADDING + at[0], frame.y + PADDING + LABEL_BAND + at[1]);
            node.w = size.get(iri)[0];
            node.h = size.get(iri)[1];
            placed.put(iri, node);
        }
        return new Placement(frame, placed, already);
    }

    /**
     * Frames planned in one row, folded into rows about as wide as a board of their size would be,
     * each frame taking its terms along. Order is kept: left to right, then the next row down.
     *
     * <p>Nineteen patterns recognised in one ontology made a row fifteen thousand pixels wide over
     * an outline seven thousand wide. The property sections, placed right of the outline, then
     * stood under the row's far half, and 141 of 982 lines between classes crossed them.
     *
     * @return the width the rows were folded at
     */
    public static double wrap(List<Placement> placements) {
        double area = 0;
        double widest = 0;
        for (Placement placement : placements) {
            CanvasLayout.FrameLayout frame = placement.getFrame();
            if (frame != null) {
                area += frame.w * frame.h;
                widest = Math.max(widest, frame.w);
            }
        }
        double width = Math.max(widest, Math.max(1600, Math.sqrt(area * 1.6) * 1.2));
        double x = MARGIN;
        double y = MARGIN;
        double rowHeight = 0;
        for (Placement placement : placements) {
            CanvasLayout.FrameLayout frame = placement.getFrame();
            if (frame == null) {
                continue;
            }
            if (x > MARGIN && x + frame.w > MARGIN + width) {
                x = MARGIN;
                y += rowHeight + MARGIN;
                rowHeight = 0;
            }
            double dx = x - frame.x;
            double dy = y - frame.y;
            frame.x += dx;
            frame.y += dy;
            for (CanvasLayout.NodeLayout node : placement.getNodes().values()) {
                node.x += dx;
                node.y += dy;
            }
            x += frame.w + MARGIN;
            rowHeight = Math.max(rowHeight, frame.h);
        }
        return width;
    }

    /**
     * The same, keeping the rows a board already has: each frame named in {@code rows} - by label,
     * its row's index - goes back in its row, in the order given; a frame not named joins the last
     * row while it fits, and starts a new one when it does not. A row is as tall as its tallest
     * frame, so a frame that grew pushes the rows under it down and its neighbours along, and no
     * frame changes row because another one grew.
     *
     * @return the width new rows were folded at
     */
    public static double wrap(List<Placement> placements, Map<String, Integer> rows) {
        if (rows == null || rows.isEmpty()) {
            return wrap(placements);
        }
        double area = 0;
        double widest = 0;
        for (Placement placement : placements) {
            CanvasLayout.FrameLayout frame = placement.getFrame();
            if (frame != null) {
                area += frame.w * frame.h;
                widest = Math.max(widest, frame.w);
            }
        }
        double width = Math.max(widest, Math.max(1600, Math.sqrt(area * 1.6) * 1.2));
        java.util.TreeMap<Integer, List<Placement>> byRow =
                new java.util.TreeMap<Integer, List<Placement>>();
        List<Placement> joining = new ArrayList<Placement>();
        for (Placement placement : placements) {
            CanvasLayout.FrameLayout frame = placement.getFrame();
            if (frame == null) {
                continue;
            }
            Integer row = rows.get(frame.label);
            if (row == null) {
                joining.add(placement);
                continue;
            }
            List<Placement> inRow = byRow.get(row);
            if (inRow == null) {
                inRow = new ArrayList<Placement>();
                byRow.put(row, inRow);
            }
            inRow.add(placement);
        }
        List<List<Placement>> ordered = new ArrayList<List<Placement>>(byRow.values());
        if (ordered.isEmpty()) {
            ordered.add(new ArrayList<Placement>());
        }
        for (Placement placement : joining) {
            List<Placement> last = ordered.get(ordered.size() - 1);
            double used = 0;
            for (Placement already : last) {
                used += already.getFrame().w + MARGIN;
            }
            if (!last.isEmpty() && used + placement.getFrame().w > width) {
                last = new ArrayList<Placement>();
                ordered.add(last);
            }
            last.add(placement);
        }
        double y = MARGIN;
        for (List<Placement> row : ordered) {
            double x = MARGIN;
            double rowHeight = 0;
            for (Placement placement : row) {
                CanvasLayout.FrameLayout frame = placement.getFrame();
                double dx = x - frame.x;
                double dy = y - frame.y;
                frame.x += dx;
                frame.y += dy;
                for (CanvasLayout.NodeLayout node : placement.getNodes().values()) {
                    node.x += dx;
                    node.y += dy;
                }
                x += frame.w + MARGIN;
                rowHeight = Math.max(rowHeight, frame.h);
            }
            y += rowHeight + (row.isEmpty() ? 0 : MARGIN);
        }
        return width;
    }

    /**
     * How many columns a group of this size gets.
     *
     * <p>Roughly square, capped at five. A pattern of twenty-six terms in one row is a frame
     * four thousand pixels wide that nobody can see the ends of; five columns keeps the widest
     * frame under a thousand, which fits a canvas on a laptop.
     */
    static int columnsFor(int count) {
        if (count <= 1) {
            return 1;
        }
        int square = (int) Math.ceil(Math.sqrt(count));
        return Math.min(5, Math.max(2, square));
    }

    /**
     * Somewhere the frame does not land on top of what is already there.
     *
     * <p>To the right of everything, on the same top edge - the reading order of the board. Down
     * below instead would put a pattern underneath a diagram somebody is working on, where they
     * have to scroll to discover it arrived at all.
     *
     * <p>An empty board gets the origin plus a margin rather than (0,0), because a node flush
     * against the corner has no room for its own border and reads as clipped.
     */
    static double[] freeSpotFor(CanvasLayout existing, double width, double height) {
        double rightmost = Double.NEGATIVE_INFINITY;
        double topmost = Double.POSITIVE_INFINITY;
        if (existing != null) {
            for (CanvasLayout.NodeLayout node : existing.nodes.values()) {
                if (node == null) {
                    continue;
                }
                rightmost = Math.max(rightmost, node.x + node.w);
                topmost = Math.min(topmost, node.y);
            }
            for (CanvasLayout.FrameLayout frame : existing.frames) {
                if (frame == null) {
                    continue;
                }
                rightmost = Math.max(rightmost, frame.x + frame.w);
                topmost = Math.min(topmost, frame.y);
            }
        }
        if (rightmost == Double.NEGATIVE_INFINITY) {
            return new double[] {MARGIN, MARGIN};
        }
        return new double[] {rightmost + MARGIN, Math.max(MARGIN, topmost)};
    }

    /**
     * A frame id, in the form the canvas recognises.
     *
     * <p>Taken as a parameter rather than generated here so that this class stays pure and the
     * view keeps its one way of minting ids.
     */
    public static CanvasLayout.FrameLayout withId(CanvasLayout.FrameLayout frame, String id) {
        if (frame != null) {
            frame.id = id;
        }
        return frame;
    }
}
