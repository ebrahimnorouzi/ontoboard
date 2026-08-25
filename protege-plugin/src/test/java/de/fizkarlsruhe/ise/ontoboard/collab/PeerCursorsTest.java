package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which cursors get drawn, and in what order.
 *
 * <p>All of it headless, which is the point of keeping these decisions out of the painting code:
 * a cursor that follows the user's own mouse a frame behind, or a pair that flicker between
 * paints, look like bugs in the canvas and would only ever be found by staring at a running
 * Protege.
 */
class PeerCursorsTest {

    private static final long NOW = 1_000_000L;

    private final PeerCursors cursors = new PeerCursors();

    private static PeerPresence peer(String user, long seenAt) {
        return new PeerPresence(user, "#112233", 1, 2, null, seenAt);
    }

    private static List<String> names(List<PeerCursors.Marker> markers) {
        List<String> names = new ArrayList<String>();
        for (PeerCursors.Marker marker : markers) {
            names.add(marker.getUser());
        }
        return names;
    }

    // ---------- self ----------

    /**
     * The bridge's peer list includes the sender, so without this a second cursor trails the
     * user's own mouse - which reads as a rendering bug, not a filtering one.
     */
    @Test
    void ourOwnCursorIsNotDrawn() {
        cursors.setSelf("alice");
        cursors.update(Arrays.asList(peer("alice", NOW), peer("bob", NOW)));

        assertEquals(Arrays.asList("bob"), names(cursors.visibleAt(NOW)));
    }

    @Test
    void beforeTheServerSaysWhoWeAreEveryoneIsDrawn() {
        cursors.update(Arrays.asList(peer("alice", NOW), peer("bob", NOW)));

        assertEquals(2, cursors.visibleAt(NOW).size());
    }

    @Test
    void ourOwnEntryStillCountsTowardsHowManyPeopleAreHere() {
        cursors.setSelf("alice");
        cursors.update(Arrays.asList(peer("alice", NOW), peer("bob", NOW)));

        assertEquals(2, cursors.countAt(NOW),
                "\"2 people here\" should include the person reading it");
    }

    // ---------- staleness ----------

    @Test
    void aPeerThatHasGoneQuietStopsBeingDrawn() {
        cursors.update(Arrays.asList(peer("bob", NOW - PeerCursors.TTL_MILLIS - 1)));

        assertTrue(cursors.visibleAt(NOW).isEmpty());
    }

    @Test
    void aPeerExactlyAtTheDeadlineIsStillDrawn() {
        cursors.update(Arrays.asList(peer("bob", NOW - PeerCursors.TTL_MILLIS)));

        assertEquals(1, cursors.visibleAt(NOW).size());
    }

    /**
     * Judging staleness more strictly than the bridge would hide someone the server still
     * considers present, so the two windows have to match.
     */
    @Test
    void theExpiryWindowMatchesTheBridgesOwn() {
        assertEquals(10000, PeerCursors.TTL_MILLIS,
                "PRESENCE_TTL_MS in collab/bridge.mjs is 10000; the two must agree");
    }

    // ---------- replacement, not merging ----------

    /**
     * The bridge sends the whole live list each time, so a peer who has left is simply absent.
     * Merging would keep them on screen until their own entry timed out.
     */
    @Test
    void aPeerMissingFromTheLatestListDisappearsImmediately() {
        cursors.update(Arrays.asList(peer("bob", NOW), peer("amy", NOW)));
        cursors.update(Arrays.asList(peer("amy", NOW)));

        assertEquals(Arrays.asList("amy"), names(cursors.visibleAt(NOW)));
    }

    @Test
    void anUpdatedPositionReplacesTheOldOneRatherThanAddingASecondCursor() {
        cursors.update(Arrays.asList(new PeerPresence("bob", "#112233", 1, 1, null, NOW)));
        cursors.update(Arrays.asList(new PeerPresence("bob", "#112233", 90, 90, null, NOW)));

        List<PeerCursors.Marker> visible = cursors.visibleAt(NOW);
        assertEquals(1, visible.size());
        assertEquals(90.0, visible.get(0).getX());
    }

    @Test
    void clearingRemovesEveryoneSoADeadSessionLeavesNoCursorsBehind() {
        cursors.update(Arrays.asList(peer("bob", NOW)));
        cursors.clear();

        assertTrue(cursors.visibleAt(NOW).isEmpty());
    }

