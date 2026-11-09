package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import java.io.File;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * What a session needs from the editor, and nothing more: the ontology, the selected term, and
 * the file it is saved in. Called on the UI executor only - the OWL API is not safe to read while
 * Protégé edits, and Protégé edits on the event thread.
 */
public interface OntologyAccess {

    OWLOntology ontology();

    /** The selected term, or null when nothing is selected. */
    IRI selected();

    /** The ontology's file, or null when it has none yet. */
    File ontologyFile();
}
