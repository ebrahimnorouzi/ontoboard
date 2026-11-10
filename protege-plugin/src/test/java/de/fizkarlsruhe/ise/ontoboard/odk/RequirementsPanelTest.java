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
}
