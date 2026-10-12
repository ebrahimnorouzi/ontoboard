package de.fizkarlsruhe.ise.ontoboard.views;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSpec;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;

/**
 * The two header rows of a sheet, editable, sitting where a column header normally does.
 *
 * <p>Row 1 is the author's own column name and row 2 is ROBOT's template string - the line that
 * decides what the column <em>does</em>. Until 1.118.0 both were painted into the column header
 * and could not be touched from here, which meant the one row that controls the build was the one
 * row a user had to leave Prot&eacute;g&eacute; to change. That is not a theoretical gap: in the
 * live MatWerk workbook row 1 of {@code req_2} says {@code l 5} where every other tab says
 * {@code #}, two columns of {@code organization} are spelled {@code A rdfs:label@en} - which
 * builds an annotation property whose IRI ends {@code #label@en} and leaves the whole sheet
 * unreferable by label - and one spec carries a double space. None of those could be fixed here.
 *
 * <h2>How it is put together</h2>
 *
 * <p>A two-row {@link JTable} that <b>shares the data grid's {@code TableColumnModel}</b>, placed
 * as the scroll pane's column-header view with the real {@code JTableHeader} above it. Sharing
 * the column model is what keeps the two grids in step: one set of widths, one drag to resize
 * both. The {@code JTableHeader} stays where it is - it is what makes columns resizable, and
 * {@code SheetTableTest} rightly dereferences it - and now shows the spreadsheet column letter,
 * because the heading it used to show is in the editable row immediately below.
 *
 * <h2>What it refuses to let you do quietly</h2>
 *
 * <p>Every edit is checked against {@link TemplateSpec}, which is a transcription of robot-core's
 * own {@code validateTemplateString}. The reason is the failure mode: a template string ROBOT
 * does not recognise is not a bad column, it is a {@code ColumnException} that produces
 * <em>nothing at all</em> from the sheet. A cell ROBOT would refuse is tinted and says so before
 * anybody runs a build.
 */
public final class SheetHeaderTable extends JTable {

    private static final long serialVersionUID = 1L;

    /** Where ROBOT would refuse the sheet outright. */
    private static final Color BREAKS_IT = new Color(0xFF, 0xD9, 0xD4);

    /** Where ROBOT accepts it and then produces nothing. */
    private static final Color DOES_NOTHING = new Color(0xFF, 0xF4, 0xCE);

    private static final Color HEADING_ROW = new Color(0xF0, 0xF1, 0xF5);
    private static final Color SPEC_ROW = new Color(0xF7, 0xF8, 0xFA);

    private final SheetBook book;
    private final String sheetName;
    private final Model model;

    public SheetHeaderTable(SheetBook book, String sheetName, SheetTable data) {
        this.book = book;
        this.sheetName = sheetName;
        this.model = new Model();
        setModel(model);
        // One column model for both grids: the widths cannot drift, and dragging a column edge
        // in the header resizes the data beneath it.
        setColumnModel(data.getColumnModel());
        setTableHeader(null);
        setRowHeight(22);
        setRowSelectionAllowed(false);
        setCellSelectionEnabled(true);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setShowGrid(true);
        setGridColor(new Color(0xD0, 0xD2, 0xD8));
        setSurrendersFocusOnKeystroke(true);
        setDefaultRenderer(Object.class, new Painted());
        setFillsViewportHeight(false);
        configureEditors();
    }

    /** The two rows, read and written straight through the book so undo and save work. */
    private final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        @Override
        public int getRowCount() {
            return 2;
        }

        @Override
        public int getColumnCount() {
            return book.width(sheetName);
        }

        @Override
        public Object getValueAt(int row, int column) {
            return book.cell(sheetName, row + 1, column + 1);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            // Through SheetBook, which records an undo step and marks the sheet unsaved exactly
            // as a data edit does. Nothing is normalised on the way: the cell is stored as typed,
            // because a sheet whose working spec got tidied on save is a sheet that stopped
            // building for a reason nobody can see.
            book.setCell(sheetName, row + 1, column + 1,
                    value == null ? "" : value.toString());
            fireTableRowsUpdated(row, row);
        }

