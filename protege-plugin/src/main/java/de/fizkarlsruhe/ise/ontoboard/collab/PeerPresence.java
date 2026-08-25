package de.fizkarlsruhe.ise.ontoboard.collab;

/**
 * Where another collaborator's cursor is, and what they have selected.
 *
 * <p>Coordinates are in <em>graph</em> space, not screen space, so a peer's cursor lands on
 * the same entity for everyone regardless of window size, scroll position or zoom. Sending
 * screen coordinates would put someone's pointer over a different class on every machine.
 */
public final class PeerPresence {

    private final String user;
    private final String colour;
    private final double x;
    private final double y;
    private final String selection;
    private final long seenAt;

    public PeerPresence(String user, String colour, double x, double y, String selection,
            long seenAt) {
        this.user = user == null ? "anonymous" : user;
        this.colour = colour == null || colour.trim().isEmpty() ? "#4A90D9" : colour;
        this.x = x;
        this.y = y;
        this.selection = selection;
        this.seenAt = seenAt;
    }

    public String getUser() {
        return user;
    }

    /** Hex colour for the cursor and label, so a peer is identifiable at a glance. */
    public String getColour() {
        return colour;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    /** IRI the peer has selected, or {@code null}. Lets a viewer see what someone is on. */
    public String getSelection() {
        return selection;
    }

    public long getSeenAt() {
        return seenAt;
    }

    /**
     * Whether this peer should still be drawn.
     *
     * <p>A client that crashes or loses its network sends no goodbye, so cursors are dropped
     * on silence. Without this a dead colleague's pointer sits on the board indefinitely and
     * suggests someone is working who is not.
     */
    public boolean isStale(long now, long ttlMillis) {
        return now - seenAt > ttlMillis;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PeerPresence && user.equals(((PeerPresence) other).user);
    }

    @Override
    public int hashCode() {
        return user.hashCode();
    }

    @Override
    public String toString() {
        return user + "@(" + (int) x + "," + (int) y + ")";
    }
}
