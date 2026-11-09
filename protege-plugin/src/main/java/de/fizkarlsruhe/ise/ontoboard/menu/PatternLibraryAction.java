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
 * OntoBoard &gt; Reuse &gt; Pattern library... - the 159 design patterns shipped in the plugin,
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

    /** For each pattern found only by what its terms are called, which terms; by id. */
    private java.util.Map<String, String> foundBy = new java.util.HashMap<String, String>();

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
        terms.setCellRenderer(new TermRenderer());

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

        // REPORTING A PROBLEM FROM WHERE IT IS SEEN. Asked for: "if the pattern is already
        // available, then the user may only have the title and description of the issue" - the one
        // issue form there was demanded competency questions, the ontology file, a source and a
        // licence of somebody reporting a wrong axiom. This opens the short problem form instead,
        // with the title and the pattern already filled in from the selection.
        //
        // Under the details rather than in the row along the bottom: it is about the pattern those
        // details describe, and that row already has a "N problems..." button about something else
        // entirely - the user's own files that would not load.
        //
        // Disabled, with the reason as its tooltip, for one of the user's own patterns: it is not
        // in the library, so there is nobody else to tell. PatternProblemReport builds the address,
        // and the same address is on every pattern's page on the documentation site.
        final JButton report = new JButton("Report a problem with this pattern...");
        report.setEnabled(false);
        report.setToolTipText(
                de.fizkarlsruhe.ise.ontoboard.pattern.PatternProblemReport.whyNot(null));
        report.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                reportProblem(patterns.getSelectedValue());
            }
        });
        // Its own listener, on every event rather than only settled ones: it only reads the
        // selection, so there is nothing to save by waiting, and it keeps this button out of
        // showSelected(), whose early returns would each need to remember it.
        patterns.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent event) {
                String why = de.fizkarlsruhe.ise.ontoboard.pattern.PatternProblemReport
                        .whyNot(patterns.getSelectedValue());
                report.setEnabled(why == null);
                report.setToolTipText(why != null ? why
                        : "Opens the issue form in your browser with this pattern filled in. A "
                                + "title and a sentence on what is wrong are all it asks for.");
            }
        });
        JPanel reportRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 4));
        reportRow.add(report);
        JPanel detailsColumn = new JPanel(new BorderLayout());
        detailsColumn.add(detailsPane, BorderLayout.CENTER);
        detailsColumn.add(reportRow, BorderLayout.SOUTH);
        right.add(detailsColumn, BorderLayout.CENTER);
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
        DialogKeys.escapePresses(dialog.getRootPane(), close);
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

    /**
     * Opens the problem form for one of the library's patterns, or shows its address.
     *
     * <p>The guard {@code TrackerItemAction} and {@code WidocoAction} use: {@code Desktop} is
     * missing on some Linux desktops, and a button that appears to do nothing is the worst outcome,
     * so when no browser opens the address is put in front of the user, selected, to copy.
     */
    private void reportProblem(DesignPattern chosen) {
        String url = de.fizkarlsruhe.ise.ontoboard.pattern.PatternProblemReport.urlFor(chosen);
        if (url == null) {
            // The button is disabled whenever this would be null; a click that races a refill of
            // the list is the only way here, and there is nothing to report on.
            return;
        }
        String why = "This desktop cannot open a browser from Protege.";
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop()
                    .isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                return;
            }
        } catch (Exception cannotOpen) {
            // Some of these carry no message at all, and "null" in a dialog explains nothing.
            why = "Could not open a browser (" + (cannotOpen.getMessage() == null
                    ? cannotOpen.getClass().getSimpleName() : cannotOpen.getMessage()) + ").";
        }
        JTextField address = new JTextField(url, 48);
        address.setEditable(false);
        address.selectAll();
        JOptionPane.showMessageDialog(dialog,
                new Object[] {why + " The form is at this address - copy it:", address},
                "Report a problem with this pattern", JOptionPane.INFORMATION_MESSAGE);
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
        foundBy = new java.util.HashMap<String, String>();
        List<DesignPattern> matching = PatternLibrary.matching(everything, search.getText());
        String ordering = String.valueOf(grouping.getSelectedItem());
        boolean searching = !search.getText().trim().isEmpty();
        List<DesignPattern> ordered = SUGGESTED.equals(ordering)
                ? suggestedThenTheRest(suggested(matching), matching, searching)
                : PatternOrder.sorted(matching, ordering);
        // A pattern whose name is what was searched for comes first, as the row selected below.
        for (DesignPattern pattern : searching
                ? PatternLibrary.nameMatchesFirst(ordered, search.getText()) : ordered) {
            model.addElement(pattern);
        }
        // Then the patterns found only by what their terms are called, below everything found by
        // its name or description, and each saying which of its terms it was found by.
        for (DesignPattern pattern : PatternOrder.sorted(
                PatternLibrary.matchingByTerms(everything, search.getText()),
                SUGGESTED.equals(ordering) ? PatternOrder.BY_PUBLISHER : ordering)) {
            model.addElement(pattern);
            foundBy.put(pattern.getId(), "Found by its terms: " + String.join(", ",
                    PatternLibrary.termsMatching(pattern, search.getText())) + ".");
        }
        if (!model.isEmpty()) {
            patterns.setSelectedIndex(0);
        } else {
            details.setText("<html><body style='font-family:sans-serif'><p>"
                    + (SUGGESTED.equals(String.valueOf(grouping.getSelectedItem())) && !searching
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

    /**
     * What "Suggested for this ontology" lists: the suggestions, then - once something is typed
     * in the search box - every other pattern that matches it.
     *
     * <p>A search used to look only among the suggestions: at most fifteen, and none for an
     * ontology with little in it yet. Replaying the pizza walkthrough in Protege, each of its
     * three searches - components, classifies, cost - listed nothing in a new project until the
     * ordering was changed, though the three patterns are in the library. The dialog opens on this
     * ordering, so that was the first thing a reader following the steps would have met.
     */
    static List<DesignPattern> suggestedThenTheRest(List<DesignPattern> suggestions,
            List<DesignPattern> matching, boolean searching) {
        if (!searching) {
            return suggestions;
        }
        List<DesignPattern> shown = new ArrayList<DesignPattern>(suggestions);
        for (DesignPattern pattern : PatternOrder.sorted(matching, PatternOrder.BY_PUBLISHER)) {
            if (!shown.contains(pattern)) {
                shown.add(pattern);
            }
        }
        return shown;
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
        shownContents = contents;
        for (IRI term : contents.getTerms()) {
            termModel.addElement(term);
        }
        String sayFirst = foundBy.get(chosen.getId());
        if (importedAs(getOWLModelManager().getActiveOntology(), importsDirectory(),
                chosen.getId()) != null) {
            sayFirst = ALREADY_IMPORTED + (sayFirst == null ? "" : " " + sayFirst);
        }
        details.setText(PatternSummary.asHtml(chosen, contents, reasons.get(chosen.getId()),
                sayFirst));
        details.setCaretPosition(0);
        importButton.setEnabled(!contents.getTerms().isEmpty());
    }

    /** Said in the details of a pattern this ontology already imports. */
    static final String ALREADY_IMPORTED = "Already imported into this ontology.";

    /**
     * The module this pattern was imported as, while the open ontology still imports it; null
     * when it does not.
     *
     * <p>By the {@code # Pattern:} line the library's import writes into the term list, so a
     * module named after the pattern's own IRI - {@code agentrole_import.owl} for
     * {@code agent-role} - is recognised too, and by the module being open among the imports, so
     * files an undone import left behind do not count.
     *
     * <p>Asked because importing a pattern twice was the way into a broken session, and nothing
     * said the second import was a second one: with "Suggested" ordering, a pattern already
     * imported is first in the list and selected.
     */
    static File importedAs(org.semanticweb.owlapi.model.OWLOntology ontology,
            File importsDirectory, String patternId) {
        File[] files = importsDirectory == null ? null : importsDirectory.listFiles();
        if (ontology == null || files == null || patternId == null) {
            return null;
        }
        for (File terms : files) {
            String name = terms.getName();
            if (!name.endsWith("_terms.txt")) {
                continue;
            }
            String id;
            try {
                id = de.fizkarlsruhe.ise.ontoboard.odk.ImportModules.patternIn(terms);
            } catch (IOException unreadable) {
                continue;
            }
            File module = new File(importsDirectory,
                    name.substring(0, name.length() - "_terms.txt".length()) + "_import.owl");
            if (patternId.equals(id) && de.fizkarlsruhe.ise.ontoboard.odk.ModuleRebuild
                    .openFrom(ontology, module) != null) {
                return module;
            }
        }
        return null;
    }

    /** {@code imports/} beside the open ontology, where the import puts modules; or null. */
    private File importsDirectory() {
        File editFile = editFile();
        File beside = editFile == null ? null : editFile.getParentFile();
        return beside == null ? null : new File(beside, "imports");
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
        List<IRI> all = new ArrayList<IRI>();
        DefaultListModel<IRI> model = (DefaultListModel<IRI>) terms.getModel();
        for (int at = 0; at < model.size(); at++) {
            all.add(model.get(at));
        }
        List<IRI> selected = terms.getSelectedValuesList();
        // A selection narrows the import, and one click on a row is enough to make one - which a
        // first look down the list easily is. So the question says so, and offers all of them.
        boolean narrowed = !selected.isEmpty() && selected.size() < all.size();
        List<IRI> wanted = narrowed ? selected : all;

        File mirror = mirrorDirectory();
        if (mirror == null) {
            JOptionPane.showMessageDialog(dialog,
                    "This ontology has not been saved, so there is nowhere to put the pattern "
                            + "file the import reads from. Save it first.",
                    "Nowhere to copy it", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int classes = 0;
        for (IRI term : wanted) {
            if (shownContents != null && shownContents.isClass(term)) {
                classes++;
            }
        }
        boolean imported = importedAs(getOWLModelManager().getActiveOntology(),
                importsDirectory(), chosen.getId()) != null;
        // One confirmation, as Find terms asks, and no form: the dialog behind Advanced... is a
        // box of IRIs and five choices, which is a lot to ask of somebody who has just picked a
        // pattern from a list. Everything it offers is still there for whoever wants it.
        String[] options = narrowed
                ? new String[] {"Add the " + selected.size() + " selected",
                    "Add all " + all.size(), "Advanced...", "Cancel"}
                : new String[] {"Add them", "Advanced...", "Cancel"};
        int answer = JOptionPane.showOptionDialog(dialog, whatAddingWrites(chosen.getName(),
                        classes, wanted.size() - classes, narrowed ? all.size() : 0,
                        moduleNameFor(chosen, mirror), imported),
                "Add " + chosen.getName(), JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (narrowed && answer == 1) {
            wanted = all;
            answer = 0;
        } else if (narrowed && answer >= 2) {
            answer--;
        }
        if (answer != 0 && answer != 1) {
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

        // The label after each IRI, as a comment: a term list reads " # " to the end of the line as
        // one, so the dialog that follows says what is about to come in as well as its address.
        StringBuilder termText = new StringBuilder();
        for (IRI term : wanted) {
            termText.append(term.toString());
            String label = shownContents == null ? null : shownContents.labelOf(term);
            if (label != null) {
                termText.append(" # ").append(label.replace('\n', ' '));
            }
            termText.append('\n');
        }

        // Told before the import runs, because the import is what fires the ontology change the
        // canvas refreshes on - leaving the note afterwards would be a race the canvas usually
        // wins. The canvas removes the note once it has framed the pattern; with the board
        // closed it waits, beside any other pattern imported meanwhile, for the board to open.
        //
        // ONLY THE CLASSES ARE FRAMED, THOUGH EVERY SELECTED TERM IS IMPORTED. Reported as the
        // frame being "so compact and lots of concepts are packed", and measuring the 163 shipped
        // patterns said why: they declare 1318 classes and 1054 properties, so 44% of the boxes in
        // a frame were relations rather than concepts. A property on this canvas is an arrow -
        // OntologyProjection already draws one for every property whose domain and range are on
        // the board - so boxing it as well showed the same fact twice and nearly doubled the grid.
        // The median pattern drops from 11 boxes to 6, and its frame from 840x380 to 640x280.
        //
        // termText above is unchanged, so the import still brings in the properties: this decides
        // what is drawn, not what is added to the ontology.
        List<String> iris = new ArrayList<String>();
        for (IRI term : framed(chosen, wanted)) {
            iris.add(term.toString());
        }
        de.fizkarlsruhe.ise.ontoboard.pattern.PatternArrival.imported(
                de.fizkarlsruhe.ise.ontoboard.pattern.PatternArrival.idOf(
                        getOWLModelManager().getActiveOntology()),
                chosen.getName(), iris);

        dialog.dispose();
        ImportTermsAction importer = new ImportTermsAction();
        importer.setEditorKit(getOWLEditorKit());
        importer.initialise();
        importer.seedWithPattern(copied.toURI().toString(), termText.toString().trim(),
                chosen.getId());
        if (answer == 0) {
            importer.withoutAsking();
        }
        importer.actionPerformed(null);
    }

    /**
     * What "Add them" will do, for the confirmation.
     *
     * <p>It names the files that will be written, which are named after the pattern as the import
     * reads it - see {@link #moduleNameFor} - and said {@code imports/agent-role_import.owl} for a
     * pattern whose module is written as {@code agentrole_import.owl}: wrong for 49 of the 157
     * patterns a code review checked.
     *
     * @param all how many terms the pattern has, when only some of them are selected; 0 when all
     *     of them are being added
     * @param imported whether the open ontology imports this pattern already
     */
    static String whatAddingWrites(String name, int classes, int properties, int all,
            String module, boolean imported) {
        int chosen = classes + properties;
        StringBuilder text = new StringBuilder();
        if (imported) {
            text.append(name).append(" is already imported. Adding it again rebuilds its module\n"
                    + "from the terms chosen here.\n\n");
        }
        text.append("Add ").append(name).append(" to your ontology: ").append(classes)
                .append(classes == 1 ? " class and " : " classes and ").append(properties)
                .append(properties == 1 ? " property." : " properties.");
        if (all > 0) {
            text.append("\n\n").append(chosen == 1 ? "Only the term" : "Only the " + chosen
                    + " terms").append(" selected in the list ").append(chosen == 1 ? "is" : "are")
                    .append(" added. Add all ").append(all).append(" adds every term\n"
                            + "of the pattern.");
        }
        return text.append("\n\nOntoBoard will:\n"
                + "- cut them out of the pattern, with what they need, into a module of your\n"
                + "  project: imports/").append(module).append("_import.owl\n"
                + "- list them in ").append(module).append("_terms.txt, so the import can be"
                + " changed\n"
                + "  later (OntoBoard > Reuse > Imported terms...)\n"
                + "- import that module into your ontology, with a catalog entry beside it\n\n"
                + "They appear on the board in a frame named after the pattern, ready to extend.\n\n"
                + "Advanced... lets you choose how much comes with them, and where the module goes.")
                .toString();
    }

    /**
     * What the import will call the pattern's module and term list: {@code TermExtract}'s name for
     * the pattern as it will be read, from its copy in {@code mirror/}. Named after the IRI the
     * pattern declares, and after the copy's file when it declares none - or one relative to the
     * file - so it cannot be worked out from the id alone. The pattern's id when it cannot be read,
     * which the import would then refuse anyway.
     */
    static String moduleNameFor(DesignPattern chosen, File mirror) {
        try {
            return de.fizkarlsruhe.ise.ontoboard.robot.TermExtract.shortNameOf(
                    PatternLibrary.loadAs(chosen,
                            IRI.create(PatternLibrary.copyFor(chosen, mirror).toURI())));
        } catch (IOException unreadable) {
            return chosen.getId();
        } catch (RuntimeException unreadable) {
            return chosen.getId();
        }
    }

    /** {@code src/ontology/mirror} beside the open ontology, created if need be. */
    /**
     * Which of the chosen terms get a box on the canvas: the classes.
     *
     * <p>Separate from what is imported, which is all of them. A property is drawn on this canvas
     * as an arrow between the classes it relates - {@code OntologyProjection} emits one for every
     * property whose domain and range are both on the board - so giving it a box as well states
     * the same fact twice and, measured across the 163 shipped patterns, nearly doubles the grid:
     * they declare 1318 classes against 1054 properties, so 44% of the boxes were relations.
     *
     * <p><b>Falls back to framing everything</b> if the pattern cannot be re-read. The frame is a
     * presentation detail and the import has already been decided; refusing to frame, or throwing,
     * would turn a cosmetic problem into a failed import.
     */
    private static List<IRI> framed(de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern chosen,
            List<IRI> wanted) {
        java.util.Set<IRI> classes = new java.util.LinkedHashSet<IRI>();
        try {
            for (org.semanticweb.owlapi.model.OWLEntity entity
                    : PatternLibrary.contentsOf(chosen).getClasses()) {
                classes.add(entity.getIRI());
            }
        } catch (IOException cannotRead) {
            return wanted;
        } catch (RuntimeException cannotRead) {
            return wanted;
        }
        if (classes.isEmpty()) {
            // A pattern of nothing but properties - rare, and an empty frame says less than a
            // crowded one.
            return wanted;
        }
        List<IRI> boxes = new ArrayList<IRI>();
        for (IRI term : wanted) {
            if (classes.contains(term)) {
                boxes.add(term);
            }
        }
        return boxes.isEmpty() ? wanted : boxes;
    }

    private File mirrorDirectory() {
        File editFile = editFile();
        File beside = editFile == null ? null : editFile.getParentFile();
        return beside == null ? null : new File(beside, "mirror");
    }

    /** The open ontology's file, or null when it has never been saved. */
    private File editFile() {
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
        return new File(uri).getAbsoluteFile();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** What the selected pattern holds, for what its terms are called. */
    private PatternLibrary.Contents shownContents;

    /** A term as what it is called, then its short name and whether it is a class or a property. */
    private final class TermRenderer extends javax.swing.DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof IRI) {
                IRI term = (IRI) value;
                String label = shownContents == null ? null : shownContents.labelOf(term);
                String kind = shownContents != null && shownContents.isClass(term) ? "class"
                        : "property";
                setText("<html>" + escape(label == null ? term.getShortForm() : label)
                        + " <font size='-2' color='#777777'>" + (label == null ? ""
                                : escape(term.getShortForm()) + " &middot; ") + kind
                        + "</font></html>");
                setToolTipText(term.toString());
            }
            return this;
        }
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
                                ? " &middot; " + escape(PatternLibrary.twinNote(pattern)) : "")
                        + "</font></html>");
            }
            return this;
        }
    }
}
