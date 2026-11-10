package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;

/**
 * WCAG 2's contrast ratio, written out here so that the tests which hold a panel's text to it do
 * not take the formula from the code they check.
 */
public final class Wcag {

    /** WCAG 2's minimum for text of ordinary size. */
    public static final double READABLE = 4.5;

    private Wcag() {
    }

    /** The contrast ratio of two colours, from 1 (the same) to 21 (black on white). */
    public static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int value) {
        double s = value / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Every label under a container, depth first. */
    public static List<JLabel> labelsIn(Container root) {
        List<JLabel> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel) {
                out.add((JLabel) c);
            }
            if (c instanceof Container) {
                out.addAll(labelsIn((Container) c));
            }
        }
        return out;
    }
}
