package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * The board's toolbar wraps onto a second row instead of laying buttons out past the edge
 * (spec D6): with the assistant column beside it, the board's column is narrower than the 777px
 * the toolbar needs in one row.
 */
class WrapLayoutTest {

    private static JPanel tenButtons(int width) {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        for (int i = 0; i < 10; i++) {
            JButton button = new JButton("b" + i);
            Dimension size = new Dimension(100, 24);
            button.setPreferredSize(size);
            button.setMinimumSize(size);
            bar.add(button);
        }
        bar.setSize(width, 400);
        return bar;
    }

    @Test
    void childrenWrapInsteadOfOverflowing() {
        JPanel bar = tenButtons(450);

        bar.doLayout();

        for (Component child : bar.getComponents()) {
            assertTrue(child.getX() + child.getWidth() <= 450,
                    "laid out past the edge at x=" + child.getX());
        }
    }

    @Test
    void thePreferredHeightCountsTheRows() {
        JPanel bar = tenButtons(450);

        int height = bar.getPreferredSize().height;

        assertTrue(height >= 3 * 24, "four buttons a row is three rows; height " + height);
    }

    @Test
    void aWideContainerIsOneRow() {
        JPanel bar = tenButtons(1200);

        int height = bar.getPreferredSize().height;

        assertTrue(height < 2 * 24, "one row; height " + height);
    }

    @Test
    void aContainerNotYetSizedAsksItsParent() {
        JPanel parent = new JPanel(null);
        parent.setSize(450, 400);
        JPanel bar = tenButtons(0);
        parent.add(bar);

        assertTrue(bar.getPreferredSize().height >= 3 * 24);
    }

    /**
     * 1.145.0: the assistant's chips scroll after two rows, so their parent is a viewport. When
     * the column narrows, the row's height is asked while the scroll pane and the chips still have
     * the old width; they wrap at the width the scroll pane's parent gives it.
     */
    @Test
    void aBarInAScrollPaneWrapsAtTheWidthTheScrollPaneGets() {
        JPanel bar = tenButtons(1200);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(bar,
                javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setSize(1200, 30);
        scroll.getViewport().setSize(1200, 30);
        JPanel parent = new JPanel(new java.awt.BorderLayout());
        parent.add(scroll, java.awt.BorderLayout.CENTER);
        parent.setSize(450, 400);

        assertTrue(bar.getPreferredSize().height >= 3 * 24, "" + bar.getPreferredSize());
    }
}
