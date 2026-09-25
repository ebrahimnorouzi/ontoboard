package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * Two peers, a real socket, and the server this project ships.
 *
 * <p>Every other test in this package drives the client against a fake transport written by the same
 * hand as the client, so between them they prove the two agree with each other and nothing about
 * whether either agrees with {@code collab/bridge.mjs} or {@code collab/server.mjs}. That gap was
 * not hypothetical. It hid the defect that made the whole feature inert: {@code server.mjs} called
 * {@code openDirectConnection} on the {@code Server} wrapper rather than on the {@code Hocuspocus}
 * instance it holds, so resolving a board threw, the bridge answered "could not open board" and
 * closed the socket, and <em>no Protégé peer could join a session in any release</em> - while 1161
 * Java tests and 41 JavaScript tests passed and the documentation described a working feature.
 *
 * <p>What makes that possible is worth stating once: the bridge's own tests supply their own
 * {@code getDoc}, so the line joining the bridge to Hocuspocus was never executed by a test. A test
 * that injects the dependency cannot check the wiring.
 *
 * <p>Skipped where node or {@code collab/node_modules} is absent. A green run without them proves
 * nothing here, which is the same bargain the Docker-dependent tests make.
 */
class CollabLiveTest {

    private final CollabHarness harness = new CollabHarness();

    @AfterEach
    void stopEverything() {
        harness.close();
    }

    // ===================================================================== the tests

