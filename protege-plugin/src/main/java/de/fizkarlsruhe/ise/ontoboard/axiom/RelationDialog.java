package de.fizkarlsruhe.ise.ontoboard.axiom;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Asks which property an edge uses and how it should be read in OWL.
 *
 * <p>Both questions are asked together because they are one decision: "Person worksFor
 * Organization" is meaningless until you also say whether that means every Person works for
 * some Organization, or only Persons work for Organizations, and so on. The candidate's
 * plain-language explanation is shown so a domain expert can answer without knowing
 * description logic.
 *
 * <p>The sentence names the property chosen, and follows every change to it; under it, the dialog
 * says when the relation does not fit the property's domain or range, and when it starts at a term
 * an import brings (1.135.1, Windows study rehearsal). The sentence read "Every boiled egg is
 * related to some egg boiling" whatever property was picked, and the egg guide's slips went
 * through without a word, to fail a build several tasks later. The form is built apart from
 * showing it, so a test can read it; the decisions it collects are applied by
 * {@link EdgeAxioms}.
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

    /** Dark orange: read as a warning, and 4.5:1 or better on the dialog's background. */
    private static final Color WARNING = new Color(0xA0, 0x40, 0x00);

    /** The dialog's form, apart from showing it. */
    static final class Form {
        final JPanel panel;
        final JComboBox<String> propertyBox;
        final JTextField newName;
        final JComboBox<EdgeAxioms.Candidate> candidateBox;
        final JLabel sentence;
        final JLabel warning;
        final List<OWLObjectProperty> properties;

        Form(JPanel panel, JComboBox<String> propertyBox, JTextField newName,
                JComboBox<EdgeAxioms.Candidate> candidateBox, JLabel sentence, JLabel warning,
                List<OWLObjectProperty> properties) {
            this.panel = panel;
            this.propertyBox = propertyBox;
            this.newName = newName;
            this.candidateBox = candidateBox;
            this.sentence = sentence;
            this.warning = warning;
            this.properties = properties;
        }

        /** The existing property picked, or null for a new one. */
        OWLObjectProperty chosen() {
            int index = propertyBox.getSelectedIndex();
            return index <= 0 ? null : properties.get(index - 1);
        }
    }

    private RelationDialog() {
    }

    /**
     * @return the choice, or {@code null} if the user cancelled - in which case the caller
     *     must apply no changes at all
     */
    public static Choice ask(Component parent, OWLOntology ontology, OWLClass source,
            OWLClass target) {
        Form form = build(ontology, source, target);
        int result = JOptionPane.showConfirmDialog(parent, form.panel, "Create relation",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        EdgeAxioms.Candidate candidate =
                (EdgeAxioms.Candidate) form.candidateBox.getSelectedItem();
        if (CREATE_NEW.equals(form.propertyBox.getSelectedItem())) {
            String typed = form.newName.getText() == null ? "" : form.newName.getText().trim();
            if (typed.isEmpty()) {
                return null;
            }
            return new Choice(null, typed, candidate);
        }
        return new Choice(form.chosen(), null, candidate);
    }

    /** The form for a relation from {@code source} to {@code target}. */
    static Form build(final OWLOntology ontology, final OWLClass source, final OWLClass target) {
        final String sourceLabel = DisplayLabels.forEntity(ontology, source);
        final String targetLabel = DisplayLabels.forEntity(ontology, target);
        List<OWLObjectProperty> properties = propertiesOffered(ontology);

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

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        JLabel propertyCaption = new JLabel("Property:");
        propertyCaption.setLabelFor(propertyBox);
        fields.add(propertyCaption);
        fields.add(propertyBox);
        JLabel nameCaption = new JLabel("New property name:");
        nameCaption.setLabelFor(newName);
        fields.add(nameCaption);
        fields.add(newName);
        JLabel readingCaption = new JLabel("Read this edge as:");
        readingCaption.setLabelFor(candidateBox);
        fields.add(readingCaption);
        fields.add(candidateBox);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(new JLabel(sourceLabel + "  →  " + targetLabel), BorderLayout.NORTH);
        top.add(fields, BorderLayout.CENTER);

        // Below the fields, and as tall as they need: a sentence pinned into a fixed panel took its
        // height from the fields, as the ODK form's message did.
        JLabel sentence = new JLabel();
        sentence.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        sentence.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel warning = new JLabel();
        warning.setForeground(WARNING);
        warning.setAlignmentX(Component.LEFT_ALIGNMENT);
        warning.setVisible(false);
        JPanel said = new JPanel();
        said.setLayout(new BoxLayout(said, BoxLayout.Y_AXIS));
        said.add(sentence);
        said.add(Box.createVerticalStrut(6));
        said.add(warning);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(said, BorderLayout.CENTER);

        final Form form = new Form(panel, propertyBox, newName, candidateBox, sentence, warning,
                properties);
        Runnable refresh = () -> refresh(form, ontology, source, target, sourceLabel, targetLabel);
        propertyBox.addActionListener(e -> {
            newName.setEnabled(CREATE_NEW.equals(propertyBox.getSelectedItem()));
            refresh.run();
        });
        candidateBox.addActionListener(e -> refresh.run());
        newName.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh.run();
            }
        });
        refresh.run();
        return form;
    }

    /** The sentence and the warning, for what is chosen now. */
    private static void refresh(Form form, OWLOntology ontology, OWLClass source, OWLClass target,
            String sourceLabel, String targetLabel) {
        EdgeAxioms.Candidate candidate = (EdgeAxioms.Candidate) form.candidateBox.getSelectedItem();
        if (candidate == null) {
            form.sentence.setText("");
            form.warning.setVisible(false);
            return;
        }
        OWLObjectProperty property = form.chosen();
        String propertyLabel = property != null ? DisplayLabels.forEntity(ontology, property)
                : form.newName.getText() == null ? "" : form.newName.getText().trim();
        // Named placeholders, substituted once: replacing every capital A and B once mangled three
        // of the six sentences ("American Hotnything that relates...").
        form.sentence.setText("<html><body style='width:340px'><i>" + candidate.explainedFor(
                escape(sourceLabel), propertyLabel.isEmpty() ? null : "<b>" + escape(propertyLabel)
                        + "</b>", escape(targetLabel)) + "</i></body></html>");
        List<String> notes = new ArrayList<String>();
        String misfit = PropertyFit.warningFor(ontology, candidate, source, property, target);
        if (misfit != null) {
            notes.add(misfit);
        }
        String imported = PropertyFit.importedNote(ontology, source);
        if (imported != null) {
            notes.add(imported);
        }
        form.warning.setText(notes.isEmpty() ? "" : "<html><body style='width:340px'>"
                + escape(String.join(" ", notes)) + "</body></html>");
        form.warning.setVisible(!notes.isEmpty());
        form.panel.revalidate();
        // The dialog grows with the text: a warning added after it was laid out had no room at all.
        Window window = SwingUtilities.getWindowAncestor(form.panel);
        if (window != null) {
            window.pack();
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Every object property the edge could use, the imported ones among them: extending a pattern
     * means reusing its properties, and offering only the ontology's own sent people off to mint a
     * copy of a property they had just imported.
     */
    static List<OWLObjectProperty> propertiesOffered(OWLOntology ontology) {
        List<OWLObjectProperty> properties = new ArrayList<OWLObjectProperty>(
                ontology.getObjectPropertiesInSignature(Imports.INCLUDED));
        // Sorted so the list does not reshuffle between invocations; OWL API returns an
        // unordered set.
        Collections.sort(properties, new Comparator<OWLObjectProperty>() {
            @Override
            public int compare(OWLObjectProperty a, OWLObjectProperty b) {
                return DisplayLabels.forEntity(ontology, a)
                        .compareToIgnoreCase(DisplayLabels.forEntity(ontology, b));
            }
        });
        return properties;
    }
}
