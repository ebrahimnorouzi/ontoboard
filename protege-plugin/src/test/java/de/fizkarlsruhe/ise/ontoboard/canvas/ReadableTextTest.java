package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.junit.jupiter.api.Test;

/**
 * Quiet text keeps the theme's own grey when that can be read, and only then.
 */
class ReadableTextTest {

    private static final Color QUIET = new Color(0x5A, 0x64, 0x70);

    @Test
    void theThemeGreyIsKeptWhenItCanBeRead() {
        Color themed = new Color(100, 100, 100);
        assertSame(themed, ReadableText.muted(themed, Color.BLACK, Color.WHITE));
    }

    @Test
    void aNearWhiteThemeGreyGivesWayToThePanelsOwnGrey() {
        // Protégé 5.6.9's look and feel on Windows: Label.disabledForeground on the white card.
        Color chosen = ReadableText.muted(new Color(220, 220, 220), Color.BLACK, Color.WHITE);
        assertEquals(QUIET, chosen);
        assertTrue(Wcag.contrast(chosen, Color.WHITE) >= Wcag.READABLE);
    }

    @Test
    void noThemeGreyAtAllGivesThePanelsOwnGrey() {
        assertEquals(QUIET, ReadableText.muted(null, Color.BLACK, Color.WHITE));
    }

    @Test
    void onADarkSurfaceTheQuietTextIsAReadableMixOfInkAndSurface() {
        Color surface = new Color(60, 63, 65);
        Color ink = new Color(187, 187, 187);
        Color chosen = ReadableText.muted(new Color(90, 90, 90), ink, surface);
        assertTrue(Wcag.contrast(chosen, surface) >= Wcag.READABLE,
                "readable on the dark surface: " + chosen + " at " + Wcag.contrast(chosen, surface));
        assertNotEquals(QUIET, chosen, "the panels' grey is dark text, unreadable on a dark surface");
        assertTrue(Wcag.contrast(chosen, surface) <= Wcag.contrast(ink, surface),
                "and no louder than the ink itself");
    }

    @Test
    void theContrastIsWcags() {
        assertEquals(21.0, ReadableText.contrast(Color.BLACK, Color.WHITE), 0.01);
        assertEquals(1.0, ReadableText.contrast(Color.WHITE, Color.WHITE), 0.0001);
        assertEquals(Wcag.contrast(QUIET, Color.WHITE), ReadableText.contrast(QUIET, Color.WHITE), 1e-9);
    }
}
