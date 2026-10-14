package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Copying a block out of the grid and pasting one into it.
 *
 * <p>These go through the grid and the model and stop short of the system clipboard, which
 * needs a desktop that a build machine does not have. What the clipboard carries is a string,
 * and both directions are tested against the string.
 *
 * <p>The sheet below is the shape the real ones are: an identifier column, a template row that
 * ROBOT understands, and three data rows starting at row 3 of the file and row 0 of the grid.
 */
class SheetClipboardTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    private static SheetTable table() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "Institution name", "City"},
                new String[] {"ID", "A rdfs:label", "A ex:city"},
                new String[] {MW + "1", "Fraunhofer-Gesellschaft", "Munich"},
                new String[] {MW + "2", "Max Planck Society", "Munich"},
                new String[] {MW + "3", "Bundesanstalt", "Berlin"}), '\t');
        SheetTableModel model = new SheetTableModel(book, "organization");
        model.refreshFindings();
        return new SheetTable(model);
    }

    private static SheetBook bookOf(SheetTable table) {
        return ((SheetTableModel) table.getModel()).getBook();
    }

    // ---------- out ----------

    @Test
    void theSelectedBlockComesOutAsTabSeparatedRows() {
        SheetTable grid = table();
        grid.setRowSelectionInterval(0, 1);
        grid.setColumnSelectionInterval(1, 2);

        assertEquals("Fraunhofer-Gesellschaft\tMunich\nMax Planck Society\tMunich\n",
                SheetClipboard.selectionOf(grid));
    }

    @Test
    void oneCellComesOutWithoutAnyDelimiterAtAll() {
        SheetTable grid = table();
        grid.setRowSelectionInterval(2, 2);
        grid.setColumnSelectionInterval(1, 1);

        assertEquals("Bundesanstalt\n", SheetClipboard.selectionOf(grid));
    }

    @Test
    void nothingSelectedCopiesNothingRatherThanEverything() {
        SheetTable grid = table();
        grid.clearSelection();

        assertEquals("", SheetClipboard.selectionOf(grid));
    }

    /**
     * A cell holding a tab or a quote has to come out quoted, or pasting it anywhere - here,
     * Excel, a text editor - turns one cell into two.
     */
    @Test
    void aCellWithADelimiterInItComesOutQuoted() {
        SheetTable grid = table();
        bookOf(grid).setCell("organization", 3, 2, "a\tb");
        bookOf(grid).setCell("organization", 4, 2, "say \"hi\"");
        grid.setRowSelectionInterval(0, 1);
        grid.setColumnSelectionInterval(1, 1);

        assertEquals("\"a\tb\"\n\"say \"\"hi\"\"\"\n", SheetClipboard.selectionOf(grid));
    }

    // ---------- in ----------

    @Test
    void aBlockLandsWithItsTopLeftAtTheSelectedCell() {
        SheetTable grid = table();

        int changed = SheetClipboard.pasteInto(grid, 1, 1, "Helmholtz\tBonn\nLeibniz\tHanover\n");

        assertEquals(4, changed);
        assertEquals("Helmholtz", bookOf(grid).cell("organization", 4, 2));
        assertEquals("Bonn", bookOf(grid).cell("organization", 4, 3));
        assertEquals("Leibniz", bookOf(grid).cell("organization", 5, 2));
        assertEquals("Hanover", bookOf(grid).cell("organization", 5, 3));
        assertEquals(MW + "1", bookOf(grid).cell("organization", 3, 1),
                "the row above must be untouched");
    }

    /** Pasting forty rows into a sheet with three is the normal reason to paste. */
    @Test
    void aBlockLongerThanTheSheetGrowsTheSheet() {
        SheetTable grid = table();
        StringBuilder forty = new StringBuilder();
        for (int at = 1; at <= 40; at++) {
            forty.append("org ").append(at).append("\tsomewhere\n");
        }

        SheetClipboard.pasteInto(grid, 0, 1, forty.toString());

        assertEquals(40, grid.getRowCount(), "three rows must have become forty");
        assertEquals("org 40", bookOf(grid).cell("organization", 42, 2));
        assertTrue(bookOf(grid).isUnsaved());
    }

    @Test
    void aQuotedCellArrivesAsOneCellAndKeepsItsQuotes() {
        SheetTable grid = table();

        SheetClipboard.pasteInto(grid, 0, 1, "\"a\tb\"\tBonn\n\"say \"\"hi\"\"\"\tKiel\n");

        assertEquals("a\tb", bookOf(grid).cell("organization", 3, 2));
        assertEquals("Bonn", bookOf(grid).cell("organization", 3, 3));
        assertEquals("say \"hi\"", bookOf(grid).cell("organization", 4, 2));
        assertEquals("Kiel", bookOf(grid).cell("organization", 4, 3));
    }

    /**
     * Excel ends the block with a newline. Treating that as another row pastes a row of blanks
     * under the data, which is worse than it sounds: a blank row with an empty ID column is a
     * row ROBOT builds nothing from and the audit reports.
     */
    @Test
    void aTrailingNewlineDoesNotPasteAnEmptyRow() {
        SheetTable grid = table();

        SheetClipboard.pasteInto(grid, 0, 1, "Helmholtz\tBonn\n");

        assertEquals(3, grid.getRowCount(), "still three rows");
        assertEquals("Max Planck Society", bookOf(grid).cell("organization", 4, 2));
    }

    @Test
    void anEmptyClipboardChangesNothing() {
        SheetTable grid = table();

        assertEquals(0, SheetClipboard.pasteInto(grid, 0, 0, ""));
        assertEquals(0, SheetClipboard.pasteInto(grid, 0, 0, "\n"));
        assertEquals("Fraunhofer-Gesellschaft", bookOf(grid).cell("organization", 3, 2));
    }

    /** One undo entry for the whole paste, not one per cell. */
    @Test
    void pastingIsOneThingToUndo() {
        SheetTable grid = table();
        SheetClipboard.pasteInto(grid, 0, 1, "Helmholtz\tBonn\nLeibniz\tHanover\n");

        bookOf(grid).undo();

        assertEquals("Fraunhofer-Gesellschaft", bookOf(grid).cell("organization", 3, 2),
                "undoing a paste has to put the whole block back");
        assertEquals("Munich", bookOf(grid).cell("organization", 3, 3));
        assertEquals("Max Planck Society", bookOf(grid).cell("organization", 4, 2));
    }

    @Test
    void whatIsCopiedOutPastesBackInAsTheSameCells() {
        SheetTable grid = table();
        grid.setRowSelectionInterval(0, 2);
        grid.setColumnSelectionInterval(1, 2);
        String copied = SheetClipboard.selectionOf(grid);

        SheetTable other = table();
        SheetClipboard.pasteInto(other, 0, 1, copied);

        for (int row = 3; row <= 5; row++) {
            for (int column = 2; column <= 3; column++) {
                assertEquals(bookOf(grid).cell("organization", row, column),
                        bookOf(other).cell("organization", row, column),
                        "row " + row + " column " + column);
            }
        }
    }

    // ---------- the keys ----------

    /**
     * Both modifiers, and no call to {@code Toolkit.getMenuShortcutKeyMask()}.
     *
     * <p>That call is how this was written first, and CI caught it within the minute: it throws
     * {@code HeadlessException}, so installing the clipboard was impossible on a machine with no
     * display. The host smoke could never have found it - a Protege has a display. Binding both
     * CTRL and META is what the canvas has always done, and it costs nothing.
     */
    @Test
    void bothMenuModifiersAreBoundAndNeitherNeedsADisplay() {
        SheetTable grid = table();
        SheetClipboard.install(grid, null);

        for (int mask : new int[] {java.awt.event.InputEvent.CTRL_DOWN_MASK,
                                   java.awt.event.InputEvent.META_DOWN_MASK}) {
            assertEquals("ontoboard.sheet.copy", boundTo(grid, java.awt.event.KeyEvent.VK_C, mask),
                    "copy under mask " + mask);
            assertEquals("ontoboard.sheet.paste", boundTo(grid, java.awt.event.KeyEvent.VK_V, mask),
                    "paste under mask " + mask);
        }
        assertTrue(grid.getActionMap().get("ontoboard.sheet.paste") != null,
                "and an action to go with them");
    }

    /**
     * Reaching for the system clipboard where there is none gives up quietly.
     *
     * <p>On a build machine this is the headless case; on a desktop it is another application
     * holding the clipboard open, which happens. Either way the grid must be unchanged and
     * nothing may propagate - a failed copy is not worth a stack trace in somebody's log.
     */
    @Test
    void copyingWithNoClipboardAvailableIsQuietRatherThanFatal() {
        SheetTable grid = table();
        grid.setRowSelectionInterval(0, 0);
        grid.setColumnSelectionInterval(1, 1);

        String put = SheetClipboard.copy(grid);

        assertTrue(put.isEmpty() || put.equals("Fraunhofer-Gesellschaft\n"), put);
        assertEquals("Fraunhofer-Gesellschaft", bookOf(grid).cell("organization", 3, 2));
        assertEquals(0, SheetClipboard.paste(grid), "and pasting from nothing changes nothing");
    }

    private static Object boundTo(SheetTable grid, int key, int mask) {
        return grid.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(javax.swing.KeyStroke.getKeyStroke(key, mask));
    }
}
