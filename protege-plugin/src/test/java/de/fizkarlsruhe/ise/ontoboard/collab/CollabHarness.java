package de.fizkarlsruhe.ise.ontoboard.collab;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * A real collaboration server and real peers, for tests that need the wire rather than a fake.
 *
 * <p>Shared by {@link CollabLiveTest}, which asserts the protocol, and the pizza harness, which
 * demonstrates it over a real ontology and writes down what crossed. One copy because the thing
 * being set up is fiddly - a child process, a port, a signed token, a session per peer - and two
 * copies of fiddly setup drift until one of them is testing something else.
 *
 * <p>Starts {@code node server.mjs} from {@code collab/}: Hocuspocus and the JSON bridge, the same
 * entry point {@code docker compose up collab} runs. Not a stand-in for it - that distinction is the
 * whole reason this exists, because the bridge's own tests inject their own {@code getDoc} and so
 * never executed the line that wires the two together. That line was wrong, and every test passed.
 */
public final class CollabHarness implements Closeable {

    /** Long enough for a WebSocket round trip through Yjs, short enough to fail a hung test. */
    public static final long TIMEOUT_MILLIS = 20_000;

    private static final String SECRET = "ontoboard-live-test-secret";

    private final List<String> serverLines = new CopyOnWriteArrayList<String>();

    private final List<Peer> peers = new CopyOnWriteArrayList<Peer>();

    private Process server;

    private int bridgePort;

    // ===================================================================== availability

    /**
     * Whether the server can be started here at all.
     *
     * <p>Node and the collaboration server's dependencies, not Docker: the server is plain Node and
     * {@code collab/node_modules} is what {@code npm install} leaves. A machine without them skips,
     * because a green run that never opened a socket says nothing about collaboration.
     */
    public static boolean isAvailable() {
        File collab = collabDirectory();
        if (!new File(collab, "server.mjs").isFile()
                || !new File(collab, "node_modules").isDirectory()) {
            return false;
        }
        try {
            Process probe = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
            return probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException | InterruptedException noNode) {
            return false;
        }
    }

    /** {@code collab/}, resolved from the working directory Maven runs tests in. */
    private static File collabDirectory() {
        return new File(new File("").getAbsoluteFile().getParentFile(), "collab");
    }

    // ===================================================================== lifecycle

