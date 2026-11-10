package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * Quiet text that can still be read, whatever the look and feel.
 *
 * <p>The empty board, the shortcut list and the requirements report took their quiet grey from the
 * look and feel's {@code Label.disabledForeground}. That key means "disabled", not "secondary", and
 * the themes disagree about it: in Protégé 5.6.9's look and feel on Windows it is near white,
 * (220,220,220) on the white card, a contrast of 1.37:1. The real-mouse walk of the study's egg
 * guide on Windows found every help line of the empty board all but invisible - the first screen a
 * participant reads.
 *
 * <p>So the theme's grey is kept where it can be read, and otherwise replaced: by the panels' own
 * grey, {@code #5A6470}, when that can be read on the surface, else (on a dark surface) by the
 * quietest mix of the ink and the surface that still can. "Read" is WCAG 2's 4.5:1 for text of
 * ordinary size.
 */
public final class ReadableText {

    /** WCAG 2's minimum contrast for text of ordinary size. */
    public static final double READABLE = 4.5;

    /** The grey these panels were drawn with before they asked the theme. */
    private static final Color QUIET = new Color(0x5A, 0x64, 0x70);

    private static final Color DEFAULT_INK = new Color(0x1A, 0x1D, 0x21);

    private ReadableText() {
    }

    /** The theme's quiet text colour for text on {@code surface}, or one that can be read there. */
    public static Color muted(Color surface) {
        Color ink = UIManager.getColor("Label.foreground");
        return muted(UIManager.getColor("Label.disabledForeground"), ink != null ? ink : DEFAULT_INK,
                surface != null ? surface : Color.WHITE);
    }

    /**
     * {@code themed} when it can be read on {@code surface}; else the panels' own grey when that
     * can; else the mix of {@code ink} and {@code surface} nearest the surface that can; else the
     * ink itself.
     */
    static Color muted(Color themed, Color ink, Color surface) {
        if (themed != null && contrast(themed, surface) >= READABLE) {
            return themed;
        }
        if (contrast(QUIET, surface) >= READABLE) {
            return QUIET;
        }
        Color best = ink;
        for (int step = 1; step < 20; step++) {
            Color mixed = mix(ink, surface, step / 20.0);
            if (contrast(mixed, surface) < READABLE) {
                break;
            }
            best = mixed;
        }
        return best;
    }

    /** WCAG 2's contrast ratio: 1 for one colour on itself, 21 for black on white. */
    public static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** {@code from} moved {@code towards} by the fraction {@code t}. */
    private static Color mix(Color from, Color towards, double t) {
        return new Color(
                (int) Math.round(from.getRed() + (towards.getRed() - from.getRed()) * t),
                (int) Math.round(from.getGreen() + (towards.getGreen() - from.getGreen()) * t),
                (int) Math.round(from.getBlue() + (towards.getBlue() - from.getBlue()) * t));
    }
}