        @Override
        public String getColumnName(int column) {
            return letterFor(column);
        }
    }

    /** {@code A}, {@code B} ... {@code AA}, as a spreadsheet names its columns. */
    static String letterFor(int index) {
        StringBuilder letters = new StringBuilder();
        int at = index;
        while (at >= 0) {
            letters.insert(0, (char) ('A' + at % 26));
            at = at / 26 - 1;
        }
        return letters.toString();
    }

    /**
     * Offers the legal template strings when row 2 is being edited.
     *
     * <p>Editable, so anything can still be typed - including the forms this plugin does not
     * model, which are legal and which {@link TemplateColumns} deliberately calls OTHER rather
     * than wrong. The list is there because the alternative is remembering that a language tag
     * goes on {@code AL} and never on {@code A}, which is the mistake sitting in the production
     * data twice.
     */
    public void configureEditors() {
        setDefaultEditor(Object.class, specEditor());
    }

    private TableCellEditor specEditor() {
        final JComboBox<String> combo = new JComboBox<String>();
        combo.setEditable(true);
        for (TemplateSpec.Form form : TemplateSpec.forms()) {
            if (!form.getSpec().isEmpty()) {
                combo.addItem(form.getSpec());
            }
        }
        JTextField editor = (JTextField) combo.getEditor().getEditorComponent();
        editor.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 3, 0, 3));
        return new DefaultCellEditor(combo) {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                    boolean selected, int row, int column) {
                Component component =
                        super.getTableCellEditorComponent(table, value, selected, row, column);
                // Row 1 is a free-text heading: offering ROBOT's template strings there would be
                // offering the wrong vocabulary for the cell.
                combo.setEditable(true);
                ((JTextField) combo.getEditor().getEditorComponent())
                        .setText(value == null ? "" : value.toString());
                return component;
            }
        };
    }

    // ---------------------------------------------------------------- what is wrong with it

    /**
     * Everything wrong with these two rows, recomputed from the book.
     *
     * <p>Recomputed rather than cached, on every paint and every keystroke. It is string work
     * over a few dozen columns, and the alternative - a cache invalidated from the right places -
     * is a bug waiting for whoever adds the next way to change a cell.
     */
    public List<TemplateSpec.Problem> problems() {
        SheetBook.Sheet sheet = book.get(sheetName);
        return sheet == null ? new ArrayList<TemplateSpec.Problem>()
                : TemplateSpec.check(sheet.getRows());
    }

    /** The worst thing wrong with one column, or null. */
    public TemplateSpec.Problem problemAt(int column) {
        TemplateSpec.Problem worst = null;
        for (TemplateSpec.Problem problem : problems()) {
            if (problem.getColumn() != column) {
                continue;
            }
            if (worst == null
                    || problem.getSeverity() == TemplateSpec.Severity.BREAKS_THE_SHEET) {
                worst = problem;
            }
        }
        return worst;
    }

    /** One line for the status bar: what is wrong with these header rows, or empty. */
    public String summary() {
        int breaks = 0;
        int quiet = 0;
        for (TemplateSpec.Problem problem : problems()) {
            if (problem.getSeverity() == TemplateSpec.Severity.BREAKS_THE_SHEET) {
                breaks++;
            } else {
                quiet++;
            }
        }
        if (breaks == 0 && quiet == 0) {
            return "";
        }
        StringBuilder said = new StringBuilder();
        if (breaks > 0) {
            said.append(breaks).append(breaks == 1 ? " template cell stops" : " template cells "
                    + "stop").append(" this sheet building at all");
        }
        if (quiet > 0) {
            said.append(said.length() == 0 ? "" : "; ").append(quiet)
                    .append(quiet == 1 ? " builds and produces nothing"
                            : " build and produce nothing");
        }
        return said.append('.').toString();
    }

    @Override
    public String getToolTipText(java.awt.event.MouseEvent event) {
        int row = rowAtPoint(event.getPoint());
        int column = columnAtPoint(event.getPoint());
        if (row < 0 || column < 0) {
            return null;
        }
        int model = convertColumnIndexToModel(column) + 1;
        StringBuilder said = new StringBuilder();
        said.append(row == 0
                ? "Row 1: the column name, which is yours. ROBOT needs it to be non-empty "
                        + "wherever row 2 is."
                : "Row 2: what ROBOT does with this column.");
        String spec = book.cell(sheetName, 2, model);
        if (row == 1 && !spec.trim().isEmpty()) {
            said.append("<br><br><b>").append(escape(spec)).append("</b>");
            for (TemplateSpec.Form form : TemplateSpec.forms()) {
                if (sameShape(form.getSpec(), spec)) {
                    said.append("<br>").append(escape(form.getMeaning()));
                    break;
                }
            }
        }
        TemplateSpec.Problem problem = problemAt(model);
        if (problem != null) {
            said.append("<br><br><b>")
                    .append(problem.getSeverity() == TemplateSpec.Severity.BREAKS_THE_SHEET
                            ? "This stops the sheet building:" : "This builds and does nothing:")
                    .append("</b><br>").append(escape(problem.getMessage()));
            if (!problem.getSuggestion().isEmpty()) {
                said.append("<br>Try: ").append(escape(problem.getSuggestion()));
            }
        }
        return "<html><body style='width:380px'>" + said + "</body></html>";
    }

    /** Whether a typed spec is an instance of one of the offered forms. */
    private static boolean sameShape(String form, String typed) {
        String keyword = form.split(" ")[0];
        String typedKeyword = typed.trim().split(" ")[0];
        return keyword.equals(typedKeyword);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Paints the heading row and the spec row differently, and marks what is wrong. */
    private final class Painted extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            Component painted = super.getTableCellRendererComponent(table, value, selected,
                    focused, row, column);
            int model = table.convertColumnIndexToModel(column) + 1;
            TemplateSpec.Problem problem = problemAt(model);
            if (!selected) {
                painted.setBackground(problem == null ? (row == 0 ? HEADING_ROW : SPEC_ROW)
                        : problem.getSeverity() == TemplateSpec.Severity.BREAKS_THE_SHEET
                                ? BREAKS_IT : DOES_NOTHING);
            }
            painted.setFont(painted.getFont().deriveFont(
                    row == 0 ? java.awt.Font.BOLD : java.awt.Font.PLAIN));
            if (painted instanceof JLabel) {
                JLabel label = (JLabel) painted;
                String text = value == null ? "" : value.toString();
                if (row == 1 && text.trim().isEmpty()) {
                    label.setText("not used");
                    label.setForeground(new Color(0x90, 0x90, 0x98));
                } else if (row == 1) {
                    // The spec with its IRIs shortened, which is the whole reason the full one
                    // lives in the tooltip: "I http://purl.obolibrary.org/obo/RO_0001025" clips
                    // to "I" at any usable column width.
                    label.setText(SheetTable.shortSpec(text));
                    label.setForeground(selected ? label.getForeground()
                            : new Color(0x44, 0x48, 0x55));
                } else {
                    label.setForeground(selected ? label.getForeground() : Color.BLACK);
                }
            }
            return painted;
        }
    }

    /** Refreshes both rows after something else changed the sheet. */
    public void refresh() {
        model.fireTableDataChanged();
    }

    /** Rebuilds for a sheet that has gained or lost a column. */
    public void refreshStructure() {
        model.fireTableStructureChanged();
        configureEditors();
    }

    /** Moves to one of the two header rows, for a search that landed there. */
    public boolean goTo(int sheetRow, int column) {
        if (sheetRow < 1 || sheetRow > 2 || column < 1 || column > getColumnCount()) {
            return false;
        }
        changeSelection(sheetRow - 1, convertColumnIndexToView(column - 1), false, false);
        return true;
    }

    /**
     * The numbers to put in the corner above the row gutter, so the two header rows are numbered
     * like the data rows below them.
     */
    public javax.swing.JComponent corner(int headerHeight) {
        javax.swing.JPanel corner = new javax.swing.JPanel(new java.awt.BorderLayout());
        javax.swing.JPanel strut = new javax.swing.JPanel();
        strut.setPreferredSize(new java.awt.Dimension(52, headerHeight));
        strut.setBackground(new Color(0xE8, 0xE8, 0xEC));
        corner.add(strut, java.awt.BorderLayout.NORTH);
        javax.swing.JPanel numbers =
                new javax.swing.JPanel(new java.awt.GridLayout(2, 1, 0, 0));
        for (String each : new String[] {"1", "2"}) {
            JLabel number = new JLabel(each, JLabel.RIGHT);
            number.setOpaque(true);
            number.setBackground(new Color(0xE8, 0xE8, 0xEC));
            number.setForeground(new Color(0x60, 0x60, 0x68));
            number.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 1,
                            new Color(0xC8, 0xC8, 0xC8)),
                    javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 5)));
            number.setFont(number.getFont().deriveFont(java.awt.Font.PLAIN,
                    Math.max(10f, number.getFont().getSize() - 1f)));
            numbers.add(number);
        }
        numbers.setPreferredSize(new java.awt.Dimension(52, getRowHeight() * 2));
        corner.add(numbers, java.awt.BorderLayout.CENTER);
        return corner;
    }
}
