package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * A form generated from a list of {@link Parameter}s, with a "?" on every row.
 *
 * <p>Generated rather than hand-written because there will be twenty of these. A dialog per ROBOT
 * operation means the explanation is written carefully for the first two and abandoned for the
 * rest; a form built from descriptors cannot omit the help, because {@link Parameter} refuses to
 * exist without it.
 *
 * <p>Three things make it usable rather than merely present. The help is a <b>button</b>, not a
 * tooltip - a tooltip cannot be read at leisure, cannot be copied, and never appears for someone
 * navigating by keyboard. Validation reports <b>every</b> problem at once, so a user with six
 * fields wrong learns that in one attempt rather than six. And <b>Reset</b> restores the defaults,
 * because an operation with six options is one a user will get into a state they cannot reason
 * about.
 *
 * <p>Swing, so untested; everything it decides - what is valid, what the defaults are, what the
 * help says - lives in {@link Parameter}, which is.
 */
public final class ParameterDialog {

    private static final Color HINT = new Color(0x66, 0x6E, 0x7A);

    private ParameterDialog() {
    }

    /**
     * Asks for values.
     *
     * @return the values keyed by parameter key, or null if the user cancelled
     */
    public static Map<String, String> show(Component parent, String title, String explanation,
            List<Parameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return new LinkedHashMap<String, String>();
        }
        Map<String, Component> controls = new LinkedHashMap<String, Component>();
        JPanel form = buildForm(parent, parameters, controls);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        if (explanation != null && !explanation.trim().isEmpty()) {
            JLabel header = new JLabel("<html><body style='width:400px'>" + escape(explanation)
                    + "</body></html>");
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            content.add(header, BorderLayout.NORTH);
        }
        content.add(form, BorderLayout.CENTER);

        JButton reset = new JButton("Reset");
        reset.setToolTipText("Put every option back to its default");
        reset.addActionListener(a -> applyValues(parameters, controls,
                Parameter.defaultsOf(parameters)));
        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        south.add(reset);
        content.add(south, BorderLayout.SOUTH);

