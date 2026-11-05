package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.table.TableColumn;
import org.junit.jupiter.api.Test;

/**
 * The rows of a sheet and the two header rows above them are drawn to the same columns, however
 * wide the window is.
 *
 * <p>Reported from Protege 5.6.9 with 1.134.0 on pizza-bfo's {@code 04_baker_roles}: four columns
 * on a wide screen. The header rows ran across the whole tab, and the rows beneath were cut off
 * part of the way through the second column, the rest of the tab grey - so Name and Role of
 * could not be seen at all. Every sheet of that project did it, because none of them is as wide
 * as the window.
 */
class SheetGridWidthTest {

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** pizza-bfo's kg/templates/04_baker_roles.tsv, as committed. */
    private static SheetBook bakerRoles() {
        SheetBook book = SheetBook.empty();
        book.add("04_baker_roles", null, rows(
                new String[] {"ID", "Type", "Name", "Role of"},
                new String[] {"ID", "TYPE", "AL rdfs:label@en", "I RO:0000081"},
                new String[] {"kg:PB_0000301", "pizza baker role", "baker role of Luigi Rossi",
                              "Luigi Rossi"},
                new String[] {"kg:PB_0000302", "pizza baker role",
                              "baker role of Maria Esposito", "Maria Esposito"}), '\t');
        return book;
    }

    /** One sheet's grid as the view builds it, laid out in a window of the given size. */
    private static final class Grid {
        final JScrollPane pane;
        final SheetTable rows;
        final SheetHeaderTable header;

        Grid(SheetBook book, String sheet, int width, int height) {
            rows = new SheetTable(new SheetTableModel(book, sheet));
            header = new SheetHeaderTable(book, sheet, rows);
            pane = SheetEditorView.gridFor(rows, header);
            pane.setSize(width, height);
            layOut(pane);
        }

        /**
         * What validating the tab does, without a screen: each container lays out, then each of
         * its children in turn. The column header comes after the viewport, as it does in Swing,
         * so whatever it does to the shared columns is what is left on screen.
         */
        private static void layOut(Component component) {
            if (component instanceof Container) {
                Container container = (Container) component;
                container.doLayout();
                for (Component child : container.getComponents()) {
                    layOut(child);
                }
            }
        }
    }

    @Test
    void aSheetNarrowerThanTheWindowIsNotCutOff() {
        Grid grid = new Grid(bakerRoles(), "04_baker_roles", 2000, 600);

        int columns = grid.rows.getColumnModel().getTotalColumnWidth();
        assertTrue(columns <= grid.rows.getWidth(), "the columns are " + columns
                + " px across and the grid that draws them " + grid.rows.getWidth()
                + " px, so everything past " + grid.rows.getWidth() + " px is cut off");
    }

    @Test
    void theHeaderRowsAreAsWideAsTheRowsBeneathThem() {
        Grid grid = new Grid(bakerRoles(), "04_baker_roles", 2000, 600);

        assertEquals(grid.rows.getWidth(), grid.header.getWidth());
    }

    /** The widths autofit chose from what the cells hold, not stretched to fill the window. */
    @Test
    void aWideWindowDoesNotStretchTheColumns() {
        Grid grid = new Grid(bakerRoles(), "04_baker_roles", 2000, 600);

        for (int at = 0; at < grid.rows.getColumnCount(); at++) {
            TableColumn column = grid.rows.getColumnModel().getColumn(at);
            assertEquals(column.getPreferredWidth(), column.getWidth(),
                    "column " + (at + 1) + " (" + column.getHeaderValue() + ")");
        }
    }

    /** The other case, which always worked: wider than the window, the sheet scrolls. */
    @Test
    void aSheetWiderThanTheWindowKeepsItsColumnsAndScrolls() {
        Grid grid = new Grid(bakerRoles(), "04_baker_roles", 300, 600);

        int columns = grid.rows.getColumnModel().getTotalColumnWidth();
        assertTrue(columns > 300, "the four columns should not fit in 300 px: " + columns);
        assertEquals(columns, grid.rows.getWidth());
        assertEquals(grid.rows.getWidth(), grid.header.getWidth());
    }
}
