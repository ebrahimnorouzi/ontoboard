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

    /**
     * The same vocabulary gets the same colour on every board.
     *
     * <p>This is the property the old implementation did not have and the reason it changed. It took
     * {@code PALETTE[assignments.size() % PALETTE.length]}, so the colour depended on how many
     * namespaces had been seen before this one - which meant a board that dropped one namespace and
     * added another handed out a duplicate, and that BFO was blue on one board and green on the next.
     * Persisting the assignment hid it within a session and could not fix it across boards.
     */
    @Test
    void aNamespaceGetsTheSameColourOnEveryBoard() {
        String bfo = "http://purl.obolibrary.org/obo/BFO_0000002";

        String onOneBoard = new PrefixColours(new java.util.LinkedHashMap<String, String>())
                .colourFor(bfo);

        // A second, empty board - and one where two other vocabularies were seen first, which under
        // the old size-keyed scheme was enough to change the answer.
        java.util.Map<String, String> busier = new java.util.LinkedHashMap<String, String>();
        PrefixColours third = new PrefixColours(busier);
        third.colourFor("http://example.org/a#X");
        third.colourFor("http://example.org/b#Y");

        assertEquals(onOneBoard,
                new PrefixColours(new java.util.LinkedHashMap<String, String>()).colourFor(bfo));
        assertEquals(onOneBoard, third.colourFor(bfo));
    }

    /**
     * Two namespaces do not collide until every colour is spoken for.
     *
     * <p>Hashing alone would collide at two namespaces roughly one time in five, which on a board
     * with exactly two vocabularies - the commonest case there is, an edit file and its import - is
     * far too often. The probe forward is what prevents it.
     */
    @Test
    void namespacesDoNotShareAColourWhileOneIsFree() {
        java.util.Map<String, String> board = new java.util.LinkedHashMap<String, String>();
        PrefixColours colours = new PrefixColours(board);
        java.util.Set<String> used = new java.util.HashSet<String>();

        for (int i = 0; i < 5; i++) {
            used.add(colours.colourFor("http://example.org/ns" + i + "#Term"));
        }

        assertEquals(5, used.size(), "five namespaces must get five different colours: " + board);
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
