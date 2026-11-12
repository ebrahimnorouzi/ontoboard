package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkYaml;
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
 * <p>In an ODK project (1.153.0) the test is one of ODK's checks:
 * {@code src/sparql/<name>-violation.sparql}, and its name in the configuration's
 * {@code robot_report: custom_sparql_checks} - a {@code .rq} file there is one ODK never runs.
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
                + "src/sparql/ as one of ODK's checks, and listed in the project's configuration."
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

        String saved = "";
        if (root != null) {
            File file = testFileFor(root, odk, question.getText());
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
            saved = save(root, odk, question.getText(), sparql).getMessage();
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
        JOptionPane.showMessageDialog(getOWLWorkspace(), shown != null ? shown : saved + answer,
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
        private final boolean listed;
        private final String message;

        Saved(File file, boolean written, boolean listed, String message) {
            this.file = file;
            this.written = written;
            this.listed = listed;
            this.message = message;
        }

        /** Where the test is, or was to be. */
        File getFile() {
            return file;
        }

        boolean isWritten() {
            return written;
        }

        /** In an ODK project: the configuration's list names the check. */
        boolean isListed() {
            return listed;
        }

        /** What the dialog says before the test's answer: plain sentences, ending in a blank line. */
        String getMessage() {
            return message;
        }
    }

    /**
     * Whether the ontology is the edit file of an ODK project - built by ODK's Makefile, which
     * runs the checks its configuration lists and nothing else in src/sparql. A project of
     * OntoBoard's older layout runs every .rq file there instead.
     */
    static boolean inAnOdkProject(File editFile) {
        return editFile != null && OdkRelease.isOdkProject(editFile);
    }

    /**
     * Where a question's test goes: {@code src/sparql/<name>-violation.sparql}, one of ODK's
     * checks, in an ODK project; {@code src/sparql/<name>.rq} in a project of the older layout.
     */
    static File testFileFor(File root, boolean odk, String question) {
        return odk ? OdkChecks.fileFor(root, QuestionTests.checkNameFor(question))
                : new File(new File(root, "src/sparql"), QuestionTests.fileNameFor(question));
    }

    /**
     * Writes the test, and in an ODK project then lists it in the configuration.
     *
     * <p>The file first: it is the user's work, and a configuration that cannot take the name
     * (changed on disk, unreadable) must not cost them the test. When the second half fails the
     * message says which half happened, and the file stays.
     */
    static Saved save(File root, boolean odk, String question, String sparql) {
        File file = testFileFor(root, odk, question);
        try {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), sparql.getBytes(StandardCharsets.UTF_8));
        } catch (IOException cannotWrite) {
            return new Saved(file, false, false,
                    "Could not save it (" + cannotWrite.getMessage() + ").\n\n");
        }
        if (!odk) {
            return new Saved(file, true, false, "Saved as src/sparql/" + file.getName() + ".\n\n");
        }
        return listed(root, file, QuestionTests.checkNameFor(question));
    }

    /** The second half, in an ODK project: the check's name in the configuration's list. */
    private static Saved listed(File root, File file, String name) {
        String path = "src/sparql/" + file.getName();
        File yaml = OdkBuildSettings.yamlIn(new File(root, "src/ontology"));
        if (yaml == null) {
            return new Saved(file, true, false, notListed(path, "the project's configuration",
                    name, "There is no single <id>-odk.yaml in src/ontology to list it in."));
        }
        try {
            String before = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
            String after = OdkChecks.add(before, name);
            if (after.equals(before)) {
                return new Saved(file, true, true, "Saved as " + path + ". " + yaml.getName()
                        + " lists it already (robot_report: custom_sparql_checks), which is how "
                        + "ODK's test finds it.\n\n");
            }
            OdkBuildSettings.writeIfUnchanged(yaml, before, after);
            String message = savedMessage(path, yaml.getName());
            if (!OdkChecks.isListed(before)) {
                message += "\n" + defaultsListed(before, yaml.getName());
            }
            return new Saved(file, true, true, message + "\n\n");
        } catch (OdkYaml.UnreadableException | IOException cannot) {
            return new Saved(file, true, false,
                    notListed(path, yaml.getName(), name, cannot.getMessage()));
        }
    }

    /**
     * What the dialog says when the check is saved and listed: where it is, where it is listed,
     * and what makes ODK run it.
     */
    static String savedMessage(String testPath, String configurationName) {
        return "Saved as " + testPath + " and listed in " + configurationName
                + "\n(robot_report: custom_sparql_checks), which is how ODK's test finds it.\n"
                + "ODK's Makefile is written from that file: it runs the new check after "
                + "OntoBoard > Project > Update project files...";
    }

    /**
     * Where the configuration had no list, ODK's defaults are written into it before the new
     * name, since a list replaces them: said, because it is a change nobody asked for in words.
     */
    private static String defaultsListed(String before, String configurationName) {
        String defaults = String.join(", ", OdkChecks.declaredIn(before));
        boolean block = false;
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(before)) {
            block |= "robot_report".equals(entry.getPath());
        }
        return block
                ? configurationName + " had no list of checks, so ODK ran its own: they are "
                        + "listed before the new one now, so they still run (" + defaults + ")."
                : configurationName + " had no robot_report block, so ODK ran its own checks: "
                        + "the block is added with them listed before the new one, so they still "
                        + "run (" + defaults + "), and with use_base_iris: TRUE, as ODK has it "
                        + "without a block.";
    }

    private static String notListed(String path, String configurationName, String name,
            String why) {
        return "Saved as " + path + ", but not listed in " + configurationName + ": ODK's test "
                + "runs only the checks listed there, under robot_report: custom_sparql_checks.\n"
                + why + "\nAdd " + name + " to that list yourself, or ask the question again once "
                + "the file can be changed.\n\n";
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
