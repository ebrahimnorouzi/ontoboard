package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Arrange menu says what Outline does (1.135.1). It read "Outline - classes as a tree,
 * properties apart", and since 1.135.1 a property whose domain names one class on the board stands
 * beside that class. Its first word stays: the docs reach it as Arrange, Outline.
 */
class ArrangeMenuSaysWhatOutlineDoesTest {

    @Test
    void theOutlineSaysThePropertiesStandByTheirClass() {
        assertEquals("Outline - classes as a tree, properties by their class",
                CanvasLayouts.Algorithm.SECTIONS.getDisplayName());
    }
}
