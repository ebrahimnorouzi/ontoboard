package de.fizkarlsruhe.ise.ontoboard.views;

import de.fizkarlsruhe.ise.ontoboard.sheet.Delimited;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * Copy and paste, between these sheets and whatever the person was using before.
 *
 * <p>Which is Excel, or the sheet in the next tab, or a column of forty identifiers out of a
 * mail. A spreadsheet editor without {@code Ctrl+V} is a spreadsheet editor somebody retypes
 * forty rows into, so this is not a convenience: the paste path is how real data arrives. The
 * model could already do it - {@link SheetTableModel#paste} has been there since the grid was
 * written, with its rows-grow-to-fit behaviour and its single undo entry - and nothing was
 * bound to it, so the only way to reach it was from a test.
 *
 * <p>{@code JTable}'s own {@code Ctrl+C} would nearly do for copying, but its idea of the
 * clipboard format is not quite this one and it cannot paste at all, so both directions are
 * done here by the same rules the files are written by. See {@link Delimited}: a cell holding a
 * tab, a newline or a quote is quoted on the way out and unquoted on the way back, which is
 * what Excel does too, so a block survives the journey in either direction.
 *
 * <p>Cut is deliberately absent. Clearing a block is {@code Delete}, which the grid already
 * has, and a cut that silently empties cells somebody has not pasted yet is the one clipboard
 * operation that loses data on its own.
 */
final class SheetClipboard {

    private SheetClipboard() {
    }

    /**
     * Binds copy and paste on a grid.
     *
     * <p>{@code WHEN_ANCESTOR_OF_FOCUSED_COMPONENT} so that the keys still work while a cell
     * editor has the focus - which it does on every keystroke here, because the grid starts an
     * edit on the first one.
     */
    static void install(final SheetTable table, final Runnable afterPaste) {
        bind(table, KeyEvent.VK_C, "ontoboard.sheet.copy", new Runnable() {
            @Override
            public void run() {
                copy(table);
            }
        });
        bind(table, KeyEvent.VK_V, "ontoboard.sheet.paste", new Runnable() {
            @Override
            public void run() {
                if (paste(table) > 0 && afterPaste != null) {
                    afterPaste.run();
                }
            }
        });
    }

    /**
     * The modifiers a copy or a paste is bound under.
     *
     * <p>Both, rather than {@code Toolkit.getMenuShortcutKeyMask()}: that is deprecated on the
     * newer of the two Java versions this plugin is built for, and - the reason it was found -
     * it throws {@code HeadlessException}, so a class that asks for it cannot be constructed on
     * a build machine. The canvas has bound both masks since it was written; this now matches it.
     */
    private static final int[] MENU_KEYS = {
        java.awt.event.InputEvent.CTRL_DOWN_MASK, java.awt.event.InputEvent.META_DOWN_MASK,
    };

    private static void bind(SheetTable table, int key, String name, final Runnable what) {
        for (int mask : MENU_KEYS) {
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(KeyStroke.getKeyStroke(key, mask), name);
        }
        table.getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent ignored) {
                what.run();
            }
        });
    }

    // ---------------------------------------------------------------- copying

    /** Puts the selected block on the system clipboard. Returns what it put there. */
    static String copy(SheetTable table) {
        String text = selectionOf(table);
        if (!text.isEmpty()) {
            try {
                clipboard().setContents(new StringSelection(text), null);
            } catch (IllegalStateException busy) {
                // Another application is holding the clipboard open, which happens. Nothing to
                // be done, and nothing lost - the grid is unchanged.
                return "";
            } catch (java.awt.HeadlessException noDisplay) {
                // No clipboard to put it on. A failed copy is not worth a stack trace.
                return "";
            }
        }
        return text;
    }

    /**
     * The selected block as delimited text, one line per row.
     *
     * <p>The selection is a rectangle because the grid uses
     * {@code SINGLE_INTERVAL_SELECTION}, so there are no holes to decide about.
     */
    static String selectionOf(SheetTable table) {
        int[] rows = table.getSelectedRows();
        int[] columns = table.getSelectedColumns();
        if (rows.length == 0 || columns.length == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int row : rows) {
            List<String> line = new ArrayList<String>(columns.length);
            for (int column : columns) {
                Object value = table.getValueAt(row, column);
                line.add(value == null ? "" : value.toString());
            }
            text.append(Delimited.line(line, '\t'));
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- pasting

    /**
     * Pastes the clipboard with its top left at the selected cell.
     *
     * @return how many cells changed
     */
    static int paste(SheetTable table) {
        String text = fromClipboard();
        if (text.isEmpty()) {
            return 0;
        }
        int row = table.getSelectedRow();
        int column = table.getSelectedColumn();
        if (row < 0 || column < 0) {
            return 0;
        }
        return pasteInto(table, row, column, text);
    }

    /** Separated from the clipboard so a test can paste without a desktop. */
    static int pasteInto(SheetTable table, int row, int column, String text) {
        List<List<String>> block = Delimited.parse(text, '\t');
        if (block.isEmpty() || isAllBlank(block)) {
            // A clipboard holding one newline, or a row of empty cells, is what is left when
            // somebody selects past the end of their data. Pasting it would quietly empty the
            // cells it landed on, which is the one thing a paste must not do by accident -
            // clearing cells is Delete.
            return 0;
        }
        SheetTableModel model = (SheetTableModel) table.getModel();
        int changed = model.paste(row, column, block);
        if (changed > 0) {
            table.autofitColumns();
            int lastRow = Math.min(table.getRowCount() - 1, row + block.size() - 1);
            int lastColumn = Math.min(table.getColumnCount() - 1,
                    column + widestOf(block) - 1);
            table.setRowSelectionInterval(row, Math.max(row, lastRow));
            table.setColumnSelectionInterval(column, Math.max(column, lastColumn));
        }
        return changed;
    }

    private static boolean isAllBlank(List<List<String>> block) {
        for (List<String> row : block) {
            for (String cell : row) {
                if (cell != null && !cell.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int widestOf(List<List<String>> block) {
        int widest = 0;
        for (List<String> row : block) {
            widest = Math.max(widest, row.size());
        }
        return widest;
    }

    private static String fromClipboard() {
        try {
            Transferable held = clipboard().getContents(null);
            if (held == null || !held.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return "";
            }
            Object text = held.getTransferData(DataFlavor.stringFlavor);
            return text == null ? "" : text.toString();
        } catch (IllegalStateException busy) {
            return "";
        } catch (java.awt.HeadlessException noDisplay) {
            return "";
        } catch (java.awt.datatransfer.UnsupportedFlavorException notText) {
            return "";
        } catch (java.io.IOException gone) {
            // The owning application quit between asking and reading.
            return "";
        }
    }

    private static Clipboard clipboard() {
        return java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
    }
}
