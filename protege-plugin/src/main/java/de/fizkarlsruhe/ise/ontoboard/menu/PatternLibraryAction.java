package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.pattern.ContributedPatterns;
import de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternLibrary;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternOrder;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternRecommender;
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
 * OntoBoard &gt; ROBOT &gt; Pattern library... - the 159 design patterns shipped in the plugin,
 * and any of the user's own.
 *
 * <p><b>Your own patterns, from a folder, with no rebuild.</b> The library used to be exactly
 * what the jar held, so contributing a 160th pattern meant editing this repository and building
 * a 66 MB bundle. {@link ContributedPatterns} reads a folder every time this dialog opens, and
 * everything downstream - search, the orderings, the suggestions, the import - treats a file
 * from that folder and a pattern from the jar the same way.
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

    /**
     * The orderings offered, with the suggestions first.
     *
     * <p>First because 159 patterns is too many to browse when you want one, and the ontology
     * in front of somebody is the best evidence of what they are modelling.
     */
    static final String SUGGESTED = "Suggested for this ontology";

    /**
     * Every ordering the chooser offers, in the order it offers them.
     *
     * <p>A constant with a test behind it, because the list was wrong and nothing noticed.
     * {@code SUGGESTED} shipped in 1.87.0 - a recommender, its scoring measured and argued, a
     * paragraph of documentation calling it "the browser's first ordering" - and was never put
     * in the combo box, so no user could ever select it. {@code BY_COLLECTION} shipped the same
     * release, to answer "show me the MWO patterns", and was never offered either. Both were
     * implemented, tested, documented and unreachable.
     *
     * <p>{@link PatternLibraryOrderingsTest} now fails if an ordering {@code PatternOrder} knows how
     * to sort by is missing from here, which is the only way this class of mistake gets caught: the
     * code that implements an option and the code that offers it have no reason to be read together.
     * It names that class because this javadoc first named {@code PatternOrderTest}, where the check
     * was written and from where it had to move - a test in the pattern package cannot see a
     * package-private constant in this one. A javadoc pointing at the wrong guard is how a guard
     * gets deleted.
     */
    static final String[] ORDERINGS = {
        SUGGESTED, PatternOrder.BY_PUBLISHER, PatternOrder.BY_COLLECTION,
        PatternOrder.BY_CATEGORY, PatternOrder.BY_NAME,
    };

    /** Why each suggested pattern was suggested, by id. Empty in the other orderings. */
    private java.util.Map<String, String> reasons = new java.util.HashMap<String, String>();

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (PatternLibrary.all().isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "The pattern library did not load. It is packaged inside the plugin, so this "
                            + "means the jar is incomplete rather than that anything is missing "
                            + "from your project.",
                    "No patterns", JOptionPane.WARNING_MESSAGE);
            return;
        }
        show();
    }

    /** Everything on offer: what ships, plus whatever the user contributed. */
    private List<DesignPattern> everything = new ArrayList<DesignPattern>();

    /** What the user's folder turned out to hold, including why anything in it is missing. */
    private ContributedPatterns.Scan contributed = null;

    /** Where the user's patterns are read from, changeable from the dialog. */
    private File contributedRoot = null;

    private JLabel counts;
    private JButton problems;

    private void show() {
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

        grouping = new JComboBox<String>(ORDERINGS);
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
        JButton addYourOwn = new JButton("Your patterns...");
        addYourOwn.setToolTipText("Add an OWL or Turtle file of your own to the library, "
                + "or change the folder they are read from");
        addYourOwn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                contribute();
            }
        });

        counts = new JLabel();
        problems = new JButton();
        problems.setVisible(false);
        problems.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                showProblems();
            }
        });

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buttons.add(counts);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(problems);
        buttons.add(javax.swing.Box.createHorizontalGlue());
        buttons.add(addYourOwn);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(importButton);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);

        // Which ordering starts selected, rather than simply the first one. "Suggested" is
        // offered first because it is the most useful way in, but with no ontology open - or one
        // with nothing in it yet - it can only say that nothing matches, which is a poor thing
        // for a browser to open on.
        //
        // Set on the model rather than through setSelectedItem, which fires the action listener
        // and so ran a whole refill - including the recommender over the open ontology - against an
        // empty candidate list, before rescan() had put anything in it. The work was wasted twice
        // over: thrown away immediately, and done against nothing.
        grouping.getModel().setSelectedItem(canSuggest() ? SUGGESTED : PatternOrder.BY_PUBLISHER);

        // The scan parses every file in the user's folder, so on a folder with a few dozen in it
        // this is seconds rather than milliseconds, and the dialog is not on screen yet to say so.
        // A wait cursor is the honest minimum; the file cap is what bounds it.
        getOWLWorkspace().setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.WAIT_CURSOR));
        try {
            rescan();
        } finally {
            getOWLWorkspace().setCursor(java.awt.Cursor.getDefaultCursor());
        }
        dialog.pack();
        dialog.setLocationRelativeTo(getOWLWorkspace());
        dialog.setVisible(true);
    }

    /** Whether there is an ontology with enough in it for a suggestion to mean anything. */
    private boolean canSuggest() {
        org.semanticweb.owlapi.model.OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        return ontology != null && !ontology.getSignature(
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED).isEmpty();
    }

    /**
     * Reads the user's folder again and rebuilds the list.
     *
     * <p>Every time the dialog opens, not once per session. A contributed pattern is a file
     * somebody is editing, and a library that showed yesterday's version of it would be worse
     * than one that could not show it at all.
     */
    private void rescan() {
        contributedRoot = ContributedPatterns.root();
        contributed = ContributedPatterns.scan(contributedRoot);
        everything = new ArrayList<DesignPattern>(PatternLibrary.all());
        everything.addAll(contributed.getPatterns());

        int yours = contributed.getPatterns().size();
        counts.setText(PatternLibrary.all().size() + " patterns"
                + (yours == 0 ? "" : " + " + yours + " of your own"));
        int wrong = contributed.getProblems().size();
        problems.setText(wrong == 1 ? "1 problem..." : wrong + " problems...");
        problems.setVisible(wrong > 0);
        refill();
    }

    /** Says which of the user's files could not be read, and why. */
    private void showProblems() {
        StringBuilder text = new StringBuilder();
        text.append("<html><body style='font-family:sans-serif;width:460px'><p>Read from ")
                .append(escape(contributedRoot.getAbsolutePath())).append(":</p><ul>");
        for (String problem : contributed.getProblems()) {
            text.append("<li>").append(escape(problem)).append("</li>");
        }
        text.append("</ul></body></html>");
        JOptionPane.showMessageDialog(dialog, new JLabel(text.toString()),
                "Files that are not in the library", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Adds a file of the user's own to the library, or moves the folder they are read from.
     *
     * <p>Both in one dialog because they are the same question asked at different times: the
     * first time, "where do mine live and here is one"; later, "put this one there too". A
     * separate preferences page for a single path would be a worse answer, and a folder nobody
     * can see the path of is a folder nobody can put a file in by hand.
     */
    private void contribute() {
        java.util.Map<String, String> chosen = ParameterDialog.show(dialog, "Your patterns",
                "A pattern of your own is a file in a folder - no index, no metadata, no "
                        + "rebuild. Drop OWL or Turtle files into this folder and they appear in "
                        + "the library next time you open it. A sub-folder becomes a collection "
                        + "of its own, so your group's patterns can sit together under its name.",
                java.util.Arrays.asList(
                        Parameter.of("folder", "Folder", Parameter.Kind.DIRECTORY)
                                .defaultValue(contributedRoot.getAbsolutePath())
                                .help("Where your own patterns are read from. Point it inside an "
                                        + "ODK project - src/patterns, say - and they travel with "
                                        + "the repository, so everybody who clones it gets them.")
                                .build(),
                        Parameter.of("file", "Pattern to add", Parameter.Kind.FILE)
                                .defaultValue("")
                                .help("An .owl, .ttl, .rdf, .owx, .omn or .ofn file, copied into "
                                        + "the folder. Leave this empty to change the folder "
                                        + "without adding anything. The file's own dcterms:title, "
                                        + "hasIntent and coversRequirements annotations become "
                                        + "its name, description and competency questions, so "
                                        + "there is nothing to type in.")
                                .build(),
                        Parameter.of("collection", "Collection",
                                Parameter.Kind.TEXT)
                                .defaultValue(ContributedPatterns.DEFAULT_COLLECTION)
                                .help("The sub-folder to put it in, which is the name it is "
                                        + "grouped under in 'By collection'. Your group's name, "
                                        + "or the project's.")
                                .build()));
        if (chosen == null) {
            return;
        }
        String folder = chosen.get("folder");
        if (folder != null && !folder.trim().isEmpty()) {
            ContributedPatterns.setRoot(new File(folder.trim()));
        }
        String file = chosen.get("file");
        if (file != null && !file.trim().isEmpty()) {
            try {
                File added = ContributedPatterns.add(ContributedPatterns.root(),
                        chosen.get("collection"), new File(file.trim()));
                JOptionPane.showMessageDialog(dialog,
                        "Copied to " + added.getAbsolutePath() + ".",
                        "Added to your patterns", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException cannotAdd) {
                JOptionPane.showMessageDialog(dialog, cannotAdd.getMessage(),
                        "Could not add it", JOptionPane.WARNING_MESSAGE);
            }
        }
        rescan();
    }

    /** Rebuilds the list for the current search and grouping. */
    private void refill() {
        DefaultListModel<DesignPattern> model =
                (DefaultListModel<DesignPattern>) patterns.getModel();
        model.clear();
        // Cleared here, not inside suggested(). Only the suggested ordering filled this map, and
        // nothing emptied it again, so the blue "Suggested: 3 of its 4 terms are already in your
        // ontology" box stayed on the details pane in every other ordering - the field's own
        // javadoc says "Empty in the other orderings", which the code never made true. Worse across
        // openings: the action object outlives the dialog, so after switching Protege to an empty
        // ontology the browser opens on By publisher and still asserts a suggestion about an
        // ontology that is no longer open.
        reasons = new java.util.HashMap<String, String>();
        List<DesignPattern> matching = PatternLibrary.matching(everything, search.getText());
        String ordering = String.valueOf(grouping.getSelectedItem());
        for (DesignPattern pattern : SUGGESTED.equals(ordering)
                ? suggested(matching) : PatternOrder.sorted(matching, ordering)) {
            model.addElement(pattern);
        }
        if (!model.isEmpty()) {
            patterns.setSelectedIndex(0);
        } else {
            details.setText("<html><body style='font-family:sans-serif'><p>"
                    + (SUGGESTED.equals(String.valueOf(grouping.getSelectedItem()))
                        ? "Nothing in the library shares enough vocabulary with this ontology to "
                          + "be worth suggesting. Try another ordering and browse."
                        : "Nothing matches.")
                    + "</p></body></html>");
            ((DefaultListModel<IRI>) terms.getModel()).clear();
            importButton.setEnabled(false);
        }
    }

    /**
     * The patterns the open ontology already speaks the vocabulary of, best first.
     *
     * <p>Scored from the index rather than by opening 159 files: parsing them took 24 seconds,
     * measured, which is a dialog that looks like it has hung.
     */
    private List<DesignPattern> suggested(List<DesignPattern> candidates) {
        org.semanticweb.owlapi.model.OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        List<DesignPattern> best = new ArrayList<DesignPattern>();
        for (PatternRecommender.Recommendation one : PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(ontology), candidates, MOST_SUGGESTED)) {
            best.add(one.getPattern());
            reasons.put(one.getPattern().getId(), one.explain());
        }
        return best;
    }

    /** Enough to choose from, few enough that the bottom of the list still means something. */
    static final int MOST_SUGGESTED = 15;

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
        details.setText(PatternSummary.asHtml(chosen, contents, reasons.get(chosen.getId())));
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

        // Told before the import runs, because the import is what fires the ontology change the
        // canvas refreshes on - leaving the note afterwards would be a race the canvas usually
        // wins. It is a single slot that the canvas consumes, so nothing accumulates when the
        // canvas is not open, which is the common case.
        List<String> iris = new ArrayList<String>();
        for (IRI term : wanted) {
            iris.add(term.toString());
        }
        de.fizkarlsruhe.ise.ontoboard.pattern.PatternArrival.imported(chosen.getName(), iris);

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
                // A contributed pattern says its collection rather than its category, because
                // nothing assigns it one - "uncategorised" on every row of your own folder is
                // noise, and which folder you put it in is what you actually chose.
                String second = pattern.isContributed()
                        ? escape(pattern.getCollection()) + " &middot; yours"
                        : escape(pattern.getCategory());
                setText("<html>" + escape(pattern.getName())
                        + "<br><font size='-2' color='#777777'>"
                        + escape(pattern.getPublisher()) + " &middot; " + second
                        + (pattern.isDuplicate()
                                ? " &middot; same as " + escape(pattern.getSameAs()) : "")
                        + "</font></html>");
            }
            return this;
        }
    }
}
