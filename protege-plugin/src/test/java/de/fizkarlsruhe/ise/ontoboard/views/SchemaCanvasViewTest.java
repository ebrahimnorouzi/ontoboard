package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import org.protege.editor.owl.model.event.EventType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.awt.Point;
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

    private static final String PERSON = "http://example.org/tiny#Person";

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


    // ================================================== what capturePositions writes back

    /**
     * Moving a sticky note or resizing a frame is kept.
     *
     * <p>It was not. {@code capturePositions} walked the entity nodes only, so the
     * {@code CELLS_MOVED} listener fired, this captured nothing about notes or frames, and an
     * unchanged sidecar was saved. The next refresh - which any edit anywhere in Protege triggers -
     * redrew both from the position they had before the drag. A note dragged beside the class it
     * comments on went back to the corner; a frame resized to enclose a group snapped back.
     *
     * <p>Nothing could have caught it, which is the more useful half of the story: the capture was
     * an inline loop in a 2,114-line view over a live {@code mxGraph}, so there was no seam a unit
     * test could reach. It is a static function over a {@code Bounds} lookup now, and this is the
     * test that was impossible to write before.
     */
    @Test
    void captureKeepsNoteAndFramePositions() {
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(0, 0));

        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = "ontoboard-note-1";
        note.text = "check this with Bob";
        note.x = 60;
        note.y = 60;
        layout.notes.add(note);

        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = "ontoboard-frame-1";
        frame.label = "Toppings";
        frame.x = 40;
        frame.y = 40;
        frame.w = 340;
        frame.h = 240;
        layout.frames.add(frame);

        // What the user did: dragged the term, dragged the note beside it, grew the frame.
        final Map<String, double[]> moved = new HashMap<String, double[]>();
        moved.put(PERSON, new double[] {300, 120, 160, 60});
        moved.put("ontoboard-note-1", new double[] {480, 140, 180, 100});
        moved.put("ontoboard-frame-1", new double[] {200, 80, 620, 400});

        SchemaCanvasView.captureInto(layout, Collections.singleton(PERSON),
                new SchemaCanvasView.Bounds() {
                    @Override
                    public double[] of(String id) {
                        return moved.get(id);
                    }
                });

        assertEquals(300, layout.nodes.get(PERSON).x, 0.001);
        assertEquals(480, note.x, 0.001, "the note must keep where it was dragged");
        assertEquals(140, note.y, 0.001, "the note must keep where it was dragged");
        assertEquals(200, frame.x, 0.001, "the frame must keep where it was dragged");
        assertEquals(620, frame.w, 0.001, "the frame must keep the width it was given");
        assertEquals(400, frame.h, 0.001, "the frame must keep the height it was given");
    }

    /** A cell that is no longer on the board leaves its stored position alone. */
    @Test
    void captureLeavesAbsentCellsUntouched() {
        CanvasLayout layout = new CanvasLayout();
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = "ontoboard-note-1";
        note.x = 77;
        note.y = 88;
        layout.notes.add(note);

        SchemaCanvasView.captureInto(layout, Collections.<String>emptySet(),
                new SchemaCanvasView.Bounds() {
                    @Override
                    public double[] of(String id) {
                        // Nothing is on the board - a refresh that has not run yet, or a note the
                        // renderer skipped. Overwriting with zeros would move it to the corner,
                        // which is the same data loss by a different route.
                        return null;
                    }
                });

        assertEquals(77, note.x, 0.001);
        assertEquals(88, note.y, 0.001);
    }

    // ================================================== turning a screen point into a graph point

    /**
     * A point on the graph control converts without a scroll term.
     *
     * <p>The bug this pins: the drop path added the viewport's scroll offset on top of coordinates
     * that already included it. The same {@code TransferHandler} is installed on the scroll pane and
     * on the graph control inside it, and the control is the full-size canvas - so its coordinates
     * are already scrolled. On a board scrolled down 500 pixels a class dropped in the middle of the
     * visible area was recorded 500 units below it, off-screen, and the drop looked like it had done
     * nothing at all.
     *
     * <p>Three gestures used to compute this three different ways: the double-click path called the
     * library's converter and was right, cursor sharing divided by the zoom and was right, and the
     * drop path added the scroll and was wrong. They now share one function, and the absence of a
     * scroll term is the thing worth asserting - it is what came back.
     */
    @Test
    void aGraphControlPointNeedsNoScrollTerm() {
        // Scale 1, no translate: the point is itself, however far the board has been scrolled.
        assertEquals(new Point(400, 300),
                SchemaCanvasView.graphPointFromControl(400, 300, 1.0, 0, 0));
    }

    /** The zoom is divided out, so a click at 200% lands where it was aimed. */
    @Test
    void theZoomIsDividedOut() {
        assertEquals(new Point(200, 150),
                SchemaCanvasView.graphPointFromControl(400, 300, 2.0, 0, 0));
        assertEquals(new Point(800, 600),
                SchemaCanvasView.graphPointFromControl(400, 300, 0.5, 0, 0));
    }

    /** The view translate is subtracted - zero on a default view, not in general. */
    @Test
    void theViewTranslateIsSubtracted() {
        assertEquals(new Point(380, 290),
                SchemaCanvasView.graphPointFromControl(400, 300, 1.0, 20, 10));
        // Applied after the zoom, the way mxGraphComponent.getPointForEvent does it.
        assertEquals(new Point(180, 140),
                SchemaCanvasView.graphPointFromControl(400, 300, 2.0, 20, 10));
    }

    /**
     * A zero or negative scale falls back to 1 rather than dividing by zero.
     *
     * <p>Not hypothetical: {@code mxGraphView.getScale()} is a double that a layout or an animation
     * can leave at zero mid-update, and an infinite coordinate puts a node nowhere recoverable.
     */
    @Test
    void anImpossibleScaleDoesNotProduceAnInfiniteCoordinate() {
        assertEquals(new Point(400, 300),
                SchemaCanvasView.graphPointFromControl(400, 300, 0, 0, 0));
        assertEquals(new Point(400, 300),
                SchemaCanvasView.graphPointFromControl(400, 300, -1, 0, 0));
    }

    // ================================================== what Delete reports

    /**
     * Delete distinguishes three outcomes, because they mean different things to the user.
     *
     * <p>"Removed from the board" and "deleted from the ontology" look identical on a canvas - the
     * node disappears either way - and nothing in the interface told them apart. A curator who
     * believes they have retired a term and has only hidden it finds out at the next release; one who
     * believes the reverse spends an afternoon hunting a class that was never gone.
     */
    @Test
    void aRemovalKnowsWhatItDid() {
        SchemaCanvasView.Removal removal = new SchemaCanvasView.Removal(3, 1, 2);

        assertEquals(3, removal.getTerms());
        assertEquals(1, removal.getAnnotations());
        assertEquals(2, removal.getSkipped());
        assertFalse(removal.nothingSelected());
    }

    /**
     * An empty selection is its own outcome, not a removal of nothing.
     *
     * <p>Pressing Delete with nothing selected used to do nothing and say nothing, which is
     * indistinguishable from a broken shortcut. It now says what to do instead.
     */
    @Test
    void anEmptySelectionIsDistinguishableFromHavingRemovedNothing() {
        assertTrue(new SchemaCanvasView.Removal(0, 0, 0).nothingSelected());
        // Something was selected and none of it could be removed - an edge, which is an axiom. That
        // is not "nothing selected", and saying so is the difference between a hint and a shrug.
        assertFalse(new SchemaCanvasView.Removal(0, 0, 1).nothingSelected());
    }

    // ================================================== editing a term's editorial note

    /**
     * What a note box's contents mean, given what was there.
     *
     * <p>The canvas can now read and write a term's {@code IAO:0000116} editor note in place, which
     * is what a domain expert actually leaves behind - the heavy border used to say a note existed
     * and nothing on the board would say what it was. The four cases are separated from the dialog
     * because the dialog cannot be tested and this can, and because each wrong answer is quiet:
     * replacing where it should add writes a second annotation, and treating whitespace as text
     * leaves an empty note in a release.
     */
    @Test
    void anEmptyBoxOverAnExistingNoteRemovesIt() {
        assertEquals(SchemaCanvasView.NoteEdit.REMOVE,
                SchemaCanvasView.noteEditFor("check with Bob", ""));
        assertEquals(SchemaCanvasView.NoteEdit.REMOVE,
                SchemaCanvasView.noteEditFor("check with Bob", "   "));
    }

    @Test
    void textWhereThereWasNoneAddsANote() {
        assertEquals(SchemaCanvasView.NoteEdit.ADD,
                SchemaCanvasView.noteEditFor("", "check the base"));
        assertEquals(SchemaCanvasView.NoteEdit.ADD,
                SchemaCanvasView.noteEditFor(null, "check the base"));
    }

    @Test
    void differentTextOverAnExistingNoteReplacesIt() {
        assertEquals(SchemaCanvasView.NoteEdit.REPLACE,
                SchemaCanvasView.noteEditFor("check the base", "check the base with Bob"));
    }

    /**
     * Whitespace-only differences are not edits.
     *
     * <p>Rewriting the axiom for a trailing space would, in a live session, republish it to every
     * peer and stamp fresh provenance on it - for something nobody typed on purpose.
     */
    @Test
    void invisibleDifferencesAreNotEdits() {
        assertEquals(SchemaCanvasView.NoteEdit.UNCHANGED,
                SchemaCanvasView.noteEditFor("check the base", "check the base  "));
        assertEquals(SchemaCanvasView.NoteEdit.UNCHANGED,
                SchemaCanvasView.noteEditFor("  check the base", "check the base"));
        assertEquals(SchemaCanvasView.NoteEdit.UNCHANGED, SchemaCanvasView.noteEditFor("", "   "));
        assertEquals(SchemaCanvasView.NoteEdit.UNCHANGED, SchemaCanvasView.noteEditFor(null, null));
    }
}
