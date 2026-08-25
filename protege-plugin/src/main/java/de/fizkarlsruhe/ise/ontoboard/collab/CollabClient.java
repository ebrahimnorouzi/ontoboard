package de.fizkarlsruhe.ise.ontoboard.collab;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin's connection to a team's collaboration server.
 *
 * <p>Talks to the JSON bridge described in {@code collab/bridge.mjs}, not to the Yjs endpoint web
 * clients use. Everything protocol-shaped lives in {@link CollabMessages}; this class owns the
 * socket, the connection lifecycle and the two decisions that make an unreliable network
 * survivable.
 *
 * <p><b>Refused is not the same as dropped.</b> The bridge answers a bad token, an unknown
 * message or an operation sent before {@code hello} by sending {@code {t:"error"}} and closing.
 * Retrying any of those can never succeed, so a close preceded by an error message stops the
 * client and reports why, while a close with no explanation is treated as an outage and retried
 * with exponential backoff. Without that distinction an expired token becomes a reconnect loop
 * hammering someone's server.
 *
 * <p><b>An outage does not lose edits.</b> Operations published while disconnected are held in a
 * bounded queue and flushed on reconnect. They keep their original timestamps, so the web
 * application's merge engine still orders them correctly against whatever happened meanwhile. The
 * queue is bounded rather than unlimited because an editor that grows a list forever during a long
 * outage is a leak, and overflow is reported rather than silently discarded.
 *
 * <p>Callbacks are handed to the {@link Executor} given at construction rather than invoked on the
 * socket's reader thread. The view passes {@code SwingUtilities::invokeLater}; tests pass
 * {@code Runnable::run}. Making the hand-off explicit is what keeps this class testable without a
 * Swing environment, and stops a listener touching Protege's model off the EDT.
 */
