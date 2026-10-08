package de.fizkarlsruhe.ise.ontoboard.collab;

/**
 * Which term this user is currently editing, so other people can see it.
 *
 * <p>Roadmap item 3, and deliberately not what that item was called. "Entity locking" promises
 * that two people cannot edit the same term; OntoBoard cannot deliver that, and should not
 * pretend to. Prot&eacute;g&eacute; owns the edit model - a user changes an axiom through its
 * own views, its own undo stack, its own keyboard shortcuts - and this plugin learns about the
 * change after it has happened. A lock it could not enforce would be worse than none, because
 * somebody would rely on it.
 *
 * <p>What is deliverable, and what people actually need, is <b>seeing</b> it: a badge saying
 * "Bob is editing Calzone" before you start editing Calzone too. That is advisory, it is
 * honest, and it prevents the collision in the only way a plugin in this position can - by
 * telling you in time.
 *
 * <p><b>Observed, not declared.</b> A claim is not something a user announces; it is made by
 * actually changing an axiom on a term, which this plugin already sees. Nothing to remember to
 * turn on, and nothing left claiming a term somebody merely clicked - selection already travels
 * and is already drawn as a ring on the canvas, and conflating the two would make every click
 * look like an edit.
 *
 * <p><b>It dies with its holder.</b> The claim rides the presence heartbeat, whose peers expire
 * after {@link PeerCursors#TTL_MILLIS} - ten seconds. A peer who closes Prot&eacute;g&eacute;,
 * loses the network or crashes stops being a peer, and their claim goes with them. A lock that
 * outlives its holder is the failure that makes locking hated, and riding presence is what
 * makes it structurally impossible here rather than merely handled.
 *
 * <p><b>It also expires in place.</b> Somebody who edits a term and then goes to lunch holds it
 * for {@link #HOLD_MILLIS} and no longer, because the interesting fact is "is editing", not
 * "once touched".
 */
public final class EditingClaim {

    /**
     * How long an edit keeps a term claimed, with no further edits.
     *
     * <p>Five minutes. Long enough to cover thinking, reading and typing a definition; short
     * enough that a forgotten window stops speaking for somebody within a coffee break. The
     * presence TTL is the other half of this and is far shorter - ten seconds - but that one
     * answers "are they still here", which is a different question.
     */
    public static final long HOLD_MILLIS = 5 * 60 * 1000L;

    private String iri;
    private long touchedAt;

    /**
     * Records that this user has just changed something on a term.
     *
     * @param iri the term, or null to let the current claim stand
     * @param now the current time in milliseconds
     */
    public synchronized void edited(String iri, long now) {
        if (iri == null || iri.trim().isEmpty()) {
            return;
        }
        this.iri = iri.trim();
        this.touchedAt = now;
    }

    /**
     * The term this user is editing, or null when the claim has expired.
     *
     * <p>Asked on every presence beat rather than stored at the moment of the edit, so the hold
     * expires at the holder even if nothing else happens - the alternative, a frozen frame
     * composed once and resent, would keep announcing a term for as long as the session lasted.
     */
    public synchronized String current(long now) {
        if (iri == null) {
            return null;
        }
        if (now - touchedAt > HOLD_MILLIS) {
            iri = null;
            return null;
        }
        return iri;
    }

    /** Drops the claim, for a user who has closed the ontology or left the session. */
    public synchronized void release() {
        iri = null;
        touchedAt = 0;
    }

    /**
     * How to say that a peer is editing a term.
     *
     * <p>"is editing" rather than "has locked": nothing is locked, and the sentence a user reads
     * should not imply otherwise. The label is short because it goes in a status bar and on a
     * badge beside a node.
     *
     * @param user the peer's name
     * @param label the term's label, or its IRI when it has none
     * @return the sentence, or null when there is nothing to say
     */
    public static String describe(String user, String label) {
        if (user == null || user.trim().isEmpty()
                || label == null || label.trim().isEmpty()) {
            return null;
        }
        return user.trim() + " is editing " + label.trim();
    }

    /**
     * One line for the status bar, from the sentences {@link #describe} produced.
     *
     * <p>Named rather than counted while there are few, because "Bob is editing Calzone" is
     * actionable and "2 people are editing" is not - the whole value of this is knowing whether
     * it is <em>your</em> term. Past three it becomes a count, since a status bar that wraps is
     * one nobody reads.
     *
     * @param described sentences from {@link #describe}, nulls allowed and skipped
     * @return the line, or null when there is nothing to say
     */
    public static String summarise(java.util.List<String> described) {
        if (described == null) {
            return null;
        }
        java.util.List<String> real = new java.util.ArrayList<String>();
        for (String one : described) {
            if (one != null && !one.trim().isEmpty()) {
                real.add(one.trim());
            }
        }
        if (real.isEmpty()) {
            return null;
        }
        if (real.size() == 1) {
            return real.get(0);
        }
        if (real.size() <= 3) {
            StringBuilder joined = new StringBuilder();
            for (int at = 0; at < real.size(); at++) {
                joined.append(at == 0 ? "" : at == real.size() - 1 ? ", and " : ", ")
                        .append(real.get(at));
            }
            return joined.toString();
        }
        return real.size() + " people are editing terms right now";
    }

    /**
     * Whether a peer's claim should still be shown.
     *
     * <p>Two expiries, and both have to pass. The peer has to still be present - that is
     * {@code PeerPresence.isStale} and ten seconds - and the claim itself has to be inside its
     * own hold. The second matters because presence keeps beating while somebody reads: without
     * it, a term claimed once would stay claimed all afternoon.
     */
    public static boolean isLive(PeerPresence peer, long now, long presenceTtl) {
        if (peer == null || peer.isStale(now, presenceTtl)) {
            return false;
        }
        String editing = peer.getEditing();
        return editing != null && !editing.trim().isEmpty();
    }
}