        // Loop rather than one shot: on invalid input the same dialog reopens with what was
        // typed, instead of discarding it and making the user start again.
        while (true) {
            int answer = JOptionPane.showConfirmDialog(parent, content, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return null;
            }
            Map<String, String> values = readValues(parameters, controls);
            List<String> problems = Parameter.rejections(parameters, values);
            if (problems.isEmpty()) {
                return values;
            }
            StringBuilder message = new StringBuilder("<html><body style='width:340px'>");
            for (String problem : problems) {
                message.append("&bull; ").append(escape(problem)).append("<br>");
            }
            JOptionPane.showMessageDialog(parent, message.append("</body></html>").toString(),
                    problems.size() == 1 ? "One value needs changing"
                            : problems.size() + " values need changing",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static JPanel buildForm(Component parent, List<Parameter> parameters,
            Map<String, Component> controls) {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(5, 4, 5, 4);
        at.anchor = GridBagConstraints.WEST;
        at.gridy = 0;

        for (Parameter parameter : parameters) {
            at.gridx = 0;
            JLabel label = new JLabel(parameter.getLabel()
                    + (parameter.isRequired() ? " *" : ""));
            form.add(label, at);

            at.gridx = 1;
            Component control = controlFor(parent, parameter);
            controls.put(parameter.getKey(), control);
            // A text area needs the row to grow, or GridBag draws it at its minimum and the
            // "several lines" the kind exists for are one line with a scrollbar.
            at.fill = parameter.getKind() == Parameter.Kind.MULTILINE
                    ? GridBagConstraints.BOTH : GridBagConstraints.NONE;
            at.weighty = parameter.getKind() == Parameter.Kind.MULTILINE ? 1 : 0;
            form.add(control, at);
            at.fill = GridBagConstraints.NONE;
            at.weighty = 0;

            at.gridx = 2;
            form.add(helpButton(parent, parameter), at);
            at.gridy++;
        }
        at.gridx = 0;
        at.gridwidth = 3;
        form.add(Box.createVerticalStrut(2), at);
        return form;
    }

    /**
     * A "?" that opens the explanation.
     *
     * <p>A button rather than a tooltip: a tooltip vanishes while you read it, cannot be copied
     * into a search, and never appears at all for anyone navigating by keyboard.
     */
    private static Component helpButton(Component parent, Parameter parameter) {
        JButton help = new JButton("?");
        help.setMargin(new Insets(0, 5, 0, 5));
        help.setToolTipText("What does " + parameter.getLabel() + " do?");
        help.setFocusable(true);
        help.addActionListener(a -> JOptionPane.showMessageDialog(parent,
                "<html><body style='width:360px'>" + asHtml(parameter.getHelp())
                        + defaultNote(parameter) + "</body></html>",
                parameter.getLabel(), JOptionPane.INFORMATION_MESSAGE));
        return help;
    }

    private static String defaultNote(Parameter parameter) {
        if (parameter.getDefaultValue() == null || parameter.getDefaultValue().isEmpty()) {
            return "";
        }
        return "<br><br><i>Default: " + escape(parameter.getDefaultValue()) + "</i>";
    }

    private static Component controlFor(Component parent, Parameter parameter) {
        switch (parameter.getKind()) {
            case CHOICE: {
                JComboBox<String> box = new JComboBox<String>(
                        parameter.getChoices().toArray(new String[0]));
                box.setSelectedItem(parameter.getDefaultValue());
                return box;
            }
            case FLAG: {
                JCheckBox check = new JCheckBox();
                check.setSelected("true".equalsIgnoreCase(parameter.getDefaultValue()));
                return check;
            }
            case FILE: {
                JPanel row = new JPanel(new BorderLayout(4, 0));
                JTextField path = new JTextField(parameter.getDefaultValue(), 22);
                JButton browse = new JButton("Browse...");
                browse.addActionListener(a -> {
                    JFileChooser chooser = new JFileChooser();
                    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                        File chosen = chooser.getSelectedFile();
                        path.setText(chosen.getAbsolutePath());
                    }
                });
                row.add(path, BorderLayout.CENTER);
                row.add(browse, BorderLayout.EAST);
                row.putClientProperty("ontoboard.field", path);
                return row;
            }
            case MULTILINE: {
                JTextArea area = new JTextArea(parameter.getDefaultValue(), 8, 34);
                area.setLineWrap(false);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN,
                        new JTextField().getFont().getSize()));
                JScrollPane scroller = new JScrollPane(area);
                scroller.putClientProperty("ontoboard.area", area);
                return scroller;
            }
            case NUMBER:
            case TEXT:
            default: {
                JTextField field = new JTextField(parameter.getDefaultValue(),
                        parameter.getKind() == Parameter.Kind.NUMBER ? 8 : 26);
                return field;
            }
        }
    }

    private static Map<String, String> readValues(List<Parameter> parameters,
            Map<String, Component> controls) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Parameter parameter : parameters) {
            values.put(parameter.getKey(), readValue(controls.get(parameter.getKey())));
        }
        return values;
    }

    private static String readValue(Component control) {
        if (control instanceof JComboBox) {
            Object selected = ((JComboBox<?>) control).getSelectedItem();
            return selected == null ? "" : String.valueOf(selected);
        }
        if (control instanceof JCheckBox) {
            return String.valueOf(((JCheckBox) control).isSelected());
        }
        if (control instanceof JTextField) {
            return ((JTextField) control).getText();
        }
        if (control instanceof JScrollPane) {
            Object area = ((JScrollPane) control).getClientProperty("ontoboard.area");
            if (area instanceof JTextArea) {
                return ((JTextArea) area).getText();
            }
        }
        if (control instanceof JPanel) {
            Object field = ((JPanel) control).getClientProperty("ontoboard.field");
            if (field instanceof JTextField) {
                return ((JTextField) field).getText();
            }
        }
        return "";
    }

    private static void applyValues(List<Parameter> parameters, Map<String, Component> controls,
            Map<String, String> values) {
        for (Parameter parameter : parameters) {
            Component control = controls.get(parameter.getKey());
            String value = values.get(parameter.getKey());
            if (control instanceof JComboBox) {
                ((JComboBox<?>) control).setSelectedItem(value);
            } else if (control instanceof JCheckBox) {
                ((JCheckBox) control).setSelected("true".equalsIgnoreCase(value));
            } else if (control instanceof JTextField) {
                ((JTextField) control).setText(value);
            } else if (control instanceof JScrollPane) {
                Object area = ((JScrollPane) control).getClientProperty("ontoboard.area");
                if (area instanceof JTextArea) {
                    ((JTextArea) area).setText(value);
                }
            } else if (control instanceof JPanel) {
                Object field = ((JPanel) control).getClientProperty("ontoboard.field");
                if (field instanceof JTextField) {
                    ((JTextField) field).setText(value);
                }
            }
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Help text as HTML, keeping the paragraphs it was written with.
     *
     * <p>Swing renders HTML in a label, and HTML collapses newlines. The help for a parameter that
     * offers five reasoners is five paragraphs, one per reasoner; without this it arrives as a
     * single unbroken block of prose that nobody reads to the end - which defeats the entire point
     * of making the help mandatory.
     *
     * <p>Package-visible so it can be tested. Everything else here is Swing and is not.
     */
    static String asHtml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        boolean blankRun = false;
        for (String line : escape(text.trim()).split("\r?\n", -1)) {
            if (line.trim().isEmpty()) {
                blankRun = html.length() > 0;
                continue;
            }
            if (blankRun) {
                // A blank line was a paragraph break, and reads as one.
                html.append("<br><br>");
                blankRun = false;
            } else if (html.length() > 0) {
                // A single newline was a line break the author meant, not a space.
                html.append("<br>");
            }
            html.append(line.trim());
        }
        return html.toString();
    }

    /** A dimmed hint label, for callers building their own rows beside a generated form. */
    public static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, label.getFont().getSize() - 1f));
        label.setForeground(HINT);
        label.setPreferredSize(new Dimension(360, label.getPreferredSize().height));
        return label;
    }
}
