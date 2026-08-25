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
        JTextField iri = new JTextField();
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

        JLabel hint = new JLabel("<html><i>ID becomes file names and IRIs - lowercase, "
                + "no spaces, e.g. <b>mwo</b>. Leave Base IRI empty to use the OBO "
                + "convention.</i></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(fields, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(560, 230));

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
            JOptionPane.showMessageDialog(parent, invalid.getMessage(),
                    "Cannot create the project", JOptionPane.WARNING_MESSAGE);
            return;
        } catch (RuntimeException failure) {
            JOptionPane.showMessageDialog(parent,
                    "Could not write the project: " + failure.getMessage(),
                    "Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        openGenerated(parent, modelManager, config, written.size());
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
                            + ".\nRun 'make reason' or 'make report' from src/ontology "
                            + "(needs make and ROBOT on PATH).",
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
