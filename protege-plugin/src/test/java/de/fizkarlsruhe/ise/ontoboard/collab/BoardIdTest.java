package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tying a collaboration board to the ontology it is for.
 *
 * <p>The board id was free text and nothing connected it to anything, so two people could type the
 * same id while editing unrelated ontologies and watch each other's axioms land in the wrong file
 * with no error anywhere. The reverse was as easy: a typo made a second empty board rather than a
 * failure, and two collaborators sat in separate sessions wondering why the other had gone quiet.
 *
 * <p>Deriving the id closes both. The properties that make that true are: same ontology, same id,
 * computed independently and without anybody agreeing anything; different ontology, different id.
 */
class BoardIdTest {

    private static final String MWO = "http://purls.helmholtz-metadaten.de/mwo/mwo.owl";

    // ---------- the two properties the whole idea rests on ----------

    /** Two people editing the same ontology must reach the same board without being told its name. */
    @Test
    void theSameOntologyAlwaysGivesTheSameBoard() {
        assertEquals(BoardId.forOntology(MWO), BoardId.forOntology(MWO));
        assertEquals(BoardId.forOntology(MWO), BoardId.forOntology("  " + MWO + "  "));
    }

    /** Two people editing different ontologies must never meet. */
    @Test
    void differentOntologiesGiveDifferentBoards() {
        assertNotEquals(BoardId.forOntology(MWO),
                BoardId.forOntology("http://purl.obolibrary.org/obo/iao.owl"));
    }

    /**
     * The case the readable stem alone would get wrong: an OBO ontology and somebody's private
     * copy of it share a file name and are not the same thing.
     */
    @Test
    void twoOntologiesWhoseNamesEndTheSameWayAreNotTheSameBoard() {
        assertNotEquals(BoardId.forOntology("http://purl.obolibrary.org/obo/mwo.owl"),
                BoardId.forOntology("http://example.org/private/mwo.owl"));
    }

    /** A board id nobody can place is a board id nobody trusts. */
    @Test
    void theBoardIdSaysWhichProjectItIsFor() {
        assertTrue(BoardId.forOntology(MWO).startsWith("mwo-"), BoardId.forOntology(MWO));
        assertTrue(BoardId.forOntology("http://purl.obolibrary.org/obo/chebi.owl")
                .startsWith("chebi-"));
    }

    // ---------- the shapes real ontology IRIs come in ----------

    @Test
    void aTrailingSlashIsNotTheName() {
        assertTrue(BoardId.forOntology("http://example.org/mwo/").startsWith("mwo-"),
                BoardId.forOntology("http://example.org/mwo/"));
    }

    @Test
    void aFragmentIsNotPartOfTheName() {
        assertTrue(BoardId.forOntology("http://example.org/mwo#").startsWith("mwo-"),
                BoardId.forOntology("http://example.org/mwo#"));
    }

    @Test
    void everyOntologyFileExtensionIsStrippedFromTheStem() {
        for (String extension : new String[] {".owl", ".obo", ".ttl", ".rdf", ".ofn", ".omn"}) {
            String id = BoardId.forOntology("http://example.org/thing" + extension);
            assertTrue(id.startsWith("thing-"), extension + " gave " + id);
        }
    }

    /**
     * A version IRI ends in the same file name as the ontology it releases. It is still a
     * different IRI, so it is still a different board - which is right: an editing session is
     * about the edit file, not about a frozen release.
     */
    @Test
    void aVersionIriIsItsOwnBoard() {
        assertNotEquals(BoardId.forOntology(MWO),
                BoardId.forOntology(
                        "http://purls.helmholtz-metadaten.de/mwo/releases/2026-08-30/mwo.owl"));
    }

    @Test
    void anIriWithNothingUsableStillGivesAnId() {
        String id = BoardId.forOntology("http://example.org/");
        assertFalse(id.isEmpty());
        assertTrue(id.startsWith("example-org-") || id.startsWith("ontology-"), id);
    }

