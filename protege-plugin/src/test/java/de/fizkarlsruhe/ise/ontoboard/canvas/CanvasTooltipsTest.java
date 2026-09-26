package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the canvas says on hover.
 *
 * <p>Worth testing rather than eyeballing, because the wording is the whole feature and because the
 * thing it replaced was wrong in a way nobody reported: hovering an arrow used to return the cell's
 * internal id, {@code rest|some|http://…#Pizza|http://…#hasTopping|http://…#PizzaTopping}. It read
 * as a plugin bug and it withheld the answer to the question a schema diagram raises most often.
 */
class CanvasTooltipsTest {

    private static final String PIZZA = "http://example.org/pizza#Pizza";
    private static final String TOPPING = "http://example.org/pizza#PizzaTopping";

    private static Map<String, String> labels() {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put(PIZZA, "pizza");
        labels.put(TOPPING, "pizza topping");
        return labels;
    }

    // ===================================================================== edges

    /** An edge names its two ends, the relation, and the OWL construct behind it. */
    @Test
    void anObjectPropertyEdgeReadsAsTheAxiomItStandsFor() {
        String tooltip = CanvasTooltips.forEdge(new CanvasEdge(
                "rest|some|" + PIZZA + "|http://example.org/pizza#hasTopping|" + TOPPING,
                PIZZA, TOPPING, "has topping", CanvasEdge.Kind.OBJECT_PROPERTY), labels());

        assertTrue(tooltip.contains("pizza"), tooltip);
        assertTrue(tooltip.contains("has topping"), tooltip);
        assertTrue(tooltip.contains("pizza topping"), tooltip);
        assertTrue(tooltip.contains("object property restriction"), tooltip);
        // And none of the internal id, which is what it used to show and all it used to show.
        assertFalse(tooltip.contains("rest|some|"), tooltip);
    }

    /**
     * An inferred edge says it is inferred.
     *
     * <p>The most consequential line in any of these. An inferred edge is not in the ontology: it is
     * a conclusion, it vanishes when inferences are switched off, and it cannot be deleted. Until now
     * the only signal was a dashed line somebody had to look up in the legend.
     */
    @Test
    void anInferredEdgeSaysSoAndAnAssertedOneSaysSo() {
        String inferred = CanvasTooltips.forEdge(new CanvasEdge("inf-sub|" + PIZZA + "|" + TOPPING,
                PIZZA, TOPPING, "", CanvasEdge.Kind.INFERRED_SUBCLASS), labels());
        String asserted = CanvasTooltips.forEdge(new CanvasEdge("sub|" + PIZZA + "|" + TOPPING,
                PIZZA, TOPPING, "", CanvasEdge.Kind.SUBCLASS), labels());

        assertTrue(inferred.contains("Inferred by the reasoner"), inferred);
        assertTrue(inferred.contains("not an axiom"), inferred);
        assertTrue(asserted.contains("Asserted in the ontology"), asserted);
        assertFalse(asserted.contains("Inferred"), asserted);
    }

    /** Every edge kind produces a phrase and a construct, so no kind can hover as blank. */
    @Test
    void everyEdgeKindHasWordsForIt() {
        for (CanvasEdge.Kind kind : CanvasEdge.Kind.values()) {
            CanvasEdge edge = new CanvasEdge("e|1", PIZZA, TOPPING, "", kind);
            assertFalse(CanvasTooltips.phraseFor(edge).trim().isEmpty(), kind.name());
            assertFalse(CanvasTooltips.constructFor(kind).trim().isEmpty(), kind.name());
            assertTrue(CanvasTooltips.forEdge(edge, labels()).contains("pizza"), kind.name());
        }
    }

    /**
     * A universal restriction is not read as an existential.
     *
     * <p>{@code OntologyProjection} puts the {@code (only)} qualifier in the label, so the phrase has
     * to come from the label rather than being assembled as "... some ...". Saying "some" over an
     * {@code only} axiom would describe a different ontology.
     */
    @Test
    void anOnlyRestrictionIsNotDescribedAsSome() {
        String tooltip = CanvasTooltips.forEdge(new CanvasEdge("rest|only|a|b", PIZZA, TOPPING,
                "has topping (only)", CanvasEdge.Kind.OBJECT_PROPERTY), labels());

        assertTrue(tooltip.contains("has topping (only)"), tooltip);
        assertFalse(tooltip.contains(" some "), tooltip);
    }

    /** An end that is not on the board is named by its short name, not left blank. */
    @Test
    void anEndWithNoLabelFallsBackToItsShortName() {
        String tooltip = CanvasTooltips.forEdge(new CanvasEdge("sub|1", PIZZA, TOPPING, "",
                CanvasEdge.Kind.SUBCLASS), Collections.<String, String>emptyMap());

        assertTrue(tooltip.contains("Pizza"), tooltip);
        assertTrue(tooltip.contains("PizzaTopping"), tooltip);
    }

    // ===================================================================== nodes

    /** A node gives the label a domain expert reads and the IRI an engineer needs. */
    @Test
    void aNodeGivesBothItsLabelAndItsIri() {
        String tooltip = CanvasTooltips.forNode(
                new CanvasNode(PIZZA, NodeKind.CLASS, "pizza"));

        assertTrue(tooltip.contains("pizza"), tooltip);
        assertTrue(tooltip.contains("Class"), tooltip);
        assertTrue(tooltip.contains(PIZZA), tooltip);
    }

    /** A marker is spelled out, not left to the border weight. */
    @Test
    void anUnsatisfiableClassSaysWhyItLooksDifferent() {
        String tooltip = CanvasTooltips.forNode(
                new CanvasNode(PIZZA, NodeKind.CLASS, "pizza").asUnsatisfiable());

        assertTrue(tooltip.contains("Cannot have instances"), tooltip);
        assertTrue(tooltip.contains("contradictory"), tooltip);
    }

    /** Every node kind has a word for it, so no node can hover as its enum name. */
    @Test
    void everyNodeKindHasAWordForIt() {
        for (NodeKind kind : NodeKind.values()) {
            String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, kind, "thing"));
            assertFalse(tooltip.contains(kind.name()), "raw enum name shown for " + kind);
        }
    }

    // ===================================================================== editorial notes

    /**
     * The words, not the fact that there are words.
     *
     * <p>This line used to read "Has an editorial note". The canvas drew the term with a heavier
     * border, the tooltip confirmed a note existed, and the only way to learn what it said was a
     * dialog in the main menu bar - a marker that raises a question and then refuses to answer it.
     */
    @Test
    void aNotedTermQuotesItsNote() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Collections.singletonList("the parent is provisional")));

        assertTrue(tooltip.contains("the parent is provisional"), tooltip);
        assertFalse(tooltip.contains("Has an editorial note"), tooltip);
    }

    /** No note, no line about notes. */
    @Test
    void aTermWithoutANoteSaysNothingAboutNotes() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza"));

        assertFalse(tooltip.contains("Note"), tooltip);
    }

    /** A note of only whitespace is not a note, and must not produce an empty quoted line. */
    @Test
    void aBlankNoteIsNotQuoted() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Arrays.asList("   ", "")));

        assertFalse(tooltip.contains("Note"), tooltip);
    }

    /**
     * Several notes: the first, and a count for the rest.
     *
     * <p>A term can carry one note per editor, and OBO ontologies do. Quoting all of them turns a
     * tooltip into a document; quoting one and saying nothing about the others hides that a colleague
     * disagreed in writing.
     */
    @Test
    void severalNotesShowTheFirstAndCountTheRest() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Arrays.asList("first note", "second note", "third note")));

        assertTrue(tooltip.contains("first note"), tooltip);
        assertTrue(tooltip.contains("2 more notes"), tooltip);
        assertFalse(tooltip.contains("third note"), tooltip);
    }

    /** Two notes is "1 more note", not "1 more notes". */
    @Test
    void theCountOfFurtherNotesReadsAsEnglish() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Arrays.asList("first", "second")));

        assertTrue(tooltip.contains("1 more note"), tooltip);
        assertFalse(tooltip.contains("1 more notes"), tooltip);
    }

    /** A long note is cut, and the cut is marked. */
    @Test
    void aLongNoteIsTruncatedRatherThanRunningOffTheScreen() {
        StringBuilder essay = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            essay.append("word ");
        }

        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Collections.singletonList(essay.toString())));

        assertTrue(tooltip.contains("\u2026"), "a truncated note should say it was truncated");
        assertTrue(tooltip.length() < 700, "tooltip was " + tooltip.length() + " characters");
    }

    /** Prose is broken into lines, because an editorial note is prose. */
    @Test
    void aNoteOfSeveralSentencesIsWrappedOntoLines() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Collections.singletonList("the parent is provisional pending the review agreed in "
                        + "issue 412, and the alignment needs checking against the upper ontology")));

        // Inside the note itself, not merely the breaks the tooltip already had around it.
        String quoted = tooltip.substring(tooltip.indexOf("Note:</i> ") + "Note:</i> ".length(),
                tooltip.indexOf("<br><font"));
        assertTrue(quoted.contains("<br>"), "the note came out on one line: " + quoted);
    }

    /** No line comes out long enough to make the tooltip wider than the diagram. */
    @Test
    void noWrappedLineIsLongerThanItsLimit() {
        String wrapped = CanvasTooltips.wrap("one two three four five six seven eight nine ten "
                + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen");

        for (String line : wrapped.split("<br>")) {
            assertTrue(line.length() <= 64, "line of " + line.length() + ": " + line);
        }
    }

    /**
     * A note containing HTML is shown, not rendered.
     *
     * <p>Notes come out of the ontology, and an ontology is a file somebody else may have written. A
     * tooltip is rendered HTML, so a note reading {@code <b>} has to arrive as those five characters.
     */
    @Test
    void aNoteThatLooksLikeHtmlIsEscaped() {
        String tooltip = CanvasTooltips.forNode(new CanvasNode(PIZZA, NodeKind.CLASS, "pizza",
                Collections.singletonList("see <b>section 4</b> & appendix")));

        assertTrue(tooltip.contains("&lt;b&gt;"), tooltip);
        assertTrue(tooltip.contains("&amp;"), tooltip);
    }

    // ===================================================================== notes and frames


    /** A note shows its text, and says it is not in the ontology. */
    @Test
    void aNoteShowsItsTextAndThatItIsNotAnAxiom() {
        String tooltip = CanvasTooltips.forNote("check the base with Bob");

        assertTrue(tooltip.contains("check the base with Bob"), tooltip);
        assertTrue(tooltip.contains("not in the ontology"), tooltip);
        assertFalse(tooltip.contains("ontoboard-note-"), tooltip);
    }

    /** An empty note still says what it is rather than hovering blank. */
    @Test
    void anEmptyNoteSaysSo() {
        assertTrue(CanvasTooltips.forNote("  ").contains("(empty)"));
        assertTrue(CanvasTooltips.forFrame(null).contains("(unlabelled)"));
    }

    /** A frame says it asserts nothing, which is the one thing to know about it. */
    @Test
    void aFrameSaysItAssertsNothing() {
        assertTrue(CanvasTooltips.forFrame("Toppings").contains("asserts nothing"));
    }

    // ===================================================================== safety

    /**
     * A label containing markup does not break the tooltip.
     *
     * <p>Swing renders these as HTML, and {@code <} is legal in a label. Unescaped, it truncates the
     * tooltip at that character or renders as a broken tag - so the one node whose label needed
     * explaining would be the one that explained nothing.
     */
    @Test
    void aLabelContainingMarkupIsEscaped() {
        String tooltip = CanvasTooltips.forNode(
                new CanvasNode(PIZZA, NodeKind.CLASS, "weight < 5 & rising"));

        assertTrue(tooltip.contains("&lt;"), tooltip);
        assertTrue(tooltip.contains("&amp;"), tooltip);
        assertFalse(tooltip.contains("< 5"), tooltip);
    }

    /** Null in, null out - the caller falls back to the id rather than showing "null". */
    @Test
    void nullsAreNotDescribed() {
        assertNull(CanvasTooltips.forNode(null));
        assertNull(CanvasTooltips.forEdge(null, labels()));
    }
}
