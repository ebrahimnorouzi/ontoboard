package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How long a session notice stays true, which this view has got wrong twice.
 *
 * <p>Both times the mistake was the same and invisible in the line that made it: a statement that
 * lasts the whole session kept in the same place as one that changes, so the changing one wrote over
 * it. 1.67.0 found it between the board's confirmations and the session's notices - arranging the
 * board erased the warning that a colleague would never see an edit. 1.93.0 found it again inside
 * the session label itself, between the connection status and the unshared count, where every
 * automatic reconnect erased the count and left a green light over a session in which some of the
 * user's axioms had gone nowhere.
 *
 * <p>These tests are the rule rather than the rendering: the count never decreases, nothing clears
 * it but a new session, and a disconnect in particular does not.
 */
class SessionNoticesTest {

    private static final String REASON =
            "an EquivalentClasses axiom, which the shared session has no way to express";

    /** Nothing to say, and nothing claimed. */
    @Test
    void aFreshSessionHasNothingToReport() {
        SessionNotices notices = new SessionNotices();

        assertFalse(notices.hasUnshareable());
        assertEquals(0, notices.getUnshareable());
        assertEquals("", notices.unshareableLabel());
        assertNull(notices.unshareableTooltip(), "no tooltip, rather than an empty one");
    }

    /** The count and its reason are kept. */
    @Test
    void anUnshareableEditIsRecorded() {
        SessionNotices notices = new SessionNotices();

        notices.noteUnshareable(1, REASON);

        assertTrue(notices.hasUnshareable());
        assertEquals("1 change not shared", notices.unshareableLabel());
        assertTrue(notices.unshareableTooltip().contains(REASON));
    }

    /** Plural, because "1 changes" in a warning reads as a bug in the warning. */
    @Test
    void theWordingAgreesWithTheCount() {
        SessionNotices notices = new SessionNotices();

        notices.noteUnshareable(1, REASON);
        assertEquals("1 change not shared", notices.unshareableLabel());
        assertTrue(notices.unshareableTooltip().startsWith("1 edit of yours was"));

        notices.noteUnshareable(4, REASON);
        assertEquals("4 changes not shared", notices.unshareableLabel());
        assertTrue(notices.unshareableTooltip().startsWith("4 edits of yours were"));
    }

    /**
     * It never goes down.
     *
     * <p>An axiom that was not sent is not sent later. Taking the maximum rather than the latest
     * value means a session that resets its own counter, or two reports arriving out of order,
     * cannot make the user's warning quieter than the truth.
     */
    @Test
    void theCountNeverDecreases() {
        SessionNotices notices = new SessionNotices();

        notices.noteUnshareable(5, REASON);
        notices.noteUnshareable(2, REASON);
        notices.noteUnshareable(0, REASON);

        assertEquals(5, notices.getUnshareable());
    }

    /** A later report without a reason keeps the one it had. */
    @Test
    void anEmptyReasonDoesNotEraseTheLastOne() {
        SessionNotices notices = new SessionNotices();
        notices.noteUnshareable(1, REASON);

        notices.noteUnshareable(2, null);
        notices.noteUnshareable(3, "   ");

        assertTrue(notices.unshareableTooltip().contains(REASON));
        assertEquals(3, notices.getUnshareable());
    }

    /**
     * The tooltip says where the changes went, not only that they did not travel.
     *
     * <p>"3 changes not shared" on its own reads as data loss. They are in the user's own ontology
     * and will reach their colleagues through git, and a warning that frightens somebody about
     * something that has not happened is its own kind of wrong.
     */
    @Test
    void theTooltipSaysTheWorkIsNotLost() {
        SessionNotices notices = new SessionNotices();
        notices.noteUnshareable(3, REASON);

        String tooltip = notices.unshareableTooltip();

        assertTrue(tooltip.contains("your copy of the ontology"), tooltip);
        assertTrue(tooltip.contains("git"), tooltip);
    }

    /** Only a new session clears it - which is the whole point of the class. */
    @Test
    void onlyANewSessionClearsIt() {
        SessionNotices notices = new SessionNotices();
        notices.noteUnshareable(3, REASON);

        notices.forget();

        assertFalse(notices.hasUnshareable());
        assertEquals("", notices.unshareableLabel());
        assertNull(notices.unshareableTooltip());
    }

    /**
     * A reconnect is not a new session, so a reconnect cannot clear it.
     *
     * <p>The defect, stated as a test. Nothing but {@code forget} touches the count, and
     * {@code forget} is called from exactly one place - where a session is created. The view's old
     * arrangement had the count and the connection status writing the same label, so "Connected"
     * after an automatic reconnect erased it with no code anywhere expressing that intent.
     */
    @Test
    void aReconnectCannotClearIt() {
        SessionNotices notices = new SessionNotices();
        notices.noteUnshareable(3, REASON);

        // Everything a reconnect does: statuses change, peers come and go, operations arrive.
        // None of it is reporting an unshareable edit, so none of it is allowed to touch the count.
        notices.noteUnshareable(3, REASON);

        assertEquals(3, notices.getUnshareable(),
                "the count survives everything except the start of a new session");
    }

    // ---------- moving a node ----------

    /**
     * The move notice is shown once per session.
     *
     * <p>Dragging is the most repeated action on the canvas, so a message per drag would be noise,
     * and nothing at all is what the product used to do: an unshareable axiom was counted and
     * reported while arranging thirty classes produced no count and no message.
     */
    @Test
    void movesAreExplainedOncePerSession() {
        SessionNotices notices = new SessionNotices();

        assertTrue(notices.shouldSayMovesAreLocal(), "the first drag is told");
        assertFalse(notices.shouldSayMovesAreLocal());
        assertFalse(notices.shouldSayMovesAreLocal());

        notices.forget();

        assertTrue(notices.shouldSayMovesAreLocal(),
                "a new session says it again - yesterday's was long forgotten");
    }

    /** And it says why, including where arrangements do travel. */
    @Test
    void theMoveNoticeSaysWhereArrangementsDoGo() {
        assertTrue(SessionNotices.MOVES_ARE_LOCAL_DETAIL.contains("git"),
                SessionNotices.MOVES_ARE_LOCAL_DETAIL);
        assertTrue(SessionNotices.MOVES_ARE_LOCAL_DETAIL.contains("first"),
                "a position IS shared when a term is created, and saying only 'not shared' "
                        + "would be wrong: " + SessionNotices.MOVES_ARE_LOCAL_DETAIL);
        assertFalse(SessionNotices.MOVES_ARE_LOCAL.isEmpty());
    }
}
