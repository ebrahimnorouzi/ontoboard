package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.File;
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
 */
public class QuestionTestAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    /** A term in a list: its label, and the IRI behind it. */
    private static final class Choice implements Comparable<Choice> {
        final IRI iri;
        final String label;

        Choice(IRI iri, String label) {
            this.iri = iri;
            this.label = label;
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
        File root = projectRoot(ontology);
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
                properties.add(new Choice(entity.getIRI(), label));
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
                + "the test is shown to copy rather than saved." : "It is saved in src/sparql/, "
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
        String sparql = QuestionTests.query(chosen, question.getText(),
                ((Choice) cls.getSelectedItem()).iri,
                chosen.needsProperty() ? ((Choice) property.getSelectedItem()).iri : null,
                chosen.needsSecondClass() ? ((Choice) second.getSelectedItem()).iri : null,
                (Integer) most.getValue());

        String saved = "";
        if (root != null) {
            File file = new File(new File(root, "src/sparql"),
                    QuestionTests.fileNameFor(question.getText()));
            if (file.exists()) {
                JOptionPane.showMessageDialog(getOWLWorkspace(), file.getName() + " is there "
                        + "already, and this would replace it. Word the question differently, or "
                        + "remove the old test first.", "Not saved",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                file.getParentFile().mkdirs();
                Files.write(file.toPath(), sparql.getBytes(StandardCharsets.UTF_8));
                saved = "Saved as src/sparql/" + file.getName() + ".\n\n";
            } catch (java.io.IOException cannotWrite) {
                saved = "Could not save it (" + cannotWrite.getMessage() + ").\n\n";
            }
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

    /** The project around the open ontology, or null when it is not in one. */
    private File projectRoot(OWLOntology ontology) {
        try {
            java.net.URI document = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            if (!"file".equalsIgnoreCase(document.getScheme())) {
                return null;
            }
            File root = ReleaseAction.projectRootOf(new File(document));
            return root != null && new File(root, "src/ontology").isDirectory() ? root : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
