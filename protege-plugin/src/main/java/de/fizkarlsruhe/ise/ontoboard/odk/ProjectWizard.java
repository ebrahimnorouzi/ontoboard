package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;

/**
 * The "new project" form and the ODK repository it produces.
 *
 * <p>Swing, so untestable headlessly. Every decision it collects - validation, IRI
 * normalisation, the file tree - lives in {@link OdkProjectConfig} and {@link OdkScaffold},
 * which are covered.
 */
public final class ProjectWizard {

    private ProjectWizard() {
    }

    /**
     * Shows the wizard and creates the project.
     *
     * <p>Static and parameterised so the Tools menu action and the canvas start panel run the
     * same code. Reaching it by subclassing the action would depend on Protege's action
     * lifecycle, which is not ours to rely on.
     */
    public static void show(Component parent, OWLModelManager modelManager) {
        JTextField id = new JTextField();
        JTextField title = new JTextField();
        JTextField description = new JTextField();
        final JTextField iri = new JTextField();
        // Filled in as the id is typed, and visible rather than implied.
        //
        // The default is OBO's namespace, which is right for an ontology being registered with
        // OBO Foundry and wrong for every other project - and it ends up in every term IRI,
        // permanently. The wizard used to say "leave Base IRI empty to use the OBO convention",
        // which is an invitation to mint terms under a namespace you do not own: MWO publishes
        // under purls.helmholtz-metadaten.de and NFDIcore under nfdi.fiz-karlsruhe.de, and
        // neither would notice until the IRIs failed to resolve for somebody else.
        //
        // So the same default is still there, but in the box where it can be read and
        // overtyped. It stops following the id as soon as anybody edits it.
        id.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private String suggested = "";

            private void follow() {
                if (!iri.getText().trim().isEmpty() && !iri.getText().equals(suggested)) {
                    return;
                }
                String typed = id.getText().trim().toLowerCase(java.util.Locale.ROOT);
                suggested = typed.isEmpty() ? ""
                        : "http://purl.obolibrary.org/obo/" + typed + ".owl";
                iri.setText(suggested);
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }
        });
        JTextField license = new JTextField("https://creativecommons.org/licenses/by/4.0/");
        final JTextField folder = new JTextField();
        folder.setEditable(false);

        JButton browse = new JButton("Choose...");
        browse.addActionListener(a -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Where should the project folder be created?");
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("Ontology ID:"));
        fields.add(id);
        fields.add(new JLabel("Title:"));
        fields.add(title);
        fields.add(new JLabel("Description:"));
        fields.add(description);
        fields.add(new JLabel("Base IRI:"));
        fields.add(iri);
        fields.add(new JLabel("License URL:"));
        fields.add(license);
        fields.add(new JLabel("Create in folder:"));
        fields.add(folderRow);

        // The rules said exactly, and the Base IRI said in a sentence: "one you own" meant nothing
        // to somebody who had never published an ontology, and the ID rule was learnt from the
        // error after the first try.
        JLabel hint = new JLabel("<html><i><b>ID</b>: a short name for file names - a lowercase "
                + "letter first, then lowercase letters, digits or underscores, e.g. <b>myont</b>"
                + " or <b>shop_ontology</b>.<br><b>Base IRI</b>: the web address your ontology "
                + "and its terms will be named under; it must end in the ID followed by .owl. "
                + "OBO's address is filled in, which is right only for an ontology registered "
                + "with the OBO Foundry. Otherwise use an address on a domain you or your "
                + "group control, e.g. <b>https://w3id.org/myont/myont.owl</b> - every term "
                + "is named under it, so it is hard to change later.</i></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        // Where a rejected value is explained, in the dialog rather than on top of it. Hidden
        // until there is something to say, so a first attempt looks no different from before.
        JLabel problem = new JLabel();
        problem.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        problem.setVisible(false);

        // START FROM WHAT IS OPEN. The likeliest start - an ontology somebody already has - had no
        // way in: the guide said to make an empty project and merge the file into it by hand,
        // and the IRI rule then refused the IRI they already had.
        final org.semanticweb.owlapi.model.OWLOntology open = modelManager.getActiveOntology();
        final File openFile = adoptable(modelManager, open);
        final javax.swing.JCheckBox adopt = new javax.swing.JCheckBox(openFile == null ? ""
                : "Start from " + openFile.getName() + " - keep its address and everything in it");
        adopt.setVisible(openFile != null);
        adopt.addActionListener(a -> {
            if (adopt.isSelected()) {
                String address = open.getOntologyID().getOntologyIRI().get().toString();
                id.setText(idFrom(address));
                iri.setText(address);
                if (title.getText().trim().isEmpty()) {
                    title.setText(titleOf(open, openFile));
                }
            }
        });

        JPanel north = new JPanel(new BorderLayout());
        north.add(problem, BorderLayout.NORTH);
        north.add(adopt, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(560, openFile == null ? 260 : 290));

        // Until it is accepted or abandoned. Every field this form collects is rejectable -
        // OdkProjectConfig.validate has eight rules, and two of them ("base IRI must end in the
        // ontology ID", "an SPDX identifier is not a licence IRI") are ones a careful person
        // trips on their first attempt. The form used to close on the way to the warning, so the
        // price of a typo in the last field was retyping the other five. The components are built
        // once above and reused here, so everything typed survives a rejection.
        //
        // Safe because OdkScaffold.create validates before it creates anything - config.validate()
        // is its first statement, ahead of the first mkdirs - so a rejected attempt has written
        // nothing and there is nothing to undo before the next one. A failure from the writing
        // half is different, because part of the tree may exist, so that one still stops.
        while (true) {
            if (JOptionPane.showConfirmDialog(parent, panel, "New ODK project",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) {
                return;
            }

            OdkProjectConfig config = new OdkProjectConfig(id.getText(), title.getText(),
                    description.getText(), iri.getText(), license.getText(),
                    folder.getText().trim().isEmpty() ? null : new File(folder.getText().trim()));
            if (adopt.isSelected()) {
                config.adopting();
            }

            List<File> written;
            try {
                written = OdkScaffold.create(config);
            } catch (IllegalArgumentException invalid) {
                problem.setText(problemHtml(invalid.getMessage()));
                problem.setVisible(true);
                continue;
            } catch (RuntimeException failure) {
                JOptionPane.showMessageDialog(parent,
                        "Could not write the project: " + failure.getMessage(),
                        "Failed", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (config.isAdopted()) {
                adoptInto(parent, modelManager, open, openFile, config, written.size());
            } else {
                openGenerated(parent, modelManager, config, written.size());
            }
            return;
        }
    }

    /** The open ontology, if it has an IRI and a file and is not a project's edit file already. */
    static File adoptable(OWLModelManager modelManager,
            org.semanticweb.owlapi.model.OWLOntology open) {
        if (open == null || !open.getOntologyID().getOntologyIRI().isPresent()) {
            return null;
        }
        IRI document = modelManager.getOWLOntologyManager().getOntologyDocumentIRI(open);
        if (document == null || !"file".equals(document.getScheme())) {
            return null;
        }
        File file = new File(document.toURI());
        return file.getName().endsWith("-edit.owl") ? null : file;
    }

    /** "https://example.org/Shop-Ontology.owl" as an ID: shop_ontology. */
    static String idFrom(String address) {
        String name = address.replaceAll("[#/]+$", "");
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('#')) + 1)
                .replaceAll("\\.(owl|ttl|rdf|ofn|omn|owx)$", "")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            name = "o" + name;
        }
        return name;
    }

    private static String titleOf(org.semanticweb.owlapi.model.OWLOntology open, File file) {
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : open.getAnnotations()) {
            String property = annotation.getProperty().getIRI().toString();
            if ((property.endsWith("/title") || property.endsWith("#label"))
                    && annotation.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) annotation.getValue())
                        .getLiteral();
            }
        }
        return file.getName().replaceAll("\\.[^.]+$", "");
    }

    /**
     * The open ontology becomes the project's edit file: saved there, kept open, its IRI and
     * every axiom as they were. The file it came from is left as it is.
     */
    private static void adoptInto(Component parent, OWLModelManager modelManager,
            org.semanticweb.owlapi.model.OWLOntology open, File from, OdkProjectConfig config,
            int fileCount) {
        try {
            modelManager.getOWLOntologyManager().setOntologyDocumentIRI(open,
                    IRI.create(config.getEditFile()));
            modelManager.save(open);
            JOptionPane.showMessageDialog(parent,
                    wrapped("Created " + fileCount + " files in\n"
                            + config.getProjectRoot().getAbsolutePath()
                            + "\n\nYour ontology is now the project's edit file, "
                            + config.getEditFile().getName() + " - the file you work in from now"
                            + " on, with its address and every term as they were. "
                            + from.getName() + " is left as it was."
                            + "\n\nNext: OntoBoard > Start here: the guide... walks you through"
                            + " the rest. Nothing else needs installing."),
                    "Project created", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception cannotSave) {
            JOptionPane.showMessageDialog(parent,
                    wrapped("The project was created in "
                            + config.getProjectRoot().getAbsolutePath()
                            + ", but your ontology could not be saved into it: "
                            + cannotSave.getMessage() + "\n\nSave it there yourself as "
                            + config.getEditFile().getName() + "."),
                    "Project created", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * A rejection message, as a banner the form itself can carry.
     *
     * <p>Wrapped at the dialog's width, because these are sentences rather than labels - the
     * base-IRI rule runs to sixty words and spends them explaining what a relative IRI does to a
     * colleague's copy, which is the part worth reading. An unwrapped {@code JLabel} lays that out
     * as a single line and widens the dialog past the edge of the screen.
     *
     * <p>Escaped, because every one of these messages quotes the user's own input back at them:
     * {@code Got: '<b>'}. Swing's HTML renderer would read that as markup and show an empty
     * string, hiding the very value that was rejected.
     */
    static String problemHtml(String message) {
        String text = message == null || message.trim().isEmpty()
                ? "Something about these values was rejected." : message;
        return "<html><body style='width:520px'><b>Not created.</b> " + escaped(text)
                + "</body></html>";
    }

    /**
     * A message for a dialog, wrapped so the dialog comes out about 600 px wide: a blank line
     * between paragraphs, a line break where the text has one.
     *
     * <p>Unwrapped, an option pane lays each line out at its full length, and "Project created"
     * measured 1355 px wide on a Mac - its "Next: ..." line alone about 1200 px - so on a screen
     * 1280 px wide, 1920x1080 at 150%, its OK button was past the edge. Escaped, as
     * {@link #problemHtml} is: a folder's name may hold an {@code &}. 400px, because Swing reads
     * a CSS pixel as 1.3 of the screen's: the text is 520 px wide, measured, and the dialog 660.
     *
     * <p>A path is broken after a separator once it is longer than a line, because nothing else
     * breaks it: a word wider than the width sets the width, and every sentence then wrapped at
     * that instead - 1165 px for a project made in a deep folder.
     */
    static String wrapped(String message) {
        StringBuilder html = new StringBuilder("<html><body style='width:400px'>");
        String[] paragraphs = (message == null ? "" : message).split("\n\n");
        for (int at = 0; at < paragraphs.length; at++) {
            html.append(at == 0 ? "" : "<br><br>")
                    .append(breakable(escaped(paragraphs[at])).replace("\n", "<br>"));
        }
        return html.append("</body></html>").toString();
    }

    /** About as many characters of a path as fit on one line of {@link #wrapped}. */
    static final int LONGEST_WORD = 60;

    /** Every word longer than a line broken after a {@code /} or {@code \}, so it wraps. */
    private static String breakable(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (String word : text.split(" ", -1)) {
            out.append(out.length() == 0 ? "" : " ");
            int start = 0;
            while (word.length() - start > LONGEST_WORD) {
                int cut = Math.max(word.lastIndexOf('/', start + LONGEST_WORD),
                        word.lastIndexOf('\\', start + LONGEST_WORD));
                if (cut <= start) {
                    break;
                }
                out.append(word, start, cut + 1).append("<br>");
                start = cut + 1;
            }
            out.append(word.substring(start));
        }
        return out.toString();
    }

    private static String escaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                escaped.append("&amp;");
            } else if (c == '<') {
                escaped.append("&lt;");
            } else if (c == '>') {
                escaped.append("&gt;");
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * What to do when Protégé would not open a new project's edit file. When an ontology with the
     * same IRI is open already - a project made again with the same ID, as starting over does -
     * opening it by hand would not work either: Protégé keeps the one it has, and a tester went on
     * editing the old project. Only a fresh start opens the new one.
     */
    static String howToOpen(Exception couldNotOpen, String editFile) {
        if (isAlreadyOpen(couldNotOpen)) {
            return "An ontology with the same IRI is already open, probably an earlier project with "
                    + "the same Ontology ID and Base IRI. To work in the new project, quit Protege, "
                    + "start it again, and choose OntoBoard > Project > Open existing ODK project... "
                    + "with the folder above.";
        }
        return "Open it manually: src/ontology/" + editFile;
    }

    /** Whether Protégé would not open it because an ontology with the same IRI is open. */
    static boolean isAlreadyOpen(Exception couldNotOpen) {
        String said = String.valueOf(couldNotOpen.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return couldNotOpen instanceof org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException
                || said.contains("already exists") || said.contains("already contains");
    }

    /**
     * Brings the OntoBoard tab forward, adding it if it is not there.
     *
     * <p>The dialog that follows says the file is on that tab. On a fresh Protege the tab has never
     * been opened, so that was not true for exactly the person reading it for the first time. A
     * failure here leaves the project as it is and the tab one menu away.
     *
     * <p>Package-visible for {@link ProjectOpener}, whose message points at the board too and which
     * left Active ontology in front.
     */
    static void showTheBoard(Component parent) {
        org.protege.editor.core.ui.workspace.TabbedWorkspace workspace =
                parent instanceof org.protege.editor.core.ui.workspace.TabbedWorkspace
                        ? (org.protege.editor.core.ui.workspace.TabbedWorkspace) parent
                        : (org.protege.editor.core.ui.workspace.TabbedWorkspace)
                                javax.swing.SwingUtilities.getAncestorOfClass(
                                        org.protege.editor.core.ui.workspace.TabbedWorkspace.class,
                                        parent);
        if (workspace == null) {
            return;
        }
        try {
            for (org.protege.editor.core.ui.workspace.WorkspaceTab open
                    : workspace.getWorkspaceTabs()) {
                if (open instanceof de.fizkarlsruhe.ise.ontoboard.views.OntoBoardTab) {
                    workspace.setSelectedTab(open);
                    return;
                }
            }
            for (org.protege.editor.core.ui.workspace.WorkspaceTabPlugin plugin
                    : workspace.getOrderedPlugins()) {
                if ("OntoBoard".equals(plugin.getLabel())) {
                    org.protege.editor.core.ui.workspace.WorkspaceTab added =
                            workspace.addTabForPlugin(plugin);
                    if (added != null) {
                        workspace.setSelectedTab(added);
                    }
                    return;
                }
            }
        } catch (RuntimeException notFatal) {
            org.slf4j.LoggerFactory.getLogger(ProjectWizard.class)
                    .warn("Could not bring the OntoBoard tab forward", notFatal);
        }
    }

    /**
     * Opens the generated edit file so the user lands in the ontology they just described.
     *
     * <p>If loading fails the project is still on disk and intact, so the message says where
     * it is rather than implying the whole operation failed.
     */
    private static void openGenerated(Component parent, OWLModelManager modelManager,
            OdkProjectConfig config, int fileCount) {
        try {
            modelManager.getOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(config.getEditFile());
            modelManager.setActiveOntology(
                    modelManager.getOWLOntologyManager().getOntology(
                            IRI.create(config.getBaseIri())));
            showTheBoard(parent);
            JOptionPane.showMessageDialog(parent,
                    wrapped("Created " + fileCount + " files in\n"
                            + config.getProjectRoot().getAbsolutePath()
                            + "\n\nEditing " + config.getEditFile().getName()
                            + " - the file you work in. It is on the OntoBoard tab now."
                            + "\n\nNext: OntoBoard > Start here: the guide... walks you through"
                            + " the rest. To check the ontology at any time,"
                            + " OntoBoard > Build and release > Build... and choose test."
                            + " Nothing else needs installing."),
                    "Project created", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception couldNotOpen) {
            JOptionPane.showMessageDialog(parent,
                    wrapped("The project was created at\n"
                            + config.getProjectRoot().getAbsolutePath()
                            + "\n\nbut Protege could not open the edit file automatically"
                            + (isAlreadyOpen(couldNotOpen) ? "." : ":\n" + couldNotOpen.getMessage())
                            + "\n\n" + howToOpen(couldNotOpen, config.getEditFile().getName())),
                    "Project created, not opened", JOptionPane.WARNING_MESSAGE);
        }
    }
}
