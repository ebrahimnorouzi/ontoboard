package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;

/**
 * Reading a term file, including the lines it cannot read.
 *
 * <p>The failure worth testing for is silence. ROBOT's own {@code parseTerms} drops a line whose
 * prefix it does not know and returns the rest, so an import of forty terms quietly yields
 * thirty-nine and the missing one turns up months later as a dangling reference. Half of what
 * follows is about the unresolved lines being kept and counted.
 *
 * <p>The other half is the comment rule, which is subtler than it looks and is load-bearing: a
 * {@code #} starts a comment only when whitespace precedes it, so an IRI with a fragment survives
 * and a trailing label does not.
 */
class TermListTest {

    private static final Map<String, String> NO_PREFIXES = Collections.emptyMap();

    // ---------- the comment rule ----------

    @Test
    void aTrailingLabelAfterAHashIsDropped() {
        TermList terms = TermList.parse(
                "http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum", NO_PREFIXES);

        assertEquals(Collections.singletonList(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000109")), terms.getIris());
    }

    /** Real ODK term files have no space between the hash and the label. */
    @Test
    void aLabelWithNoSpaceAfterTheHashIsAlsoDropped() {
        TermList terms = TermList.parse(
                "http://purl.obolibrary.org/obo/IAO_0000221 #is quality measurement of",
                NO_PREFIXES);

        assertEquals(Collections.singletonList(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000221")), terms.getIris());
    }

    /**
     * The case that makes the rule non-obvious. An IRI's own fragment is a hash with no space
     * before it, and treating it as a comment would silently truncate every term in an ontology
     * that uses fragment IRIs - which is most non-OBO ontologies.
     */
    @Test
    void anIriWithAFragmentKeepsItsFragment() {
        TermList terms = TermList.parse("http://example.org/o#Person", NO_PREFIXES);

        assertEquals(Collections.singletonList(IRI.create("http://example.org/o#Person")),
                terms.getIris());
    }

    @Test
    void aWholeCommentLineContributesNothing() {
        TermList terms = TermList.parse(String.join("\n",
                "# terms we take from IAO",
                "##http://purl.obolibrary.org/obo/IAO_0000310 # commented out on purpose",
                "   # indented comment",
                "http://purl.obolibrary.org/obo/IAO_0000109"), NO_PREFIXES);

        assertEquals(1, terms.getIris().size(), terms.getIris().toString());
        assertTrue(terms.getUnresolved().isEmpty(), terms.getUnresolved().toString());
    }

    @Test
    void blankLinesAreNotTerms() {
        TermList terms = TermList.parse("\n\n   \n\t\nIAO:0000109\n\n", NO_PREFIXES);

        assertEquals(1, terms.getIris().size());
        assertTrue(terms.getUnresolved().isEmpty(), terms.getUnresolved().toString());
    }

    // ---------- CURIEs ----------

    @Test
    void anObiCurieExpandsWithoutBeingTold() {
        TermList terms = TermList.parse("OBI:0100026", NO_PREFIXES);

        assertEquals(Collections.singletonList(
                IRI.create("http://purl.obolibrary.org/obo/OBI_0100026")), terms.getIris());
    }

    /** A project's own CURIEs are not in ROBOT's 277, and must come from the open ontology. */
    @Test
    void aProjectsOwnPrefixResolvesWhenItIsSupplied() {
        Map<String, String> prefixes = new HashMap<String, String>();
        prefixes.put("MWO", "http://purls.helmholtz-metadaten.de/mwo/MWO_");

        TermList terms = TermList.parse("MWO:0001234", prefixes);

        assertEquals(Collections.singletonList(
                IRI.create("http://purls.helmholtz-metadaten.de/mwo/MWO_0001234")),
                terms.getIris());
    }

    /** Protege hands prefixes over with the colon attached; ROBOT wants them without. */
    @Test
    void aPrefixNameEndingInAColonIsAccepted() {
        Map<String, String> prefixes = new HashMap<String, String>();
        prefixes.put("MWO:", "http://purls.helmholtz-metadaten.de/mwo/MWO_");

        TermList terms = TermList.parse("MWO:0001234", prefixes);

        assertEquals(Collections.singletonList(
                IRI.create("http://purls.helmholtz-metadaten.de/mwo/MWO_0001234")),
                terms.getIris());
    }

    // ---------- nothing is dropped in silence ----------

    /**
     * The point of the class. An unknown prefix must be reported, not skipped: ROBOT returns the
     * rest and says nothing, and the import comes back a term short.
     */
    @Test
    void anUnknownPrefixIsReportedRatherThanSkipped() {
        TermList terms = TermList.parse(String.join("\n",
                "IAO:0000109",
                "WIBBLE:123",
                "IAO:0000003"), NO_PREFIXES);

        assertEquals(2, terms.getIris().size());
        assertEquals(1, terms.getUnresolved().size());
        assertEquals("WIBBLE:123", terms.getUnresolved().get(0).getText());
    }

    /** Without the line number, "WIBBLE:123 was not recognised" is not actionable in a big file. */
    @Test
    void anUnresolvedLineCarriesItsLineNumber() {
        TermList terms = TermList.parse(String.join("\n",
                "# a comment",
                "IAO:0000109",
                "",
                "WIBBLE:123"), NO_PREFIXES);

        assertEquals(4, terms.getUnresolved().get(0).getLine());
    }

    @Test
    void aRepeatedTermIsCountedRatherThanReturnedTwice() {
        TermList terms = TermList.parse(String.join("\n",
                "IAO:0000109",
                "http://purl.obolibrary.org/obo/IAO_0000109",
                "IAO:0000003"), NO_PREFIXES);

        assertEquals(2, terms.getIris().size(), terms.getIris().toString());
        assertEquals(1, terms.getDuplicates());
    }

    /** File order, because a term file is read alongside the module it produces. */
    @Test
    void theTermsComeBackInTheOrderTheyWereWritten() {
        TermList terms = TermList.parse(String.join("\n",
                "IAO:0000429", "IAO:0000003", "IAO:0000109"), NO_PREFIXES);

        assertEquals("[http://purl.obolibrary.org/obo/IAO_0000429, "
                + "http://purl.obolibrary.org/obo/IAO_0000003, "
                + "http://purl.obolibrary.org/obo/IAO_0000109]", terms.getIris().toString());
    }

    @Test
    void anEmptyFileIsEmptyRatherThanAFailure() {
        assertTrue(TermList.parse("", NO_PREFIXES).isEmpty());
        assertTrue(TermList.parse(null, NO_PREFIXES).isEmpty());
        assertTrue(TermList.parse("# only a comment", NO_PREFIXES).isEmpty());
    }

    @Test
    void aNullPrefixMapJustMeansTheObiOnes() {
        assertEquals(1, TermList.parse("IAO:0000109", null).getIris().size());
    }

    // ---------- the description a user reads ----------

    @Test
    void theDescriptionSaysWhatWasReadAndWhatWasNot() {
        TermList terms = TermList.parse(String.join("\n",
                "IAO:0000109", "IAO:0000109", "WIBBLE:123"), NO_PREFIXES);

        assertEquals("1 term, 1 duplicate line ignored, 1 not recognised", terms.describe());
    }

    @Test
    void aCleanFileIsDescribedWithoutCaveats() {
        assertEquals("2 terms",
                TermList.parse("IAO:0000109\nIAO:0000003", NO_PREFIXES).describe());
    }

    // ---------- a real ODK term file ----------

    /**
     * The fixture is a real term file taken from a working project, comment styles and all. It is
     * here because every rule above was inferred from it, and a change that broke one of them
     * would break reading the file that motivated the rule.
     */
    @Test
    void theRealTermFileReadsWithNothingUnresolved() throws Exception {
        File fixture = new File("src/test/resources/fixture-iao-terms.txt");
        assertTrue(fixture.isFile(), "missing fixture: " + fixture.getAbsolutePath());

        TermList terms = TermList.fromFile(fixture, NO_PREFIXES);

        assertTrue(terms.getUnresolved().isEmpty(),
                "a real ODK term file should read cleanly: " + terms.getUnresolved());
        assertTrue(terms.getIris().size() >= 10,
                "only read " + terms.getIris().size() + " terms from a file that has more");
        for (IRI iri : terms.getIris()) {
            assertFalse(iri.toString().contains(" "),
                    "a comment leaked into a term: " + iri);
            assertTrue(iri.toString().startsWith("http://purl.obolibrary.org/obo/IAO_"),
                    "unexpected term in the IAO list: " + iri);
        }
    }

    /** The other fixture mixes CURIEs and full IRIs, which is what a real exclusion list does. */
    @Test
    void aFileMixingCuriesAndIrisReadsBothWays() throws Exception {
        File fixture = new File("src/test/resources/fixture-unwanted-curies.txt");
        assertTrue(fixture.isFile(), "missing fixture: " + fixture.getAbsolutePath());

        TermList terms = TermList.fromFile(fixture, NO_PREFIXES);

        assertTrue(terms.getUnresolved().isEmpty(), terms.getUnresolved().toString());
        boolean fromCurie = false;
        boolean fromIri = false;
        for (IRI iri : terms.getIris()) {
            fromCurie |= iri.toString().contains("NCBITaxon_");
            fromIri |= iri.toString().contains("IAO_");
        }
        assertTrue(fromCurie, "no CURIE was expanded");
        assertTrue(fromIri, "no full IRI came through");
    }
}
