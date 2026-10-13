package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing a cell so that the reader gets the same cell back.
 *
 * <p>These are written against ROBOT's own reader rather than against a second implementation
 * of the rules, because the rules are not the point - agreeing with the thing that will read
 * the file is. Every awkward cell below goes out through the writer, comes back in through
 * {@link TemplateSheet#read}, and has to be the same string.
 *
 * <p>The list is not invented. It is what a real workbook held: inch marks, a quoted paper
 * title inside a longer sentence, a quote at the start of a cell. The last one is the one that
 * cost two sheets - OpenCSV begins a quoted field at a {@code "} after a delimiter and then
 * reads to the next one, through the tab, through the newline, and on to the end of the file.
 */
class DelimitedTest {

    /** Cells that a naive writer gets wrong, and one that it does not. */
    private static final List<String> AWKWARD = Arrays.asList(
            "plain",
            "",
            "He said \"stop\"",
            "width 36\" nominal",
            "\"leading quote",
            "trailing quote\"",
            "\"\"",
            "\"",
            "Torbjorn Bjorkman, \"CIF2Cell: Generating geometries\", Comput. Phys. Commun. 182",
            "a,b",
            "semi;colon",
            "a\tb",
            "  padded  ",
            "Schöne Grüße — “curly”");

    private static List<List<String>> writeAndReadBack(File folder, char delimiter,
            List<String> row) throws Exception {
        File file = new File(folder, delimiter == ',' ? "cells.csv" : "cells.tsv");
        String text = Delimited.line(Arrays.asList("ID", "LABEL", "A rdfs:comment"), delimiter)
                + Delimited.line(row, delimiter);
        Files.write(file.toPath(), text.getBytes(Charset.forName("UTF-8")));
        return TemplateSheet.read(file);
    }

    @Test
    void everyAwkwardCellComesBackFromRobotsReaderUnchanged(@TempDir File folder) throws Exception {
        for (String cell : AWKWARD) {
            List<String> row = Arrays.asList("ID:1", cell, "after");
            List<List<String>> back = writeAndReadBack(folder, '\t', row);

            assertEquals(2, back.size(), "one header and one data row, writing " + quoted(cell));
            assertEquals(row, back.get(1), "did not survive the round trip: " + quoted(cell));
        }
    }

    @Test
    void theSameHoldsForCommaSeparatedFiles(@TempDir File folder) throws Exception {
        for (String cell : AWKWARD) {
            List<String> row = Arrays.asList("ID:1", cell, "after");

            assertEquals(row, writeAndReadBack(folder, ',', row).get(1),
                    "did not survive the round trip as CSV: " + quoted(cell));
        }
    }

    /**
     * The failure this all comes from, stated as a test: a cell that opens a quote and never
     * closes it used to take the rest of the file with it.
     */
    @Test
    void anUnbalancedQuoteDoesNotSwallowTheRowsBelowIt(@TempDir File folder) throws Exception {
        File file = new File(folder, "terms.tsv");
        String text = Delimited.line(Arrays.asList("ID", "LABEL"), '\t')
                + Delimited.line(Arrays.asList("ID", "LABEL"), '\t')
                + Delimited.line(Arrays.asList("EX:1", "a 36\" bar"), '\t')
                + Delimited.line(Arrays.asList("EX:2", "the row below it"), '\t');
        Files.write(file.toPath(), text.getBytes(Charset.forName("UTF-8")));

        List<List<String>> back = TemplateSheet.read(file);

        assertEquals(4, back.size(), "no row may be eaten: " + back);
        assertEquals("EX:2", back.get(3).get(0), "the identifier below must still be its own");
        assertEquals("the row below it", back.get(3).get(1));
    }

    @Test
    void aCellWithNothingSpecialInItIsWrittenAsItStands() {
        assertEquals("MWO:0000001", Delimited.escape("MWO:0000001", '\t'));
        assertEquals("has part", Delimited.escape("has part", '\t'));
        assertFalse(Delimited.line(Arrays.asList("a", "b"), '\t').contains("\""),
                "an ordinary row must stay readable by anything that splits on tabs");
    }

    @Test
    void aQuoteInsideACellIsDoubledAndTheCellIsWrapped() {
        assertEquals("\"say \"\"hi\"\"\"", Delimited.escape("say \"hi\"", '\t'));
    }

    @Test
    void aCellHoldingTheDelimiterIsWrapped() {
        assertEquals("\"a,b\"", Delimited.escape("a,b", ','));
        assertEquals("a,b", Delimited.escape("a,b", '\t'), "a comma is nothing to a TSV");
    }

    @Test
    void nullIsTreatedAsAnEmptyCellRatherThanThrowing() {
        assertEquals("", Delimited.escape(null, '\t'));
        assertEquals("a\t\tb\n", Delimited.line(Arrays.asList("a", null, "b"), '\t'));
    }

    @Test
    void aRowEndsWithOneNewlineAndNoTrailingDelimiter() {
        assertEquals("a\tb\tc\n", Delimited.line(Arrays.asList("a", "b", "c"), '\t'));
        assertEquals("\n", Delimited.line(Arrays.<String>asList(), '\t'));
    }

    // ---------- flattening, which is a decision about the data ----------

    @Test
    void flatteningTurnsEveryKindOfLineBreakIntoOneSpace() {
        assertEquals("one two", Delimited.flatten("one\r\ntwo"));
        assertEquals("one two", Delimited.flatten("one\ntwo"));
        assertEquals("one two", Delimited.flatten("one\rtwo"));
        assertEquals("one two", Delimited.flatten("one\ttwo"));
        assertEquals("", Delimited.flatten(null));
    }

    /**
     * Flattening is not escaping. It was tempting to call the first one enough - a cell with no
     * tab and no newline cannot break a row - and that is exactly the reasoning that shipped
     * the bug.
     */
    @Test
    void flatteningLeavesTheQuoteForTheEscaperToDealWith() {
        assertTrue(Delimited.flatten("a 36\" bar").contains("\""));
        assertEquals("\"a 36\"\" bar\"", Delimited.escape(Delimited.flatten("a 36\" bar"), '\t'));
    }

    private static String quoted(String cell) {
        return "[" + cell + "]";
    }
}
