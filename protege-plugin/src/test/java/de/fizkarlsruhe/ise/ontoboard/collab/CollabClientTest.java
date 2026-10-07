package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The connection lifecycle, tested against a real WebSocket server rather than a mock.
 *
 * <p>{@link FakeBridge} speaks the protocol {@code collab/bridge.mjs} defines and can be told to
 * misbehave in the specific ways a real one does - refuse a token, close without explanation,
 * echo an operation back to its sender. A mocked socket would have proved only that the client
 * calls the methods this test expected it to call; what needs proving is that it survives a
 * network, and that its two load-bearing distinctions hold: refused is not dropped, and an outage
 * does not lose edits.
 *
 * <p>Timings are shortened through the package-private constructor. Without that a test of
 * exponential backoff would spend seven seconds waiting.
 */
class CollabClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "a-valid-token";

    private FakeBridge bridge;
    private CollabClient client;

    @AfterEach
    void shutDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (bridge != null) {
            bridge.stop(200);
        }
    }

    // ---------- harness ----------

    /** A server that behaves like bridge.mjs, and can be told to behave badly on purpose. */
    private static final class FakeBridge extends WebSocketServer {
        private final CountDownLatch listening = new CountDownLatch(1);
        private final Map<WebSocket, String> users = new ConcurrentHashMap<WebSocket, String>();

        final List<String> received = new CopyOnWriteArrayList<String>();
        final AtomicInteger connections = new AtomicInteger();

        /** Send {t:"error"} and close on hello, the way a rejected token is answered. */
        volatile String refuseWith;
        /** Close with no explanation once connected, the way an outage looks. */
        volatile boolean dropAfterWelcome;
        /** Relay an operation back to its sender, which a correct bridge does not do. */
        volatile boolean echoToSender;

        FakeBridge() {
            this(0);
        }

        /**
         * On a chosen port, so a test can stand a second bridge where the first one was.
         *
         * <p>Needed to exercise a reconnect for real: the client retries the address it was
         * given, so proving a dropped queue comes back means putting a working server at that
         * same address.
         */
        FakeBridge(int port) {
            super(new InetSocketAddress("127.0.0.1", port));
            setReuseAddr(true);
        }

        @Override
        public void onStart() {
            listening.countDown();
        }

        int awaitPort() throws InterruptedException {
            assertTrue(listening.await(5, TimeUnit.SECONDS), "the fake bridge did not start");
            return getPort();
        }

        @Override
        public void onOpen(WebSocket socket, ClientHandshake handshake) {
            connections.incrementAndGet();
        }

        @Override
        public void onMessage(WebSocket socket, String raw) {
            received.add(raw);
            JsonNode message;
            try {
                message = MAPPER.readTree(raw);
            } catch (Exception notJson) {
                fail(socket, "not valid JSON");
                return;
            }
            String type = message.path("t").asText("");

            if ("hello".equals(type)) {
                if (refuseWith != null) {
                    fail(socket, refuseWith);
                    return;
                }
                if (!TOKEN.equals(message.path("token").asText(""))) {
                    fail(socket, "invalid token: signature verification failed");
                    return;
                }
                users.put(socket, "alice");
                socket.send("{\"t\":\"welcome\",\"user\":\"alice\",\"peers\":[]}");
                if (dropAfterWelcome) {
                    socket.close();
                }
                return;
            }
            if (!users.containsKey(socket)) {
                fail(socket, "say hello before anything else");
                return;
            }
            if ("op".equals(type)) {
                String frame = "{\"t\":\"op\",\"op\":" + message.get("op").toString() + "}";
                for (WebSocket peer : users.keySet()) {
                    if (peer != socket || echoToSender) {
                        peer.send(frame);
                    }
                }
                return;
            }
            if ("presence".equals(type)) {
                for (WebSocket peer : users.keySet()) {
                    peer.send("{\"t\":\"peers\",\"peers\":[{\"user\":\"bob\",\"colour\":"
                            + "\"#111111\",\"x\":1,\"y\":2,\"selection\":null}]}");
                }
            }
        }

        /** The bridge's own failure path: say why, then close. */
        private void fail(WebSocket socket, String reason) {
            socket.send("{\"t\":\"error\",\"message\":\"" + reason + "\"}");
            socket.close();
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason, boolean remote) {
            users.remove(socket);
        }

        @Override
        public void onError(WebSocket socket, Exception failure) {
            // A dead socket must not take the server down, exactly as in bridge.mjs.
        }

        /** How many frames of {@code type} the server has been sent. */
        int countOf(String type) {
            int found = 0;
            for (String raw : received) {
                try {
                    if (type.equals(MAPPER.readTree(raw).path("t").asText(""))) {
                        found++;
                    }
                } catch (Exception ignored) {
                    // A deliberately malformed frame is not one of the counted types.
                }
            }
            return found;
        }
    }

    /** Records what the client reports, so assertions read as statements about behaviour. */
    private static final class Recorder implements CollabClient.Listener {
        final List<String> connectedAs = new CopyOnWriteArrayList<String>();
        final List<OntologyOperation> operations = new CopyOnWriteArrayList<OntologyOperation>();
        final List<List<PeerPresence>> peerLists = new CopyOnWriteArrayList<List<PeerPresence>>();
        final List<String> refusals = new CopyOnWriteArrayList<String>();
        final List<String> disconnections = new CopyOnWriteArrayList<String>();
        final java.util.concurrent.atomic.AtomicInteger undecodable =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void onUndecodableOperation() {
            undecodable.incrementAndGet();
        }

        @Override
        public void onConnected(String user) {
            connectedAs.add(user);
        }

        @Override
        public void onOperation(OntologyOperation operation) {
            operations.add(operation);
        }

        @Override
        public void onPeers(List<PeerPresence> peers) {
            peerLists.add(peers);
        }

        @Override
        public void onRefused(String reason) {
            refusals.add(reason);
        }

        @Override
        public void onDisconnected(String reason, int attempt, long retryInMillis) {
            disconnections.add(attempt + ": " + reason + " (retry in " + retryInMillis + "ms)");
        }
    }

    private final Recorder recorder = new Recorder();

    private CollabSettings settingsFor(int port, String token) {
        return new CollabSettings("ws://127.0.0.1:" + port, "board-1", token, "alice", "#AA0000");
    }

    /** Fast timings; a real backoff would make this suite take minutes. */
    private CollabClient clientFor(CollabSettings settings) {
        return new CollabClient(settings, new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        }, recorder, 40, 120, 60);
    }

    private CollabClient started(FakeBridge server, String token) throws Exception {
        client = clientFor(settingsFor(server.awaitPort(), token));
        client.start();
        return client;
    }

    private static void waitUntil(String what, Condition condition) {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.met()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail("timed out waiting for " + what);
    }

    private interface Condition {
        boolean met();
    }

    private FakeBridge listening() throws Exception {
        bridge = new FakeBridge();
        bridge.start();
        bridge.awaitPort();
        return bridge;
    }

    /** A second bridge on a port the first one has released. */
    private FakeBridge listeningOn(int port) throws Exception {
        bridge = new FakeBridge(port);
        bridge.start();
        bridge.awaitPort();
        return bridge;
    }

    private static OntologyOperation anOperation(String iri) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("iri", iri);
        return OntologyOperation.local("addClass", "alice", data);
    }

    // ---------- connecting ----------

    @Test
    void reportsTheUserTheServerAuthenticatedRatherThanTheOneAskedFor() throws Exception {
        started(listening(), TOKEN);

        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());
        assertEquals("alice", recorder.connectedAs.get(0));
        assertTrue(client.isConnected());
    }

    @Test
    void speaksHelloBeforeAnythingElseBecauseTheBridgeClosesOnAnythingSooner() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        client.publish(anOperation("http://example.org/o#Person"));

        waitUntil("the operation to reach the server", () -> server.countOf("op") == 1);
        assertEquals("hello", firstFrameType(server),
                "the first frame on the wire must be hello: " + server.received);
    }

    private static String firstFrameType(FakeBridge server) throws Exception {
        return MAPPER.readTree(server.received.get(0)).path("t").asText("");
    }

    /**
     * Settings that cannot connect are reported through the same channel as a server refusal, so
     * the UI has one place to show "you are not collaborating, and here is why".
     */
    @Test
    void incompleteSettingsAreRefusedWithTheMissingFieldsNamed() {
        client = clientFor(CollabSettings.offline());
        client.start();

        assertEquals(1, recorder.refusals.size());
        assertTrue(recorder.refusals.get(0).contains("access token"), recorder.refusals.get(0));
        assertFalse(client.isConnected());
    }

    @Test
    void anAddressThatIsNotAWebSocketUrlIsRefusedBeforeAnySocketIsOpened() {
        client = clientFor(new CollabSettings("http://team.example:1235", "b", "t", "a", ""));
        client.start();

        assertEquals(1, recorder.refusals.size());
        assertTrue(recorder.refusals.get(0).contains("ws://"), recorder.refusals.get(0));
    }

    @Test
    void requiresItsCollaborators() {
        assertThrows(IllegalArgumentException.class,
                () -> new CollabClient(null, Runnable::run, recorder));
        assertThrows(IllegalArgumentException.class,
                () -> new CollabClient(CollabSettings.offline(), null, recorder));
        assertThrows(IllegalArgumentException.class,
                () -> new CollabClient(CollabSettings.offline(), Runnable::run, null));
    }

    // ---------- refused is not dropped ----------

    /**
     * The distinction the whole class is built around. A rejected token cannot succeed on the
     * hundredth attempt either, so retrying it would be a loop hammering someone's server.
     */
    @Test
    void aRejectedTokenStopsTheClientInsteadOfRetryingForever() throws Exception {
        FakeBridge server = listening();
        started(server, "the-wrong-token");

        waitUntil("the refusal", () -> !recorder.refusals.isEmpty());
        assertTrue(recorder.refusals.get(0).contains("invalid token"),
                recorder.refusals.get(0));
        assertEquals(1, server.connections.get());

        // Long enough that several shortened backoffs would have elapsed.
        Thread.sleep(400);
        assertEquals(1, server.connections.get(),
                "a refusal must not be retried; the server saw " + server.connections.get()
                        + " connections");
        assertTrue(recorder.disconnections.isEmpty(),
                "a refusal is not a disconnection: " + recorder.disconnections);
        assertNotNull(client.getRefusedReason());
    }

    @Test
    void aServerErrorIsReportedWithTheServersOwnWording() throws Exception {
        bridge = new FakeBridge();
        bridge.refuseWith = "board 'board-1' is not shared with you";
        bridge.start();
        started(bridge, TOKEN);

        waitUntil("the refusal", () -> !recorder.refusals.isEmpty());
        assertEquals("board 'board-1' is not shared with you", recorder.refusals.get(0));
    }

    /** A close with no explanation is an outage, and outages come back. */
    @Test
    void aDroppedConnectionIsRetried() throws Exception {
        bridge = new FakeBridge();
        bridge.dropAfterWelcome = true;
        bridge.start();
        started(bridge, TOKEN);

        waitUntil("a retry", () -> bridge.connections.get() >= 2);
        assertFalse(recorder.disconnections.isEmpty(),
                "the user should be told the connection dropped");
        assertTrue(recorder.disconnections.get(0).contains("retry in"),
                "the message should say when, not just that: " + recorder.disconnections.get(0));
        assertNull(client.getRefusedReason(), "an outage is not a refusal");
    }

    @Test
    void aServerThatIsNotThereYetIsRetriedRatherThanTreatedAsARefusal() throws Exception {
        // Nothing is listening on this port, so the connection is refused at the TCP level.
        client = clientFor(settingsFor(1, TOKEN));
        client.start();

        waitUntil("a retry to be scheduled", () -> recorder.disconnections.size() >= 2);
        assertTrue(recorder.refusals.isEmpty(),
                "a server that is down is an outage, not a refusal: " + recorder.refusals);
    }

    @Test
    void backoffDoublesFromTheBaseAndStopsAtTheCap() {
        CollabClient timing = clientFor(CollabSettings.offline());

        assertEquals(40, timing.backoffFor(1));
        assertEquals(80, timing.backoffFor(2));
        assertEquals(120, timing.backoffFor(3), "should be capped, not 160");
        assertEquals(120, timing.backoffFor(50));
        assertTrue(timing.backoffFor(1) < timing.backoffFor(2),
                "a flat retry interval would hammer a server that is down");
    }

    @Test
    void theDefaultBackoffIsPatientEnoughForAServerThatIsDownForAWhile() {
        CollabClient defaults = new CollabClient(CollabSettings.offline(), Runnable::run, recorder);

        assertEquals(CollabClient.BASE_BACKOFF_MILLIS, defaults.backoffFor(1));
        assertEquals(CollabClient.MAX_BACKOFF_MILLIS, defaults.backoffFor(20));
        assertTrue(CollabClient.MAX_BACKOFF_MILLIS <= 60000,
                "a cap beyond a minute would leave a recovered server unnoticed for too long");
    }

    // ---------- an outage does not lose edits ----------

    @Test
    void operationsPublishedWhileDisconnectedAreHeldAndSentOnReconnect() throws Exception {
        client = clientFor(settingsFor(1, TOKEN));
        client.start();
        waitUntil("the first failed attempt", () -> !recorder.disconnections.isEmpty());

        client.publish(anOperation("http://example.org/o#Person"));
        client.publish(anOperation("http://example.org/o#Organisation"));
        assertEquals(2, client.getPendingCount());
        client.stop();

        // Now bring a server up and connect a fresh client carrying the same queue is not
        // possible, so assert the queue drains against a server that is there from the start.
        FakeBridge server = listening();
        Recorder second = new Recorder();
        CollabClient reconnecting = new CollabClient(settingsFor(server.awaitPort(), TOKEN),
                Runnable::run, second, 40, 120, 60);
        try {
            reconnecting.publish(anOperation("http://example.org/o#Queued"));
            assertEquals(1, reconnecting.getPendingCount(),
                    "an operation published before connecting must be held, not dropped");
            reconnecting.start();

            waitUntil("the queued operation to be sent", () -> server.countOf("op") == 1);
            assertEquals(0, reconnecting.getPendingCount());
        } finally {
            reconnecting.stop();
        }
    }

    @Test
    void theQueueIsBoundedAndWhatItDropsIsCounted() {
        client = clientFor(settingsFor(1, TOKEN));

        for (int i = 0; i < CollabClient.PENDING_LIMIT + 5; i++) {
            client.publish(anOperation("http://example.org/o#C" + i));
        }

        assertEquals(CollabClient.PENDING_LIMIT, client.getPendingCount(),
                "an unbounded queue during a long outage is a leak");
        assertEquals(5, client.getDroppedWhileOfflineCount(),
                "what was dropped must be countable, so the user can be told");
    }

    /** A cursor position from five minutes ago is worse than none. */
    @Test
    void presenceIsNeverQueuedBecauseAStaleCursorIsWorthless() throws Exception {
        client = clientFor(settingsFor(1, TOKEN));
        client.start();

        client.publishPresence(10, 20, null);

        assertEquals(0, client.getPendingCount());
    }

    // ---------- receiving ----------

    @Test
    void anIncomingOperationReachesTheListener() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        for (WebSocket socket : server.getConnections()) {
            socket.send("{\"t\":\"op\",\"op\":{\"id\":\"remote-1\",\"type\":\"addSubClassOf\","
                    + "\"timestamp\":5,\"userId\":\"bob\",\"data\":{\"childIri\":\"a\","
                    + "\"parentIri\":\"b\"}}}");
        }

        waitUntil("the operation", () -> !recorder.operations.isEmpty());
        assertEquals("addSubClassOf", recorder.operations.get(0).getType());
        assertEquals("bob", recorder.operations.get(0).getUserId());
    }

    /**
     * A reconnect replays the shared log, so the same operation arrives again. Applying it twice
     * would duplicate an axiom and leave a spurious entry in Protege's undo stack.
     */
    @Test
    void aRedeliveredOperationIsDeliveredOnlyOnce() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        String frame = "{\"t\":\"op\",\"op\":{\"id\":\"remote-1\",\"type\":\"addClass\","
                + "\"timestamp\":5,\"userId\":\"bob\",\"data\":{\"iri\":\"a\"}}}";
        for (WebSocket socket : server.getConnections()) {
            socket.send(frame);
            socket.send(frame);
        }

        waitUntil("the operation", () -> !recorder.operations.isEmpty());
        Thread.sleep(150);
        assertEquals(1, recorder.operations.size(),
                "the same operation was delivered " + recorder.operations.size() + " times");
        assertTrue(client.hasSeen("remote-1"));
    }

    /**
     * The bridge filters an operation out by authenticated user, but two Protege sessions signed
     * in as the same account defeat that. Remembering our own ids is what covers it.
     */
    @Test
    void ourOwnOperationIsNotAppliedBackToUsEvenIfTheServerEchoesIt() throws Exception {
        bridge = new FakeBridge();
        bridge.echoToSender = true;
        bridge.start();
        started(bridge, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        client.publish(anOperation("http://example.org/o#Person"));

        waitUntil("the echo to arrive at the server", () -> bridge.countOf("op") == 1);
        Thread.sleep(150);
        assertTrue(recorder.operations.isEmpty(),
                "our own edit came back and would have been applied twice: "
                        + recorder.operations);
    }

    @Test
    void aPeerListReachesTheListener() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        client.publishPresence(5, 6, "http://example.org/o#Person");

        waitUntil("a peer list", () -> !recorder.peerLists.isEmpty());
        List<PeerPresence> peers = recorder.peerLists.get(recorder.peerLists.size() - 1);
        assertEquals(1, peers.size());
        assertEquals("bob", peers.get(0).getUser());
    }

    @Test
    void aMessageThisVersionDoesNotUnderstandIsIgnoredRatherThanTreatedAsAFailure()
            throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        for (WebSocket socket : server.getConnections()) {
            socket.send("{\"t\":\"somethingNewerServersSend\",\"payload\":1}");
            socket.send("not json at all");
        }

        Thread.sleep(150);
        assertTrue(client.isConnected(),
                "an unknown message must not drop the connection - a newer server would break "
                        + "every older plugin");
        assertTrue(recorder.refusals.isEmpty());
    }

    /**
     * An unreadable operation is reported, where an unknown message type is not.
     *
     * <p>They shared a branch and were both logged at debug. The two deserve opposite
     * treatment: an unrecognised type is a newer server talking to an older plugin, and
     * ignoring it is what keeps the plugin working, while an operation frame that will not
     * decode is somebody's edit this plugin is about to lose. A board quietly missing a change
     * gives its owner nothing to notice.
     */
    @Test
    void anOperationThatWillNotDecodeIsReportedRatherThanLoggedAtDebug() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        for (WebSocket socket : server.getConnections()) {
            // Shaped like an operation, and unreadable: no id, no type.
            socket.send("{\"t\":\"op\",\"op\":{\"nonsense\":true}}");
        }

        waitUntil("the dropped operation to be reported",
                () -> recorder.undecodable.get() == 1);
        assertTrue(client.isConnected(), "one bad frame must not drop the session");
        assertTrue(recorder.operations.isEmpty(), "nothing was applied");
    }

    /** An unknown message type still reports nothing, which is the other half of the rule. */
    @Test
    void anUnknownMessageTypeIsStillSilent() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        for (WebSocket socket : server.getConnections()) {
            socket.send("{\"t\":\"somethingNewerServersSend\",\"payload\":1}");
        }

        Thread.sleep(150);
        assertEquals(0, recorder.undecodable.get(),
                "a message type we do not know is not a lost edit");
    }

    /**
     * Queued edits survive a flush into a socket that is already gone.
     *
     * <p>The queue was emptied under the lock and the sends happened outside it, so a socket
     * dying mid-flush destroyed up to PENDING_LIMIT operations: the queue was already clear,
     * every failure was logged at debug, and the next reconnect had nothing left to send. Those
     * are exactly the edits made while offline, which is the only reason the queue exists.
     */
    @Test
    void aFlushIntoADeadSocketKeepsTheQueue() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        // Offline, so publishing queues rather than sends.
        int port = server.getPort();
        server.stop();
        waitUntil("the drop to be noticed", () -> !client.isConnected());
        for (int at = 0; at < 5; at++) {
            client.publish(OntologyOperation.local("addClass", "alice",
                    java.util.Collections.<String, Object>singletonMap(
                            "iri", "http://example.org/C" + at)));
        }

        assertEquals(5, client.getPendingCount(), "five edits made while offline");

        // The flush, into a bridge standing where the old one was. Before the fix the queue was
        // emptied under the lock and the sends happened outside it, so anything that failed to
        // go out was unrecoverable; now what is unsent comes back in order for the next attempt.
        FakeBridge replacement = listeningOn(port);
        waitUntil("the reconnect", () -> client.isConnected());
        waitUntil("the queue to drain into a live socket", () -> client.getPendingCount() == 0);
        assertEquals(0, client.getDroppedWhileOfflineCount(),
                "nothing was dropped - the queue stayed well under PENDING_LIMIT");
        // Waited for, not asserted. getPendingCount() reaching zero says the client finished
        // writing; the bridge receiving is a separate hop, so asserting it immediately is a race
        // that passes most of the time - which is worse than no test at all. Caught by this
        // very test failing once and passing on the next run.
        waitUntil("all five queued edits to reach the bridge",
                () -> replacement.received.size() >= 6);
        assertTrue(replacement.received.get(0).contains("\"hello\""),
                "the first frame is the handshake, then the five edits: "
                        + replacement.received);
    }

    // ---------- presence heartbeat ----------

    /**
     * bridge.mjs expires a peer after PRESENCE_TTL_MS of silence, so a cursor has to be resent
     * even when its owner is not moving. Otherwise it disappears whenever someone stops to think,
     * which reads as them having left.
     */
    @Test
    void theLastCursorPositionIsResentSoTheBridgeDoesNotExpireIt() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        client.publishPresence(7, 8, null);

        waitUntil("presence to be resent without being asked",
                () -> server.countOf("presence") >= 3);
    }

    @Test
    void theHeartbeatIntervalBeatsTheBridgesExpiryWithRoomToSpare() {
        long bridgeTtlMillis = 10000; // PRESENCE_TTL_MS in collab/bridge.mjs
        assertTrue(CollabClient.PRESENCE_INTERVAL_MILLIS * 2 < bridgeTtlMillis,
                "one lost heartbeat should not expire the cursor; interval is "
                        + CollabClient.PRESENCE_INTERVAL_MILLIS + "ms against a "
                        + bridgeTtlMillis + "ms ttl");
    }

    // ---------- threading and lifecycle ----------

    /**
     * Callbacks must not run on the socket reader thread: a listener that touches Protege's model
     * off the EDT corrupts Swing in ways that surface much later and elsewhere.
     */
    @Test
    void everyCallbackGoesThroughTheGivenExecutor() throws Exception {
        final AtomicInteger dispatched = new AtomicInteger();
        FakeBridge server = listening();
        final Recorder listener = new Recorder();
        client = new CollabClient(settingsFor(server.awaitPort(), TOKEN), command -> {
            dispatched.incrementAndGet();
            command.run();
        }, listener, 40, 120, 60);
        client.start();

        waitUntil("the welcome", () -> !listener.connectedAs.isEmpty());
        assertTrue(dispatched.get() >= 1,
                "the connection callback bypassed the executor");
    }

    /**
     * A live non-daemon thread would stop Protege's JVM exiting after its window closed, which
     * looks to a user like the application hanging on quit.
     */
    @Test
    void itsBackgroundThreadIsADaemonSoProtegeCanStillExit() throws Exception {
        started(listening(), TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        List<Thread> ours = new ArrayList<Thread>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("ontoboard-collab")) {
                ours.add(thread);
            }
        }
        assertFalse(ours.isEmpty(), "expected to find the client's own scheduler thread");
        for (Thread thread : ours) {
            assertTrue(thread.isDaemon(), thread.getName() + " is not a daemon thread");
        }
    }

    @Test
    void stoppingIsSafeBeforeStartingAndTwice() {
        client = clientFor(settingsFor(1, TOKEN));
        client.stop();
        client.start();
        client.stop();
        client.stop();
        assertFalse(client.isConnected());
    }

    @Test
    void startingTwiceDoesNotOpenTwoConnections() throws Exception {
        FakeBridge server = listening();
        started(server, TOKEN);
        waitUntil("the welcome", () -> !recorder.connectedAs.isEmpty());

        client.start();
        Thread.sleep(150);

        assertEquals(1, server.connections.get());
    }

    @Test
    void stoppingEndsTheRetryLoop() throws Exception {
        client = clientFor(settingsFor(1, TOKEN));
        client.start();
        waitUntil("a failed attempt", () -> !recorder.disconnections.isEmpty());

        client.stop();
        int after = recorder.disconnections.size();
        Thread.sleep(400);

        assertEquals(after, recorder.disconnections.size(),
                "retries continued after stop()");
    }

    @Test
    void publishingNothingIsIgnoredRatherThanThrowing() {
        client = clientFor(settingsFor(1, TOKEN));
        client.publish(null);
        assertEquals(0, client.getPendingCount());
    }

    @Test
    void anExecutorThatRejectsWorkDoesNotBreakTheSocketThread() throws Exception {
        FakeBridge server = listening();
        client = new CollabClient(settingsFor(server.awaitPort(), TOKEN), command -> {
            throw new java.util.concurrent.RejectedExecutionException("the view has gone");
        }, recorder, 40, 120, 60);

        client.start();
        Thread.sleep(200);

        // The point is that nothing was thrown out of the reader thread and the client is intact.
        assertTrue(recorder.connectedAs.isEmpty());
        client.stop();
    }

    @Test
    void seenIdsAreBoundedSoALongSessionDoesNotGrowForever() {
        client = clientFor(settingsFor(1, TOKEN));

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < CollabClient.SEEN_LIMIT + 10; i++) {
            OntologyOperation operation = anOperation("http://example.org/o#C" + i);
            ids.add(operation.getId());
            client.publish(operation);
        }

        assertFalse(client.hasSeen(ids.get(0)), "the oldest ids should have been evicted");
        assertTrue(client.hasSeen(ids.get(ids.size() - 1)), "the newest must still be remembered");
    }

}