    /**
     * Starts the server on free ports and returns the bridge's.
     *
     * <p>Free ports rather than 1234 and 1235, so a server the developer is already running is
     * neither collided with nor - much worse - silently talked to, which would make this pass
     * against a build nobody is testing.
     *
     * @throws IllegalStateException if the server exits or never reports listening
     */
    public int start() throws IOException, InterruptedException {
        int hocuspocusPort = freePort();
        bridgePort = freePort();
        ProcessBuilder builder = new ProcessBuilder("node", "server.mjs");
        builder.directory(collabDirectory());
        builder.redirectErrorStream(true);
        builder.environment().put("SECRET_KEY", SECRET);
        builder.environment().put("COLLAB_PORT", String.valueOf(hocuspocusPort));
        builder.environment().put("BRIDGE_PORT", String.valueOf(bridgePort));
        server = builder.start();
        pumpOutput(server);

        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (output().contains("JSON bridge listening on port " + bridgePort)) {
                return bridgePort;
            }
            if (!server.isAlive()) {
                throw new IllegalStateException(
                        "the collaboration server exited before it listened:\n" + output());
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(
                "the collaboration server never reported listening:\n" + output());
    }

    /** Everything the server has said, for a failure message that includes its side of the story. */
    public String output() {
        return String.join("\n", serverLines);
    }

    @Override
    public void close() {
        for (Peer peer : peers) {
            try {
                peer.session.stop();
            } catch (RuntimeException ignored) {
                // A session that never connected still has to be released.
            }
        }
        peers.clear();
        if (server != null) {
            server.destroyForcibly();
            server = null;
        }
    }

    // ===================================================================== peers

    /**
     * One peer: its own ontology, its own session, and a record of what it was told to apply.
     *
     * <p>The ontology is the caller's, not a copy. A demonstration over pizza wants the real pizza
     * on one side and a peer's own empty copy on the other, and which is which is the caller's
     * business.
     */
    public static final class Peer {
        private final String user;
        private final OWLOntology ontology;
        private final CollabSession session;
        private final List<String> applied = new CopyOnWriteArrayList<String>();
        private final List<String> status = new CopyOnWriteArrayList<String>();

        /** Geometry peers sent for a term, so a test can assert it arrived rather than vanished. */
        private final java.util.Map<String, java.util.Map<String, Object>> geometry =
                new java.util.concurrent.ConcurrentHashMap<String, java.util.Map<String, Object>>();

        /** One entry per time this peer's session announced itself as joined. */
        private final List<String> joined = new CopyOnWriteArrayList<String>();

        Peer(String user, OWLOntology ontology, CollabSettings settings) {
            this.user = user;
            this.ontology = ontology;
            this.session = new CollabSession(settings, new CollabSession.Host() {
                @Override
                public OWLOntology activeOntology() {
                    return Peer.this.ontology;
                }

                @Override
                public void applyChanges(List<OWLOntologyChange> changes) {
                    for (OWLOntologyChange change : changes) {
                        applied.add(change.toString());
                    }
                    Peer.this.ontology.getOWLOntologyManager().applyChanges(changes);
                }

                @Override
                public void onStatus(String text, boolean connected) {
                    status.add(text);
                }

                @Override
                public void onPeersChanged() {
                    // Nothing to repaint without a canvas.
                }

                @Override
                public void onUnshareable(int count, String exampleReason) {
                    status.add("unshareable " + count + ": " + exampleReason);
                }

                @Override
                public void onPeerGeometry(String iri, java.util.Map<String, Object> data) {
                    geometry.put(iri, data);
                }

                @Override
                public void onSessionEnded(String reason) {
                    status.add("ended: " + reason);
                }

                @Override
                public void onJoined() {
                    joined.add(user);
                }
            }, new Executor() {
                @Override
                public void execute(Runnable command) {
                    // Straight through rather than onto the EDT: there is no EDT in a test, and the
                    // session's own remote-apply guard is part of what is being exercised.
                    command.run();
                }
            });
        }

        public String getUser() {
            return user;
        }

        public OWLOntology getOntology() {
            return ontology;
        }

        public CollabSession getSession() {
            return session;
        }

        /** Every change this peer was handed by the session, in order. */
        public List<String> getApplied() {
            return applied;
        }

        /** Every status line the session reported, which is where a refusal shows up. */
        /** Geometry this peer received for a term, or null. */
        public java.util.Map<String, Object> getGeometryFor(String iri) {
            return geometry.get(iri);
        }

        /** How many times this peer's session announced itself as joined. */
        public int getJoinCount() {
            return joined.size();
        }

        public List<String> getStatus() {
            return status;
        }
    }

    /**
     * Connects a peer and starts its session.
     *
     * @param ontologyIri what this peer tells the others it is editing; the mismatch warning is
     *     built from it, so a test can deliberately disagree here
     */
    public Peer join(String user, String board, OWLOntology ontology, String ontologyIri)
            throws Exception {
        CollabSettings settings = new CollabSettings(
                "ws://127.0.0.1:" + bridgePort, board, tokenFor(user), user, "#4A90D9")
                .forOntology(ontologyIri);
        Peer peer = new Peer(user, ontology, settings);
        peer.session.start();
        peers.add(peer);
        return peer;
    }

    /** {@link #join} taking the IRI from the ontology, which is the ordinary case. */
    public Peer join(String user, String board, OWLOntology ontology) throws Exception {
        return join(user, board, ontology,
                ontology.getOntologyID().getOntologyIRI().isPresent()
                        ? ontology.getOntologyID().getOntologyIRI().get().toString()
                        : "");
    }

    // ===================================================================== waiting

    /** What a caller is waiting to become true. */
    public interface Condition {
        boolean isMet();
    }

    /**
     * Polls until met or timed out. Returns whether it was met, so the caller asserts with its own
     * message rather than being told "timed out" about a claim it never stated.
     */
    public boolean waitFor(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isMet()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** Waits for every peer to be connected; false if any never got there. */
    public boolean waitUntilConnected(final Peer... connecting) throws InterruptedException {
        for (final Peer peer : connecting) {
            boolean ready = waitFor(new Condition() {
                @Override
                public boolean isMet() {
                    return peer.session.isConnected();
                }
            });
            if (!ready) {
                return false;
            }
        }
        return true;
    }

    // ===================================================================== plumbing

    private void pumpOutput(Process process) {
        final BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (String line = out.readLine(); line != null; line = out.readLine()) {
                        serverLines.add(line);
                    }
                } catch (IOException closed) {
                    // The process ended; nothing left to read.
                }
            }
        }, "collab-server-output");
        pump.setDaemon(true);
        pump.start();
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    /**
     * An HS256 token the server will accept, minted here rather than fetched.
     *
     * <p>Twenty lines of {@code javax.crypto} instead of a JWT dependency on the test classpath: the
     * point is the protocol, and a library to sign two claims would be one more thing to keep
     * current for no gain.
     */
    private static String tokenFor(String subject) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = url.encodeToString(
                ("{\"sub\":\"" + subject + "\",\"role\":\"user\"}")
                        .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signingInput + "."
                + url.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }
}
