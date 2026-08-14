package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * {@link SchemaCanvasView} itself needs a live {@code OWLEditorKit} to construct, which is
 * not available in a headless unit test. What is testable in isolation is the reset core
 * that {@code switchToActiveOntology()} calls when Protege fires
 * {@code EventType.ACTIVE_ONTOLOGY_CHANGED} - exercised directly here, with no view, no
 * listener wiring, and no Swing involved.
 */
class SchemaCanvasViewTest {

    @Test
    void resetLayoutForOntologySwapsIriAndDiscardsStaleMembership() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology nextOntology = manager.createOntology(IRI.create("http://example.org/next#"));

        CanvasLayout layout = new CanvasLayout();
        layout.ontologyIri = "http://example.org/previous#";
        layout.onCanvas.add("http://example.org/previous#Person");
        layout.onCanvas.add("http://example.org/previous#Agent");

        SchemaCanvasView.resetLayoutForOntology(nextOntology, layout);

        assertEquals("http://example.org/next#", layout.ontologyIri);
        assertTrue(layout.onCanvas.isEmpty(),
                "membership from the previous ontology must not survive the switch");
    }
}
