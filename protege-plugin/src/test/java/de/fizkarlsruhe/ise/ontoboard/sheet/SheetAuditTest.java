package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Everything wrong, odd or probably-a-mistake in a sheet.
 *
 * <p>The cases are the ones the MatWerk knowledge graph actually contains. Run over its 26
 * sheets with every name indexed, this reports 1,227 findings on 8,471 rows, 289 of them with
 * a replacement that can be applied - on a graph that builds through ROBOT with zero problems.
 * That gap is the reason the class exists.
 *
 * <p>Several tests here are about <b>not</b> reporting. The first version of the near-duplicate
 * check produced 10,059 findings on the {@code temporal} sheet alone, every one of them wrong,
 * and a check that cries wolf is one somebody turns off.
 */
class SheetAuditTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    /** Written as code points so this file stays ASCII and compiles under any encoding. */
    private static final String CURLY_QUOTE = String.valueOf((char) 0x201c);
    private static final String EM_DASH = String.valueOf((char) 0x2014);
    private static final String NO_BREAK_SPACE = String.valueOf((char) 0x00a0);

    private static List<List<String>> sheet(String[]... rows) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] row : rows) {
            table.add(Arrays.asList(row));
        }
        return table;
    }

    /** The real organization shape: identifier, type, label, and a reference to a city. */
    private static List<List<String>> organisations(String[]... dataRows) {
        List<List<String>> table = new ArrayList<List<String>>();
        table.add(Arrays.asList("#", "TYPE", "Institution name", "City"));
        table.add(Arrays.asList("ID", "TYPE", "A rdfs:label",
                "I http://purl.obolibrary.org/obo/RO_0001025"));
        for (String[] row : dataRows) {
            table.add(Arrays.asList(row));
        }
        return table;
    }

    private static List<SheetAudit.Finding> of(SheetAudit.Kind kind,
            List<SheetAudit.Finding> all) {
        List<SheetAudit.Finding> found = new ArrayList<SheetAudit.Finding>();
        for (SheetAudit.Finding finding : all) {
            if (finding.getKind() == kind) {
                found.add(finding);
            }
        }
        return found;
    }

    // ---------- things that are genuinely wrong ----------

    @Test
    void twoRowsWithOneIdentifierAreReportedAgainstTheSecond() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "Fraunhofer", ""},
                new String[] {MW + "1", "owl:NamedIndividual", "Max Planck", ""}),
                LabelIndex.empty());

        List<SheetAudit.Finding> duplicates = of(SheetAudit.Kind.DUPLICATE_ID, found);
        assertEquals(1, duplicates.size(), found.toString());
        assertEquals(4, duplicates.get(0).getRow(), "the second row is the one to look at");
        assertTrue(duplicates.get(0).getMessage().contains("Row 3"),
                duplicates.get(0).getMessage());
    }

    @Test
    void aRowWithDataButNoIdentifierIsReported() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {"", "owl:NamedIndividual", "nameless", ""}), LabelIndex.empty());

        assertEquals(1, of(SheetAudit.Kind.MISSING_ID, found).size(), found.toString());
        assertTrue(of(SheetAudit.Kind.MISSING_ID, found).get(0).getMessage()
                .contains("nothing it says is kept"));
    }

    /** A blank trailing row is how a spreadsheet ends, not a mistake. */
    @Test
    void aWhollyBlankRowIsNotReported() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "Fraunhofer", ""},
                new String[] {"", "", "", ""}), LabelIndex.empty());

        assertTrue(found.isEmpty(), found.toString());
    }

    /**
     * The real case: {@code organization} has 687 cells carrying {@code _name_}, which is a
     * template placeholder that escaped into the published graph.
     */
    @Test
    void textLeftBehindByATemplateIsReported() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "Fraunhofer_name_", ""}),
                LabelIndex.empty());

        assertEquals(1, of(SheetAudit.Kind.PLACEHOLDER, found).size(), found.toString());
        assertTrue(of(SheetAudit.Kind.PLACEHOLDER, found).get(0).getMessage()
                .contains("_name_"));
    }

    @Test
    void aColumnTheTemplateIgnoresButSomebodyFilledInIsReportedOnce() {
        List<SheetAudit.Finding> found = SheetAudit.of("notes", sheet(
                new String[] {"#", "Label", "A note nobody reads"},
                new String[] {"ID", "LABEL", ""},
                new String[] {"ex:1", "steel", "important"},
                new String[] {"ex:2", "iron", "also important"}), LabelIndex.empty());

        List<SheetAudit.Finding> unused = of(SheetAudit.Kind.UNUSED_COLUMN, found);
        assertEquals(1, unused.size(), "one finding for the column, not one per row");
        assertEquals(0, unused.get(0).getRow());
        assertTrue(unused.get(0).getMessage().contains("2 cells"), unused.get(0).getMessage());
    }

    @Test
    void anEmptyColumnTheTemplateIgnoresIsNotReported() {
        List<SheetAudit.Finding> found = SheetAudit.of("notes", sheet(
                new String[] {"#", "Label", "spare"},
                new String[] {"ID", "LABEL", ""},
                new String[] {"ex:1", "steel", ""}), LabelIndex.empty());

        assertTrue(of(SheetAudit.Kind.UNUSED_COLUMN, found).isEmpty(), found.toString());
    }

    // ---------- things pasted from somewhere else ----------

    @Test
    void spaceAroundACellIsReportedWithTheTidiedValue() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "  Fraunhofer  ", ""}),
                LabelIndex.empty());

        List<SheetAudit.Finding> space = of(SheetAudit.Kind.WHITESPACE, found);
        assertEquals(1, space.size(), found.toString());
        assertTrue(space.get(0).isFixable());
        assertEquals("Fraunhofer", space.get(0).getSuggestion());
    }

    @Test
    void aNonBreakingSpaceIsNamedBecauseItLooksLikeAnOrdinaryOne() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual",
                             "Max" + NO_BREAK_SPACE + "Planck", ""}), LabelIndex.empty());

        List<SheetAudit.Finding> space = of(SheetAudit.Kind.WHITESPACE, found);
        assertEquals(1, space.size(), found.toString());
        assertTrue(space.get(0).getMessage().contains("non-breaking"),
                space.get(0).getMessage());
        assertEquals("Max Planck", space.get(0).getSuggestion());
    }

    @Test
    void aCurlyQuoteAndALongDashAreReplacedWithPlainOnes() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual",
                             "Fiji" + EM_DASH + "a " + CURLY_QUOTE + "batteries", ""}),
                LabelIndex.empty());

        List<SheetAudit.Finding> typography = of(SheetAudit.Kind.TYPOGRAPHIC_CHARACTER, found);
        assertEquals(1, typography.size(), found.toString());
        String fixed = typography.get(0).getSuggestion();
        assertEquals("Fiji-a \"batteries", fixed);
        assertFalse(fixed.contains(EM_DASH), "the long dash survived the fix");
        assertFalse(fixed.contains(CURLY_QUOTE), "the curly quote survived the fix");
    }

    @Test
    void anOrdinaryCellIsNotReportedAtAll() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "Fraunhofer-Gesellschaft", ""}),
                LabelIndex.empty());

        assertTrue(found.isEmpty(), found.toString());
    }

    // ---------- references ----------

    @Test
    void aReferenceThatNamesNothingIsReportedWithTheClosestRealName() {
        LabelIndex index = LabelIndex.empty().plus("city", sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "9", "Fraunhofer-Gesellschaft"}));

        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "x",
                             "Fraunhofer-Gesellschafft"}), index);

        List<SheetAudit.Finding> unresolved = of(SheetAudit.Kind.UNRESOLVED_REFERENCE, found);
        assertEquals(1, unresolved.size(), found.toString());
        assertTrue(unresolved.get(0).getMessage().contains("Fraunhofer-Gesellschaft"),
                unresolved.get(0).getMessage());
        assertEquals("Fraunhofer-Gesellschaft", unresolved.get(0).getSuggestion());
    }

    @Test
    void aReferenceThatResolvesIsNotReported() {
        LabelIndex index = LabelIndex.empty().plus("city", sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "9", "Berlin"}));

        List<SheetAudit.Finding> found = SheetAudit.of("organization",
                organisations(new String[] {MW + "1", "owl:NamedIndividual", "x", "Berlin"}),
                index);

        assertTrue(of(SheetAudit.Kind.UNRESOLVED_REFERENCE, found).isEmpty(), found.toString());
    }

    /** An IRI or a CURIE is not a name and must not be guessed at as a misspelt one. */
    @Test
    void anIriInAReferenceColumnIsLeftAlone() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "x", MW + "99"},
                new String[] {MW + "2", "owl:NamedIndividual", "y", "obo:BFO_0000040"}),
                LabelIndex.empty());

        assertTrue(of(SheetAudit.Kind.UNRESOLVED_REFERENCE, found).isEmpty(), found.toString());
    }

    /** Each half of a SPLIT cell is its own reference. */
    @Test
    void everyValueInASplitCellIsChecked() {
        LabelIndex index = LabelIndex.empty().plus("city", sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "9", "Berlin"}));
        List<List<String>> table = sheet(
                new String[] {"#", "Cities"},
                new String[] {"ID", "I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,"},
                new String[] {MW + "1", "Berlin,Hamburg"});

        List<SheetAudit.Finding> found = SheetAudit.of("organization", table, index);

        assertEquals(1, of(SheetAudit.Kind.UNRESOLVED_REFERENCE, found).size(),
                "only Hamburg is unknown: " + found);
    }

    /** Quoting a label works in a class-expression column and not in this one. */
    @Test
    void aQuotedReferenceIsReportedWithTheQuotesRemoved() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "x", "'Berlin'"}),
                LabelIndex.empty());

        List<SheetAudit.Finding> quoted = of(SheetAudit.Kind.QUOTED_REFERENCE, found);
        assertEquals(1, quoted.size(), found.toString());
        assertEquals("Berlin", quoted.get(0).getSuggestion());
    }

    @Test
    void referenceChecksAreSkippedRatherThanGuessedWhenThereIsNoIndex() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "x", "whatever"}), null);

        assertTrue(of(SheetAudit.Kind.UNRESOLVED_REFERENCE, found).isEmpty(), found.toString());
    }

    // ---------- duplicated things, and the noise that nearly sank the check ----------

    @Test
    void twoRowsWithNamesOneEditApartAreReported() {
        List<SheetAudit.Finding> found = SheetAudit.of("city", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "Stuttgart", ""},
                new String[] {MW + "2", "owl:NamedIndividual", "Stuttgart_", ""}),
                LabelIndex.empty());

        List<SheetAudit.Finding> near = of(SheetAudit.Kind.NEAR_DUPLICATE, found);
        assertEquals(1, near.size(), found.toString());
        assertEquals(4, near.get(0).getRow());
        assertFalse(near.get(0).isFixable(),
                "which of two entities to keep is not a tool's decision");
    }

    /**
     * The check as first written produced 10,059 findings on one real sheet and every one was
     * wrong: {@code temporal} names its rows {@code temporal region 1} to
     * {@code temporal region 171}, and each is a single edit from several others.
     */
    @Test
    void namesThatDifferOnlyInTheirNumbersAreASeriesAndNotReported() {
        List<List<String>> table = new ArrayList<List<String>>();
        table.add(Arrays.asList("#", "TYPE", "Label", "City"));
        table.add(Arrays.asList("ID", "TYPE", "A rdfs:label",
                "I http://purl.obolibrary.org/obo/RO_0001025"));
        for (int at = 1; at <= 12; at++) {
            table.add(Arrays.asList(MW + at, "owl:NamedIndividual",
                    "temporal region " + at, ""));
        }

        List<SheetAudit.Finding> found = SheetAudit.of("temporal", table, LabelIndex.empty());

        assertTrue(of(SheetAudit.Kind.NEAR_DUPLICATE, found).isEmpty(),
                "a numbered series was reported as a pile of typos: " + found);
    }

    /** Short names are too close to each other to compare: iron and zinc are two edits apart. */
    @Test
    void shortNamesAreNotComparedBecauseEverythingIsCloseToEverything() {
        List<SheetAudit.Finding> found = SheetAudit.of("materials", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "iron", ""},
                new String[] {MW + "2", "owl:NamedIndividual", "zinc", ""}),
                LabelIndex.empty());

        assertTrue(of(SheetAudit.Kind.NEAR_DUPLICATE, found).isEmpty(), found.toString());
    }

    /** Two spellings on ONE identifier is a second name for a thing, not a duplicate of it. */
    @Test
    void twoSimilarNamesOnOneIdentifierAreNotADuplicate() {
        List<List<String>> table = sheet(
                new String[] {"#", "English", "German"},
                new String[] {"ID", "AL rdfs:label@en", "AL rdfs:label@de"},
                new String[] {MW + "1", "Humboldt University", "Humboldt Universitat"});

        List<SheetAudit.Finding> found = SheetAudit.of("organization", table,
                LabelIndex.empty());

        assertTrue(of(SheetAudit.Kind.NEAR_DUPLICATE, found).isEmpty(), found.toString());
    }

    /** A name that means two things, which ROBOT resolves to one of them silently. */
    @Test
    void aNameThatMeansTwoThingsIsReportedWithBothCandidates() {
        LabelIndex index = LabelIndex.empty()
                .plus("req_2", sheet(
                        new String[] {"#", "Label"},
                        new String[] {"ID", "LABEL"},
                        new String[] {MW + "1", "NIMS"},
                        new String[] {MW + "2", "NIMS"}));
        List<List<String>> table = sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "1", "NIMS"});

        List<SheetAudit.Finding> found = SheetAudit.of("organization", table, index);

        List<SheetAudit.Finding> ambiguous = of(SheetAudit.Kind.AMBIGUOUS_NAME, found);
        assertEquals(1, ambiguous.size(), found.toString());
        assertTrue(ambiguous.get(0).getMessage().contains(MW + "1"));
        assertTrue(ambiguous.get(0).getMessage().contains(MW + "2"));
    }

    @Test
    void anAmbiguousNameIsReportedOncePerSheetNotOncePerRow() {
        LabelIndex index = LabelIndex.empty().plus("req_2", sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "1", "NIMS"},
                new String[] {MW + "2", "NIMS"}));
        List<List<String>> table = sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "1", "NIMS"},
                new String[] {MW + "2", "NIMS"});

        assertEquals(1, of(SheetAudit.Kind.AMBIGUOUS_NAME,
                SheetAudit.of("organization", table, index)).size());
    }

    // ---------- the plumbing ----------

    @Test
    void anEmptyOrHeaderOnlySheetIsNotAnError() {
        assertTrue(SheetAudit.of("x", null, LabelIndex.empty()).isEmpty());
        assertTrue(SheetAudit.of("x", sheet(new String[] {"#"}), LabelIndex.empty()).isEmpty());
    }

    @Test
    void aFindingSaysWhereItIsInOnePhrase() {
        List<SheetAudit.Finding> found = SheetAudit.of("organization", organisations(
                new String[] {MW + "1", "owl:NamedIndividual", "  spaced  ", ""}),
                LabelIndex.empty());

        assertEquals("organization row 3, column \"Institution name\"",
                found.get(0).where());
    }

    @Test
    void theClosestNameIsEmptyWhenNothingIsCloseEnough() {
        assertEquals("", SheetAudit.closestTo("Fraunhofer-Gesellschaft",
                Arrays.asList("Max Planck Institute", "Helmholtz Association")));
        assertEquals("", SheetAudit.closestTo("iron", Arrays.asList("iron ore")),
                "a name too short to compare must not be guessed at");
    }
}
