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

    /** Why the server refused us, or null while connected or retrying. */
    String getRefusedReason();

    /** Operations waiting for the connection to come back. */
    int getPendingCount();

    /** Operations discarded because the outage outlasted the queue. */
    int getDroppedWhileOfflineCount();
}
