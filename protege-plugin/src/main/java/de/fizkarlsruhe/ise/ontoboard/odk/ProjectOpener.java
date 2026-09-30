package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.Component;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import org.protege.editor.owl.OWLEditorKit;

/**
 * Opening an ODK repository somebody already has.
 *
 * <p>Extracted from {@code SchemaCanvasView} so a menu item can run it. It was written as a
 * private method there and wired to one button on the start card, which meant the only way to
 * reach it was to have an empty board: open anything at all and the action a user was looking
 * for - "open my project" - was nowhere in the menu that offers "New ODK project..." and "Open
 * from GitHub...". The two neighbours it belongs between.
 *
 * <p>Static and parameterised for the same reason {@link ProjectWizard} is: the menu action and
 * the start panel then run one copy of this, rather than the action re-implementing it or
 * reaching into the view's lifecycle.
 */
public final class ProjectOpener {

    private ProjectOpener() {
    }

    /**
     * Asks for a project folder and opens the file the user is meant to edit.
     *
     * <p>Users think in terms of "my ontology repo", not "the file at
     * src/ontology/foo-edit.owl", and picking the generated release file by mistake means their
     * edits get overwritten by the next build. So this takes a folder and works out what to
     * open, refusing rather than guessing when it cannot tell.
     *
     * @param parent what the dialogs are centred on; may be null
     * @param editorKit the kit whose workspace should end up showing the ontology
     */
    public static void open(Component parent, OWLEditorKit editorKit) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select an ODK project folder");
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        OdkProjectLoader.Detected project;
        try {
            project = OdkProjectLoader.detect(chooser.getSelectedFile());
        } catch (RuntimeException notAProject) {
            JOptionPane.showMessageDialog(parent, notAProject.getMessage(),
                    "Not an ODK project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            // handleLoadFrom, not loadOntologyFromOntologyDocument. The latter loads the
            // ontology into the OWLOntologyManager and stops there: Protege's OWLModelManager
            // never hears about it, so it does not become the active ontology, does not appear
            // in the ontology list, and the class hierarchy carries on showing whatever was
            // open before. The dialog said "Project opened" and nothing appeared, which is
            // exactly what was reported. handleLoadFrom is the call Protege's own File > Open
            // makes.
            if (!editorKit.handleLoadFrom(project.getEditFile().toURI())) {
                JOptionPane.showMessageDialog(parent,
                        "Protege declined to open " + project.getEditFile().getName()
                                + ". It may already be open in another window.",
                        "Not opened", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JOptionPane.showMessageDialog(parent,
                    "Opened " + project.getTitle() + "\n\n"
                            + project.getEditFile().getAbsolutePath()
                            + "\n\nAdd entities from the class hierarchy, or double-click "
                            + "the board to create one.",
                    "Project opened", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception couldNotLoad) {
            JOptionPane.showMessageDialog(parent,
                    "Found " + project.getEditFile().getName()
                            + " but Protege could not load it:\n" + couldNotLoad.getMessage(),
                    "Could not open", JOptionPane.ERROR_MESSAGE);
        }
    }
}
