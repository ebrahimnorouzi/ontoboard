package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermSearch;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * OntoBoard &gt; Find terms in another ontology... - search an ontology by its labels, tick the terms
 * you want, and import them.
 *
 * <p>Asked for: "what if the user doesn't know the terms? Load the ontology from a URL, search
 * through the labels, select the terms they want, and they should be added - user friendly and
 * easy to use." Import terms asked for IRIs, which is to say it asked people to have found the
 * terms somewhere else first.
 *
 * <p><b>This finds and chooses; Import terms imports.</b> The chosen terms are handed to
 * {@code ROBOT &gt; Import terms...} with the ontology already loaded, as the pattern library hands
 * over a pattern: it extracts the module, writes the term list, adds the import and the catalog
 * entry, and has the guards each of those steps needed.
 */
public class FindTermsAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    /** More than anyone reads, few enough that typing stays instant on a large ontology. */
    static final int SHOWN = 500;

    private JDialog dialog;
    private JTextField where;
    private JButton load;
    private JLabel status;
    private JTextField search;
    private JCheckBox obsolete;
    private FoundModel found;
    private final Map<String, TermSearch.Term> chosen =
            new LinkedHashMap<String, TermSearch.Term>();
    private final DefaultListModel<String> chosenList = new DefaultListModel<String>();
    private JButton importButton;
    private TermSearch index;
    private OWLOntology loaded;
    private String loadedFrom;
    /** Which of the loaded ontology's imports could not be fetched; hidden when none. */
    private JLabel missingImports;
    private Set<String> alreadyYours = new java.util.HashSet<String>();

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        alreadyYours = new java.util.HashSet<String>();
        OWLOntology yours = getOWLModelManager().getActiveOntology();
        if (yours != null) {
            for (org.semanticweb.owlapi.model.OWLEntity entity
                    : yours.getSignature(Imports.INCLUDED)) {
                alreadyYours.add(entity.getIRI().toString());
            }
        }
        show();
    }

    private void show() {
        dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(getOWLWorkspace()),
                "Find terms in another ontology", java.awt.Dialog.ModalityType.MODELESS);

        JLabel intro = new JLabel("<html><b>Find the terms you need by what they are called.</b> "
                + "Load an ontology from its web address or a file, search its labels, tick the "
                + "terms you want and import them. Each comes with what it needs, as a module of "
                + "your project - there are no IRIs to look up.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        where = new JTextField(46);
        where.setToolTipText("A web address - https://... - or the path of an ontology file");
        where.addActionListener(e -> loadIt());
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> browse());
        load = new JButton("Load");
        load.addActionListener(e -> loadIt());
        JPanel source = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        source.add(new JLabel("Ontology:"));
        source.add(where);
        source.add(browse);
        source.add(load);

        status = new JLabel("Nothing loaded yet.");
        missingImports = new JLabel();
        missingImports.setForeground(new java.awt.Color(0xA0, 0x4A, 0x00));
        missingImports.setVisible(false);
        status.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));

        search = new JTextField(30);
        search.setEnabled(false);
        search.setToolTipText("Part of a label, a synonym, or the end of an IRI");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        obsolete = new JCheckBox("Show obsolete terms");
        obsolete.addActionListener(e -> refilter());
        JPanel finder = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        finder.add(new JLabel("Search:"));
        finder.add(search);
        finder.add(obsolete);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JPanel row : new JPanel[] {wrap(intro), source, wrap(status),
            wrap(missingImports), finder}) {
            row.setAlignmentX(0f);
            top.add(row);
        }

        found = new FoundModel();
        JTable table = new JTable(found) {
            /** The whole IRI, which the table shortens. */
            @Override
            public String getToolTipText(java.awt.event.MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                return row < 0 ? null : found.termAt(convertRowIndexToModel(row)).getIri();
            }
        };
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(34);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(140);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);

        JList<String> picked = new JList<String>(chosenList);
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            for (String label : picked.getSelectedValuesList()) {
                String iri = iriOfChosen(label);
                if (iri != null) {
                    chosen.remove(iri);
                }
            }
            chosenChanged();
        });
        JPanel side = new JPanel(new BorderLayout(0, 4));
        side.add(new JLabel("Chosen"), BorderLayout.NORTH);
        side.add(new JScrollPane(picked), BorderLayout.CENTER);
        side.add(remove, BorderLayout.SOUTH);
        side.setPreferredSize(new Dimension(240, 300));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table),
                side);
        split.setResizeWeight(0.8);

        importButton = new JButton("Import the chosen terms...");
        importButton.setEnabled(false);
        importButton.addActionListener(e -> importChosen());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(importButton);
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(1180, 680);
        dialog.setLocationRelativeTo(getOWLWorkspace());
        dialog.setVisible(true);
        where.requestFocusInWindow();
    }

    private static JPanel wrap(java.awt.Component component) {
        JPanel row = new JPanel(new BorderLayout());
        row.add(component, BorderLayout.CENTER);
        return row;
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("An ontology to find terms in");
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            where.setText(chooser.getSelectedFile().getAbsolutePath());
            loadIt();
        }
    }

    /** Loads the ontology and indexes its terms, off the event thread. */
    private void loadIt() {
        final String text = where.getText().trim();
        if (text.isEmpty()) {
            status.setText("Give the ontology's web address, or the path of its file.");
            return;
        }
        load.setEnabled(false);
        search.setEnabled(false);
        status.setText("Loading " + text + " - a large ontology can take a minute...");
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                OWLOntology ontology = OntologySource.loadLeavingOutMissingImports(
                        OWLManager.createOWLOntologyManager(), OntologySource.toIri(text));
                return new Object[] {ontology, TermSearch.of(ontology),
                    OntologySource.describeMissingImports(
                            OntologySource.missingImportsOf(ontology))};
            }

            @Override
            protected void done() {
                load.setEnabled(true);
                try {
                    Object[] result = get();
                    loaded = (OWLOntology) result[0];
                    index = (TermSearch) result[1];
                    missingImports.setText(result[2] == null ? "" : result[2] + ".");
                    missingImports.setVisible(result[2] != null);
                    loadedFrom = text;
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                    status.setText("Could not load it: " + cause.getMessage());
                    return;
                }
                chosen.clear();
                chosenChanged();
                search.setEnabled(true);
                search.setText("");
                refilter();
                search.requestFocusInWindow();
            }
        }.execute();
    }

    private void refilter() {
        if (index == null) {
            return;
        }
        String query = search.getText();
        List<TermSearch.Term> shown = index.find(query, SHOWN, obsolete.isSelected());
        found.show(shown);
        int total = index.count(query, obsolete.isSelected());
        String name = loaded.getOntologyID().getOntologyIRI().isPresent()
                ? loaded.getOntologyID().getOntologyIRI().get().toString() : loadedFrom;
        status.setText(index.size() + " terms in " + name + ". "
                + (query.trim().isEmpty() ? "Type to search their labels."
                        : total == 0 ? "Nothing is called that - try part of a word."
                        : total > shown.size() ? "Showing the best " + shown.size() + " of "
                                + total + " - type more to narrow it."
                        : total + " found."));
    }

    private void chosenChanged() {
        chosenList.clear();
        for (TermSearch.Term term : chosen.values()) {
            chosenList.addElement(chosenText(term));
        }
        importButton.setEnabled(!chosen.isEmpty());
        importButton.setText(chosen.isEmpty() ? "Import the chosen terms..."
                : "Import " + chosen.size() + " term" + (chosen.size() == 1 ? "" : "s") + "...");
        found.fireTableDataChanged();
    }

    private static String chosenText(TermSearch.Term term) {
        return term.getLabel() + "  (" + term.getShortIri() + ")";
    }

    private String iriOfChosen(String shown) {
        for (TermSearch.Term term : chosen.values()) {
            if (shown.equals(chosenText(term))) {
                return term.getIri();
            }
        }
        return null;
    }

    /** Hands the chosen terms to Import terms, with the ontology already loaded. */
    private void importChosen() {
        if (chosen.isEmpty() || loaded == null) {
            return;
        }
        StringBuilder terms = new StringBuilder();
        for (String iri : chosen.keySet()) {
            terms.append(iri).append('\n');
        }
        dialog.dispose();
        ImportTermsAction importer = new ImportTermsAction();
        importer.setEditorKit(getOWLEditorKit());
        try {
            importer.initialise();
        } catch (Exception cannot) {
            throw new IllegalStateException(cannot);
        }
        importer.seedWithLoaded(loadedFrom, terms.toString().trim(), loaded);
        importer.actionPerformed(null);
    }

    /** The terms found, with a box to tick each one. */
    private final class FoundModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] columns = {"", "Label", "Kind", "Term", "Definition"};
        private List<TermSearch.Term> rows = new ArrayList<TermSearch.Term>();

        void show(List<TermSearch.Term> shown) {
            rows = shown;
            fireTableDataChanged();
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
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 && !alreadyYours.contains(rows.get(row).getIri());
        }

        TermSearch.Term termAt(int row) {
            return rows.get(row);
        }

        @Override
        public Object getValueAt(int row, int column) {
            TermSearch.Term term = rows.get(row);
            switch (column) {
                case 0:
                    return chosen.containsKey(term.getIri());
                case 1:
                    return term.getLabel() + (term.isObsolete() ? " (obsolete)" : "")
                            + (alreadyYours.contains(term.getIri()) ? " - already yours" : "");
                case 2:
                    return term.getKind();
                case 3:
                    return term.getShortIri();
                default:
                    return term.getDefinition();
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            TermSearch.Term term = rows.get(row);
            if (Boolean.TRUE.equals(value)) {
                chosen.put(term.getIri(), term);
            } else {
                chosen.remove(term.getIri());
            }
            chosenChanged();
        }
    }
}