    @Test
    void aVeryLongNameIsShortenedButStaysDistinct() {
        String longName = "http://example.org/"
                + "a-really-very-long-ontology-name-that-nobody-would-read-out-loud.owl";
        String id = BoardId.forOntology(longName);

        assertTrue(id.length() < 40, "too long to read out: " + id);
        assertNotEquals(id, BoardId.forOntology(longName + "-two"));
        assertFalse(id.contains("--"), id);
    }

    @Test
    void noIriMeansNoDerivedBoard() {
        assertEquals("", BoardId.forOntology(null));
        assertEquals("", BoardId.forOntology(""));
        assertEquals("", BoardId.forOntology("   "));
    }

    /** Ids get typed, read aloud and put in chat messages. */
    @Test
    void theIdIsSafeToTypeAndToPutInAUrl() {
        for (String iri : new String[] {MWO, "http://example.org/Ünïcödé Näme.owl",
                "http://example.org/with spaces.owl", "http://example.org/UPPER.owl"}) {
            String id = BoardId.forOntology(iri);
            assertTrue(id.matches("[a-z0-9-]+"), iri + " gave " + id);
        }
    }

    /** Different IRIs must not collide across a realistic set. */
    @Test
    void aProjectsWorthOfOntologiesAllGetDistinctBoards() {
        String[] iris = {
            "http://purl.obolibrary.org/obo/mwo.owl",
            "http://purl.obolibrary.org/obo/iao.owl",
            "http://purl.obolibrary.org/obo/obi.owl",
            "http://purls.helmholtz-metadaten.de/mwo/mwo.owl",
            "http://purls.helmholtz-metadaten.de/mwo/imports/iao_import.owl",
            "http://example.org/mwo.owl",
            "https://w3id.org/mwo",
        };
        Set<String> ids = new HashSet<String>();
        for (String iri : iris) {
            assertTrue(ids.add(BoardId.forOntology(iri)),
                    "two of these share a board: " + iri + " -> " + BoardId.forOntology(iri));
        }
    }

    // ---------- noticing that the board is for something else ----------

    @Test
    void aDerivedBoardMatchesItsOntology() {
        assertTrue(BoardId.matches(BoardId.forOntology(MWO), MWO));
        assertNull(BoardId.mismatchWarning(BoardId.forOntology(MWO), MWO));
    }

    @Test
    void aBoardForAnotherOntologyDoesNotMatch() {
        assertFalse(BoardId.matches(BoardId.forOntology("http://example.org/other.owl"), MWO));
    }

    /**
     * The warning has to describe both halves of the danger, because which one applies depends on
     * what the other editors did, and the user cannot see that from here.
     */
    @Test
    void theWarningExplainsBothWaysItCanGoWrong() {
        String warning = BoardId.mismatchWarning("some-other-board", MWO);

        assertTrue(warning.contains("some-other-board"), warning);
        assertTrue(warning.contains(BoardId.forOntology(MWO)),
                "it should say what the derived board is: " + warning);
        assertTrue(warning.contains("not see each other"), warning);
        assertTrue(warning.contains("land in yours"), warning);
    }

    /** A deliberate override is legitimate, so this reports rather than refuses. */
    @Test
    void anOverriddenBoardIsWarnedAboutAndNotRefused() {
        assertFalse(BoardId.mismatchWarning("shared-across-two-files", MWO)
                .toLowerCase().contains("cannot"));
        assertTrue(BoardId.mismatchWarning("shared-across-two-files", MWO)
                .contains("fine if you meant it"));
    }

    /** An ontology with no IRI cannot be checked, and saying nothing would hide that. */
    @Test
    void anOntologyWithNoIriIsReportedAsUncheckable() {
        String warning = BoardId.mismatchWarning("some-board", "");

        assertTrue(warning.contains("no IRI of its own"), warning);
        assertTrue(warning.contains("ontology header"), warning);
    }

    @Test
    void noBoardIdIsNothingToWarnAbout() {
        assertNull(BoardId.mismatchWarning("", MWO));
        assertNull(BoardId.mismatchWarning(null, MWO));
    }
}
