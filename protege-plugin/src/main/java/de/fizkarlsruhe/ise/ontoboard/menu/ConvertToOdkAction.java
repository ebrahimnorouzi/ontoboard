package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkConversion;
import de.fizkarlsruhe.ise.ontoboard.robot.WebOntology;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Turn an ontology into an ODK repository... - an ontology in any format, with its
 * earlier versions, made an ODK project ready for GitHub, every step shown as it happens.
 *
 * <p>Asked for by somebody with an ontology and its history and no ODK repository: the earlier
 * versions as releases and git tags, its imports as modules, the build run, the current version
 * released. {@link OdkConversion} does the work; this asks the questions and shows the steps, a
 * log of what each did, and at the end what to type to publish it. Nothing is pushed anywhere.
 */
public class ConvertToOdkAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        JTextField source = new JTextField(46);
        OWLOntology open = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        String openSource = fileOf(open);
        if (openSource != null) {
            source.setText(openSource);
        }
        DefaultListModel<String> earlier = new DefaultListModel<String>();
        JList<String> earlierList = new JList<String>(earlier);
        earlierList.setVisibleRowCount(5);
        JTextField folder = new JTextField(new File(System.getProperty("user.home")).getPath(),
                46);
        JTextField id = new JTextField(20);
        JTextField title = new JTextField(30);
        JCheckBox modules = new JCheckBox("Turn each import into a module of the terms the "
                + "ontology uses", true);
        boolean gitThere = OdkConversion.gitIsInstalled();
        JCheckBox git = new JCheckBox("Record every version as a git commit and tag"
                + (gitThere ? "" : " - git is not installed, so the versions are only written "
                        + "under releases/"), gitThere);
        git.setEnabled(gitThere);

        JButton chooseSource = new JButton("Browse...");
        chooseSource.addActionListener(e -> {
            File file = chooseFile(false);
            if (file != null) {
                source.setText(file.getAbsolutePath());
            }
        });
        JButton addFile = new JButton("Add a file...");
        addFile.addActionListener(e -> {
            File file = chooseFile(false);
            if (file != null) {
                earlier.addElement(file.getAbsolutePath());
            }
        });
        JButton addAddress = new JButton("Add an address...");
        addAddress.addActionListener(e -> {
            String address = JOptionPane.showInputDialog(getOWLWorkspace(),
                    "The web address of an earlier version - its release file, or its version "
                            + "IRI:", "An earlier version", JOptionPane.QUESTION_MESSAGE);
            if (address != null && !address.trim().isEmpty()) {
                earlier.addElement(address.trim());
            }
        });
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            for (String chosen : earlierList.getSelectedValuesList()) {
                earlier.removeElement(chosen);
            }
        });
        JButton chooseFolder = new JButton("Browse...");
        chooseFolder.addActionListener(e -> {
            File directory = chooseFile(true);
            if (directory != null) {
                folder.setText(directory.getAbsolutePath());
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        row(form, 0, "The ontology", line(source, chooseSource));
        row(form, 1, "", new JLabel("<html><font color=gray>A file in any format OWL reads - "
                + "RDF/XML, Turtle, OWL/XML, functional or Manchester syntax, OBO, JSON-LD - or "
                + "its web address.</font></html>"));
        JPanel versions = new JPanel(new BorderLayout(4, 4));
        versions.add(new JScrollPane(earlierList), BorderLayout.CENTER);
        versions.add(line(addFile, addAddress, remove), BorderLayout.SOUTH);
        row(form, 2, "Earlier versions", versions);
        row(form, 3, "", new JLabel("<html><font color=gray>In any order: each is sorted by "
                + "the version it says it is, or the one in its address.</font></html>"));
        row(form, 4, "Save the project in", line(folder, chooseFolder));
        row(form, 5, "Project id", line(id, new JLabel("empty: made from the ontology's IRI")));
        row(form, 6, "Title", line(title, new JLabel("empty: the ontology's own")));
        row(form, 7, "", modules);
        row(form, 8, "", git);
        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), form,
                "Turn an ontology into an ODK repository", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (source.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "Name the ontology to convert.",
                    "Nothing to convert", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        OdkConversion.Request request = new OdkConversion.Request()
                .current(source.getText().trim()).into(new File(folder.getText().trim()))
                .id(id.getText()).title(title.getText()).modules(modules.isSelected())
                .git(git.isSelected());
        for (int at = 0; at < earlier.size(); at++) {
            request.earlier(earlier.get(at));
        }
        new Progress(request).show();
    }

    /** The steps, a log, and the result, while the conversion runs and after. */
    private final class Progress {
        private final OdkConversion.Request request;
        private final JDialog dialog;
        private final JLabel[] steps = new JLabel[OdkConversion.STEPS.size()];
        private final JTextArea log = new JTextArea(24, 90);
        private final JLabel summary = new JLabel("Working...");
        private final JButton stop = new JButton("Stop");
        private final JButton close = new JButton("Close");
        private final JButton openIt = new JButton("Open the project in Protégé");
        private final WebOntology.Attempt attempt = new WebOntology.Attempt();
        private File root;

        Progress(OdkConversion.Request request) {
            this.request = request;
            dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(
                    getOWLWorkspace()), "Turning the ontology into an ODK repository",
                    java.awt.Dialog.ModalityType.MODELESS);
            JPanel list = new JPanel(new GridBagLayout());
            list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 12));
            for (int at = 0; at < steps.length; at++) {
                steps[at] = new JLabel("○  " + (at + 1) + ". " + OdkConversion.STEPS.get(at));
                GridBagConstraints c = new GridBagConstraints();
                c.gridy = at;
                c.anchor = GridBagConstraints.WEST;
                c.insets = new Insets(4, 2, 4, 2);
                list.add(steps[at], c);
            }
            log.setEditable(false);
            log.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
            stop.addActionListener(e -> attempt.stop());
            close.addActionListener(e -> dialog.dispose());
            close.setEnabled(false);
            openIt.setEnabled(false);
            openIt.addActionListener(e -> openProject());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(stop);
            buttons.add(openIt);
            buttons.add(close);
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(summary, BorderLayout.CENTER);
            bottom.add(buttons, BorderLayout.EAST);
            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            content.add(list, BorderLayout.WEST);
            content.add(new JScrollPane(log), BorderLayout.CENTER);
            content.add(bottom, BorderLayout.SOUTH);
            dialog.setContentPane(content);
            dialog.setSize(new Dimension(1180, 620));
            dialog.setLocationRelativeTo(getOWLWorkspace());
        }

        void show() {
            dialog.setVisible(true);
            new SwingWorker<OdkConversion.Result, Runnable>() {
                @Override
                protected OdkConversion.Result doInBackground() {
                    return OdkConversion.run(request, new OdkConversion.Log() {
                        @Override
                        public void step(int index) {
                            publish(() -> {
                                mark(index, "▶", Color.BLUE);
                                append("\n== " + (index + 1) + ". "
                                        + OdkConversion.STEPS.get(index));
                            });
                        }

                        @Override
                        public void line(String text) {
                            publish(() -> append("   " + text));
                        }

                        @Override
                        public void warn(String text) {
                            publish(() -> append("   ! " + text));
                        }

                        @Override
                        public void done(int index, boolean ok) {
                            publish(() -> mark(index, ok ? "✓" : "!",
                                    ok ? new Color(0x1F, 0x7A, 0x3A) : new Color(0xB3, 0x5C, 0)));
                        }
                    }, attempt);
                }

                @Override
                protected void process(List<Runnable> updates) {
                    for (Runnable update : updates) {
                        update.run();
                    }
                }

                @Override
                protected void done() {
                    stop.setEnabled(false);
                    close.setEnabled(true);
                    try {
                        OdkConversion.Result result = get();
                        root = result.getRoot();
                        openIt.setEnabled(true);
                        summary.setText("<html><b>Done: " + root.getAbsolutePath() + "</b> - "
                                + result.getVersions().size() + " versions, "
                                + result.getTags().size() + " git tags, "
                                + result.getModules().size() + " import modules, build "
                                + (result.isBuildPassed() ? "passing" : "with problems - see "
                                        + "the log") + ", " + result.getWarnings().size()
                                + " warnings.</html>");
                    } catch (Exception failed) {
                        Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                        summary.setText("<html><b>Stopped:</b> " + cause.getMessage()
                                + "</html>");
                        append("\nStopped: " + cause.getMessage());
                    }
                }
            }.execute();
        }

        private void mark(int index, String symbol, Color colour) {
            steps[index].setText(symbol + "  " + (index + 1) + ". "
                    + OdkConversion.STEPS.get(index));
            steps[index].setForeground(colour);
        }

        private void append(String text) {
            log.append(text + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }

        private void openProject() {
            File edit = new File(root, "src/ontology/" + root.getName() + "-edit.owl");
            try {
                // As File > Open does - see ProjectOpener for why not a plain load.
                getOWLEditorKit().handleLoadFrom(edit.toURI());
                dialog.dispose();
            } catch (Exception cannot) {
                JOptionPane.showMessageDialog(dialog, "Could not open " + edit + ": "
                        + cannot.getMessage(), "Not opened", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private String fileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            IRI document = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology);
            return "file".equalsIgnoreCase(document.getScheme())
                    ? new File(document.toURI()).getAbsolutePath() : document.toString();
        } catch (RuntimeException notKnown) {
            return null;
        }
    }

    private File chooseFile(boolean directory) {
        JFileChooser chooser = new JFileChooser();
        if (directory) {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        }
        return chooser.showOpenDialog(getOWLWorkspace()) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile() : null;
    }

    private static JPanel line(JComponent... parts) {
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JComponent part : parts) {
            line.add(part);
        }
        return line;
    }

    private static void row(JPanel form, int y, String label, JComponent field) {
        GridBagConstraints at = new GridBagConstraints();
        at.gridy = y;
        at.insets = new Insets(3, 3, 3, 3);
        at.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel(label), at);
        at.gridx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;
        form.add(field, at);
    }
}
