package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.table.AbstractTableModel;

/**
 * One sheet, as a table a person can edit.
 *
 * <p>Swing's {@code TableModel} and nothing else - no view, no renderer, no listener wiring -
 * so the part that decides what a cell says, whether it can be edited, and what is wrong with
 * it is testable without opening a window. The parts that cannot be tested that way are the
 * ones that only draw.
 *
 * <h2>The header rows are the table's header</h2>
 *
 * <p>A ROBOT template's first two rows are not data: row 1 is the author's own headings and row
 * 2 says what each column means. Showing them as the first two rows of the grid would invite
 * somebody to sort them into the middle of the sheet, and sorting a template is how a sheet
 * stops building. So the grid holds data only, starting at the sheet's row 3, and the two
 * header rows become the column header - the heading above the spec, which is also the only
 * arrangement in which both are visible at once.
 *
 * <p>{@link #sheetRowOf} converts between the two numbering schemes and every message uses the
 * sheet's own, because that is what the person sees in their spreadsheet.
 *
 * <h2>Findings are attached to cells</h2>
 *
 * <p>{@link #refreshFindings} re-runs the audit and indexes it by cell, so a renderer can tint
 * a cell and a tooltip can explain it without either of them knowing what an audit is. Cheap
 * enough to call on every edit: the whole 26-sheet audit takes about six seconds, one sheet a
 * few milliseconds.
 */
