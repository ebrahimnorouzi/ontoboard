package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Taking a colleague's position for a term, and the two cases where taking it would be wrong.
 *
 * <p>Every operation this plugin publishes carries the node's geometry - the view collects it precisely
 * so a peer can draw an arriving term where it is rather than at the origin - and the receiving side
 * discarded it. So a class a colleague created landed wherever an unpositioned node goes, in a row along
 * the top of the board.
 *
 * <p>The two rules worth testing are both about not fighting the local user, and both fail silently if
 * got wrong: a position we already have must never be replaced, and the origin has to be read as "no
 * hint" rather than as a coordinate.
 */
class PeerGeometryTest {

    private static final String PIZZA = "http://example.org/pizza#Margherita";

    private static Map<String, Object> at(Object x, Object y) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("x", x);
        data.put("y", y);
        return data;
    }

    private static Map<String, Object> box(double x, double y, double w, double h) {
        Map<String, Object> data = at(x, y);
        data.put("w", w);
        data.put("h", h);
        return data;
    }

    @Test
    void aPeersPositionIsTakenForATermWeHaveNoneFor() {
        CanvasLayout board = new CanvasLayout();

        assertTrue(PeerGeometry.adoptInto(board, PIZZA, box(420, 260, 200, 80)));

        CanvasLayout.NodeLayout placed = board.nodes.get(PIZZA);
        assertEquals(420, placed.x, 1e-9);
        assertEquals(260, placed.y, 1e-9);
        assertEquals(200, placed.w, 1e-9);
        assertEquals(80, placed.h, 1e-9);
    }

    /**
     * A position we already have wins.
     *
     * <p>The wire has no "moved" operation, so an inbound position is not news about a move - it is the
     * sender's geometry at the moment they made some edit. Adopting it over a local one would drag
     * somebody's node across their own board because a colleague added a label to it.
     */
    @Test
    void aPositionWeAlreadyHaveIsNeverReplaced() {
        CanvasLayout board = new CanvasLayout();
        board.nodes.put(PIZZA, new CanvasLayout.NodeLayout(10, 20));

        assertFalse(PeerGeometry.adoptInto(board, PIZZA, box(999, 999, 200, 80)));

        assertEquals(10, board.nodes.get(PIZZA).x, 1e-9);
        assertEquals(20, board.nodes.get(PIZZA).y, 1e-9);
    }

    /**
     * The origin means "no hint".
     *
     * <p>{@code OperationMapper.putGeometry} writes 0.0 when the sender had no geometry for a term,
     * which is every change made from Prot&eacute;g&eacute;'s own tabs rather than the canvas - most of
     * them. Reading that literally would stack every such arrival at (0,0), which is worse than the row
     * it replaced.
     */
    @Test
    void theOriginIsTreatedAsNoHintRatherThanAsAPosition() {
        CanvasLayout board = new CanvasLayout();

        assertFalse(PeerGeometry.adoptInto(board, PIZZA, box(0, 0, 160, 60)));

        assertFalse(board.nodes.containsKey(PIZZA), board.nodes.toString());
    }

    /** A position on one axis only is still a position. */
    @Test
    void aTermOnAnAxisIsStillPlaced() {
        CanvasLayout board = new CanvasLayout();

        assertTrue(PeerGeometry.adoptInto(board, PIZZA, at(0, 400)));

        assertEquals(400, board.nodes.get(PIZZA).y, 1e-9);
    }

    @Test
    void numbersArrivingAsStringsAreRead() {
        // Operations are JSON from a web client or a third-party one; a coordinate can arrive quoted.
        CanvasLayout board = new CanvasLayout();

        assertTrue(PeerGeometry.adoptInto(board, PIZZA, at("120.5", "240")));

        assertEquals(120.5, board.nodes.get(PIZZA).x, 1e-9);
    }

    @Test
    void nonsenseIsRefusedRatherThanDrawnSomewhereImpossible() {
        CanvasLayout board = new CanvasLayout();

        assertFalse(PeerGeometry.adoptInto(board, PIZZA, at("north", "east")));
        assertFalse(PeerGeometry.adoptInto(board, PIZZA, at(Double.NaN, 10)));
        assertFalse(PeerGeometry.adoptInto(board, PIZZA, at(Double.POSITIVE_INFINITY, 10)));
        // A node at 1e18 is somewhere no amount of scrolling reaches, and the report would be
        // "my board is empty".
        assertFalse(PeerGeometry.adoptInto(board, PIZZA, at(1e18, 1e18)));
        assertTrue(board.nodes.isEmpty(), board.nodes.toString());
    }

    @Test
    void aBadSizeDoesNotStopThePositionBeingUsed() {
        CanvasLayout board = new CanvasLayout();

        assertTrue(PeerGeometry.adoptInto(board, PIZZA, box(300, 300, -5, 0)));

        CanvasLayout.NodeLayout placed = board.nodes.get(PIZZA);
        assertEquals(300, placed.x, 1e-9);
        // The defaults, rather than a node of zero height that cannot be clicked.
        assertEquals(160, placed.w, 1e-9);
        assertEquals(60, placed.h, 1e-9);
    }

    @Test
    void missingArgumentsAreRefusedRatherThanThrown() {
        CanvasLayout board = new CanvasLayout();

        assertFalse(PeerGeometry.adoptInto(null, PIZZA, at(10, 10)));
        assertFalse(PeerGeometry.adoptInto(board, null, at(10, 10)));
        assertFalse(PeerGeometry.adoptInto(board, "  ", at(10, 10)));
        assertFalse(PeerGeometry.adoptInto(board, PIZZA, null));
        assertFalse(PeerGeometry.adoptInto(board, PIZZA, new HashMap<String, Object>()));
    }
}
