package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The identifier a node shows beside its label.
 *
 * <p>Asked for as "obo:BFO_0000023 (role)". A label alone is the one thing an editor cannot cite:
 * labels get edited, identifiers do not, and a reviewer asking which term a box is wants the
 * identifier.
 */
class CuriesTest {

    private static Map<String, String> prefixes(String... pairs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** The case from the request, against the built-in OBO prefix. */
    @Test
    void anOboIriBecomesAnOboCurie() {
        assertEquals("obo:BFO_0000023", Curies.curieFor(
                prefixes("obo", "http://purl.obolibrary.org/obo/"),
                "http://purl.obolibrary.org/obo/BFO_0000023"));
    }

    /**
     * The longest matching namespace wins.
     *
     * <p>Without this, an ontology declaring both {@code obo:} and a narrower {@code bfo:} would
     * get whichever the map iterated to first, and the same term would be named two different
     * ways in one diagram depending on hash order.
     */
    @Test
    void theMostSpecificPrefixWins() {
        Map<String, String> both = prefixes(
                "obo", "http://purl.obolibrary.org/obo/",
                "bfo", "http://purl.obolibrary.org/obo/BFO_");

        assertEquals("bfo:0000023",
                Curies.curieFor(both, "http://purl.obolibrary.org/obo/BFO_0000023"));
        assertEquals("obo:IAO_0000115",
                Curies.curieFor(both, "http://purl.obolibrary.org/obo/IAO_0000115"));
    }

    /**
     * A deeper path is not a local name.
     *
     * <p>{@code obo:uberon/releases/2024-01-01/uberon.owl} is not a CURIE anybody wants to read,
     * and a release IRI under a known namespace would otherwise produce one.
     */
    @Test
    void aPathUnderTheNamespaceIsNotACurie() {
        Map<String, String> obo = prefixes("obo", "http://purl.obolibrary.org/obo/");

        assertNull(Curies.curieFor(obo,
                "http://purl.obolibrary.org/obo/uberon/releases/2024-01-01/uberon.owl"));
        assertNull(Curies.curieFor(obo, "http://purl.obolibrary.org/obo/mwo.owl#Thing"));
    }

    /** An unknown namespace yields nothing rather than a guess. */
    @Test
    void anUnknownNamespaceHasNoCurie() {
        assertNull(Curies.curieFor(prefixes("obo", "http://purl.obolibrary.org/obo/"),
                "http://example.org/pizza#Margherita"));
        assertNull(Curies.curieFor(null, "http://example.org/x"));
        assertNull(Curies.curieFor(prefixes("obo", "http://purl.obolibrary.org/obo/"), null));
    }

    // ---------- what the node actually shows ----------

    /** Two lines: the identifier, then the label. */
    @Test
    void bothAreShownWhenBothSaySomething() {
        assertEquals("obo:BFO_0000023\nrole",
                Curies.displayLabel("obo:BFO_0000023", "role"));
    }

    /**
     * One line when the second would repeat the first.
     *
     * <p>An ontology without {@code rdfs:label} falls back to the local name, so a node would
     * otherwise read "obo:BFO_0000023" above "BFO_0000023".
     */
    @Test
    void theLabelIsDroppedWhenItRepeatsTheIdentifier() {
        assertEquals("obo:BFO_0000023",
                Curies.displayLabel("obo:BFO_0000023", "BFO_0000023"));
        assertEquals("obo:BFO_0000023", Curies.displayLabel("obo:BFO_0000023", ""));
        assertEquals("obo:BFO_0000023", Curies.displayLabel("obo:BFO_0000023", null));
    }

    /** With no identifier, nothing changes - which is every ontology outside a known namespace. */
    @Test
    void withoutAnIdentifierTheLabelStandsAlone() {
        assertEquals("Margherita", Curies.displayLabel(null, "Margherita"));
        assertEquals("Margherita", Curies.displayLabel("", "Margherita"));
        assertEquals("", Curies.displayLabel(null, null));
    }

    /**
     * The built-in table covers the prefixes an OBO project uses without declaring them.
     *
     * <p>A functional-syntax file may carry no prefix declarations at all, and that is exactly
     * the shape of an ODK edit file.
     */
    @Test
    void theBuiltInTableCoversTheObviousOnes() {
        Map<String, String> defaults = Curies.prefixesOf(null);

        assertTrue(defaults.containsKey("obo"), defaults.keySet().toString());
        assertTrue(defaults.containsKey("rdfs"));
        assertTrue(defaults.containsKey("dcterms"));
        assertEquals("rdfs:label",
                Curies.curieFor(defaults, "http://www.w3.org/2000/01/rdf-schema#label"));
    }
}
