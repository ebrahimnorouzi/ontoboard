package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrefixColoursTest {

    @Test
    void namespaceIsEverythingUpToTheFinalHash() {
        assertEquals("http://example.org/o#",
                PrefixColours.namespaceOf("http://example.org/o#Person"));
    }

    @Test
    void namespaceFallsBackToTheFinalSlashWhenThereIsNoHash() {
        assertEquals("http://example.org/o/",
                PrefixColours.namespaceOf("http://example.org/o/Person"));
    }

    @Test
    void namespaceOfADelimiterlessIriIsTheWholeString() {
        assertEquals("urn:abc", PrefixColours.namespaceOf("urn:abc"));
    }

    @Test
    void theSameNamespaceAlwaysGetsTheSameColour() {
        PrefixColours colours = new PrefixColours(new LinkedHashMap<String, String>());
        String first = colours.colourFor("http://example.org/o#Person");
        String again = colours.colourFor("http://example.org/o#Organization");
        assertEquals(first, again, "two entities in one namespace must share a colour");
    }

    @Test
    void differentNamespacesGetDifferentColours() {
        PrefixColours colours = new PrefixColours(new LinkedHashMap<String, String>());
        String a = colours.colourFor("http://example.org/a#Thing");
        String b = colours.colourFor("http://example.org/b#Thing");
        assertNotEquals(a, b, "distinguishing vocabularies is the whole point");
    }

    /**
     * The assignment must be written back to the caller's map, because that map is the
     * sidecar's {@code prefixColors}. Without persistence the palette would be handed out
     * in projection order and the same ontology would change colour between sessions.
     */
    @Test
    void assignmentsArePersistedIntoTheCallersMap() {
        Map<String, String> store = new LinkedHashMap<String, String>();
        String colour = new PrefixColours(store).colourFor("http://example.org/o#Person");

        assertEquals(1, store.size());
        assertEquals(colour, store.get("http://example.org/o#"));
    }

    @Test
    void aPreviouslyStoredColourIsReusedRatherThanReassigned() {
        Map<String, String> store = new LinkedHashMap<String, String>();
        store.put("http://example.org/o#", "#123456");

        assertEquals("#123456",
                new PrefixColours(store).colourFor("http://example.org/o#Person"),
                "a colour restored from the sidecar must survive, or diagrams would "
                        + "recolour themselves on every restart");
    }

    @Test
    void colourIsAlwaysAHexValueTheStylesheetCanUse() {
        PrefixColours colours = new PrefixColours(new LinkedHashMap<String, String>());
        for (int i = 0; i < 12; i++) {
            String colour = colours.colourFor("http://example.org/ns" + i + "#Thing");
            assertTrue(colour.matches("#[0-9A-Fa-f]{6}"), "not a hex colour: " + colour);
        }
    }
}
