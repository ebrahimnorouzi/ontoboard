package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

/**
 * What this machine can do, as a screen.
 *
 * <p>Two lists, because they answer different questions. The first is what the user can do, which
 * is what they came to find out. The second is what is installed, which is what they need in
 * order to change the first.
 *
 * <p>Status is shown with a painted disc rather than a tick character. The canvas design spec
 * ruled out glyph icons for the toolbar because the fonts this plugin can end up drawing with do
 * not all carry them; a dialog is no different, and a missing glyph in a requirements screen
 * would be read as a missing requirement.
 */
public final class RequirementsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Color GREEN = new Color(0x2E, 0x7D, 0x32);
    private static final Color AMBER = new Color(0xB2, 0x6A, 0x00);
    private static final Color GREY = new Color(0x8A, 0x90, 0x99);
    private static final Color INK = new Color(0x1A, 0x1D, 0x21);
    private static final Color MUTED = new Color(0x5A, 0x64, 0x70);

    public RequirementsPanel(Toolchain.Report report) {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        JLabel heading = new JLabel(Toolchain.summary(report));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD,
                heading.getFont().getSize() + 2f));
        heading.setForeground(ink());
        add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);

        body.add(section("What you can do"));
        for (Toolchain.Capability capability : report.getCapabilities()) {
            body.add(row(capability.isAvailable() ? GREEN : AMBER,
                    capability.getName(), capability.getHow(), ""));
        }

        body.add(Box.createVerticalStrut(14));
        body.add(section("What is installed"));
        for (Toolchain.Tool tool : report.getTools()) {
            body.add(row(colourFor(tool.getState()), tool.getName(), tool.getDetail(),
                    tool.getRemedy()));
        }

        JScrollPane scroll = new JScrollPane(body,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(620, 420));
        add(scroll, BorderLayout.CENTER);
    }

    private static Color colourFor(Toolchain.State state) {
        if (state == Toolchain.State.PRESENT) {
            return GREEN;
        }
        return state == Toolchain.State.NOT_WORKING ? AMBER : GREY;
    }

    private static JLabel section(String title) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(muted());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return label;
    }

    /** One line: a coloured disc, a name, what was found, and what to do about it. */
    private static JPanel row(Color colour, String name, String detail, String remedy) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));

        GridBagConstraints at = new GridBagConstraints();
        at.gridx = 0;
        at.gridy = 0;
        at.anchor = GridBagConstraints.NORTHWEST;
        at.insets = new Insets(2, 0, 0, 8);
        row.add(new JLabel(new de.fizkarlsruhe.ise.ontoboard.canvas.CanvasIcons.Dot(colour)), at);

        at.gridx = 1;
        at.weightx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.insets = new Insets(0, 0, 0, 0);
        JLabel text = new JLabel("<html><body style='width:520px'><b>" + escape(name)
                + "</b> &mdash; " + escape(detail)
                + (remedy.isEmpty() ? ""
                        : "<br><span style='color:#5A6470'>" + escape(remedy) + "</span>")
                + "</body></html>");
        text.setForeground(ink());
        row.add(text, at);
        return row;
    }

    /**
     * Escaped, because these strings carry filesystem paths and command lines.
     *
     * <p>A native environment at {@code C:\dev\<odk>} would otherwise take the rest of the row
     * with it into a tag that is never closed.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') {
                out.append("&amp;");
            } else if (c == '<') {
                out.append("&lt;");
            } else if (c == '>') {
                out.append("&gt;");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Color ink() {
        Color themed = UIManager.getColor("Label.foreground");
        return themed != null ? themed : INK;
    }

    private static Color muted() {
        Color themed = UIManager.getColor("Label.disabledForeground");
        return themed != null ? themed : MUTED;
    }
}
