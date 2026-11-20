package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.Component;
import java.io.File;
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
     * Before a project opens: when its ID ranges are still ODK's placeholders and none is this
     * person's, asks which is theirs - the board's question - and writes their name into it.
     * Protege reads the ranges as it opens the project and asked "No Id Range Found For Current
     * User", and again each time the edit file was read again: after every pattern imported
     * (1.155.0).
     *
     * @param choose given the choices, the one chosen; null when none was
     * @return the placeholder claimed; null when nothing was asked, or nothing chosen
     * @throws java.io.IOException when the ranges file cannot be read as UTF-8, or written
     * @throws IllegalArgumentException when the name cannot be written into it
     */
    static String claimBeforeOpening(File editFile, String editor,
            java.util.function.Function<String[], String> choose) throws java.io.IOException {
        TermMinter minter = TermMinter.forOntologyFile(editFile, editor);
        if (!minter.rangesNameNobodyYet()) {
            return null;
        }
        java.util.List<String> placeholders = TermMinter.claimable(minter.getRanges());
        String[] choices = new String[placeholders.size()];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = TermMinter.claimChoice(minter.getRanges(), placeholders.get(i));
        }
        String chosen = choose.apply(choices);
        int at = chosen == null ? -1 : java.util.Arrays.asList(choices).indexOf(chosen);
        if (at < 0) {
            return null;
        }
        minter.claim(placeholders.get(at));
        return placeholders.get(at);
    }

    /** The question before the project opens, in words; what goes wrong is said, and it opens. */
    private static void claimARangeFirst(Component parent, File editFile) {
        final String editor = de.fizkarlsruhe.ise.ontoboard.sheet.IriGenerator.editorName(editFile);
        final File rangesFile = TermMinter.findRangesFile(editFile);
        if (rangesFile == null) {
            return;
        }
        try {
            claimBeforeOpening(editFile, editor, choices ->
                    de.fizkarlsruhe.ise.ontoboard.views.RangeChoice.ask(parent,
                            TermMinter.onLines(openingQuestion(editor, rangesFile.getName()), 90),
                            choices));
        } catch (java.io.IOException | RuntimeException notClaimed) {
            JOptionPane.showMessageDialog(parent, ProjectWizard.wrapped("Your name was not "
                    + "written into " + rangesFile.getName() + ": " + notClaimed.getMessage()
                    + "\n\nThe project opens, and Protege asks which range is yours."),
                    "Range not claimed", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** What is asked before a project opens whose ID ranges name nobody. */
    static String openingQuestion(String editor, String rangesFileName) {
        return "This project's ID ranges are still the two placeholders ODK seeds a project with,"
                + " allocated to nobody yet. Which range is yours?\n\n"
                + "OntoBoard writes \"" + editor + "\" into the one you choose, in "
                + rangesFileName + ", before the project opens, so that Protege finds your range "
                + "and new terms are numbered from it. Commit " + rangesFileName + " so that "
                + "nobody else claims the same range.\n\n"
                + "Cancel writes nothing; the project opens, and Protege asks which range is yours.";
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
        org.semanticweb.owlapi.model.OWLOntology already = openFrom(
                editorKit.getModelManager().getOWLOntologyManager(), project.getEditFile());
        if (already != null) {
            if (!already.equals(editorKit.getModelManager().getActiveOntology())) {
                editorKit.getModelManager().setActiveOntology(already);
            }
            ProjectWizard.showTheBoard(parent);
            JOptionPane.showMessageDialog(parent, ProjectWizard.wrapped(alreadyOpen(
                    project.getTitle(), project.getEditFile())), "Already open",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        claimARangeFirst(parent, project.getEditFile());
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
            // Protégé keeps an ontology it already has under the same IRI and opens nothing new,
            // yet answers true. A tester who started the project again in another folder was told
            // "Project opened" and went on editing the old one.
            java.io.File open = documentOf(editorKit);
            if (open != null && !sameFile(open, project.getEditFile())) {
                ProjectWizard.showTheBoard(parent);
                JOptionPane.showMessageDialog(parent,
                        ProjectWizard.wrapped("Not opened: an ontology with the same IRI is already"
                                + " open, from\n" + open.getAbsolutePath()
                                + "\n\nProtege kept it, so you are still working in that file. To"
                                + " work in " + project.getEditFile().getAbsolutePath()
                                + ", quit Protege, start it again, and open the project from here."),
                        "Not opened", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // The board in front, as New ODK project... does, because the message below points at
            // it - and Active ontology was the tab in front after opening.
            ProjectWizard.showTheBoard(parent);
            JOptionPane.showMessageDialog(parent,
                    ProjectWizard.wrapped("Opened " + project.getTitle() + "\n\n"
                            + project.getEditFile().getAbsolutePath()
                            + "\n\nTo add a class of your own, right-click its superclass on the "
                            + "board and choose New subclass..., or double-click empty board: it "
                            + "is numbered from your ID range."),
                    "Project opened", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception couldNotLoad) {
            JOptionPane.showMessageDialog(parent,
                    "Found " + project.getEditFile().getName()
                            + " but Protege could not load it:\n" + couldNotLoad.getMessage(),
                    "Could not open", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * The ontology this window has open from the project's edit file, or null. Opened again,
     * Protégé listed the eleven ontologies of nfdicore it had ("They have not been
     * replaced/overwritten") and this then said "Project opened" for nothing opened (Phase 7,
     * 2026-11-20).
     */
    static org.semanticweb.owlapi.model.OWLOntology openFrom(
            org.semanticweb.owlapi.model.OWLOntologyManager manager, java.io.File editFile) {
        return ImportLinks.readFrom(manager, editFile);
    }

    /** What the person reads when the project is open in this window already. */
    static String alreadyOpen(String title, java.io.File editFile) {
        return title + " is already open in this window, from\n" + editFile.getAbsolutePath()
                + "\n\nNothing was read again. File > Reload reads it again from its file.";
    }

    /** The file the active ontology was loaded from, or null. */
    private static java.io.File documentOf(OWLEditorKit editorKit) {
        try {
            org.semanticweb.owlapi.model.OWLOntology active =
                    editorKit.getModelManager().getActiveOntology();
            org.semanticweb.owlapi.model.IRI document = active == null ? null
                    : active.getOWLOntologyManager().getOntologyDocumentIRI(active);
            return document != null && "file".equalsIgnoreCase(document.getScheme())
                    ? new java.io.File(document.toURI()) : null;
        } catch (RuntimeException cannotTell) {
            return null;
        }
    }

    static boolean sameFile(java.io.File one, java.io.File other) {
        try {
            return one.getCanonicalFile().equals(other.getCanonicalFile());
        } catch (java.io.IOException cannotTell) {
            return one.getAbsoluteFile().equals(other.getAbsoluteFile());
        }
    }
}
