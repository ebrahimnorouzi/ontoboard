package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SettingsStore;
import de.fizkarlsruhe.ise.ontoboard.canvas.WrapLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * The Settings tab beside the Assistant tab: which connection is active and its form, the role,
 * and this project's "local models only" switch, which is kept next to the ontology so the whole
 * team gets it (spec D1-D4).
 */
public final class SettingsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String NO_PROJECT_FOLDER = "Save the ontology to a file first: the switch is"
            + " kept next to it in assistant/project.properties.";

    /**
     * What ticking "Save conversations" asks (1.142.0, the maintainer's request) - since 1.144.0
     * about this person's own conversations only (review 1.143.0 I-1).
     */
    static final String SHARING_QUESTION = "Saved conversations hold your questions, the titles of"
            + " the context that was sent, and the model's answers.\nKeys are removed; everything"
            + " else stays as written.\n\n"
            + "Keep private: yours go to assistant/conversations/, which git ignores"
            + " (assistant/.gitignore), so they stay on this computer.\n\n"
            + "Release with the project: yours go to assistant/conversations/released/, which git"
            + " sees, so they go into the repository and its releases when you commit.\nAnyone who"
            + " can read the repository can read them.\n\n"
            + "This is your choice for your own conversations: colleagues choose for theirs."
            + " OntoBoard commits nothing itself.";

    private final SettingsStore store;
    private final Supplier<ProjectFolder> project;
    private final Runnable onChange;
    private final JComboBox<String> active = new JComboBox<String>();
    private final ConnectionEditor editor;
    private final RoleEditor roles;
    private final JCheckBox localOnly = new JCheckBox();
    private final JCheckBox saveConversations = new JCheckBox();
    private final Note sharingLine = new Note(" ", Color.GRAY);
    private final JButton changeSharing = new JButton("Change…");
    private final JPanel sharingRow = new JPanel(new BorderLayout(6, 0));
    /** Asks the question; a dialog, replaced in tests. Null is Cancel. */
    private Supplier<ProjectFolder.ConversationSharing> sharingAsker = this::askSharing;
    /**
     * Says a warning that leaves the switch as it is; a dialog, replaced in tests. As wide as a
     * paragraph: the text names a path, and a long one made a dialog as wide as the screen.
     */
    private java.util.function.Consumer<String> warner = text -> JOptionPane.showMessageDialog(
            this, "<html><body style='width: 380px'>" + text.replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;") + "</body></html>",
            "Save conversations", JOptionPane.WARNING_MESSAGE);
    private final JCheckBox provenance = new JCheckBox();
    private final JButton remove = new JButton("Delete");
    private final Map<String, String> environment;
    private boolean loading;

    public SettingsPanel(SettingsStore store, Supplier<ProjectFolder> project, Runnable onChange) {
        this(store, project, onChange, System.getenv());
    }

    SettingsPanel(SettingsStore store, Supplier<ProjectFolder> project, Runnable onChange,
            Map<String, String> environment) {
        super(new BorderLayout());
        this.store = store;
        this.project = project;
        this.onChange = onChange;
        this.environment = environment;
        this.editor = new ConnectionEditor(store, environment, () -> {
            loadConnections();
            onChange.run();
        });
        this.roles = new RoleEditor(store, project, onChange, environment);

        active.addActionListener(event -> {
            if (!loading && active.getSelectedItem() != null) {
                String name = active.getSelectedItem().toString();
                store.setActiveConnection(name);
                editor.load(byName(name));
                onChange.run();
            }
        });
        JButton add = new JButton("New…");
        add.addActionListener(event -> editor.newConnection());
        remove.addActionListener(event -> deleteActive());
        JPanel chooser = new JPanel(new LabelledRowLayout(new int[] {0}));
        chooser.add(new JLabel("Active connection"));
        chooser.add(active);
        JPanel chooserButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
        chooserButtons.add(add);
        chooserButtons.add(remove);

        JPanel connections = section("Model");
        connections.add(chooser);
        connections.add(chooserButtons);
        connections.add(editor);

        JPanel role = section("Role");
        role.add(roles);

        localOnly.addActionListener(event -> switchLocalOnly());
        Note explained = new Note("Only connections to this machine or to a private network can"
                + " be used with this ontology.", Color.GRAY);
        saveConversations.addActionListener(event -> switchSaveConversations());
        Note savedWhere = new Note("Each conversation is written to assistant/conversations/ next"
                + " to the ontology after every answer: when, which model, and what became of each"
                + " proposal. Keys are never written.", Color.GRAY);
        changeSharing.setToolTipText("Choose again: keep them private, or release them with the"
                + " project.");
        changeSharing.addActionListener(event -> askAgain());
        sharingRow.add(sharingLine, BorderLayout.CENTER);
        sharingRow.add(changeSharing, BorderLayout.EAST);
        JPanel projectSection = section("This project");
        projectSection.add(Checks.labelled(localOnly, "Local models only for this project"));
        projectSection.add(explained);
        projectSection.add(Checks.labelled(saveConversations, "Save conversations in this project"));
        projectSection.add(savedWhere);
        projectSection.add(sharingRow);

        // A choice made elsewhere - another window, a colleague's file - shows when the tab does;
        // only this project's part is read again, so a key being typed is kept (1.144.0).
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent event) {
                refreshProject();
            }
        });
        // In another window the Settings tab usually stays selected and is not shown again when
        // the view is; the view showing again reads it too (1.145.0, review 1.144.0). Hidden as
        // well: that costs one small file, and the tab is never stale when it shows.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                refreshProject();
            }
        });

        provenance.setSelected(store.isProvenance());
        provenance.addActionListener(event -> {
            store.setProvenance(provenance.isSelected());
            onChange.run();
        });
        Note provenanceWhat = new Note("What you accept is annotated dcterms:contributor"
                + " \"OntoBoard assistant (kind model)\", so the ontology records which changes a"
                + " model suggested.", Color.GRAY);
        JPanel changes = section("Accepted changes");
        changes.add(Checks.labelled(provenance, "Name the model on what I accept (provenance)"));
        changes.add(provenanceWhat);

        // The project's switches come before the role: the role's text is the tallest thing here
        // and the least often changed.
        JPanel all = new Column();
        all.add(connections);
        all.add(projectSection);
        all.add(changes);
        all.add(role);
        JScrollPane scroll = new JScrollPane(all, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        reload();
    }

    private static JPanel section(String title) {
        JPanel section = new JPanel(new StackLayout(2));
        section.setBorder(BorderFactory.createTitledBorder(title));
        return section;
    }

    /**
     * The tab's sections, as wide as the view whatever they contain, scrolled by lines. Left to
     * its preferred width it was 1,433 px in a 396 px view, and the shorter sections were centred
     * in that, out of sight; and a scroll pane moves anything else by a pixel a notch (1.142.0).
     */
    private static final class Column extends JPanel implements Scrollable {

        private static final long serialVersionUID = 1L;
        private static final int LINE = 16;

        Column() {
            super(new StackLayout(4));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return LINE;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            int page = orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
            return Math.max(LINE, page - LINE);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport
                    && getParent().getHeight() > getPreferredSize().height;
        }
    }

    /** Everything again: the connections, the roles, and the active ontology's project. */
    public void reload() {
        loadConnections();
        // The view calls this when another ontology is active: a role text typed for the last
        // one waits for it, instead of going by Save, Reset or Delete into this one's project
        // (1.147.0, review 1.146.0 ui-1).
        roles.reloadForActiveOntology();
        // The roles were read just now; a failed read is read again when the tab is shown.
        refreshProject(false);
    }

    /**
     * The ontology was saved: a first save gives it a project folder, and Save As may give it
     * another. That neither shows nor hides this tab, so the view calls this: the role line said
     * Protégé's preferences while Save wrote next to the ontology (1.146.0, review 1.145.0 ui-2).
     * On the event thread.
     */
    public void projectMayHaveChanged() {
        refreshProject();
    }

    /**
     * What another window or a colleague's file can change, again: this project's switches,
     * where conversations go, the roles when the ontology got its first file or another one
     * (1.145.0, review 1.144.0: the role section said preferences while Save wrote next to the
     * ontology), and the provenance switch. A key being typed is kept, and so is a role's text
     * (1.146.0, review 1.145.0 ui-4), with a line that says where edits go has changed (1.147.0,
     * review 1.146.0 ui-1).
     */
    void refreshProject() {
        refreshProject(true);
    }

    /** @param readRoles whether the roles may need reading again: not right after a read */
    private void refreshProject(boolean readRoles) {
        ProjectFolder folder = project.get();
        String key = folder == null ? "" : folder.key();
        // The role section knows where its line says edits go; a key kept here as well went
        // stale when the section followed a Save on its own (1.146.0, review 1.145.0 ui-2).
        // After a failed read they are read again as well: read only for another project, the
        // list stayed empty, and the texts typed and kept did not come back, until another
        // ontology was active (1.149.0, review 1.147.0 ui-3).
        if (readRoles && (!key.equals(roles.shownFor()) || roles.readFailed())) {
            roles.reload();
        }
        provenance.setSelected(store.isProvenance());
        localOnly.setEnabled(folder != null);
        localOnly.setSelected(folder != null && folder.isLocalModelsOnly());
        localOnly.setToolTipText(folder == null ? NO_PROJECT_FOLDER
                : "Kept in assistant/project.properties next to the ontology, so the team gets"
                        + " it through git.");
        saveConversations.setEnabled(folder != null);
        saveConversations.setSelected(folder != null && folder.isSavingConversations());
        saveConversations.setToolTipText(folder == null ? NO_PROJECT_FOLDER
                : "Kept in assistant/project.properties; the files go to"
                        + " assistant/conversations/.");
        showSharing(folder);
    }

    private ProjectFolder.ConversationSharing mine(ProjectFolder folder) {
        return ProjectFolder.ConversationSharing.of(store.conversationSharing(folder.key()));
    }

    /** Where this person's conversations go, once that is a question worth showing. */
    private void showSharing(ProjectFolder folder) {
        ProjectFolder.ConversationSharing sharing = folder == null
                ? ProjectFolder.ConversationSharing.UNDECIDED : mine(folder);
        boolean undecided = sharing == ProjectFolder.ConversationSharing.UNDECIDED;
        boolean shown = folder != null && (!undecided || folder.isSavingConversations());
        sharingRow.setVisible(shown);
        changeSharing.setVisible(shown);
        if (!shown) {
            return;
        }
        if (undecided) {
            sharingLine.setText("Not decided yet: your conversations stay out of git until you"
                    + " choose.");
        } else if (sharing == ProjectFolder.ConversationSharing.RELEASED) {
            sharingLine.setText("Released: your conversations go to"
                    + " assistant/conversations/released/, which git sees; they go with the"
                    + " project when you commit.");
        } else {
            File[] released = folder.conversationsDirectory(
                    ProjectFolder.ConversationSharing.RELEASED).listFiles();
            sharingLine.setText("Private: your conversations go to assistant/conversations/,"
                    + " which git ignores." + (released == null || released.length == 0 ? ""
                            : " Those released earlier stay in conversations/released/; git rm"
                                    + " --cached takes one out of the repository."));
        }
        revalidate();
        repaint();
    }

    private ProjectFolder.ConversationSharing askSharing() {
        Object[] choices = {"Keep private", "Release with the project", "Cancel"};
        int chosen = JOptionPane.showOptionDialog(this, SHARING_QUESTION, "Save conversations",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices,
                choices[0]);
        return chosen == 0 ? ProjectFolder.ConversationSharing.PRIVATE
                : chosen == 1 ? ProjectFolder.ConversationSharing.RELEASED : null;
    }

    /** "Change…": the question again; Cancel leaves everything as it is. */
    private void askAgain() {
        ProjectFolder folder = project.get();
        if (folder == null) {
            return;
        }
        ProjectFolder.ConversationSharing answer = sharingAsker.get();
        if (answer == null) {
            return;
        }
        store.setConversationSharing(folder.key(), answer.id());
        showSharing(folder);
    }

    private void loadConnections() {
        loading = true;
        try {
            active.removeAllItems();
            for (Connection connection : store.connections()) {
                active.addItem(connection.getName());
            }
            Connection current = store.active();
            if (current != null) {
                active.setSelectedItem(current.getName());
            }
            editor.load(current);
        } finally {
            loading = false;
        }
    }

    private Connection byName(String name) {
        for (Connection connection : store.connections()) {
            if (connection.getName().equals(name)) {
                return connection;
            }
        }
        return null;
    }

    private void deleteActive() {
        Object chosen = active.getSelectedItem();
        if (chosen == null) {
            return;
        }
        List<Connection> kept = new ArrayList<Connection>();
        for (Connection connection : store.connections()) {
            if (!connection.getName().equals(chosen.toString())) {
                kept.add(connection);
            }
        }
        // Its keys stay known, so what is written later keeps them out (review 1.144.0
        // privacy-01).
        de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor.noteKnownKeys(
                store.connections(), environment, store::rememberedKey);
        store.forgetKey(chosen.toString());
        store.saveConnections(kept);
        store.setActiveConnection(kept.isEmpty() ? "" : kept.get(0).getName());
        loadConnections();
        onChange.run();
    }

    private void switchLocalOnly() {
        ProjectFolder folder = project.get();
        if (folder == null) {
            return;
        }
        try {
            folder.setLocalModelsOnly(localOnly.isSelected());
            onChange.run();
        } catch (IOException unwritable) {
            localOnly.setSelected(folder.isLocalModelsOnly());
            JOptionPane.showMessageDialog(this, "The switch could not be saved: "
                    + unwritable.getMessage(), "Local models only", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void switchSaveConversations() {
        ProjectFolder folder = project.get();
        if (folder == null) {
            return;
        }
        try {
            if (saveConversations.isSelected()
                    && mine(folder) == ProjectFolder.ConversationSharing.UNDECIDED) {
                ProjectFolder.ConversationSharing answer = sharingAsker.get();
                if (answer == null) {
                    saveConversations.setSelected(false);
                    return;
                }
                store.setConversationSharing(folder.key(), answer.id());
            }
            folder.setSavingConversations(saveConversations.isSelected());
            IOException notInExclude = saveConversations.isSelected()
                    ? folder.ensureConversationRules() : null;
            showSharing(folder);
            onChange.run();
            if (notInExclude != null) {
                // The box stays ticked and saving goes on: the shared assistant/.gitignore holds,
                // and only the person can make their clone's exclude writable (1.149.0, review
                // 1.148.0 recorder-01).
                warner.accept(de.fizkarlsruhe.ise.ontoboard.assistant.history.ConversationRecorder
                        .excludeProblem(folder, notInExclude));
            }
        } catch (IOException unwritable) {
            saveConversations.setSelected(folder.isSavingConversations());
            JOptionPane.showMessageDialog(this, "The switch could not be saved: "
                    + unwritable.getMessage(), "Save conversations", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ for tests

    JCheckBox saveConversationsBox() {
        return saveConversations;
    }

    void setSharingAsker(Supplier<ProjectFolder.ConversationSharing> asker) {
        this.sharingAsker = asker;
    }

    void setWarner(java.util.function.Consumer<String> warner) {
        this.warner = warner;
    }

    String sharingText() {
        return sharingLine.getText();
    }

    JButton changeSharingButton() {
        return changeSharing;
    }

    JCheckBox provenanceBox() {
        return provenance;
    }

    ConnectionEditor connectionEditor() {
        return editor;
    }

    JButton removeButton() {
        return remove;
    }

    RoleEditor roleEditor() {
        return roles;
    }

    JCheckBox localOnlyBox() {
        return localOnly;
    }
}
