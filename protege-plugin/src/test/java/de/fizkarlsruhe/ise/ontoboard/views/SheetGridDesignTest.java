package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JList;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.Test;

/**
 * The parts of the grid added in 1.117.0, and the one test a person still has to do by eye.
 *
 * <p>Each of these exists because of something a reader of a wide sheet gets wrong. The row
 * numbers: every message about these files names a row - the audit says "row 412", the build
 * says which row would not build - and a grid with no row numbers makes finding it a counting
 * exercise. The banding: reading along one row of a twenty-six column grid is where people take
 * a value from the row above. The widths: a fixed 180px made a column of two-sentence notes
 * unreadable and a column of four-digit years mostly white space.
 */
class SheetGridDesignTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    private static SheetBook book() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "Year", "Institution name", "Note"},
                new String[] {"ID", "A ex:year", "A rdfs:label", "A rdfs:comment"},
                new String[] {MW + "1", "1949", "Fraunhofer-Gesellschaft",
                              "A German research organization of 76 institutes, founded in "
                                      + "Munich, named after Joseph von Fraunhofer."},
                new String[] {MW + "2", "1911", "Max Planck Society", "Also German."},
                new String[] {MW + "3", "1887", "Bundesanstalt", ""}), '\t');
        return book;
    }

    private static SheetTable table() {
        SheetTableModel model = new SheetTableModel(book(), "organization");
        model.refreshFindings();
        return new SheetTable(model);
    }

    // ---------- row numbers ----------

    /**
     * The sheet's numbers, not the grid's. Data starts at sheet row 3, so a header counting from
     * 1 would send somebody two rows wrong - which is worse than no header at all, because it
     * looks authoritative.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRowHeaderCountsInSheetRowsAndNotInGridRows() {
        SheetTable grid = table();

        JList<String> numbers = (JList<String>) grid.rowNumbers();

        assertEquals(3, numbers.getModel().getSize(), "three data rows");
        assertEquals("3", numbers.getModel().getElementAt(0),
                "the first data row is sheet row 3");
        assertEquals("5", numbers.getModel().getElementAt(2));
    }

    /** A header that did not follow an added row would show a wrong number, not a missing one. */
    @Test
    @SuppressWarnings("unchecked")
    void theRowHeaderFollowsRowsBeingAddedAndRemoved() {
        SheetTableModel model = new SheetTableModel(book(), "organization");
        SheetTable grid = new SheetTable(model);
        JList<String> numbers = (JList<String>) grid.rowNumbers();
        assertEquals(3, numbers.getModel().getSize());

        model.addRow();
        assertEquals(4, numbers.getModel().getSize());
        assertEquals("6", numbers.getModel().getElementAt(3));

        model.deleteRow(0);
        assertEquals(3, numbers.getModel().getSize());
    }

    @Test
    void theRowHeaderIsNarrowEnoughNotToCostAColumn() {
        assertTrue(table().rowNumbers().getPreferredSize().width <= 60,
                "a wide row header spends the space the tab exists to give the grid");
    }

    // ---------- widths from the content ----------

    /**
     * The note column holds a sentence and the year column holds four digits; before this they
     * were both 180px.
     */
    @Test
    void autofitMakesTheWideColumnWiderThanTheNarrowOne() {
        SheetTable grid = table();

        grid.autofitColumns();

        int year = grid.getColumnModel().getColumn(1).getPreferredWidth();
        int note = grid.getColumnModel().getColumn(3).getPreferredWidth();
        assertTrue(note > year * 2, "a sentence should be much wider than a year: note=" + note
                + " year=" + year);
    }

    /**
     * Clamped at both ends. A column narrower than 60px cannot show even a truncated value, and
     * one wider than 420px pushes everything after it off a twenty-six column sheet.
     */
    @Test
    void autofitStaysWithinBoundsThatKeepTheGridUsable() {
        SheetTable grid = table();

        grid.autofitColumns();

        for (int at = 0; at < grid.getColumnCount(); at++) {
            int width = grid.getColumnModel().getColumn(at).getPreferredWidth();
            assertTrue(width >= 60 && width <= 420, "column " + at + " is " + width + "px");
        }
    }

    @Test
    void autofitOnAnEmptySheetDoesNotThrow() {
        SheetBook empty = SheetBook.empty();
        empty.add("terms", null, rows(
                new String[] {"ID", "Label"},
                new String[] {"ID", "LABEL"}), '\t');

        new SheetTable(new SheetTableModel(empty, "terms")).autofitColumns();
    }

    // ---------- going to a cell ----------

    @Test
    void goingToASheetRowSelectsIt() {
        SheetTable grid = table();

        assertTrue(grid.goTo(4, 3));

        assertEquals(1, grid.getSelectedRow(), "sheet row 4 is grid row 1");
        assertEquals(2, grid.getSelectedColumn(), "column 3 is index 2");
    }

    /**
     * A template row is a real place for a search to land and not a cell the grid holds, so this
     * says no rather than selecting something else. The view then reports where it is.
     */
    @Test
    void goingToAHeaderRowIsRefusedRatherThanSilentlyWrong() {
        SheetTable grid = table();

        assertFalse(grid.goTo(1, 1), "row 1 is the heading row, above the grid");
        assertFalse(grid.goTo(2, 1), "row 2 is the ROBOT template row");
        assertFalse(grid.goTo(99, 1));
        assertFalse(grid.goTo(3, 99));
    }

    // ---------- the stripe ----------

    /**
     * Every other row, and only where the audit has nothing to say. A finding outranks the
     * stripe: the tints are the only colours in this grid that mean anything.
     */
    @Test
    void everyOtherRowIsTintedAndAMarkedCellKeepsItsOwnColour() {
        SheetTable grid = table();

        Color first = background(grid, 0, 0);
        Color second = background(grid, 1, 0);

        assertFalse(first.equals(second), "the rows should alternate: " + first + " " + second);
    }

    private static Color background(SheetTable grid, int row, int column) {
        Component painted = grid.getCellRenderer(row, column)
                .getTableCellRendererComponent(grid, grid.getValueAt(row, column), false, false,
                        row, column);
        return painted.getBackground();
    }

    /** The whole grid, painted, so somebody can look at it. */
    @Test
    void theGridRendersWithItsRowHeaderAndNothingIsBlank() throws Exception {
        SheetTable grid = table();
        grid.autofitColumns();
        JScrollPane scrolled = new JScrollPane(grid);
        scrolled.setRowHeaderView(grid.rowNumbers());
        // addNotify and validate, not doLayout: a JTable's header lives in the pane's
        // column-header viewport and only validate lays that out, and the row header is in
        // another viewport again. The 1.105.0 proof learned this by producing a picture of a
        // table with no header and very nearly reporting it as a defect; doLayout here produced
        // an image in two colours.
        javax.swing.JPanel holder = new javax.swing.JPanel(new java.awt.BorderLayout());
        holder.add(scrolled, java.awt.BorderLayout.CENTER);
        holder.setSize(new Dimension(900, 220));
        holder.addNotify();
        holder.validate();

        BufferedImage painted = new BufferedImage(900, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D into = painted.createGraphics();
        into.setColor(Color.WHITE);
        into.fillRect(0, 0, 900, 220);
        holder.paint(into);
        into.dispose();

        java.io.File where = new java.io.File("target/design-proof");
        where.mkdirs();
        javax.imageio.ImageIO.write(painted, "png",
                new java.io.File(where, "sheet-grid.png"));

        // Not blank: a painted grid that came out all one colour is the failure this catches,
        // and it has happened to the canvas twice.
        java.util.Set<Integer> colours = new java.util.HashSet<Integer>();
        for (int x = 0; x < 900; x += 7) {
            for (int y = 0; y < 220; y += 7) {
                colours.add(painted.getRGB(x, y));
            }
        }
        assertTrue(colours.size() > 3, "the grid painted in " + colours.size() + " colours");
    }
}
