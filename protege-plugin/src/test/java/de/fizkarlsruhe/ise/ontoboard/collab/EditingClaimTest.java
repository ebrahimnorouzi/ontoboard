package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Seeing who is editing a term, which is what "entity locking" can honestly be.
 *
 * <p>The roadmap called it locking. Prot&eacute;g&eacute; owns the edit model, so OntoBoard
 * cannot stop a second edit - only report the first in time. These tests are about the
 * reporting being true: observed rather than declared, expiring in place, and dying with its
 * holder.
 */
class EditingClaimTest {

    private static final long NOW = 1_700_000_000_000L;

    // ---------------------------------------------------------------- the claim

    @Test
    void anEditClaimsTheTerm() {
        EditingClaim claim = new EditingClaim();
        claim.edited("http://example.org/Calzone", NOW);

        assertEquals("http://example.org/Calzone", claim.current(NOW));
    }

    /** It expires in place, so a forgotten window stops speaking for somebody. */
    @Test
    void aClaimExpiresWhereItStands() {
        EditingClaim claim = new EditingClaim();
        claim.edited("http://example.org/Calzone", NOW);

        assertNotNull(claim.current(NOW + EditingClaim.HOLD_MILLIS));
        assertNull(claim.current(NOW + EditingClaim.HOLD_MILLIS + 1),
                "five minutes after the last edit, they are not editing it any more");
    }

    /** Editing again extends it; that is what "is editing" means. */
    @Test
    void editingAgainKeepsIt() {
        EditingClaim claim = new EditingClaim();
        claim.edited("http://example.org/Calzone", NOW);
        claim.edited("http://example.org/Calzone", NOW + EditingClaim.HOLD_MILLIS - 1);

        assertNotNull(claim.current(NOW + EditingClaim.HOLD_MILLIS + 1));
    }

    /** Editing a different term moves the claim rather than holding two. */
    @Test
    void onlyTheLatestTermIsClaimed() {
        EditingClaim claim = new EditingClaim();
        claim.edited("http://example.org/A", NOW);
        claim.edited("http://example.org/B", NOW + 1000);

        assertEquals("http://example.org/B", claim.current(NOW + 2000));
    }

    /** Nothing claims nothing. */
    @Test
    void anEmptyEditIsNotAClaim() {
        EditingClaim claim = new EditingClaim();
        claim.edited(null, NOW);
        claim.edited("   ", NOW);

        assertNull(claim.current(NOW));
    }

    /** Leaving drops it immediately rather than waiting for the hold. */
    @Test
    void releasingDropsItAtOnce() {
        EditingClaim claim = new EditingClaim();
        claim.edited("http://example.org/Calzone", NOW);
        claim.release();

        assertNull(claim.current(NOW));
    }

    // ---------------------------------------------------------------- what a peer shows

    /** A peer who is here and editing something is shown. */
    @Test
    void aLivePeerEditingSomethingIsShown() {
        PeerPresence bob = new PeerPresence("Bob", "#f00", 0, 0, null, NOW, "",
                "http://example.org/Calzone");

        assertTrue(EditingClaim.isLive(bob, NOW, PeerCursors.TTL_MILLIS));
        assertEquals("http://example.org/Calzone", bob.getEditing());
    }

    /**
     * A peer who has gone is not, however recently they were editing.
     *
     * <p>This is the property that makes the feature safe: the claim rides presence, and
     * presence expires in ten seconds, so a crashed or disconnected peer cannot hold a term.
     */
    @Test
    void aClaimDiesWithItsHolder() {
        PeerPresence bob = new PeerPresence("Bob", "#f00", 0, 0, null, NOW, "",
                "http://example.org/Calzone");

        assertFalse(EditingClaim.isLive(bob, NOW + PeerCursors.TTL_MILLIS + 1,
                PeerCursors.TTL_MILLIS),
                "a peer who stopped beating cannot still be editing");
    }

    /** A peer who is here but editing nothing shows no badge. */
    @Test
    void presentButNotEditingShowsNothing() {
        PeerPresence idle = new PeerPresence("Bob", "#f00", 0, 0,
                "http://example.org/Selected", NOW, "", "");

        assertFalse(EditingClaim.isLive(idle, NOW, PeerCursors.TTL_MILLIS),
                "selection is not editing");
        assertFalse(EditingClaim.isLive(null, NOW, PeerCursors.TTL_MILLIS));
    }

    /** The sentence says "is editing", never "has locked". */
    @Test
    void theSentenceDoesNotPromiseALock() {
        String said = EditingClaim.describe("Bob", "Calzone");

        assertEquals("Bob is editing Calzone", said);
        assertFalse(said.toLowerCase().contains("lock"),
                "nothing is locked, and the words must not imply it");
        assertNull(EditingClaim.describe(null, "Calzone"));
        assertNull(EditingClaim.describe("Bob", "  "));
    }

    // ---------------------------------------------------------------- the wire

