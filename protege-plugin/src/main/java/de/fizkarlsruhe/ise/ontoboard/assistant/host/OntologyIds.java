package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.SetOntologyID;

/**
 * What the assistant column keeps per ontology ID, kept when the ID changes. A new ontology IRI
 * or version IRI - a release's bump - left the conversation on screen under the old ID, and every
 * later "Explain the reasoner problem...", "Why is this unsatisfiable?" and the board's "Add to
 * assistant context" went to a new, empty one the column did not show (review 1.153.0
 * reasoner-01).
 */
public final class OntologyIds {

    private OntologyIds() {
    }

    /** Moves what is kept under an ontology's old ID to its new one, for each ID change. */
    public static <T> void follow(Map<OWLOntologyID, T> byId,
            List<? extends OWLOntologyChange> changes) {
        for (OWLOntologyChange change : changes) {
            if (change instanceof SetOntologyID) {
                OWLOntologyID from = ((SetOntologyID) change).getOriginalOntologyID();
                OWLOntologyID to = ((SetOntologyID) change).getNewOntologyID();
                if (!from.equals(to) && byId.containsKey(from)) {
                    byId.put(to, byId.remove(from));
                }
            }
        }
    }
}
