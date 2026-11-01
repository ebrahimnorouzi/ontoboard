package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermSearch;
import de.fizkarlsruhe.ise.ontoboard.robot.WebOntology;
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
import javax.swing.JOptionPane;
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
import org.semanticweb.owlapi.model.IRI;
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
    private javax.swing.JComboBox<String> where;
    private JButton load;
    /** The load under way, which the Load button - then saying Stop - can end; null when none. */
    private WebOntology.Attempt loading;
    private javax.swing.Timer ticking;
    private volatile String stage = "";
    private volatile long stageSince;
    /** Set when opened from Imported terms to add to one import: its name, and what adds. */
    private String targetImport;
    private AddToImport addToImport;
    private String startWith;
    private final Map<String, String> suggested = new LinkedHashMap<String, String>();
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

    /** What adds the chosen terms to one import, for Find terms opened from Imported terms. */
    public interface AddToImport {
        /**
         * @param labels the chosen terms, each with its label
         * @param source the ontology they were found in, already loaded
         * @param sourceText where it was loaded from, as typed
         */
        void add(Map<IRI, String> labels, OWLOntology source, String sourceText);
    }

    /**
     * Find terms opened to add to one import: its source loaded straight away, and the chosen
     * terms handed to {@code adder} instead of to Import terms.
     */
    public static void openToAddTo(org.protege.editor.owl.OWLEditorKit kit, String source,
            String importName, AddToImport adder) {
        FindTermsAction finder = new FindTermsAction();
        finder.setEditorKit(kit);
        finder.targetImport = importName;
        finder.addToImport = adder;
        finder.startWith = source;
        finder.actionPerformed(null);
    }

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
                targetImport == null ? "Find terms in another ontology"
                        : "Add terms to " + targetImport, java.awt.Dialog.ModalityType.MODELESS);

        JLabel intro = new JLabel(targetImport == null
                ? "<html><b>Find the terms you need by what they are called.</b> "
                        + "Load an ontology from its web address or a file, search its labels, "
                        + "tick the terms you want and import them. Each comes with what it "
                        + "needs, as a module of your project - there are no IRIs to look up."
                        + "</html>"
                : "<html><b>Find the terms to add to " + escape(targetImport) + ".</b> Search "
                        + "its source's labels and tick the terms you want; adding them rebuilds "
                        + "the import with them at once. Terms it has already are marked.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        suggested.clear();
        suggested.putAll(suggestions());
        where = new javax.swing.JComboBox<String>(suggested.keySet().toArray(new String[0]));
        where.setEditable(true);
        where.setSelectedItem(startWith == null ? "" : startWith);
        where.setPrototypeDisplayValue("https://example.org/a-longer-address/of/an/ontology.owl");
        where.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                String address = String.valueOf(value);
                String what = suggested.get(address);
                return super.getListCellRendererComponent(list, what == null || index < 0
                        ? address : "<html>" + escape(address) + " &nbsp;<font color=gray>"
                                + escape(what) + "</font></html>", index, selected, focused);
            }
        });
        where.setToolTipText("<html>A web address - https://... - or the path of an ontology "
                + "file.<br>The list has this project's imports and some widely used "
                + "ontologies.<br>The address of a page about an ontology works too: its link "
                + "to the ontology's file is followed.</html>");
        ((javax.swing.JTextField) where.getEditor().getEditorComponent())
                .addActionListener(e -> loadIt());
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> browse());
        load = new JButton("Load");
        load.addActionListener(e -> loadIt());
        JPanel source = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        source.add(new JLabel("Ontology:"));
        source.add(where);
        source.add(browse);
        source.add(load);

        status = new JLabel(targetImport == null ? "Nothing loaded yet."
                : "Loading " + startWith + "...");
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

        importButton = new JButton(targetImport == null ? "Import the chosen terms..."
                : "Add the chosen terms to " + targetImport);
        importButton.setEnabled(false);
        importButton.addActionListener(e -> importChosen());
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            if (loading != null) {
                loading.stop();
            }
            dialog.dispose();
        });
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
        if (startWith != null && !startWith.trim().isEmpty()) {
            loadIt();
        }
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
            where.setSelectedItem(chooser.getSelectedFile().getAbsolutePath());
            loadIt();
        }
    }

    private String whereText() {
        Object typed = where.getEditor().getItem();
        return typed == null ? "" : typed.toString().trim();
    }

    /**
     * Sources to offer: where this ontology's imports came from, then a few ontologies many
     * others reuse. Each with a few words on what it is.
     */
    private Map<String, String> suggestions() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        OWLOntology yours = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        if (yours != null) {
            for (OWLOntology part : yours.getImportsClosure()) {
                if (part.equals(yours)) {
                    continue;
                }
                IRI from = de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance.sourceOf(part);
                if (from == null && part.getOntologyID().getOntologyIRI().isPresent()
                        && WebOntology.isWebAddress(part.getOntologyID().getOntologyIRI().get())) {
                    from = part.getOntologyID().getOntologyIRI().get();
                }
                if (from != null && WebOntology.isWebAddress(from)) {
                    sources.put(from.toString(), "already imported by your ontology");
                }
            }
        }
        sources.putIfAbsent("http://purl.obolibrary.org/obo/bfo/2020/bfo.owl",
                "BFO 2020 - a top-level ontology many others build on");
        sources.putIfAbsent("http://purl.obolibrary.org/obo/ro.owl",
                "RO - relations such as part of and participates in");
        sources.putIfAbsent("http://purl.obolibrary.org/obo/iao.owl",
                "IAO - documents, data and identifiers");
        sources.putIfAbsent("http://www.w3.org/ns/prov-o", "PROV-O - provenance");
        sources.putIfAbsent("http://www.w3.org/2006/time", "OWL-Time - instants and intervals");
        sources.putIfAbsent("http://www.w3.org/2004/02/skos/core",
                "SKOS - concepts and vocabularies");
        return sources;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** The current step of a load and how long it has taken, every second while it runs. */
    private void showStage() {
        WebOntology.Attempt running = loading;
        if (running == null) {
            return;
        }
        long seconds = (System.currentTimeMillis() - stageSince) / 1000;
        String what = stage;
        if (what.startsWith("Waiting for")) {
            status.setText(what + " " + seconds + " s - OntoBoard stops waiting after "
                    + running.getSilentSeconds() + " s; Stop ends it now.");
        } else {
            status.setText(what + (seconds >= 2 ? " " + seconds + " s" : ""));
        }
    }

    /**
     * Loads the ontology and indexes its terms, off the event thread - or, while a load is under
     * way and the button says Stop, stops it.
     *
     * <p>A server that accepts the connection and then sends nothing used to hold this at
     * "Loading..." for minutes, with no way out; {@link WebOntology} now gives up after
     * {@link WebOntology#SILENT_SECONDS}, and Stop ends it at once.
     */
    private void loadIt() {
        if (loading != null) {
            loading.stop();
            status.setText("Stopping...");
            return;
        }
        final String text = whereText();
        if (text.isEmpty()) {
            status.setText("Give the ontology's web address, or the path of its file.");
            return;
        }
        final WebOntology.Attempt attempt = new WebOntology.Attempt();
        loading = attempt;
        load.setText("Stop");
        search.setEnabled(false);
        missingImports.setVisible(false);
        stage = "Loading " + text + "...";
        stageSince = System.currentTimeMillis();
        status.setText(stage);
        ticking = new javax.swing.Timer(1000, e -> showStage());
        ticking.start();
        final List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<String>();
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                OWLOntology ontology = OntologySource.loadLeavingOutMissingImports(
                        OWLManager.createOWLOntologyManager(), OntologySource.toIri(text),
                        attempt, what -> {
                            stage = what;
                            stageSince = System.currentTimeMillis();
                        }, notes);
                stage = "Indexing its terms...";
                stageSince = System.currentTimeMillis();
                return new Object[] {ontology, TermSearch.of(ontology),
                    OntologySource.describeMissingImports(
                            OntologySource.missingImportsOf(ontology))};
            }

            @Override
            protected void done() {
                ticking.stop();
                loading = null;
                load.setText("Load");
                try {
                    Object[] result = get();
                    loaded = (OWLOntology) result[0];
                    index = (TermSearch) result[1];
                    List<String> said = new ArrayList<String>(notes);
                    if (result[2] != null) {
                        said.add(result[2] + ".");
                    }
                    StringBuilder shown = new StringBuilder("<html>");
                    for (String note : said) {
                        shown.append(shown.length() > 6 ? "<br>" : "").append(escape(note));
                    }
                    missingImports.setText(shown.append("</html>").toString());
                    missingImports.setVisible(!said.isEmpty());
                    loadedFrom = text;
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                    status.setText("<html>Could not load it. " + escape(cause.getMessage())
                            + "</html>");
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
        String counted = chosen.size() + " term" + (chosen.size() == 1 ? "" : "s");
        importButton.setText(targetImport != null
                ? (chosen.isEmpty() ? "Add the chosen terms to " + targetImport
                        : "Add " + counted + " to " + targetImport)
                : chosen.isEmpty() ? "Import the chosen terms..." : "Import " + counted + "...");
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
    /**
     * Adds the chosen terms. Opened from Imported terms, to that import at once; otherwise after
     * one confirmation that names the files it writes - with the choice of method and place
     * behind "Advanced...", where Import terms' own dialog has them.
     */
    private void importChosen() {
        if (chosen.isEmpty() || loaded == null) {
            return;
        }
        if (addToImport != null) {
            Map<IRI, String> labels = new LinkedHashMap<IRI, String>();
            for (TermSearch.Term term : chosen.values()) {
                labels.put(IRI.create(term.getIri()), term.getLabel());
            }
            dialog.dispose();
            addToImport.add(labels, loaded, loadedFrom);
            return;
        }
        String[] options = {"Add them", "Advanced...", "Cancel"};
        int answer = JOptionPane.showOptionDialog(dialog, whatAddingWrites(), "Add "
                + chosen.size() + (chosen.size() == 1 ? " term" : " terms"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options,
                options[0]);
        if (answer != 0 && answer != 1) {
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
        if (answer == 0) {
            importer.withoutAsking();
        }
        importer.actionPerformed(null);
    }

    /** What "Add them" will do, file by file, for the confirmation. */
    private String whatAddingWrites() {
        String name = de.fizkarlsruhe.ise.ontoboard.robot.TermExtract.shortNameOf(loaded);
        OWLOntology yours = getOWLModelManager().getActiveOntology();
        java.io.File editFile = null;
        try {
            java.net.URI document = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(yours).toURI();
            editFile = "file".equalsIgnoreCase(document.getScheme())
                    ? new java.io.File(document) : null;
        } catch (RuntimeException notAFile) {
            editFile = null;
        }
        StringBuilder text = new StringBuilder("Add " + chosen.size()
                + (chosen.size() == 1 ? " term" : " terms") + " from " + name
                + " to your ontology.\n\n");
        if (editFile == null) {
            return text.append("Your ontology has not been saved yet, so there is nowhere to put "
                    + "the import. Save it first (File > Save as...), then add the terms.")
                    .toString();
        }
        java.io.File imports = new java.io.File(editFile.getParentFile(), "imports");
        java.io.File list = new java.io.File(imports, name + "_terms.txt");
        text.append("OntoBoard will:\n")
                .append("  - cut them out of ").append(loadedFrom).append(", with the terms "
                        + "they need, into\n      ")
                .append(new java.io.File(imports, name + "_import.owl").getPath()).append('\n')
                .append("  - list them in ").append(list.getName()).append(", so the import can "
                        + "be rebuilt and changed later\n      (OntoBoard > Imported terms...)\n")
                .append("  - import that module into your ontology, with a catalog entry "
                        + "beside it\n");
        if (list.isFile()) {
            text.append("\n").append(name).append(" is imported already: these join the terms "
                    + "it has, and none of those is taken out.\n");
        }
        return text.append("\nAdvanced... lets you choose how much comes with them, and where "
                + "the module goes.").toString();
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
