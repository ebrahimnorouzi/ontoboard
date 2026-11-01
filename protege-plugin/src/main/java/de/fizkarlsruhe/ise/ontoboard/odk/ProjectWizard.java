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

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(problem, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(560, 260));

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

            openGenerated(parent, modelManager, config, written.size());
            return;
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
        return "<html><body style='width:520px'><b>Not created.</b> " + escaped + "</body></html>";
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
            JOptionPane.showMessageDialog(parent,
                    "Created " + fileCount + " files in\n"
                            + config.getProjectRoot().getAbsolutePath()
                            + "\n\nEditing " + config.getEditFile().getName()
                            + " - the file you work in. It is on the OntoBoard tab now."
                            + "\n\nNext: OntoBoard > Start here: the guide... walks you through"
                            + " the rest. To check the ontology at any time, OntoBoard > Project"
                            + " > Build... and choose test. Nothing else needs installing.",
                    "Project created", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception couldNotOpen) {
            JOptionPane.showMessageDialog(parent,
                    "The project was created at\n"
                            + config.getProjectRoot().getAbsolutePath()
                            + "\n\nbut Protege could not open the edit file automatically:\n"
                            + couldNotOpen.getMessage()
                            + "\n\nOpen it manually: src/ontology/"
                            + config.getEditFile().getName(),
                    "Project created, not opened", JOptionPane.WARNING_MESSAGE);
        }
    }
}
