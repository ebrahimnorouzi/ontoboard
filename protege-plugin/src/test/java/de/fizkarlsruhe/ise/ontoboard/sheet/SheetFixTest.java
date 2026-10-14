package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Applying what the audit suggested.
 *
 * <p>Run over the real MatWerk sheets this applies 307 corrections in one action and the
 * knowledge graph builds to exactly the same 36,761 axioms and 6,771 individuals afterwards -
 * which is the property that matters. 33 are skipped because an earlier fix in the same batch
 * had already changed the cell, and that case has a test of its own below.
 */
class SheetFixTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static final String CURLY = String.valueOf((char) 0x201c);

    // ---------- a cell with two things wrong with it ----------

    /**
     * One cell, a doubled space and a curly quote, and one click that fixes both.
     *
     * <p>Forty cells of the NFDI-MatWerk workbook are this shape, and until the batch learned to
     * go round again they were the forty it reported as "edited since the check ran" - true, but
     * the thing that edited them was the batch itself. Each finding carries a replacement
     * computed from the cell as the audit found it, so applying the first leaves the second
     * describing text that is no longer there.
     */
    private static SheetBook twoThingsWrongWithOneCell() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Institution name"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", "", "Max" + CURLY + "Planck  Society"}), '\t');
        return book;
    }

    @Test
    void acellWithTwoThingsWrongWithItIsFullyFixedByOneBatch() {
        SheetBook book = twoThingsWrongWithOneCell();
        assertEquals(2, fixableIn(book.auditAll()),
                "the fixture has to produce two fixable findings on one cell");

        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());

        assertEquals("Max\"Planck Society", book.cell("organization", 3, 3),
                "both the quote and the doubled space, in one action");
        assertEquals(0, outcome.getSkipped(), "and nothing reported as somebody else's edit");
        assertEquals(2, outcome.getPasses(), "which took two passes");
        assertEquals(1, outcome.getApplied(), "one cell changed, not two");
        assertEquals(0, fixableIn(book.auditAll()), "nothing fixable left");
    }

    /** Running it again must do nothing at all. That is what idempotent means here. */
    @Test
    void asecondBatchChangesNothing() {
        SheetBook book = twoThingsWrongWithOneCell();
        SheetFix.applyAll(book, book.auditAll());

        SheetFix.Outcome again = SheetFix.applyAll(book, book.auditAll());

        assertEquals(0, again.getApplied());
        assertFalse(again.changedAnything());
    }

    /** And it is still one thing to undo, however many passes it took. */
    @Test
    void severalPassesAreStillOneUndo() {
        SheetBook book = twoThingsWrongWithOneCell();
        SheetFix.applyAll(book, book.auditAll());

        book.undo();

        assertEquals("Max" + CURLY + "Planck  Society", book.cell("organization", 3, 3));
        assertFalse(book.canUndo(), "two passes, one entry");
    }

    @Test
    void anordinaryBatchStillTakesOnePass() {
        SheetBook book = messy();

        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());

        assertEquals(1, outcome.getPasses(),
                "going round again costs an audit of the whole book, so it must not happen "
                        + "when the first pass settled it");
    }

    /**
     * The guard that makes a bulk fix safe is not weakened by going round again.
     *
     * <p>The second pass re-audits only cells THIS batch wrote. A cell somebody else changed
     * while the dialog was open is not one of those, so it is still left exactly alone.
     */
    @Test
    void acellSomebodyElseChangedIsStillLeftAloneAcrossPasses() {
        SheetBook book = twoThingsWrongWithOneCell();
        book.add("other", null, rows(
                new String[] {"#", "Institution name"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "2", "  Fraunhofer  "}), '\t');
        List<SheetAudit.Finding> asSeen = book.auditAll();
        book.setCell("other", 3, 2, "something a person just typed");

        SheetFix.Outcome outcome = SheetFix.applyAll(book, asSeen);

        assertEquals("something a person just typed", book.cell("other", 3, 2),
                "no pass may overwrite an edit this fix did not make");
        assertEquals(1, outcome.getSkipped());
        assertEquals("Max\"Planck Society", book.cell("organization", 3, 3),
                "while the cell it did write is finished properly");
    }

    @Test
    void theOutcomeMentionsThePassesOnlyWhenThereWasMoreThanOne() {
        SheetBook one = messy();
        SheetBook two = twoThingsWrongWithOneCell();

        String ordinary = SheetFix.applyAll(one, one.auditAll()).describe();
        String twice = SheetFix.applyAll(two, two.auditAll()).describe();

        assertFalse(ordinary.contains("passes"), ordinary);
        assertTrue(twice.contains("over 2 passes"), twice);
    }

    private static int fixableIn(List<SheetAudit.Finding> findings) {
        int fixable = 0;
        for (SheetAudit.Finding finding : findings) {
            if (finding.isFixable()) {
                fixable++;
            }
        }
        return fixable;
    }

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** Two cells that need tidying and one row that duplicates another's name. */
    private static SheetBook messy() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "1", "owl:NamedIndividual", "  Fraunhofer  "},
                new String[] {MW + "2", "owl:NamedIndividual", "Max" + CURLY + "Planck"},
                new String[] {MW + "3", "owl:NamedIndividual", "Fraunhofer"},
                new String[] {MW + "4", "owl:NamedIndividual", "Fraunhofer_"}), '\t');
        return book;
    }

    @Test
    void oneFindingIsApplied() {
        SheetBook book = messy();
        List<SheetAudit.Finding> found = book.auditAll();
        SheetAudit.Finding whitespace = first(found, SheetAudit.Kind.WHITESPACE);

        assertTrue(SheetFix.apply(book, whitespace));

        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
        assertTrue(book.isUnsaved());
    }

    @Test
    void aFindingWithNoSuggestionIsNotApplied() {
        SheetBook book = messy();
        SheetAudit.Finding near = first(book.auditAll(), SheetAudit.Kind.NEAR_DUPLICATE);

        assertFalse(near.isFixable());
        assertFalse(SheetFix.apply(book, near));
        assertFalse(book.isUnsaved(), "nothing should have been touched");
    }

    /**
     * The guard that makes a bulk fix safe.
     *
     * <p>A finding records what the cell said when the audit ran. If it has changed since - by
     * the person, or by an earlier fix in the same batch - applying a replacement computed for
     * the old text would overwrite the new. On the real sheets this fires 33 times in one
     * batch, because the whitespace fix and the curly-quote fix can both name the same cell.
     */
    @Test
    void aCellEditedSinceTheCheckRanIsLeftAlone() {
        SheetBook book = messy();
        SheetAudit.Finding whitespace = first(book.auditAll(), SheetAudit.Kind.WHITESPACE);
        book.setCell("organization", 3, 3, "something else entirely");

        assertFalse(SheetFix.apply(book, whitespace));

        assertEquals("something else entirely", book.cell("organization", 3, 3),
                "a stale finding overwrote a cell somebody had just edited");
    }

    @Test
    void applyingEverythingReportsWhatItDidAndWhatItCouldNot() {
        SheetBook book = messy();

        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());

        assertEquals(2, outcome.getApplied(), outcome.describe());
        assertTrue(outcome.getNotFixable() > 0, "the near-duplicate has no replacement");
        assertTrue(outcome.changedAnything());
        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
        assertEquals("Max\"Planck", book.cell("organization", 4, 3));
    }

    @Test
    void theFindingsGoAwayWhenTheyAreFixed() {
        SheetBook book = messy();
        int before = book.auditAll().size();

        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());

        assertEquals(before - outcome.getApplied(), book.auditAll().size(),
                "the audit should report exactly the applied findings fewer");
    }

    @Test
    void oneKindCanBeAppliedWithoutTheOthers() {
        SheetBook book = messy();

        SheetFix.Outcome outcome = SheetFix.applyKind(book, book.auditAll(),
                SheetAudit.Kind.WHITESPACE);

        assertEquals(1, outcome.getApplied());
        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
        assertEquals("Max" + CURLY + "Planck", book.cell("organization", 4, 3),
                "the curly quote was not in the batch and must be untouched");
    }

    /** A button saying "fix 49" that fixes none of them is worse than no button. */
    @Test
    void onlyTheFixableOnesAreOfferedAsABatch() {
        SheetBook book = messy();

        Map<SheetAudit.Kind, Integer> offered = SheetFix.fixableByKind(book.auditAll());

        assertEquals(Integer.valueOf(1), offered.get(SheetAudit.Kind.WHITESPACE));
        assertEquals(Integer.valueOf(1), offered.get(SheetAudit.Kind.TYPOGRAPHIC_CHARACTER));
        assertFalse(offered.containsKey(SheetAudit.Kind.NEAR_DUPLICATE),
                "a kind with nothing applicable must not be offered");
    }

    @Test
    void awholeBatchCanBeUndoneInOneGo() {
        SheetBook book = messy();
        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());
        assertEquals(2, outcome.getApplied());

        assertEquals(2, SheetFix.undoBatch(book, outcome));

        assertEquals("  Fraunhofer  ", book.cell("organization", 3, 3));
        assertEquals("Max" + CURLY + "Planck", book.cell("organization", 4, 3));
    }

    /**
     * In one go literally: one entry in the history, not one per cell.
     *
     * <p>The difference does not show in a two-cell fixture, which is why it survived. On the
     * published NFDI-MatWerk workbook the audit suggests 7,446 replacements and 7,406 apply, so
     * per-cell entries meant the batch both filled the 200-entry history and could not be taken
     * back - and the Undo button, which reverses one entry, took back one cell of 7,406.
     */
    @Test
    void theBatchIsOneEntryInTheHistoryAndNotOnePerCell() {
        SheetBook book = messy();

        SheetFix.Outcome outcome = SheetFix.applyAll(book, book.auditAll());

        assertEquals(2, outcome.getApplied());
        assertEquals("apply the audit's suggestions", book.nextUndo());
        book.undo();
        assertEquals("  Fraunhofer  ", book.cell("organization", 3, 3),
                "one undo has to put every cell of the batch back");
        assertEquals("Max" + CURLY + "Planck", book.cell("organization", 4, 3));
        assertFalse(book.canUndo(), "and leave nothing of the batch behind");
    }

    /** Fixing one cell on its own is still one entry describing that cell. */
    @Test
    void oneFixOnItsOwnIsStillJustThatOneEdit() {
        SheetBook book = messy();
        List<SheetAudit.Finding> whitespace = new java.util.ArrayList<SheetAudit.Finding>();
        for (SheetAudit.Finding finding : book.auditAll()) {
            if (finding.getKind() == SheetAudit.Kind.WHITESPACE) {
                whitespace.add(finding);
            }
        }

        SheetFix.applyAll(book, whitespace);

        assertTrue(book.nextUndo().startsWith("edit of organization"), book.nextUndo());
    }

    @Test
    void theOutcomeSaysSomethingUsefulWhenThereIsNothingToDo() {
        SheetBook clean = SheetBook.empty();
        clean.add("s", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"ex:1", "steel"}), '\t');

        SheetFix.Outcome outcome = SheetFix.applyAll(clean, clean.auditAll());

        assertEquals(0, outcome.getApplied());
        assertFalse(outcome.changedAnything());
        assertEquals("Nothing to apply.", outcome.describe());
    }

    @Test
    void nullsAreSurvivable() {
        assertFalse(SheetFix.apply(null, null));
        assertFalse(SheetFix.apply(SheetBook.empty(), null));
        assertEquals(0, SheetFix.applyAll(null, null).getApplied());
        assertEquals(0, SheetFix.applyAll(SheetBook.empty(), null).getApplied());
        assertTrue(SheetFix.fixableByKind(null).isEmpty());
        assertEquals(0, SheetFix.undoBatch(null, null));
    }

    private static SheetAudit.Finding first(List<SheetAudit.Finding> findings,
            SheetAudit.Kind kind) {
        for (SheetAudit.Finding finding : findings) {
            if (finding.getKind() == kind) {
                return finding;
            }
        }
        throw new AssertionError("no " + kind + " finding in " + findings);
    }
}
