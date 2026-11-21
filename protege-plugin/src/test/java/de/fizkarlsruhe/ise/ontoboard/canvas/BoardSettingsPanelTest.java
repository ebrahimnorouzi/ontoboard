package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * More > Board settings... (1.155.0): each kind's colour, shape and line, the arrangement, for
 * all of a person's ontologies or for this project - shared through git - with Reset and a
 * preview.
 */
class BoardSettingsPanelTest {

    private static Map<String, String> of(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @Test
    void itEditsThePersonsLookOrTheProjectsAndNeverMixesThem() {
        BoardSettingsPanel panel = new BoardSettingsPanel(of("obClass.fill", "#111111"),
                Collections.<String, String>emptyMap(), true);
        assertEquals("#111111", panel.shownValue("obClass.fill"), "the person's look to start");

        panel.set("obSubClass.colour", "#222222");
        panel.setForProject(true);
        assertEquals(BoardLook.defaults().get("obClass.fill"), panel.shownValue("obClass.fill"),
                "the project's own look: nothing of the person's");
        panel.set("obClass.fill", "#333333");

        assertEquals(of("obClass.fill", "#111111", "obSubClass.colour", "#222222"), panel.personChanges());
        assertEquals(of("obClass.fill", "#333333"), panel.projectChanges());
        assertEquals("#333333", panel.previewLook().get("obClass.fill"), "the preview: the project's over the person's");
        assertEquals("#222222", panel.previewLook().get("obSubClass.colour"));
    }

    @Test
    void resetTakesWhatIsEditedBackToTheDefaults() {
        BoardSettingsPanel panel = new BoardSettingsPanel(of("obClass.fill", "#111111"),
                of("board.spacing", "roomy"), true);

        panel.reset();

        assertEquals(Collections.emptyMap(), panel.personChanges());
        assertEquals(of("board.spacing", "roomy"), panel.projectChanges(), "only what is edited");
        assertEquals(BoardLook.defaults().get("obClass.fill"), panel.shownValue("obClass.fill"));
    }

    @Test
    void aBoardWithNoProjectFileHasNoProjectToKeepALookIn() {
        BoardSettingsPanel panel = new BoardSettingsPanel(Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(), false);

        panel.setForProject(true);
        panel.set("obClass.fill", "#333333");

        assertFalse(panel.forProject());
        assertEquals(of("obClass.fill", "#333333"), panel.personChanges());
    }

    @Test
    void theSettingsReadAt1024By768() throws Exception {
        BoardSettingsPanel panel = new BoardSettingsPanel(of("obClass.fill", "#FFF7E6"),
                Collections.<String, String>emptyMap(), true);
        // The dialog's content at 1024x768: the window's frame and its buttons take the rest.
        panel.setSize(980, 640);
        layOut(panel);
        BufferedImage picture = new BufferedImage(980, 640, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = picture.createGraphics();
        panel.printAll(graphics);
        graphics.dispose();
        File png = new File("target/board-settings.png");
        ImageIO.write(picture, "PNG", png);
        assertTrue(png.length() > 0);
        assertTrue(panel.getPreferredSize().width <= 980 && panel.getPreferredSize().height <= 640,
                "fits a 1024x768 screen: " + panel.getPreferredSize());
    }

    private static void layOut(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                layOut(child);
            }
        }
    }
}