    /** A claim is sent on the beat, and omitted entirely when there is none. */
    @Test
    void theClaimTravelsOnPresence() {
        String withClaim = CollabMessages.presence("Bob", "#f00", 1, 2, null,
                "http://example.org/Calzone");
        String without = CollabMessages.presence("Bob", "#f00", 1, 2, null, null);

        assertTrue(withClaim.contains("\"editing\":\"http://example.org/Calzone\""), withClaim);
        assertFalse(without.contains("editing"),
                "no claim means no field, so an older bridge sees what it always saw: "
                        + without);
    }

    /** And comes back from the peers message. */
    @Test
    void theClaimComesBackFromThePeersMessage() {
        String peers = "{\"t\":\"peers\",\"peers\":[{\"user\":\"Bob\",\"colour\":\"#f00\","
                + "\"x\":1,\"y\":2,\"selection\":null,\"ontology\":\"\","
                + "\"editing\":\"http://example.org/Calzone\"}]}";

        List<PeerPresence> read = CollabMessages.decode(peers, NOW).getPeers();

        assertEquals(1, read.size());
        assertEquals("http://example.org/Calzone", read.get(0).getEditing());
    }

    /** An older bridge that drops the field yields no claim, not a wrong one. */
    @Test
    void anOlderBridgeIsNotMisread() {
        String peers = "{\"t\":\"peers\",\"peers\":[{\"user\":\"Bob\",\"colour\":\"#f00\","
                + "\"x\":1,\"y\":2,\"selection\":null,\"ontology\":\"\"}]}";

        List<PeerPresence> read = CollabMessages.decode(peers, NOW).getPeers();

        assertEquals("", read.get(0).getEditing());
        assertFalse(EditingClaim.isLive(read.get(0), NOW, PeerCursors.TTL_MILLIS),
                "absence is no information, and must not become a badge");
    }

    /**
     * The bridge copies the field in BOTH places, which is one test because it was one bug.
     *
     * <p>{@code livePeers} rebuilds each peer as a fresh object rather than passing the stored
     * one through, so a field set on receipt and omitted there arrives empty at every peer. The
     * bridge's own comments record that happening to {@code ontology}. Reading the file is the
     * only way to check it from here, and it is worth checking.
     */
    @Test
    void theBridgeCarriesTheFieldOnBothSides() throws Exception {
        File bridge = new File("../collab/bridge.mjs");
        if (!bridge.isFile()) {
            return;
        }
        String source = new String(Files.readAllBytes(bridge.toPath()), StandardCharsets.UTF_8);

        assertTrue(source.contains("peer.editing = message.editing"),
                "the bridge must copy the field when a presence arrives");
        assertTrue(source.contains("editing: peer.editing"),
                "and must include it in what livePeers sends back - omitting this is the bug "
                        + "that made every peer's ontology arrive empty");
    }

    // ---------------------------------------------------------------- the status line

    /** One peer is named, because knowing whether it is your term is the whole point. */
    @Test
    void oneClaimIsNamed() {
        assertEquals("Bob is editing Calzone",
                EditingClaim.summarise(java.util.Arrays.asList("Bob is editing Calzone")));
    }

    /** A few are listed. */
    @Test
    void aFewAreListed() {
        String said = EditingClaim.summarise(java.util.Arrays.asList(
                "Bob is editing Calzone", "Ann is editing Pizza"));

        assertEquals("Bob is editing Calzone, and Ann is editing Pizza", said);
    }

    /** Past three it becomes a count, because a status bar that wraps is not read. */
    @Test
    void manyBecomeACount() {
        String said = EditingClaim.summarise(java.util.Arrays.asList(
                "a is editing X", "b is editing Y", "c is editing Z", "d is editing W"));

        assertEquals("4 people are editing terms right now", said);
    }

    /** Nothing to say says nothing, rather than an empty bar. */
    @Test
    void nothingToSaySaysNothing() {
        assertNull(EditingClaim.summarise(null));
        assertNull(EditingClaim.summarise(java.util.Collections.<String>emptyList()));
        assertNull(EditingClaim.summarise(java.util.Arrays.asList((String) null, "  ")));
    }

    /** A marker carries the claim through to the canvas, distinct from selection. */
    @Test
    void theCanvasMarkerCarriesTheClaim() {
        PeerCursors cursors = new PeerCursors();
        cursors.setSelf("Me");
        cursors.update(java.util.Arrays.asList(new PeerPresence("Bob", "#f00", 1, 2,
                "http://example.org/Selected", NOW, "", "http://example.org/Edited")));

        java.util.List<PeerCursors.Marker> visible = cursors.visibleAt(NOW);

        assertEquals(1, visible.size());
        assertEquals("http://example.org/Edited", visible.get(0).getEditing());
        assertEquals("http://example.org/Selected", visible.get(0).getSelection());
    }

    /** And shows no claim when there is none, rather than echoing the selection. */
    @Test
    void aMarkerWithNoClaimHasNone() {
        PeerCursors cursors = new PeerCursors();
        cursors.update(java.util.Arrays.asList(new PeerPresence("Bob", "#f00", 1, 2,
                "http://example.org/Selected", NOW, "", "")));

        assertNull(cursors.visibleAt(NOW).get(0).getEditing());
    }
}
