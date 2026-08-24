package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import org.junit.jupiter.api.Test;

/**
 * Only the payload decoding is tested. Constructing PalettePanel needs a display, and the
 * drop handling lives in SchemaCanvasView which needs a live OWLEditorKit - both stated
 * rather than faked.
 */
class PalettePanelTest {

    @Test
    void decodesEveryKindItOffers() {
        assertEquals(EntityFactory.Kind.CLASS, PalettePanel.kindOf("CLASS"));
        assertEquals(EntityFactory.Kind.INDIVIDUAL, PalettePanel.kindOf("INDIVIDUAL"));
        assertEquals(EntityFactory.Kind.OBJECT_PROPERTY,
                PalettePanel.kindOf("OBJECT_PROPERTY"));
    }

    @Test
    void tolerantOfSurroundingWhitespace() {
        assertEquals(EntityFactory.Kind.CLASS, PalettePanel.kindOf("  CLASS \n"));
    }

    /**
     * A drop from somewhere else - a file, text from another app - must be ignored. Falling
     * back to a default kind would silently create an entity the user never asked for.
     */
    @Test
    void foreignPayloadsAreRejectedRatherThanDefaulted() {
        assertNull(PalettePanel.kindOf("some dragged text"));
        assertNull(PalettePanel.kindOf(""));
        assertNull(PalettePanel.kindOf(null));
        assertNull(PalettePanel.kindOf("class"), "matching must be exact, not case-folded");
    }
}