    @Test
    void aMissingOrEmptyListIsHandledWithoutComplaint() {
        cursors.update(null);
        assertTrue(cursors.visibleAt(NOW).isEmpty());
        cursors.update(new ArrayList<PeerPresence>());
        assertTrue(cursors.visibleAt(NOW).isEmpty());
    }

    @Test
    void aPeerWithNoNameIsSkippedSinceTheLabelWouldBeBlank() {
        cursors.update(Arrays.asList(new PeerPresence("", "#112233", 1, 1, null, NOW),
                peer("bob", NOW)));

        assertEquals(Arrays.asList("bob"), names(cursors.visibleAt(NOW)));
    }

    // ---------- order ----------

    /**
     * A peer list arrives on every presence update from anyone, several times a second. An
     * unstable order makes overlapping cursors swap which one is on top between paints, which
     * shows as flicker.
     */
    @Test
    void theDrawOrderIsStableAcrossUpdatesThatArriveInDifferentOrders() {
        cursors.update(Arrays.asList(peer("zoe", NOW), peer("amy", NOW), peer("bob", NOW)));
        List<String> first = names(cursors.visibleAt(NOW));

        cursors.update(Arrays.asList(peer("bob", NOW), peer("zoe", NOW), peer("amy", NOW)));
        List<String> second = names(cursors.visibleAt(NOW));

        assertEquals(first, second, "the order changed with the arrival order");
        assertEquals(Arrays.asList("amy", "bob", "zoe"), first);
    }

    // ---------- what the painter needs ----------

    @Test
    void aMarkerCarriesTheGraphSpacePositionUnchanged() {
        cursors.update(Arrays.asList(
                new PeerPresence("bob", "#112233", -42.5, 900.25, null, NOW)));

        PeerCursors.Marker marker = cursors.visibleAt(NOW).get(0);
        assertEquals(-42.5, marker.getX(), "negative coordinates are legal in graph space");
        assertEquals(900.25, marker.getY());
    }

    @Test
    void aMarkerCarriesTheSelectionSoTheNodeCanBeRinged() {
        cursors.update(Arrays.asList(new PeerPresence("bob", "#112233", 0, 0,
                "http://example.org/o#Person", NOW)));

        assertEquals("http://example.org/o#Person", cursors.visibleAt(NOW).get(0).getSelection());
    }

    @Test
    void noSelectionReadsAsNullRatherThanAnEmptyString() {
        cursors.update(Arrays.asList(peer("bob", NOW)));
        assertNull(cursors.visibleAt(NOW).get(0).getSelection());
    }

    // ---------- colours ----------

    @Test
    void aSixDigitHexColourIsUsedAsGiven() {
        assertEquals(new Color(0xAA, 0xBB, 0xCC), PeerCursors.parseColour("#AABBCC"));
    }

    @Test
    void theShortHexFormIsExpandedRatherThanRejected() {
        assertEquals(new Color(0xAA, 0xBB, 0xCC), PeerCursors.parseColour("#abc"));
    }

    /**
     * The colour comes from another client and passes through the bridge unvalidated, so anything
     * can arrive. Throwing inside a paint would be far worse than drawing the wrong colour.
     */
    @Test
    void anUnreadableColourFallsBackInsteadOfThrowing() {
        for (String bad : new String[] {null, "", "   ", "red", "#", "#12", "#12345",
            "#GGGGGG", "rgb(1,2,3)", "0xAABBCC", "#AABBCCDD"}) {
            Color result = PeerCursors.parseColour(bad);
            assertNotNull(result, "returned null for " + bad);
            assertEquals(PeerCursors.FALLBACK_COLOUR, result, "for input: " + bad);
        }
    }

    @Test
    void theFallbackMatchesPeerPresencesOwnDefaultSoOneUserDoesNotChangeColour() {
        Color fromPresence = PeerCursors.parseColour(
                new PeerPresence("bob", null, 0, 0, null, NOW).getColour());

        assertEquals(PeerCursors.FALLBACK_COLOUR, fromPresence,
                "PeerPresence defaults to #4A90D9; drawing a different colour for the same "
                        + "peer would look like two people");
    }

    @Test
    void surroundingWhitespaceIsToleratedSinceItComesOffAWire() {
        assertEquals(new Color(0xAA, 0xBB, 0xCC), PeerCursors.parseColour("  #AABBCC  "));
    }
}
