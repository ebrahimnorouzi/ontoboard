package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import org.junit.jupiter.api.Test;

/**
 * The empty board is the first thing a participant reads, so every line on it has to be readable.
 *
 * <p>Its quiet lines took the look and feel's {@code Label.disabledForeground}, which in Protégé
 * 5.6.9's look and feel on Windows is near white: (220,220,220) on the white card, 1.37:1. The
 * real-mouse walk of the egg guide on Windows found every help line of the card all but invisible.
 */
class StartPanelTest {

    @Test
    void everyLineOfTheEmptyBoardCanBeReadWhenTheThemeGreyIsNearWhite() {
        UIManager.put("Label.disabledForeground", new ColorUIResource(220, 220, 220));
        try {
            Runnable nothing = () -> { };
            StartPanel panel = new StartPanel(nothing, nothing, nothing, nothing, nothing, nothing);
            JPanel card = (JPanel) panel.getComponent(0);
            List<JLabel> labels = Wcag.labelsIn(card);
            assertTrue(labels.size() >= 8, "the card's heading, two lines, six hints and two notes: " + labels.size());
            for (JLabel label : labels) {
                double ratio = Wcag.contrast(label.getForeground(), card.getBackground());
                assertTrue(ratio >= Wcag.READABLE, String.format("\"%s\" reads at %.2f:1 on the card, %s on %s",
                        label.getText().trim(), ratio, label.getForeground(), card.getBackground()));
            }
        } finally {
            UIManager.put("Label.disabledForeground", null);
        }
    }
}
