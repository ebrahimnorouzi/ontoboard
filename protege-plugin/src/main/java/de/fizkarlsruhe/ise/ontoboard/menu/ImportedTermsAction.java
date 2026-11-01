package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportedTerms;
import de.fizkarlsruhe.ise.ontoboard.odk.ModuleRebuild;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import de.fizkarlsruhe.ise.ontoboard.robot.WebOntology;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * OntoBoard &gt; Imported terms... - which terms come from which ontology, with their labels,
 * and taking terms out of an import or adding more to it.
 *
 * <p>Asked for: "how can a user see which terms are imported from which ontologies? Is there a
 * way to add or remove terms imported before, by seeing the terms - with their labels - and
 * adding or deleting?" Before this, the only view was Term lists..., which showed one import's
 * list as IRIs, could add only a term already in the ontology - which a term not yet imported
 * never is - and left the module unchanged until Refresh imports was run as well.
 *
 * <p>Here each import is listed with every term it brings: chosen ones, which can be taken out,
 * and the ones that came along because a chosen term needs them. Adding opens Find terms on the
 * import's own source; adding or taking out rebuilds the module straight away and changes the
 * copy Prot&eacute;g&eacute; has open, so the change is visible at once and saved.
 */
public class ImportedTermsAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    private JDialog dialog;
    private final DefaultListModel<ImportedTerms.Source> sources =
            new DefaultListModel<ImportedTerms.Source>();
    private JList<ImportedTerms.Source> sourceList;
    private TermsModel terms;
    private JTable table;
    private TableRowSorter<TermsModel> sorter;
    private JTextField filter;
    private JLabel heading;
    private JLabel status;
    private JButton add;
    private JButton remove;
    private JButton rebuild;
    private JButton showIt;
    private JButton stop;
    private WebOntology.Attempt working;
    private File projectRoot;
    private File ontologyDirectory;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        OWLOntology ontology = active();
        if (ontology == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "Open an ontology first.",
                    "Imported terms", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File editFile = fileOf(ontology);
        ontologyDirectory = editFile == null ? null : editFile.getAbsoluteFile().getParentFile();
        projectRoot = editFile == null ? null : ReleaseAction.projectRootOf(editFile);
        show();
        refresh(null);
    }

    private OWLOntology active() {
        return getOWLModelManager() == null ? null : getOWLModelManager().getActiveOntology();
    }

    private File fileOf(OWLOntology ontology) {
        try {
            java.net.URI document = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(document.getScheme()) ? new File(document) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }

    private void show() {
        dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(getOWLWorkspace()),
                "Imported terms", java.awt.Dialog.ModalityType.MODELESS);

        JLabel intro = new JLabel("<html><b>Which terms your ontology takes from other "
                + "ontologies, and where from.</b> Choose an import on the left. <i>Chosen</i> "
                + "terms are on its list and can be taken out; <i>brought along</i> terms are "
                + "there because a chosen term needs them, and go when those go. Adding or "
                + "taking out terms rebuilds the import at once.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        sourceList = new JList<ImportedTerms.Source>(sources);
        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sourceList.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                ImportedTerms.Source source = (ImportedTerms.Source) value;
                String counts = source.getModule() == null
                        ? source.getTerms().size() + " terms, imported whole"
                        : source.countChosen() + " chosen, " + source.getTerms().size()
                                + " in all";
                return super.getListCellRendererComponent(list, "<html><b>"
                        + escape(source.getName()) + "</b><br><font color=gray>" + counts
                        + "</font></html>", index, selected, focused);
            }
        });
        sourceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        JScrollPane left = new JScrollPane(sourceList);
        left.setPreferredSize(new Dimension(250, 400));

        heading = new JLabel(" ");
        filter = new JTextField(24);
        filter.setToolTipText("Part of a label or of the term's address");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        search.add(new JLabel("Search:"));
        search.add(filter);

        terms = new TermsModel();
        table = new JTable(terms) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getToolTipText(java.awt.event.MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                if (row < 0) {
                    return null;
                }
                ImportedTerms.Term term = terms.at(convertRowIndexToModel(row));
                String tip = term.getIri().toString();
                if (!term.getNeededBy().isEmpty()) {
                    tip += " - brought along for " + String.join(", ", term.getNeededBy());
                }
                if (term.isSaidDifferently()) {
                    tip += " - " + String.join(", ", term.getAlsoIn()) + " gives it a different "
                            + "label or definition, so your ontology has both";
                }
                return tip;
            }
        };
        sorter = new TableRowSorter<TermsModel>(terms);
        table.setRowSorter(sorter);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(170);
        table.getColumnModel().getColumn(3).setPreferredWidth(170);
        table.getColumnModel().getColumn(4).setPreferredWidth(200);
        table.getColumnModel().getColumn(5).setPreferredWidth(80);
        table.getSelectionModel().addListSelectionListener(e -> enableButtons());

        add = new JButton("Add terms...");
        add.addActionListener(e -> addTerms());
        remove = new JButton("Take out of the import");
        remove.addActionListener(e -> removeSelected());
        rebuild = new JButton("Rebuild from the source");
        rebuild.setToolTipText("Cut the import again from its source - when the source has a new "
                + "release, say");
        rebuild.addActionListener(e -> rebuildSelected());
        showIt = new JButton("Show in Protege");
        showIt.addActionListener(e -> showInProtege());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(add);
        actions.add(remove);
        actions.add(rebuild);
        actions.add(showIt);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(heading, BorderLayout.NORTH);
        top.add(search, BorderLayout.SOUTH);
        right.add(top, BorderLayout.NORTH);
        right.add(new JScrollPane(table), BorderLayout.CENTER);
        right.add(actions, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.22);

        status = new JLabel(" ");
        stop = new JButton("Stop");
        stop.setVisible(false);
        stop.addActionListener(e -> {
            if (working != null) {
                working.stop();
            }
        });
        JButton findTerms = new JButton("Find terms in another ontology...");
        findTerms.addActionListener(e -> {
            FindTermsAction finder = new FindTermsAction();
            finder.setEditorKit(getOWLEditorKit());
            finder.actionPerformed(null);
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            if (working != null) {
                working.stop();
            }
            dialog.dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(stop);
        buttons.add(findTerms);
        buttons.add(close);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(intro, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(1120, 640);
        dialog.setLocationRelativeTo(getOWLWorkspace());
        dialog.setVisible(true);
    }

    /** Reads every import again, keeping the one that was selected selected. */
    private void refresh(String keep) {
        List<ImportedTerms.Source> found = ImportedTerms.of(active(), projectRoot,
                ontologyDirectory);
        sources.clear();
        int select = found.isEmpty() ? -1 : 0;
        for (int at = 0; at < found.size(); at++) {
            sources.addElement(found.get(at));
            if (found.get(at).getName().equals(keep)) {
                select = at;
            }
        }
        if (select >= 0) {
            sourceList.setSelectedIndex(select);
        } else {
            heading.setText("<html><b>Your ontology imports nothing yet.</b> Find terms in "
                    + "another ontology... finds terms by their labels and adds them as an "
                    + "import, which is then listed here.</html>");
            terms.show(new ArrayList<ImportedTerms.Term>());
        }
        showSelected();
    }

    private ImportedTerms.Source selected() {
        return sourceList == null ? null : sourceList.getSelectedValue();
    }

    private void showSelected() {
        ImportedTerms.Source source = selected();
        if (source == null) {
            enableButtons();
            return;
        }
        terms.show(source.getTerms());
        applyFilter();
        StringBuilder text = new StringBuilder("<html><b>").append(escape(source.getName()))
                .append("</b> - ");
        if (source.getModule() == null) {
            text.append("imported whole: ").append(source.getTerms().size()).append(" terms")
                    .append(source.getFrom() == null ? "" : " from " + escape(source.getFrom()))
                    .append(". There is no list to take one out of; to use only some of its "
                            + "terms, find them in it with Find terms instead.");
        } else {
            int chosen = source.countChosen();
            text.append(chosen).append(chosen == 1 ? " chosen term, " : " chosen terms, ")
                    .append(source.getTerms().size() - chosen).append(" brought along");
            if (source.getFrom() != null) {
                text.append(", from ").append(escape(source.getFrom()));
            }
            text.append(" (").append(source.getMethod().getLabel()).append(").");
            if (source.getPattern() != null) {
                text.append(" From OntoBoard's pattern library: it changes when the pattern is "
                        + "imported again (OntoBoard &gt; Reuse &gt; Pattern library...).");
            } else if (source.getFrom() == null) {
                text.append(" Its term list does not say where it comes from, so it cannot be "
                        + "rebuilt here.");
            } else if (source.getOntology() == null) {
                text.append(" Your ontology does not import it at the moment.");
            }
        }
        heading.setText(text.append("</html>").toString());
        add.setText(source.isEditable() ? "Add terms from " + source.getName() + "..."
                : "Add terms...");
        enableButtons();
    }

    private void applyFilter() {
        final String wanted = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        sorter.setRowFilter(wanted.isEmpty() ? null
                : new RowFilter<TermsModel, Integer>() {
                    @Override
                    public boolean include(Entry<? extends TermsModel, ? extends Integer> row) {
                        ImportedTerms.Term term = terms.at(row.getIdentifier());
                        return term.getLabel().toLowerCase(java.util.Locale.ROOT).contains(wanted)
                                || term.getIri().toString().toLowerCase(java.util.Locale.ROOT)
                                        .contains(wanted);
                    }
                });
    }

    private void enableButtons() {
        ImportedTerms.Source source = selected();
        boolean idle = working == null;
        boolean editable = source != null && source.isEditable();
        add.setEnabled(idle && editable);
        rebuild.setEnabled(idle && editable);
        remove.setEnabled(idle && editable && table.getSelectedRowCount() > 0);
        showIt.setEnabled(table.getSelectedRowCount() == 1);
    }

    private List<ImportedTerms.Term> selectedTerms() {
        List<ImportedTerms.Term> picked = new ArrayList<ImportedTerms.Term>();
        for (int row : table.getSelectedRows()) {
            picked.add(terms.at(table.convertRowIndexToModel(row)));
        }
        return picked;
    }

    /** Find terms, on this import's source, adding what is chosen to this import. */
    private void addTerms() {
        final ImportedTerms.Source source = selected();
        if (source == null || !source.isEditable()) {
            return;
        }
        FindTermsAction.openToAddTo(getOWLEditorKit(), source.getFrom(), source.getName(),
                (labels, upstream, sourceText) -> {
                    String before = read(source.getModule().getTermsFile());
                    String after = ImportedTerms.withTerms(before, labels);
                    int added = de.fizkarlsruhe.ise.ontoboard.odk.TermFile.termsIn(after).size()
                            - de.fizkarlsruhe.ise.ontoboard.odk.TermFile.termsIn(before).size();
                    if (added == 0) {
                        status.setText("Those terms are in " + source.getName() + " already.");
                        return;
                    }
                    change(source, before, after, upstream, "Added " + added
                            + (added == 1 ? " term to " : " terms to ") + source.getName() + ".");
                });
    }

    private void removeSelected() {
        ImportedTerms.Source source = selected();
        if (source == null || !source.isEditable()) {
            return;
        }
        Set<IRI> out = new LinkedHashSet<IRI>();
        List<String> kept = new ArrayList<String>();
        List<String> used = new ArrayList<String>();
        for (ImportedTerms.Term term : selectedTerms()) {
            if (term.isRemovable()) {
                out.add(term.getIri());
                if (term.getUsedByYou() > 0) {
                    used.add(term.getLabel() + " (" + term.getUsedByYou()
                            + (term.getUsedByYou() == 1 ? " axiom)" : " axioms)"));
                }
            } else {
                kept.add(term.getLabel() + (term.getNeededBy().isEmpty() ? ""
                        : " - needed by " + String.join(", ", term.getNeededBy())));
            }
        }
        if (out.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "These are in " + source.getName()
                    + " only because chosen terms need them, so they go when those are taken "
                    + "out:\n\n  " + String.join("\n  ", kept), "Brought along",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder message = new StringBuilder("Take " + out.size()
                + (out.size() == 1 ? " term" : " terms") + " out of " + source.getName()
                + "? The import is rebuilt without " + (out.size() == 1 ? "it" : "them")
                + " straight away.");
        if (!used.isEmpty()) {
            message.append("\n\nYour own axioms use:\n  ").append(String.join("\n  ", used))
                    .append("\nThose axioms stay. Unless something else brings the term in, it "
                            + "will have no label or parents in your ontology.");
        }
        if (!kept.isEmpty()) {
            message.append("\n\nNot taken out, because chosen terms need them:\n  ")
                    .append(String.join("\n  ", kept));
        }
        if (JOptionPane.showConfirmDialog(dialog, message.toString(), "Take terms out",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        String before = read(source.getModule().getTermsFile());
        String after = ImportedTerms.withoutTerms(before, out);
        if (de.fizkarlsruhe.ise.ontoboard.odk.TermFile.termsIn(after).isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "That would leave " + source.getName()
                    + " with no terms at all. To stop importing it, remove the import "
                    + "(Active ontology tab > Imported ontologies) and delete its files under "
                    + ImportModules.IMPORTS_DIRECTORY + ".", "Nothing would be left",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        change(source, before, after, null, "Took " + out.size()
                + (out.size() == 1 ? " term" : " terms") + " out of " + source.getName() + ".");
    }

    private void rebuildSelected() {
        ImportedTerms.Source source = selected();
        if (source == null || !source.isEditable()) {
            return;
        }
        String list = read(source.getModule().getTermsFile());
        change(source, list, list, null, "Rebuilt " + source.getName() + " from "
                + source.getFrom() + ".");
    }

    /**
     * Writes the new term list, cuts the module again and makes the open copy match it - off the
     * event thread for the download and the extraction, on it for the change to the model.
     */
    private void change(final ImportedTerms.Source source, final String listBefore,
            final String listAfter, final OWLOntology alreadyLoaded, final String said) {
        final File list = source.getModule().getTermsFile();
        final File moduleFile = source.getModule().getModuleFile();
        final WebOntology.Attempt attempt = new WebOntology.Attempt();
        final List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<String>();
        working = attempt;
        stop.setVisible(true);
        enableButtons();
        status.setText("Rebuilding " + source.getName() + "...");
        new SwingWorker<TermExtract.Result, String>() {
            @Override
            protected TermExtract.Result doInBackground() throws Exception {
                if (!listAfter.equals(listBefore)) {
                    writeIfUnchanged(list, listBefore, listAfter);
                }
                List<IRI> wanted = ImportModules.readTerms(list);
                OWLOntology upstream = alreadyLoaded != null ? alreadyLoaded
                        : ModuleRebuild.upstream(projectRoot, source.getFrom(), attempt,
                                what -> publish(what), notes);
                IRI moduleIri = source.getOntology() != null
                        && source.getOntology().getOntologyID().getOntologyIRI().isPresent()
                        ? source.getOntology().getOntologyID().getOntologyIRI().get()
                        : ModuleRebuild.declaredIriOf(moduleFile);
                if (moduleIri == null) {
                    OWLOntology yours = active();
                    moduleIri = TermExtract.moduleIriIn(
                            yours.getOntologyID().getOntologyIRI().isPresent()
                                    ? yours.getOntologyID().getOntologyIRI().get() : null,
                            upstream);
                }
                publish("Cutting " + wanted.size() + " terms out of " + source.getFrom() + "...");
                return ModuleRebuild.extract(upstream, wanted, source.getMethod(), moduleIri,
                        Provenance.today());
            }

            @Override
            protected void process(List<String> steps) {
                status.setText(steps.get(steps.size() - 1));
            }

            @Override
            protected void done() {
                working = null;
                stop.setVisible(false);
                TermExtract.Result rebuilt;
                try {
                    rebuilt = get();
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                    status.setText("<html>Nothing was rebuilt: " + escape(cause.getMessage())
                            + (listAfter.equals(listBefore) ? ""
                                    : " The term list was saved; Rebuild from the source tries "
                                            + "again.") + "</html>");
                    refresh(source.getName());
                    return;
                }
                String outcome;
                try {
                    outcome = install(rebuilt.getModule(), moduleFile);
                } catch (Exception cannotSave) {
                    status.setText("<html>The module was rebuilt but could not be saved: "
                            + escape(cannotSave.getMessage()) + "</html>");
                    refresh(source.getName());
                    return;
                }
                List<String> told = new ArrayList<String>();
                told.add(said + " " + outcome);
                for (IRI missing : rebuilt.getMissing()) {
                    told.add(missing + " is not in " + source.getFrom() + " any more.");
                }
                told.addAll(notes);
                status.setText("<html>" + escape(String.join(" ", told)) + "</html>");
                refresh(source.getName());
            }
        }.execute();
    }

    /**
     * Puts a rebuilt module in place: into the copy Prot&eacute;g&eacute; has open, through the
     * model manager so the hierarchy shows it at once, and saved; or straight to its file when it
     * is not open.
     */
    private String install(OWLOntology rebuilt, File moduleFile) throws Exception {
        OWLOntology open = ModuleRebuild.openFrom(active(), moduleFile);
        if (open == null) {
            rebuilt.getOWLOntologyManager().saveOntology(rebuilt, IRI.create(moduleFile.toURI()));
            return "Saved " + moduleFile.getName() + ".";
        }
        List<OWLOntologyChange> changes = ModuleRebuild.changesToMatch(open, rebuilt);
        if (changes.isEmpty()) {
            return "The module was already up to date.";
        }
        getOWLModelManager().applyChanges(changes);
        getOWLModelManager().save(open);
        return "The module now has " + ImportedTerms.termsOf(open).size()
                + " terms, and is saved.";
    }

    private void showInProtege() {
        List<ImportedTerms.Term> picked = selectedTerms();
        if (picked.size() != 1) {
            return;
        }
        for (OWLEntity entity : active().getEntitiesInSignature(picked.get(0).getIri(),
                Imports.INCLUDED)) {
            getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(entity);
            status.setText("Selected " + picked.get(0).getLabel() + " in Protege.");
            return;
        }
        status.setText(picked.get(0).getLabel() + " is not in your ontology's imports yet - "
                + "rebuild the import to bring it in.");
    }

    /**
     * Writes a term list, refusing when it changed since it was read here - a text editor, a git
     * pull - so that change is not overwritten with text built from the older one.
     */
    private static void writeIfUnchanged(File file, String expected, String updated)
            throws IOException {
        String now = file.isFile() ? new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8) : "";
        if (!now.equals(expected)) {
            throw new RobotException(file.getName() + " changed while this was open - in a text "
                    + "editor, or by a git pull - so it was left as it is. Close this window and "
                    + "open it again to see the new list.");
        }
        File temporary = File.createTempFile(file.getName(), ".tmp",
                file.getAbsoluteFile().getParentFile());
        Files.write(temporary.toPath(), updated.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private static String read(File file) {
        try {
            return file != null && file.isFile() ? new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8) : "";
        } catch (IOException unreadable) {
            throw new RobotException("Could not read " + file + ": " + unreadable.getMessage(),
                    unreadable);
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** One import's terms: label, kind, short name, how it came in and how much you use it. */
    private static final class TermsModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] columns = {"Label", "Kind", "Term", "How it came in",
            "Also in", "Used by you"};
        private List<ImportedTerms.Term> rows = new ArrayList<ImportedTerms.Term>();

        void show(List<ImportedTerms.Term> shown) {
            rows = new ArrayList<ImportedTerms.Term>(shown);
            fireTableDataChanged();
        }

        ImportedTerms.Term at(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 5 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ImportedTerms.Term term = rows.get(row);
            switch (column) {
                case 0:
                    return term.getLabel();
                case 1:
                    return term.getKind();
                case 2:
                    return shortForm(term.getIri());
                case 3:
                    return term.getHow().getWords() + (term.getNeededBy().isEmpty() ? ""
                            : " (for " + String.join(", ", term.getNeededBy()) + ")");
                case 4:
                    return String.join(", ", term.getAlsoIn()) + (term.isSaidDifferently()
                            ? " - worded differently there" : "");
                default:
                    return term.getUsedByYou();
            }
        }

        private static String shortForm(IRI iri) {
            String text = iri.toString();
            int cut = Math.max(text.lastIndexOf('#'), text.lastIndexOf('/'));
            return cut >= 0 && cut < text.length() - 1 ? text.substring(cut + 1) : text;
        }
    }

    /** Opens Imported terms from elsewhere - the board, the guide - on the open ontology. */
    public static void open(org.protege.editor.owl.OWLEditorKit kit) {
        ImportedTermsAction action = new ImportedTermsAction();
        action.setEditorKit(kit);
        action.actionPerformed(null);
    }
}