public final class CollabClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollabClient.class);

    /**
     * How many operations may wait out an outage. About a hundred edits is far more than anyone
     * makes during a brief disconnection, and well short of a memory problem.
     */
    static final int PENDING_LIMIT = 200;

    /**
     * How many operation ids are remembered for deduplication. Ids are UUIDs, so this is a few
     * tens of kilobytes, and it only has to outlast a reconnect that redelivers recent work.
     */
    static final int SEEN_LIMIT = 2000;

    /**
     * How often the last cursor position is resent.
     *
     * <p>{@code PRESENCE_TTL_MS} in bridge.mjs is 10 seconds, and the bridge drops a peer that
     * goes quiet for longer. Without a heartbeat a cursor would vanish whenever someone stopped
     * moving the mouse, which reads as the other person having left.
     */
    static final long PRESENCE_INTERVAL_MILLIS = 3000;

    static final long BASE_BACKOFF_MILLIS = 1000;
    static final long MAX_BACKOFF_MILLIS = 30000;

    /** What the client tells its owner. Every method arrives on the configured executor. */
    public interface Listener {

        /** The server accepted us. {@code user} is the name it authenticated, not the one asked for. */
        void onConnected(String user);

        /** Someone else changed the ontology. Already deduplicated. */
        void onOperation(OntologyOperation operation);

        /** The current peer list, replacing any previous one. */
        void onPeers(List<PeerPresence> peers);

        /**
         * The server will not have us, and retrying cannot help - a bad token, a rejected
         * message, or settings that are not complete enough to connect. The client has stopped.
         */
        void onRefused(String reason);

        /**
         * The connection dropped and will be retried. {@code attempt} counts from 1, and
         * {@code retryInMillis} says when, so the UI can show something honest rather than a
         * spinner.
         */
        void onDisconnected(String reason, int attempt, long retryInMillis);
    }

    private final CollabSettings settings;
    private final Executor dispatcher;
    private final Listener listener;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;
    private final long presenceIntervalMillis;

    private final Object lock = new Object();
    private final Deque<OntologyOperation> pending = new ArrayDeque<OntologyOperation>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<String>();

    private ScheduledExecutorService scheduler;
    private WebSocketClient socket;
    private boolean running;
    private boolean connected;
    private String refusedReason;
    private int attempt;
    private String lastPresenceFrame;
    private int droppedWhileOffline;

    public CollabClient(CollabSettings settings, Executor dispatcher, Listener listener) {
        this(settings, dispatcher, listener, BASE_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS,
                PRESENCE_INTERVAL_MILLIS);
    }

    /** Timings are parameters so a test does not have to wait out a real backoff. */
    CollabClient(CollabSettings settings, Executor dispatcher, Listener listener,
            long baseBackoffMillis, long maxBackoffMillis, long presenceIntervalMillis) {
        if (settings == null || dispatcher == null || listener == null) {
            throw new IllegalArgumentException(
                    "settings, dispatcher and listener are all required");
        }
        this.settings = settings;
        this.dispatcher = dispatcher;
        this.listener = listener;
        this.baseBackoffMillis = baseBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.presenceIntervalMillis = presenceIntervalMillis;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Connects, and keeps reconnecting until {@link #stop()} or a refusal.
     *
     * <p>Returns immediately. Incomplete settings are reported through
     * {@link Listener#onRefused} rather than thrown, because "you have not filled in a token" is
     * the same kind of news as "your token is wrong" and belongs in the same place in the UI.
     */
    public void start() {
        if (!settings.isLive()) {
            refuse(settings.explainWhyNotLive());
            return;
        }
        try {
            settings.validate();
        } catch (IllegalArgumentException badAddress) {
            refuse(badAddress.getMessage());
            return;
        }
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            refusedReason = null;
            attempt = 0;
            // Daemon threads: a live scheduler thread would keep Protege's JVM from exiting
            // after the window closed, which looks like the application hanging on quit.
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ontoboard-collab");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::beatPresence, presenceIntervalMillis,
                    presenceIntervalMillis, TimeUnit.MILLISECONDS);
        }
        openSocket();
    }

    /** Disconnects and stops retrying. Safe to call when never started, or twice. */
    public void stop() {
        WebSocketClient closing;
        ScheduledExecutorService stopping;
        synchronized (lock) {
            running = false;
            connected = false;
            closing = socket;
            socket = null;
            stopping = scheduler;
            scheduler = null;
        }
        if (closing != null) {
            try {
                closing.close();
            } catch (RuntimeException ignored) {
                // A socket that is already gone must not stop the view from closing.
                LOGGER.debug("OntoBoard: collaboration socket already closed", ignored);
            }
        }
        if (stopping != null) {
            stopping.shutdownNow();
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return connected;
        }
    }

    /** Why the server refused us, or null while connected or retrying. */
    public String getRefusedReason() {
        synchronized (lock) {
            return refusedReason;
        }
    }

    /** Operations waiting for the connection to come back. */
    public int getPendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Operations discarded because the outage outlasted the queue. */
    public int getDroppedWhileOfflineCount() {
        synchronized (lock) {
            return droppedWhileOffline;
        }
    }

    // ------------------------------------------------------------------ sending

    /**
     * Publishes a local change, or queues it until the connection returns.
     *
     * <p>The id is recorded as seen before sending. The bridge filters an operation out by
     * authenticated user before it ever comes back, so this is a second guard rather than the
     * only one - and it is the guard that matters when the same account is signed in twice.
     */
    public void publish(OntologyOperation operation) {
        if (operation == null) {
            return;
        }
        String frame;
        synchronized (lock) {
            remember(operation.getId());
            if (!connected || socket == null) {
                if (pending.size() >= PENDING_LIMIT) {
                    // Oldest first: the newest edits are the ones most likely still to matter.
                    pending.removeFirst();
                    droppedWhileOffline++;
                }
                pending.addLast(operation);
                return;
            }
            frame = CollabMessages.operation(operation);
        }
        send(frame);
    }

    /**
     * Publishes the cursor position, in graph space.
     *
     * <p>Also becomes the heartbeat: the same frame is resent every few seconds so the bridge does
     * not expire the cursor while its owner sits still.
     */
    public void publishPresence(double x, double y, String selection) {
        String frame = CollabMessages.presence(settings.getDisplayName(), settings.getColour(),
                x, y, selection);
        synchronized (lock) {
            lastPresenceFrame = frame;
            if (!connected || socket == null) {
                // A stale cursor is worth nothing, so presence is never queued.
                return;
            }
        }
        send(frame);
    }

    /**
     * True when this operation has been seen before.
     *
     * <p>Exposed so the view can double-check an operation it is about to apply. Redelivery is
     * normal after a reconnect - the shared log is replayed - and applying the same change twice
     * would show as a duplicated axiom or a spurious entry in Protege's undo stack.
     */
    public boolean hasSeen(String operationId) {
        synchronized (lock) {
            return seen.contains(operationId);
        }
    }

    // ------------------------------------------------------------------ internals

    private void openSocket() {
        WebSocketClient created;
        try {
            created = new WebSocketClient(new URI(settings.getBridgeUrl())) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send(CollabMessages.hello(settings));
                }

                @Override
                public void onMessage(String message) {
                    handle(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    handleClose(reason == null || reason.isEmpty()
                            ? "connection closed (code " + code + ")" : reason);
                }

                @Override
                public void onError(Exception failure) {
                    // Java-WebSocket follows an error with a close, so the retry is scheduled
                    // there. Recording the cause here is what makes the close message useful.
                    LOGGER.debug("OntoBoard: collaboration socket error", failure);
                }
            };
        } catch (Exception badUri) {
            refuse("the server address could not be used: " + badUri.getMessage());
            return;
        }
        // Java-WebSocket's own lost-connection detection, so a silently dead TCP connection is
        // noticed rather than looking like a session where nobody is talking.
        created.setConnectionLostTimeout((int) TimeUnit.MILLISECONDS.toSeconds(
                Math.max(maxBackoffMillis, 60000)));
        synchronized (lock) {
            if (!running) {
                return;
            }
            socket = created;
        }
        created.connect();
    }

    private void handle(String raw) {
        CollabMessages.Incoming incoming = CollabMessages.decode(raw, System.currentTimeMillis());
        switch (incoming.getKind()) {
            case WELCOME: {
                List<OntologyOperation> flush;
                synchronized (lock) {
                    connected = true;
                    attempt = 0;
                    flush = new ArrayList<OntologyOperation>(pending);
                    pending.clear();
                }
                final String user = incoming.getUser();
                final List<PeerPresence> peers = incoming.getPeers();
                dispatch(() -> {
                    listener.onConnected(user);
                    if (peers != null && !peers.isEmpty()) {
                        listener.onPeers(peers);
                    }
                });
                // After the welcome, not before: the bridge closes a socket that speaks first.
                for (OntologyOperation queued : flush) {
                    send(CollabMessages.operation(queued));
                }
                String presence;
                synchronized (lock) {
                    presence = lastPresenceFrame;
                }
                if (presence != null) {
                    send(presence);
                }
                return;
            }
            case OPERATION: {
                OntologyOperation operation = incoming.getOperation();
                synchronized (lock) {
                    if (seen.contains(operation.getId())) {
                        // Redelivered, or our own work echoed by a server that does not filter.
                        return;
                    }
                    remember(operation.getId());
                }
                dispatch(() -> listener.onOperation(operation));
                return;
            }
            case PEERS: {
                List<PeerPresence> peers = incoming.getPeers();
                dispatch(() -> listener.onPeers(peers));
                return;
            }
            case ERROR:
                // The bridge closes straight after this, and reconnecting would meet the same
                // refusal. Recording it here is what stops handleClose scheduling a retry.
                refuse(incoming.getMessage() == null
                        ? "the server refused the connection without saying why"
                        : incoming.getMessage());
                return;
            case UNKNOWN:
            default:
                // A message this version does not understand. Ignored rather than treated as a
                // failure: a newer server adding a message type must not break an older plugin.
                LOGGER.debug("OntoBoard: ignoring an unrecognised collaboration message");
        }
    }

    private void handleClose(String reason) {
        String refused;
        long delay;
        int thisAttempt;
        ScheduledExecutorService retryOn;
        synchronized (lock) {
            connected = false;
            socket = null;
            refused = refusedReason;
            if (!running || refused != null) {
                return;
            }
            attempt++;
            thisAttempt = attempt;
            delay = backoffFor(attempt);
            retryOn = scheduler;
        }
        final String message = reason;
        final int attemptNumber = thisAttempt;
        final long retryIn = delay;
        dispatch(() -> listener.onDisconnected(message, attemptNumber, retryIn));
        if (retryOn != null && !retryOn.isShutdown()) {
            retryOn.schedule(this::openSocket, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Exponential backoff, capped.
     *
     * <p>Doubling means a server that is down for an hour is polled a handful of times rather than
     * thousands, and the cap means a connection that comes back is picked up within half a minute
     * rather than after a wait that has grown to hours.
     */
    long backoffFor(int attemptNumber) {
        long delay = baseBackoffMillis;
        for (int i = 1; i < attemptNumber && delay < maxBackoffMillis; i++) {
            delay *= 2;
        }
        return Math.min(delay, maxBackoffMillis);
    }

    private void beatPresence() {
        String frame;
        synchronized (lock) {
            if (!connected || socket == null || lastPresenceFrame == null) {
                return;
            }
            frame = lastPresenceFrame;
        }
        send(frame);
    }

    private void send(String frame) {
        WebSocketClient current;
        synchronized (lock) {
            current = socket;
        }
        if (current == null) {
            return;
        }
        try {
            current.send(frame);
        } catch (RuntimeException notOpen) {
            // The socket closed between the check and the send. onClose schedules the retry, and
            // the operation is already in `seen`, so nothing here needs to escalate.
            LOGGER.debug("OntoBoard: collaboration frame not sent; the socket had closed",
                    notOpen);
        }
    }

    /** Stops the client and reports why, without a retry. */
    private void refuse(String reason) {
        WebSocketClient closing;
        ScheduledExecutorService stopping;
        synchronized (lock) {
            refusedReason = reason;
            connected = false;
            running = false;
            closing = socket;
            socket = null;
            stopping = scheduler;
            scheduler = null;
        }
        dispatch(() -> listener.onRefused(reason));
        if (closing != null) {
            try {
                closing.close();
            } catch (RuntimeException ignored) {
                LOGGER.debug("OntoBoard: socket already closed while refusing", ignored);
            }
        }
        if (stopping != null) {
            stopping.shutdown();
        }
    }

    /** Caller holds {@link #lock}. */
    private void remember(String operationId) {
        if (operationId == null) {
            return;
        }
        seen.add(operationId);
        if (seen.size() > SEEN_LIMIT) {
            Iterator<String> oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void dispatch(Runnable callback) {
        try {
            dispatcher.execute(callback);
        } catch (RuntimeException rejected) {
            // The view is going away, so its executor no longer accepts work. Nothing to do, and
            // certainly nothing to throw back into a socket reader thread.
            LOGGER.debug("OntoBoard: collaboration callback not delivered", rejected);
        }
    }
}
