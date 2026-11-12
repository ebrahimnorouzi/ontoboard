package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.canvas.Wcag;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import org.junit.jupiter.api.Test;

/**
 * The requirements report's section titles took the look and feel's
 * {@code Label.disabledForeground}, which in Protégé 5.6.9's look and feel on Windows is near
 * white. Every line it writes has to be readable on the panel.
 */
class RequirementsPanelTest {

    @Test
    void everyLineCanBeReadWhenTheThemeGreyIsNearWhite() {
        UIManager.put("Label.disabledForeground", new ColorUIResource(220, 220, 220));
        try {
            Toolchain.Report report = new Toolchain.Report(
                    Collections.singletonList(new Toolchain.Tool("GNU make", Toolchain.State.ABSENT,
                            "not on this PATH", "Install make")),
                    Collections.singletonList(new Toolchain.Capability("Create a new ODK project", true,
                            "Inside Protégé")));
            RequirementsPanel panel = new RequirementsPanel(report);
            List<JLabel> labels = Wcag.labelsIn(panel);
            boolean sawSection = false;
            for (JLabel label : labels) {
                String text = label.getText() == null ? "" : label.getText();
                if (text.trim().isEmpty()) {
                    // A status disc: an icon, no words.
                    continue;
                }
                sawSection |= "What is installed".equals(text);
                double ratio = Wcag.contrast(label.getForeground(), panel.getBackground());
                assertTrue(ratio >= Wcag.READABLE, String.format("\"%s\" reads at %.2f:1, %s on %s",
                        text, ratio, label.getForeground(), panel.getBackground()));
            }
            assertTrue(sawSection, "the section titles are on the panel");
        } finally {
            UIManager.put("Label.disabledForeground", null);
        }
    }

    /**
     * The window does not scroll sideways, so a row wider than what is shown is cut off: in the
     * first Protege that showed the row for ODK projects, every longer row ended in the middle
     * of a sentence ("... ROBOT runs inside Protege," and on the next line "to install"). Each
     * row asked for a width of 520px, which Swing's HTML takes for about 676 pixels - in a
     * window that shows some 600.
     *
     * <p>Rendered here as the window is shown - at the size it asks for, with as many rows as
     * the real one has, so that it scrolls down - every row ends inside what is shown. The
     * picture is kept under target/ for a person to look at.
     */
    @Test
    void noRowIsWiderThanWhatIsShown() throws Exception {
        List<Toolchain.Capability> can = new java.util.ArrayList<Toolchain.Capability>();
        List<Toolchain.Tool> tools = new java.util.ArrayList<Toolchain.Tool>();
        String sentence = "Through one program or another, in the project's own image - and by "
                + "OntoBoard itself where that image is not on the machine, with nothing to "
                + "install and nothing to fetch";
        for (int i = 0; i < 4; i++) {
            can.add(new Toolchain.Capability("Do a thing of some length, number " + i, true,
                    sentence));
        }
        for (int i = 0; i < 10; i++) {
            tools.add(new Toolchain.Tool("A program, number " + i, Toolchain.State.ABSENT,
                    "not found", sentence + ". " + sentence + "."));
        }
        final RequirementsPanel panel = new RequirementsPanel(new Toolchain.Report(tools, can));
        final java.awt.image.BufferedImage[] picture = {null};
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                java.awt.Dimension size = panel.getPreferredSize();
                panel.setSize(size);
                panel.addNotify();
                panel.validate();
                picture[0] = new java.awt.image.BufferedImage(size.width, size.height,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = picture[0].createGraphics();
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, size.width, size.height);
                panel.paint(graphics);
                graphics.dispose();
            }
        });
        java.io.File kept = new java.io.File("target/requirements-panel.png");
        kept.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(picture[0], "png", kept);

        javax.swing.JScrollPane scroll = null;
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof javax.swing.JScrollPane) {
                scroll = (javax.swing.JScrollPane) child;
            }
        }
        assertTrue(scroll != null, "the rows are in a scroll pane");
        assertTrue(scroll.getVerticalScrollBar().isVisible(), "as many rows as the real window "
                + "has: it scrolls down, and the bar takes its share of the width");
        java.awt.Component rows = scroll.getViewport().getView();
        int shown = scroll.getViewport().getExtentSize().width;
        int checked = 0;
        for (JLabel label : Wcag.labelsIn((java.awt.Container) rows)) {
            java.awt.Rectangle bounds = javax.swing.SwingUtilities.convertRectangle(
                    label.getParent(), label.getBounds(), rows);
            assertTrue(bounds.x + bounds.width <= shown, "a row is cut off: it ends at "
                    + (bounds.x + bounds.width) + " and " + shown + " are shown - "
                    + label.getText());
            checked++;
        }
        assertTrue(checked >= 14, "every row was looked at: " + checked);
    }
}
