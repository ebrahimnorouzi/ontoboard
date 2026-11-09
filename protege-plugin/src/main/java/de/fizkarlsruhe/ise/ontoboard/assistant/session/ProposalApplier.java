package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import java.util.List;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Applies an accepted proposal's changes, as one list: in Protégé, through the model manager, so
 * that it is one *Edit → Undo* and every view hears of it. Called on the UI executor.
 */
public interface ProposalApplier {
    void apply(List<OWLOntologyChange> changes);
}
