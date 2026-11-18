package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import org.junit.jupiter.api.Test;

/**
 * "Which range is yours?": the choice of a range keeps the underscore of its name in sight.
 * The focus border Swing draws around the chosen value hid the underscore of
 * RANGECHECK_0000000 (1.153.0's minor 21): there is room under the text for the border now.
 */
class RangeChoiceTest {

    private static final String[] CHOICES = {
        "ONTOLOGY-CREATOR: RANGECHECK_0000000 to RANGECHECK_0000999",
        "ADDITIONAL EDITOR: RANGECHECK_0001000 to RANGECHECK_0001999"};

    @Test
    void theChosenRangeHasRoomUnderItsTextForTheFocusBorder() {
        JComboBox<String> combo = RangeChoice.combo(CHOICES);
        JList<String> list = new JList<String>(CHOICES);

        Component ours = combo.getRenderer().getListCellRendererComponent(list, CHOICES[0], -1,
                true, true);
        Component swings = new DefaultListCellRenderer().getListCellRendererComponent(list,
                CHOICES[0], -1, true, true);

        int below = ((JComponent) ours).getInsets().bottom;
        int plain = ((JComponent) swings).getInsets().bottom;
        assertTrue(below >= plain + 2, "under the text: " + below + " px, Swing's own " + plain);
        assertTrue(ours.getPreferredSize().height >= swings.getPreferredSize().height + 2,
                ours.getPreferredSize() + " against " + swings.getPreferredSize());
        assertEquals(CHOICES[0], combo.getSelectedItem(), "the first is chosen, as before");
        assertEquals(CHOICES.length, combo.getItemCount());
    }

    /** The chosen value as it is drawn with the focus, for a person to look at. */
    @Test
    void theChosenRangeRendersToAnImageSomebodyCanLookAt() throws Exception {
        JComboBox<String> combo = RangeChoice.combo(CHOICES);
        JList<String> list = new JList<String>(CHOICES);
        JComponent shown = (JComponent) combo.getRenderer().getListCellRendererComponent(list,
                CHOICES[0], -1, true, true);
        java.awt.Dimension size = shown.getPreferredSize();
        shown.setSize(size);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size.width * 3,
                size.height * 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.scale(3, 3);
        shown.paint(graphics);
        graphics.dispose();
        java.io.File png = new java.io.File("target/design/range-choice.png");
        png.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(image, "PNG", png);
        assertTrue(png.length() > 200, png.getAbsolutePath());
    }
}
