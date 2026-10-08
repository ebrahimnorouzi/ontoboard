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

    /** Space between nodes, and between the outermost node and the frame's edge. */
    static final double GAP = 28;
    static final double PADDING = 34;

    /** Room at the top of the frame for its label, so the first row does not sit under it. */
    static final double LABEL_BAND = 30;

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

        int columns = columnsFor(fresh.size());
        int rows = (fresh.size() + columns - 1) / columns;
        double innerW = columns * NODE_W + (columns - 1) * GAP;
        double innerH = rows * NODE_H + (rows - 1) * GAP;

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
            CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(
                    frame.x + PADDING + column * (NODE_W + GAP),
                    frame.y + PADDING + LABEL_BAND + row * (NODE_H + GAP));
            node.w = NODE_W;
            node.h = NODE_H;
            placed.put(fresh.get(at), node);
        }
        return new Placement(frame, placed, already);
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
