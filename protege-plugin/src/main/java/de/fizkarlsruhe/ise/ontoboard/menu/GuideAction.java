package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * OntoBoard &gt; Start here: the guide... - the steps from an idea to a released ontology, where the
 * open project is along them, and a button for each that does it.
 *
 * <p>Asked for: "if the user does not know ODK, ROBOT and eXtreme Design they are lost - where to
 * start, what the steps to a release are. Assist them in the plugin: a design or decision tree
 * they can see, click on steps, see what each does, why it is important, the next steps, and what
 * they gain by using OntoBoard - with as little learning as possible." {@link Guide} holds the
 * steps and reads the progress; this draws them.
 */
public class GuideAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    private static final Color DONE_COLOUR = new Color(0x2E, 0x7D, 0x32);
    private static final Color NEXT_COLOUR = new Color(0x1F, 0x5F, 0xAF);
    private static final Color LATER_COLOUR = new Color(0x70, 0x70, 0x70);

    private JDialog dialog;
    private JComboBox<Guide.Start> start;
    private JList<Guide.Step> path;
    private JEditorPane details;
    private JButton doIt;
    private JButton back;
    private JButton next;
    private Map<String, Guide.State> states;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(getOWLWorkspace()),
                "OntoBoard guide", java.awt.Dialog.ModalityType.MODELESS);

        JLabel title = new JLabel("From an idea to a released ontology, step by step");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JLabel subtitle = new JLabel("<html>OntoBoard runs what the Ontology Development Kit "
                + "(ODK) and ROBOT would run, from these steps and its menus - inside Protege, "
                + "with nothing else to install. Choose a step to see what it does and why; the "
                + "button under it does it.</html>");
        start = new JComboBox<Guide.Start>(Guide.Start.values());
        start.addActionListener(e -> rebuild());
        JPanel startRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        startRow.add(new JLabel("Starting from:"));
        startRow.add(start);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.CENTER);
        header.add(startRow, BorderLayout.SOUTH);

        path = new JList<Guide.Step>();
        path.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        path.setCellRenderer(new StepRenderer());
        path.setFixedCellHeight(34);
        path.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                describe();
            }
        });
        JScrollPane pathScroll = new JScrollPane(path);
        pathScroll.setPreferredSize(new Dimension(350, 400));

        details = new JEditorPane("text/html", "");
        details.setEditable(false);
        doIt = new JButton();
        doIt.addActionListener(e -> runSelected());
        back = new JButton("Back");
        back.addActionListener(e -> path.setSelectedIndex(Math.max(0,
                path.getSelectedIndex() - 1)));
        next = new JButton("Next step");
        next.addActionListener(e -> path.setSelectedIndex(Math.min(
                path.getModel().getSize() - 1, path.getSelectedIndex() + 1)));
        JPanel stepButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        stepButtons.add(doIt);
        stepButtons.add(back);
        stepButtons.add(next);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(new JScrollPane(details), BorderLayout.CENTER);
        right.add(stepButtons, BorderLayout.SOUTH);

        JLabel why = new JLabel("<html><b>Why OntoBoard:</b> nothing to install - no Docker, no "
                + "command line &nbsp;&middot;&nbsp; your questions become tests that run in "
                + "seconds &nbsp;&middot;&nbsp; start from proven patterns, not a blank page "
                + "&nbsp;&middot;&nbsp; every release versioned, kept and compared &nbsp;&middot;"
                + "&nbsp; one board your team can share</html>");
        why.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDD, 0xDD, 0xDD)),
                BorderFactory.createEmptyBorder(8, 2, 0, 2)));

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(header, BorderLayout.NORTH);
        content.add(pathScroll, BorderLayout.WEST);
        content.add(right, BorderLayout.CENTER);
        content.add(why, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(1020, 640);
        dialog.setLocationRelativeTo(getOWLWorkspace());
        // Coming back to the guide after doing a step is when its tick should appear.
        dialog.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowGainedFocus(java.awt.event.WindowEvent event) {
                refreshProgress();
            }
        });
        rebuild();
        dialog.setVisible(true);
    }

    /** The steps for where the user is starting, with the next one to do selected. */
    private void rebuild() {
        List<Guide.Step> steps = Guide.steps((Guide.Start) start.getSelectedItem());
        javax.swing.DefaultListModel<Guide.Step> model =
                new javax.swing.DefaultListModel<Guide.Step>();
        for (Guide.Step step : steps) {
            model.addElement(step);
        }
        path.setModel(model);
        refreshProgress();
        int nextStep = 0;
        for (int at = 0; at < steps.size(); at++) {
            if (states.get(steps.get(at).getId()) == Guide.State.NEXT) {
                nextStep = at;
                break;
            }
        }
        path.setSelectedIndex(nextStep);
    }

    private void refreshProgress() {
        java.util.List<Guide.Step> steps = new java.util.ArrayList<Guide.Step>();
        for (int at = 0; at < path.getModel().getSize(); at++) {
            steps.add(path.getModel().getElementAt(at));
        }
        states = Guide.progress(steps, Guide.factsOf(editFile(), activeOntology()));
        path.repaint();
        describe();
    }

    private void describe() {
        Guide.Step step = path.getSelectedValue();
        if (step == null || states == null) {
            return;
        }
        Guide.State state = states.get(step.getId());
        String standing;
        switch (state) {
            case DONE:
                standing = "<span style='color:#2E7D32'><b>Done</b></span> - "
                        + escape(step.getDoneWhen());
                break;
            case NEXT:
                standing = "<span style='color:#1F5FAF'><b>You are here</b></span> - this is "
                        + "the next step for the project you have open.";
                break;
            case OPTIONAL:
                standing = "<b>Optional</b> - " + escape(step.getDoneWhen());
                break;
            case EVERY_TIME:
                standing = "<b>Every time</b> - " + escape(step.getDoneWhen());
                break;
            default:
                standing = "<b>Later</b> - done when: " + escape(step.getDoneWhen());
                break;
        }
        int index = path.getSelectedIndex();
        details.setText("<html><body style='font-family:sans-serif;font-size:12px'>"
                + "<h2 style='margin:0'>" + (index + 1) + ". " + escape(step.getTitle()) + "</h2>"
                + "<p>" + standing + "</p>"
                + "<h3 style='margin-bottom:2px'>What it does</h3><p>" + escape(step.getWhat())
                + "</p>"
                + "<h3 style='margin-bottom:2px'>Why it matters</h3><p>" + escape(step.getWhy())
                + "</p>"
                + "<h3 style='margin-bottom:2px'>What OntoBoard does for you</h3><p>"
                + escape(step.getInstead()) + "</p>"
                + (index + 1 < path.getModel().getSize()
                        ? "<p style='color:#707070'>Next: "
                                + escape(path.getModel().getElementAt(index + 1).getTitle())
                                + "</p>" : "<p style='color:#707070'>That is the whole path."
                                + " From here it is a loop: model, test, release.</p>")
                + "</body></html>");
        details.setCaretPosition(0);
        doIt.setText(step.getActionLabel());
        back.setEnabled(index > 0);
        next.setEnabled(index + 1 < path.getModel().getSize());
    }

    /** Does the selected step: its menu item, or one of the guide's own three. */
    private void runSelected() {
        Guide.Step step = path.getSelectedValue();
        if (step == null) {
            return;
        }
        try {
            if ("requirements".equals(step.getAction())) {
                openRequirements();
            } else if ("board".equals(step.getAction())) {
                openTheBoard();
            } else if ("tests".equals(step.getAction())) {
                openATest();
            } else {
                ProtegeOWLAction action = (ProtegeOWLAction) Class.forName(step.getAction())
                        .newInstance();
                action.setEditorKit(getOWLEditorKit());
                action.initialise();
                action.actionPerformed(null);
            }
        } catch (Exception cannot) {
            javax.swing.JOptionPane.showMessageDialog(dialog, "Could not do that: "
                    + cannot.getMessage(), step.getTitle(),
                    javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        refreshProgress();
    }

    private void openRequirements() throws java.io.IOException {
        File root = projectRoot();
        if (root == null) {
            return;
        }
        File existing = new File(root, "docs/xd/requirements.md");
        File document = existing.isFile() ? existing : new File(root, "docs/requirements.md");
        if (!document.isFile()) {
            document.getParentFile().mkdirs();
            Files.write(document.toPath(), Guide.requirementsTemplate(projectTitle())
                    .getBytes(StandardCharsets.UTF_8));
        }
        open(document);
    }

    private void openATest() throws java.io.IOException {
        File root = projectRoot();
        if (root == null) {
            return;
        }
        File sparql = new File(root, "src/sparql");
        File first = new File(sparql, "cq1_your_first_question.rq");
        if (Guide.factsOf(editFile(), activeOntology()).ownTests == 0 && !first.isFile()) {
            sparql.mkdirs();
            Files.write(first.toPath(), Guide.testTemplate().getBytes(StandardCharsets.UTF_8));
        }
        open(first.isFile() ? first : sparql);
    }

    /** Opens a file in the program the system uses for it, or says where it is. */
    private void open(File file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(file);
                return;
            }
        } catch (Exception noProgramForIt) {
            // Said below instead.
        }
        javax.swing.JOptionPane.showMessageDialog(dialog, "It is here:\n" + file.getAbsolutePath(),
                "Open it in any text editor", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    private void openTheBoard() {
        org.protege.editor.core.ui.workspace.TabbedWorkspace workspace = getOWLWorkspace();
        for (org.protege.editor.core.ui.workspace.WorkspaceTab tab : workspace.getWorkspaceTabs()) {
            if (tab.getId() != null && tab.getId().endsWith("OntoBoardTab")) {
                workspace.setSelectedTab(tab);
                return;
            }
        }
        for (org.protege.editor.core.ui.workspace.WorkspaceTabPlugin plugin
                : workspace.getOrderedPlugins()) {
            if ("OntoBoard".equals(plugin.getLabel())) {
                workspace.setSelectedTab(workspace.addTabForPlugin(plugin));
                return;
            }
        }
    }

    private File projectRoot() {
        File edit = editFile();
        if (edit == null || !Guide.factsOf(edit, null).inProject) {
            javax.swing.JOptionPane.showMessageDialog(dialog, "Set up a project first - step 1 - "
                    + "so there is somewhere to keep this.", "No project yet",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return ReleaseAction.projectRootOf(edit);
    }

    private String projectTitle() {
        OWLOntology ontology = activeOntology();
        if (ontology == null) {
            return "";
        }
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString().endsWith("/title")
                    && annotation.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) annotation.getValue())
                        .getLiteral();
            }
        }
        return "";
    }

    private OWLOntology activeOntology() {
        return getOWLModelManager() == null ? null : getOWLModelManager().getActiveOntology();
    }

    private File editFile() {
        OWLOntology ontology = activeOntology();
        if (ontology == null) {
            return null;
        }
        java.net.URI physical = getOWLModelManager().getOntologyPhysicalURI(ontology);
        return physical != null && "file".equals(physical.getScheme()) ? new File(physical)
                : null;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A step in the path: its mark, number and title, coloured by how it stands. */
    private final class StepRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            Guide.Step step = (Guide.Step) value;
            Guide.State state = states == null ? Guide.State.TO_DO : states.get(step.getId());
            String mark;
            Color colour;
            switch (state) {
                case DONE:
                    mark = "✓";
                    colour = DONE_COLOUR;
                    break;
                case NEXT:
                    mark = "▶";
                    colour = NEXT_COLOUR;
                    break;
                case EVERY_TIME:
                    mark = "↻";
                    colour = NEXT_COLOUR;
                    break;
                case OPTIONAL:
                    mark = "○";
                    colour = LATER_COLOUR;
                    break;
                default:
                    mark = "○";
                    colour = LATER_COLOUR;
                    break;
            }
            setText(mark + "  " + (index + 1) + ". " + step.getTitle()
                    + (state == Guide.State.NEXT ? "  - you are here"
                            : state == Guide.State.OPTIONAL ? "  (optional)" : ""));
            setFont(getFont().deriveFont(state == Guide.State.NEXT ? Font.BOLD : Font.PLAIN));
            if (!isSelected) {
                setForeground(colour);
            }
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            return this;
        }
    }
}
