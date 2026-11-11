package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

/**
 * The History tab: this project's saved conversations, newest first, and the one picked, as it
 * was written - read-only. Without a project folder, or before anything was saved, it says how
 * conversations come to be saved.
 */
public final class HistoryPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String HOW = "Conversations are kept here when Settings > \"Save conversations in"
            + " this project\" is on. Each one is a Markdown file in assistant/conversations/,"
            + " next to the ontology - or in assistant/conversations/released/ for those whose"
            + " author released them. Each person chooses for their own in Settings.";

    private final Supplier<ProjectFolder> project;
    private final DefaultListModel<String> names = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(names);
    private final JTextArea shown = new JTextArea();
    private final List<File> files = new ArrayList<File>();

    public HistoryPanel(Supplier<ProjectFolder> project) {
        super(new BorderLayout(0, 4));
        this.project = project;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showPicked();
            }
        });
        shown.setEditable(false);
        shown.setLineWrap(true);
        shown.setWrapStyleWord(true);
        shown.setFont(new Font(Font.MONOSPACED, Font.PLAIN, shown.getFont().getSize()));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list),
                new JScrollPane(shown));
        split.setResizeWeight(0.25);
        split.setBorder(BorderFactory.createEmptyBorder());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> reload());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(refresh);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    /** The active ontology's saved conversations, again. */
    public void reload() {
        names.clear();
        files.clear();
        ProjectFolder folder = project.get();
        if (folder != null) {
            files.addAll(folder.conversations());
        }
        for (File file : files) {
            names.addElement(file.getName());
        }
        if (files.isEmpty()) {
            shown.setText(HOW);
        } else {
            list.setSelectedIndex(0);
            showPicked();
        }
    }

    private void showPicked() {
        int index = list.getSelectedIndex();
        if (index < 0 || index >= files.size()) {
            return;
        }
        try {
            shown.setText(new String(Files.readAllBytes(files.get(index).toPath()),
                    StandardCharsets.UTF_8));
            shown.setCaretPosition(0);
        } catch (IOException unreadable) {
            shown.setText("This file could not be read: " + unreadable.getMessage());
        }
    }

    // ------------------------------------------------------------------ for tests

    List<String> listedNames() {
        List<String> listed = new ArrayList<String>();
        for (int i = 0; i < names.size(); i++) {
            listed.add(names.get(i));
        }
        return listed;
    }

    void select(int index) {
        list.setSelectedIndex(index);
        showPicked();
    }

    String shownText() {
        return shown.getText();
    }
}
