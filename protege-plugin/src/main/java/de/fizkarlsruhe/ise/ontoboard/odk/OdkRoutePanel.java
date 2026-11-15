package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * The two things a person can settle about ODK's container: whether one is used, and which
 * program runs it ({@link OdkRoute}).
 *
 * <p>Shows what is kept, and gives back what was chosen; it keeps nothing itself. Every control
 * has a name ({@code route.use.NEVER_CONTAINER}, {@code route.program.chosen}, ...) so that a
 * test, or somebody driving a Protégé from outside, can find it.
 */
public final class OdkRoutePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final Map<OdkRoute.Use, JRadioButton> uses =
            new EnumMap<OdkRoute.Use, JRadioButton>(OdkRoute.Use.class);
    private final JRadioButton findIt = new JRadioButton("Find Docker or Podman on this machine");
    private final JRadioButton thisOne = new JRadioButton("Use this program:");
    private final JTextField path = new JTextField(34);
    private final JButton choose = new JButton("Choose...");

    /**
     * @param use           the use that is kept
     * @param programOrNull the program that is kept, or null when it is left to OntoBoard
     * @param foundNow      a sentence on what answers on this machine now, or null to say nothing
     */
    public OdkRoutePanel(OdkRoute.Use use, String programOrNull, String foundNow) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        GridBagConstraints at = new GridBagConstraints();
        at.gridx = 0;
        at.gridy = 0;
        at.gridwidth = 3;
        at.anchor = GridBagConstraints.WEST;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;

        add(heading("Run ODK's targets"), at);
        ButtonGroup useGroup = new ButtonGroup();
        for (OdkRoute.Use one : OdkRoute.Use.values()) {
            JRadioButton button = new JRadioButton(one.label() + " - " + explanation(one));
            button.setName("route.use." + one.name());
            button.setSelected(one == use);
            useGroup.add(button);
            uses.put(one, button);
            at.gridy++;
            add(button, at);
        }

        at.gridy++;
        at.insets = new Insets(12, 0, 0, 0);
        add(heading("The program that runs containers"), at);
        at.insets = new Insets(0, 0, 0, 0);
        ButtonGroup programGroup = new ButtonGroup();
        programGroup.add(findIt);
        programGroup.add(thisOne);
        findIt.setName("route.program.automatic");
        thisOne.setName("route.program.chosen");
        path.setName("route.program.path");
        choose.setName("route.program.choose");
        at.gridy++;
        add(findIt, at);

        at.gridy++;
        at.gridwidth = 1;
        at.weightx = 0;
        at.fill = GridBagConstraints.NONE;
        add(thisOne, at);
        at.gridx = 1;
        at.weightx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.insets = new Insets(0, 4, 0, 4);
        add(path, at);
        at.gridx = 2;
        at.weightx = 0;
        at.fill = GridBagConstraints.NONE;
        at.insets = new Insets(0, 0, 0, 0);
        add(choose, at);

        if (foundNow != null) {
            at.gridx = 0;
            at.gridy++;
            at.gridwidth = 3;
            at.weightx = 1;
            at.fill = GridBagConstraints.HORIZONTAL;
            at.insets = new Insets(12, 0, 0, 0);
            JLabel found = new JLabel("<html><body style='width:440px'>" + escape(foundNow)
                    + "</body></html>");
            found.setName("route.found");
            add(found, at);
        }

        if (programOrNull == null) {
            findIt.setSelected(true);
        } else {
            thisOne.setSelected(true);
            path.setText(programOrNull);
        }
        ActionListener onChoice = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                programFieldFollowsTheChoice();
            }
        };
        findIt.addActionListener(onChoice);
        thisOne.addActionListener(onChoice);
        choose.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                chooseAProgram();
            }
        });
        programFieldFollowsTheChoice();
    }

    /** The use that is chosen now. */
    public OdkRoute.Use use() {
        for (Map.Entry<OdkRoute.Use, JRadioButton> one : uses.entrySet()) {
            if (one.getValue().isSelected()) {
                return one.getKey();
            }
        }
        return OdkRoute.Use.AUTOMATIC;
    }

    /** The program that is named now; null when finding it is left to OntoBoard. */
    public String program() {
        String named = path.getText().trim();
        return thisOne.isSelected() && !named.isEmpty() ? named : null;
    }

    /**
     * Why what is chosen now cannot be kept, or null when it can: "this program" without a
     * program, or with a path that is no file.
     */
    public String whyNot() {
        if (!thisOne.isSelected()) {
            return null;
        }
        String named = path.getText().trim();
        if (named.isEmpty()) {
            return "Name the program that runs containers - docker or podman, with its folder - "
                    + "or let OntoBoard find it.";
        }
        if (!new File(named).isFile()) {
            return named + " is not a file. Name the program itself - docker or podman, with its "
                    + "folder - or let OntoBoard find it.";
        }
        return null;
    }

    private void programFieldFollowsTheChoice() {
        path.setEnabled(thisOne.isSelected());
        choose.setEnabled(thisOne.isSelected());
    }

    private void chooseAProgram() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setDialogTitle("The program that runs containers: docker or podman");
        File named = new File(path.getText().trim());
        if (named.getParentFile() != null && named.getParentFile().isDirectory()) {
            chooser.setCurrentDirectory(named.getParentFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            path.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private static String explanation(OdkRoute.Use use) {
        switch (use) {
            case ALWAYS_CONTAINER:
                return "without one, nothing runs and the reason is said";
            case NEVER_CONTAINER:
                return "OntoBoard runs what it can itself, whatever is installed";
            default:
                return "in a container when one can be run, else by OntoBoard itself";
        }
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        return label;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
