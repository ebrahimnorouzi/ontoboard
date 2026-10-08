package de.fizkarlsruhe.ise.ontoboard.collab;

/**
 * What {@link CollabSession} needs from a connection.
 *
 * <p>Narrow on purpose. {@link CollabClient} is the only implementation that ships, and it is
 * final because a half-overridden socket client is a bad idea; this interface is how a test drives
 * a session without one. That matters because the session carries the loop guard - the piece whose
 * failure amplifies across every client in a board - and testing it needs the ability to record
 * what would have gone out rather than to open a port.
 */
public interface CollabTransport {

    void start();

    void stop();

    boolean isConnected();

    /** Sends an operation, or holds it until the connection returns. */
    void publish(OntologyOperation operation);

    /** Sends the cursor position, in graph space. Never queued. */
    void publishPresence(double x, double y, String selection);

    /**
     * Records that this user has just changed an axiom on a term.
     *
     * <p>A default rather than a required method, because the claim is advisory: a transport
     * that does not carry it - an older one, or a test fake - should still work, and showing no
     * badge is the right behaviour when there is nothing to show. See {@code EditingClaim}.
     */
    default void noteEdited(String iri) {
        // Nothing by default.
    }

    /** Why the server refused us, or null while connected or retrying. */
    String getRefusedReason();

    /** Operations waiting for the connection to come back. */
    int getPendingCount();

    /** Operations discarded because the outage outlasted the queue. */
    int getDroppedWhileOfflineCount();
}
