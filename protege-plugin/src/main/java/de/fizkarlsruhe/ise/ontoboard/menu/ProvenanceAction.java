package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.TermMinter;
import java.io.File;
import java.net.URI;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Provenance &gt; Who to record - the ORCID or name new terms are attributed to.
 *
 * <p>Separate from the collaboration display name on purpose, and the dialog says so: a cursor
 * label is a nickname, while an author recorded in a published ontology should be an ORCID, which
 * resolves and which two people cannot share.
 */
public class ProvenanceAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected boolean runsInBackground() {
        // This action's work is a modal dialog, and opening one from a worker thread is a Swing
        // threading violation with intermittent, miserable symptoms.
        return false;
    }

    @Override
    protected String operationName() {
        return "Provenance";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        ProvenanceSettings current = ProvenanceSettings.load();
        JTextField agent = new JTextField(current.getAgent(), 30);
        JComboBox<ProvenanceSettings.Mode> mode =
                new JComboBox<ProvenanceSettings.Mode>(ProvenanceSettings.Mode.values());
        mode.setSelectedItem(current.getMode());

        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        form.add(new JLabel("ORCID (preferred) or name to record as dcterms:contributor:"));
        form.add(agent);
        form.add(new JLabel("When to record it:"));
        form.add(mode);
        form.add(new JLabel("<html><body style=\'width:420px\'><br>"
                + "Following the ontology means: record provenance if this ontology already does, "
                + "or if it is an ODK project. An ontology that has never recorded provenance "
                + "does not start because you opened it here."
                + "</body></html>"));

        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), form, "Provenance",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return null;
        }
        ProvenanceSettings chosen = new ProvenanceSettings(agent.getText(),
                (ProvenanceSettings.Mode) mode.getSelectedItem());
        ProvenanceSettings.save(chosen);

        // Whether this is an ODK project decides the FOLLOW_THE_ONTOLOGY default, so it has to
        // be asked properly rather than assumed.
        boolean odk = TermMinter.forOntologyFile(
                ontologyFileOf(ontology), chosen.getAgent()).isNumeric();
        OperationResult.Builder result = OperationResult.of(operationName())
                .summary(chosen.describe(ontology, odk));
        result.note("Recorded as: " + (chosen.getAgent().isEmpty() ? "(nobody)"
                : chosen.canonicalAgent()));
        result.note("This ontology " + (Provenance.isUsedIn(ontology) ? "already records"
                : "does not currently record") + " provenance.");
        if (!chosen.getAgent().isEmpty() && !chosen.hasOrcid()) {
            result.warn("That is not an ORCID. A name is accepted, but an ORCID is resolvable "
                    + "and cannot be confused with another person of the same name.");
        }
        return result.build();
    }

    /** The ontology's own file, or null when it has never been saved. */
    private File ontologyFileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
