package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The loop guard, tested against a host that behaves the way Protege does.
 *
 * <p>This is the failure that cannot be allowed to reach production. Applying a remote operation
 * goes through {@code OWLModelManager}, which fires the ontology change listener, which is the
 * listener that publishes local edits. If the guard is missing, one person's change is
 * republished by everyone who receives it and again by everyone who receives that - the ontology
 * filling up with each round's output, and the only symptom being an editor that will not settle.
 *
 * <p>{@link EchoingHost} reproduces that exactly: its {@code applyChanges} applies the changes to
 * a real ontology and then calls back into {@code publishLocalChanges}, which is precisely what
 * Protege's listener does. A test with a passive host would prove nothing about the guard, since
 * the loop only exists when the apply feeds the publish.
 */
class CollabSessionTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void freshOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    // ---------- harness ----------

    /**
     * A host that re-fires its change listener on apply, as Protege's OWLModelManager does.
     *
     * <p>Without the re-fire this whole test class would be theatre.
     */
    private class EchoingHost implements CollabSession.Host {

        /** Geometry a peer sent, so a test can assert it arrived rather than being discarded. */
        final java.util.Map<String, java.util.Map<String, Object>> geometry =
                new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();

        /** Reasons a session was declared over, and how often it was announced as joined. */
        final List<String> ended = new java.util.ArrayList<String>();
        int joins;

        @Override
        public void onPeerGeometry(String iri, java.util.Map<String, Object> data) {
            geometry.put(iri, data);
        }

        @Override
        public void onSessionEnded(String reason) {
            ended.add(reason);
        }

        @Override
        public void onJoined() {
            joins++;
        }
        private CollabSession session;
        final List<String> statuses = new ArrayList<String>();
        final List<String> unshareable = new ArrayList<String>();
        int peerRepaints;
        int applyCalls;
        /** Counts republications, which is what a loop would drive up. */
        int publishedFromListener;

        void attach(CollabSession session) {
            this.session = session;
        }

        @Override
        public OWLOntology activeOntology() {
            return ontology;
        }

        @Override
        public void applyChanges(List<OWLOntologyChange> changes) {
            applyCalls++;
            manager.applyChanges(changes);
            // Protege's ontology change listener fires here, synchronously, and it is the same
            // listener that publishes. This line is the loop.
            publishedFromListener +=
                    session.publishLocalChanges(changes, OperationMapper.NO_HINTS);
        }

        @Override
        public void onStatus(String status, boolean connected) {
            statuses.add((connected ? "up: " : "down: ") + status);
        }

        @Override
        public void onPeersChanged() {
            peerRepaints++;
        }

        @Override
        public void onUnshareable(int count, String exampleReason) {
            unshareable.add(count + ": " + exampleReason);
        }
    }

    /**
     * Records what the session tried to send, in place of a socket.
     *
     * <p>CollabClient is final - a half-overridden socket client is a bad idea - so the session
     * depends on CollabTransport and this implements it. The transport's own behaviour is covered
     * by CollabClientTest against a real server; what is under test here is what the session
     * decides to hand it.
     */
    private static final class RecordingTransport implements CollabTransport {
        final List<OntologyOperation> published = new ArrayList<OntologyOperation>();
        final List<String> presence = new ArrayList<String>();
        boolean started;

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            started = false;
        }

        @Override
        public boolean isConnected() {
            return started;
        }

        @Override
        public void publish(OntologyOperation operation) {
            published.add(operation);
        }

        @Override
        public void publishPresence(double x, double y, String selection) {
            presence.add(x + "," + y + "," + selection);
        }

        @Override
        public String getRefusedReason() {
            return null;
        }

        @Override
        public int getPendingCount() {
            return 0;
        }

        @Override
        public int getDroppedWhileOfflineCount() {
            return 0;
        }
    }

    private static CollabSettings settings() {
        return new CollabSettings("ws://x:1235", "board-1", "token", "alice", "#AA0000");
    }

    private EchoingHost host;
    private RecordingTransport client;

    private CollabSession session() {
        return sessionWith(new EchoingHost());
    }

    private CollabSession sessionWith(EchoingHost withHost) {
        host = withHost;
        client = new RecordingTransport();
        client.start();
        CollabSession session = new CollabSession(settings(), host, client);
        host.attach(session);
        return session;
    }

    private static OntologyOperation operation(String type, Object... keysAndValues) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return OntologyOperation.local(type, "bob", data);
    }

    // ---------- presence, geometry and the end of a session ----------

    /**
     * A peer's geometry reaches the host instead of being dropped.
     *
     * <p>Every operation this plugin publishes carries the node's geometry, and the receiving side
     * discarded it - so a class a colleague created landed wherever an unpositioned node goes, in a row
     * along the top of the board. The sender did the work and the receiver ignored it.
     */
    @Test
    void aPeersGeometryIsHandedToTheHost() {
        EchoingHost host = new EchoingHost();
        CollabSession session = sessionWith(host);

        session.onOperation(operation("addClass", "iri", NS + "Person", "x", 420.0, "y", 260.0));

        Map<String, Object> received = host.geometry.get(NS + "Person");
        assertNotNull(received, host.geometry.toString());
        assertEquals(420.0, ((Number) received.get("x")).doubleValue(), 1e-9);
    }

    /**
     * Even when the change itself is already applied.
     *
     * <p>A peer re-asserting a declaration produces no ontology change - convergence, not an error -
     * and the method returns early. But this board can perfectly well have the term and no position for
     * it, which is the common case when two people put the same class on their boards independently.
     */
    @Test
    void geometryArrivesEvenWhenThereIsNothingToApply() {
        EchoingHost host = new EchoingHost();
        CollabSession session = sessionWith(host);
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));

        session.onOperation(operation("addClass", "iri", NS + "Person", "x", 100.0, "y", 100.0));

        assertNotNull(host.geometry.get(NS + "Person"), "nothing to apply is not nothing to learn");
    }

    /**
     * A refusal ends the session; a disconnect does not.
     *
     * <p>They were both reported the same way, as {@code onStatus(reason, false)}, and the toolbar took
     * its button label from that flag. So after a refusal the button read "Collaborate..." while a live
     * session object was still held, and clicking it disconnected instead of opening the dialog - and
     * during an ordinary reconnect it said the same thing while the session was perfectly alive.
     */
    @Test
    void aRefusalEndsTheSessionAndADisconnectDoesNot() {
        EchoingHost host = new EchoingHost();
        CollabSession session = sessionWith(host);

        session.onDisconnected("network", 1, 2000);
        assertTrue(host.ended.isEmpty(), "a retrying session has not ended: " + host.ended);

        session.onRefused("The server rejected the token");

        assertEquals(1, host.ended.size());
        assertTrue(host.ended.get(0).contains("token"), host.ended.toString());
    }

    /** Joining tells the host, so presence can be announced without waiting for the mouse. */
    @Test
    void joiningIsAnnouncedOnce() {
        EchoingHost host = new EchoingHost();
        CollabSession session = sessionWith(host);

        session.onConnected("alice");

        assertEquals(1, host.joins);
    }

    // ---------- the loop guard ----------

    /** The one that matters. */
    @Test
    void aRemoteOperationIsNotRepublishedBackToTheSender() {
        CollabSession session = session();

        session.onOperation(operation("addSubClassOf",
                "childIri", NS + "Dog", "parentIri", NS + "Animal"));

        assertEquals(1, host.applyCalls, "the operation should have been applied");
        assertTrue(ontology.containsAxiom(
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal"))));
        assertEquals(0, host.publishedFromListener,
                "the change we applied for someone else was published straight back out, which "
                        + "is the amplification loop this guard exists to stop");
        assertTrue(client.published.isEmpty(), "nothing at all should have been published: "
                + client.published);
    }

    @Test
    void theGuardIsReleasedAfterwardsSoLocalEditsStillPublish() {
        CollabSession session = session();
        session.onOperation(operation("addClass", "iri", NS + "Person"));
        assertFalse(session.isApplyingRemote());

        int published = session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Robot")))),
                OperationMapper.NO_HINTS);

        assertEquals(1, published, "a genuinely local edit must still travel");
    }

    /**
     * A flag left set by a failed apply would silently stop the session publishing anything for
     * the rest of its life - much worse than the failed apply itself, and impossible to notice.
     */
    @Test
    void anExceptionDuringApplyStillReleasesTheGuard() {
        CollabSession session = sessionWith(new EchoingHost() {
            @Override
            public void applyChanges(List<OWLOntologyChange> changes) {
                throw new IllegalStateException("the ontology is read-only");
            }
        });

        assertThrows(IllegalStateException.class,
                () -> session.onOperation(operation("addClass", "iri", NS + "Person")));

        assertFalse(session.isApplyingRemote(), "the guard was left set by a failed apply");
        assertEquals(1, session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Robot")))),
                OperationMapper.NO_HINTS));
    }

    /**
     * "Already agrees" means the declarations too: addSubClassOf declares both ends, so an
     * ontology holding the subclass axiom but not the declarations still has work to do. The first
     * version of this test asserted otherwise and failed correctly.
     */
    @Test
    void anOperationTheOntologyAlreadyAgreesWithIsNotAppliedAtAll() {
        CollabSession session = session();
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Dog")));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Animal")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")));

        session.onOperation(operation("addSubClassOf",
                "childIri", NS + "Dog", "parentIri", NS + "Animal"));

        assertEquals(0, host.applyCalls,
                "applying a no-op would add a pointless entry to Protege's undo stack");
    }

    @Test
    void anOperationThisVersionCannotApplyIsIgnoredWithoutTouchingTheOntology() {
        CollabSession session = session();
        int before = ontology.getAxiomCount();

        session.onOperation(operation("addStickyNote", "id", "n1"));

        assertEquals(before, ontology.getAxiomCount());
        assertEquals(0, host.applyCalls);
    }

    @Test
    void anOperationArrivingWithNoOntologyOpenIsReportedRatherThanSwallowed() {
        CollabSession session = sessionWith(new EchoingHost() {
            @Override
            public OWLOntology activeOntology() {
                return null;
            }
        });

        session.onOperation(operation("addClass", "iri", NS + "Person"));

        assertEquals(1, host.statuses.size());
        assertTrue(host.statuses.get(0).contains("not applied"), host.statuses.get(0));
    }

    // ---------- publishing local work ----------

    @Test
    void aLocalEditIsPublishedAsAnOperation() {
        CollabSession session = session();

        session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")))),
                OperationMapper.NO_HINTS);

        assertEquals(1, client.published.size());
        assertEquals("addClass", client.published.get(0).getType());
        assertEquals(NS + "Person", client.published.get(0).getIri());
    }

    /**
     * The point of publishing from the change listener rather than from the canvas: an edit made
     * in Protege's class hierarchy or Manchester syntax editor travels too.
     */
    @Test
    void severalChangesFromOneProtegeActionAllTravel() {
        CollabSession session = session();

        int published = session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Dog"))),
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Animal"))),
                new AddAxiom(ontology,
                        factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")))),
                OperationMapper.NO_HINTS);

        assertEquals(3, published);
    }

    /**
     * Protege produces far more axiom kinds than the vocabulary carries. A user has to be able to
     * find out that their equivalence axiom did not reach anyone.
     */
    @Test
    void changesThatCannotBeSharedAreCountedAndReported() {
        CollabSession session = session();
        OWLAxiom unshareable =
                factory.getOWLEquivalentClassesAxiom(cls("Person"), cls("Human"));

        int published = session.publishLocalChanges(
                Arrays.<OWLOntologyChange>asList(new AddAxiom(ontology, unshareable)),
                OperationMapper.NO_HINTS);

        assertEquals(0, published);
        assertEquals(1, session.getUnshareableCount());
        assertEquals(1, host.unshareable.size());
        assertTrue(host.unshareable.get(0).contains("Equivalent"), host.unshareable.get(0));
    }

    @Test
    void theUnshareableCountAccumulatesAcrossTheSession() {
        CollabSession session = session();
        for (int i = 0; i < 3; i++) {
            session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(new AddAxiom(ontology,
                    factory.getOWLDisjointClassesAxiom(cls("A" + i), cls("B" + i)))),
                    OperationMapper.NO_HINTS);
        }
        assertEquals(3, session.getUnshareableCount());
        assertTrue(host.unshareable.get(2).startsWith("3: "),
                "the report should carry the running total: " + host.unshareable.get(2));
    }

    @Test
    void aMixedBatchPublishesWhatItCanAndReportsTheRest() {
        CollabSession session = session();

        int published = session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person"))),
                new AddAxiom(ontology,
                        factory.getOWLHasKeyAxiom(cls("Person"),
                                factory.getOWLObjectProperty(IRI.create(NS + "id"))))),
                OperationMapper.NO_HINTS);

        assertEquals(1, published, "the shareable half must not be held back by the other");
        assertEquals(1, session.getUnshareableCount());
    }

    @Test
    void anEmptyOrMissingChangeListPublishesNothingAndReportsNothing() {
        CollabSession session = session();

        assertEquals(0, session.publishLocalChanges(null, OperationMapper.NO_HINTS));
        assertEquals(0, session.publishLocalChanges(
                new ArrayList<OWLOntologyChange>(), OperationMapper.NO_HINTS));
        assertTrue(host.unshareable.isEmpty());
    }

    // ---------- presence and status ----------

    @Test
    void theCursorPositionIsPassedStraightThroughInGraphSpace() {
        CollabSession session = session();

        session.publishCursor(-12.5, 900.0, NS + "Person");

        assertEquals("-12.5,900.0," + NS + "Person", client.presence.get(0));
    }

    @Test
    void connectingRecordsWhoWeAreSoOurOwnCursorIsNotDrawn() {
        CollabSession session = session();

        session.onConnected("alice");
        session.onPeers(Arrays.asList(
                new PeerPresence("alice", "#AA0000", 1, 1, null, System.currentTimeMillis()),
                new PeerPresence("bob", "#00AA00", 2, 2, null, System.currentTimeMillis())));

        List<PeerCursors.Marker> visible =
                session.getCursors().visibleAt(System.currentTimeMillis());
        assertEquals(1, visible.size());
        assertEquals("bob", visible.get(0).getUser());
    }

    @Test
    void theStatusNamesTheBoardAndTheAuthenticatedUser() {
        CollabSession session = session();

        session.onConnected("alice");

        assertTrue(host.statuses.get(0).startsWith("up: "), host.statuses.get(0));
        assertTrue(host.statuses.get(0).contains("alice"), host.statuses.get(0));
        assertTrue(host.statuses.get(0).contains("board-1"), host.statuses.get(0));
    }

    @Test
    void aDisconnectionSaysWhenItWillRetryRatherThanJustThatItFailed() {
        CollabSession session = session();

        session.onDisconnected("connection reset", 2, 4000);

        assertTrue(host.statuses.get(0).startsWith("down: "), host.statuses.get(0));
        assertTrue(host.statuses.get(0).contains("4s"), host.statuses.get(0));
        assertTrue(host.statuses.get(0).contains("connection reset"), host.statuses.get(0));
    }

    /** A cursor left on the canvas after a drop suggests someone is still there. */
    @Test
    void losingTheConnectionRemovesEveryPeerCursor() {
        CollabSession session = session();
        session.onPeers(Arrays.asList(
                new PeerPresence("bob", "#00AA00", 2, 2, null, System.currentTimeMillis())));
        assertEquals(1, session.getCursors().visibleAt(System.currentTimeMillis()).size());

        session.onDisconnected("connection reset", 1, 1000);

        assertTrue(session.getCursors().visibleAt(System.currentTimeMillis()).isEmpty());
        assertTrue(host.peerRepaints >= 2, "the canvas must be asked to repaint without them");
    }

    @Test
    void beingRefusedClearsTheCursorsAndShowsTheReason() {
        CollabSession session = session();
        session.onPeers(Arrays.asList(
                new PeerPresence("bob", "#00AA00", 2, 2, null, System.currentTimeMillis())));

        session.onRefused("invalid token: jwt expired");

        assertTrue(session.getCursors().visibleAt(System.currentTimeMillis()).isEmpty());
        assertTrue(host.statuses.get(host.statuses.size() - 1).contains("jwt expired"));
        assertTrue(host.statuses.get(host.statuses.size() - 1).startsWith("down: "));
    }

    // ---------- diagnostics ----------

    @Test
    void theDescriptionCarriesEverythingNeededToDiagnoseASession() {
        CollabSession session = session();
        session.publishLocalChanges(Arrays.<OWLOntologyChange>asList(new AddAxiom(ontology,
                factory.getOWLDisjointClassesAxiom(cls("A"), cls("B")))),
                OperationMapper.NO_HINTS);

        Map<String, String> described = session.describe();

        assertEquals("LIVE", described.get("mode"));
        assertEquals("ws://x:1235", described.get("server"));
        assertEquals("board-1", described.get("board"));
        assertEquals("1", described.get("changes that could not be shared"));
        assertTrue(described.containsKey("queued operations"));
        assertTrue(described.containsKey("dropped while offline"));
    }

    @Test
    void requiresItsCollaborators() {
        assertThrows(IllegalArgumentException.class,
                () -> new CollabSession(null, new EchoingHost(), Runnable::run));
        assertThrows(IllegalArgumentException.class,
                () -> new CollabSession(settings(), null, Runnable::run));
        assertThrows(IllegalArgumentException.class,
                () -> new CollabSession(settings(), new EchoingHost(),
                        (java.util.concurrent.Executor) null));
        assertThrows(IllegalArgumentException.class,
                () -> new CollabSession(settings(), new EchoingHost(),
                        (CollabTransport) null));
    }
}
