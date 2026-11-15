package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JViewport;

/**
 * Children one under another, each as wide as the column and as tall as it needs at that width.
 *
 * <p>Wrapped text is the reason for it: a text area's height depends on its width, and the
 * layouts Swing ships ask for a preferred height before they give the width - so a conversation
 * in a narrow column either clips its last lines or leaves gaps. This one gives each child the
 * width first and then asks.
 */
final class StackLayout implements LayoutManager {

    /** A component that knows its height at a width without being laid out first. */
    interface Measured {
        int heightAt(int width);
    }

    private final int gap;

    StackLayout(int gap) {
        this.gap = gap;
    }

    static int heightAt(Component child, int width) {
        if (child instanceof Measured) {
            return ((Measured) child).heightAt(width);
        }
        child.setSize(width, Math.max(child.getHeight(), 1));
        if (child instanceof Container) {
            ((Container) child).doLayout();
        }
        return child.getPreferredSize().height;
    }

    /**
     * The width the container will get: its viewport's; its BorderLayout parent's inner width
     * (as {@code WrapLayout} does: the parent has its new width before this asks); its own; or
     * its nearest ancestor's.
     */
    static int widthOf(Container target) {
        Container parent = target.getParent();
        if (parent instanceof JViewport && parent.getWidth() > 0) {
            return parent.getWidth();
        }
        if (parent != null && parent.getLayout() instanceof java.awt.BorderLayout
                && parent.getWidth() > 0) {
            Insets insets = parent.getInsets();
            return parent.getWidth() - insets.left - insets.right;
        }
        int width = target.getWidth();
        for (Container up = parent; width <= 0 && up != null; up = up.getParent()) {
            width = up.getWidth();
        }
        return width > 0 ? width : 320;
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int width = widthOf(target) - insets.left - insets.right;
            int height = 0;
            int shown = 0;
            for (Component child : target.getComponents()) {
                if (child.isVisible()) {
                    height += heightAt(child, width);
                    shown++;
                }
            }
            height += Math.max(0, shown - 1) * gap + insets.top + insets.bottom;
            return new Dimension(width + insets.left + insets.right, height);
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
            int y = insets.top;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                int height = heightAt(child, width);
                child.setBounds(insets.left, y, width, height);
                y += height + gap;
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
