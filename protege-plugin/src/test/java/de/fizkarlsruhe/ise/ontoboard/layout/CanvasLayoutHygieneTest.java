package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two rules that keep a sidecar honest about what it describes.
 *
 * <p>Both were found in a real generated project rather than imagined. Its sidecar carried a
 * position for a class named {@code apple} that existed nowhere in the ontology, and its keys were
 * all {@code file:/C:/Users/.../o#...} - identities belonging to one machine's disk. A second
 * person opening that repository would have got an empty canvas that was then written back over
 * the first person's arrangement.
 */
class CanvasLayoutHygieneTest {

    private static Set<String> declared(String... iris) {
        return new HashSet<String>(Arrays.asList(iris));
    }

    private static CanvasLayout layoutFor(String ontologyIri, String... members) {
        CanvasLayout layout = new CanvasLayout();
        layout.ontologyIri = ontologyIri;
        for (String member : members) {
            layout.onCanvas.add(member);
            layout.nodes.put(member, new CanvasLayout.NodeLayout(10, 20));
        }
        return layout;
    }

    // ---------- identity ----------

    @Test
    void aSidecarForTheOpenOntologyIsAccepted() {
        assertTrue(layoutFor("http://example.org/o").belongsTo("http://example.org/o"));
    }

    /**
     * The case that destroyed an arrangement: a sidecar keyed to another machine's file IRI sits
     * beside an ontology that now has a proper one. Every stored key matches nothing, so the board
     * renders empty - and an empty board is what gets saved back.
     */
    @Test
    void aSidecarNamingADifferentOntologyIsRejected() {
        CanvasLayout fromAnotherMachine =
                layoutFor("file:/C:/Users/eno/Desktop/oo/mmo/src/ontology/o");

        assertFalse(fromAnotherMachine.belongsTo("http://purl.obolibrary.org/obo/mmo.owl"));
    }

    @Test
    void anAnonymousOntologyDoesNotMatchANamedSidecar() {
        assertFalse(layoutFor("http://example.org/o").belongsTo(null));
    }

    /** Sidecars written before the IRI was recorded are still worth loading. */
    @Test
    void aSidecarWithNoRecordedIriIsAcceptedRatherThanDiscarded() {
        assertTrue(new CanvasLayout().belongsTo("http://example.org/o"));
        assertTrue(layoutFor("   ").belongsTo("http://example.org/o"));
        assertTrue(new CanvasLayout().belongsTo(null));
    }

    // ---------- pruning ----------

    @Test
    void anEntryForAnEntityTheOntologyNoLongerDeclaresIsRemoved() {
        CanvasLayout layout = layoutFor("http://example.org/o",
                "http://example.org/o#Person", "http://example.org/o#apple");

        List<String> removed = layout.pruneMissing(declared("http://example.org/o#Person"));

        assertEquals(Arrays.asList("http://example.org/o#apple"), removed);
        assertEquals(Collections.singletonList("http://example.org/o#Person"), layout.onCanvas);
        assertFalse(layout.nodes.containsKey("http://example.org/o#apple"),
                "the position must go with the membership, or it is resurrected on the next save");
    }

    @Test
    void everythingStillDeclaredIsLeftAlone() {
        CanvasLayout layout = layoutFor("http://example.org/o",
                "http://example.org/o#Person", "http://example.org/o#Dog");

        List<String> removed = layout.pruneMissing(
                declared("http://example.org/o#Person", "http://example.org/o#Dog"));

        assertTrue(removed.isEmpty());
        assertEquals(2, layout.onCanvas.size());
        assertEquals(2, layout.nodes.size());
    }

    /** A position with no membership entry is still an orphan, and still accumulates. */
    @Test
    void aPositionOrphanedWithoutAMembershipEntryIsAlsoRemoved() {
        CanvasLayout layout = layoutFor("http://example.org/o", "http://example.org/o#Person");
        layout.nodes.put("http://example.org/o#Ghost", new CanvasLayout.NodeLayout(1, 1));

        List<String> removed = layout.pruneMissing(declared("http://example.org/o#Person"));

        assertEquals(Arrays.asList("http://example.org/o#Ghost"), removed);
        assertEquals(1, layout.nodes.size());
    }

    @Test
    void anIriIsReportedOnceEvenWhenItIsOrphanedInBothCollections() {
        CanvasLayout layout = layoutFor("http://example.org/o", "http://example.org/o#apple");

        List<String> removed = layout.pruneMissing(declared());

        assertEquals(1, removed.size(),
                "reporting it twice would overstate what was cleaned up: " + removed);
    }

    @Test
    void pruningAgainstAnEmptyOntologyEmptiesTheBoardAndSaysSo() {
        CanvasLayout layout = layoutFor("http://example.org/o",
                "http://example.org/o#A", "http://example.org/o#B");

        assertEquals(2, layout.pruneMissing(declared()).size());
        assertTrue(layout.onCanvas.isEmpty());
        assertTrue(layout.nodes.isEmpty());
    }

    @Test
    void pruningAnAlreadyCleanLayoutIsANoOp() {
        CanvasLayout layout = new CanvasLayout();
        assertTrue(layout.pruneMissing(declared("http://example.org/o#Person")).isEmpty());
    }

    /**
     * Frames and notes are not keyed by entity IRI, so pruning must not touch them. If it did,
     * a diagram's annotations would vanish whenever an unrelated class was deleted.
     */
    @Test
    void framesAndNotesAreNotEntityKeyedAndSurvivePruning() {
        CanvasLayout layout = layoutFor("http://example.org/o", "http://example.org/o#apple");
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = "n1";
        note.text = "check this";
        layout.notes.add(note);
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = "f1";
        frame.label = "Core";
        layout.frames.add(frame);

        layout.pruneMissing(declared());

        assertEquals(1, layout.notes.size());
        assertEquals(1, layout.frames.size());
    }
}
