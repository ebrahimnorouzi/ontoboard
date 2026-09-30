package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Finding a term on the board.
 *
 * <p>The ordering is what is tested, not whether a substring search can find a substring. On a
 * hundred-term board the feature is only as good as its first answer: somebody types {@code marg},
 * looks at what was centred, and either carries on or goes back to the class hierarchy. A search that
 * returns the right set in the wrong order is a search people use once.
 *
 * <p>The ontology here is a trimmed pizza, because that is the ontology the project's own reports use
 * and because it happens to contain the two cases that break naive matching: a term whose name
 * contains another term's name in full ({@code Margherita} inside
 * {@code VegetarianMargheritaBase}), and labels that separate words where the identifier does not
 * ("American Hot" against {@code AmericanHot}).
 */
class CanvasSearchTest {

    private static final String NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    private static CanvasNode term(String localName, String label) {
        return new CanvasNode(NS + localName, NodeKind.CLASS, label);
    }

    /** A handful of pizza terms, in an order chosen to be unhelpful. */
    private static List<CanvasNode> pizza() {
        return new ArrayList<CanvasNode>(Arrays.asList(
                term("VegetarianMargheritaBase", "Vegetarian Margherita Base"),
                term("AmericanHot", "American Hot"),
                term("Margherita", "Margherita"),
                term("MargheritaTopping", "Margherita Topping"),
                term("Pizza", "pizza"),
                term("NamedPizza", "named pizza")));
    }

    private static List<String> namesOf(List<CanvasNode> nodes) {
        List<String> names = new ArrayList<String>();
        for (CanvasNode node : nodes) {
            names.add(CanvasSearch.localNameOf(node.getId()));
        }
        return names;
    }

    @Test
    void theExactTermComesFirstEvenWhenAnotherNameContainsIt() {
        List<String> found = namesOf(CanvasSearch.matches("margherita", pizza()));

        // The point of the whole class. VegetarianMargheritaBase contains "margherita" too, and is
        // deliberately listed first in the collection - a filter would centre it.
        assertEquals("Margherita", found.get(0));
        assertTrue(found.contains("VegetarianMargheritaBase"), found.toString());
    }

    @Test
    void aPrefixOutranksAMatchInTheMiddle() {
        List<String> found = namesOf(CanvasSearch.matches("marg", pizza()));

        assertEquals(Arrays.asList("Margherita", "MargheritaTopping", "VegetarianMargheritaBase"),
                found);
    }

    @Test
    void searchIsCaseInsensitiveAndIgnoresSurroundingSpace() {
        assertEquals("AmericanHot",
                namesOf(CanvasSearch.matches("  AMERICAN hot ", pizza())).get(0));
    }

    @Test
    void aFullIriFindsItsTerm() {
        // What happens when somebody pastes an identifier out of a ticket or a diff.
        List<String> found = namesOf(CanvasSearch.matches(NS + "AmericanHot", pizza()));

        assertEquals("AmericanHot", found.get(0));
    }

    @Test
    void separatorsInTheQueryDoNotStopAnUnlabelledTermMatching() {
        // The case this exists for: a term added five minutes ago with no rdfs:label yet. Typing the
        // name the way a person says it has to still find it, or the box is only useful on the parts
        // of the ontology that are already finished.
        List<CanvasNode> unlabelled = Collections.singletonList(
                new CanvasNode(NS + "AmericanHot", NodeKind.CLASS, ""));

        assertEquals(1, CanvasSearch.matches("american hot", unlabelled).size());
    }

    @Test
    void aLabelMatchOutranksAnIdentifierMatch() {
        // Two terms, each matching by exactly one of the two routes. The labelled one wins, because
        // the box is meant to speak the ontology's own vocabulary first.
        CanvasNode byLabel = new CanvasNode(NS + "Q0001", NodeKind.CLASS, "Mozzarella");
        CanvasNode byIri = new CanvasNode(NS + "MozzarellaTopping", NodeKind.CLASS, "cheese");

        List<CanvasNode> found = CanvasSearch.matches("mozzarella", Arrays.asList(byIri, byLabel));

        assertSame(byLabel, found.get(0));
    }

    @Test
    void aBlankQueryMatchesNothingRatherThanEverything() {
        assertTrue(CanvasSearch.matches("", pizza()).isEmpty());
        assertTrue(CanvasSearch.matches("   ", pizza()).isEmpty());
        assertTrue(CanvasSearch.matches(null, pizza()).isEmpty());
    }

    @Test
    void theNumberOfMatchesIsCapped() {
        assertEquals(2, CanvasSearch.matches("pizza", pizza(), 2).size());
        assertTrue(CanvasSearch.matches("pizza", pizza(), 0).isEmpty());
    }

    @Test
    void tiesBreakOnTheNameSoTheOrderDoesNotMoveWithTheOntology() {
        // Same rank for both - each matches its label from the start - so only the tie-break
        // decides. Asked twice with the collection reversed: an order that depends on the ontology's
        // axiom order is an order that changes when an unrelated edit happens.
        List<CanvasNode> nodes = new ArrayList<CanvasNode>(Arrays.asList(
                term("Zzz", "spicy zucchini"), term("Aaa", "spicy anchovy")));
        List<String> forwards = namesOf(CanvasSearch.matches("spicy", nodes));
        Collections.reverse(nodes);
        List<String> backwards = namesOf(CanvasSearch.matches("spicy", nodes));

        assertEquals(forwards, backwards);
        assertEquals(Arrays.asList("Aaa", "Zzz"), forwards);
    }

    @Test
    void nullsInTheProjectionAreSkippedRatherThanThrown() {
        List<CanvasNode> withHole = new ArrayList<CanvasNode>(pizza());
        withHole.add(null);

        assertEquals("Margherita", namesOf(CanvasSearch.matches("margherita", withHole)).get(0));
    }

    @Test
    void theShortNameIsWhatAPersonReads() {
        assertEquals("Pizza", CanvasSearch.localNameOf(NS + "Pizza"));
        assertEquals("continuant", CanvasSearch.localNameOf("http://purl.obolibrary.org/obo/continuant"));
        assertEquals("", CanvasSearch.localNameOf(null));
        // A namespace with nothing after its separator falls back to the segment before it. The
        // answer matters less than that it is never the empty string, which would match every query
        // typed and put every unlabelled term at the top of every search.
        assertEquals("pizza.owl#", CanvasSearch.localNameOf(NS));
    }

    @Test
    void anUnlabelledTermIsNamedByItsShortName() {
        assertEquals("AmericanHot",
                CanvasSearch.nameOf(new CanvasNode(NS + "AmericanHot", NodeKind.CLASS, "  ")));
        assertEquals("American Hot", CanvasSearch.nameOf(term("AmericanHot", "American Hot")));
    }
}
