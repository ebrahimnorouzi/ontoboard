package de.fizkarlsruhe.ise.ontoboard.collab;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.Map;

/**
 * Where a colleague put a term, used when this board has no opinion of its own.
 *
 * <p>Every operation this plugin publishes carries the node's geometry - {@code canvasHints} in the
 * view collects it precisely so a peer can draw an arriving term where it actually is rather than at
 * the origin. On the receiving side it was thrown away: {@code onOperation} mapped the operation to
 * axioms, applied them, and never looked at {@code x}, {@code y}, {@code w} or {@code h}. So the sender
 * did the work and the receiver ignored it, and a class created by a colleague landed wherever
 * {@code SchemaGraph} puts an unpositioned node - in a row along the top of the board.
 *
 * <p>Two rules, and both are about not fighting the local user:
 *
 * <ul>
 *   <li><b>A position we already have is never replaced.</b> The wire has no "moved" operation, so an
 *       inbound position is not news about a move - it is the sender's geometry at the moment they made
 *       an edit. Adopting it over a local position would yank somebody's node across their board
 *       because a colleague happened to add a label to it.
 *   <li><b>The origin means "no hint", not "at the origin".</b> {@code putGeometry} writes 0.0 when the
 *       sender had no geometry for a term - a change made from Prot&eacute;g&eacute;'s tabs rather than
 *       the canvas, which is most of them. Taking that literally would stack every such arrival at
 *       (0,0), which is worse than the row it replaced and is the same defect the expansion code had.
 * </ul>
 *
 * <p>Pure, so both rules are testable without a session, a server or a peer.
 */
public final class PeerGeometry {

    private PeerGeometry() {
    }

    /**
     * Records a peer's geometry for a term, if this board has none.
     *
     * @return true when the position was taken up, so the caller knows whether to redraw
     */
    public static boolean adoptInto(CanvasLayout board, String iri, Map<String, Object> data) {
        if (board == null || iri == null || iri.trim().isEmpty() || data == null) {
            return false;
        }
        if (board.nodes.containsKey(iri)) {
            return false;
        }
        double x = number(data.get("x"));
        double y = number(data.get("y"));
        if (!usable(x) || !usable(y) || (x == 0 && y == 0)) {
            return false;
        }

        CanvasLayout.NodeLayout position = new CanvasLayout.NodeLayout(x, y);
        double width = number(data.get("w"));
        double height = number(data.get("h"));
        if (usable(width) && width > 0) {
            position.w = width;
        }
        if (usable(height) && height > 0) {
            position.h = height;
        }
        board.nodes.put(iri, position);
        return true;
    }

    /**
     * A number from an operation's data, or NaN.
     *
     * <p>Operations arrive as JSON from a web client, a third-party one, or one that is simply wrong,
     * so a field that should be a number can be a string, a boolean or missing. NaN is the one answer
     * that cannot be mistaken for a coordinate.
     */
    private static double number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException notANumber) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static boolean usable(double value) {
        // Bounded as well as finite: a peer sending 1e18 would put a node somewhere no amount of
        // scrolling reaches, and "my board is empty" is how that would be reported.
        return !Double.isNaN(value) && !Double.isInfinite(value) && Math.abs(value) <= 1e7;
    }
}
