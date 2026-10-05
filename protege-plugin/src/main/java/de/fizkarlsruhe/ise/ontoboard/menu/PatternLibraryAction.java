package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternLibrary;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternOrder;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternSummary;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.IRI;

/**
 * OntoBoard &gt; ROBOT &gt; Pattern library... - the 123 design patterns shipped in the plugin.
 *
 * <p>Asked for: "I want to have a pattern repository to import ODPs to the ontology, and have
 * these patterns, separated by the source, and uses ODK to import terms from the pattern." The
 * patterns were already in the repository and reachable from nothing at all.
 *
 * <p><b>This browses and chooses; it does not import.</b> Choosing hands the pattern and its
 * terms to {@code ROBOT &gt; Import terms...}, which already extracts the module, writes the
 * term list, saves the module, adds the import and adds the catalog entry - five steps with four
 * guards between them, each written after the corresponding mistake. A second implementation
 * here would be a second set of those mistakes.
 */
public class PatternLibraryAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    /** Where a pattern is copied so that ROBOT can be given a file IRI for it. */
    static final String MIRROR = "src/ontology/mirror";

    private JList<DesignPattern> patterns;
    private JList<IRI> terms;
    private JEditorPane details;
    private JTextField search;
    private JComboBox<String> grouping;
    private JButton importButton;
    private JDialog dialog;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        List<DesignPattern> all = PatternLibrary.all();
        if (all.isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "The pattern library did not load. It is packaged inside the plugin, so this "
                            + "means the jar is incomplete rather than that anything is missing "
                            + "from your project.",
                    "No patterns", JOptionPane.WARNING_MESSAGE);
            return;
        }
        show(all);
    }

    private void show(List<DesignPattern> all) {
        dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(getOWLWorkspace()),
                "Pattern library", JDialog.ModalityType.APPLICATION_MODAL);

        patterns = new JList<DesignPattern>(new DefaultListModel<DesignPattern>());
        patterns.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        patterns.setCellRenderer(new PatternRenderer());
        patterns.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent event) {
                if (!event.getValueIsAdjusting()) {
                    showSelected();
                }
            }
        });

        terms = new JList<IRI>(new DefaultListModel<IRI>());
        terms.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        details = new JEditorPane("text/html", "");
        details.setEditable(false);
        details.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        search = new JTextField();
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refill();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refill();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refill();
            }
        });

        grouping = new JComboBox<String>(new String[] {PatternOrder.BY_PUBLISHER, PatternOrder.BY_CATEGORY,
            PatternOrder.BY_NAME});
        grouping.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                refill();
            }
        });

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(new JLabel("Search"), BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        top.add(grouping, BorderLayout.EAST);

        JPanel right = new JPanel(new BorderLayout());
        JScrollPane detailsPane = new JScrollPane(details);
        detailsPane.setPreferredSize(new Dimension(420, 260));
        right.add(detailsPane, BorderLayout.CENTER);
        JPanel termsPanel = new JPanel(new BorderLayout());
        termsPanel.setBorder(BorderFactory.createTitledBorder(
                "Terms to import - all of them unless you select some"));
        JScrollPane termsPane = new JScrollPane(terms);
        termsPane.setPreferredSize(new Dimension(420, 200));
        termsPanel.add(termsPane, BorderLayout.CENTER);
        right.add(termsPanel, BorderLayout.SOUTH);

        JScrollPane left = new JScrollPane(patterns);
        left.setPreferredSize(new Dimension(300, 470));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        importButton = new JButton("Import terms...");
        importButton.setEnabled(false);
        importButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                handOff();
            }
        });
        JButton close = new JButton("Close");
        close.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dialog.dispose();
            }
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buttons.add(new JLabel(all.size() + " patterns"));
        buttons.add(javax.swing.Box.createHorizontalGlue());
        buttons.add(importButton);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(getOWLWorkspace());

        refill();
        dialog.setVisible(true);
    }

    /** Rebuilds the list for the current search and grouping. */
    private void refill() {
        DefaultListModel<DesignPattern> model =
                (DefaultListModel<DesignPattern>) patterns.getModel();
        model.clear();
        List<DesignPattern> matching = PatternLibrary.matching(search.getText());
        for (DesignPattern pattern : PatternOrder.sorted(matching,
                String.valueOf(grouping.getSelectedItem()))) {
            model.addElement(pattern);
        }
        if (!model.isEmpty()) {
            patterns.setSelectedIndex(0);
        } else {
            details.setText("<html><body style='font-family:sans-serif'>"
                    + "<p>Nothing matches.</p></body></html>");
            ((DefaultListModel<IRI>) terms.getModel()).clear();
            importButton.setEnabled(false);
        }
    }

    /** Reads the selected pattern's file and shows what is really in it. */
    private void showSelected() {
        DesignPattern chosen = patterns.getSelectedValue();
        DefaultListModel<IRI> termModel = (DefaultListModel<IRI>) terms.getModel();
        termModel.clear();
        if (chosen == null) {
            importButton.setEnabled(false);
            return;
        }
        PatternLibrary.Contents contents;
        try {
            contents = PatternLibrary.contentsOf(chosen);
        } catch (IOException unreadable) {
            details.setText("<html><body style='font-family:sans-serif'><p>"
                    + escape(unreadable.getMessage()) + "</p></body></html>");
            importButton.setEnabled(false);
            return;
        }
        for (IRI term : contents.getTerms()) {
            termModel.addElement(term);
        }
        details.setText(PatternSummary.asHtml(chosen, contents));
        details.setCaretPosition(0);
        importButton.setEnabled(!contents.getTerms().isEmpty());
    }

    /**
     * Copies the pattern beside the project and opens the import dialog on it.
     *
     * <p>Copied rather than referenced: a resource inside an OSGi bundle has no file IRI for
     * ROBOT to be pointed at, and a module whose source cannot be found again is a module nobody
     * dares regenerate. It goes to {@code src/ontology/mirror}, which is where ODK keeps the
     * upstream copies a build extracts from, so the next {@code refresh-imports} finds it.
     */
    private void handOff() {
        DesignPattern chosen = patterns.getSelectedValue();
        if (chosen == null) {
            return;
        }
        List<IRI> wanted = terms.getSelectedValuesList();
        if (wanted.isEmpty()) {
            wanted = new ArrayList<IRI>();
            DefaultListModel<IRI> model = (DefaultListModel<IRI>) terms.getModel();
            for (int at = 0; at < model.size(); at++) {
                wanted.add(model.get(at));
            }
        }

        File mirror = mirrorDirectory();
        if (mirror == null) {
            JOptionPane.showMessageDialog(dialog,
                    "This ontology has not been saved, so there is nowhere to put the pattern "
                            + "file the import reads from. Save it first.",
                    "Nowhere to copy it", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File copied;
        try {
            copied = PatternLibrary.copyTo(chosen, mirror);
        } catch (IOException cannotCopy) {
            JOptionPane.showMessageDialog(dialog, cannotCopy.getMessage(),
                    "Could not copy the pattern", JOptionPane.WARNING_MESSAGE);
            return;
        }

        StringBuilder termText = new StringBuilder();
        for (IRI term : wanted) {
            termText.append(term.toString()).append('\n');
        }

        dialog.dispose();
        ImportTermsAction importer = new ImportTermsAction();
        importer.setEditorKit(getOWLEditorKit());
        importer.initialise();
        importer.seedWith(copied.toURI().toString(), termText.toString().trim());
        importer.actionPerformed(null);
    }

    /** {@code src/ontology/mirror} beside the open ontology, created if need be. */
    private File mirrorDirectory() {
        org.semanticweb.owlapi.model.OWLOntology ontology =
                getOWLModelManager().getActiveOntology();
        if (ontology == null) {
            return null;
        }
        IRI document = getOWLModelManager().getOWLOntologyManager()
                .getOntologyDocumentIRI(ontology);
        java.net.URI uri = document == null ? null : document.toURI();
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        File beside = new File(uri).getAbsoluteFile().getParentFile();
        return beside == null ? null : new File(beside, "mirror");
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** Renders a pattern as its name, with the grouping key and a duplicate note beside it. */
    private static final class PatternRenderer extends javax.swing.DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof DesignPattern) {
                DesignPattern pattern = (DesignPattern) value;
                setText("<html>" + escape(pattern.getName())
                        + "<br><font size='-2' color='#777777'>"
                        + escape(pattern.getPublisher()) + " &middot; "
                        + escape(pattern.getCategory())
                        + (pattern.isDuplicate()
                                ? " &middot; same as " + escape(pattern.getSameAs()) : "")
                        + "</font></html>");
            }
            return this;
        }
    }
}
