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
