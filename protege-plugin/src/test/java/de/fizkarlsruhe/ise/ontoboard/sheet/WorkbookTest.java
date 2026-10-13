package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading an Excel workbook.
 *
 * <p>The fixture is a real {@code .xlsx}, and every oddity in it is one a naive reader gets
 * wrong: tabs in an order that is not their file order, a number that must not come back as
 * {@code 1225.0}, a date that must not come back as {@code 46307}, rich text in one cell, a
 * formula whose cached value is the answer, a non-ASCII label, a tab character inside a cell,
 * and an empty tab.
 *
 * <p>Checked beyond this file as well: the reader's output was compared cell by cell against
 * openpyxl's on six workbooks on the development machine - 11,389 cells over 19 tabs, five of
 * them written by Excel itself rather than by a library - and agreed exactly. That is in the
 * 1.117.0 receipt, because a reader of somebody else's format should be held to somebody
 * else's reading of it.
 */
class WorkbookTest {

    private static final String MWO = "http://purls.helmholtz-metadaten.de/mwo/MWO_";

    private static File fixture() {
        return new File("src/test/resources/workbook/sample.xlsx");
    }

    private static Workbook.Tab tabNamed(List<Workbook.Tab> tabs, String name) {
        for (Workbook.Tab tab : tabs) {
            if (tab.getName().equals(name)) {
                return tab;
            }
        }
        throw new AssertionError("no tab called " + name + " in " + tabs);
    }

    // ---------- is it one at all ----------

    @Test
    void aRealWorkbookIsRecognised() {
        assertTrue(Workbook.looksLikeAWorkbook(fixture()));
        assertEquals("", Workbook.whyNot(fixture()));
    }

    /**
     * The commonest failure is not a corrupt file. It is a tab-separated file named .xlsx -
     * which is what this plugin itself wrote once, and what Excel then refused with a message
     * about the format not matching the extension. The file is fine; the name is wrong, and
     * saying which is the whole value of this check.
     */
    @Test
    void textNamedXlsxIsDiagnosedRatherThanCalledCorrupt(@TempDir File dir) throws Exception {
        File pretender = new File(dir, "templates.xlsx");
        Files.write(pretender.toPath(),
                "ID\tLabel\nID\tLABEL\nex:1\tsteel\n".getBytes("UTF-8"));

        assertFalse(Workbook.looksLikeAWorkbook(pretender));
        String why = Workbook.whyNot(pretender);
        assertTrue(why.contains("plain text"), why);
        assertTrue(why.contains(".tsv"), "it should say what to do: " + why);
        assertTrue(assertThrows(IOException.class, () -> Workbook.read(pretender))
                .getMessage().contains("plain text"));
    }

    @Test
    void theOldBinaryFormatIsNamedRatherThanGuessedAt(@TempDir File dir) throws Exception {
        File old = new File(dir, "book.xls");
        Files.write(old.toPath(), new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});