public final class SheetTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;

    private final SheetBook book;
    private final String sheetName;
    private final Map<Long, SheetAudit.Finding> findings =
            new LinkedHashMap<Long, SheetAudit.Finding>();

    public SheetTableModel(SheetBook book, String sheetName) {
        this.book = book;
        this.sheetName = sheetName;
    }

    public SheetBook getBook() {
        return book;
    }

    public String getSheetName() {
        return sheetName;
    }

    /** The sheet row a grid row stands for, as a spreadsheet numbers rows. */
    public int sheetRowOf(int tableRow) {
        return tableRow + SheetBook.FIRST_DATA_ROW;
    }

    /** The grid row a sheet row appears at, or -1 when it is a header row. */
    public int tableRowOf(int sheetRow) {
        return sheetRow < SheetBook.FIRST_DATA_ROW ? -1
                : sheetRow - SheetBook.FIRST_DATA_ROW;
    }

    @Override
    public int getRowCount() {
        SheetBook.Sheet sheet = book == null ? null : book.get(sheetName);
        return sheet == null ? 0 : sheet.getDataRows();
    }

    @Override
    public int getColumnCount() {
        return book == null ? 0 : book.width(sheetName);
    }

    @Override
    public Object getValueAt(int row, int column) {
        return book.cell(sheetName, sheetRowOf(row), column + 1);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return true;
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        String text = value == null ? "" : value.toString();
        if (book.setCell(sheetName, sheetRowOf(row), column + 1, text)) {
            fireTableCellUpdated(row, column);
        }
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return String.class;
    }

    /**
     * The author's own heading, which is what they will look for.
     *
     * <p>Not the ROBOT spec: a column header reading {@code I http://purl.obolibrary.org/obo/RO_0001025}
     * tells a domain expert nothing, and {@code City} tells them everything. The spec belongs
     * underneath it, which a two-line header renderer supplies from {@link #specOf}.
     */
    @Override
    public String getColumnName(int column) {
        String heading = book.cell(sheetName, 1, column + 1).trim();
        return heading.isEmpty() ? "column " + (column + 1) : heading;
    }

    /** The ROBOT spec for a column, for the second line of the header. */
    public String specOf(int column) {
        return book.cell(sheetName, 2, column + 1).trim();
    }

    /** What the column does, for deciding how to edit it. Never null. */
    public TemplateColumns.Column columnAt(int column) {
        SheetBook.Sheet sheet = book.get(sheetName);
        return sheet == null ? null : sheet.getColumns().at(column + 1);
    }

    /** Whether cells in this column name another term, and so should complete. */
    public boolean namesATerm(int column) {
        TemplateColumns.Column spec = columnAt(column);
        return spec != null && spec.namesATerm();
    }

    /** Names to offer for what has been typed into a cell of this column. */
    public List<LabelIndex.Entry> completionsFor(int column, String typed, int limit) {
        return book.completionsFor(sheetName, column + 1, typed, limit);
    }

    // ---------------------------------------------------------------- findings

    /** Re-runs the audit for this sheet and indexes it by cell. */
    public void refreshFindings() {
        findings.clear();
        for (SheetAudit.Finding finding : book.audit(sheetName)) {
            int row = tableRowOf(finding.getRow());
            if (row < 0 || finding.getColumn() < 1) {
                continue;
            }
            // First one wins. A cell can attract several - whitespace and a curly quote, say -
            // and a renderer has one background and one tooltip to give.
            Long key = key(row, finding.getColumn() - 1);
            if (!findings.containsKey(key)) {
                findings.put(key, finding);
            }
        }
        fireTableDataChanged();
    }

    /** What is wrong with this cell, or null. */
    public SheetAudit.Finding findingAt(int row, int column) {
        return findings.get(key(row, column));
    }

    /** How many cells currently carry a finding. */
    public int findingCount() {
        return findings.size();
    }

    /** Everything wrong with this sheet, whether or not it belongs to one cell. */
    public List<SheetAudit.Finding> allFindings() {
        return book.audit(sheetName);
    }

    /** The findings that are not attached to a single cell, for a panel to list separately. */
    public List<SheetAudit.Finding> sheetWideFindings() {
        List<SheetAudit.Finding> wide = new ArrayList<SheetAudit.Finding>();
        for (SheetAudit.Finding finding : book.audit(sheetName)) {
            if (finding.getRow() < SheetBook.FIRST_DATA_ROW) {
                wide.add(finding);
            }
        }
        return wide;
    }

    private static Long key(int row, int column) {
        return Long.valueOf(((long) row << 20) | (column & 0xFFFFF));
    }

    // ---------------------------------------------------------------- editing

    /** Adds a row at the end and returns its grid row. */
    public int addRow() {
        int sheetRow = book.addRow(sheetName);
        fireTableRowsInserted(getRowCount() - 1, getRowCount() - 1);
        return tableRowOf(sheetRow);
    }

    /** Copies a grid row to just below itself and returns the copy's grid row. */
    public int duplicateRow(int row) {
        int sheetRow = book.duplicateRow(sheetName, sheetRowOf(row));
        if (sheetRow == 0) {
            return -1;
        }
        fireTableRowsInserted(row + 1, row + 1);
        return tableRowOf(sheetRow);
    }

    public boolean deleteRow(int row) {
        if (!book.deleteRow(sheetName, sheetRowOf(row))) {
            return false;
        }
        fireTableRowsDeleted(row, row);
        return true;
    }

    /** Copies one cell down a column, counting up where the text ends in a number. */
    public int fillDown(int column, int fromRow, int toRow) {
        int changed = book.fillDown(sheetName, column + 1, sheetRowOf(fromRow),
                sheetRowOf(toRow));
        if (changed > 0) {
            fireTableRowsUpdated(fromRow, toRow);
        }
        return changed;
    }

    /** Pastes a block with its top left at this cell, growing the sheet if it has to. */
    public int paste(int row, int column, List<List<String>> block) {
        int changed = book.paste(sheetName, sheetRowOf(row), column + 1, block);
        if (changed > 0) {
            fireTableDataChanged();
        }
        return changed;
    }

    /** Reverses the last change anywhere in the book, and says which sheet it was in. */
    public String undo() {
        String where = book.undo();
        if (!where.isEmpty()) {
            fireTableDataChanged();
        }
        return where;
    }
}
