package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkQuestions;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Project &gt; Write a test from a question... - a competency question turned into a test, with
 * the terms picked from lists by their labels and no SPARQL to write.
 *
 * <p>The guide's "Turn your questions into tests" step used to hand people a stub to fill with
 * SPARQL, which is where a student, or anybody new to ODK, stopped. {@link QuestionTests} has the
 * shapes; this writes the chosen one to {@code src/sparql/}, where Build &gt; test runs it from
 * then on, and runs it at once so the answer is there before the dialog has closed.
 *
 * <p>The test is {@code src/sparql/<name>.rq} in every project, and it is asked of the
 * ontology with its imports - as the dialog asks it for its answer. In an ODK project
 * (1.153.0) that takes a rule of the project's own: ODK's checks, the ones its configuration
 * lists, read the edit file without its imports, where a question about an imported term
 * has no answer. {@link OdkQuestions} keeps that rule in the project's own makefile, and
 * saving a question sees to it that it is there.
 */
public class QuestionTestAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    /** A term in a list: its label, the IRI behind it, and whether it is a data property. */
    private static final class Choice implements Comparable<Choice> {
        final IRI iri;
        final String label;
        final boolean data;

        Choice(IRI iri, String label) {
            this(iri, label, false);
        }

        Choice(IRI iri, String label, boolean data) {
            this.iri = iri;
            this.label = label;
            this.data = data;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public int compareTo(Choice other) {
            return label.compareToIgnoreCase(other.label);
        }
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        File document = documentOf(ontology);
        File root = projectRoot(document);
        boolean odk = root != null && inAnOdkProject(document);
        List<Choice> classes = new ArrayList<Choice>();
        List<Choice> properties = new ArrayList<Choice>();
        for (OWLEntity entity : ontology.getSignature(Imports.INCLUDED)) {
            if (entity.isBuiltIn()) {
                continue;
            }
            String label = DisplayLabels.forEntity(ontology, entity);
            if (entity.isOWLClass()) {
                classes.add(new Choice(entity.getIRI(), label));
            } else if (entity.isOWLObjectProperty() || entity.isOWLDataProperty()) {
                properties.add(new Choice(entity.getIRI(), label, entity.isOWLDataProperty()));
            }
        }
        Collections.sort(classes);
        Collections.sort(properties);
        if (classes.isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "The ontology has no classes yet, so "
                    + "there is nothing to ask a question about.", "Write a test",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JComboBox<QuestionTests.Template> template =
                new JComboBox<QuestionTests.Template>(QuestionTests.Template.values());
        JComboBox<Choice> cls = new JComboBox<Choice>(classes.toArray(new Choice[0]));
        JComboBox<Choice> second = new JComboBox<Choice>(classes.toArray(new Choice[0]));
        JComboBox<Choice> property = new JComboBox<Choice>(properties.toArray(new Choice[0]));
        JSpinner most = new JSpinner(new SpinnerNumberModel(1, 0, 1000, 1));
        JTextField question = new JTextField(40);
        Runnable fit = () -> {
            QuestionTests.Template t = (QuestionTests.Template) template.getSelectedItem();
            property.setEnabled(t.needsProperty());
            second.setEnabled(t.needsSecondClass());
            most.setEnabled(t == QuestionTests.Template.NO_MORE_THAN);
            question.setText(t.getWords()
                    .replace("<class>", String.valueOf(cls.getSelectedItem()))
                    .replace("<second class>", String.valueOf(second.getSelectedItem()))
                    .replace("<property>", String.valueOf(property.getSelectedItem()))
                    .replace(" N ", " " + most.getValue() + " ") + "?");
        };
        template.addActionListener(e -> fit.run());
        cls.addActionListener(e -> fit.run());
        second.addActionListener(e -> fit.run());
        property.addActionListener(e -> fit.run());
        most.addChangeListener(e -> fit.run());
        fit.run();

        JPanel form = new JPanel(new GridBagLayout());
        row(form, 0, "The rule", template);
        row(form, 1, "Class", cls);
        row(form, 2, "Property", property);
        row(form, 3, "Second class", second);
        row(form, 4, "N", most);
        row(form, 5, "The question, in words", question);
        row(form, 6, "", new JLabel("<html>The test lists what <i>breaks</i> the rule; an empty "
                + "answer passes.<br>" + (root == null ? "This ontology is not in a project, so "
                + "the test is shown to copy rather than saved." : odk ? "It is saved in "
                + "src/sparql/, and the project's test asks it of the ontology with its "
                + "imports from then on."
                : "It is saved in src/sparql/, "
                + "and Build and release > Build... runs it from then on.") + "</html>"));
        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), form, "Write a test from a question",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        QuestionTests.Template chosen = (QuestionTests.Template) template.getSelectedItem();
        if (chosen.needsProperty() && property.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "That rule needs a property, and the "
                    + "ontology has none yet.", "Write a test", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String refusal = refusalFor(chosen,
                chosen.needsProperty() && ((Choice) property.getSelectedItem()).data);
        if (refusal != null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), refusal, "Write a test",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String sparql = QuestionTests.query(chosen, question.getText(),
                ((Choice) cls.getSelectedItem()).iri,
                chosen.needsProperty() ? ((Choice) property.getSelectedItem()).iri : null,
                chosen.needsSecondClass() ? ((Choice) second.getSelectedItem()).iri : null,
                (Integer) most.getValue());

        Saved kept = null;
        if (root != null) {
            File file = testFileFor(root, question.getText());
            // Replaced when asked to, not refused: a test is named after its question, so the way
            // to change a test is to ask the same question again - and nothing in Protege removes
            // one, so a refusal left a test whose class had been deleted failing every build, with
            // the file in src/sparql as the only way out.
            Object[] choices = {"Replace it", "Cancel"};
            if (file.exists() && JOptionPane.showOptionDialog(getOWLWorkspace(), file.getName()
                    + " is there already,\nfrom a question worded the same way. Replace it with "
                    + "this test?", "Replace the test?", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]) != 0) {
                return;
            }
            kept = save(root, odk, question.getText(), sparql);
        }
        String answer;
        try {
            List<String> broken = new ArrayList<String>();
            for (List<String> found : SparqlQuery.run(ontology, sparql).getRows()) {
                broken.add(labelOf(ontology, found.isEmpty() ? "" : found.get(0)));
            }
            answer = broken.isEmpty() ? "Passes: nothing breaks the rule."
                    : broken.size() + (broken.size() == 1 ? " term breaks" : " terms break")
                            + " the rule:\n  " + String.join("\n  ",
                                    broken.subList(0, Math.min(30, broken.size())))
                            + (broken.size() > 30 ? "\n  ..." : "");
        } catch (RuntimeException cannotRun) {
            answer = "It could not be run: " + cannotRun.getMessage();
        }
        JComponent shown = root == null ? new javax.swing.JScrollPane(new javax.swing.JTextArea(
                sparql + "\n# " + answer.replace("\n", "\n# "), 16, 70)) : null;
        String saved = kept == null ? "" : kept.getMessage();
        JOptionPane.showMessageDialog(getOWLWorkspace(), shown != null ? shown
                // An ODK project's sentences are long: at a width one reads.
                : odk ? ReleaseAction.wrapped(saved + answer) : saved + answer,
                "Write a test from a question", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Why this rule cannot be written for this property, or null when it can (1.135.2 review).
     * "Every kind of X has some P that is a Y" asks for a filler that is a kind of Y: a data
     * property's value is never one, so the test would stay red for ever.
     */
    static String refusalFor(QuestionTests.Template template, boolean dataProperty) {
        if (template == QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS && dataProperty) {
            return "That rule asks what the property points to, and a data property points to a "
                    + "value, never to a class - so the test could never pass. Pick an object "
                    + "property, or \"Every kind of <class> has some <property>\" for a data "
                    + "property.";
        }
        return null;
    }

    /** What saving a question's test did, and what the dialog says about it. */
    static final class Saved {
        private final File file;
        private final boolean written;
        private final boolean asked;
        private final String message;

        Saved(File file, boolean written, boolean asked, String message) {
            this.file = file;
            this.written = written;
            this.asked = asked;
            this.message = message;
        }

        /** Where the test is, or was to be. */
        File getFile() {
            return file;
        }

        boolean isWritten() {
            return written;
        }

        /**
         * Whether the project's test asks it from now on: in an ODK project, the rule that
         * asks the project's questions is in the project's own makefile; in a project of the
         * older layout, that layout's own Makefile asks every .rq it finds.
         */
        boolean isAsked() {
            return asked;
        }

        /** What the dialog says before the test's answer: plain sentences, ending in a blank line. */
        String getMessage() {
            return message;
        }
    }

    /**
     * Whether the ontology is the edit file of an ODK project - built by ODK's Makefile, whose
     * own checks read the edit file without its imports, so that the project's questions need
     * a rule of their own. A project of OntoBoard's older layout runs every .rq file with the
     * imports merged.
     */
    static boolean inAnOdkProject(File editFile) {
        return editFile != null && OdkRelease.isOdkProject(editFile);
    }

    /** Where a question's test goes, in every project: {@code src/sparql/<name>.rq}. */
    static File testFileFor(File root, String question) {
        return new File(new File(root, "src/sparql"), QuestionTests.fileNameFor(question));
    }

    /**
     * Writes the test, and in an ODK project then sees to the rule that asks it.
     *
     * <p>The file first: it is the person's work, and a makefile that cannot be written must
     * not cost them the test. When the second half fails the message says which half
     * happened, and the file stays.
     */
    static Saved save(File root, boolean odk, String question, String sparql) {
        File file = testFileFor(root, question);
        try {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), sparql.getBytes(StandardCharsets.UTF_8));
        } catch (IOException cannotWrite) {
            return new Saved(file, false, false,
                    "Could not save it (" + cannotWrite.getMessage() + ").\n\n");
        }
        String path = "src/sparql/" + file.getName();
        if (!odk) {
            return new Saved(file, true, true, "Saved as " + path + ".\n\n");
        }
        File own = OdkQuestions.ownMakefileOf(root);
        String makefile = own == null ? "the project's own makefile" : own.getName();
        try {
            boolean changed = OdkQuestions.isChangedIn(root);
            File wrote = OdkQuestions.ensure(root);
            return new Saved(file, true, true, "Saved as " + path + ". ODK's test asks it of "
                    + "the ontology with its imports" + (wrote == null
                            ? " (the rule for the project's questions in " + makefile + ")."
                            : changed ? ": the rule that asks the project's questions was "
                                    + "written again in " + makefile + ", where it had been "
                                    + "changed."
                            : ": the rule that asks the project's questions was added to "
                                    + makefile + ".") + "\n\n");
        } catch (IOException | RuntimeException notWritten) {
            String why = notWritten.getMessage() == null
                    ? notWritten.getClass().getSimpleName() : notWritten.getMessage();
            return new Saved(file, true, false, "Saved as " + path + ", but the rule that "
                    + "asks the project's questions could not be written into " + makefile
                    + " (" + why + ").\nUntil it is there, ODK's test does not ask this "
                    + "question.\n\n");
        }
    }

    private static String labelOf(OWLOntology ontology, String iri) {
        for (OWLEntity entity : ontology.getEntitiesInSignature(IRI.create(iri),
                Imports.INCLUDED)) {
            return DisplayLabels.forEntity(ontology, entity);
        }
        return iri;
    }

    private static void row(JPanel form, int y, String label, JComponent field) {
        GridBagConstraints at = new GridBagConstraints();
        at.gridy = y;
        at.insets = new Insets(3, 3, 3, 3);
        at.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), at);
        at.gridx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;
        form.add(field, at);
    }

    /** The open ontology's own file, or null when it is not a file. */
    private File documentOf(OWLOntology ontology) {
        try {
            java.net.URI document = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(document.getScheme()) ? new File(document) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }

    /** The project around the ontology's file, or null when it is not in one. */
    private static File projectRoot(File document) {
        if (document == null) {
            return null;
        }
        File root = ReleaseAction.projectRootOf(document);
        return root != null && new File(root, "src/ontology").isDirectory() ? root : null;
    }
}
