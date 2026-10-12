package de.fizkarlsruhe.ise.ontoboard.views;

import de.fizkarlsruhe.ise.ontoboard.menu.TemplateAction;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.sheet.IriGenerator;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetFix;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetLocation;
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
import javax.swing.JTextField;
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
 * the typing happens, a reference to something that does not exist can be made to exist
 * without going anywhere, and a new row gets an identifier out of the project's own allocated
 * block rather than out of somebody's head.
 *
 * <p>It has a workspace tab of its own from 1.117.0 - see {@link SheetsTab} - because for five
 * releases it was a third of a window and a sheet is twenty-six columns wide.
 *
 * <p>Thin on purpose. Every action here is one call into a tested class - {@code SheetBook}
 * for the edits and the search, {@code SheetAudit} for the marks, {@code SheetFix} for the
 * corrections, {@code TermCreation} for the new row, {@code IriGenerator} for the identifiers,
 * {@code SheetProvenance} for who did it - so what is left is wiring, a file chooser and some
 * buttons. That is the half a test cannot reach, and keeping it small is what makes the rest of
 * it testable at all.
 *
 * <p>Nothing is written until <i>Save</i>. These files are in git and somebody else is editing
 * them.
 */
public class SheetEditorView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private final JTabbedPane sheets = new JTabbedPane();
    private final JLabel status = new JLabel(" ");
    private final JPanel fixes = new JPanel();
    private final JTextField search = new JTextField(16);
    private final Map<String, SheetTableModel> models =
            new LinkedHashMap<String, SheetTableModel>();
    private final Map<String, SheetHeaderTable> headers =
            new LinkedHashMap<String, SheetHeaderTable>();

    /**
     * The source the dialog was last told to use, so the right-click mint agrees with it.
     *
     * <p>Not a saved preference: a choice remembered across sessions would quietly outlive the
     * sheet it was made for, and the right answer is per sheet - the live MatWerk tabs want a
     * timestamp and the hand-written MWO templates want the ID ranges.
     */
    private IriGenerator.Source lastChosen;

    private SheetBook book = SheetBook.empty();
    private File folder;

    /** Where the last search landed, so the next press moves on rather than finding it again. */
    private SheetBook.Match lastFound;

    /**
     * Builds the view, and says so.
     *
     * <p>The report is the point, and the reasoning is {@code SchemaCanvasView}'s: Protege's
     * {@code View.createContent} catches whatever {@code initialise()} throws and puts "An
     * error occurred whilst creating the view" in its place, so from outside this method a view
     * that died looks exactly like one that worked. That is how 1.73.0 shipped with the canvas
     * crashing on every open and a receipt recording PASS 10/10. {@link ViewHealth} is read by
     * the self-test immediately after it opens the tab, and from 1.113.0 that check requires
     * every view the tab's own layout asks for rather than merely one of them.
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
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar(), BorderLayout.NORTH);
        north.add(dataBar(), BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(sheets, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        fixes.setLayout(new javax.swing.BoxLayout(fixes, javax.swing.BoxLayout.X_AXIS));
        status.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(fixes, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                new java.awt.Color(0xC8, 0xC8, 0xC8)));
        add(bottom, BorderLayout.SOUTH);

        openTheProjectsOwnTemplates();
    }

    /**
     * Opens the templates of whatever ontology is already loaded, without being asked.
     *
     * <p>A project has one obvious place for them and the editor knows where it is, so
     * beginning with an empty grid and a file chooser puts a question to somebody whose answer
     * was already determined. If the folder is there with sheets in it, they are open before
     * anybody clicks anything; if it is empty or absent, the status line says where it would
     * be rather than making them guess.
     *
     * <p>Nothing is created here. An editor that puts a directory into a repository just by
     * being opened would be a surprise in somebody's next {@code git status}.
     */
    private void openTheProjectsOwnTemplates() {
        File ontologyFile = ontologyFileOrNull();
        File templates = SheetLocation.forOntologyFile(ontologyFile);
        if (templates == null) {
            say("Open a folder of .tsv or .csv templates to begin. In an ODK project that is "
                    + "usually src/templates.");
            return;
        }
        folder = templates;
        if (SheetLocation.sheetsIn(templates) == 0) {
            say("No templates yet. They belong in " + templates.getAbsolutePath()
                    + " - write one with ROBOT > Template... and it will open here, or use "
                    + "Open folder... for somewhere else.");
            return;
        }
        open(SheetBook.open(templates).against(ontologyOrNull(), "the open ontology"));
    }

    /** Where the open ontology was loaded from, or null. */
    private File ontologyFileOrNull() {
        OWLOntology ontology = ontologyOrNull();
        if (ontology == null) {
            return null;
        }
        try {
            org.semanticweb.owlapi.model.IRI document = getOWLModelManager()
                    .getOWLOntologyManager().getOntologyDocumentIRI(ontology);
            return document == null || !"file".equals(document.getScheme()) ? null
                    : new File(document.toURI());
        } catch (RuntimeException notAFile) {
            return null;
        }
    }

    @Override
    protected void disposeOWLView() {
        // Nothing is registered with the model manager, so there is nothing to unregister.
        // Unsaved edits are deliberately not written here: closing a view is not consent.
    }

    /** The file row: where the sheets come from and where they go. */
    private JComponent toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("Project templates", "Open this project's own src/templates",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        openTheProjectsOwnTemplates();
                    }
                }));
        bar.add(button("Open folder...", "Read every .tsv and .csv in some other folder",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        openFolder();
                    }
                }));
        bar.add(button("New sheet...", "Add another template to this folder",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        newSheet();
                    }
                }));
        bar.add(button("Import Excel...", "Read an .xlsx workbook and add each of its tabs to "
                + "this folder as a template", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        importWorkbook();
                    }
                }));
        bar.addSeparator();
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
        bar.add(button("Check", "Look again for mistakes in every sheet",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        recheck();
                    }
                }));
        return bar;
    }

    /**
     * The data row: the operations on rows, and the search.
     *
     * <p>Two rows rather than one because there are now eleven of these and a single toolbar
     * wrapped - a wrapped toolbar hides whichever buttons happen to fall off the end, which for
     * five releases was every one past "Fill down".
     */
    private JComponent dataBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
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
        bar.add(button("Add column", "A new column, with its template row left empty until "
                + "you say what it does", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        addColumn();
                    }
                }));
        bar.addSeparator();
        bar.add(button("Generate IRIs...", "Mint identifiers for the rows that have none, out "
                + "of this project's own allocated block", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        generateIdentifiers();
                    }
                }));
        bar.add(button("Record who", "Add the provenance columns and stamp every row",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        stamp();
                    }
                }));
        bar.addSeparator();
        bar.add(new JLabel(" Find "));
        search.setToolTipText("Any text, in any sheet. Enter finds the next one and wraps "
                + "round.");
        search.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                findNext();
            }
        });
        bar.add(search);
        bar.add(button("Next", "The next cell containing that text, in any sheet",
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        findNext();
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
        lastFound = null;
        models.clear();
        sheets.removeAll();
        headers.clear();
        for (SheetBook.Sheet sheet : book.getSheets()) {
            final String name = sheet.getName();
            SheetTableModel model = new SheetTableModel(book, name);
            models.put(name, model);
            final SheetTable table = new SheetTable(model);
            table.addMouseListener(new RightClick(table, model));
            table.autofitColumns();
            JScrollPane scrolled = new JScrollPane(table);
            // Row numbers down the left, as a spreadsheet has: every message about these sheets
            // names a row, and until 1.117.0 the grid showed none, so finding the row a message
            // named meant counting.
            scrolled.setRowHeaderView(table.rowNumbers());

            // The two header rows, editable, where the column header goes. The real
            // JTableHeader stays above them - it is what resizes columns - and now carries the
            // spreadsheet column letter, because the heading it used to paint is in the row
            // immediately below and is editable there.
            final SheetHeaderTable header = new SheetHeaderTable(book, name, table);
            headers.put(name, header);
            JPanel columnHeader = new JPanel(new BorderLayout());
            columnHeader.add(table.getTableHeader(), BorderLayout.NORTH);
            columnHeader.add(header, BorderLayout.CENTER);
            table.setCompositeColumnHeader(columnHeader);
            scrolled.setColumnHeaderView(columnHeader);
            scrolled.setCorner(JScrollPane.UPPER_LEFT_CORNER,
                    header.corner(table.getTableHeader().getPreferredSize().height));
            header.getModel().addTableModelListener(
                    new javax.swing.event.TableModelListener() {
                        @Override
                        public void tableChanged(javax.swing.event.TableModelEvent event) {
                            headerEdited(name, table, header);
                        }
                    });
            sheets.addTab(name, scrolled);
        }
        if (book.size() == 0) {
            say("No .tsv or .csv files there, so there is nothing to edit.");
            return;
        }
        recheck();
    }

    /**
     * Adds another template to the folder that is open, and opens it with the others.
     *
     * <p>A knowledge graph is many sheets - the one this was built against is twenty-six - so
     * adding the second is not an unusual thing to want. The new sheet gets the same starter
     * columns {@code ROBOT > Template...} writes, with identifiers taken from the project's
     * own id ranges, because a new sheet full of examples that produce nothing is how the old
     * starter wasted people's time.
     */
    private void newSheet() {
        if (folder == null) {
            say("Open a folder first, so there is somewhere to put it.");
            return;
        }
        String name = JOptionPane.showInputDialog(this,
                wrap("What should the sheet be called? It becomes a .tsv in "
                        + folder.getAbsolutePath() + "."), "terms");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        File created = SheetLocation.create(folder);
        if (created == null) {
            say("Could not create " + folder.getAbsolutePath());
            return;
        }
        File file = SheetLocation.freeNameIn(created, name.trim().replaceAll("[^A-Za-z0-9_-]",
                "-"));
        if (file == null) {
            say("Could not find a free name for it.");
            return;
        }
        String[] ids = TemplateAction.starterIdentifiersFor(ontologyOrNull(),
                ontologyFileOrNull());
        try {
            java.nio.file.Files.write(file.toPath(),
                    de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet
                            .starterWithIris(ids[0], ids[1], ids[2])
                            .getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } catch (IOException cannotWrite) {
            say("Could not write " + file.getName() + ": " + cannotWrite.getMessage());
            return;
        }
        open(SheetBook.open(folder).against(ontologyOrNull(), "the open ontology"));
        for (int at = 0; at < sheets.getTabCount(); at++) {
            if (sheets.getTitleAt(at).equals(stemOf(file.getName()))) {
                sheets.setSelectedIndex(at);
            }
        }
        say("Wrote " + file.getName() + " with three example rows to replace. Its identifiers "
                + "continue this project's own numbering.");
    }

    /**
     * Reads an Excel workbook and adds each of its tabs to this folder as a template.
     *
     * <p>Converted, not opened in place. {@link de.fizkarlsruhe.ise.ontoboard.sheet.Workbook}
     * reads the format and deliberately does not write it, so a workbook left open here could
     * never be saved - and TSV is what ROBOT reads and what git can show a diff of anyway.
     * Said plainly in the result, because somebody who imported a workbook will go looking for
     * it again.
     */
    private void importWorkbook() {
        if (folder == null) {
            say("Open a folder first, so there is somewhere to put the tabs.");
            return;
        }
        JFileChooser chooser = new JFileChooser(folder);
        chooser.setDialogTitle("An Excel workbook");
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory()
                        || file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx");
            }

            @Override
            public String getDescription() {
                return "Excel workbooks (*.xlsx)";
            }
        });
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File workbook = chooser.getSelectedFile();
        String refusal = de.fizkarlsruhe.ise.ontoboard.sheet.Workbook.whyNot(workbook);
        if (!refusal.isEmpty()) {
            // Which is usually not a corrupt file: it is a tab-separated file named .xlsx, and
            // saying so is the difference between a five-minute fix and an afternoon.
            JOptionPane.showMessageDialog(this, wrap(refusal), "Not a workbook",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<File> written;
        try {
            written = de.fizkarlsruhe.ise.ontoboard.sheet.Workbook.importInto(workbook, folder);
        } catch (IOException cannotRead) {
            JOptionPane.showMessageDialog(this, wrap(cannotRead.getMessage()),
                    "Could not import it", JOptionPane.WARNING_MESSAGE);
            return;
        }
        open(SheetBook.open(folder).against(ontologyOrNull(), "the open ontology"));
        StringBuilder names = new StringBuilder();
        for (File each : written) {
            names.append(names.length() == 0 ? "" : ", ").append(each.getName());
        }
        say("Imported " + written.size() + (written.size() == 1 ? " tab" : " tabs")
                + " from " + workbook.getName() + " as " + names + ". They are tab-separated "
                + "files now, which is what the build reads - the workbook itself is not "
                + "changed and is not written back to.");
    }

    /**
     * What has to be redone when a header cell changes.
     *
     * <p>The model layer needs nothing - {@code SheetBook.setCell} already takes rows 1 and 2,
     * and {@code Sheet.getColumns()} re-parses the template row on every call, with no cache
     * anywhere. What goes stale is Swing: {@code SheetTable.configureColumns} binds each
     * column's cell editor and width from the spec <em>once</em>, so a column changed from
     * {@code A rdfs:label} to {@code I RO:0001025} keeps a plain text field and offers no
     * completion until this runs.
     *
     * <p>And a structure change only when the column COUNT moved, which typing into a cell past
     * the end of the sheet does - {@code getColumnCount()} is the width of the widest row,
     * header rows included. Firing it unconditionally would throw away the column widths and the
     * renderers on every keystroke.
     */
    private void headerEdited(String name, SheetTable table, SheetHeaderTable header) {
        SheetTableModel model = models.get(name);
        if (model == null) {
            return;
        }
        if (model.getColumnCount() != table.getColumnCount()) {
            model.fireTableStructureChanged();
            header.refreshStructure();
        }
        table.configureColumns();
        header.refresh();
        recheck();
        String wrong = header.summary();
        if (!wrong.isEmpty()) {
            say(wrong + "  Hover the marked cell for what ROBOT would say.");
        }
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
        int waiting = identifiersWanted();
        say(book.size() + " sheets, " + book.index().size() + " names. "
                + all.size() + (all.size() == 1 ? " finding" : " findings")
                + ", " + correctable + " with an obvious correction."
                + (waiting == 0 ? "" : "  " + waiting + (waiting == 1 ? " row needs"
                        : " rows need") + " an identifier.")
                + (book.isUnsaved() ? "  Unsaved changes." : ""));
    }

    /** How many rows in the open sheet have content but no identifier. */
    private int identifiersWanted() {
        SheetTable table = selectedTableOrNull();
        return table == null ? 0 : IriGenerator.rowsNeedingAnIdentifier(book,
                ((SheetTableModel) table.getModel()).getSheetName()).size();
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

    // ---------------------------------------------------------------- identifiers

    /**
     * Mints identifiers for the rows that have none.
     *
     * <p>Every source is offered with a real example of what it would produce, because the
     * choice is not a preference - it decides what the identifiers of this graph look like for
     * ever, and the shapes are not interchangeable. The example is the value that will actually
     * be written: each generator fixes its instant and its UUIDs when it is built, so the
     * preview and the write agree.
     *
     * <p>A source that cannot be used is still listed, greyed, with the reason - "no ID range is
     * allocated to you" has a remedy one menu item away, and hiding it would hide the remedy.
     */
    private void generateIdentifiers() {
        SheetTable table = selectedTable();
        if (table == null) {
            return;
        }
        final String sheetName = ((SheetTableModel) table.getModel()).getSheetName();
        List<Integer> waiting = IriGenerator.rowsNeedingAnIdentifier(book, sheetName);
        if (waiting.isEmpty()) {
            say("Every row in " + sheetName + " that has anything in it already has an "
                    + "identifier.");
            return;
        }

        final Map<IriGenerator.Source, IriGenerator> offered =
                new LinkedHashMap<IriGenerator.Source, IriGenerator>();
        String editor = IriGenerator.editorName();
        for (IriGenerator.Source source : IriGenerator.Source.values()) {
            offered.put(source, IriGenerator.using(source, book, ontologyOrNull(),
                    ontologyFileOrNull(), editor, sheetName, IriGenerator.Moment.now()));
        }
        boolean anyUsable = false;
        for (IriGenerator each : offered.values()) {
            anyUsable |= each.canMint();
        }
        if (!anyUsable) {
            StringBuilder why = new StringBuilder("Nothing can be minted for this sheet.\n");
            for (Map.Entry<IriGenerator.Source, IriGenerator> each : offered.entrySet()) {
                why.append("\n").append(each.getKey().getLabel()).append(": ")
                        .append(each.getValue().getRefusal());
            }
            JOptionPane.showMessageDialog(this, wrap(why.toString()), "No identifiers to give",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        IriGenerator.Source preferred = lastChosen != null && offered.get(lastChosen).canMint()
                ? lastChosen
                : IriGenerator.preferredFor(book, sheetName, ontologyFileOrNull());
        if (!offered.get(preferred).canMint()) {
            for (Map.Entry<IriGenerator.Source, IriGenerator> each : offered.entrySet()) {
                if (each.getValue().canMint()) {
                    preferred = each.getKey();
                    break;
                }
            }
        }

        final javax.swing.JComboBox<IriGenerator.Source> choice =
                new javax.swing.JComboBox<IriGenerator.Source>();
        for (IriGenerator.Source source : IriGenerator.Source.values()) {
            choice.addItem(source);
        }
        choice.setSelectedItem(preferred);
        choice.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                java.awt.Component painted = super.getListCellRendererComponent(list, value,
                        index, selected, focused);
                IriGenerator.Source source = (IriGenerator.Source) value;
                setText(source.getLabel());
                setEnabled(offered.get(source).canMint());
                return painted;
            }
        });

        final JLabel preview = new JLabel();
        preview.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        choice.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                IriGenerator.Source source = (IriGenerator.Source) choice.getSelectedItem();
                preview.setText(wrap(offered.get(source).describe()));
            }
        });
        preview.setText(wrap(offered.get(preferred).describe()));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(wrap(waiting.size() + (waiting.size() == 1 ? " row in " : " rows in ")
                + sheetName + " have content but no identifier. Nothing is written to the file "
                + "until you save.")), BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout());
        middle.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        middle.add(choice, BorderLayout.NORTH);
        middle.add(preview, BorderLayout.CENTER);
        panel.add(middle, BorderLayout.CENTER);

        if (JOptionPane.showConfirmDialog(this, panel, "Generate identifiers",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        IriGenerator.Source chosen = (IriGenerator.Source) choice.getSelectedItem();
        IriGenerator minting = offered.get(chosen);
        if (!minting.canMint()) {
            JOptionPane.showMessageDialog(this, wrap(minting.getRefusal()),
                    "That one cannot be used here", JOptionPane.WARNING_MESSAGE);
            return;
        }
        lastChosen = chosen;
        int filled;
        try {
            filled = minting.fillBlanks(sheetName);
        } catch (RuntimeException cannotMint) {
            JOptionPane.showMessageDialog(this, wrap(cannotMint.getMessage()),
                    "Nothing was written", JOptionPane.WARNING_MESSAGE);
            return;
        }
        SheetTableModel model = models.get(sheetName);
        if (model != null) {
            model.fireTableDataChanged();
        }
        recheck();
        say(filled + (filled == 1 ? " identifier" : " identifiers") + " minted in " + sheetName
                + " - " + chosen.getLabel().toLowerCase(java.util.Locale.ROOT)
                + ". Undo reverses them one at a time; nothing is written until you save.");
    }

    /**
     * Mints one identifier into one cell, from the right-click menu.
     *
     * <p>Using whatever the dialog was last told to use, so the two ways of minting into one
     * sheet do not quietly disagree about its shape.
     */
    private void generateOneIdentifier(String sheetName, int sheetRow, int column) {
        IriGenerator minting = lastChosen == null
                ? IriGenerator.forSheet(book, sheetName, ontologyOrNull(), ontologyFileOrNull(),
                        IriGenerator.editorName())
                : IriGenerator.using(lastChosen, book, ontologyOrNull(), ontologyFileOrNull(),
                        IriGenerator.editorName(), sheetName, IriGenerator.Moment.now());
        if (!minting.canMint()) {
            JOptionPane.showMessageDialog(this, wrap(minting.getRefusal()),
                    "No identifier to give", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String minted;
        try {
            minted = minting.getSource().needsALabel()
                    ? minting.mintFor(java.util.Collections.singletonList(
                            minting.labelIn(sheetName, sheetRow))).get(0)
                    : minting.mint(1).get(0);
        } catch (RuntimeException cannotMint) {
            JOptionPane.showMessageDialog(this, wrap(cannotMint.getMessage()),
                    "No identifier to give", JOptionPane.WARNING_MESSAGE);
            return;
        }
        book.setCell(sheetName, sheetRow, column, minted);
        SheetTableModel model = models.get(sheetName);
        if (model != null) {
            model.fireTableDataChanged();
        }
        recheck();
        say("Row " + sheetRow + " of " + sheetName + " is now " + minted + ".");
    }


    // ---------------------------------------------------------------- searching

    /**
     * The next cell containing what is in the search box, in any sheet.
     *
     * <p>Across the book, because a graph spread over twenty-six files is one body of data and
     * "which sheet is this in" is the question. Wrapping, so repeated presses walk every match.
     */
    private void findNext() {
        String wanted = search.getText();
        if (wanted == null || wanted.trim().isEmpty()) {
            for (SheetTableModel model : models.values()) {
                SheetTable table = tableFor(model.getSheetName());
                if (table != null) {
                    table.clearFound();
                }
            }
            say("Type something to find.");
            return;
        }
        SheetBook.Match found = lastFound == null
                ? book.findNext(wanted, null, 0, 0)
                : book.findNext(wanted, lastFound.getSheet(), lastFound.getRow(),
                        lastFound.getColumn());
        if (found == null) {
            lastFound = null;
            say("Nothing in any sheet contains \"" + wanted.trim() + "\".");
            return;
        }
        lastFound = found;
        for (int at = 0; at < sheets.getTabCount(); at++) {
            if (sheets.getTitleAt(at).equals(found.getSheet())) {
                sheets.setSelectedIndex(at);
            }
        }
        SheetTable table = tableFor(found.getSheet());
        int total = book.countMatches(wanted);
        if (found.getRow() <= 2) {
            // A match in a heading or a template row. Those are editable from 1.118.0, so the
            // search goes to them rather than apologising for not being able to.
            SheetHeaderTable header = headers.get(found.getSheet());
            if (header != null && header.goTo(found.getRow(), found.getColumn())) {
                say("Found in " + found.getSheet() + ", row " + found.getRow()
                        + " - the " + (found.getRow() == 1 ? "heading" : "template")
                        + " row. " + total + (total == 1 ? " match in all." : " matches in "
                                + "all."));
                return;
            }
        }
        if (table == null || !table.goTo(found.getRow(), found.getColumn())) {
            say("Found in " + found.getSheet() + " at row " + found.getRow() + ", column "
                    + found.getColumn() + ", which is not in the grid. " + total + " in all.");
            return;
        }
        say("Found in " + found.getSheet() + ", row " + found.getRow() + ". " + total
                + (total == 1 ? " match in all." : " matches in all.  Press Next for the "
                        + "following one."));
    }

    private SheetTable tableFor(String sheetName) {
        for (int at = 0; at < sheets.getTabCount(); at++) {
            if (!sheets.getTitleAt(at).equals(sheetName)) {
                continue;
            }
            Component chosen = sheets.getComponentAt(at);
            if (chosen instanceof JScrollPane) {
                Component inside = ((JScrollPane) chosen).getViewport().getView();
                if (inside instanceof SheetTable) {
                    return (SheetTable) inside;
                }
            }
        }
        return null;
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

    /**
     * Adds a column at the end, named but not yet doing anything.
     *
     * <p>Its template cell is left empty on purpose: an empty template string is ROBOT's own way
     * of saying "ignore this column", so a new column changes nothing about what the sheet
     * builds until somebody says what it is for. Inventing a spec would be inventing axioms.
     *
     * <p>Only the heading is written, and {@code width()} is the widest row, so that one cell is
     * what makes the column exist.
     */
    private void addColumn() {
        SheetTable table = selectedTable();
        if (table == null) {
            return;
        }
        String sheetName = ((SheetTableModel) table.getModel()).getSheetName();
        int next = book.width(sheetName) + 1;
        String name = JOptionPane.showInputDialog(this,
                wrap("What should column " + SheetHeaderTable.letterFor(next - 1)
                        + " be called? That is the heading in row 1 - ROBOT needs every column "
                        + "with a template string to have one."), "New column");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        book.setCell(sheetName, 1, next, name.trim());
        SheetTableModel model = models.get(sheetName);
        if (model != null) {
            model.fireTableStructureChanged();
        }
        SheetHeaderTable header = headers.get(sheetName);
        if (header != null) {
            header.refreshStructure();
        }
        table.configureColumns();
        recheck();
        say("Column " + SheetHeaderTable.letterFor(next - 1) + " added as \"" + name.trim()
                + "\". Its template row is empty, so the build ignores it - click the cell "
                + "under the heading and choose what the column should do.");
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
            final int sheetRow = model.sheetRowOf(row);
            final String sheetName = model.getSheetName();
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
                    sheetName, sheetRow, modelColumn + 1,
                    IriGenerator.forSheet(book, sheetName, ontologyOrNull(),
                            ontologyFileOrNull(), IriGenerator.editorName()))) {
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
            // Minting into the cell that is open, which is the other half of "a new row needs an
            // identifier": Generate IRIs... fills a whole column, and this is for the one row
            // somebody is typing.
            if (model.columnAt(modelColumn + 1) != null
                    && model.columnAt(modelColumn + 1).getKind()
                            == de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns.Kind.ID) {
                menu.add(item("Mint an identifier here", new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        generateOneIdentifier(sheetName, sheetRow, modelColumn + 1);
                    }
                }));
            }
            menu.addSeparator();
            menu.add(item("Insert a row above this one", new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    book.insertRow(sheetName, sheetRow);
                    model.fireTableDataChanged();
                    recheck();
                }
            }));
            menu.add(item("Duplicate this row", new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    book.duplicateRow(sheetName, sheetRow);
                    model.fireTableDataChanged();
                    recheck();
                }
            }));
            menu.add(item("Delete this row", new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    if (confirm("Delete row " + sheetRow + " of " + sheetName + "?")) {
                        book.deleteRow(sheetName, sheetRow);
                        model.fireTableDataChanged();
                        recheck();
                    }
                }
            }));
            final String value = book.cell(sheetName, sheetRow, modelColumn + 1);
            if (!value.trim().isEmpty()) {
                menu.addSeparator();
                menu.add(item("Find \"" + shorten(value) + "\" everywhere",
                        new ActionListener() {
                            @Override
                            public void actionPerformed(ActionEvent event) {
                                search.setText(value.trim());
                                lastFound = null;
                                findNext();
                            }
                        }));
            }
            if (finding != null) {
                menu.addSeparator();
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

    /** A cell value short enough for a menu entry. */
    private static String shorten(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 37) + "...";
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
                + " at row " + row + " as " + proposal.getIri()
                + ". Nothing is written until you save.");
    }

    // ---------------------------------------------------------------- odds and ends

    /** The grid in front, or null, having said why. */
    private SheetTable selectedTable() {
        SheetTable found = selectedTableOrNull();
        if (found == null) {
            say("Open a folder of templates first.");
        }
        return found;
    }

    /** The grid in front, or null, saying nothing. */
    private SheetTable selectedTableOrNull() {
        Component chosen = sheets.getSelectedComponent();
        if (!(chosen instanceof JScrollPane)) {
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
                + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
                + "</body></html>";
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
