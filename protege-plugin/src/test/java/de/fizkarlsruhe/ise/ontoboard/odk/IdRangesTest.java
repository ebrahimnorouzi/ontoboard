package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ID range allocation, against a real ODK file.
 *
 * <p>The fixture is {@code ISE-FIZKarlsruhe/mwo/src/ontology/mwo-idranges.owl}, copied unmodified.
 * Testing against a synthetic file would have proved only that the parser reads what this project
 * writes; what needs proving is that it reads what ODK writes, including the details nobody would
 * invent - a {@code ##} comment line, Manchester syntax under a {@code .owl} extension, blank lines
 * inside a range block, and trailing spaces after {@code Annotations:}.
 *
 * <p>The bug class here is the worst kind this plugin can have: two editors minting the same
 * identifier for different concepts, discovered only at merge time when both terms are already in
 * use. Every assertion about {@code mint} is really about that.
 */
class IdRangesTest {

    private static final String FIXTURE = "src/test/resources/fixture-mwo-idranges.owl";

    private static String realFile() throws Exception {
        return new String(Files.readAllBytes(new File(FIXTURE).toPath()), StandardCharsets.UTF_8);
    }

    private static IdRanges mwo() throws Exception {
        return IdRanges.parse(realFile());
    }

    private static Set<String> taken(String... iris) {
        return new HashSet<String>(Arrays.asList(iris));
    }

    // ---------- reading a real file ----------

    @Test
    void theRealOdkFileIsRead() throws Exception {
        IdRanges ranges = mwo();

        assertEquals("MWO", ranges.getPolicyName());
        assertEquals("http://purls.helmholtz-metadaten.de/mwo/MWO_", ranges.getIdPrefix());
        assertEquals(7, ranges.getIdDigits());
        assertEquals("http://purls.helmholtz-metadaten.de/mwo/mwo/mwo-idranges.owl",
                ranges.getOntologyIri());
    }

    @Test
    void everyAllocatedRangeIsFoundWithItsBounds() throws Exception {
        List<IdRanges.Range> ranges = mwo().getRanges();

        assertEquals(3, ranges.size(), "mwo allocates three ranges: " + ranges);
        assertEquals("HosseinBeygiNasrabadi", ranges.get(0).getAllocatedTo());
        assertEquals(1000, ranges.get(0).getLower());
        assertEquals(9999, ranges.get(0).getUpper());
        assertEquals("joergwa", ranges.get(1).getAllocatedTo());
        assertEquals(10000, ranges.get(1).getLower());
        assertEquals("OtherEditors", ranges.get(2).getAllocatedTo());
        assertEquals(29999, ranges.get(2).getUpper());
    }

    /**
     * {@code Datatype: xsd:integer} sits at the end of the real file and is not a range. Reading it
     * as one would invent an unallocated block that mint could hand out.
     */
    @Test
    void theTrailingXsdIntegerDeclarationIsNotMistakenForARange() throws Exception {
        for (IdRanges.Range range : mwo().getRanges()) {
            assertFalse(range.getAllocatedTo().isEmpty(),
                    "a range with no owner was read: " + range);
        }
    }

    @Test
    void anOwnerIsFoundRegardlessOfCaseOrSurroundingSpace() throws Exception {
        IdRanges ranges = mwo();

        assertNotNull(ranges.rangeFor("joergwa"));
        assertNotNull(ranges.rangeFor("JOERGWA"));
        assertNotNull(ranges.rangeFor("  joergwa  "));
        assertNull(ranges.rangeFor("somebody-else"));
        assertNull(ranges.rangeFor(null));
    }

    @Test
    void theOwnersAreListedInFileOrderForADialogToShow() throws Exception {
        assertEquals(new LinkedHashSet<String>(Arrays.asList(
                "HosseinBeygiNasrabadi", "joergwa", "OtherEditors")), mwo().owners());
    }

    // ---------- minting ----------

    @Test
    void mintingGivesTheFirstFreeNumberInTheOwnersOwnRange() throws Exception {
        String minted = mwo().mint("joergwa", taken());

        assertEquals("http://purls.helmholtz-metadaten.de/mwo/MWO_0010000", minted,
                "joergwa's range starts at 10000 and pads to 7 digits");
    }

    @Test
    void anIdentifierAlreadyInUseIsSkipped() throws Exception {
        IdRanges ranges = mwo();
        Set<String> used = taken(ranges.iriFor(10000), ranges.iriFor(10001));

        assertEquals(ranges.iriFor(10002), ranges.mint("joergwa", used));
    }

    /** The whole point: two editors' identifiers can never collide, because their ranges cannot. */
    @Test
    void twoEditorsMintDisjointIdentifiers() throws Exception {
        IdRanges ranges = mwo();

        String hers = ranges.mint("HosseinBeygiNasrabadi", taken());
        String his = ranges.mint("joergwa", taken());

        assertFalse(hers.equals(his), "two editors minted the same identifier: " + hers);
    }

    @Test
    void severalIdentifiersAtOnceAreAllDistinctAndConsecutive() throws Exception {
        IdRanges ranges = mwo();

        List<String> minted = ranges.mint("joergwa", 3, taken());

        assertEquals(Arrays.asList(ranges.iriFor(10000), ranges.iriFor(10001),
                ranges.iriFor(10002)), minted);
    }

    /**
     * Minting for somebody with no range must fail, and say so. Falling back to some default block
     * would be the collision this file exists to prevent, arriving quietly.
     */
    @Test
    void anEditorWithNoRangeIsRefusedWithAnActionableMessage() throws Exception {
        IdRanges.NoRangeException thrown = assertThrows(IdRanges.NoRangeException.class,
                () -> mwo().mint("a-new-colleague", taken()));

        assertTrue(thrown.getMessage().contains("a-new-colleague"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("idranges"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("joergwa"),
                "the message should say who does have a range: " + thrown.getMessage());
    }

    @Test
    void anExhaustedRangeIsReportedRatherThanSpillingIntoTheNextEditorsBlock() throws Exception {
        IdRanges ranges = IdRanges.create("http://example.org/o", "O",
                "http://example.org/o/O_", 7).withRange("alice", 1, 2);
        Set<String> used = taken(ranges.iriFor(1), ranges.iriFor(2));

        IdRanges.NoRangeException thrown = assertThrows(IdRanges.NoRangeException.class,
                () -> ranges.mint("alice", used));

        assertTrue(thrown.getMessage().contains("1 to 2"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Allocate"), thrown.getMessage());
    }

    @Test
    void paddingMatchesTheDeclaredDigitCount() throws Exception {
        assertEquals("http://purls.helmholtz-metadaten.de/mwo/MWO_0000042", mwo().iriFor(42));
        assertEquals("http://example.org/o/O_0042",
                IdRanges.create("http://example.org/o", "O", "http://example.org/o/O_", 4)
                        .iriFor(42));
    }

    @Test
    void aNumberTooLongForThePaddingIsNotTruncated() throws Exception {
        assertEquals("http://example.org/o/O_12345",
                IdRanges.create("http://example.org/o", "O", "http://example.org/o/O_", 4)
                        .iriFor(12345),
                "silently truncating would mint an identifier that is not the one requested");
    }

    // ---------- allocating ----------

    @Test
    void aNewEditorGetsABlockAfterTheLastOne() throws Exception {
        IdRanges extended = mwo().withRangeFor("newcomer", 10000);

        IdRanges.Range added = extended.rangeFor("newcomer");
        assertEquals(30000, added.getLower(), "the highest existing range ends at 29999");
        assertEquals(39999, added.getUpper());
    }

    /** An overlap hands the same numbers to two people, which is the failure to be prevented. */
    @Test
    void anOverlappingRangeIsRefused() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mwo().withRange("newcomer", 5000, 15000));

        assertTrue(thrown.getMessage().contains("overlaps"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("same identifiers"), thrown.getMessage());
    }

    @Test
    void aRangeAllocatedToNobodyIsRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mwo().withRange("", 40000, 49999));
        assertThrows(IllegalArgumentException.class, () -> mwo().withRange(null, 40000, 49999));
    }

    @Test
    void abackwardsRangeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new IdRanges.Range(1, "alice", 100, 10));
    }

    // ---------- writing ----------

    /**
     * The property that keeps this usable on a real project: what is written can be read back with
     * nothing lost. Anything less means adding one editor corrupts the allocations of the others.
     */
    @Test
    void whatIsWrittenIsReadBackIdentically() throws Exception {
        IdRanges original = mwo().withRangeFor("newcomer", 10000);

        IdRanges reparsed = IdRanges.parse(original.toManchester());

        assertEquals(original.getPolicyName(), reparsed.getPolicyName());
        assertEquals(original.getIdPrefix(), reparsed.getIdPrefix());
        assertEquals(original.getIdDigits(), reparsed.getIdDigits());
        assertEquals(original.getOntologyIri(), reparsed.getOntologyIri());
        assertEquals(original.getRanges().size(), reparsed.getRanges().size());
        for (int i = 0; i < original.getRanges().size(); i++) {
            assertEquals(original.getRanges().get(i).toString(),
                    reparsed.getRanges().get(i).toString());
        }
    }

    @Test
    void theWrittenFileUsesTheRealObiPropertyIrisRatherThanInventedOnes() throws Exception {
        String written = mwo().toManchester();

        assertTrue(written.contains("IAO_0000598"), "idsfor");
        assertTrue(written.contains("IAO_0000597"), "allocatedto");
        assertTrue(written.contains("IAO_0000599"), "idprefix");
        assertTrue(written.contains("IAO_0000596"), "iddigits");
        assertFalse(written.contains("has_id_policy"),
                "has_id_policy was the invented property the old scaffold wrote; ODK's tooling "
                        + "does not recognise it");
    }

    // ---------- deriving the term prefix ----------

    /**
     * Both real cases, reproduced. An OBO Foundry ontology and MWO derive their term prefixes the
     * same way, and neither mints under the ontology document's own IRI - which is what the plugin
     * would have done, since EntityFactory appends # to the ontology IRI.
     */
    @Test
    void theTermPrefixDropsTheDocumentSegmentTheWayOboOntologiesDo() {
        assertEquals("http://purl.obolibrary.org/obo/MWO_",
                IdRanges.prefixFor("http://purl.obolibrary.org/obo/mwo.owl", "mwo"));
        assertEquals("http://purls.helmholtz-metadaten.de/mwo/MWO_",
                IdRanges.prefixFor("http://purls.helmholtz-metadaten.de/mwo/mwo", "mwo"));
    }

    @Test
    void theDerivedPrefixIsTheOneTheRealMwoFileDeclares() throws Exception {
        assertEquals(mwo().getIdPrefix(),
                IdRanges.prefixFor("http://purls.helmholtz-metadaten.de/mwo/mwo", "mwo"),
                "if these disagree, a project generated by the plugin mints under a different "
                        + "prefix from the one its own idranges file declares");
    }

    @Test
    void trailingDelimitersAndOwlSuffixesAreHandled() {
        assertEquals("https://w3id.org/MMO_", IdRanges.prefixFor("https://w3id.org/mmo/", "mmo"));
        assertEquals("https://w3id.org/MMO_", IdRanges.prefixFor("https://w3id.org/mmo#", "mmo"));
        assertEquals("https://w3id.org/MMO_", IdRanges.prefixFor("https://w3id.org/mmo.owl", "mmo"));
        assertEquals("https://w3id.org/MMO_", IdRanges.prefixFor("https://w3id.org/mmo.OWL", "mmo"));
    }

    /** A bare host has no segment to drop; truncating it would produce nonsense. */
    @Test
    void aBareHostIsNotTruncated() {
        assertEquals("https://example.org/O_", IdRanges.prefixFor("https://example.org", "o"));
    }

    @Test
    void aMissingBaseOrIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> IdRanges.prefixFor(null, "o"));
        assertThrows(IllegalArgumentException.class, () -> IdRanges.prefixFor("", "o"));
        assertThrows(IllegalArgumentException.class,
                () -> IdRanges.prefixFor("https://example.org/o", ""));
    }

    // ---------- refusing nonsense ----------

    @Test
    void anEmptyFileIsRefusedRatherThanMintingFromNowhere() {
        assertThrows(IdRanges.NoRangeException.class, () -> IdRanges.parse(""));
        assertThrows(IdRanges.NoRangeException.class, () -> IdRanges.parse(null));
        assertThrows(IdRanges.NoRangeException.class, () -> IdRanges.parse("   \n  \n"));
    }

    @Test
    void aFileWithNoPrefixIsRefusedBecauseThereIsNoIriToMintUnder() {
        assertThrows(IdRanges.NoRangeException.class, () -> IdRanges.parse(
                "Ontology: <http://example.org/o>\n\nAnnotations:\n    idsfor: \"O\"\n"));
    }

    /** Padding is guessed only when absent, and 7 is what ODK's template and every OBO file use. */
    @Test
    void anAbsentDigitCountFallsBackToTheOboConventionOfSeven() {
        IdRanges ranges = IdRanges.parse("Ontology: <http://example.org/o>\n\nAnnotations:\n"
                + "    idsfor: \"O\",\n    idprefix: \"http://example.org/o/O_\"\n");

        assertEquals(7, ranges.getIdDigits());
    }

    @Test
    void aRangeWithoutBoundsIsSkippedRatherThanFailingTheWholeFile() {
        IdRanges ranges = IdRanges.parse("Ontology: <http://example.org/o>\n\nAnnotations:\n"
                + "    idprefix: \"http://example.org/o/O_\",\n    iddigits: 7\n\n"
                + "Datatype: idrange:1\n    Annotations:\n        allocatedto: \"broken\"\n\n"
                + "Datatype: idrange:2\n    Annotations:\n        allocatedto: \"alice\"\n"
                + "    EquivalentTo:\n        xsd:integer[>= 1 , <= 99]\n");

        assertEquals(1, ranges.getRanges().size());
        assertEquals("alice", ranges.getRanges().get(0).getAllocatedTo());
    }

    @Test
    void anImpossibleDigitCountIsRefusedAtCreation() {
        assertThrows(IllegalArgumentException.class,
                () -> IdRanges.create("http://example.org/o", "O", "p", 0));
        assertThrows(IllegalArgumentException.class,
                () -> IdRanges.create("http://example.org/o", "O", "p", 19));
    }

    @Test
    void mintingZeroIdentifiersIsAProgrammingErrorNotAnEmptyList() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mwo().mint("joergwa", 0, taken()));
    }
}
