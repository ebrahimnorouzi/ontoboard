package de.fizkarlsruhe.ise.ontoboard.collab;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which peer cursors to draw, and where.
 *
 * <p>Separated from the painting so the decisions are testable: the painting is a dozen lines of
 * Swing that cannot be exercised headlessly, while the rules about staleness, self-exclusion and
 * draw order are the parts that have observable consequences and can go wrong quietly.
 *
 * <p>Three of those rules matter more than they look:
 *
 * <ul>
 *   <li><b>We are in our own peer list.</b> The bridge sends every peer on the board including
 *       the sender, so without filtering, a second cursor would follow the user's own mouse a
 *       frame behind it. That looks like a bug in the canvas rather than a bug in the filter.
 *   <li><b>Order has to be stable.</b> A peer list arrives on every presence update from anyone,
 *       so ordering by anything unstable makes overlapping cursors flicker between paints.
 *   <li><b>Staleness is judged locally.</b> The bridge already expires peers after ten seconds,
 *       but between the last update and the next one a departed peer's cursor would sit on the
 *       canvas. Filtering again here means it fades on our own clock rather than waiting for the
 *       server to tell us twice.
 * </ul>
 */
public final class PeerCursors {

    /**
     * How long a cursor survives without an update.
     *
     * <p>Matched to {@code PRESENCE_TTL_MS} in {@code collab/bridge.mjs}: judging staleness more
     * strictly than the server would hide a peer the server still considers present.
     */
    public static final long TTL_MILLIS = 10000;

    /** Used when a peer's colour is missing or unreadable; matches PeerPresence's own default. */
    public static final Color FALLBACK_COLOUR = new Color(0x4A, 0x90, 0xD9);

    /** One cursor to draw, in graph space. */
    public static final class Marker {
        private final String user;
        private final Color colour;
        private final double x;
        private final double y;
        private final String selection;
        private final String editing;

        Marker(String user, Color colour, double x, double y, String selection) {
            this(user, colour, x, y, selection, null);
        }

        Marker(String user, Color colour, double x, double y, String selection, String editing) {
            this.user = user;
            this.colour = colour;
            this.x = x;
            this.y = y;
            this.selection = selection;
            this.editing = editing == null || editing.trim().isEmpty() ? null : editing.trim();
        }

        /** The name to draw beside the cursor. Never null or blank. */
        public String getUser() {
            return user;
        }

        public Color getColour() {
            return colour;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        /** IRI this peer has selected, or null. Lets the canvas ring the node they are on. */
        public String getSelection() {
            return selection;
        }

        /**
         * IRI this peer is editing, or null - which is not the same as the one they selected.
         *
         * <p>Selection changes with every click and means nothing; this is a term they have
         * actually changed an axiom on within the last few minutes, and is the one worth
         * warning somebody about before they start on it too. See {@code EditingClaim}.
         */
        public String getEditing() {
            return editing;
        }
    }

    /** Keyed by user so a new list replaces rather than duplicates, and insertion order is kept. */
    private final Map<String, PeerPresence> byUser = new LinkedHashMap<String, PeerPresence>();
    private volatile String self = "";

    /** The name the server authenticated us as, so our own cursor is not drawn twice. */
    public synchronized void setSelf(String user) {
        this.self = user == null ? "" : user;
    }

    /**
     * Replaces the known peers.
     *
     * <p>A replacement rather than a merge: the bridge sends the whole live list every time, so a
     * peer that has gone is absent from it, and merging would keep them until their own entry
     * timed out.
     */
    public synchronized void update(List<PeerPresence> peers) {
        byUser.clear();
        if (peers == null) {
            return;
        }
        for (PeerPresence peer : peers) {
            if (peer != null && peer.getUser() != null && !peer.getUser().trim().isEmpty()) {
                byUser.put(peer.getUser(), peer);
            }
        }
    }

    /** Forgets every peer, for a disconnection - stale cursors must not linger on a dead session. */
    public synchronized void clear() {
        byUser.clear();
    }

    /** Cursors worth drawing at {@code now}: not ours, not stale, ordered stably by name. */
    public synchronized List<Marker> visibleAt(long now) {
        List<Marker> markers = new ArrayList<Marker>();
        for (PeerPresence peer : byUser.values()) {
            if (peer.getUser().equals(self)) {
                continue;
            }
            if (peer.isStale(now, TTL_MILLIS)) {
                continue;
            }
            markers.add(new Marker(peer.getUser(), parseColour(peer.getColour()),
                    peer.getX(), peer.getY(), peer.getSelection(), peer.getEditing()));
        }
        Collections.sort(markers, new Comparator<Marker>() {
            @Override
            public int compare(Marker left, Marker right) {
                return left.getUser().compareTo(right.getUser());
            }
        });
        return markers;
    }

    /** How many peers are known, including ourselves - for a "3 people here" indicator. */
    public synchronized int countAt(long now) {
        int alive = 0;
        for (PeerPresence peer : byUser.values()) {
            if (!peer.isStale(now, TTL_MILLIS)) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * Reads a peer's colour string.
     *
     * <p>The colour comes from another client and is never validated on the way through the
     * bridge, so anything could arrive. A bad value falls back rather than throwing: an
     * unreadable colour is not a reason to stop drawing someone's cursor, and an exception here
     * would happen inside a paint.
     */
    public static Color parseColour(String value) {
        if (value == null) {
            return FALLBACK_COLOUR;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("#") || (trimmed.length() != 7 && trimmed.length() != 4)) {
            return FALLBACK_COLOUR;
        }
        try {
            return Color.decode(trimmed.length() == 4 ? expandShortHex(trimmed) : trimmed);
        } catch (NumberFormatException notAColour) {
            return FALLBACK_COLOUR;
        }
    }

    /** {@code #abc} to {@code #aabbcc}, since Color.decode does not accept the short form. */
    private static String expandShortHex(String shortForm) {
        StringBuilder expanded = new StringBuilder("#");
        for (int i = 1; i < shortForm.length(); i++) {
            expanded.append(shortForm.charAt(i)).append(shortForm.charAt(i));
        }
        return expanded.toString();
    }
}
