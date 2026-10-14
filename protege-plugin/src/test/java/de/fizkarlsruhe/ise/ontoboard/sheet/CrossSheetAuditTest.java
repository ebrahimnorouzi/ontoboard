package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One identifier, two sheets, and whether they contradict each other.
 *
 * <p>The whole design of this check is a question about false positives, so most of these tests
 * are about staying quiet. On the published NFDI-MatWerk workbook 1,639 of 6,688 identifiers are
 * the subject of rows in more than one sheet - the intended way to use ROBOT, which merges what
 * the templates assert. A check that reported all 1,639 would be a list of 1,619 non-problems,
 * which is the same as no check at all.
 *
 * <p>Twenty contradict, and they are all the same shape: an identifier typed as one class in
 * MatWerk-PP and another in Others, in two files neither author can see from the other, which
 * ROBOT resolves by whichever order it merged them in.
 *
 * <p>None of the 1,639 disagrees about a label, and that is a measurement rather than a lucky
 * absence: 1,627 of them carry a label on BOTH sides and every one matches. The first version of
 * this check looked for {@code Kind.LABEL}, which no tab in that workbook uses - they all write
 * {@code A rdfs:label} - so it compared no labels at all and reported that they always agreed.
 * {@link de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns#labelColumns} is now the one answer
 * to which column holds a name, and {@code twoSheetsGivingOneIdentifierTwoLabelsIsReportedOnBothRows}
 * is what would have caught it.
 */
class CrossSheetAuditTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";
    private static final String MWO = "http://purls.helmholtz-metadaten.de/mwo/MWO_";
    private static final String NFDI = "https://nfdi.fiz-karlsruhe.de/ontology/NFDI_";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    private static Map<String, List<List<String>>> book(String firstName,
            List<List<String>> first, String secondName, List<List<String>> second) {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put(firstName, first);
        if (second != null) {
            sheets.put(secondName, second);
        }
        return sheets;
    }

    private static List<SheetAudit.Finding> only(List<SheetAudit.Finding> found) {
        List<SheetAudit.Finding> wanted = new ArrayList<SheetAudit.Finding>();
        for (SheetAudit.Finding finding : found) {
            if (finding.getKind() == SheetAudit.Kind.CONFLICTING_DEFINITION) {
                wanted.add(finding);
            }
        }
        return wanted;
    }

    // ---------- staying quiet ----------

    /**
     * The 1,619 case, and the reason this check is shaped the way it is.
     */
    @Test
    void asecondSheetThatContributesOtherColumnsIsNotAContradiction() {
        List<List<String>> people = rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", MWO + "0001022", "Ada Lovelace"});
        List<List<String>> roles = rows(
                new String[] {"#", "TYPE", "Label", "Affiliation"},
                new String[] {"ID", "TYPE", "A rdfs:label", "I " + NFDI + "0000240"},
                new String[] {MW + "1", "", "", "Analytical Engine Group"});

        assertTrue(only(SheetAudit.across(book("people", people, "roles", roles))).isEmpty(),
                "a blank is not a disagreement");
    }

    @Test
    void twoSheetsSayingTheSameThingIsNotAContradiction() {
        List<List<String>> here = rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", MWO + "0001022", "Ada Lovelace"});
        List<List<String>> there = rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", MWO + "0001022", "Ada Lovelace"});

        assertTrue(only(SheetAudit.across(book("here", here, "there", there))).isEmpty());
    }

    /** Within one sheet this is DUPLICATE_ID, which exists and says so. Not this check's job. */
    @Test
    void oneSheetContradictingItselfIsNotACrossSheetFinding() {
        List<List<String>> twice = rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", MWO + "0001022", "Ada Lovelace"},
                new String[] {MW + "1", NFDI + "0000002", "Ada, Countess of Lovelace"});

        assertTrue(only(SheetAudit.across(book("people", twice, null, null))).isEmpty(),
                "one sheet is not two sheets, whatever it says about itself");
        assertFalse(only(SheetAudit.of("people", twice, null)).size() > 0,
                "and the per-sheet audit does not raise this kind either");
    }

    /**
     * {@code TYPE SPLIT=,} holding two classes in the other order is the same cell. Comparing
     * the raw strings would call that a contradiction, and on a real workbook it would do so
     * hundreds of times.
     */
    @Test
    void asplitCellInADifferentOrderIsTheSameCell() {
        List<List<String>> here = rows(
                new String[] {"#", "TYPE"},
                new String[] {"ID", "TYPE SPLIT=,"},
                new String[] {MW + "1", MWO + "0001022," + NFDI + "0000002"});
        List<List<String>> there = rows(
                new String[] {"#", "TYPE"},
                new String[] {"ID", "TYPE SPLIT=,"},
                new String[] {MW + "1", NFDI + "0000002, " + MWO + "0001022"});

        assertTrue(only(SheetAudit.across(book("here", here, "there", there))).isEmpty(),
                "same two types, listed the other way round, with a stray space");
    }

    @Test
    void asheetWithNoIdentifierColumnContributesNothing() {
        List<List<String>> notATemplate = rows(
                new String[] {"Notes", "More"},
                new String[] {"", ""},
                new String[] {"some spreadsheet", "somebody keeps"});
        List<List<String>> people = rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "Ada Lovelace"});

        assertTrue(only(SheetAudit.across(book("notes", notATemplate, "people", people)))
                .isEmpty());
    }

    @Test
    void onesheetOrNoneIsNothingToCompare() {
        assertTrue(SheetAudit.across(null).isEmpty());
        assertTrue(SheetAudit.across(new LinkedHashMap<String, List<List<String>>>()).isEmpty());
        assertTrue(SheetAudit.across(book("people", rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "Ada Lovelace"}), null, null)).isEmpty());
    }

    // ---------- speaking up ----------

    @Test
    void twoSheetsGivingOneIdentifierTwoLabelsIsReportedOnBothRows() {
        List<List<String>> here = rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "Ada Lovelace"});
        List<List<String>> there = rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "Charles Babbage"});

        List<SheetAudit.Finding> found =
                only(SheetAudit.across(book("here", here, "there", there)));

        assertEquals(2, found.size(), "both sides get marked, not just the one scanned first");
        assertEquals("here", found.get(0).getSheet());
        assertEquals(3, found.get(0).getRow());
        assertEquals(2, found.get(0).getColumn(), "the label column, which is the cell at fault");
        assertEquals("there", found.get(1).getSheet());
        for (SheetAudit.Finding finding : found) {
            assertTrue(finding.getMessage().contains("Ada Lovelace"), finding.getMessage());
            assertTrue(finding.getMessage().contains("Charles Babbage"), finding.getMessage());
            assertTrue(finding.getMessage().contains(MW + "1"), finding.getMessage());
            assertFalse(finding.isFixable(),
                    "which of two labels is right is not a tool's decision");
        }
    }

    /** The twenty, in miniature. */
    @Test
    void twoSheetsTypingOneIdentifierDifferentlyIsReported() {
        List<List<String>> projects = rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", MWO + "0001029", "A project"});
        List<List<String>> others = rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", NFDI + "0000002", ""});

        List<SheetAudit.Finding> found =
                only(SheetAudit.across(book("MatWerk-PP", projects, "Others", others)));

        assertEquals(2, found.size());
        assertEquals("TYPE", found.get(0).getHeading());
        assertTrue(found.get(0).getMessage().contains("the types disagree"),
                found.get(0).getMessage());
        assertTrue(found.get(0).getMessage().contains("MatWerk-PP")
                && found.get(0).getMessage().contains("Others"),
                "it has to name both files, since neither can see the other");
        assertTrue(found.get(0).getMessage().contains("merge order"),
                "and say why it matters: " + found.get(0).getMessage());
    }

    @Test
    void athirdSheetIsNamedToo() {
        Map<String, List<List<String>>> three = new LinkedHashMap<String, List<List<String>>>();
        three.put("one", rows(new String[] {"#", "Label"}, new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "first"}));
        three.put("two", rows(new String[] {"#", "Label"}, new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "second"}));
        three.put("three", rows(new String[] {"#", "Label"}, new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "third"}));

        List<SheetAudit.Finding> found = only(SheetAudit.across(three));

        assertEquals(3, found.size());
        assertTrue(found.get(0).getMessage().contains("two row 3")
                && found.get(0).getMessage().contains("three row 3"),
                found.get(0).getMessage());
    }

    // ---------- through the book, which is how the editor sees it ----------

    private static SheetBook twoSheetsThatDisagree() {
        SheetBook book = SheetBook.empty();
        book.add("MatWerk-PP", null, rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", MWO + "0001029", "A project"}), '\t');
        book.add("Others", null, rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", NFDI + "0000002", ""}), '\t');
        return book;
    }

    @Test
    void eachSheetSeesOnlyItsOwnHalfAndTheBookSeesBoth() {
        SheetBook book = twoSheetsThatDisagree();

        assertEquals(1, only(book.audit("MatWerk-PP")).size());
        assertEquals(1, only(book.audit("Others")).size());
        assertEquals(2, only(book.auditAll()).size());
    }

    /**
     * The answer is cached, because it reads every sheet and the grid asks three times per
     * refresh. Caching it without dropping it on an edit would be worse than not caching it.
     */
    @Test
    void fixingOneSideMakesTheFindingGoAway() {
        SheetBook book = twoSheetsThatDisagree();
        assertEquals(2, only(book.auditAll()).size());

        book.setCell("Others", 3, 2, MWO + "0001029");

        assertTrue(only(book.auditAll()).isEmpty(),
                "the cached answer has to be dropped when a cell changes");
    }

    @Test
    void anEditThatIntroducesAContradictionIsSeenImmediately() {
        SheetBook book = SheetBook.empty();
        book.add("one", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", "Ada Lovelace"}), '\t');
        book.add("two", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "1", ""}), '\t');
        assertTrue(only(book.auditAll()).isEmpty());

        book.setCell("two", 3, 2, "Charles Babbage");

        assertEquals(2, only(book.auditAll()).size());
    }

    /** Undo puts the sheets back, so it has to put the finding back too. */
    @Test
    void undoingTheFixBringsTheFindingBack() {
        SheetBook book = twoSheetsThatDisagree();
        book.setCell("Others", 3, 2, MWO + "0001029");
        assertTrue(only(book.auditAll()).isEmpty());

        book.undo();

        assertEquals(2, only(book.auditAll()).size());
    }

    @Test
    void thebulkFixLeavesTheseAloneBecauseTheyCarryNoReplacement() {
        SheetBook book = twoSheetsThatDisagree();

        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());

        assertEquals(0, outcome.getApplied());
        assertEquals(2, outcome.getNotFixable());
        assertEquals(2, only(book.auditAll()).size(), "and they are still there to be read");
    }
}
