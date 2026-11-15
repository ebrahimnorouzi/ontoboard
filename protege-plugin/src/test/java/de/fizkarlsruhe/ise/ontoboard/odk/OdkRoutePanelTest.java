package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The panel for the two settings of ODK's container: it shows what is kept, gives back what is
 * chosen, and every control of it can be reached at the size it is shown at.
 */
class OdkRoutePanelTest {

    private static final String[] CONTROLS = {"route.use.AUTOMATIC", "route.use.ALWAYS_CONTAINER",
        "route.use.NEVER_CONTAINER", "route.program.automatic", "route.program.chosen",
        "route.program.path", "route.program.choose"};

    @Test
    void itShowsWhatIsKept() {
        OdkRoutePanel panel = new OdkRoutePanel(OdkRoute.Use.NEVER_CONTAINER,
                "C:/tools/podman.exe", null);

        assertEquals(OdkRoute.Use.NEVER_CONTAINER, panel.use());
        assertEquals("C:/tools/podman.exe", panel.program());
        assertTrue(button(panel, "route.use.NEVER_CONTAINER").isSelected());
        assertTrue(button(panel, "route.program.chosen").isSelected());
        assertTrue(named(panel, "route.program.path").isEnabled());
    }

    @Test
    void leftOpenTheProgramIsNoneAndItsFieldIsOff() {
        OdkRoutePanel panel = new OdkRoutePanel(OdkRoute.Use.AUTOMATIC, null, null);

        assertEquals(OdkRoute.Use.AUTOMATIC, panel.use());
        assertNull(panel.program());
        assertTrue(button(panel, "route.program.automatic").isSelected());
        assertFalse(named(panel, "route.program.path").isEnabled());
        assertFalse(named(panel, "route.program.choose").isEnabled());
    }

    @Test
    void whatIsChosenIsWhatItGivesBack() {
        OdkRoutePanel panel = new OdkRoutePanel(OdkRoute.Use.AUTOMATIC, null, null);

        button(panel, "route.use.ALWAYS_CONTAINER").doClick();
        button(panel, "route.program.chosen").doClick();
        ((JTextField) named(panel, "route.program.path")).setText("  C:/tools/docker.exe ");

        assertEquals(OdkRoute.Use.ALWAYS_CONTAINER, panel.use());
        assertEquals("C:/tools/docker.exe", panel.program());
        assertTrue(named(panel, "route.program.path").isEnabled(), "the field is on once "
                + "\"this program\" is chosen");

        button(panel, "route.program.automatic").doClick();
        assertNull(panel.program(), "a path left in the field is not a choice");
    }

    /** "This program" needs one: a file that is there. */
    @Test
    void aProgramThatIsNoFileCannotBeKept(@TempDir File folder) throws Exception {
        OdkRoutePanel panel = new OdkRoutePanel(OdkRoute.Use.AUTOMATIC, null, null);
        assertNull(panel.whyNot());

        button(panel, "route.program.chosen").doClick();
        assertNotNull(panel.whyNot(), "no program named");

        JTextField path = (JTextField) named(panel, "route.program.path");
        path.setText(new File(folder, "not-there.exe").getPath());
        assertTrue(panel.whyNot().contains("not-there.exe"), panel.whyNot());

        File program = new File(folder, "docker.exe");
        Files.write(program.toPath(), new byte[0]);
        path.setText(program.getPath());
        assertNull(panel.whyNot());
    }

    /**
     * A control that is in the panel but outside what is shown cannot be used. At the size the
     * panel asks for, every control lies inside it and has a size; the picture is kept under
     * target/ for a person to look at.
     */
    @Test
    void everyControlCanBeReachedAtTheSizeItIsShown() throws Exception {
        final OdkRoutePanel panel = new OdkRoutePanel(OdkRoute.Use.AUTOMATIC, null,
                "Docker answers on this machine: docker, from the PATH.");
        final BufferedImage[] picture = {null};
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                picture[0] = render(panel);
            }
        });
        File kept = new File("target/odk-route-panel.png");
        kept.getParentFile().mkdirs();
        ImageIO.write(picture[0], "png", kept);

        Rectangle whole = new Rectangle(panel.getSize());
        assertTrue(whole.width <= 640, "it has to fit a dialog on a small screen: "
                + whole.width);
        for (String name : CONTROLS) {
            Component control = named(panel, name);
            Rectangle bounds = SwingUtilities.convertRectangle(control.getParent(),
                    control.getBounds(), panel);
            assertTrue(bounds.width > 10 && bounds.height > 10, name + " has no size: " + bounds);
            assertTrue(whole.contains(bounds), name + " lies outside the panel: " + bounds
                    + " in " + whole);
        }
        assertNotNull(named(panel, "route.found"));
    }

    // ------------------------------------------------------------------ finding and drawing

    private static AbstractButton button(Container in, String name) {
        return (AbstractButton) named(in, name);
    }

    private static Component named(Container in, String name) {
        Component found = find(in, name);
        assertNotNull(found, "no control named " + name);
        return found;
    }

    private static Component find(Container in, String name) {
        for (Component child : in.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container) {
                Component deeper = find((Container) child, name);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    private static BufferedImage render(JPanel panel) {
        Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        panel.addNotify();
        panel.validate();
        BufferedImage image = new BufferedImage(Math.max(1, size.width),
                Math.max(1, size.height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        panel.paint(graphics);
        graphics.dispose();
        return image;
    }
}
