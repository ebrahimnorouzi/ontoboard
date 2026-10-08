package de.fizkarlsruhe.ise.ontoboard.views;

import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetFix;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetProvenance;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetTableModel;
import de.fizkarlsruhe.ise.ontoboard.sheet.TermCreation;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A folder of spreadsheets, open for editing, inside Prot&eacute;g&eacute;.
 *
 * <p>The surface over everything in {@code sheet/}. A knowledge graph built from ROBOT
 * templates is edited in a spreadsheet program today, which means the names a cell has to
 * match live in another window, nothing says which of them are wrong until a build runs, and
 * creating the thing a cell refers to means leaving the sheet entirely. This closes that loop:
 * the names come from every open sheet and from the ontology, the mistakes are marked where
 * the typing happens, and a reference to something that does not exist can be made to exist
 * without going anywhere.
 *
 * <p>Thin on purpose. Every action here is one call into a tested class - {@code SheetBook}
 * for the edits, {@code SheetAudit} for the marks, {@code SheetFix} for the corrections,
 * {@code TermCreation} for the new row, {@code SheetProvenance} for who did it - so what is
 * left is wiring, a file chooser and some buttons. That is the half a test cannot reach, and
 * keeping it small is what makes the rest of it testable at all.
 *
 * <p>Nothing is written until <i>Save</i>. These files are in git and somebody else is editing
 * them.
 */
