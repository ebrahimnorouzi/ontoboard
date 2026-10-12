package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSpec;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Editing the two rows that decide what a sheet builds.
 *
 * <p>The cases are the ones sitting in the real data. Row 1 of the live MatWerk {@code req_2}
 * tab says {@code l 5} where the other twenty-five say {@code #}; two columns of
 * {@code organization} are spelled {@code A rdfs:label@en}, which robot-core accepts and turns
 * into an annotation property whose IRI ends {@code #label@en} - and, undocumented, leaves the
 * whole sheet unreferable by label; {@code collaboration} has a spec with a double space in it;
 * and a generated template in this repo has six columns whose spec is a bare {@code A}. None of
 * them could be corrected from inside Prot&eacute;g&eacute; before 1.118.0.
 */
class SheetHeaderTableTest {

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** The shape the live tabs have. */
    private static SheetBook book() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Label", "City"},
                new String[] {"ID", "TYPE", "A rdfs:label",
                              "I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,"},
                new String[] {"https://nfdi.fiz-karlsruhe.de/matwerk/msekg/1",
                              "obo:OBI_0000245", "Fraunhofer", "Berlin"}), '\t');
        return book;
    }

    private static SheetHeaderTable headerFor(SheetBook book) {
        SheetTable data = new SheetTable(new SheetTableModel(book, "organization"));
        return new SheetHeaderTable(book, "organization", data);
    }

    // ---------- it really edits the sheet ----------

    @Test
    void theTwoRowsAreTheSheetsFirstTwoRows() {
        SheetHeaderTable header = headerFor(book());

        assertEquals("#", header.getValueAt(0, 0));
        assertEquals("ID", header.getValueAt(1, 0));
        assertEquals("Label", header.getValueAt(0, 2));
        assertEquals("A rdfs:label", header.getValueAt(1, 2));
    }

    /** The {@code l 5} typo: row 1, in production, and unreachable until now. */
    @Test
    void editingTheHeadingWritesItToTheSheet() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("l 5", 0, 0);

        assertEquals("l 5", book.cell("organization", 1, 1));
        assertTrue(book.get("organization").isUnsaved());
    }

    /** And the correction is one undo away, like every other edit in this editor. */
    @Test
    void editingAHeaderRowIsUndoable() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("l 5", 0, 0);
        book.undo();

        assertEquals("#", book.cell("organization", 1, 1));
    }

    /**
     * Editing the template row changes what the column does, immediately - nothing caches
     * {@code TemplateColumns}, which is why this works at all.
     */
    @Test
    void editingTheTemplateRowChangesWhatTheColumnMeans() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);
        assertEquals(de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns.Kind.ANNOTATION,
                book.get("organization").getColumns().at(3).getKind());

        header.setValueAt("LABEL", 1, 2);

        assertEquals(de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns.Kind.LABEL,
                book.get("organization").getColumns().at(3).getKind());
    }

    /** Nothing is tidied on the way in: a working sheet must not change because it was opened. */
    @Test
    void whatIsTypedIsWhatIsStored() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        // collaboration column 6 in the live workbook, double space and all. It parses by luck.
        header.setValueAt("I http://purl.obolibrary.org/obo/RO_0000057  SPLIT=|", 1, 3);

        assertEquals("I http://purl.obolibrary.org/obo/RO_0000057  SPLIT=|",
                book.cell("organization", 2, 4));
    }

    // ---------- it says what ROBOT would say ----------

    @Test
    void aGoodHeaderHasNothingToSay() {
        assertTrue(headerFor(book()).problems().isEmpty());
        assertEquals("", headerFor(book()).summary());
    }

    @Test
    void aSpecRobotRefusesIsReportedAsCostingTheWholeSheet() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("DOMAIN %", 1, 2);

        TemplateSpec.Problem problem = header.problemAt(3);
        assertNotNull(problem);
        assertEquals(TemplateSpec.Severity.BREAKS_THE_SHEET, problem.getSeverity());
        assertEquals("DOMAIN", problem.getSuggestion());
        assertTrue(header.summary().contains("stops this sheet building"), header.summary());
    }

    /**
     * The measured one. robot-core accepts it, so this is not about validity - it is about what
     * gets built, which is an annotation property nobody named and a sheet whose rows cannot be
     * referred to by label.
     */
    @Test
    void theLanguageTagOnAnAnnotationColumnIsReportedWithTheSpellingThatWorks() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("A rdfs:label@en", 1, 2);

        TemplateSpec.Problem problem = header.problemAt(3);
        assertNotNull(problem, "the production defect must be reported");
        assertEquals(TemplateSpec.Severity.DOES_NOTHING, problem.getSeverity());
        assertEquals("AL rdfs:label@en", problem.getSuggestion(),
                "the correction is ready to paste over the old one");
    }

    @Test
    void aBareAnnotationKeywordWithNoPropertyIsReported() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("A", 1, 2);

        TemplateSpec.Problem problem = header.problemAt(3);
        assertNotNull(problem, "six columns of a template in this repo are spelled exactly this");
        assertEquals(TemplateSpec.Severity.BREAKS_THE_SHEET, problem.getSeverity());
    }

    @Test
    void blankingAHeadingUnderATemplateStringIsReported() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("", 0, 2);

        TemplateSpec.Problem problem = header.problemAt(3);
        assertNotNull(problem);
        assertTrue(problem.getMessage().contains("COLUMN MISMATCH"), problem.getMessage());
    }

    /** Clearing the template string is the supported way to turn a column off. */
    @Test
    void blankingATemplateStringIsNotAnError() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("", 1, 3);

        assertNull(header.problemAt(4));
        assertEquals("", header.summary());
    }

    @Test
    void theSummaryCountsBothKindsSeparately() {
        SheetBook book = book();
        SheetHeaderTable header = headerFor(book);

        header.setValueAt("DOMAIN %", 1, 2);
        header.setValueAt("II", 1, 3);

        String summary = header.summary();
        assertTrue(summary.contains("stops this sheet building"), summary);
        assertTrue(summary.contains("produce nothing") || summary.contains("produces nothing"),
                summary);
    }

    // ---------- the trimmings ----------

    @Test
    void theColumnsAreLetteredLikeASpreadsheet() {
        assertEquals("A", SheetHeaderTable.letterFor(0));
        assertEquals("Z", SheetHeaderTable.letterFor(25));
        assertEquals("AA", SheetHeaderTable.letterFor(26));
        assertEquals("AZ", SheetHeaderTable.letterFor(51));
        assertEquals("BA", SheetHeaderTable.letterFor(52));
    }

    @Test
    void aTooltipSaysWhichRowItIsAndWhatTheSpecMeans() {
        SheetHeaderTable header = headerFor(book());
        header.setSize(400, 44);

        String heading = header.getToolTipText(new java.awt.event.MouseEvent(header, 0, 0L, 0,
                5, 5, 1, false));
        assertNotNull(heading);
        assertTrue(heading.contains("Row 1"), heading);

        String spec = header.getToolTipText(new java.awt.event.MouseEvent(header, 0, 0L, 0,
                5, 30, 1, false));
        assertNotNull(spec);
        assertTrue(spec.contains("Row 2"), spec);
    }

    @Test
    void aSearchCanLandOnEitherHeaderRow() {
        SheetHeaderTable header = headerFor(book());

        assertTrue(header.goTo(1, 1));
        assertEquals(0, header.getSelectedRow());
        assertTrue(header.goTo(2, 3));
        assertEquals(1, header.getSelectedRow());
        assertFalse(header.goTo(3, 1), "row 3 is data, not header");
        assertFalse(header.goTo(1, 99));
    }

    @Test
    void bothRowsAreEditableAndTheGridSharesTheColumnWidths() {
        SheetBook book = book();
        SheetTable data = new SheetTable(new SheetTableModel(book, "organization"));
        data.autofitColumns();
        SheetHeaderTable header = new SheetHeaderTable(book, "organization", data);

        assertEquals(data.getColumnModel(), header.getColumnModel(),
                "one column model, so the two grids cannot drift apart");
        for (int at = 0; at < header.getColumnCount(); at++) {
            assertTrue(header.isCellEditable(0, at));
            assertTrue(header.isCellEditable(1, at));
            assertEquals(data.getColumnModel().getColumn(at).getPreferredWidth(),
                    header.getColumnModel().getColumn(at).getPreferredWidth());
        }
    }

    @Test
    void aSheetThatIsNotThereHasNoProblems() {
        SheetBook book = book();
        SheetTable data = new SheetTable(new SheetTableModel(book, "organization"));
        SheetHeaderTable header = new SheetHeaderTable(book, "no-such-sheet", data);

        assertTrue(header.problems().isEmpty());
        assertEquals("", header.summary());
    }
}
