package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
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

    private static CanvasLayout layoutWithSomethingInEveryPerOntologyCollection() {
        CanvasLayout layout = new CanvasLayout();
        layout.ontologyIri = "http://example.org/previous#";
        layout.onCanvas.add("http://example.org/previous#Person");
        layout.nodes.put("http://example.org/previous#Person",
                new CanvasLayout.NodeLayout(310, 190));

        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = "frame-1";
        frame.label = "Core";
        layout.frames.add(frame);

        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = "note-1";
        note.text = "left over from the previous ontology";
        layout.notes.add(note);

        layout.prefixColors.put("ex", "#FF0000");
        return layout;
    }

    @Test
    void resetLayoutForOntologySwapsIriAndDiscardsEveryPerOntologyCollection() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology nextOntology = manager.createOntology(IRI.create("http://example.org/next#"));

        CanvasLayout layout = layoutWithSomethingInEveryPerOntologyCollection();

        SchemaCanvasView.resetLayoutForOntology(nextOntology, layout);

        assertEquals("http://example.org/next#", layout.ontologyIri);
        assertTrue(layout.onCanvas.isEmpty(), "onCanvas from the previous ontology must not survive");
        assertTrue(layout.nodes.isEmpty(), "node positions from the previous ontology must not survive");
        assertTrue(layout.frames.isEmpty(), "frames from the previous ontology must not survive");
        assertTrue(layout.notes.isEmpty(), "notes from the previous ontology must not survive");
        assertTrue(layout.prefixColors.isEmpty(), "prefix colors from the previous ontology must not survive");
    }

    /**
     * {@code nodes} is keyed by entity IRI alone, with no ontology qualifier. Two
     * ontologies that share an imported vocabulary term (BFO, RO, SKOS, ...) can easily
     * share an IRI - this is the actual failure the bug report described: a stale
     * position surviving a switch purely because its key happens to match in the new
     * ontology, not because the user placed it there.
     */
    @Test
    void resetLayoutForOntologyDropsAStoredPositionEvenWhenItsIriAlsoExistsInTheNewOntology() throws Exception {
        String sharedIri = "http://example.org/shared#Person";

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology nextOntology = manager.createOntology(IRI.create("http://example.org/next#"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass sharedClass = factory.getOWLClass(IRI.create(sharedIri));
        manager.addAxiom(nextOntology, factory.getOWLDeclarationAxiom(sharedClass));

        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(sharedIri, new CanvasLayout.NodeLayout(310, 190));

        SchemaCanvasView.resetLayoutForOntology(nextOntology, layout);

        assertTrue(layout.nodes.isEmpty(),
                "a stored position must not survive a switch just because its IRI also exists "
                        + "in the new ontology");
    }
}
