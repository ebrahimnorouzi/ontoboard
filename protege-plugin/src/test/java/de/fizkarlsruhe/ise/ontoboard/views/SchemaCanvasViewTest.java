package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import org.protege.editor.owl.model.event.EventType;
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
    // ---------- which Protege events mean the diagram is out of date ----------

    /**
     * The one that was missing. Classification is its own event channel: starting a reasoner
     * changes no axiom, so neither the ontology-change listener nor the active-ontology listener
     * hears anything, and the canvas went on showing what it had computed before.
     *
     * <p>The symptom was exactly backwards from useful - turn inferences on with no reasoner
     * started, be told to start one, start one, and still see nothing until you toggled the
     * button off and on or happened to make an edit.
     */
    @Test
    void classifyingRefreshesTheDiagramWhenInferencesAreShown() {
        assertTrue(SchemaCanvasView.shouldRefreshFor(EventType.ONTOLOGY_CLASSIFIED, true));
    }

    /** ELK and HermiT genuinely disagree, so what is drawn is no longer what the reasoner says. */
    @Test
    void switchingReasonerRefreshesTheDiagramWhenInferencesAreShown() {
        assertTrue(SchemaCanvasView.shouldRefreshFor(EventType.REASONER_CHANGED, true));
    }

    /**
     * A classification changes nothing visible on an asserted-only diagram, and re-rendering a
     * large board for no difference is a stutter with no purpose.
     */
    @Test
    void classifyingDoesNotRedrawADiagramThatIsNotShowingInferences() {
        assertFalse(SchemaCanvasView.shouldRefreshFor(EventType.ONTOLOGY_CLASSIFIED, false));
        assertFalse(SchemaCanvasView.shouldRefreshFor(EventType.REASONER_CHANGED, false));
    }

    /** Reverting from disk can change every axiom and fires no axiom-change event. */
    @Test
    void reloadingRefreshesWhetherOrNotInferencesAreShown() {
        assertTrue(SchemaCanvasView.shouldRefreshFor(EventType.ONTOLOGY_RELOADED, false));
        assertTrue(SchemaCanvasView.shouldRefreshFor(EventType.ONTOLOGY_RELOADED, true));
    }

    /**
     * ACTIVE_ONTOLOGY_CHANGED is handled on its own branch, by a full reset rather than a
     * refresh. Refreshing for it here as well would render the new ontology through the old
     * layout before the reset had run.
     */
    @Test
    void switchingOntologyIsNotHandledAsAPlainRefresh() {
        assertFalse(SchemaCanvasView.shouldRefreshFor(EventType.ACTIVE_ONTOLOGY_CHANGED, true));
    }

    /**
     * Everything else either cannot alter this diagram or already arrives through the
     * ontology-change listener, and refreshing twice for one edit makes a large board stutter.
     */
    @Test
    void theRemainingEventsDoNotRedrawTheDiagram() {
        for (EventType type : new EventType[] {EventType.ONTOLOGY_SAVED,
                EventType.ONTOLOGY_LOADED, EventType.ONTOLOGY_CREATED,
                EventType.ONTOLOGY_VISIBILITY_CHANGED, EventType.ENTITY_RENDERER_CHANGED,
                EventType.ENTITY_RENDERING_CHANGED, EventType.ABOUT_TO_CLASSIFY}) {
            assertFalse(SchemaCanvasView.shouldRefreshFor(type, true),
                    type + " should not trigger a redraw");
        }
    }

    /**
     * ABOUT_TO_CLASSIFY specifically: the reasoner has not finished, so refreshing on it would
     * query a reasoner mid-classification and draw whatever it had at that moment.
     */
    @Test
    void theDiagramIsNotRedrawnBeforeTheReasonerHasFinished() {
        assertFalse(SchemaCanvasView.shouldRefreshFor(EventType.ABOUT_TO_CLASSIFY, true));
    }

    @Test
    void noEventIsNotAnEvent() {
        assertFalse(SchemaCanvasView.shouldRefreshFor(null, true));
    }

}
