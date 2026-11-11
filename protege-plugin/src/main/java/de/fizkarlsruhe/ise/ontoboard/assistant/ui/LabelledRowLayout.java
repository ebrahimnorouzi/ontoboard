package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * A label and its field: side by side while the field still gets {@link #FIELD_MIN} pixels, the
 * label above the field when it would not (1.144.0, review 1.143.0 I-4). Side by side at any
 * width, a 300 px column, or a larger font, left the model field 29 px wide - and with a 1.5x
 * font, -62 px. The rows of one form share their label width, so their fields line up.
 */
final class LabelledRowLayout implements LayoutManager {

    static final int GAP = 6;
    static final int FIELD_MIN = 160;

    /** The widest label of the form, set once its rows are built; shared by them. */
    private final int[] labelWidth;

    /** @param labelWidth a one-element array the form fills with its widest label's width */
    LabelledRowLayout(int[] labelWidth) {
        this.labelWidth = labelWidth;
    }

    private int labelWidth(Component label) {
        return Math.max(labelWidth[0], label.getPreferredSize().width);
    }

    private boolean beside(Component label, int width) {
        return width - labelWidth(label) - GAP >= FIELD_MIN;
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int width = StackLayout.widthOf(target) - insets.left - insets.right;
            Component label = target.getComponent(0);
            Component field = target.getComponent(1);
            if (!label.isVisible() && !field.isVisible()) {
                return new Dimension(width + insets.left + insets.right, 0);
            }
            Dimension words = label.getPreferredSize();
            Dimension value = field.getPreferredSize();
            int height = beside(label, width) ? Math.max(words.height, value.height)
                    : words.height + 2 + value.height;
            return new Dimension(width + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension preferred = preferredLayoutSize(target);
        return new Dimension(0, preferred.height);
    }

    @Override
    public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int width = target.getWidth() - insets.left - insets.right;
            Component label = target.getComponent(0);
            Component field = target.getComponent(1);
            Dimension words = label.getPreferredSize();
            Dimension value = field.getPreferredSize();
            if (beside(label, width)) {
                int height = Math.max(words.height, value.height);
                int left = labelWidth(label);
                label.setBounds(insets.left, insets.top + (height - words.height) / 2, left,
                        words.height);
                field.setBounds(insets.left + left + GAP, insets.top, width - left - GAP,
                        height);
            } else {
                label.setBounds(insets.left, insets.top, width, words.height);
                field.setBounds(insets.left, insets.top + words.height + 2, width, value.height);
            }
        }
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }
}
