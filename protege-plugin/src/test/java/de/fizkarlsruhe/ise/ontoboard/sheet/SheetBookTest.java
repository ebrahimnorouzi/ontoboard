package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A folder of spreadsheets, open for editing.
 *
 * <p>Row and column numbers here are a spreadsheet's own: row 1 is the author's headings, row 2
 * is ROBOT's, and the first row of data is row 3. Getting that wrong would put every message
 * one or two rows out from what the person is looking at, so most of these tests are about it.
 */
class SheetBookTest {

    @TempDir
    File dir;

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    private static SheetBook withOneSheet() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Label", "City"},
                new String[] {"ID", "TYPE", "A rdfs:label",
                              "I http://purl.obolibrary.org/obo/RO_0001025"},
                new String[] {MW + "1", "owl:NamedIndividual", "Fraunhofer", "Berlin"},
                new String[] {MW + "2", "owl:NamedIndividual", "Max Planck", "Dusseldorf"}),
                '\t');
        return book;
    }

    // ---------- reading ----------

    @Test
    void cellsAreFoundAtSpreadsheetRowAndColumnNumbers() {
        SheetBook book = withOneSheet();

        assertEquals("#", book.cell("organization", 1, 1));
        assertEquals("ID", book.cell("organization", 2, 1));
        assertEquals("Fraunhofer", book.cell("organization", 3, 3),
                "the first row of data is row 3");
        assertEquals("Max Planck", book.cell("organization", 4, 3));
        assertEquals(2, book.get("organization").getDataRows());
        assertEquals(4, book.get("organization").getLastRow());
    }

    @Test
    void askingOutsideTheSheetGivesNothingRatherThanThrowing() {
        SheetBook book = withOneSheet();

        assertEquals("", book.cell("organization", 99, 1));
        assertEquals("", book.cell("organization", 3, 99));
        assertEquals("", book.cell("organization", 0, 1));
        assertEquals("", book.cell("no such sheet", 3, 1));
        assertNull(book.get("no such sheet"));
    }

    @Test
    void theWidthIsTheWidestRow() {
        SheetBook book = SheetBook.empty();
        book.add("s", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"ex:1", "steel", "a third cell nobody declared"}), '\t');

        assertEquals(3, book.width("s"));
    }

    // ---------- changing ----------

    @Test
    void anEditChangesTheCellAndMarksTheSheetUnsaved() {
        SheetBook book = withOneSheet();
        assertFalse(book.isUnsaved());

        assertTrue(book.setCell("organization", 3, 3, "Fraunhofer-Gesellschaft"));

        assertEquals("Fraunhofer-Gesellschaft", book.cell("organization", 3, 3));
        assertTrue(book.isUnsaved());
        assertTrue(book.get("organization").isUnsaved());
    }

    @Test
    void writingTheSameValueChangesNothing() {
        SheetBook book = withOneSheet();

        assertFalse(book.setCell("organization", 3, 3, "Fraunhofer"));
        assertFalse(book.isUnsaved(), "an edit that changed nothing must not dirty the sheet");
        assertFalse(book.canUndo());
    }

    /** A hand-written TSV often stops at the last filled cell; clicking past it is not a bug. */
    @Test
    void aShortRowIsPaddedRatherThanRefused() {
        SheetBook book = SheetBook.empty();
        book.add("s", null, rows(
                new String[] {"#", "Label", "Note"},
                new String[] {"ID", "LABEL", "A rdfs:comment"},
                new String[] {"ex:1", "steel"}), '\t');

        assertTrue(book.setCell("s", 3, 3, "an added note"));
        assertEquals("an added note", book.cell("s", 3, 3));
    }

    @Test
    void aNewRowGoesAtTheEndAndIsReportedAtItsOwnNumber() {
        SheetBook book = withOneSheet();

        int row = book.addRow("organization");

        assertEquals(5, row);
        assertEquals(3, book.get("organization").getDataRows());
        assertEquals("", book.cell("organization", 5, 1));
    }

    @Test
    void anInsertedRowPushesTheRestDown() {
        SheetBook book = withOneSheet();

        assertEquals(3, book.insertRow("organization", 3));

        assertEquals("", book.cell("organization", 3, 3));
        assertEquals("Fraunhofer", book.cell("organization", 4, 3));
        assertEquals("Max Planck", book.cell("organization", 5, 3));
    }

    /** The header rows are not data and must not be pushed down by an insert above them. */
    @Test
    void aRowCannotBeInsertedAboveTheHeaderRows() {
        SheetBook book = withOneSheet();

        assertEquals(3, book.insertRow("organization", 1));

        assertEquals("#", book.cell("organization", 1, 1));
        assertEquals("ID", book.cell("organization", 2, 1));
    }

    @Test
    void aDuplicatedRowLandsDirectlyBelowItsOriginal() {
        SheetBook book = withOneSheet();

        assertEquals(4, book.duplicateRow("organization", 3));

        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
        assertEquals("Fraunhofer", book.cell("organization", 4, 3));
        assertEquals("Max Planck", book.cell("organization", 5, 3));
    }

    @Test
    void theHeaderRowsCannotBeDuplicatedOrDeleted() {
        SheetBook book = withOneSheet();

        assertEquals(0, book.duplicateRow("organization", 1));
        assertEquals(0, book.duplicateRow("organization", 2));
        assertFalse(book.deleteRow("organization", 1));
        assertFalse(book.deleteRow("organization", 2));
        assertEquals(4, book.get("organization").getLastRow());
    }

    @Test
    void aDeletedRowIsGoneAndTheRestMoveUp() {
        SheetBook book = withOneSheet();

        assertTrue(book.deleteRow("organization", 3));

        assertEquals("Max Planck", book.cell("organization", 3, 3));
        assertEquals(1, book.get("organization").getDataRows());
    }

    // ---------- fill down, which is what a spreadsheet user reaches for ----------

    /**
     * The MatWerk {@code temporal} sheet is 171 rows of {@code temporal region <n>}, so a fill
     * that repeated the text rather than counting would be the wrong half of the feature.
     */
    @Test
    void fillingDownANumberedNameCountsUp() {
        SheetBook book = SheetBook.empty();
        book.add("temporal", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"ex:1", "temporal region 1"},
                new String[] {"ex:2", ""},
                new String[] {"ex:3", ""}), '\t');

        assertEquals(2, book.fillDown("temporal", 2, 3, 5));

        assertEquals("temporal region 2", book.cell("temporal", 4, 2));
        assertEquals("temporal region 3", book.cell("temporal", 5, 2));
    }

    @Test
    void fillingDownPlainTextRepeatsIt() {
        SheetBook book = withOneSheet();

        assertEquals(1, book.fillDown("organization", 3, 3, 4));

        assertEquals("Fraunhofer", book.cell("organization", 4, 3),
                "plain text with no number at the end is repeated, not counted");
    }

    @Test
    void fillingDownFromAnEmptyCellDoesNothing() {
        SheetBook book = SheetBook.empty();
        book.add("s", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"ex:1", ""},
                new String[] {"ex:2", ""}), '\t');

        assertEquals(0, book.fillDown("s", 2, 3, 4));
        assertFalse(book.isUnsaved());
    }

    /**
     * A fill never reads from or writes to the header rows.
     *
     * <p>Dragging from the wrong place is easy, and this used to copy the author's own
     * heading - {@code City} - down over the data and report three cells changed.
     */
    @Test
    void fillingDownFromAHeaderRowIsRefused() {
        SheetBook book = withOneSheet();

        assertEquals(0, book.fillDown("organization", 4, 1, 4));
        assertEquals(0, book.fillDown("organization", 4, 2, 4));

        assertEquals("City", book.cell("organization", 1, 4));
        assertEquals("Berlin", book.cell("organization", 3, 4));
        assertFalse(book.isUnsaved());
    }

    // ---------- paste ----------

    @Test
    void aPastedBlockLandsWithItsTopLeftWhereAsked() {
        SheetBook book = withOneSheet();

        int changed = book.paste("organization", 3, 3, rows(
                new String[] {"one", "Hamburg"},
                new String[] {"two", "Bremen"}));

        assertEquals(4, changed);
        assertEquals("one", book.cell("organization", 3, 3));
        assertEquals("Hamburg", book.cell("organization", 3, 4));
        assertEquals("two", book.cell("organization", 4, 3));
        assertEquals("Bremen", book.cell("organization", 4, 4));
    }

    /** Pasting forty rows into a sheet with two is the normal reason to paste. */
    @Test
    void aPasteLongerThanTheSheetAddsRows() {
        SheetBook book = withOneSheet();

        book.paste("organization", 4, 3, rows(
                new String[] {"a"}, new String[] {"b"}, new String[] {"c"}));

        assertEquals(4, book.get("organization").getDataRows());
        assertEquals("c", book.cell("organization", 6, 3));
    }

    // ---------- undo ----------

    @Test
    void undoReversesTheLastEditAndNamesWhatItWould() {
        SheetBook book = withOneSheet();
        book.setCell("organization", 3, 3, "changed");

        assertTrue(book.canUndo());
        assertTrue(book.nextUndo().contains("row 3"), book.nextUndo());
        assertEquals("organization", book.undo());

        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
        assertFalse(book.canUndo());
    }

    @Test
    void undoReversesEachKindOfChange() {
        SheetBook book = withOneSheet();

        book.addRow("organization");
        book.duplicateRow("organization", 3);
        book.deleteRow("organization", 4);
        book.setCell("organization", 3, 3, "x");
        while (book.canUndo()) {
            book.undo();
        }

        assertEquals(2, book.get("organization").getDataRows());
        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
        assertEquals("Max Planck", book.cell("organization", 4, 3));
    }

    @Test
    void undoingNothingIsSafe() {
        assertEquals("", SheetBook.empty().undo());
        assertEquals("", SheetBook.empty().nextUndo());
        assertFalse(SheetBook.empty().canUndo());
    }

    // ---------- the index and the audit come from the whole book ----------

    @Test
    void aReferenceToAnotherSheetResolvesBecauseBothAreOpen() {
        SheetBook book = withOneSheet();
        book.add("city", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "9", "Berlin"},
                new String[] {MW + "10", "Dusseldorf"}), '\t');

        assertNotNull(book.index().resolve("Berlin"));
        assertTrue(book.audit("organization").isEmpty(),
                "Berlin resolves through the other sheet: " + book.audit("organization"));
    }

    @Test
    void theIndexIsRebuiltAfterAnEdit() {
        SheetBook book = withOneSheet();
        assertNull(book.index().resolve("Siemens"));

        book.setCell("organization", 3, 3, "Siemens");

        assertNotNull(book.index().resolve("Siemens"),
                "the index was not rebuilt, so completion would offer stale names");
    }

    @Test
    void completionIsOfferedForAReferenceColumnAndNotForAnAnnotationOne() {
        SheetBook book = withOneSheet();
        book.add("city", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "9", "Berlin"}), '\t');

        assertFalse(book.completionsFor("organization", 4, "Ber", 5).isEmpty(),
                "column 4 names a term, so it must complete");
        assertTrue(book.completionsFor("organization", 3, "Ber", 5).isEmpty(),
                "column 3 holds a label, so completing it would be wrong");
    }

    @Test
    void auditingEverySheetCoversAllOfThem() {
        SheetBook book = withOneSheet();
        book.setCell("organization", 3, 3, "  spaced  ");
        book.add("city", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "9", "Berlin"},
                new String[] {MW + "10", "Dusseldorf"},
                new String[] {MW + "11", "  also spaced  "}), '\t');

        assertEquals(2, book.auditAll().size(), book.auditAll().toString());
    }

    // ---------- writing it back ----------

    @Test
    void openingAFolderReadsEverySheetInIt() throws Exception {
        write(new File(dir, "one.tsv"), "#\tLabel\nID\tLABEL\nex:1\tsteel\n");
        write(new File(dir, "two.csv"), "#,Label\nID,LABEL\nex:2,iron\n");
        write(new File(dir, "notes.txt"), "ignored\n");

        SheetBook book = SheetBook.open(dir);

        assertEquals(2, book.size(), book.getSheets().toString());
        assertNotNull(book.get("one"));
        assertNotNull(book.get("two"));
        assertEquals("steel", book.cell("one", 3, 2));
        assertEquals("iron", book.cell("two", 3, 2));
    }

    /** One unreadable file in a folder of twenty-six must not stop the other twenty-five. */
    @Test
    void aSheetWithNoTemplateRowIsSkippedRatherThanFailingTheFolder() throws Exception {
        write(new File(dir, "good.tsv"), "#\tLabel\nID\tLABEL\nex:1\tsteel\n");
        write(new File(dir, "tiny.tsv"), "#\tLabel\n");

        SheetBook book = SheetBook.open(dir);

        assertEquals(1, book.size());
        assertNotNull(book.get("good"));
    }

    @Test
    void savingWritesTheSheetBackAndClearsTheUnsavedMark() throws Exception {
        File file = new File(dir, "one.tsv");
        write(file, "#\tLabel\nID\tLABEL\nex:1\tsteel\n");
        SheetBook book = SheetBook.open(dir);
        book.setCell("one", 3, 2, "stainless steel");

        book.save("one");

        assertFalse(book.isUnsaved());
        assertEquals("stainless steel", TemplateSheet.read(file).get(2).get(1));
    }

    /**
     * Every cell survives being written and read again.
     *
     * <p>The cases are the ones that broke it. A cell starting with a quote made opencsv - which
     * ROBOT reads both formats with - begin a quoted field and swallow the rest of the file,
     * coming back as "Unterminated quoted field at end of CSV line"; there is such a cell in
     * the MatWerk {@code req_2} sheet. A cell with a non-ASCII character came back
     * double-encoded. Both were found by comparing 99,154 real cells, not by thinking about it.
     */
    @Test
    void everyAwkwardCellSurvivesARoundTrip() throws Exception {
        String[] awkward = {
            "\"quoted at the start\" and more",
            "a comma, inside",
            "Julich with an umlaut: " + ((char) 0x00fc),
            "an em dash " + ((char) 0x2014) + " like this",
            "trailing quote\"",
            "", "   spaced   ",
        };
        for (char delimiter : new char[] {'\t', ','}) {
            SheetBook book = SheetBook.empty();
            List<List<String>> table = rows(
                    new String[] {"#", "Value"},
                    new String[] {"ID", "A rdfs:comment"});
            for (int at = 0; at < awkward.length; at++) {
                table.add(new ArrayList<String>(
                        Arrays.asList("ex:" + at, awkward[at])));
            }
            String name = delimiter == ',' ? "round" : "roundt";
            book.add(name, null, table, delimiter);
            File file = new File(dir, name + (delimiter == ',' ? ".csv" : ".tsv"));
            book.writeTo(name, file);

            List<List<String>> back = TemplateSheet.read(file);
            assertEquals(table.size(), back.size(),
                    "row count changed for delimiter " + (int) delimiter);
            for (int at = 0; at < awkward.length; at++) {
                List<String> row = back.get(at + 2);
                String got = row.size() > 1 ? row.get(1) : "";
                assertEquals(awkward[at], got,
                        "cell " + at + " did not survive delimiter " + (int) delimiter);
            }
        }
    }

    @Test
    void aSheetThatWasNeverOnDiskSaysSoRatherThanFailingQuietly() {
        SheetBook book = withOneSheet();

        try {
            book.save("organization");
            org.junit.jupiter.api.Assertions.fail("saving a sheet with no file should refuse");
        } catch (java.io.IOException refused) {
            assertTrue(refused.getMessage().contains("nowhere to save"), refused.getMessage());
        }
    }

    @Test
    void openingSomethingThatIsNotAFolderGivesAnEmptyBook() {
        assertEquals(0, SheetBook.open(null).size());
        assertEquals(0, SheetBook.open(new File(dir, "nope")).size());
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(Charset.forName("UTF-8")));
    }
}
