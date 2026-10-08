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

    private final SheetTableModel sheet;

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

    /** Re-reads the sheet's shape, for when a column has been added. */
    public void configureColumns() {
        setDefaultRenderer(Object.class, new Marked());
        for (int at = 0; at < getColumnCount(); at++) {
            TableColumn column = getColumnModel().getColumn(at);
            column.setPreferredWidth(widthFor(at));
            column.setHeaderRenderer(new TwoLineHeader());
            column.setCellEditor(editorFor(at));
        }
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
            SheetAudit.Finding finding =
                    sheet.findingAt(row, table.convertColumnIndexToModel(column));
            if (finding == null) {
                if (!selected) {
                    painted.setBackground(table.getBackground());
                }
                return painted;
            }
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

    /** The author's heading, with ROBOT's spec in small type underneath. */
    private final class TwoLineHeader implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            int model = table.convertColumnIndexToModel(column);
            String heading = escape(sheet.getColumnName(model));
            String full = sheet.specOf(model);
            String spec = escape(shortSpec(full));
            JLabel label = new JLabel("<html><div style='padding:2px 4px'>"
                    + "<b>" + heading + "</b><br>"
                    + "<span style='font-size:85%;color:#777'>"
                    + (spec.isEmpty() ? "not used" : spec) + "</span></div></html>");
            label.setToolTipText(full.isEmpty()
                    ? "This column has nothing in the template row, so the build ignores it."
                    : full);
            label.setOpaque(true);
            JTableHeader header = table.getTableHeader();
            if (header != null) {
                label.setBackground(header.getBackground());
                label.setForeground(header.getForeground());
                label.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 1,
                        new Color(0xC8, 0xC8, 0xC8)));
            }
            return label;
        }
    }
}
