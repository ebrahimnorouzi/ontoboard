package de.fizkarlsruhe.ise.ontoboard.collab;

/**
 * How this user collaborates, and with what.
 *
 * <p>OntoBoard ships no server. A team runs its own and each member points the plugin at it;
 * a team without one collaborates through git instead, which is how most published
 * ontologies are actually built. Both are first-class - {@link Mode#GIT} is not a
 * degraded fallback, it is the ODK release workflow.
 *
 * <p>The mode is <em>derived</em> from what has been configured rather than asked as a
 * separate question, so nothing is required to start and the plugin is fully usable with no
 * settings at all. The important consequence is that callers must check {@link #getMode()}
 * before showing any live-collaboration affordance: a cursor overlay that is present but
 * silently doing nothing would let someone believe they are collaborating when they are not,
 * and that is how work gets lost.
 */
public final class CollabSettings {

    /** What collaboration is available with the current configuration. */
    public enum Mode {
        /** A server is configured: live sync, remote cursors, comments. */
        LIVE,
        /** No server: share through commit, pull and push. No cursors, no live sync. */
        GIT
    }

    private final String bridgeUrl;
    private final String board;
    /** Not final and not persisted - see forOntology. */
    private String ontologyIri = "";
    private final String token;
    private final String displayName;
    private final String colour;

    public CollabSettings(String bridgeUrl, String board, String token, String displayName,
            String colour) {
        this.bridgeUrl = trim(bridgeUrl);
        this.board = trim(board);
        this.token = trim(token);
        this.displayName = trim(displayName);
        this.colour = trim(colour).isEmpty() ? "#4A90D9" : trim(colour);
    }

    /** Settings with nothing configured, i.e. {@link Mode#GIT}. */
    public static CollabSettings offline() {
        return new CollabSettings("", "", "", "", "");
    }

    /**
     * Live only when everything a connection needs is present. Deliberately strict: a
     * half-configured server would fail at connect time and look like an outage rather than
     * a missing setting.
     */
    public Mode getMode() {
        return bridgeUrl.isEmpty() || board.isEmpty() || token.isEmpty() ? Mode.GIT : Mode.LIVE;
    }

    public boolean isLive() {
        return getMode() == Mode.LIVE;
    }

    /**
     * Why live mode is unavailable, in words a user can act on, or {@code null} when it is
     * available. Shown instead of a generic "not connected", which tells nobody anything.
     */
    public String explainWhyNotLive() {
        if (isLive()) {
            return null;
        }
        StringBuilder missing = new StringBuilder();
        if (bridgeUrl.isEmpty()) {
            missing.append("server address");
        }
        if (board.isEmpty()) {
            append(missing, "board id");
        }
        if (token.isEmpty()) {
            append(missing, "access token");
        }
        return "Working in git mode - live sync and cursors are off because no "
                + missing + " is configured. Set one up in Tools > Collaboration Settings, "
                + "or keep using commit and push to share your work.";
    }

    /**
     * @throws IllegalArgumentException if the address is present but unusable, so a typo is
     *     reported when it is entered rather than as a silent failure to connect
     */
    public void validate() {
        if (getMode() == Mode.GIT) {
            return;
        }
        if (!bridgeUrl.startsWith("ws://") && !bridgeUrl.startsWith("wss://")) {
            throw new IllegalArgumentException(
                    "Server address must start with ws:// or wss:// - got '" + bridgeUrl
                            + "'. The plugin connects to the JSON bridge (default port 1235), "
                            + "not to the browser endpoint on 1234.");
        }
        if (isBrowserEndpoint(bridgeUrl)) {
            // docs/collaboration.md has promised this refusal in two places since the feature
            // shipped, and nothing checked the port - so the documented protection did not exist.
            // It is worth having rather than deleting, because the failure it prevents is the worst
            // kind: a Yjs endpoint accepts the socket and then never answers a word of JSON, so the
            // plugin sits on "Connecting..." indefinitely with nothing in any log to explain it.
            throw new IllegalArgumentException(
                    "Port " + HOCUSPOCUS_PORT + " is the browser endpoint, which speaks Yjs rather "
                            + "than JSON. It would accept this connection and then never answer, so "
                            + "the plugin would wait indefinitely. Use the JSON bridge on port "
                            + BRIDGE_PORT + " - the same address with " + BRIDGE_PORT + " in place "
                            + "of " + HOCUSPOCUS_PORT + ".");
        }
    }

    /** The Yjs endpoint's conventional port, which the plugin cannot speak to. */
    private static final int HOCUSPOCUS_PORT = 1234;

    /** The JSON bridge's conventional port, which it can. */
    private static final int BRIDGE_PORT = 1235;

    /**
     * Whether an address points at the Yjs endpoint rather than the JSON bridge.
     *
     * <p>Matched on the port alone, and only the conventional one. A team that has moved the bridge
     * onto 1234 deliberately is not helped by being refused, but that arrangement requires moving
     * the browser endpoint too and is rare enough not to design for; a typed 4 in place of a 5 is
     * not.
     */
    private static boolean isBrowserEndpoint(String address) {
        try {
            return new java.net.URI(address).getPort() == HOCUSPOCUS_PORT;
        } catch (java.net.URISyntaxException notAnAddress) {
            // Left to fail at connect time with its own message; guessing at a malformed address is
            // how a validator starts refusing things that would have worked.
            return false;
        }
    }

    public String getBridgeUrl() {
        return bridgeUrl;
    }

    public String getBoard() {
        return board;
    }

    /**
     * The ontology being edited, for telling peers what this end is working on.
     *
     * <p>Context rather than configuration, which is why it is not persisted: it changes whenever
     * the active ontology changes, and a stored copy would go stale the first time somebody
     * switched file.
     */
    public String getOntologyIri() {
        return ontologyIri;
    }

    /** The same settings, for a particular ontology. */
    public CollabSettings forOntology(String iri) {
        CollabSettings copy = new CollabSettings(bridgeUrl, board, token, displayName, colour);
        copy.ontologyIri = iri == null ? "" : iri.trim();
        return copy;
    }

    public String getToken() {
        return token;
    }

    /** Falls back to the OS user, so a peer is never shown as blank to others. */
    public String getDisplayName() {
        if (!displayName.isEmpty()) {
            return displayName;
        }
        String osUser = System.getProperty("user.name");
        return osUser == null || osUser.trim().isEmpty() ? "anonymous" : osUser.trim();
    }

    public String getColour() {
        return colour;
    }

    private static void append(StringBuilder target, String value) {
        if (target.length() > 0) {
            target.append(", ");
        }
        target.append(value);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
