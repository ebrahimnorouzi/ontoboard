package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A {@link FlowLayout} whose preferred size is the size of the rows it actually lays out.
 *
 * <p>{@code FlowLayout} already wraps when it lays children out, but reports a preferred size of
 * one row, so a {@code BorderLayout.NORTH} that trusts it gives the bar one row's height and the
 * second row is drawn under the board. This one measures the rows against the width it will
 * get: the parent's inner width when the parent is a {@code BorderLayout} (the board's toolbar is
 * its {@code NORTH}, and the parent already has its new width when a column is resized, while the
 * bar still has its old one), otherwise its own width, or the nearest ancestor's when it has not
 * been sized yet.
 *
 * <p>Written for the board's toolbar in 1.136.0, when the assistant column took a third of the
 * tab's width and the toolbar's single row (777px) no longer fitted.
 */
public class WrapLayout extends FlowLayout {

    private static final long serialVersionUID = 1L;

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int hgap = getHgap();
            Insets insets = target.getInsets();
            int sides = insets.left + insets.right + hgap * 2;
            int maxWidth = availableWidth(target) - sides;
            Dimension size = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                Dimension wanted = preferred ? child.getPreferredSize() : child.getMinimumSize();
                if (rowWidth > 0 && rowWidth + hgap + wanted.width > maxWidth) {
                    addRow(size, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += (rowWidth == 0 ? 0 : hgap) + wanted.width;
                rowHeight = Math.max(rowHeight, wanted.height);
            }
            addRow(size, rowWidth, rowHeight);
            size.width += sides;
            size.height += insets.top + insets.bottom + getVgap() * 2;
            return size;
        }
    }

    private void addRow(Dimension size, int rowWidth, int rowHeight) {
        size.width = Math.max(size.width, rowWidth);
        if (size.height > 0) {
            size.height += getVgap();
        }
        size.height += rowHeight;
    }

    static int availableWidth(Container target) {
        Container parent = target.getParent();
        // Scrolled up and down, as the assistant's chips are after two rows (1.145.0): the width
        // the scroll pane gets, less its bar. The height is asked before the scroll pane is laid
        // out again, while the viewport still has its old width.
        if (parent instanceof javax.swing.JViewport
                && parent.getParent() instanceof javax.swing.JScrollPane) {
            javax.swing.JScrollPane scroll = (javax.swing.JScrollPane) parent.getParent();
            int width = availableWidth(scroll);
            if (width == Integer.MAX_VALUE) {
                return width;
            }
            Insets insets = scroll.getInsets();
            javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
            int barWidth = bar != null && bar.isVisible() ? bar.getWidth() : 0;
            return Math.max(1, width - insets.left - insets.right - barWidth);
        }
        if (parent != null && parent.getLayout() instanceof BorderLayout
                && parent.getWidth() > 0) {
            Insets insets = parent.getInsets();
            return parent.getWidth() - insets.left - insets.right;
        }
        int width = target.getWidth();
        for (Container up = parent; width == 0 && up != null; up = up.getParent()) {
            width = up.getWidth();
        }
        return width == 0 ? Integer.MAX_VALUE : width;
    }
}