    /**
     * An axiom added by one peer reaches the other.
     *
     * <p>The single claim the feature rests on, and the one nothing checked.
     */
    @Test
    void anEditOnOnePeerArrivesAtTheOther() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/pizza");
        Scratch bob = new Scratch("http://example.org/live/pizza");
        CollabHarness.Peer a = harness.join("alice", "board-1", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "board-1", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass calzone = alice.factory.getOWLClass(IRI.create(alice.iri + "#Calzone"));
        int shared = a.getSession().publishLocalChanges(
                Collections.singletonList((OWLOntologyChange) new AddAxiom(alice.ontology,
                        alice.factory.getOWLDeclarationAxiom(calzone))),
                OperationMapper.NO_HINTS);
        assertEquals(1, shared,
                "alice's declaration must be shareable: " + a.getSession().describe());

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return b.getOntology().containsClassInSignature(calzone.getIRI());
            }
        });
        assertTrue(b.getOntology().containsClassInSignature(calzone.getIRI()),
                "bob's ontology must contain Calzone. bob applied " + b.getApplied()
                        + "; server said:\n" + harness.output());
    }

    /**
     * An editorial note added by one peer reaches the other.
     *
     * <p>Separate from the axiom case because a note travels as {@code updateAnnotation}, and the
     * bridge gates operation types by name: a type absent from {@code OPERATION_TYPES} is not
     * dropped but <em>refused</em>, and the bridge then closes the socket. An unlisted type
     * therefore does not lose one edit, it ends the session - which is only visible against the real
     * gate, never against a fake that accepts whatever it is given.
     */
    @Test
    void anEditorialNoteArrivesAtTheOtherPeer() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/notes");
        Scratch bob = new Scratch("http://example.org/live/notes");
        CollabHarness.Peer a = harness.join("alice", "board-notes", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "board-notes", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass pizza = alice.factory.getOWLClass(IRI.create(alice.iri + "#Pizza"));
        final OWLAnnotationProperty editorNote = alice.factory.getOWLAnnotationProperty(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000116"));
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        changes.add(new AddAxiom(alice.ontology, alice.factory.getOWLDeclarationAxiom(pizza)));
        changes.add(new AddAxiom(alice.ontology,
                alice.factory.getOWLAnnotationAssertionAxiom(editorNote, pizza.getIRI(),
                        alice.factory.getOWLLiteral("Check the base before release."))));

        assertEquals(2, changes.size());
        int shared = a.getSession().publishLocalChanges(changes, OperationMapper.NO_HINTS);
        assertEquals(2, shared, "the declaration and the note must both be shareable: "
                + a.getSession().describe());

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return noteOn(b.getOntology(), pizza, editorNote) != null;
            }
        });
        assertEquals("Check the base before release.",
                noteOn(b.getOntology(), pizza, editorNote),
                "bob must see the note alice wrote. bob applied " + b.getApplied());
        assertTrue(b.getSession().isConnected(),
                "the bridge must not have refused the note's operation type and closed the socket");
    }

    /**
     * Two peers on one board editing different ontologies warn each other.
     *
     * <p>{@code CollabSession.peerOntologyWarning} exists for the case that costs the most and shows
     * the least: two people whose board id collides - a shared default, or the same override typed
     * twice - editing unrelated ontologies, each applying the other's axioms to their own file.
     * Nothing local can detect it. The only evidence is what each peer says it is editing, so the
     * warning is worth exactly as much as the round trip carrying it, and that round trip crosses
     * from Java into JavaScript and back.
     *
     * <p>It was worth nothing. The plugin sent {@code ontology} in its hello and read it back off
     * each peer; the bridge stored it on the connection and {@code livePeers} left it out of the
     * payload it broadcast. Every peer's ontology arrived empty, so the warning could never fire -
     * and both sides' tests passed, because {@code BoardIdTest} covers the wording and the bridge's
     * tests never mentioned the field.
     */
    @Test
    void peersEditingDifferentOntologiesAreWarnedAboutEachOther() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/pizza");
        Scratch bob = new Scratch("http://example.org/live/entirely-different");
        final CollabHarness.Peer a = harness.join("alice", "shared-board", alice.ontology);
        CollabHarness.Peer b = harness.join("bob", "shared-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        // null, not empty: BoardId.peerMismatchWarning documents null as "nothing to say", and
        // SchemaCanvasView relies on that to clear the warning it last showed.
        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return a.getSession().peerOntologyWarning() != null;
            }
        });

        String warning = a.getSession().peerOntologyWarning();
        assertNotNull(warning,
                "alice must be warned that bob is on this board with a different ontology. No "
                        + "warning means the peers payload carried no ontology field, so every peer "
                        + "looks like it is editing the same thing. Session: "
                        + a.getSession().describe());
        // The peer, not the IRI: BoardId names who to go and talk to, which is the actionable half,
        // and BoardIdTest already pins the wording. What is asserted here is the part that depends
        // on the round trip - that bob was identified at all.
        assertTrue(warning.contains("bob"),
                "the warning must name the peer alice has to talk to: " + warning);
    }

    /** Two peers editing the same ontology are not warned, or the warning becomes noise. */
    @Test
    void peersEditingTheSameOntologyAreNotWarned() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/agreed");
        Scratch bob = new Scratch("http://example.org/live/agreed");
        final CollabHarness.Peer a = harness.join("alice", "agreed-board", alice.ontology);
        CollabHarness.Peer b = harness.join("bob", "agreed-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        // Waited for, not assumed: the absence of a warning means nothing until bob has actually
        // been announced. Without this the test would also pass on an ontology field that never
        // arrives, which is the very thing the previous test pins.
        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return a.getSession().getCursors().countAt(System.currentTimeMillis()) > 0;
            }
        });
        assertTrue(a.getSession().getCursors().countAt(System.currentTimeMillis()) > 0,
                "bob never showed up as a peer, so the absence of a warning proves nothing");
        assertNull(a.getSession().peerOntologyWarning(),
                "two peers on the same ontology must not be warned about each other");
    }

    /** A peer's own edits must not come back to it, or every change would be applied twice. */
    @Test
    void aPeerDoesNotReceiveItsOwnEdits() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/echo");
        Scratch bob = new Scratch("http://example.org/live/echo");
        CollabHarness.Peer a = harness.join("alice", "echo-board", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "echo-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass solo = alice.factory.getOWLClass(IRI.create(alice.iri + "#Solo"));
        a.getSession().publishLocalChanges(
                Collections.singletonList((OWLOntologyChange) new AddAxiom(alice.ontology,
                        alice.factory.getOWLDeclarationAxiom(solo))),
                OperationMapper.NO_HINTS);

        // Bob receiving it is the moment alice would have, had echo been broken - so this is a real
        // synchronisation point rather than a sleep chosen by guesswork.
        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return b.getOntology().containsClassInSignature(solo.getIRI());
            }
        });
        assertTrue(b.getOntology().containsClassInSignature(solo.getIRI()),
                "bob must have received it, or this proves nothing about echo");
        assertTrue(a.getApplied().isEmpty(),
                "alice must not have been sent her own operation back: " + a.getApplied());
    }


    /**
     * A label on a new term reaches the other peer, and so does a rename.
     *
     * <p>Its own test because it travels differently from every other edit and was broken in a way
     * nothing else could reveal. A label is not sent as an annotation: the web client models it as a
     * field on the entity, so the plugin sends {@code updateClass} carrying
     * {@code data.updates.label} - the only payload in the protocol with a nested object in it.
     *
     * <p>The inbound reader flattened every payload field through a scalar helper whose comment said
     * nested structures were "stringified rather than lost". They were lost. {@code updates} arrived
     * as the String {@code {"label":"calzone"}}, the code applying it asks
     * {@code updates instanceof Map} first, and a String is not a Map - so the operation was
     * delivered, produced no changes, and returned without a word. Renaming a term, which is about
     * the commonest thing anybody does to an ontology, silently never reached a colleague.
     *
     * <p>Every existing test missed it for the same reason: outbound uses Jackson's
     * {@code valueToTree}, which preserves nesting exactly, and each test built its operations in
     * process from a real Map. The shape only collapsed coming back in, so seeing it required
     * crossing the socket.
     */
    @Test
    void aLabelAndThenARenameReachTheOtherPeer() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/labels");
        Scratch bob = new Scratch("http://example.org/live/labels");
        CollabHarness.Peer a = harness.join("alice", "label-board", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "label-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass calzone = alice.factory.getOWLClass(IRI.create(alice.iri + "#Calzone"));

        // Applied before publishing, which is the order Protege works in - the view publishes from
        // the ontology-change listener. It also matters here: a label maps to an update of the class
        // it sits on, and the mapper can only identify that class once it is declared.
        List<OWLOntologyChange> creation = new ArrayList<OWLOntologyChange>();
        creation.add(new AddAxiom(alice.ontology,
                alice.factory.getOWLDeclarationAxiom(calzone)));
        creation.add(new AddAxiom(alice.ontology, alice.factory.getOWLAnnotationAssertionAxiom(
                alice.factory.getRDFSLabel(), calzone.getIRI(),
                alice.factory.getOWLLiteral("calzone"))));
        alice.manager.applyChanges(creation);
        assertEquals(2, a.getSession().publishLocalChanges(creation, OperationMapper.NO_HINTS),
                "both the declaration and the label must be shareable: "
                        + a.getSession().describe());

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return "calzone".equals(labelOf(b.getOntology(), calzone));
            }
        });
        assertEquals("calzone", labelOf(b.getOntology(), calzone),
                "bob must see the label alice gave the new term. He applied " + b.getApplied()
                        + "; server said:\n" + harness.output());

        // And the rename, which arrives as a removal followed by an addition.
        List<OWLOntologyChange> rename = new ArrayList<OWLOntologyChange>();
        rename.add(new RemoveAxiom(alice.ontology, alice.factory.getOWLAnnotationAssertionAxiom(
                alice.factory.getRDFSLabel(), calzone.getIRI(),
                alice.factory.getOWLLiteral("calzone"))));
        rename.add(new AddAxiom(alice.ontology, alice.factory.getOWLAnnotationAssertionAxiom(
                alice.factory.getRDFSLabel(), calzone.getIRI(),
                alice.factory.getOWLLiteral("calzone (folded pizza)"))));
        alice.manager.applyChanges(rename);
        a.getSession().publishLocalChanges(rename, OperationMapper.NO_HINTS);

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return "calzone (folded pizza)".equals(labelOf(b.getOntology(), calzone));
            }
        });
        assertEquals("calzone (folded pizza)", labelOf(b.getOntology(), calzone),
                "bob must end up with the new label and only it. He applied " + b.getApplied());
        assertEquals(1, labelCount(b.getOntology(), calzone),
                "a rename must replace the label, not leave both on the term");
    }

    // ===================================================================== plumbing

    private String connectionFailure(CollabHarness.Peer... peers) {
        StringBuilder text = new StringBuilder("a session never connected.");
        for (CollabHarness.Peer peer : peers) {
            text.append("\n  ").append(peer.getUser()).append(": ").append(peer.getStatus());
        }
        return text.append("\nServer said:\n").append(harness.output()).toString();
    }

    /** The rdfs:label on a class, or null. */
    private static String labelOf(OWLOntology ontology, OWLClass subject) {
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(subject.getIRI())) {
            if (axiom.getProperty().isLabel() && axiom.getValue().asLiteral().isPresent()) {
                return axiom.getValue().asLiteral().get().getLiteral();
            }
        }
        return null;
    }

    /** How many labels the term carries - a rename that adds without removing leaves two. */
    private static int labelCount(OWLOntology ontology, OWLClass subject) {
        int labels = 0;
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(subject.getIRI())) {
            if (axiom.getProperty().isLabel()) {
                labels++;
            }
        }
        return labels;
    }

    private static String noteOn(OWLOntology ontology, OWLClass subject,
            OWLAnnotationProperty property) {
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(subject.getIRI())) {
            if (axiom.getProperty().equals(property) && axiom.getValue().asLiteral().isPresent()) {
                return axiom.getValue().asLiteral().get().getLiteral();
            }
        }
        return null;
    }

    /** An empty ontology with its own manager, so two peers share nothing but the socket. */
    private static final class Scratch {
        private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        private final OWLDataFactory factory = manager.getOWLDataFactory();
        private final OWLOntology ontology;
        private final String iri;

        Scratch(String iri) throws Exception {
            this.iri = iri;
            this.ontology = manager.createOntology(IRI.create(iri));
        }
    }
}
