package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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


    /**
     * Renaming one language's label leaves the others alone.
     *
     * <p>Data loss, and the sort that is discovered months later by somebody asking where the German
     * labels went. A label change used to arrive as "this term's label is now X", and the code
     * applying it removed <em>every</em> {@code rdfs:label} on the term before adding the new one -
     * so a curator renaming an English label silently deleted the German and French labels on every
     * other peer, with nothing in the operation to put them back from.
     *
     * <p>The operation now carries the language tag beside the text, and a peer replaces only the
     * label in that language. An operation with no tag is taken as being about the untagged label,
     * which is what an older peer means by it - so an old peer talking to a new one loses nothing,
     * and the new one stops destroying what it was never told about.
     */
    @Test
    void renamingOneLanguageLeavesTheOtherLanguagesAlone() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/multilingual");
        Scratch bob = new Scratch("http://example.org/live/multilingual");
        CollabHarness.Peer a = harness.join("alice", "lang-board", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "lang-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass pizza = alice.factory.getOWLClass(IRI.create(alice.iri + "#Pizza"));

        // A term with three labels, which is ordinary in an OBO ontology with translations.
        List<OWLOntologyChange> creation = new ArrayList<OWLOntologyChange>();
        creation.add(new AddAxiom(alice.ontology, alice.factory.getOWLDeclarationAxiom(pizza)));
        creation.add(labelChange(alice, pizza, "pizza", "en"));
        creation.add(labelChange(alice, pizza, "Pizza", "de"));
        creation.add(labelChange(alice, pizza, "pizza", null));
        alice.manager.applyChanges(creation);
        a.getSession().publishLocalChanges(creation, OperationMapper.NO_HINTS);

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return labelCount(b.getOntology(), pizza) >= 3;
            }
        });
        assertEquals(3, labelCount(b.getOntology(), pizza),
                "bob must have all three labels before the rename, or this proves nothing. He has "
                        + labelsOf(b.getOntology(), pizza));

        // Alice renames only the English one.
        List<OWLOntologyChange> rename = new ArrayList<OWLOntologyChange>();
        rename.add(new RemoveAxiom(alice.ontology,
                alice.factory.getOWLAnnotationAssertionAxiom(alice.factory.getRDFSLabel(),
                        pizza.getIRI(), alice.factory.getOWLLiteral("pizza", "en"))));
        rename.add(labelChange(alice, pizza, "pizza pie", "en"));
        alice.manager.applyChanges(rename);
        a.getSession().publishLocalChanges(rename, OperationMapper.NO_HINTS);

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return labelsOf(b.getOntology(), pizza).contains("pizza pie@en");
            }
        });

        String labels = labelsOf(b.getOntology(), pizza);
        assertTrue(labels.contains("pizza pie@en"), "the English label must be the new one: " + labels);
        assertTrue(labels.contains("Pizza@de"),
                "the German label must survive a rename of the English one: " + labels);
        assertTrue(labels.contains("pizza@"),
                "the untagged label must survive too: " + labels);
        assertEquals(3, labelCount(b.getOntology(), pizza),
                "still three labels, one of them changed - not one label, or four: " + labels);
    }


    /**
     * An edit to a different ontology does not reach the board.
     *
     * <p>Protégé reports every loaded ontology's changes through one listener, and an ODK project has
     * several loaded at once - the edit file plus every import module under {@code imports/}. The
     * session used to publish whatever it was handed, so touching an import module pushed somebody
     * else's vocabulary onto the board, and every peer applied it to their own edit file.
     *
     * <p>The same defect had a second face: inbound operations were applied to whatever Protégé had
     * made active since the session started, so switching ontology while connected redirected a
     * peer's edits into an unrelated file. A session is now bound to one ontology when it starts, and
     * both directions are checked against it.
     */
    @Test
    void anEditToAnotherOntologyIsNotPublished() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/edit-file");
        Scratch bob = new Scratch("http://example.org/live/edit-file");
        CollabHarness.Peer a = harness.join("alice", "binding-board", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "binding-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));
        assertEquals(alice.ontology, a.getSession().getSubject(),
                "the session must be bound to the ontology it started on");

        // A second ontology in the same manager, standing in for an import module.
        OWLOntology importModule = alice.manager.createOntology(
                IRI.create("http://example.org/live/food-import"));
        OWLClass borrowed = alice.factory.getOWLClass(
                IRI.create("http://example.org/live/food-import#Cheese"));
        List<OWLOntologyChange> intoTheModule = new ArrayList<OWLOntologyChange>();
        intoTheModule.add(new AddAxiom(importModule,
                alice.factory.getOWLDeclarationAxiom(borrowed)));
        alice.manager.applyChanges(intoTheModule);

        assertEquals(0, a.getSession().publishLocalChanges(intoTheModule, OperationMapper.NO_HINTS),
                "an edit to another ontology must not be published to this board");
        assertEquals(0, a.getSession().getUnshareableCount(),
                "and must not be counted as an unshareable axiom either - there is nothing wrong "
                        + "with it, it is simply not this board's business");

        // And the edit file still works, so the filter has not broken the ordinary case.
        final OWLClass calzone = alice.factory.getOWLClass(IRI.create(alice.iri + "#Calzone"));
        List<OWLOntologyChange> intoTheEditFile = new ArrayList<OWLOntologyChange>();
        intoTheEditFile.add(new AddAxiom(alice.ontology,
                alice.factory.getOWLDeclarationAxiom(calzone)));
        alice.manager.applyChanges(intoTheEditFile);
        assertEquals(1, a.getSession().publishLocalChanges(intoTheEditFile,
                OperationMapper.NO_HINTS), "the edit file's own changes must still publish");

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return b.getOntology().containsClassInSignature(calzone.getIRI());
            }
        });
        assertTrue(b.getOntology().containsClassInSignature(calzone.getIRI()),
                "bob must receive the edit-file change");
        assertFalse(b.getOntology().containsClassInSignature(borrowed.getIRI()),
                "bob must never have been sent the import module's class: " + b.getApplied());
    }


    /**
     * A typed literal keeps its datatype, so obsoletion survives the wire.
     *
     * <p>The sharpest case of a general defect. Every non-label annotation used to travel as its
     * lexical form alone and be rebuilt as {@code xsd:string}, and {@code Obsoletion} writes
     * {@code owl:deprecated "true"^^xsd:boolean}. So a peer ended up with
     * {@code owl:deprecated "true"^^xsd:string}, for which OWL API's
     * {@code isDeprecatedIRIAssertion} is <em>false</em>: a term retired on one machine stayed live
     * on the other, both files claimed to say the same thing, and nothing anywhere reported it.
     *
     * <p>Nothing could report it. No reasoner interprets an annotation, and the lexical form matched
     * - so even a diff of the text agreed. It is visible only by asking OWL API what the axiom
     * means, which is what this does.
     */
    @Test
    void aTypedLiteralKeepsItsDatatypeSoObsoletionCrosses() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/obsolete");
        Scratch bob = new Scratch("http://example.org/live/obsolete");
        CollabHarness.Peer a = harness.join("alice", "obsolete-board", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "obsolete-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass retired = alice.factory.getOWLClass(IRI.create(alice.iri + "#OldTopping"));
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        changes.add(new AddAxiom(alice.ontology,
                alice.factory.getOWLDeclarationAxiom(retired)));
        // Exactly what odk/Obsoletion writes.
        changes.add(new AddAxiom(alice.ontology, alice.factory.getOWLAnnotationAssertionAxiom(
                alice.factory.getOWLAnnotationProperty(
                        org.semanticweb.owlapi.vocab.OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                retired.getIRI(), alice.factory.getOWLLiteral(true))));
        alice.manager.applyChanges(changes);
        a.getSession().publishLocalChanges(changes, OperationMapper.NO_HINTS);

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return !b.getOntology().getAnnotationAssertionAxioms(retired.getIRI()).isEmpty();
            }
        });

        boolean deprecatedAtBobsEnd = false;
        String whatArrived = "nothing";
        for (OWLAnnotationAssertionAxiom axiom
                : b.getOntology().getAnnotationAssertionAxioms(retired.getIRI())) {
            whatArrived = String.valueOf(axiom.getValue());
            if (axiom.isDeprecatedIRIAssertion()) {
                deprecatedAtBobsEnd = true;
            }
        }
        assertTrue(deprecatedAtBobsEnd,
                "bob must see the term as deprecated, not merely annotated with the text \"true\". "
                        + "What arrived: " + whatArrived);
    }

    /** A language-tagged non-label annotation keeps its tag. */
    @Test
    void aLanguageTaggedDefinitionKeepsItsTag() throws Exception {
        assumeTrue(CollabHarness.isAvailable(), "node or collab/node_modules is not available");
        harness.start();

        Scratch alice = new Scratch("http://example.org/live/tagged");
        Scratch bob = new Scratch("http://example.org/live/tagged");
        CollabHarness.Peer a = harness.join("alice", "tagged-board", alice.ontology);
        final CollabHarness.Peer b = harness.join("bob", "tagged-board", bob.ontology);
        assertTrue(harness.waitUntilConnected(a, b), connectionFailure(a, b));

        final OWLClass pizza = alice.factory.getOWLClass(IRI.create(alice.iri + "#Pizza"));
        final OWLAnnotationProperty definition = alice.factory.getOWLAnnotationProperty(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000115"));
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        changes.add(new AddAxiom(alice.ontology, alice.factory.getOWLDeclarationAxiom(pizza)));
        changes.add(new AddAxiom(alice.ontology, alice.factory.getOWLAnnotationAssertionAxiom(
                definition, pizza.getIRI(),
                alice.factory.getOWLLiteral("Ein Gericht aus Italien.", "de"))));
        alice.manager.applyChanges(changes);
        a.getSession().publishLocalChanges(changes, OperationMapper.NO_HINTS);

        harness.waitFor(new CollabHarness.Condition() {
            @Override
            public boolean isMet() {
                return !b.getOntology().getAnnotationAssertionAxioms(pizza.getIRI()).isEmpty();
            }
        });

        String tag = "";
        for (OWLAnnotationAssertionAxiom axiom
                : b.getOntology().getAnnotationAssertionAxioms(pizza.getIRI())) {
            if (axiom.getValue().asLiteral().isPresent()) {
                tag = axiom.getValue().asLiteral().get().getLang();
            }
        }
        assertEquals("de", tag,
                "the definition must arrive tagged @de, not as an untagged xsd:string");
    }

    // ===================================================================== plumbing

    private String connectionFailure(CollabHarness.Peer... peers) {
        StringBuilder text = new StringBuilder("a session never connected.");
        for (CollabHarness.Peer peer : peers) {
            text.append("\n  ").append(peer.getUser()).append(": ").append(peer.getStatus());
        }
        return text.append("\nServer said:\n").append(harness.output()).toString();
    }

    /** An rdfs:label addition, with a language tag when one is given. */
    private static OWLOntologyChange labelChange(Scratch peer, OWLClass subject, String text,
            String language) {
        return new AddAxiom(peer.ontology, peer.factory.getOWLAnnotationAssertionAxiom(
                peer.factory.getRDFSLabel(), subject.getIRI(),
                language == null ? peer.factory.getOWLLiteral(text)
                        : peer.factory.getOWLLiteral(text, language)));
    }

    /** Every label on a term as {@code text@tag}, sorted, for a failure message worth reading. */
    private static String labelsOf(OWLOntology ontology, OWLClass subject) {
        java.util.TreeSet<String> labels = new java.util.TreeSet<String>();
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(subject.getIRI())) {
            if (axiom.getProperty().isLabel() && axiom.getValue().asLiteral().isPresent()) {
                org.semanticweb.owlapi.model.OWLLiteral literal =
                        axiom.getValue().asLiteral().get();
                labels.add(literal.getLiteral() + "@" + literal.getLang());
            }
        }
        return labels.toString();
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
