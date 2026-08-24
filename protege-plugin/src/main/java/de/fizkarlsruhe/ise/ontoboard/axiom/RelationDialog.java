package de.fizkarlsruhe.ise.ontoboard.axiom;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Asks which property an edge uses and how it should be read in OWL.
 *
 * <p>Both questions are asked together because they are one decision: "Person worksFor
 * Organization" is meaningless until you also say whether that means every Person works for
 * some Organization, or only Persons work for Organizations, and so on. The candidate's
 * plain-language explanation is shown so a domain expert can answer without knowing
 * description logic.
 *
 * <p>Swing, so untestable headlessly. The decisions it collects are applied by
 * {@link EdgeAxioms}, which is fully tested.
 */
public final class RelationDialog {

    /** What the user chose, or {@code null} if they cancelled. */
    public static final class Choice {
        private final OWLObjectProperty property;
        private final String newPropertyName;
        private final EdgeAxioms.Candidate candidate;

        Choice(OWLObjectProperty property, String newPropertyName,
                EdgeAxioms.Candidate candidate) {
            this.property = property;
            this.newPropertyName = newPropertyName;
            this.candidate = candidate;
        }

        /** An existing property, or {@code null} when {@link #getNewPropertyName()} is set. */
        public OWLObjectProperty getProperty() {
            return property;
        }

        /** A name to mint a new property from, or {@code null} when an existing one was picked. */
        public String getNewPropertyName() {
            return newPropertyName;
        }

        public EdgeAxioms.Candidate getCandidate() {
            return candidate;
        }
    }

    private static final String CREATE_NEW = "➕  new property…";

    private RelationDialog() {
    }

    /**
     * @return the choice, or {@code null} if the user cancelled - in which case the caller
     *     must apply no changes at all
     */
    public static Choice ask(Component parent, OWLOntology ontology, String sourceLabel,
            String targetLabel) {

        List<OWLObjectProperty> properties =
                new ArrayList<OWLObjectProperty>(ontology.getObjectPropertiesInSignature());
        // Sorted so the list does not reshuffle between invocations; OWL API returns an
        // unordered set.
        Collections.sort(properties, new Comparator<OWLObjectProperty>() {
            @Override
            public int compare(OWLObjectProperty a, OWLObjectProperty b) {
                return DisplayLabels.forEntity(ontology, a)
                        .compareToIgnoreCase(DisplayLabels.forEntity(ontology, b));
            }
        });

        List<String> propertyChoices = new ArrayList<String>();
        propertyChoices.add(CREATE_NEW);
        for (OWLObjectProperty property : properties) {
            propertyChoices.add(DisplayLabels.forEntity(ontology, property));
        }

        JComboBox<String> propertyBox = new JComboBox<String>(
                new DefaultComboBoxModel<String>(
                        propertyChoices.toArray(new String[propertyChoices.size()])));
        if (properties.size() > 0) {
            propertyBox.setSelectedIndex(1);
        }

        JTextField newName = new JTextField();
        newName.setEnabled(properties.isEmpty());

        JComboBox<EdgeAxioms.Candidate> candidateBox =
                new JComboBox<EdgeAxioms.Candidate>(EdgeAxioms.Candidate.values());
        candidateBox.setSelectedItem(EdgeAxioms.DEFAULT);

        final JLabel explanation = new JLabel();
        explanation.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        updateExplanation(explanation, candidateBox, sourceLabel, targetLabel);
        candidateBox.addActionListener(
                e -> updateExplanation(explanation, candidateBox, sourceLabel, targetLabel));
        propertyBox.addActionListener(
                e -> newName.setEnabled(CREATE_NEW.equals(propertyBox.getSelectedItem())));

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("Property:"));
        fields.add(propertyBox);
        fields.add(new JLabel("New property name:"));
        fields.add(newName);
        fields.add(new JLabel("Read this edge as:"));
        fields.add(candidateBox);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(sourceLabel + "  →  " + targetLabel), BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        panel.add(explanation, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(460, 190));

        int result = JOptionPane.showConfirmDialog(parent, panel, "Create relation",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        EdgeAxioms.Candidate candidate =
                (EdgeAxioms.Candidate) candidateBox.getSelectedItem();
        if (CREATE_NEW.equals(propertyBox.getSelectedItem())) {
            String typed = newName.getText() == null ? "" : newName.getText().trim();
            if (typed.isEmpty()) {
                return null;
            }
            return new Choice(null, typed, candidate);
        }
        return new Choice(properties.get(propertyBox.getSelectedIndex() - 1), null, candidate);
    }

    private static void updateExplanation(JLabel label,
            JComboBox<EdgeAxioms.Candidate> box, String source, String target) {
        EdgeAxioms.Candidate candidate = (EdgeAxioms.Candidate) box.getSelectedItem();
        if (candidate == null) {
            label.setText("");
            return;
        }
        label.setText("<html><i>" + candidate.getExplanation()
                .replace("A", source).replace("B", target) + "</i></html>");
    }
}
