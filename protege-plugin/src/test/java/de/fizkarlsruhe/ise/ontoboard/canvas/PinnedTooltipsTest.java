package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Keeping a tooltip open, which is what makes comparing two terms possible.
 *
 * <p>OntoGraf has the feature and OntoBoard did not: a hover tooltip vanishes when the pointer
 * leaves, so holding "what is A a subclass of" next to "what is B disjoint from" was a matter of
 * memory.
 */
class PinnedTooltipsTest {

    private static Map<String, String> tooltips(String... idThenHtml) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int at = 0; at < idThenHtml.length; at += 2) {
            map.put(idThenHtml[at], idThenHtml[at + 1]);
        }
        return map;
    }

    /** Pinning twice unpins. */
    @Test
    void pinningIsAToggle() {
        PinnedTooltips pins = new PinnedTooltips();

        assertTrue(pins.toggle("a"));
        assertTrue(pins.isPinned("a"));
        assertFalse(pins.toggle("a"));
        assertFalse(pins.isPinned("a"));
        assertTrue(pins.isEmpty());
    }

    /** Nothing is pinned for a cell with no id. */
    @Test
    void anIdlessCellCannotBePinned() {
        PinnedTooltips pins = new PinnedTooltips();

        assertFalse(pins.toggle(null));
        assertFalse(pins.toggle(""));
        assertEquals(0, pins.size());
    }

    /**
     * Past the limit the oldest card closes, not the newest.
     *
     * <p>The one just asked for is the one wanted. Dropping it instead would make the fifth pin
     * look like a broken key.
     */
    @Test
    void theOldestGoesWhenTheLimitIsReached() {
        PinnedTooltips pins = new PinnedTooltips();
        for (int at = 0; at < PinnedTooltips.MOST_PINNED; at++) {
            pins.toggle("cell" + at);
        }

        assertTrue(pins.toggle("newest"));

        assertEquals(PinnedTooltips.MOST_PINNED, pins.size());
        assertTrue(pins.isPinned("newest"));
        assertFalse(pins.isPinned("cell0"), "the oldest should have closed");
        assertTrue(pins.isPinned("cell1"));
    }

    /** Cards come back oldest first, so they do not reshuffle as more are opened. */
    @Test
    void cardsKeepTheOrderTheyWerePinnedIn() {
        PinnedTooltips pins = new PinnedTooltips();
        pins.toggle("a");
        pins.toggle("b");

        List<PinnedTooltips.Card> cards = pins.cards(tooltips(
                "a", "<html><b>Alpha</b><br>Class", "b", "<html><b>Beta</b><br>Class"));

        assertEquals(2, cards.size());
        assertEquals("a", cards.get(0).getCellId());
        assertEquals("b", cards.get(1).getCellId());
    }

    /** A pinned cell with no tooltip produces no card rather than an empty box. */
    @Test
    void aCellWithNoTooltipDrawsNothing() {
        PinnedTooltips pins = new PinnedTooltips();
        pins.toggle("ghost");

        assertTrue(pins.cards(tooltips()).isEmpty());
        assertTrue(pins.cards(null).isEmpty());
    }

    /**
     * A rebuild drops pins whose cell has gone.
     *
     * <p>Otherwise the card stays where that term used to be and keeps describing it, which is
     * worse than losing the pin: everything it says is true, and none of it is about anything
     * on the board.
     */
    @Test
    void aRebuildDropsPinsForCellsThatLeft() {
        PinnedTooltips pins = new PinnedTooltips();
        pins.toggle("stays");
        pins.toggle("goes");

        int dropped = pins.keepOnly(new LinkedHashSet<String>(Arrays.asList("stays", "other")));

        assertEquals(1, dropped);
        assertTrue(pins.isPinned("stays"));
        assertFalse(pins.isPinned("goes"));
    }

    /** An empty board drops all of them. */
    @Test
    void nothingOnTheBoardMeansNoPins() {
        PinnedTooltips pins = new PinnedTooltips();
        pins.toggle("a");

        assertEquals(1, pins.keepOnly(null));
        assertTrue(pins.isEmpty());
    }

    // ---------- the text ----------

    /** A card says what the hover tooltip says, with the markup taken out. */
    @Test
    void theCardReadsLikeTheTooltip() {
        PinnedTooltips pins = new PinnedTooltips();
        pins.toggle("a");

        List<String> lines = pins.cards(tooltips("a",
                "<html><b>Pizza</b><br>Class<br><font size=\"-2\">http://x#Pizza</font>"))
                .get(0).getLines();

        assertEquals(Arrays.asList("Pizza", "Class", "http://x#Pizza"), lines);
    }

    /** Escaped markup in a label comes back as the characters it stood for. */
    @Test
    void entitiesAreUndone() {
        assertEquals(Arrays.asList("a < b & c > d"),
                CanvasTooltips.toLines("<html>a &lt; b &amp; c &gt; d"));
    }

    /**
     * An escaped ampersand in front of an entity stays escaped text.
     *
     * <p>Undoing {@code &amp;} first would turn the stored {@code &amp;lt;} - which renders as
     * the literal text "&lt;" - into a less-than sign.
     */
    @Test
    void anEscapedEntityIsNotUndoneTwice() {
        assertEquals(Arrays.asList("&lt;"), CanvasTooltips.toLines("<html>&amp;lt;"));
    }

    /** Empty lines are dropped, so a card has no blank rows in it. */
    @Test
    void emptyLinesAreDropped() {
        assertEquals(Arrays.asList("one", "two"),
                CanvasTooltips.toLines("<html><br>one<br><br>two<br>"));
        assertTrue(CanvasTooltips.toLines(null).isEmpty());
        assertTrue(CanvasTooltips.toLines("<html><br></html>").isEmpty());
    }

    /** A long line is broken, so a card is a card and not a stripe across the board. */
    @Test
    void longLinesWrap() {
        StringBuilder sentence = new StringBuilder();
        while (sentence.length() < PinnedTooltips.WRAP_AT * 2) {
            sentence.append("word ");
        }

        List<String> wrapped = PinnedTooltips.wrapAll(
                Arrays.asList(sentence.toString().trim()));

        assertTrue(wrapped.size() >= 2, wrapped.toString());
        for (String line : wrapped) {
            assertTrue(line.length() <= PinnedTooltips.WRAP_AT, "too long: " + line);
        }
    }

    /** A single word longer than the limit is kept whole rather than cut mid-IRI. */
    @Test
    void anUnbreakableWordSurvives() {
        String iri = "http://purl.obolibrary.org/obo/a-very-long-identifier-that-will-not-fit";

        List<String> wrapped = PinnedTooltips.wrapAll(Arrays.asList(iri));

        assertEquals(Arrays.asList(iri), wrapped);
    }
}
