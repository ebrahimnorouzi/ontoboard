package de.fizkarlsruhe.ise.ontoboard.collab;

/**
 * What a session has had to tell the user, and for how long it stays true.
 *
 * <p>Its own class because the canvas view got this wrong twice in the same way, and the mistake is
 * not visible in either place it was made. A session produces two kinds of message. One is true
 * <em>now</em> - connected, retrying, refused - and the next one replaces it. The other is true for
 * the rest of the session once it has happened: three of your axioms were never sent, and no
 * subsequent "Connected" makes that less so.
 *
 * <p>1.67.0 found the first instance: the transient board confirmations ("Arranged the board") and
 * the session notices shared one label, whichever wrote last won permanently, and so arranging the
 * board erased the only warning that a colleague would never see an edit. 1.93.0 found the same bug
 * one level down - the count and the connection status were both written into the session label, so
 * the next reconnect erased it and left a green light over a session in which some of the user's
 * work had gone nowhere. Both times the two statements looked like the same kind of thing because
 * they were the same type in the same field.
 *
 * <p>So the lasting ones live here, together, with their own rules: the count never decreases, and
 * nothing clears it except {@link #forget()} at the start of a new session. Pure, so both rules are
 * testable without a session, a server or a peer - the same reason {@link PeerGeometry} is.
 */
public final class SessionNotices {

    private int unshareable;
    private String lastReason = "";
    private boolean saidMovesAreLocal;

    /**
     * Starts a new session's worth of notices.
     *
     * <p>Called when a session begins, and only then. <b>Not</b> on disconnect: those edits are
     * still unshared once the socket closes, and clearing the warning as the session ends would
     * hide it at the moment it starts to matter - the user is about to commit, and that warning is
     * what tells them a commit is how their colleagues get these changes. Not on reconnect either,
     * which is the defect this class exists to prevent.
     */
    public void forget() {
        unshareable = 0;
        lastReason = "";
        saidMovesAreLocal = false;
    }

    /**
     * Records that some edits could not be put on the wire.
     *
     * <p>Takes the running total the session reports rather than an increment, and keeps the larger
     * of the two. An axiom that was not sent is not sent later, so this is monotonic by nature; and
     * taking the maximum means a session that resets its own counter - or two reports arriving out
     * of order - cannot make the user's warning quieter than the truth.
     */
    public void noteUnshareable(int total, String reason) {
        unshareable = Math.max(unshareable, total);
        if (reason != null && !reason.trim().isEmpty()) {
            lastReason = reason.trim();
        }
    }

    /** How many edits never reached anybody. */
    public int getUnshareable() {
        return unshareable;
    }

    /** True when there is a warning to show at all. */
    public boolean hasUnshareable() {
        return unshareable > 0;
    }

    /** The status text, or empty when there is nothing to say. */
    public String unshareableLabel() {
        if (unshareable == 0) {
            return "";
        }
        return unshareable + " change" + (unshareable == 1 ? "" : "s") + " not shared";
    }

    /**
     * The whole explanation, for the tooltip, or null when there is nothing to say.
     *
     * <p>Says where the changes went as well as that they did not travel. "3 changes not shared"
     * alone reads as data loss; the edits are in fact in the user's own ontology and will reach
     * their colleagues through git, and a warning that frightens somebody about a thing that has
     * not happened is its own kind of wrong.
     */
    public String unshareableTooltip() {
        if (unshareable == 0) {
            return null;
        }
        String sentence = unshareable + (unshareable == 1 ? " edit of yours was" : " edits of yours were")
                + " not sent to anyone, because the live session has no way to express "
                + (unshareable == 1 ? "it" : "them") + ". "
                + (unshareable == 1 ? "It is" : "They are") + " in your copy of the ontology and "
                + "will reach your colleagues through git.";
        return lastReason.isEmpty() ? sentence : sentence + " The most recent was " + lastReason;
    }

    /**
     * Whether to tell the user that dragging a node is a local act - true the first time only.
     *
     * <p>Once per session, because dragging is the most repeated action on the canvas and a message
     * per drag would be noise. Said at all because the product was silent about it: an unshareable
     * <em>axiom</em> is counted and reported, while arranging thirty classes into a readable diagram
     * produced no count, no message and no hint that the colleague watching sees none of it. The
     * behaviour itself is deliberate and right - see {@link PeerGeometry} - and that is exactly why
     * it has to be said rather than fixed.
     */
    public boolean shouldSayMovesAreLocal() {
        if (saidMovesAreLocal) {
            return false;
        }
        saidMovesAreLocal = true;
        return true;
    }

    /** What to say about a move, when {@link #shouldSayMovesAreLocal()} says to. */
    public static final String MOVES_ARE_LOCAL = "Moved - positions stay on your board";

    /** And why, at length. */
    public static final String MOVES_ARE_LOCAL_DETAIL =
            "Where you put a node is yours. A position is shared only when a term is first "
            + "created; the live session has no operation for a move, so dragging one afterwards "
            + "changes nothing for your colleagues. Arrangements travel through git, in the "
            + "board's layout file.";
}