public class SheetEditorView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private final JTabbedPane sheets = new JTabbedPane();
    private final JLabel status = new JLabel(" ");
    private final JPanel fixes = new JPanel();
    private final Map<String, SheetTableModel> models =
            new LinkedHashMap<String, SheetTableModel>();

    private SheetBook book = SheetBook.empty();
    private File folder;

    /**
     * Builds the view, and says so.
     *
     * <p>The report is the point, and the reasoning is {@code SchemaCanvasView}'s: Protege's
     * {@code View.createContent} catches whatever {@code initialise()} throws and puts "An
     * error occurred whilst creating the view" in its place, so from outside this method a view
     * that died looks exactly like one that worked. That is how 1.73.0 shipped with the canvas
     * crashing on every open and a receipt recording PASS 10/10. {@link ViewHealth} is read by
     * the self-test immediately after it opens the tab, and from 1.113.0 that check requires
     * every view plugin.xml registers rather than merely one of them - without which this view,
     * sitting behind the canvas in a tabbed group, would never have been constructed by it at
     * all.
     *
     * <p>The throwable is re-thrown unchanged. Protege's handling of it is right, and a view
     * that swallowed its own construction failure would be a worse lie than the one this fixes.
     */
    @Override
    protected void initialiseOWLView() {
        try {
            buildView();
        } catch (RuntimeException | Error broke) {
            ViewHealth.failed(getClass().getSimpleName(), broke);
            throw broke;
        }
        ViewHealth.constructed(getClass().getSimpleName());
    }

    private void buildView() {
        setLayout(new BorderLayout());
        add(toolbar(), BorderLayout.NORTH);
        add(sheets, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        fixes.setLayout(new javax.swing.BoxLayout(fixes, javax.swing.BoxLayout.X_AXIS));
        status.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(fixes, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                new java.awt.Color(0xC8, 0xC8, 0xC8)));
        add(bottom, BorderLayout.SOUTH);

        say("Open a folder of .tsv or .csv templates to begin. In an ODK project that is "
                + "usually src/templates.");
    }

    @Override
    protected void disposeOWLView() {
        // Nothing is registered with the model manager, so there is nothing to unregister.
        // Unsaved edits are deliberately not written here: closing a view is not consent.
    }

    private JComponent toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("Open folder...", "Read every .tsv and .csv in a folder",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        openFolder();
                    }
                }));
        bar.add(button("Save", "Write this sheet back to its file", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                save(false);
            }
        }));
        bar.add(button("Save all", "Write back every sheet that has changed",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        save(true);
                    }
                }));
        bar.add(button("Undo", "Reverse the last change", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                undo();
            }
        }));
        bar.addSeparator();
        bar.add(button("Add row", "A new empty row at the end", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                withSheet(ADD_ROW);
            }
        }));
        bar.add(button("Duplicate row", "A copy of the selected row, below it",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        withSheet(DUPLICATE);
                    }
                }));
        bar.add(button("Delete row", "Remove the selected row", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                withSheet(DELETE);
            }
        }));
        bar.add(button("Fill down", "Copy the top cell of the selection down, counting up "
                + "where it ends in a number", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        withSheet(FILL_DOWN);
                    }
                }));
        bar.addSeparator();
        bar.add(button("Check", "Look again for mistakes in every sheet",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        recheck();
                    }
                }));
        bar.add(button("Record who", "Add the provenance columns and stamp every row",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        stamp();
                    }
                }));
        return bar;
    }

    private static JMenuItem item(String text, ActionListener action) {
        JMenuItem entry = new JMenuItem(text);
        entry.addActionListener(action);
        return entry;
    }

    private static JButton button(String text, String tip, ActionListener action) {
        JButton button = new JButton(text);
        button.setToolTipText(tip);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }

    // ---------------------------------------------------------------- opening

    private void openFolder() {
        JFileChooser chooser = new JFileChooser(folder);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("A folder of ROBOT templates");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        if (book.isUnsaved() && !confirm("Some sheets have unsaved changes. Open another "
                + "folder and lose them?")) {
            return;
        }
        folder = chooser.getSelectedFile();
        open(SheetBook.open(folder).against(ontologyOrNull(), "the open ontology"));
    }

    /** Package-visible so a test can open a book without a file chooser. */
    void open(SheetBook opened) {
        book = opened;
        models.clear();
        sheets.removeAll();
        for (SheetBook.Sheet sheet : book.getSheets()) {
            SheetTableModel model = new SheetTableModel(book, sheet.getName());
            models.put(sheet.getName(), model);
            SheetTable table = new SheetTable(model);
            table.addMouseListener(new RightClick(table, model));
            sheets.addTab(sheet.getName(), new JScrollPane(table));
        }
        if (book.size() == 0) {
            say("No .tsv or .csv files there, so there is nothing to edit.");
            return;
        }
        recheck();
    }

    private OWLOntology ontologyOrNull() {
        try {
            return getOWLModelManager().getActiveOntology();
        } catch (RuntimeException noOntology) {
            return null;
        }
    }

    // ---------------------------------------------------------------- checking and fixing

    private void recheck() {
        if (book.size() == 0) {
            return;
        }
        for (SheetTableModel model : models.values()) {
            model.refreshFindings();
        }
        List<SheetAudit.Finding> all = book.auditAll();
        Map<SheetAudit.Kind, Integer> fixable = SheetFix.fixableByKind(all);

        fixes.removeAll();
        for (final Map.Entry<SheetAudit.Kind, Integer> each : fixable.entrySet()) {
            fixes.add(button("Fix " + each.getValue() + " " + readable(each.getKey()),
                    "Apply the correction to every one of them, in one step you can undo",
                    new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent event) {
                            applyKind(each.getKey());
                        }
                    }));
        }
        fixes.revalidate();
        fixes.repaint();

        int correctable = 0;
        for (Integer count : fixable.values()) {
            correctable += count;
        }
        say(book.size() + " sheets, " + book.index().size() + " names. "
                + all.size() + (all.size() == 1 ? " finding" : " findings")
                + ", " + correctable + " with an obvious correction."
                + (book.isUnsaved() ? "  Unsaved changes." : ""));
    }

    private void applyKind(SheetAudit.Kind kind) {
        SheetFix.Outcome outcome = SheetFix.applyKind(book, book.auditAll(), kind);
        recheck();
        say(outcome.describe() + "  Undo reverses them one at a time.");
    }

    private static String readable(SheetAudit.Kind kind) {
        switch (kind) {
            case WHITESPACE:
                return "whitespace";
            case TYPOGRAPHIC_CHARACTER:
                return "curly quotes";
            case UNRESOLVED_REFERENCE:
                return "near-miss names";
            case QUOTED_REFERENCE:
                return "quoted names";
            default:
                return kind.name().toLowerCase().replace('_', ' ');
        }
    }

    // ---------------------------------------------------------------- editing

    private static final int ADD_ROW = 0;
    private static final int DUPLICATE = 1;
    private static final int DELETE = 2;
    private static final int FILL_DOWN = 3;

    private void withSheet(int what) {
        SheetTable table = selectedTable();
        if (table == null) {
            return;
        }
        SheetTableModel model = (SheetTableModel) table.getModel();
        int row = table.getSelectedRow();
        switch (what) {
            case ADD_ROW:
                int added = model.addRow();
                table.changeSelection(added, 0, false, false);
                break;
            case DUPLICATE:
                if (row >= 0) {
                    model.duplicateRow(row);
                }
                break;
            case DELETE:
                if (row >= 0 && confirm("Delete row " + model.sheetRowOf(row) + "?")) {
                    model.deleteRow(row);
                }
                break;
            case FILL_DOWN:
                int[] rows = table.getSelectedRows();
                int column = table.getSelectedColumn();
                if (rows.length > 1 && column >= 0) {
                    int changed = model.fillDown(table.convertColumnIndexToModel(column),
                            rows[0], rows[rows.length - 1]);
                    say(changed + (changed == 1 ? " cell" : " cells") + " filled.");
                } else {
                    say("Select the cell to copy and the ones below it, then fill down.");
                }
                break;
            default:
                break;
        }
        recheck();
    }

    private void undo() {
        String where = book.undo();
        if (where.isEmpty()) {
            say("Nothing to undo.");
            return;
        }
        SheetTableModel model = models.get(where);
        if (model != null) {
            model.fireTableDataChanged();
        }
        recheck();
        say("Undone, in " + where + ".");
    }

    private void save(boolean everything) {
        SheetTable table = selectedTable();
        List<String> written = new ArrayList<String>();
        try {
            for (SheetBook.Sheet sheet : book.getSheets()) {
                boolean wanted = everything
                        || (table != null && ((SheetTableModel) table.getModel())
                                .getSheetName().equals(sheet.getName()));
                if (wanted && sheet.isUnsaved()) {
                    book.save(sheet.getName());
                    written.add(sheet.getName());
                }
            }
        } catch (IOException cannotWrite) {
            say("Could not write: " + cannotWrite.getMessage());
            return;
        }
        say(written.isEmpty() ? "Nothing had changed."
                : "Wrote " + written.size() + ": " + String.join(", ", written));
    }

    // ---------------------------------------------------------------- provenance

    private void stamp() {
        SheetTable table = selectedTable();
        if (table == null) {
            return;
        }
        String name = ((SheetTableModel) table.getModel()).getSheetName();
        ProvenanceSettings settings = ProvenanceSettings.load();
        String agent = settings.canonicalAgent();
        if (agent == null || agent.trim().isEmpty()) {
            say("Nobody to record. Set who you are in OntoBoard > Provenance... first.");
            return;
        }
        int added = SheetProvenance.ensureColumns(book, name, settings.hasOrcid());
        int stamped = SheetProvenance.stampEveryRow(book, name, agent, Provenance.today());
        SheetTable rebuilt = selectedTable();
        if (rebuilt != null) {
            ((SheetTableModel) rebuilt.getModel()).fireTableStructureChanged();
            rebuilt.configureColumns();
        }
        recheck();
        say((added > 0 ? added + " columns added. " : "")
                + stamped + (stamped == 1 ? " row" : " rows") + " recorded as "
                + agent + " on " + Provenance.today() + ". Nothing is written until you save.");
    }

    // ---------------------------------------------------------------- create what it refers to

    /** Offers to create the thing a cell refers to, when it refers to nothing. */
    private final class RightClick extends MouseAdapter {
        private final SheetTable table;
        private final SheetTableModel model;

        RightClick(SheetTable table, SheetTableModel model) {
            this.table = table;
            this.model = model;
        }

        @Override
        public void mousePressed(MouseEvent event) {
            maybe(event);
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            maybe(event);
        }

        private void maybe(MouseEvent event) {
            if (!event.isPopupTrigger()) {
                return;
            }
            int row = table.rowAtPoint(event.getPoint());
            int column = table.columnAtPoint(event.getPoint());
            if (row < 0 || column < 0) {
                return;
            }
            table.changeSelection(row, column, false, false);
            final int modelColumn = table.convertColumnIndexToModel(column);
            JPopupMenu menu = new JPopupMenu();

            final SheetAudit.Finding finding = model.findingAt(row, modelColumn);
            if (finding != null && finding.isFixable()) {
                menu.add(item("Change to \"" + finding.getSuggestion() + "\"",
                        new ActionListener() {
                            @Override
                            public void actionPerformed(ActionEvent event) {
                                SheetFix.apply(book, finding);
                                recheck();
                            }
                        }));
            }
            for (final TermCreation.Proposal proposal : TermCreation.proposeAll(book,
                    model.getSheetName(), model.sheetRowOf(row), modelColumn + 1)) {
                if (!proposal.isPossible()) {
                    continue;
                }
                menu.add(item("Create \"" + proposal.getLabel() + "\" in "
                        + proposal.getTargetSheet() + "...", new ActionListener() {
                            @Override
                            public void actionPerformed(ActionEvent event) {
                                create(proposal);
                            }
                        }));
            }
            if (finding != null) {
                menu.add(item("Why is this marked?", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        JOptionPane.showMessageDialog(SheetEditorView.this,
                                wrap(finding.getMessage()), finding.where(),
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                }));
            }
            if (menu.getComponentCount() > 0) {
                menu.show(table, event.getX(), event.getY());
            }
        }
    }

    private void create(TermCreation.Proposal proposal) {
        if (!confirm(proposal.describe())) {
            return;
        }
        int row = TermCreation.create(book, proposal);
        if (row == 0) {
            say("Nothing was added.");
            return;
        }
        SheetTableModel target = models.get(proposal.getTargetSheet());
        if (target != null) {
            target.fireTableDataChanged();
        }
        recheck();
        say("Added \"" + proposal.getLabel() + "\" to " + proposal.getTargetSheet()
                + " at row " + row + ". Nothing is written until you save.");
    }

    // ---------------------------------------------------------------- odds and ends

    private SheetTable selectedTable() {
        Component chosen = sheets.getSelectedComponent();
        if (!(chosen instanceof JScrollPane)) {
            say("Open a folder of templates first.");
            return null;
        }
        Component inside = ((JScrollPane) chosen).getViewport().getView();
        return inside instanceof SheetTable ? (SheetTable) inside : null;
    }

    private boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(this, wrap(question), "OntoBoard",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private static String wrap(String text) {
        return "<html><body style='width:420px'>"
                + text.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>";
    }

    private void say(String text) {
        status.setText(text);
    }

    /** What the status line says, so a test can read it without a screen. */
    String statusText() {
        return status.getText();
    }

    /** How many sheets are open, for the same reason. */
    int openSheets() {
        return sheets.getTabCount();
    }
}
