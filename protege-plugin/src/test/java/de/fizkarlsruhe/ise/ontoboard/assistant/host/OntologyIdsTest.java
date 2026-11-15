package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.common.base.Optional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SetOntologyID;

/** The column's conversations are kept per ontology ID, and follow a change of that ID. */
class OntologyIdsTest {

    /**
     * Review 1.153.0 reasoner-01: the ontology's version IRI was changed - a release's bump -
     * and every later "Explain the reasoner problem..." went to a new, empty conversation the
     * column did not show, while the one on screen stayed under the old ID.
     */
    @Test
    void aConversationFollowsANewVersionIri() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI iri = IRI.create("http://example.org/onto");
        OWLOntology ontology = manager.createOntology(new OWLOntologyID(Optional.of(iri),
                Optional.of(IRI.create("http://example.org/onto/1.0"))));
        OWLOntologyID before = ontology.getOntologyID();
        Map<OWLOntologyID, String> sessions = new HashMap<OWLOntologyID, String>();
        sessions.put(before, "the conversation on screen");
        OWLOntologyID after = new OWLOntologyID(Optional.of(iri),
                Optional.of(IRI.create("http://example.org/onto/1.1")));
        List<OWLOntologyChange> changes = java.util.Collections.<OWLOntologyChange>singletonList(
                new SetOntologyID(ontology, after));
        manager.applyChanges(changes);

        OntologyIds.follow(sessions, changes);

        assertEquals("the conversation on screen", sessions.get(ontology.getOntologyID()));
        assertFalse(sessions.containsKey(before), sessions.toString());
    }

    /** Another ontology's conversation, and one whose ID did not change, stay where they are. */
    @Test
    void otherConversationsStay() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology one = manager.createOntology(IRI.create("http://example.org/one"));
        OWLOntology two = manager.createOntology(IRI.create("http://example.org/two"));
        Map<OWLOntologyID, String> sessions = new HashMap<OWLOntologyID, String>();
        sessions.put(one.getOntologyID(), "one");
        sessions.put(two.getOntologyID(), "two");

        OntologyIds.follow(sessions, java.util.Collections.<OWLOntologyChange>emptyList());

        assertEquals("one", sessions.get(one.getOntologyID()));
        assertEquals("two", sessions.get(two.getOntologyID()));
    }
}
