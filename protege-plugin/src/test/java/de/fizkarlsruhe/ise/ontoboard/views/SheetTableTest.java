package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import org.junit.jupiter.api.Test;

/**
 * The grid somebody edits a sheet in.
 *
 * <p>Two kinds of test. The decidable ones - which columns complete, which cells are marked,
 * what the header says, that nothing is laid out outside its container - and one that renders
 * the table to a PNG so a person can look at it. {@code CanvasDesignProofTest} made that
 * argument for the diagram in 1.65.0 and {@code DialogDesignProofTest} for the forms in
 * 1.105.0; this is the third surface and the one somebody spends an afternoon in.
 *
 * <p>A test cannot tell handsome from ugly. It can tell blank from not blank, and it can
 * measure the things that are actually decidable.
 */
class SheetTableTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /**
     * A sheet shaped like the real organization one, with the mistakes it really has.
     *
     * <p>Row 3 is clean. Row 4 has whitespace around a label, which can be corrected. Row 5
     * names a city nothing defines, which cannot be corrected automatically. A grid reviewed
     * on clean data is a grid reviewed on nothing.
     */
    private static SheetBook book() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Institution name", "City", "Website"},
                new String[] {"ID", "TYPE", "A rdfs:label",
                              "I http://purl.obolibrary.org/obo/RO_0001025",
                              "AT http://purl.obolibrary.org/obo/IAO_0000235^^xsd:anyURI"},
                new String[] {MW + "1", "obo:OBI_0000245", "Fraunhofer-Gesellschaft", "Berlin",
                              "https://www.fraunhofer.de"},
                new String[] {MW + "2", "obo:OBI_0000245", "  Max Planck Society  ", "Berlin",
                              "https://www.mpg.de"},
                new String[] {MW + "3", "obo:OBI_0000245", "Institute of Testing",
                              "Nowherecity", ""}), '\t');
        book.add("city", null, rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "10", "obo:ENVO_00000856", "Berlin"}), '\t');
        return book;
    }

    private static SheetTableModel model() {
        SheetTableModel model = new SheetTableModel(book(), "organization");
        model.refreshFindings();
        return model;
    }

    // ---------- the header rows are the header, not the first two rows ----------

    @Test
    void theGridHoldsDataOnlyAndStartsAtTheSheetsThirdRow() {
        SheetTableModel model = model();

        assertEquals(3, model.getRowCount(), "three data rows, not five");
        assertEquals(5, model.getColumnCount());
        assertEquals(3, model.sheetRowOf(0), "the first grid row is the sheet's row 3");
        assertEquals(0, model.tableRowOf(3));
        assertEquals(-1, model.tableRowOf(2), "a header row has no place in the grid");
        assertEquals(MW + "1", model.getValueAt(0, 0));
    }

    @Test
    void theColumnNameIsTheAuthorsHeadingAndTheSpecIsSeparate() {
        SheetTableModel model = model();

        assertEquals("Institution name", model.getColumnName(2));
        assertEquals("A rdfs:label", model.specOf(2));
        assertEquals("City", model.getColumnName(3));
        assertEquals("I http://purl.obolibrary.org/obo/RO_0001025", model.specOf(3));
    }

    @Test
    void editingACellGoesThroughToTheSheetAndCanBeUndone() {
        SheetTableModel model = model();

        model.setValueAt("Fraunhofer", 0, 2);

        assertEquals("Fraunhofer", model.getBook().cell("organization", 3, 3));
        assertEquals("organization", model.undo());
        assertEquals("Fraunhofer-Gesellschaft", model.getValueAt(0, 2));
    }

    @Test
    void theRowOperationsUseGridRowsAndReportGridRows() {
        SheetTableModel model = model();

        assertEquals(3, model.addRow());
        assertEquals(1, model.duplicateRow(0), "a copy of the first row sits below it");
        assertEquals(MW + "1", model.getValueAt(1, 0));
        assertTrue(model.deleteRow(1));
        assertEquals(4, model.getRowCount());
    }

    @Test
    void fillingDownCountsUpThroughTheModel() {
        SheetTableModel model = model();
        model.setValueAt("site 1", 0, 4);

        assertEquals(2, model.fillDown(4, 0, 2));

        assertEquals("site 2", model.getValueAt(1, 4));
        assertEquals("site 3", model.getValueAt(2, 4));
    }

    // ---------- marked cells ----------

    @Test
    void aCellWithSomethingWrongCarriesItsFinding() {
        SheetTableModel model = model();

        SheetAudit.Finding space = model.findingAt(1, 2);
        assertNotNull(space, "the padded label should be marked");
        assertEquals(SheetAudit.Kind.WHITESPACE, space.getKind());
        assertTrue(space.isFixable());

        SheetAudit.Finding missing = model.findingAt(2, 3);
        assertNotNull(missing, "the unknown city should be marked");
        assertEquals(SheetAudit.Kind.UNRESOLVED_REFERENCE, missing.getKind());

        assertNull(model.findingAt(0, 2), "the clean row must not be marked");
        assertEquals(2, model.findingCount());
    }

    @Test
    void theMarksFollowAnEditWhenTheFindingsAreRefreshed() {
        SheetTableModel model = model();
        assertNotNull(model.findingAt(1, 2));

        model.setValueAt("Max Planck Society", 1, 2);
        model.refreshFindings();

        assertNull(model.findingAt(1, 2), "the whitespace was removed, so the mark should go");
        assertEquals(1, model.findingCount());
    }

    // ---------- completion where it belongs, and only there ----------

    @Test
    void aColumnThatNamesATermCompletesAndOneThatHoldsTextDoesNot() {
        SheetTableModel model = model();

        assertTrue(model.namesATerm(3), "the City column names a term");
        assertFalse(model.namesATerm(2), "the label column holds text");
        assertFalse(model.namesATerm(4), "a typed annotation holds text");

        assertFalse(model.completionsFor(3, "Ber", 5).isEmpty());
        assertEquals("Berlin", model.completionsFor(3, "Ber", 5).get(0).getLabel());
        assertTrue(model.completionsFor(2, "Fraun", 5).isEmpty(),
                "completing a label column would offer a reference where a value belongs");
    }

    // ---------- the grid itself ----------

    @Test
    void theTableTintsAMarkedCellAndLeavesACleanOneAlone() {
        SheetTable table = new SheetTable(model());

        Color marked = backgroundAt(table, 1, 2);
        Color clean = backgroundAt(table, 0, 2);

        assertFalse(marked.equals(clean),
                "a cell with a finding should not look like a clean one");
        assertFalse(backgroundAt(table, 2, 3).equals(backgroundAt(table, 1, 2)),
                "a finding that needs a person should not look like one that can be fixed");
    }

    @Test
    void aMarkedCellExplainsItselfInATooltip() {
        SheetTable table = new SheetTable(model());
        table.setSize(table.getPreferredSize());
        table.doLayout();

        java.awt.Rectangle cell = table.getCellRect(1, 2, true);
        String tip = table.getToolTipText(new java.awt.event.MouseEvent(table, 0, 0, 0,
                cell.x + 2, cell.y + 2, 1, false));

        assertNotNull(tip, "a marked cell should say what is wrong with it");
        assertTrue(tip.contains("whitespace") || tip.contains("space"), tip);
        assertTrue(tip.contains("-&gt;") || tip.contains("->"),
                "the correction should be offered: " + tip);
    }

    @Test
    void anIdentifierColumnIsWideEnoughToReadAnIri() {
        SheetTable table = new SheetTable(model());

        assertTrue(table.getColumnModel().getColumn(0).getPreferredWidth() >= 300,
                "the MatWerk identifiers are 46 characters and must be readable");
    }

    @Test
    void everyColumnHasATwoLineHeaderShowingBothTheHeadingAndTheSpec() {
        SheetTable table = new SheetTable(model());

        for (int at = 0; at < table.getColumnCount(); at++) {
            assertNotNull(table.getColumnModel().getColumn(at).getHeaderRenderer(),
                    "column " + at + " has no header renderer");
            Component header = table.getColumnModel().getColumn(at).getHeaderRenderer()
                    .getTableCellRendererComponent(table, null, false, false, 0, at);
            String text = ((javax.swing.JLabel) header).getText();
            assertTrue(text.contains(table.getModel().getColumnName(at)),
                    "the heading is missing from the header: " + text);
            assertTrue(text.contains("<br>"), "the header is not two lines: " + text);
        }
    }

    /**
     * The spec on the header's second line is short enough to actually appear.
     *
     * <p>Added because the rendered image showed the City column's spec clipped to {@code I}
     * and the Website column's to {@code AT} - the two columns whose behaviour that line
     * exists to explain - while every assertion here passed. The picture caught what the
     * tests did not, which is the whole reason the picture is produced.
     */
    @Test
    void theSpecOnTheHeaderIsShortenedEnoughToBeRead() {
        assertEquals("I RO_0001025",
                SheetTable.shortSpec("I http://purl.obolibrary.org/obo/RO_0001025"));
        assertEquals("AT IAO_0000235^^xsd:anyURI", SheetTable.shortSpec(
                "AT http://purl.obolibrary.org/obo/IAO_0000235^^xsd:anyURI"));
        assertEquals("I RO_0001025 SPLIT=,", SheetTable.shortSpec(
                "I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,"));
        assertEquals("A rdfs:label", SheetTable.shortSpec("A rdfs:label"),
                "a CURIE is already short and must be left alone");
        assertEquals("ID", SheetTable.shortSpec("ID"));
        assertEquals("", SheetTable.shortSpec(""));

        SheetTable table = new SheetTable(model());
        for (int at = 0; at < table.getColumnCount(); at++) {
            Component header = table.getColumnModel().getColumn(at).getHeaderRenderer()
                    .getTableCellRendererComponent(table, null, false, false, 0, at);
            javax.swing.JLabel label = (javax.swing.JLabel) header;
            int width = table.getColumnModel().getColumn(at).getPreferredWidth();
            assertTrue(label.getPreferredSize().width <= width + 8,
                    "column " + at + " header wants " + label.getPreferredSize().width
                            + "px in a " + width + "px column, so it will be clipped");
            assertNotNull(label.getToolTipText(),
                    "the full spec should still be reachable on column " + at);
        }
    }

    /** A column the template ignores still says so, rather than looking like a normal one. */
    @Test
    void aColumnTheTemplateIgnoresSaysNotUsed() {
        SheetBook book = SheetBook.empty();
        book.add("s", null, rows(
                new String[] {"#", "Label", "Scratch"},
                new String[] {"ID", "LABEL", ""},
                new String[] {"ex:1", "steel", "a note"}), '\t');
        SheetTableModel model = new SheetTableModel(book, "s");
        SheetTable table = new SheetTable(model);

        Component header = table.getColumnModel().getColumn(2).getHeaderRenderer()
                .getTableCellRendererComponent(table, null, false, false, 0, 2);

        assertTrue(((javax.swing.JLabel) header).getText().contains("not used"),
                ((javax.swing.JLabel) header).getText());
    }

    // ---------- something to look at ----------

    /**
     * Renders the grid to a PNG so its appearance can be reviewed.
     *
     * <p>In a scroll pane and validated, not {@code doLayout}: a {@code JTable}'s header lives
     * in the pane's column-header viewport and only {@code validate} lays that out. The
     * 1.105.0 proof learned this by producing a picture of a table with no header and very
     * nearly reporting it as a defect.
     */
    @Test
    void theGridRendersToAnImageSomebodyCanLookAt() throws Exception {
        SheetTable table = new SheetTable(model());
        JScrollPane pane = new JScrollPane(table);
        JPanel holder = new JPanel(new java.awt.BorderLayout());
        holder.add(pane, java.awt.BorderLayout.CENTER);

        Dimension size = new Dimension(1180, 190);
        holder.setSize(size);
        holder.addNotify();
        holder.validate();

        BufferedImage image = new BufferedImage(size.width, size.height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size.width, size.height);
        holder.paint(graphics);
        graphics.dispose();

        File directory = new File("target/design");
        directory.mkdirs();
        File png = new File(directory, "sheet-editor.png");
        ImageIO.write(image, "PNG", png);

        assertTrue(png.isFile() && png.length() > 2048,
                "no usable image at " + png.getAbsolutePath());
        assertTrue(distinctColours(image) > 8,
                "the grid rendered as " + distinctColours(image) + " colours - it is blank");
        assertTrue(table.getTableHeader().getWidth() > 0
                        && table.getTableHeader().getHeight() > 0,
                "the header did not lay out, so the picture is lying about the product");
        assertTrue(table.getTableHeader().getHeight() >= 24,
                "a two-line header needs more than one line of height, got "
                        + table.getTableHeader().getHeight());
    }

    @Test
    void noColumnIsLaidOutOutsideTheTable() {
        SheetTable table = new SheetTable(model());
        JScrollPane pane = new JScrollPane(table);
        pane.setSize(1180, 190);
        pane.addNotify();
        pane.validate();

        int total = 0;
        for (int at = 0; at < table.getColumnCount(); at++) {
            total += table.getColumnModel().getColumn(at).getWidth();
        }
        assertEquals(total, table.getWidth(),
                "the columns and the table disagree about the width, which scrolls wrongly");
    }

    private static Color backgroundAt(JTable table, int row, int column) {
        Component painted = table.getCellRenderer(row, column)
                .getTableCellRendererComponent(table, table.getValueAt(row, column),
                        false, false, row, column);
        return painted.getBackground();
    }

    private static int distinctColours(BufferedImage image) {
        Set<Integer> seen = new HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                seen.add(image.getRGB(x, y));
                if (seen.size() > 64) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }
}
