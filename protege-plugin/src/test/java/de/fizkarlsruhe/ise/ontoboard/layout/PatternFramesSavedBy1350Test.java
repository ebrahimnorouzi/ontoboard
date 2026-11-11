package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A pattern's frame on a board 1.135.0 saved: titled with the pattern's name, without the pattern
 * field 1.135.1 reads. It is that pattern's frame again once read - so an empty one goes, as an
 * empty pattern frame does - while a frame somebody drew stays theirs, whatever it is called
 * (1.135.2).
 *
 * <p>Found on the maintainer's pizza board: "Objectrole Pattern" and "dataset", saved by 1.135.0,
 * empty, their imports taken back. 1.135.1 took them for frames somebody drew - never dropped,
 * never unframed - and the outline went below and right of them: 8,649 x 7,431 px after Arrange >
 * Outline, 2,915 x 2,952 once they went.
 *
 * <p>Only an empty frame (1.135.2 review): a frame that holds a box is somebody's arrangement
 * whatever its title, and taken for a pattern's, Arrange &gt; Outline would have removed it and
 * moved what it held.
 */
class PatternFramesSavedBy1350Test {

    private static CanvasLayout.FrameLayout frame(String id, String label) {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = id;
        frame.label = label;
        frame.w = 240;
        frame.h = 180;
        return frame;
    }

    private static final Map<String, String> LIBRARY = new HashMap<String, String>();

    static {
        LIBRARY.put("Objectrole Pattern", "objectrole");
        LIBRARY.put("dataset", "nfdi-dataset-description");
        LIBRARY.put("Price Pattern", "price");
        LIBRARY.put("Classification Pattern", "classification");
    }

    /** The patterns this project imported once: their modules are in imports/. */
    private static final Set<String> IMPORTED_ONCE = new HashSet<String>(
            Arrays.asList("objectrole", "nfdi-dataset-description", "classification"));

    @Test
    void aFrameTitledLikeAPatternTheProjectImportedIsThatPatternsFrame() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout objectrole =
                frame("ontoboard-frame-459e1d25", "Objectrole Pattern");
        CanvasLayout.FrameLayout dataset = frame("ontoboard-frame-53657962", "dataset");
        board.frames.add(objectrole);
        board.frames.add(dataset);

        int adopted = board.adoptPatternFramesSavedBy1350(LIBRARY::get, IMPORTED_ONCE::contains);

        assertEquals(2, adopted);
        assertEquals("objectrole", objectrole.pattern);
        assertEquals("nfdi-dataset-description", dataset.pattern);
    }

    @Test
    void aFrameSomebodyDrewStaysTheirsEvenWhenItIsTitledLikeAPattern() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout notes = frame("ontoboard-frame-0a1b2c3d", "my notes");
        // Never imported here: no module, so nothing says the board put it there.
        CanvasLayout.FrameLayout price = frame("ontoboard-frame-1a2b3c4d", "Price Pattern");
        CanvasLayout.FrameLayout untitled = frame("ontoboard-frame-2b3c4d5e", null);
        board.frames.add(notes);
        board.frames.add(price);
        board.frames.add(untitled);

        assertEquals(0, board.adoptPatternFramesSavedBy1350(LIBRARY::get, IMPORTED_ONCE::contains));
        assertNull(notes.pattern);
        assertNull(price.pattern);
        assertNull(untitled.pattern);
    }

    @Test
    void aSectionAndAFrameThatKnowsItsPatternAreLeftAlone() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout section = frame("ontoboard-frame-section-object-properties",
                "Object properties");
        CanvasLayout.FrameLayout known =
                frame("ontoboard-frame-6c80951a", "Classification Pattern");
        known.pattern = "classification";
        board.frames.add(section);
        board.frames.add(known);

        assertEquals(0, board.adoptPatternFramesSavedBy1350(LIBRARY::get, IMPORTED_ONCE::contains));
        assertNull(section.pattern);
        assertEquals("classification", known.pattern);
    }

    /**
     * A frame somebody drew and titled "dataset", in a project that imported the dataset pattern
     * once, holds their own classes: it is theirs (1.135.2 review).
     */
    @Test
    void aFrameThatHoldsABoxIsSomebodysWhateverItsTitle() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout theirs = frame("ontoboard-frame-3c4d5e6f", "dataset");
        theirs.x = 100;
        theirs.y = 100;
        board.frames.add(theirs);
        board.nodes.put("urn:yours#Survey", new CanvasLayout.NodeLayout(120, 140));

        assertEquals(0, board.adoptPatternFramesSavedBy1350(LIBRARY::get, IMPORTED_ONCE::contains));
        assertNull(theirs.pattern);
    }

    /**
     * The place of a restriction's node, which the board keeps for a node it draws without the
     * ontology declaring it, is no class of anybody's; nor is a note a pattern's frame carries,
     * which 1.135.1 began to add.
     */
    @Test
    void aRestrictionsPlaceAndAPatternsNoteStillMakeItThePatternsFrame() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout dataset = frame("ontoboard-frame-53657962", "dataset");
        dataset.x = 100;
        dataset.y = 100;
        board.frames.add(dataset);
        board.notes.add(note(CanvasLayout.PATTERN_NOTE_PREFIX + "dataset", 110, 110));
        board.nodes.put("exp|some|urn:pattern#has-part|urn:pattern#Part",
                new CanvasLayout.NodeLayout(120, 160));

        assertEquals(1, board.adoptPatternFramesSavedBy1350(LIBRARY::get, IMPORTED_ONCE::contains));
        assertEquals("nfdi-dataset-description", dataset.pattern);
    }

    /**
     * A note somebody wrote makes the frame theirs (1.135.2 review): 1.135.0 put no note in a
     * pattern's frame, and a frame of notes titled "dataset", once taken for the pattern's and
     * empty, was dropped with nothing to undo it.
     */
    @Test
    void aFrameHoldingSomebodysNoteIsTheirs() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout theirs = frame("ontoboard-frame-4d5e6f70", "dataset");
        theirs.x = 100;
        theirs.y = 100;
        board.frames.add(theirs);
        board.notes.add(note("ontoboard-note-7a8b9c0d", 110, 110));

        assertEquals(0, board.adoptPatternFramesSavedBy1350(LIBRARY::get, IMPORTED_ONCE::contains));
        assertNull(theirs.pattern);
    }

    private static CanvasLayout.NoteLayout note(String id, double x, double y) {
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = id;
        note.x = x;
        note.y = y;
        note.w = 100;
        note.h = 40;
        return note;
    }
}