        assertTrue(Workbook.whyNot(old).contains(".xls"), Workbook.whyNot(old));
    }

    @Test
    void somethingThatIsNotThereSaysSo() {
        assertTrue(Workbook.whyNot(new File("no-such-file.xlsx")).contains("no file"));
        assertFalse(Workbook.looksLikeAWorkbook(null));
    }

    // ---------- the tabs ----------

    /**
     * Sheet order and file order are different things. This workbook's tabs were reordered
     * after they were created, so a reader matching {@code sheet1.xml} to the first tab hands
     * the wrong data to the right name - and every value in it looks plausible.
     */
    @Test
    void theTabsComeBackInTheOrderExcelShowsThemWithTheirOwnData() throws Exception {
        List<Workbook.Tab> tabs = Workbook.read(fixture());

        List<String> names = new ArrayList<String>();
        for (Workbook.Tab tab : tabs) {
            names.add(tab.getName());
        }
        assertEquals(java.util.Arrays.asList("mwo-instances", "mwo-terms", "Sheet3"), names);
        assertEquals("ID", tabNamed(tabs, "mwo-terms").getRows().get(0).get(0));
        assertEquals("Label", tabNamed(tabs, "mwo-terms").getRows().get(0).get(1));
        assertEquals("TYPE", tabNamed(tabs, "mwo-instances").getRows().get(0).get(1),
                "the instances tab must hold the instances tab's columns");
    }

    @Test
    void theTabNamesCanBeHadWithoutReadingTheCells() throws Exception {
        assertEquals(java.util.Arrays.asList("mwo-instances", "mwo-terms", "Sheet3"),
                Workbook.tabNamesIn(fixture()));
    }

    // ---------- the values ----------

    @Test
    void textComesThroughAsText() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-terms").getRows();

        assertEquals(MWO + "0001225", rows.get(2).get(0));
        assertEquals("sintering process", rows.get(2).get(1));
        assertEquals("'annealing process'", rows.get(2).get(2));
    }

    /** A number stored as a double must not come back as 1225.0 in an identifier column. */
    @Test
    void aWholeNumberHasNoDecimalPointStuckOnIt() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-terms").getRows();

        assertEquals("1225", rows.get(2).get(3));
        assertEquals("1226", rows.get(3).get(3));
    }

    /**
     * Excel has no date type: 2026-10-12 is the number 46307 with a format applied. Reading the
     * number is not a formatting difference, it is the wrong value - and these templates have
     * date columns, because provenance is one of them.
     */
    @Test
    void aDateComesBackAsADateAndNotAsItsSerialNumber() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-terms").getRows();

        assertEquals("2026-10-12", rows.get(2).get(4));
        assertEquals("1960-03-01", rows.get(3).get(4),
                "a date before 1970 is the one the epoch arithmetic gets wrong");
    }

    @Test
    void nonAsciiSurvives() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-terms").getRows();

        assertEquals("Jülich, Köln", rows.get(2).get(5),
                "the charset bug this project already had once, in the other reader");
    }

    /** Rich text is several runs in one cell; keeping only the last truncates every one. */
    @Test
    void richTextIsJoinedRatherThanTruncated() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-instances").getRows();

        assertEquals("bold and plain", rows.get(2).get(2));
    }

    @Test
    void aBooleanReadsAsTrueOrFalseAndNotAsOneOrZero() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-instances").getRows();

        assertEquals("true", rows.get(3).get(2));
    }

    /** A formula cell carries what Excel last calculated, and that is the value. */
    @Test
    void aFormulaIsReadAsItsCachedResult() throws Exception {
        List<List<String>> rows = tabNamed(Workbook.read(fixture()), "mwo-terms").getRows();

        assertEquals("1227", rows.get(4).get(3));
    }

    @Test
    void anEmptyTabIsEmptyRatherThanAbsent() throws Exception {
        assertTrue(tabNamed(Workbook.read(fixture()), "Sheet3").getRows().isEmpty());
    }

    // ---------- importing ----------

    @Test
    void importingWritesOneTabSeparatedFilePerTabThatHasAnythingInIt(@TempDir File folder)
            throws Exception {
        List<File> written = Workbook.importInto(fixture(), folder);

        assertEquals(2, written.size(), "the empty tab is skipped: " + written);
        assertEquals("mwo-instances.tsv", written.get(0).getName());
        assertEquals("mwo-terms.tsv", written.get(1).getName());
    }

    /** What is written has to be openable by the thing that reads these folders. */
    @Test
    void whatIsWrittenOpensAsASheetWithItsColumnsUnderstood(@TempDir File folder)
            throws Exception {
        Workbook.importInto(fixture(), folder);

        SheetBook book = SheetBook.open(folder);

        assertEquals(2, book.size());
        assertEquals("ID", book.cell("mwo-terms", 2, 1));
        assertEquals("sintering process", book.cell("mwo-terms", 3, 2));
        assertEquals(de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns.Kind.ID,
                book.get("mwo-terms").getColumns().at(1).getKind());
    }

    /**
     * A tab or a newline inside a cell adds a column or a row on the way out. Excel allows
     * both; TSV survives neither, and the damage is silent - the sheet simply has one more
     * column than its header row.
     */
    @Test
    void aTabInsideACellDoesNotBecomeAnExtraColumn(@TempDir File folder) throws Exception {
        Workbook.importInto(fixture(), folder);

        String written = new String(Files.readAllBytes(
                new File(folder, "mwo-terms.tsv").toPath()), Charset.forName("UTF-8"));

        for (String line : written.split("\n")) {
            assertEquals(6, line.split("\t", -1).length,
                    "every row must have the same number of columns: " + line);
        }
        assertTrue(written.contains("a note with a tab"), written);
    }

    @Test
    void importingSomethingThatIsNotAWorkbookRefusesWithTheReason(@TempDir File folder)
            throws Exception {
        File pretender = new File(folder, "templates.xlsx");
        Files.write(pretender.toPath(), "ID\tLabel\n".getBytes("UTF-8"));

        assertTrue(assertThrows(IOException.class,
                () -> Workbook.importInto(pretender, folder)).getMessage().contains("text"));
    }

    @Test
    void importingTwiceDoesNotOverwriteTheFirstImport(@TempDir File folder) throws Exception {
        Workbook.importInto(fixture(), folder);
        List<File> again = Workbook.importInto(fixture(), folder);

        assertFalse(again.get(0).getName().equals("mwo-instances.tsv"),
                "the second import must not write over the first: " + again);
    }

    // ---------- what we write has to be readable by what reads it ----------

    /**
     * A workbook built here rather than shipped as a fixture, because the point is one cell:
     * {@code a 36" bar}. Imported raw, that cell opened a quoted field for OpenCSV, which then
     * ate the tab after it, the newline after that, and every column of every row below until
     * it found another quote or the end of the file. In the workbook this came from it moved an
     * identifier onto the wrong row and changed 20,795 cells, and two tabs could not be opened
     * at all - with no error anywhere.
     */
    private static File workbookWithAQuoteInACell(File folder) throws Exception {
        File file = new File(folder, "quoted.xlsx");
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(file));
        put(zip, "xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook"
                + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships\">"
                + "<sheets><sheet name=\"terms\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>");
        put(zip, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\"?><Relationships"
                + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" Type=\"http://"
                + "schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/>"
                + "</Relationships>");
        put(zip, "xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\"?><worksheet"
                + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData>"
                + row(1, "#", "Label", "Note")
                + row(2, "ID", "LABEL", "A rdfs:comment")
                + row(3, "ex:1", "a 36&quot; bar", "inch marks")
                + row(4, "ex:2", "the row below it", "must stay its own")
                + "</sheetData></worksheet>");
        zip.close();
        return file;
    }

    private static String row(int number, String... cells) {
        StringBuilder xml = new StringBuilder("<row r=\"" + number + "\">");
        for (int at = 0; at < cells.length; at++) {
            xml.append("<c r=\"").append((char) ('A' + at)).append(number)
                    .append("\" t=\"inlineStr\"><is><t>").append(cells[at])
                    .append("</t></is></c>");
        }
        return xml.append("</row>").toString();
    }

    private static void put(java.util.zip.ZipOutputStream zip, String name, String content)
            throws Exception {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(Charset.forName("UTF-8")));
        zip.closeEntry();
    }

    @Test
    void aQuoteInACellSurvivesTheTripThroughRobotsOwnReader(@TempDir File folder)
            throws Exception {
        File workbook = workbookWithAQuoteInACell(folder);
        File into = new File(folder, "templates");
        assertTrue(into.mkdirs());

        Workbook.importInto(workbook, into);
        SheetBook book = SheetBook.open(into);

        assertTrue(book.getUnreadable().isEmpty(),
                "the sheet must open at all: " + book.getUnreadable());
        assertEquals(1, book.size());
        assertEquals("a 36\" bar", book.cell("terms", 3, 2), "the cell itself");
        assertEquals("ex:2", book.cell("terms", 4, 1),
                "the row below must keep its own identifier");
        assertEquals("must stay its own", book.cell("terms", 4, 3),
                "and its own last column");
    }

    /**
     * The same file, read by ROBOT rather than by us, because that is who reads it next.
     */
    @Test
    void whatWeWriteForRobotIsWhatRobotReads(@TempDir File folder) throws Exception {
        File into = new File(folder, "templates");
        assertTrue(into.mkdirs());
        Workbook.importInto(workbookWithAQuoteInACell(folder), into);

        List<List<String>> back = de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet.read(
                new File(into, "terms.tsv"));

        assertEquals(4, back.size(), "four rows in, four rows out: " + back);
        assertEquals(Arrays.asList("ex:1", "a 36\" bar", "inch marks"), back.get(2));
        assertEquals(Arrays.asList("ex:2", "the row below it", "must stay its own"), back.get(3));
    }

    // ---------- the arithmetic, directly ----------

    /**
     * The epoch is 1899-12-30, not 1900-01-01, because Excel believes 1900 was a leap year - a
     * deliberate bug kept for compatibility with Lotus 1-2-3. Serial 60 is that day that never
     * existed.
     */
    @Test
    void theDateEpochIsTheOneExcelActuallyUses() {
        assertEquals("2026-10-12", Workbook.dateFrom("46307", false));
        assertEquals("1960-03-01", Workbook.dateFrom("21976", false));
        assertEquals("1900-01-01", Workbook.dateFrom("1", false));
        assertEquals("1900-03-01", Workbook.dateFrom("61", false));
    }

    /** A 1904 workbook, which is what Excel for Mac used to write, counts from 1904-01-01. */
    @Test
    void theOtherDateSystemCountsFromNineteenOhFour() {
        assertEquals("1904-01-01", Workbook.dateFrom("0", true));
        assertEquals("1904-01-02", Workbook.dateFrom("1", true));
    }

    @Test
    void aTimeOfDayIsKept() {
        assertTrue(Workbook.dateFrom("46307.5", false).endsWith("T12:00:00"),
                Workbook.dateFrom("46307.5", false));
    }

    @Test
    void somethingThatIsNotANumberIsNotADate() {
        assertEquals("", Workbook.dateFrom("yesterday", false));
        assertEquals("", Workbook.dateFrom("", false));
    }

    @Test
    void numbersAreTidiedWithoutBeingChanged() {
        assertEquals("1225", Workbook.tidyNumber("1225"));
        assertEquals("1225", Workbook.tidyNumber("1225.0"));
        assertEquals("1.5", Workbook.tidyNumber("1.50"));
        assertEquals("-3", Workbook.tidyNumber("-3.0"));
        assertEquals("0.1", Workbook.tidyNumber("0.1"));
        assertEquals("not a number", Workbook.tidyNumber("not a number"));
        assertEquals("MWO_0001225", Workbook.tidyNumber("MWO_0001225"),
                "an identifier is not a number and must keep its padding");
    }

    @Test
    void columnLettersBecomeColumnNumbers() {
        assertEquals(1, Workbook.columnOf("A1"));
        assertEquals(2, Workbook.columnOf("B3"));
        assertEquals(26, Workbook.columnOf("Z1"));
        assertEquals(27, Workbook.columnOf("AA1"));
        assertEquals(702, Workbook.columnOf("ZZ99"));
        assertEquals(703, Workbook.columnOf("AAA1"));
    }
}
