package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;

/** How the board's right-click menu reaches the assistant column of the same window. */
class AssistantSessionsTest {

    @Test
    void addTermReachesTheRegisteredView() {
        Object kit = new Object();
        List<IRI> added = new ArrayList<IRI>();
        AssistantSessions.register(kit, added::add);

        assertTrue(AssistantSessions.addTerm(kit, IRI.create("http://x#A")));

        assertEquals(1, added.size());
        AssistantSessions.unregister(kit);
    }

    @Test
    void noViewMeansFalse() {
        assertFalse(AssistantSessions.addTerm(new Object(), IRI.create("http://x#A")));
    }

    @Test
    void unregisterForgets() {
        Object kit = new Object();
        AssistantSessions.register(kit, iri -> { });

        AssistantSessions.unregister(kit);

        assertFalse(AssistantSessions.addTerm(kit, IRI.create("http://x#A")));
    }

    @Test
    void canAddIsTrueOnlyWithAView() {
        Object kit = new Object();
        assertFalse(AssistantSessions.canAdd(kit));

        AssistantSessions.register(kit, iri -> { });
        assertTrue(AssistantSessions.canAdd(kit));

        AssistantSessions.unregister(kit);
        assertFalse(AssistantSessions.canAdd(kit));
    }

    @Test
    void anotherWindowsViewIsNotReached() {
        Object mine = new Object();
        Object theirs = new Object();
        List<IRI> added = new ArrayList<IRI>();
        AssistantSessions.register(theirs, added::add);

        assertFalse(AssistantSessions.addTerm(mine, IRI.create("http://x#A")));
        assertTrue(added.isEmpty());
        AssistantSessions.unregister(theirs);
    }
}
