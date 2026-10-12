package de.fizkarlsruhe.ise.ontoboard.views;

import de.fizkarlsruhe.ise.ontoboard.sheet.LabelIndex;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * The grid a person edits a sheet in.
 *
 * <p>A {@link JTable} with three things added, each of which exists because of something
 * measured on real sheets rather than because a spreadsheet usually has it.
 *
 * <ul>
 *   <li><b>A two-line header.</b> The author's own heading above ROBOT's spec. A header reading
 *       {@code I http://purl.obolibrary.org/obo/RO_0001025} tells a domain expert nothing and
 *       {@code City} tells them everything, but the spec is what decides whether the column
 *       completes - so both are shown rather than choosing.</li>
 *   <li><b>Marked cells.</b> A cell the audit has something to say about is tinted and carries
 *       the finding as its tooltip. On the MatWerk sheets that is 1,227 cells, so a panel
 *       listing them is not enough: they have to be visible where the typing happens.</li>
 *   <li><b>Completion in the columns that need it.</b> A column whose cells name another term
 *       gets an editable combo offering names from every open sheet and from the ontology;
 *       a column holding text gets a plain field. Completing a label column would be wrong -
 *       the text there is the value, not a reference.</li>
 * </ul>
 *
 * <p>Separate from the view component so it can be constructed, laid out and painted in a test
 * without Prot&eacute;g&eacute;. {@code CanvasDesignProofTest} made that argument for the
 * diagram and it holds here: this is the surface somebody spends an afternoon looking at.
 */
public final class SheetTable extends JTable {

    private static final long serialVersionUID = 1L;

    /** How many names to offer. More than a screenful is a list nobody reads. */
    private static final int MOST_COMPLETIONS = 12;

    /**
     * The tint for a cell with something wrong.
     *
     * <p>Pale on purpose. A cell has to stay readable while it is marked, and on a sheet where
     * a fifth of the cells carry a finding a strong colour makes the grid unusable. Two shades:
     * one for something that can be corrected automatically, one for something needing a
     * person, because those call for different actions.
     */
    private static final Color FIXABLE = new Color(0xFF, 0xF4, 0xCE);
    private static final Color NEEDS_A_PERSON = new Color(0xFF, 0xE4, 0xE1);
    private static final Color MARKED_SELECTED = new Color(0xDC, 0xE7, 0xF5);

    /**
     * Every other row, very slightly off-white.
     *
     * <p>Not decoration. The sheets this was built for are up to twenty-six columns wide, and
     * reading along one row of a wide grid without a banding cue is the thing people get wrong -
     * they take a value from the row above or below. Two percent of grey is enough to follow and
     * too little to compete with the audit tints, which are the only colours here that mean
     * anything.
     */
    private static final Color EVERY_OTHER_ROW = new Color(0xF2, 0xF3, 0xF7);

    /** A cell the search has just landed on, for as long as it stays selected. */
    private static final Color FOUND = new Color(0xFF, 0xE9, 0x7F);

    private final SheetTableModel sheet;

    /** Where the search last landed: sheet row and 1-based column, or -1. */
    private int foundRow = -1;
    private int foundColumn = -1;

    public SheetTable(SheetTableModel sheet) {
        super(sheet);
        this.sheet = sheet;
        setAutoResizeMode(AUTO_RESIZE_OFF);
        setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        setCellSelectionEnabled(true);
        setRowHeight(Math.max(20, getRowHeight()));
        setFillsViewportHeight(true);
        setShowGrid(true);
        setGridColor(new Color(0xE0, 0xE0, 0xE0));
        // Starting an edit on the first keystroke is what a spreadsheet does, and the thing
        // people complain about when a table does not.
        setSurrendersFocusOnKeystroke(true);
        configureColumns();
    }

    /**
     * What the scroll pane should show above the grid, instead of the bare column header.
     *
     * <p>Set by the view to a panel holding the {@code JTableHeader} and the two editable header
     * rows. It has to be remembered rather than simply installed, because {@code JTable} puts
     * its own header back: {@code configureEnclosingScrollPane()} runs from {@code addNotify()}
     * and calls {@code setColumnHeaderView(getTableHeader())}, so a composite installed before
     * the table is realised is silently discarded. Measured - the editable rows laid out at
     * 0 x 0 while the picture still looked plausible.
     */
    private javax.swing.JComponent compositeHeader;

    /**
     * Puts {@code view} above the grid, and keeps it there.
     *
     * <p>{@code view} must use a {@link java.awt.BorderLayout} with its NORTH slot reserved for
     * this table's own {@code JTableHeader}, because that header gets <em>reparented</em>: when
     * {@code JTable} reinstates its own column header it moves the component out of whatever
     * container it was in, and this has to put it back.
     */
    public void setCompositeColumnHeader(javax.swing.JComponent view) {
        this.compositeHeader = view;
        installCompositeHeader();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden to put the composite header back after {@code JTable} has replaced it with
     * its own. This is the hook {@code JTable} documents for the purpose.
     */
    @Override
    protected void configureEnclosingScrollPane() {
        super.configureEnclosingScrollPane();
        installCompositeHeader();
    }

    private void installCompositeHeader() {
        if (compositeHeader == null) {
            return;
        }
        // JTable does not merely replace the scroll pane's column header view - it moves the
        // JTableHeader into it, out of the composite. Measured: the composite then laid out at
        // 44px rather than 60 and the column letters vanished, while the editable rows were
        // present and the picture looked fine.
        if (getTableHeader() != null && getTableHeader().getParent() != compositeHeader) {
            compositeHeader.add(getTableHeader(), java.awt.BorderLayout.NORTH);
            compositeHeader.revalidate();
        }
        java.awt.Container parent = getParent();
        if (!(parent instanceof javax.swing.JViewport)) {
            return;
        }
        java.awt.Container above = parent.getParent();
        if (above instanceof javax.swing.JScrollPane) {
            javax.swing.JScrollPane pane = (javax.swing.JScrollPane) above;
            if (pane.getViewport() != null && pane.getViewport().getView() == this) {
                pane.setColumnHeaderView(compositeHeader);
            }
        }
    }

    /** Re-reads the sheet's shape, for when a column has been added. */
    public void configureColumns() {
        setDefaultRenderer(Object.class, new Marked());
        for (int at = 0; at < getColumnCount(); at++) {
            TableColumn column = getColumnModel().getColumn(at);
            column.setPreferredWidth(widthFor(at));
            column.setHeaderRenderer(new ColumnLetter());
            column.setCellEditor(editorFor(at));
        }
    }

    /**
     * Widths from what the cells actually hold, within limits.
     *
     * <p>The fixed widths {@link #widthFor} gives are right for the columns a ROBOT template
     * always has and wrong for the rest: a <i>Note</i> column of two-sentence comments and a
     * <i>Year</i> column of four digits were both 180px, so one was unreadable and the other
     * was mostly white space. Measured from a sample rather than the whole sheet, because the
     * MatWerk graph is 8,471 rows and nobody needs the widest cell in it to decide a column
     * width.
     *
     * <p>Clamped at both ends. A column narrower than 60px cannot show even a truncated value,
     * and one wider than 420px pushes everything after it off the screen - which on a
     * twenty-six-column sheet means most of it.
     */
    public void autofitColumns() {
        java.awt.FontMetrics metrics = getFontMetrics(getFont());
        int sample = Math.min(getRowCount(), 60);
        for (int at = 0; at < getColumnCount(); at++) {
            int model = convertColumnIndexToModel(at);
            int widest = metrics.stringWidth(sheet.getColumnName(model));
            // The template string too, shortened as the header row shows it. Measuring only the
            // heading and the data clipped the spec on exactly the columns whose spec explains
            // something - "AT IAO_0000235^^xsd:anyURI" is wider than any of that column's values.
            widest = Math.max(widest, metrics.stringWidth(shortSpec(sheet.specOf(model))));
            for (int row = 0; row < sample; row++) {
                Object value = sheet.getValueAt(row, model);
                if (value != null) {
                    widest = Math.max(widest, metrics.stringWidth(value.toString()));
                }
            }
            int wanted = Math.max(60, Math.min(420, widest + 18));
            getColumnModel().getColumn(at).setPreferredWidth(wanted);
        }
    }

    /**
     * A column of sheet row numbers down the left, as a spreadsheet has.
     *
     * <p>Every message about these sheets names a row - the audit says "row 412", the build says
     * which row would not build - and until now the grid showed no row numbers at all, so
     * finding the row a message named meant counting. The numbers are the <em>sheet's</em>, not
     * the grid's: data starts at sheet row 3, and a header that counted from 1 would send
     * somebody two rows wrong.
     */
    public javax.swing.JComponent rowNumbers() {
        final javax.swing.JList<String> numbers = new javax.swing.JList<String>(
                new javax.swing.AbstractListModel<String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public int getSize() {
                        return getRowCount();
                    }

                    @Override
                    public String getElementAt(int index) {
                        return String.valueOf(sheet.sheetRowOf(index));
                    }
                });
        numbers.setFixedCellHeight(getRowHeight());
        numbers.setFixedCellWidth(52);
        numbers.setFocusable(false);
        numbers.setBackground(new Color(0xF0, 0xF0, 0xF2));
        numbers.setCellRenderer(new javax.swing.ListCellRenderer<String>() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<? extends String> list, String value, int index,
                    boolean selected, boolean focused) {
                JLabel label = new JLabel(value, JLabel.RIGHT);
                label.setOpaque(true);
                label.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                        javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1,
                                new Color(0xC8, 0xC8, 0xC8)),
                        javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 5)));
                label.setFont(label.getFont().deriveFont(java.awt.Font.PLAIN,
                        Math.max(10f, label.getFont().getSize() - 1f)));
                boolean here = isRowSelected(index);
                label.setBackground(here ? new Color(0xDC, 0xE7, 0xF5)
                        : new Color(0xF0, 0xF0, 0xF2));
                label.setForeground(new Color(0x60, 0x60, 0x68));
                return label;
            }
        });
        // The numbers have to follow the rows. Adding, deleting or inserting a row changes how
        // many there are, and a header that did not notice would go on showing the old count -
        // which is worse than no header, because it is a wrong row number rather than none.
        sheet.addTableModelListener(new javax.swing.event.TableModelListener() {
            @Override
            public void tableChanged(javax.swing.event.TableModelEvent event) {
                numbers.setFixedCellHeight(getRowHeight());
                numbers.revalidate();
                numbers.repaint();
            }
        });
        getSelectionModel().addListSelectionListener(
                new javax.swing.event.ListSelectionListener() {
                    @Override
                    public void valueChanged(javax.swing.event.ListSelectionEvent event) {
                        numbers.repaint();
                    }
                });
        return numbers;
    }

    /**
     * Moves to a cell and marks it as the one the search found.
     *
     * @param sheetRow the row as the sheet numbers rows
     * @param column 1-based, as the template columns are numbered
     * @return false when that cell is not in this grid
     */
    public boolean goTo(int sheetRow, int column) {
        int row = sheet.tableRowOf(sheetRow);
        if (row < 0 || row >= getRowCount() || column < 1 || column > getColumnCount()) {
            return false;
        }
        foundRow = sheetRow;
        foundColumn = column;
        changeSelection(row, convertColumnIndexToView(column - 1), false, false);
        java.awt.Rectangle where = getCellRect(row, convertColumnIndexToView(column - 1), true);
        scrollRectToVisible(where);
        repaint();
        return true;
    }

    /** Forgets the search highlight, for when the box is cleared. */
    public void clearFound() {
        foundRow = -1;
        foundColumn = -1;
        repaint();
    }

    /**
     * An identifier column is wide because an IRI is long; the rest get a readable default.
     *
     * <p>Measured rather than guessed at: the MatWerk identifiers are 46 characters and a
     * column narrow enough to hide them makes the sheet impossible to check.
     */
    private int widthFor(int column) {
        de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns.Column spec = sheet.columnAt(column);
        if (spec == null) {
            return 160;
        }
        switch (spec.getKind()) {
            case ID:
                return 320;
            case LABEL:
            case ANNOTATION_LANGUAGE:
                return 220;
            case TYPE:
                return 240;
            case UNUSED:
                return 110;
            default:
                return 180;
        }
    }

    /**
     * A completing combo where the column names a term, a plain field where it holds text.
     *
     * <p>The combo is editable, so a name nobody has used yet can still be typed - which is the
     * case that leads to creating it. A closed dropdown would make the 123 unresolved
     * references on the real sheets impossible to enter in the first place.
     */
    private TableCellEditor editorFor(final int column) {
        if (!sheet.namesATerm(column)) {
            JTextField field = new JTextField();
            field.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 3, 0, 3));
            return new DefaultCellEditor(field);
        }
        final JComboBox<String> combo = new JComboBox<String>();
        combo.setEditable(true);
        final JTextField editor = (JTextField) combo.getEditor().getEditorComponent();
        editor.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 3, 0, 3));
        // Offer names when the box is opened rather than on every keystroke: rebuilding the
        // list under somebody's fingers moves what they were about to click on.
        combo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!"comboBoxEdited".equals(event.getActionCommand())) {
                    return;
                }
                offer(combo, editor.getText(), column);
            }
        });
        return new DefaultCellEditor(combo);
    }

    /** Fills the dropdown with what matches, newest query wins. */
    private void offer(JComboBox<String> combo, String typed, int column) {
        combo.removeAllItems();
        List<LabelIndex.Entry> found = sheet.completionsFor(column, typed, MOST_COMPLETIONS);
        for (LabelIndex.Entry entry : found) {
            combo.addItem(entry.getLabel());
        }
        combo.getEditor().setItem(typed);
    }

    /** The names on offer for a cell, which a test can ask for without opening a dropdown. */
    public List<LabelIndex.Entry> completionsFor(int row, int column, String typed) {
        return sheet.completionsFor(column, typed, MOST_COMPLETIONS);
    }

    @Override
    public String getToolTipText(java.awt.event.MouseEvent event) {
        int row = rowAtPoint(event.getPoint());
        int column = columnAtPoint(event.getPoint());
        if (row < 0 || column < 0) {
            return null;
        }
        SheetAudit.Finding finding = sheet.findingAt(row, convertColumnIndexToModel(column));
        if (finding == null) {
            return null;
        }
        String message = finding.getMessage();
        if (finding.isFixable()) {
            message += "  ->  \"" + finding.getSuggestion() + "\"";
        }
        return "<html><body style='width:380px'>" + escape(message) + "</body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Paints a cell, tinted when the audit has something to say about it. */
    private final class Marked extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            Component painted = super.getTableCellRendererComponent(table, value, selected,
                    focused, row, column);
            int model = table.convertColumnIndexToModel(column);
            SheetAudit.Finding finding = sheet.findingAt(row, model);
            boolean isTheFoundCell = sheet.sheetRowOf(row) == foundRow
                    && model + 1 == foundColumn;
            if (finding == null) {
                if (!selected) {
                    painted.setBackground(isTheFoundCell ? FOUND
                            : row % 2 == 1 ? EVERY_OTHER_ROW : table.getBackground());
                }
                return painted;
            }
            // A finding outranks the stripe, and the search outranks nothing: somebody who
            // searched knows where they are, and a tint that means "this is wrong" is the one
            // colour in the grid that has to survive.
            painted.setBackground(selected ? MARKED_SELECTED
                    : finding.isFixable() ? FIXABLE : NEEDS_A_PERSON);
            return painted;
        }
    }

    /**
     * A ROBOT spec with its IRIs shortened, so the header line fits the column.
     *
     * <p>The design proof caught this and the assertions did not. Rendered at a usable column
     * width, {@code I http://purl.obolibrary.org/obo/RO_0001025} was clipped to {@code I} and
     * {@code AT http://purl.obolibrary.org/obo/IAO_0000235^^xsd:anyURI} to {@code AT} - so the
     * second line said nothing at all for exactly the columns whose behaviour it was there to
     * explain. Widening the columns to 43 characters of IRI would make the grid unusable, so
     * the IRI is shown by its last segment and the whole thing stays in the tooltip.
     */
    static String shortSpec(String spec) {
        if (spec == null || spec.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(spec.length());
        for (String word : spec.split(" ")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            int cut = Math.max(word.lastIndexOf('/'), word.lastIndexOf('#'));
            // Only when there is something after the separator; a trailing slash is not a name.
            out.append(cut >= 0 && cut < word.length() - 1 ? word.substring(cut + 1) : word);
        }
        return out.toString();
    }

    /**
     * The spreadsheet column letter, and nothing else.
     *
     * <p>It used to paint the author's heading above ROBOT's spec, which was the only way to see
     * either of them. From 1.118.0 both are in {@link SheetHeaderTable}, one row each, directly
     * underneath this - where they can be read <em>and</em> edited. Repeating them here would
     * cost a third of the vertical space to say the same thing twice.
     *
     * <p>The letter is not decoration: it is the drag target for resizing a column, which is why
     * this header stays rather than being removed outright, and it is how a person refers to a
     * column out loud.
     */
    private final class ColumnLetter implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            JLabel label = new JLabel(
                    SheetHeaderTable.letterFor(table.convertColumnIndexToModel(column)),
                    JLabel.CENTER);
            label.setOpaque(true);
            label.setFont(label.getFont().deriveFont(java.awt.Font.PLAIN,
                    Math.max(10f, label.getFont().getSize() - 1f)));
            label.setForeground(new Color(0x60, 0x60, 0x68));
            label.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 1,
                    new Color(0xC8, 0xC8, 0xC8)));
            JTableHeader header = table.getTableHeader();
            label.setBackground(header == null ? new Color(0xE8, 0xE8, 0xEC)
                    : header.getBackground());
            return label;
        }
    }
}
