package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Where a node being dragged lines up with the nodes already placed.
 *
 * <p>A board with a grid still gives no feedback about alignment, because the grid is a texture
 * and the question is not "is this on a grid point" but "is this edge the same as that edge".
 * Without an answer the only way to align two terms is to zoom in, read coordinates off nothing,
 * and compare by eye - so diagrams drift by a few pixels everywhere and look careless in a paper.
 *
 * <p>Pure geometry over rectangles, so it is tested rather than looked at. Screen pixels
 * throughout: the caller works in the graph view's own coordinates, which already have the zoom
 * applied, so a tolerance here is a tolerance on screen and does not grow when you zoom out.
 *
 * <p><b>With the grid on, a guide means exact.</b> The grid step is 20 and the tolerance is 4, so
 * two snapped nodes are either on the same line or at least 20 apart - a match inside the
 * tolerance can only be an exact one. The tolerance earns its keep when the grid is off, which on
 * this canvas is Alt-drag, and there it reads as "you are nearly there". That is why the line is
 * drawn through the stationary node's edge rather than the dragged one's: the guide marks where
 * alignment is, and following it is the point.
 */
public final class AlignmentGuides {

    /**
     * How near counts as aligned, in screen pixels.
     *
     * <p>Four, not more. Every pixel of tolerance is a pixel of misalignment the guide will tell
     * you is fine, and a guide that lies is worse than no guide.
     */
    public static final int TOLERANCE = 4;

    /** Which pair of edges matched. Named so a test can say which, and not merely that. */
    public enum Match {
        /** Left edges. */
        LEFT,
        /** Horizontal centres. */
        CENTRE_X,
        /** Right edges. */
        RIGHT,
        /** The dragged node's left against a stationary right edge, or the reverse. */
        EDGE_X,
        /** Top edges. */
        TOP,
        /** Vertical centres. */
        CENTRE_Y,
        /** Bottom edges. */
        BOTTOM,
        /** The dragged node's top against a stationary bottom edge, or the reverse. */
        EDGE_Y;

        /** Whether this match draws a vertical line. */
        public boolean isVertical() {
            return this == LEFT || this == CENTRE_X || this == RIGHT || this == EDGE_X;
        }
    }

    /** One line to draw: where it sits, and how far it runs. */
    public static final class Guide {
        private final Match match;
        private final int position;
        private final int start;
        private final int end;

        Guide(Match match, int position, int start, int end) {
            this.match = match;
            this.position = position;
            this.start = Math.min(start, end);
            this.end = Math.max(start, end);
        }

        /** Which edges matched. */
        public Match getMatch() {
            return match;
        }

        /** The x of a vertical line, or the y of a horizontal one. */
        public int getPosition() {
            return position;
        }

        /** Where the line begins along its own direction. */
        public int getStart() {
            return start;
        }

        /** Where the line ends along its own direction. */
        public int getEnd() {
            return end;
        }

        /** True for a line running top to bottom. */
        public boolean isVertical() {
            return match.isVertical();
        }
    }

    private AlignmentGuides() {
    }

    /**
     * The guides for a node at {@code moving}, against everything else on the board.
     *
     * <p>At most two: the nearest vertical and the nearest horizontal. A dense board matches a
     * dozen edges at once and drawing them all turns the diagram into a cage - the question being
     * answered is "what am I lined up with", and two lines answer it.
     *
     * <p>Ties go to the more meaningful match, which is why the pairings are tried in a fixed
     * order with centres first. Without that the answer would depend on the order the caller
     * happened to pass the rectangles in, and the guide would flicker between two equally valid
     * lines as the mouse moved a pixel.
     *
     * @param moving where the dragged node is now
     * @param others every other node's rectangle; the dragged one must not be among them, or it
     *     will align with itself
     * @param tolerance how near counts, in the same pixels
     * @return zero, one or two guides
     */
    public static List<Guide> forMove(Rectangle moving, Collection<Rectangle> others,
            int tolerance) {
        List<Guide> guides = new ArrayList<Guide>();
        if (moving == null || others == null || others.isEmpty() || tolerance < 0) {
            return guides;
        }
        Guide vertical = nearest(moving, others, tolerance, true);
        if (vertical != null) {
            guides.add(vertical);
        }
        Guide horizontal = nearest(moving, others, tolerance, false);
        if (horizontal != null) {
            guides.add(horizontal);
        }
        return Collections.unmodifiableList(guides);
    }

    /** {@link #forMove} with the tolerance this canvas uses. */
    public static List<Guide> forMove(Rectangle moving, Collection<Rectangle> others) {
        return forMove(moving, others, TOLERANCE);
    }

    /**
     * The best match on one axis, or null.
     *
     * <p>Strictly-smaller keeps the first of equals, so the order the pairings are tried in is
     * the tie-break and the result does not depend on the order of {@code others}.
     */
    private static Guide nearest(Rectangle moving, Collection<Rectangle> others, int tolerance,
            boolean verticalAxis) {
        Match[] kinds = verticalAxis
                ? new Match[] {Match.CENTRE_X, Match.LEFT, Match.RIGHT, Match.EDGE_X}
                : new Match[] {Match.CENTRE_Y, Match.TOP, Match.BOTTOM, Match.EDGE_Y};

        Guide best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Match kind : kinds) {
            for (Rectangle other : others) {
                if (other == null || other.equals(moving)) {
                    continue;
                }
                int[] pair = coordinates(kind, moving, other);
                for (int i = 0; i < pair.length; i += 2) {
                    int distance = Math.abs(pair[i] - pair[i + 1]);
                    if (distance <= tolerance && distance < bestDistance) {
                        bestDistance = distance;
                        best = new Guide(kind, pair[i + 1],
                                verticalAxis
                                        ? Math.min(moving.y, other.y)
                                        : Math.min(moving.x, other.x),
                                verticalAxis
                                        ? Math.max(moving.y + moving.height,
                                                other.y + other.height)
                                        : Math.max(moving.x + moving.width,
                                                other.x + other.width));
                    }
                }
            }
        }
        return best;
    }

    /**
     * The coordinates this kind of match compares, as {moving, stationary} pairs.
     *
     * <p>{@code EDGE_X} and {@code EDGE_Y} carry two pairs because touching is symmetric: the
     * dragged node's left against a stationary right edge is the same gesture as its right
     * against a stationary left one.
     */
    private static int[] coordinates(Match kind, Rectangle moving, Rectangle other) {
        switch (kind) {
            case LEFT:
                return new int[] {moving.x, other.x};
            case RIGHT:
                return new int[] {moving.x + moving.width, other.x + other.width};
            case CENTRE_X:
                return new int[] {moving.x + moving.width / 2, other.x + other.width / 2};
            case EDGE_X:
                return new int[] {moving.x, other.x + other.width,
                        moving.x + moving.width, other.x};
            case TOP:
                return new int[] {moving.y, other.y};
            case BOTTOM:
                return new int[] {moving.y + moving.height, other.y + other.height};
            case CENTRE_Y:
                return new int[] {moving.y + moving.height / 2, other.y + other.height / 2};
            case EDGE_Y:
            default:
                return new int[] {moving.y, other.y + other.height,
                        moving.y + moving.height, other.y};
        }
    }
}
