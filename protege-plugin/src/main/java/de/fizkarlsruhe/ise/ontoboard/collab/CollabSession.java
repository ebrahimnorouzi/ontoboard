package de.fizkarlsruhe.ise.ontoboard.collab;

import de.fizkarlsruhe.ise.ontoboard.collab.OperationMapper.CanvasHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins the ontology to the shared session: publishes local changes, applies remote ones, and
 * keeps the two from chasing each other.
 *
 * <p><b>The loop this exists to prevent.</b> A remote operation is applied through Protege's
 * {@code OWLModelManager}, which fires the ontology change listener, which is the same listener
 * that publishes local edits. Left alone, one person's change would be republished by everyone who
 * received it, then republished again by everyone who received that, forever - with the ontology
 * accumulating whatever each round produced. So {@link #isApplyingRemote()} is held for the
 * duration of the apply and {@link #publishLocalChanges} refuses while it is set.
 *
 * <p>A flag is enough because Protege applies changes and fires listeners synchronously on the
 * event dispatch thread, and remote operations are marshalled onto that thread before being
 * applied. Two further guards sit behind it, because a loop is expensive to discover in
 * production: the client remembers every operation id it has sent or seen, and the bridge filters
 * an operation out by authenticated user before it is relayed at all.
 *
 * <p>Everything about Protege is behind {@link Host}, so this class - the one carrying the
 * dangerous logic - is testable headlessly, including against a host that re-fires its change
 * listener on apply exactly as Protege does.
 */
public final class CollabSession implements CollabClient.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollabSession.class);

    /** What the session needs from Protege and from the view. */
    public interface Host {

        /** The ontology being edited, or null if none is open. */
        OWLOntology activeOntology();

        /**
         * Applies changes through {@code OWLModelManager}, so Protege's undo and its other views
         * stay correct. Called on the dispatch thread, inside the remote-apply guard.
         */
        void applyChanges(List<OWLOntologyChange> changes);

        /** A short line for the toolbar: connected, retrying, or why not. */
        void onStatus(String status, boolean connected);

        /** Peers moved or changed; the canvas should repaint its cursor layer. */
        void onPeersChanged();

        /**
         * Some local changes could not be shared, with a count and one example reason.
         *
         * <p>Reported rather than swallowed: a user whose {@code owl:hasKey} axiom never reached a
         * colleague has to be able to find that out from the UI.
         */
        void onUnshareable(int count, String exampleReason);

        /**
         * A peer's geometry for a term, so an arriving node can be drawn where they have it.
         *
         * <p>Every operation carries this and the receiving side used to discard it, so a class a
         * colleague created landed wherever an unpositioned node goes - a row along the top of the
         * board. What the host does with it is the host's rule; see {@code PeerGeometry}.
         */
        void onPeerGeometry(String iri, java.util.Map<String, Object> data);

        /**
         * The session is over and will not retry - a refusal rather than a dropped connection.
         *
         * <p>Separate from {@code onStatus(reason, false)} because the two mean different things to the
         * UI: a disconnect is temporary and the session still exists, while a refusal is final and the
         * host has to stop holding one. Without this, the toolbar button read "Collaborate..." while a
         * refused session object was still alive, so clicking it disconnected instead of opening the
         * dialog.
         */
        void onSessionEnded(String reason);

        /**
         * The session is live: a good moment to say where this editor is looking.
         *
         * <p>Presence used to be published only from mouse motion, so a peer who joined and read the
         * diagram without moving the mouse was invisible - and to everybody else indistinguishable from
         * nobody having joined at all. What to announce is the host's decision, because it is the only
         * side that knows where the view is and what is selected.
         */
        void onJoined();
    }

    private final CollabSettings settings;
    private final Host host;
    private final PeerCursors cursors = new PeerCursors();

    /**
     * The one ontology this session is about, fixed when it starts.
     *
     * <p>Not read from the host per operation, which is what it used to do. A board belongs to an
     * ontology - that is the whole basis of the peer-mismatch warning - so a session that resolves
     * "the ontology" afresh each time follows Protege's active-ontology selection instead, and two
     * things go wrong at once. A peer's edits land in whatever file the user has since switched to,
     * and the user's edits to that unrelated file are published to the original board. Neither is
     * visible until somebody notices a class they never wrote.
     */
    private OWLOntology subject;
    private final CollabTransport client;

    private final Object lock = new Object();
    private boolean applyingRemote;
    private int unshareableCount;
    private String lastUnshareableReason;

    /** What each connected peer last said they were editing, keyed by name. */
    private volatile java.util.Map<String, String> peerOntologies =
            new java.util.LinkedHashMap<String, String>();

    /**
     * Peers who are on this board editing a different ontology, if any.
     *
     * <p>The one check no single end can do alone: two people who both override the derived board
     * id to the same wrong value defeat every local check, and the first sign either gets is
     * somebody else's class arriving in their file.
     */
    public String peerOntologyWarning() {
        return BoardId.peerMismatchWarning(settings.getOntologyIri(), peerOntologies);
    }

    public CollabSession(CollabSettings settings, Host host, Executor dispatcher) {
        if (settings == null || host == null || dispatcher == null) {
            throw new IllegalArgumentException("settings, host and dispatcher are all required");
        }
        this.settings = settings;
        this.host = host;
        this.client = new CollabClient(settings, dispatcher, this);
    }

    /**
     * For tests: drives the session through a recording transport instead of a socket.
     *
     * <p>Unambiguous against the public constructor because {@link CollabTransport} has several
     * methods and so cannot be written as a lambda, while {@link Executor} can.
     */
    CollabSession(CollabSettings settings, Host host, CollabTransport transport) {
        if (settings == null || host == null || transport == null) {
            throw new IllegalArgumentException("settings, host and transport are all required");
        }
        this.settings = settings;
        this.host = host;
        this.client = transport;
    }

    /** The ontology this session syncs, or null before it has started. */
    public OWLOntology getSubject() {
        synchronized (lock) {
            return subject;
        }
    }

    public void start() {
        synchronized (lock) {
            // Fixed here, once. Every later decision - what to publish, where to apply what
            // arrives - is made against this and not against Protege's current selection.
            subject = host.activeOntology();
        }
        client.start();
    }

    public void stop() {
        client.stop();
        cursors.clear();
        host.onPeersChanged();
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /** The cursors to draw. Owned here so the view has one thing to ask. */
    public PeerCursors getCursors() {
        return cursors;
    }

    /**
     * True while a remote operation is being applied.
     *
     * <p>Read by {@link #publishLocalChanges}, and exposed because the view's own bookkeeping -
     * adding a newly-arrived entity to the canvas, saving the sidecar - sometimes needs to know
     * whether a change originated here or elsewhere.
     */
    public boolean isApplyingRemote() {
        synchronized (lock) {
            return applyingRemote;
        }
    }

    /**
     * Publishes changes Protege has just applied.
     *
     * <p>Called from the ontology change listener, so it sees edits made anywhere in Protege -
     * the canvas, the class hierarchy, the Manchester syntax editor - not only this plugin's own.
     * That is most of the point of living inside an editor.
     *
     * @return how many changes were published
     */
    public int publishLocalChanges(List<? extends OWLOntologyChange> changes, CanvasHints hints) {
        synchronized (lock) {
            if (applyingRemote) {
                // This is the loop guard. These changes are the ones we have just applied on
                // someone else's behalf; republishing them is how two clients amplify forever.
                return 0;
            }
        }
        if (changes == null || changes.isEmpty()) {
            return 0;
        }
        int published = 0;
        int unshareable = 0;
        String reason = null;
        OWLOntology mine = getSubject();
        for (OWLOntologyChange change : changes) {
            if (mine != null && change.getOntology() != null
                    && !mine.equals(change.getOntology())) {
                // Another ontology entirely - most often an import module, which Protege reports
                // through the same listener as the edit file. Publishing it would push somebody
                // else's vocabulary onto this board and into every peer's edit file. Skipped
                // rather than counted as unshareable: there is nothing wrong with the axiom, it
                // simply is not this board's business, and a count would send the user looking for
                // a protocol limitation that is not there.
                continue;
            }
            OperationMapper.Outbound mapped = OperationMapper.toOperation(change,
                    settings.getDisplayName(), hints == null ? OperationMapper.NO_HINTS : hints);
            if (mapped.isMapped()) {
                client.publish(mapped.getOperation());
                // Observed, not declared: the term somebody is editing is the term they just
                // changed an axiom on, which is exactly what has been mapped here. Nothing to
                // switch on, and no claim on a term that was merely clicked.
                client.noteEdited(editedTerm(mapped.getOperation()));
                published++;
            } else {
                unshareable++;
                reason = mapped.getUnmappableReason();
            }
        }
        if (unshareable > 0) {
            int total;
            String example;
            synchronized (lock) {
                unshareableCount += unshareable;
                lastUnshareableReason = reason;
                total = unshareableCount;
                example = lastUnshareableReason;
            }
            host.onUnshareable(total, example);
        }
        return published;
    }

    /** Publishes the cursor, in graph space. Cheap enough to call on every mouse move. */
    public void publishCursor(double x, double y, String selection) {
        client.publishPresence(x, y, selection);
    }

    /** How many local changes have not been shareable this session, for a status line. */
    public int getUnshareableCount() {
        synchronized (lock) {
            return unshareableCount;
        }
    }

    // ------------------------------------------------------------------ CollabClient.Listener

    @Override
    public void onConnected(String user) {
        cursors.setSelf(user);
        host.onStatus("Collaborating as " + user
                + " on " + settings.getBoard(), true);
        // Announce a position straight away. Presence was published only from mouse motion, so
        // somebody who joined and then read the diagram without moving the mouse was invisible to
        // everyone else, indistinguishable from nobody having joined. What to announce is the host's
        // decision - it is the only side that knows where the view is looking.
        host.onJoined();
    }

    @Override
    public void onOperation(OntologyOperation operation) {
        // The ontology this session is about, not the one Protege happens to be showing. See the
        // field's own note for what reading it from the host each time did.
        OWLOntology ontology = getSubject();
        if (ontology == null) {
            ontology = host.activeOntology();
        }
        if (ontology == null) {
            // Nothing open to apply it to. Dropping is the only option, and saying so beats
            // failing silently - the two copies are now out of step.
            host.onStatus("Received a change with no ontology open; it was not applied", true);
            return;
        }
        // An operation is data from somewhere else - a web client, a third-party one, or one that
        // is simply wrong - and the bridge validates only its id and its type. Everything below
        // this point runs on the event thread, so one malformed field used to be enough to put an
        // exception there and take the session down. Dropping the operation and saying so keeps
        // the session alive; the two copies are out of step either way, and a live session that
        // reports a gap is worth more than a dead one that does not.
        OperationMapper.Inbound inbound;
        try {
            inbound = OperationMapper.toChanges(operation, ontology);
        } catch (RuntimeException malformed) {
            LOGGER.warn("OntoBoard: could not read {} from a peer; it was not applied",
                    operation, malformed);
            host.onStatus("A change from another editor could not be read and was not applied",
                    true);
            return;
        }
        if (!inbound.isUnderstood()) {
            LOGGER.info("OntoBoard: ignoring {} - {}", operation, inbound.getSkippedReason());
            return;
        }
        // Before the early return below: a term can already be in the requested state - a peer
        // re-asserting a declaration - while this board still has nowhere to draw it.
        if (operation.getIri() != null) {
            host.onPeerGeometry(operation.getIri(), operation.getData());
        }
        if (inbound.getChanges().isEmpty()) {
            // Already in the requested state. Convergence, not an error.
            return;
        }
        synchronized (lock) {
            applyingRemote = true;
        }
        try {
            host.applyChanges(new ArrayList<OWLOntologyChange>(inbound.getChanges()));
        } finally {
            // In a finally block deliberately: an exception from applyChanges that left the flag
            // set would silently stop this session publishing anything ever again, which is far
            // worse than the failed apply.
            synchronized (lock) {
                applyingRemote = false;
            }
        }
    }

    @Override
    public void onPeers(List<PeerPresence> peers) {
        cursors.update(peers);
        java.util.Map<String, String> editing = new java.util.LinkedHashMap<String, String>();
        for (PeerPresence peer : peers) {
            editing.put(peer.getUser(), peer.getOntologyIri());
        }
        peerOntologies = editing;
        host.onPeersChanged();
    }

    @Override
    public void onRefused(String reason) {
        cursors.clear();
        host.onPeersChanged();
        host.onStatus(reason, false);
        // Final, unlike a disconnect: the server rejected the token or the board, and retrying would
        // be rejected the same way. The host is told so it can stop holding a session nobody is in.
        host.onSessionEnded(reason);
    }

    @Override
    public void onDisconnected(String reason, int attempt, long retryInMillis) {
        cursors.clear();
        host.onPeersChanged();
        host.onStatus("Disconnected (" + reason + "); retrying in "
                + Math.max(1, retryInMillis / 1000) + "s", false);
    }

    /**
     * Somebody's edit arrived unreadable and was dropped.
     *
     * <p>Counted as well as announced. A single malformed frame is worth a line on the status
     * bar; a stream of them means the two ends disagree about the wire format, and a count is
     * what distinguishes the two - one is a glitch, forty is a bug in somebody's build.
     */
    @Override
    public void onUndecodableOperation() {
        undecodable++;
        // false, so the indicator goes amber. The socket is up - the frame arrived, after all -
        // but the parameter drives the light, and a copy that is quietly missing somebody's
        // change is exactly the state the amber light is for. Reporting this on a green light
        // would be reporting it where nobody looks.
        host.onStatus(undecodable == 1
                ? "An incoming change could not be read and was dropped - your copy may be "
                        + "missing it"
                : undecodable + " incoming changes could not be read and were dropped",
                false);
    }

    private int undecodable;

    /** For a diagnostics panel: everything the session knows about itself. */
    public Map<String, String> describe() {
        Map<String, String> description = new LinkedHashMap<String, String>();
        description.put("mode", settings.getMode().name());
        description.put("server", settings.getBridgeUrl());
        description.put("board", settings.getBoard());
        description.put("connected", String.valueOf(client.isConnected()));
        description.put("refused", String.valueOf(client.getRefusedReason()));
        description.put("queued operations", String.valueOf(client.getPendingCount()));
        description.put("dropped while offline",
                String.valueOf(client.getDroppedWhileOfflineCount()));
        description.put("changes that could not be shared", String.valueOf(getUnshareableCount()));
        return description;
    }

    /**
     * The term an operation is about, or null.
     *
     * <p>An operation's id is the entity IRI for everything that concerns one term. The canvas
     * also writes pipe-separated ids for an edge - {@code subprop|a|b} and the like - and those
     * name a relation rather than a term, so they claim nothing: a badge on one end of an edge
     * would be as likely to mislead as to help.
     */
    static String editedTerm(OntologyOperation operation) {
        if (operation == null) {
            return null;
        }
        String id = operation.getId();
        if (id == null || id.indexOf('|') >= 0) {
            return null;
        }
        return id.startsWith("http://") || id.startsWith("https://") ? id : null;
    }

}
