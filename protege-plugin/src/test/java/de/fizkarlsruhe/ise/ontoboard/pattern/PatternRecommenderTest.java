package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Suggesting patterns for the ontology that is open.
 *
 * <p>Both signals here were chosen by measuring against a real BFO-based project rather than by
 * reasoning about them, and the measurement overturned the obvious design twice - see the two
 * tests about builtins and about ranking by count.
 */
class PatternRecommenderTest {

    private static OWLOntology ontologyWith(String... iris) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        for (String iri : iris) {
            manager.addAxiom(ontology,
                    factory.getOWLDeclarationAxiom(factory.getOWLClass(IRI.create(iri))));
        }
        return ontology;
    }

    private static PatternRecommender.Recommendation find(
            List<PatternRecommender.Recommendation> all, String id) {
        for (PatternRecommender.Recommendation one : all) {
            if (one.getPattern().getId().equals(id)) {
                return one;
            }
        }
        return null;
    }

    // ---------- the signal ----------

    /**
     * owl:Thing is in 42 of the 123 patterns, so matching it ranks nothing.
     *
     * <p>The measurement that killed the first design. Pointed at a real project, 43 patterns
     * "shared an IRI" and every one of those matches was owl:Thing. A recommender built on that
     * would have looked like it worked.
     */
    @Test
    void theBuiltinVocabularyIsNotEvidence() throws Exception {
        OWLOntology onlyBuiltins = ontologyWith(
                "http://www.w3.org/2002/07/owl#Thing",
                "http://www.w3.org/2000/01/rdf-schema#label",
                "http://www.w3.org/2001/XMLSchema#string");

        PatternRecommender.Vocabulary vocabulary =
                PatternRecommender.vocabularyOf(onlyBuiltins);

        assertTrue(PatternRecommender.forOntology(vocabulary, PatternLibrary.all(), 10).isEmpty(),
                "an ontology that declares only builtins resembles nothing");
        assertTrue(PatternRecommender.isBuiltin(IRI.create("http://www.w3.org/2002/07/owl#Thing")));
        assertFalse(PatternRecommender.isBuiltin(
                IRI.create("http://purl.obolibrary.org/obo/BFO_0000015")));
    }

    /** Using a pattern's own IRIs is the strongest evidence there is. */
    @Test
    void anExactIriBeatsAMatchingName() throws Exception {
        String odp = "http://www.ontologydesignpatterns.org/cp/owl/";
        OWLOntology exact = ontologyWith(odp + "agentrole.owl#Agent", odp + "agentrole.owl#Role",
                odp + "objectrole.owl#hasRole");
        OWLOntology byName = ontologyWith("http://example.org/x#Agent",
                "http://example.org/x#Role", "http://example.org/x#hasRole");

        PatternRecommender.Recommendation strong = find(PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(exact), PatternLibrary.all(), 20), "agent-role");
        PatternRecommender.Recommendation weak = find(PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(byName), PatternLibrary.all(), 20), "agent-role");

        assertNotNull(strong, "the pattern whose own IRIs these are");
        assertNotNull(weak);
        assertTrue(strong.getScore() > weak.getScore(),
                strong.getScore() + " should beat " + weak.getScore());
        assertFalse(strong.getSharedIris().isEmpty());
        assertTrue(weak.getSharedIris().isEmpty(), "a different namespace is not the same term");
    }

    /** One shared term is a coincidence, not a recommendation. */
    @Test
    void oneSharedTermIsNotEnough() throws Exception {
        OWLOntology barely = ontologyWith("http://example.org/x#Collection");

        assertTrue(PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(barely), PatternLibrary.all(), 10).isEmpty());
        assertEquals(2, PatternRecommender.ENOUGH_SHARED);
    }

    /**
     * A big pattern does not win on volume.
     *
     * <p>The other measurement that changed the design. Ranking by how many terms matched put a
     * 195-term pattern first on twenty-five generic words; ranking by how much of the pattern is
     * covered puts the small, specific ones first, which is what somebody asking "is there a
     * pattern for this" wants.
     */
    @Test
    void coverageRanksAboveCount() throws Exception {
        OWLOntology roles = ontologyWith("http://example.org/x#Agent", "http://example.org/x#Role",
                "http://example.org/x#hasRole", "http://example.org/x#Object",
                "http://example.org/x#Event", "http://example.org/x#hasParticipant",
                "http://example.org/x#Collection", "http://example.org/x#hasMember",
                "http://example.org/x#Entity", "http://example.org/x#Quality");

        List<PatternRecommender.Recommendation> top = PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(roles), PatternLibrary.all(), 5);

        assertFalse(top.isEmpty());
        for (PatternRecommender.Recommendation one : top) {
            assertTrue(one.getScore() <= 1.0 && one.getScore() > 0, one.toString());
        }
        // eep has 195 terms and matches many generic words; it must not lead.
        assertFalse(top.get(0).getPattern().getId().equals("eep"),
                "a 195-term pattern led on generic words: " + top);
        // Descending, which is what makes it a ranking.
        for (int at = 1; at < top.size(); at++) {
            assertTrue(top.get(at - 1).getScore() >= top.get(at).getScore(), top.toString());
        }
    }

    // ---------- what it says ----------

    /** The reason is the point: a ranked list with no evidence is a magic box. */
    @Test
    void itSaysWhy() throws Exception {
        OWLOntology roles = ontologyWith("http://example.org/x#Agent", "http://example.org/x#Role",
                "http://example.org/x#hasRole");

        PatternRecommender.Recommendation one = find(PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(roles), PatternLibrary.all(), 20), "agent-role");

        assertNotNull(one);
        String why = one.explain();
        assertTrue(why.contains("already in your ontology"), why);
        assertTrue(why.contains("Agent"), why);
        assertTrue(why.contains("Role"), why);
        assertTrue(why.startsWith(one.getSharedCount() + " of its "), why);
    }

    /** Evidence is not listed twice when two terms share a local name. */
    @Test
    void theEvidenceHasNoRepeats() throws Exception {
        OWLOntology shared = ontologyWith("http://example.org/x#Object",
                "http://example.org/x#Role", "http://example.org/x#Agent",
                "http://example.org/x#Event", "http://example.org/x#hasParticipant");

        for (PatternRecommender.Recommendation one : PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(shared), PatternLibrary.all(), 20)) {
            List<String> all = new ArrayList<String>(one.getSharedIris());
            all.addAll(one.getSharedWords());
            assertEquals(all.size(), new java.util.HashSet<String>(all).size(),
                    one.getPattern().getId() + " repeats its evidence: " + all);
        }
    }

    /**
     * A pattern that is another one under a second name is not offered as well.
     *
     * <p>Ten of the library are duplicates and they match identically, so without this Agent
     * Role and Agentrole take two of the ten places and say the same thing twice.
     */
    @Test
    void duplicatesAreNotOfferedTwice() throws Exception {
        OWLOntology roles = ontologyWith("http://example.org/x#Agent", "http://example.org/x#Role",
                "http://example.org/x#hasRole", "http://example.org/x#Object");

        List<PatternRecommender.Recommendation> all = PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(roles), PatternLibrary.all(), 50);

        for (PatternRecommender.Recommendation one : all) {
            assertFalse(one.getPattern().isDuplicate(),
                    one.getPattern().getId() + " is a duplicate of "
                            + one.getPattern().getSameAs());
        }
    }

    // ---------- the edges ----------

    /** No ontology, or nothing in it, is no recommendation rather than a crash. */
    @Test
    void nothingToGoOnIsNoRecommendation() throws Exception {
        assertTrue(PatternRecommender.vocabularyOf(null).isEmpty());
        assertTrue(PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(null), PatternLibrary.all(), 10).isEmpty());
        assertTrue(PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(ontologyWith()), PatternLibrary.all(), 10)
                .isEmpty());
    }

    /** Asking for none gives none, and asking for more than exist gives what exists. */
    @Test
    void theLimitIsRespected() throws Exception {
        PatternRecommender.Vocabulary vocabulary = PatternRecommender.vocabularyOf(
                ontologyWith("http://example.org/x#Agent", "http://example.org/x#Role",
                        "http://example.org/x#Object", "http://example.org/x#Event"));

        assertTrue(PatternRecommender.forOntology(vocabulary, PatternLibrary.all(), 0).isEmpty());
        assertTrue(PatternRecommender.forOntology(vocabulary, PatternLibrary.all(), -1).isEmpty());
        assertTrue(PatternRecommender.forOntology(vocabulary, PatternLibrary.all(), 3).size() <= 3);
    }

    /** Names match across the spellings an ontology might use. */
    @Test
    void namesMatchAcrossSpelling() {
        assertEquals(PatternRecommender.normalise("hasPart"),
                PatternRecommender.normalise("has_part"));
        assertEquals(PatternRecommender.normalise("hasPart"),
                PatternRecommender.normalise("has part"));
        assertEquals("", PatternRecommender.normalise(null));
    }

    /** A local name is what follows the last slash or hash. */
    @Test
    void theLocalNameIsTheLastSegment() {
        assertEquals("Pizza",
                PatternRecommender.localNameOf(IRI.create("http://x.org/onto#Pizza")));
        assertEquals("BFO_0000015",
                PatternRecommender.localNameOf(
                        IRI.create("http://purl.obolibrary.org/obo/BFO_0000015")));
    }
}
