package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.host.AssistantPreferences;
import de.fizkarlsruhe.ise.ontoboard.assistant.host.FakePreferences;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.Role;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleLibrary;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Settings tab: connections, the key and where it goes, the role, the project switch. */
class SettingsPanelTest {

    private final AssistantPreferences store = new AssistantPreferences(new FakePreferences());
    private final AtomicInteger changes = new AtomicInteger();
    private static final Map<String, String> NO_ENVIRONMENT = Collections.emptyMap();

    @TempDir
    File dir;

    @BeforeEach
    void needsADisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
        store.saveConnections(Arrays.asList(
                Connection.of("Claude", ConnectionKind.ANTHROPIC).withModel("claude-x")));
        store.setActiveConnection("Claude");
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /** What the question about saved conversations answers; null is Cancel. */
    private ProjectFolder.ConversationSharing answer = ProjectFolder.ConversationSharing.PRIVATE;
    private int asked;

    private SettingsPanel panel(ProjectFolder project) {
        SettingsPanel panel = new SettingsPanel(store, () -> project, changes::incrementAndGet,
                NO_ENVIRONMENT);
        panel.setSharingAsker(() -> {
            asked++;
            return answer;
        });
        return panel;
    }

    /** Laid out as in the assistant column: 30 % of a 1440 px window (see the viewconfig). */
    private static javax.swing.JScrollPane laidOut(SettingsPanel panel, int width, int height) {
        javax.swing.JFrame frame = new javax.swing.JFrame();
        frame.setContentPane(panel);
        frame.setSize(width, height);
        frame.addNotify();
        frame.validate();
        return (javax.swing.JScrollPane) panel.getComponent(0);
    }

    /** Every control a person works with, outside any scroll pane of its own. */
    private static void controls(java.awt.Container root, java.util.List<java.awt.Component> out) {
        for (java.awt.Component child : root.getComponents()) {
            if (!child.isVisible() || child instanceof javax.swing.JScrollPane) {
                continue;
            }
            if (child instanceof javax.swing.JLabel || child instanceof javax.swing.AbstractButton
                    || child instanceof javax.swing.text.JTextComponent
                    || child instanceof javax.swing.JComboBox
                    || child instanceof javax.swing.JSpinner) {
                out.add(child);
            } else if (child instanceof java.awt.Container) {
                controls((java.awt.Container) child, out);
            }
        }
    }

    private static String describe(java.awt.Component control) {
        String text = control instanceof javax.swing.JLabel
                ? ((javax.swing.JLabel) control).getText()
                : control instanceof javax.swing.AbstractButton
                        ? ((javax.swing.AbstractButton) control).getText() : "";
        return control.getClass().getSimpleName() + " '" + text + "'";
    }

    /**
     * 1.142.0, the maintainer: "i cannot see Settings > Save conversations in this project". Long
     * labels made the column 1,433 px wide in a 396 px view, and the shorter sections were centred
     * in that: the box was at x = 626, out of sight, with the left of the view empty.
     */
    @Test
    void everyControlFitsTheColumnWidth() throws Exception {
        java.util.List<String> outside = onEdt(() -> {
            javax.swing.JScrollPane scroll = laidOut(panel(ProjectFolder.of(new File(dir,
                    "pizza.owl"))), 430, 800);
            java.awt.Component view = scroll.getViewport().getView();
            int width = scroll.getViewport().getWidth();
            java.util.List<java.awt.Component> found = new java.util.ArrayList<java.awt.Component>();
            controls((java.awt.Container) view, found);
            java.util.List<String> wrong = new java.util.ArrayList<String>();
            if (view.getWidth() > width) {
                wrong.add("the column is " + view.getWidth() + " wide in a view of " + width);
            }
            for (java.awt.Component control : found) {
                java.awt.Rectangle at = javax.swing.SwingUtilities.convertRectangle(
                        control.getParent(), control.getBounds(), view);
                if (at.x < 0 || at.x + at.width > width) {
                    wrong.add(describe(control) + " spans " + at.x + " to " + (at.x + at.width));
                }
            }
            return wrong;
        });

        assertEquals(Collections.<String>emptyList(), outside);
    }

    @Test
    void noLabelOrBoxIsCutOffAtTheColumnWidth() throws Exception {
        java.util.List<String> cut = onEdt(() -> {
            javax.swing.JScrollPane scroll = laidOut(panel(ProjectFolder.of(new File(dir,
                    "pizza.owl"))), 430, 800);
            java.util.List<java.awt.Component> found = new java.util.ArrayList<java.awt.Component>();
            controls((java.awt.Container) scroll.getViewport().getView(), found);
            java.util.List<String> wrong = new java.util.ArrayList<String>();
            for (java.awt.Component control : found) {
                if ((control instanceof javax.swing.JLabel
                        || control instanceof javax.swing.JCheckBox)
                        && control.getPreferredSize().width > control.getWidth()) {
                    wrong.add(describe(control) + " needs " + control.getPreferredSize().width
                            + " and has " + control.getWidth());
                }
            }
            return wrong;
        });

        assertEquals(Collections.<String>emptyList(), cut);
    }

    /**
     * Every component's font made bigger, as a larger system font would. Only this panel's
     * components: changing the look and feel's defaults leaked into the tests that ran after.
     */
    private static void scaleFonts(java.awt.Component root, float scale) {
        if (scale == 1f) {
            return;
        }
        java.awt.Font font = root.getFont();
        if (font != null) {
            root.setFont(font.deriveFont(font.getSize2D() * scale));
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) root).getComponents()) {
                scaleFonts(child, scale);
            }
        }
    }

    /**
     * Review 1.143.0 I-4: below about 350 px of column, or with a larger font, the model field
     * was 29 px wide (-62 px with a 1.5x font), "Fetch the list" was pushed out of sight, and
     * check boxes were cut ("This server is on my network; send witho..."). 430 px with the
     * normal font, the maintainer's own width, was already right.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} px, font x{1}")
    @org.junit.jupiter.params.provider.CsvSource({"430, 1.0", "300, 1.0", "430, 1.5", "300, 1.5"})
    void atEveryWidthAndFontNothingIsOutsideCutOrSqueezed(int width, float scale)
            throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        project.setSavingConversations(true);

        java.util.List<String> wrong = onEdt(() -> {
            {
                SettingsPanel built = panel(project);
                scaleFonts(built, scale);
                javax.swing.JScrollPane scroll = laidOut(built, width, 800);
                java.awt.Component view = scroll.getViewport().getView();
                int room = scroll.getViewport().getWidth();
                java.util.List<java.awt.Component> found =
                        new java.util.ArrayList<java.awt.Component>();
                controls((java.awt.Container) view, found);
                java.util.List<String> problems = new java.util.ArrayList<String>();
                if (view.getWidth() > room) {
                    problems.add("the column is " + view.getWidth() + " wide in a view of "
                            + room);
                }
                for (java.awt.Component control : found) {
                    java.awt.Rectangle at = javax.swing.SwingUtilities.convertRectangle(
                            control.getParent(), control.getBounds(), view);
                    if (at.x < 0 || at.x + at.width > room) {
                        problems.add(describe(control) + " spans " + at.x + " to "
                                + (at.x + at.width));
                    }
                    if ((control instanceof javax.swing.JLabel
                            || control instanceof javax.swing.JCheckBox)
                            && control.getPreferredSize().width > control.getWidth()) {
                        problems.add(describe(control) + " needs "
                                + control.getPreferredSize().width + " and has "
                                + control.getWidth());
                    }
                    if ((control instanceof javax.swing.JTextField
                            || control instanceof javax.swing.JComboBox
                            || control instanceof javax.swing.JSpinner)
                            && control.getWidth() < Math.min(100,
                                    control.getPreferredSize().width)) {
                        problems.add(describe(control) + " is only " + control.getWidth()
                                + " wide");
                    }
                }
                return problems;
            }
        });

        assertEquals(Collections.<String>emptyList(), wrong);
    }

    /** The project's switches are found without scrolling in a column of ordinary height. */
    @Test
    void theSaveConversationsBoxIsInViewWithoutScrolling() throws Exception {
        int[] seen = onEdt(() -> {
            SettingsPanel panel = panel(ProjectFolder.of(new File(dir, "pizza.owl")));
            javax.swing.JScrollPane scroll = laidOut(panel, 430, 800);
            java.awt.Rectangle at = javax.swing.SwingUtilities.convertRectangle(
                    panel.saveConversationsBox().getParent(),
                    panel.saveConversationsBox().getBounds(), scroll.getViewport().getView());
            return new int[] {at.y + at.height, scroll.getViewport().getHeight()};
        });

        assertTrue(seen[0] <= seen[1], "the box ends at y=" + seen[0] + " in a view of " + seen[1]);
    }

    @Test
    void theWheelMovesTheColumnByLinesNotPixels() throws Exception {
        int unit = onEdt(() -> laidOut(panel(null), 430, 800).getVerticalScrollBar()
                .getUnitIncrement(1));

        assertTrue(unit >= 10, "one notch moves " + unit + " x 3 px");
    }

    @Test
    void aNarrowColumnKeepsItsFieldsUsable() throws Exception {
        java.util.List<String> squeezed = onEdt(() -> {
            javax.swing.JScrollPane scroll = laidOut(panel(ProjectFolder.of(new File(dir,
                    "pizza.owl"))), 300, 800);
            java.util.List<java.awt.Component> found = new java.util.ArrayList<java.awt.Component>();
            controls((java.awt.Container) scroll.getViewport().getView(), found);
            java.util.List<String> wrong = new java.util.ArrayList<String>();
            for (java.awt.Component control : found) {
                if (control instanceof javax.swing.JTextField && control.getWidth() < 100) {
                    wrong.add("a field is " + control.getWidth() + " wide");
                }
            }
            return wrong;
        });

        assertEquals(Collections.<String>emptyList(), squeezed);
    }

    @Test
    void rememberingTheKeyNamesWhereItGoes() throws Exception {
        String shown = onEdt(() -> panel(null).connectionEditor().storageText());

        assertTrue(shown.contains(store.keyStorageDescription()), shown);
        assertTrue(shown.contains("plain text"), shown);
    }

    @Test
    void untickingErasesTheStoredKey() throws Exception {
        store.rememberKey("Claude", "sk-x");

        onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            assertTrue(editor.rememberBox().isSelected(), "shows that a key is remembered");
            editor.rememberBox().doClick();
            return null;
        });

        assertFalse(store.isKeyRemembered("Claude"));
    }

    @Test
    void aTypedKeyIsRememberedOnlyWhenTicked() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.typeKey("sk-typed");
            editor.save();
            return null;
        });
        assertFalse(store.isKeyRemembered("Claude"));

        onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.typeKey("sk-typed");
            editor.rememberBox().setSelected(true);
            editor.save();
            return null;
        });
        assertEquals("sk-typed", store.rememberedKey("Claude"));
    }

    @Test
    void savingAConnectionStoresItAndMakesItActive() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.fill("Ollama", ConnectionKind.OLLAMA, "http://localhost:11434", "llama3.1");
            editor.save();
            return null;
        });

        assertEquals(2, store.connections().size());
        assertEquals("Ollama", store.activeConnection());
        assertEquals("llama3.1", store.active().getModel());
        assertTrue(changes.get() > 0);
    }

    /** The maintainer asked for Gemini: one choice fills the address and where the key is read. */
    @Test
    void theGeminiPresetFillsTheAddressAndTheKeyVariable() throws Exception {
        Connection shown = onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.newConnection();
            editor.choosePreset("Google Gemini");
            return editor.read();
        });

        assertEquals(ConnectionKind.OPENAI_COMPATIBLE, shown.getKind());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai",
                shown.getBaseUrl());
        assertEquals("GEMINI_API_KEY", shown.getKeyVariable());
        assertEquals("Google Gemini", shown.getName());
    }

    @Test
    void theOpenAiPresetUsesOpenAisAddressAndVariable() throws Exception {
        Connection shown = onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.newConnection();
            editor.choosePreset("OpenAI");
            return editor.read();
        });

        assertEquals("https://api.openai.com/v1", shown.getBaseUrl());
        assertEquals("OPENAI_API_KEY", shown.getKeyVariable());
    }

    /** Review M12: a team's own variable name is kept, not reset to the kind's default. */
    @Test
    void anEditedKeyVariableIsSavedAsTyped() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.fill("Team", ConnectionKind.OPENAI_COMPATIBLE, "https://llm.example.org/v1",
                    "m");
            editor.setKeyVariable("TEAM_LLM_KEY");
            editor.save();
            return null;
        });

        assertEquals("TEAM_LLM_KEY", store.active().getKeyVariable());
    }

    @Test
    void reopeningAConnectionKeepsItsKeyVariable() throws Exception {
        store.saveConnections(Arrays.asList(Connection.of("Team",
                ConnectionKind.OPENAI_COMPATIBLE).withKeyVariable("TEAM_LLM_KEY")));
        store.setActiveConnection("Team");

        Connection shown = onEdt(() -> panel(null).connectionEditor().read());

        assertEquals("TEAM_LLM_KEY", shown.getKeyVariable());
    }

    /** Round-1 M6: a server's error text was shown as Swing HTML, markup and all. */
    @Test
    void serverTextIsShownAsText() throws Exception {
        Object line = onEdt(() -> panel(null).connectionEditor().resultLine());

        // A text area has no way to render markup; a label does (1.142.0: Note).
        assertTrue(line instanceof javax.swing.JTextArea, String.valueOf(line));
    }

    /**
     * Review R12 (1.138.0): a model name from a server is a name, never HTML. The guard was set
     * after the look and feel's label had taken the first name, so the first one shown was still
     * rendered (the review of the 1.151.0 ui fix): no HTML view is made for it.
     */
    @Test
    void modelNamesAreShownAsText() throws Exception {
        Object[] seen = onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            java.awt.Component shown = editor.modelRenderer().getListCellRendererComponent(
                    new javax.swing.JList<String>(), "<html><b>x</b>", 0, false, false);
            return new Object[] {((javax.swing.JComponent) shown).getClientProperty("html.disable"),
                ((javax.swing.JComponent) shown).getClientProperty("html")};
        });

        assertEquals(Boolean.TRUE, seen[0]);
        assertEquals(null, seen[1], "an HTML view was made for the first name");
    }

    /** This person's choice for their own conversations in the project, as stored. */
    private String mine(ProjectFolder project) {
        return store.conversationSharing(project.key());
    }

    /**
     * 1.142.0, the maintainer: ask "whether user wants to gitignore it or release them". Since
     * 1.144.0 (review 1.143.0 I-1) the answer is this person's, kept in their preferences: one
     * person's Release in the shared project file published everyone's conversations.
     */
    @Test
    void tickingAsksAndKeepPrivateKeepsMineOutOfGit() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        answer = ProjectFolder.ConversationSharing.PRIVATE;

        String line = onEdt(() -> {
            SettingsPanel panel = panel(project);
            panel.saveConversationsBox().doClick();
            return panel.sharingText();
        });

        assertEquals(1, asked);
        assertTrue(project.isSavingConversations());
        assertEquals("private", mine(project));
        assertTrue(new File(project.getDirectory(), ".gitignore").isFile());
        assertTrue(line.contains("git ignores"), line);
    }

    /**
     * Review 1.148.0 recorder-01: the clone's own exclude could not be written, and ticking the
     * box said nothing; the conversations rest on assistant/.gitignore alone until the person
     * makes the file writable. The box stays ticked: saving goes on.
     */
    @Test
    void tickingWithAnExcludeThatCannotBeWrittenSaysWhichFile() throws Exception {
        File repo = new File(dir, "repo");
        File git = new File(repo, ".git");
        new File(git, "objects").mkdirs();
        new File(git, "refs").mkdirs();
        java.nio.file.Files.write(new File(git, "HEAD").toPath(),
                "ref: refs/heads/main\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File exclude = new File(new File(git, "info"), "exclude");
        exclude.getParentFile().mkdirs();
        java.nio.file.Files.write(exclude.toPath(), new byte[0]);
        org.junit.jupiter.api.Assumptions.assumeTrue(exclude.setWritable(false),
                "the exclude cannot be made read-only here");
        java.util.List<String> warnings = new java.util.ArrayList<String>();
        ProjectFolder project = ProjectFolder.of(new File(new File(repo, "onto"),
                "pizza-edit.owl"));
        answer = ProjectFolder.ConversationSharing.PRIVATE;
        try {
            boolean ticked = onEdt(() -> {
                SettingsPanel panel = panel(project);
                panel.setWarner(warnings::add);
                panel.saveConversationsBox().doClick();
                return panel.saveConversationsBox().isSelected();
            });

            assertTrue(ticked, "the box stays ticked");
            assertTrue(project.isSavingConversations());
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains(exclude.getCanonicalPath()), warnings.get(0));
            assertTrue(warnings.get(0).contains("Make that file writable."), warnings.get(0));
        } finally {
            exclude.setWritable(true);
        }
    }

    @Test
    void tickingAndReleasingSendsMineToTheReleasedFolder() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        answer = ProjectFolder.ConversationSharing.RELEASED;

        String line = onEdt(() -> {
            SettingsPanel panel = panel(project);
            panel.saveConversationsBox().doClick();
            return panel.sharingText();
        });

        assertTrue(project.isSavingConversations());
        assertEquals("released", mine(project));
        assertTrue(new File(project.getDirectory(), ".gitignore").isFile(),
                "the block is the same for everyone");
        assertTrue(line.contains("released/"), line);
        assertTrue(line.contains("commit"), line);
    }

    @Test
    void cancellingTheQuestionLeavesTheBoxOffAndWritesNothing() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        answer = null;

        boolean ticked = onEdt(() -> {
            SettingsPanel panel = panel(project);
            panel.saveConversationsBox().doClick();
            return panel.saveConversationsBox().isSelected();
        });

        assertFalse(ticked);
        assertFalse(project.isSavingConversations());
        assertFalse(project.getDirectory().exists(), "not even the folder");
        assertEquals("", mine(project));
    }

    /** Once decided it is shown, not asked again; "Change…" is the way to ask again. */
    @Test
    void aChoiceIsAskedOnceAndCanBeChanged() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));

        String changed = onEdt(() -> {
            SettingsPanel panel = panel(project);
            panel.saveConversationsBox().doClick();
            panel.saveConversationsBox().doClick();
            panel.saveConversationsBox().doClick();
            assertEquals(1, asked);
            answer = ProjectFolder.ConversationSharing.RELEASED;
            panel.changeSharingButton().doClick();
            return mine(project);
        });

        assertEquals(2, asked);
        assertEquals("released", changed);
        assertTrue(project.isSavingConversations(), "changing the choice leaves the switch on");
    }

    @Test
    void cancellingAChangeKeepsTheChoice() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        store.setConversationSharing(project.key(), "private");
        project.setSavingConversations(true);
        answer = null;

        onEdt(() -> {
            panel(project).changeSharingButton().doClick();
            return null;
        });

        assertEquals("private", mine(project));
    }

    /** Saving switched on, and this person has not chosen: the state is said, and can be. */
    @Test
    void anUndecidedProjectSaysSo() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        project.setSavingConversations(true);

        String[] seen = onEdt(() -> {
            SettingsPanel panel = panel(project);
            return new String[] {panel.sharingText(),
                String.valueOf(panel.changeSharingButton().isVisible())};
        });

        assertTrue(seen[0].contains("Not decided"), seen[0]);
        assertEquals("true", seen[1]);
    }

    /** Review 1.143.0 I-1: a colleague's Release in the project file does not decide for me. */
    @Test
    void aReleaseInTheProjectFileDoesNotDecideForMe() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        project.getDirectory().mkdirs();
        java.nio.file.Files.write(new File(project.getDirectory(), "project.properties").toPath(),
                "saveConversations=true\nreleaseConversations=true\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String line = onEdt(() -> panel(project).sharingText());

        assertTrue(line.contains("Not decided"), line);
        assertEquals("", mine(project));
    }

    /** Review 1.143.0 I-1: a choice made elsewhere - another window - shows when Settings does. */
    @Test
    void theSharingLineIsReadAgainWhenSettingsIsShown() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        project.setSavingConversations(true);
        SettingsPanel panel = onEdt(() -> panel(project));
        store.setConversationSharing(project.key(), "released");

        onEdt(() -> {
            panel.setVisible(false);
            panel.setVisible(true);
            return null;
        });
        String line = onEdt(() -> panel.sharingText());

        assertTrue(line.contains("Released"), line);
    }

    /**
     * Review 1.144.0 settings-ui: after the ontology got its first file, the role section still
     * said edits stay in Protege's preferences, while Save wrote them next to the ontology.
     */
    @Test
    void theRoleLineFollowsTheProjectWhenItIsShown() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ProjectFolder> current =
                new java.util.concurrent.atomic.AtomicReference<ProjectFolder>();
        SettingsPanel panel = onEdt(() -> new SettingsPanel(store, current::get,
                changes::incrementAndGet, NO_ENVIRONMENT));
        current.set(ProjectFolder.of(new File(dir, "pizza-edit.owl")));

        onEdt(() -> {
            panel.setVisible(false);
            panel.setVisible(true);
            return null;
        });
        String where = onEdt(() -> panel.roleEditor().whereText());

        assertTrue(where.contains("roles/"), where);
        assertFalse(where.contains("preferences"), where);
    }

    /** A Settings tab whose project the test changes, as the ontology's first save does. */
    private SettingsPanel following(AtomicReference<ProjectFolder> current) throws Exception {
        return onEdt(() -> new SettingsPanel(store, current::get, changes::incrementAndGet,
                NO_ENVIRONMENT));
    }

    /** The role files next to the ontology, by name. */
    private static java.util.List<String> roleFiles(ProjectFolder project) {
        File[] files = new File(project.getDirectory(), "roles").listFiles();
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (File file : files == null ? new File[0] : files) {
            names.add(file.getName());
        }
        Collections.sort(names);
        return names;
    }

    private static void choose(RoleEditor editor, String id) {
        javax.swing.JComboBox<Role> box = editor.roleBox();
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).getId().equals(id)) {
                box.setSelectedIndex(i);
            }
        }
    }

    /**
     * Review 1.145.0 ui-2: saving the ontology for the first time neither shows nor hides the
     * Settings tab, so the role line kept saying Protégé's preferences. The view calls this
     * method when the ontology is saved.
     */
    @Test
    void theRoleLineFollowsTheFirstSaveWhileSettingsStaysOpen() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);
        current.set(ProjectFolder.of(new File(dir, "pizza-edit.owl")));

        String where = onEdt(() -> {
            panel.projectMayHaveChanged();
            return panel.roleEditor().whereText();
        });

        assertTrue(where.contains("roles/ next to the ontology"), where);
        assertFalse(where.contains("preferences"), where);
    }

    /**
     * Review 1.145.0 ui-2: with the line still saying Protégé's preferences, Save wrote the role
     * next to the ontology, where git sees it. That Save writes nothing and says where the next
     * one writes; the text typed stays for it.
     */
    @Test
    void aSaveAfterTheOntologyGotAFileWritesOnlyOnceTheLineSaysWhere() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        String typed = "My own role: I review pizza ontologies for my thesis.";

        Object[] first = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typed);
            current.set(project);
            editor.saveButton().doClick();
            return new Object[] {roleFiles(project), editor.whereText(), editor.typedText(),
                editor.saveButton().isEnabled()};
        });
        boolean inPreferences = new RoleLibrary(store.roles()).get("ontology-expert").isEdited();
        java.util.List<String> second = onEdt(() -> {
            panel.roleEditor().saveButton().doClick();
            return roleFiles(project);
        });

        assertEquals(Collections.<String>emptyList(), first[0], "the first Save wrote");
        assertTrue(((String) first[1]).contains("roles/ next to the ontology"), (String) first[1]);
        assertEquals(typed, first[2]);
        assertEquals(Boolean.TRUE, first[3]);
        assertFalse(inPreferences, "the first Save wrote into Protégé's preferences");
        assertEquals(Arrays.asList("ontology-expert.md"), second);
        String written = new String(java.nio.file.Files.readAllBytes(new File(new File(
                project.getDirectory(), "roles"), "ontology-expert.md").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(written.contains(typed), written);
    }

    /**
     * Review 1.145.0 ui-4: a role text typed and not saved was replaced by the role's stored text
     * at the next show or hide after the ontology's first save. It stays, on the same role, with
     * Save enabled, and the line says where Save now writes.
     */
    @Test
    void aRoleBeingTypedIsKeptWhenTheProjectChanges() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);
        String typed = "My own role text, typed and not yet saved.";
        onEdt(() -> {
            choose(panel.roleEditor(), "knowledge-engineer");
            panel.roleEditor().setText(typed);
            return null;
        });
        current.set(ProjectFolder.of(new File(dir, "pizza-edit.owl")));

        String[] seen = onEdt(() -> {
            panel.dispatchEvent(new java.awt.event.HierarchyEvent(panel,
                    java.awt.event.HierarchyEvent.HIERARCHY_CHANGED, panel, panel.getParent(),
                    java.awt.event.HierarchyEvent.SHOWING_CHANGED));
            RoleEditor editor = panel.roleEditor();
            return new String[] {editor.typedText(), editor.whereText(),
                ((Role) editor.roleBox().getSelectedItem()).getId(),
                String.valueOf(editor.saveButton().isEnabled())};
        });

        assertEquals(typed, seen[0]);
        assertTrue(seen[1].contains("roles/ next to the ontology"), seen[1]);
        assertEquals("knowledge-engineer", seen[2]);
        assertEquals("true", seen[3]);
    }

    /** Text typed is kept when the project changes, not when Reset asks for the shipped text. */
    @Test
    void resetStillReplacesATypedText() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        new RoleLibrary(project.roles(Collections::<String>emptyList)).edit("ontology-expert",
                "An edited role, saved before.");

        String shown = onEdt(() -> {
            RoleEditor editor = panel(project).roleEditor();
            editor.setText("Typed, not saved.");
            resetButton(editor).doClick();
            return editor.typedText();
        });

        assertEquals(RoleLibrary.shipped("ontology-expert"), shown);
    }

    private static javax.swing.AbstractButton resetButton(RoleEditor editor) {
        for (java.awt.Component part : ((java.awt.Container) editor.saveButton().getParent())
                .getComponents()) {
            if ("Reset".equals(((javax.swing.AbstractButton) part).getText())) {
                return (javax.swing.AbstractButton) part;
            }
        }
        throw new AssertionError("no Reset button beside Save");
    }

    /**
     * Review 1.145.0 ui-2, for Reset as for Save: with the line still saying Protégé's
     * preferences, Reset removed the edit kept next to the ontology - a team's, shared by git.
     */
    @Test
    void aResetAfterTheOntologyGotAFileRemovesNothing() throws Exception {
        new RoleLibrary(store.roles()).edit("ontology-expert", "Edited in the preferences.");
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        new RoleLibrary(project.roles(Collections::<String>emptyList)).edit("ontology-expert",
                "Edited for the team.");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        String where = onEdt(() -> {
            current.set(project);
            resetButton(panel.roleEditor()).doClick();
            return panel.roleEditor().whereText();
        });

        assertEquals(Arrays.asList("ontology-expert.md"), roleFiles(project),
                "the team's edit was reset");
        assertTrue(new RoleLibrary(store.roles()).get("ontology-expert").isEdited(),
                "the edit in the preferences was reset");
        assertTrue(where.contains("roles/ next to the ontology"), where);
    }

    // ------------------------------------- a text typed for one ontology, another's role file

    /** What team B committed for its ontology, and what the person types while A is active. */
    private static final String TEAM_B = "# Ontology expert\n\nTeam B, committed: every answer"
            + " names the BFO class it relies on.\n";
    private static final String TYPED_FOR_A = "# Ontology expert\n\nTyped for A: prefer OWL 2 EL"
            + " and never propose disjointness axioms.";

    /** An ontology in a folder of its own, as a repository of its own is. */
    private ProjectFolder ontologyIn(String folder) {
        return ProjectFolder.of(new File(new File(dir, folder), "pizza.owl"));
    }

    /** A role's file next to an ontology, as it is on disk, or "(no file)". */
    private static String stored(ProjectFolder project, String id) throws java.io.IOException {
        File file = new File(new File(project.getDirectory(), "roles"), id + ".md");
        return file.isFile() ? new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8) : "(no file)";
    }

    private static javax.swing.AbstractButton button(RoleEditor editor, String label) {
        for (java.awt.Component part : ((java.awt.Container) editor.saveButton().getParent())
                .getComponents()) {
            if (label.equals(((javax.swing.AbstractButton) part).getText())) {
                return (javax.swing.AbstractButton) part;
            }
        }
        throw new AssertionError("no " + label + " button beside Save");
    }

    /**
     * Review 1.146.0 ui-1, S1: a role text typed for ontology A stayed on screen when B became
     * active (AssistantView.showCurrent calls reload), and Save wrote it over B's committed role;
     * the line read the same for both. B shows its own role, and the text waits for A.
     */
    @Test
    void aTextTypedForOneOntologyIsNotSavedOverAnothersRole() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        new RoleLibrary(b.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            String lineForA = editor.whereText();
            current.set(b);
            panel.reload();
            String shownForB = editor.typedText();
            String lineForB = editor.whereText();
            editor.saveButton().doClick();
            current.set(a);
            panel.reload();
            return new String[] {lineForA, shownForB, lineForB, editor.typedText()};
        });

        assertEquals(TEAM_B, stored(b, "ontology-expert"), "B's committed role after Save");
        assertEquals(Collections.<String>emptyList(), roleFiles(a), "written for A");
        assertEquals(TEAM_B, seen[1], "what the editor showed while B was active");
        assertFalse(seen[0].equals(seen[2]), "the line reads the same for A and B: " + seen[0]);
        assertTrue(seen[2].contains(new File(b.key()).getParent()), seen[2]);
        assertTrue(seen[2].contains("roles/ next to the ontology"), seen[2]);
        assertTrue(seen[2].contains("kept"), "nothing says the text typed is kept: " + seen[2]);
        assertEquals(TYPED_FOR_A, seen[3], "the text typed for A, once A is active again");
    }

    /**
     * Review of the 1.147.0 ui-1 fix: when B's roles could not be read at the switch - a role file
     * locked by another program, an online-only file - the line moved to B while A's text and
     * role stayed on screen, so reading B again, or going to A and back, saved A's text over B's
     * committed role. A lock stops a read only on Windows.
     */
    @Test
    void aTextTypedForOneOntologyStaysOffAnotherWhoseRolesCannotBeRead() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
                "only Windows refuses to read a locked file");
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        new RoleLibrary(b.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        File role = new File(new File(b.getDirectory(), "roles"), "ontology-expert.md");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String typedForA = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            try (java.io.RandomAccessFile open = new java.io.RandomAccessFile(role, "rw");
                    java.nio.channels.FileLock lock = open.getChannel().lock()) {
                current.set(b);
                panel.reload();
            }
            panel.reload();
            editor.saveButton().doClick();
            current.set(a);
            panel.reload();
            String back = editor.typedText();
            current.set(b);
            panel.reload();
            editor.saveButton().doClick();
            return back;
        });

        assertEquals(TEAM_B, stored(b, "ontology-expert"), "B's committed role");
        assertEquals(TYPED_FOR_A, typedForA, "the text typed for A, once A is active again");
    }

    /**
     * Review 1.146.0 ui-1, S2: once B was active, the text typed for A was still on screen, and
     * Reset, enabled by B's committed edit, removed B's file. The person presses Reset to throw
     * away the text they typed, where it is on screen.
     */
    @Test
    void aResetMeantForTheTextTypedForOneOntologyKeepsAnothersRole() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        new RoleLibrary(b.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            current.set(b);
            panel.reload();
            String shownForB = editor.typedText();
            if (TYPED_FOR_A.equals(shownForB)) {
                resetButton(editor).doClick();
            }
            current.set(a);
            panel.reload();
            return new String[] {shownForB, editor.typedText()};
        });

        assertEquals(TEAM_B, stored(b, "ontology-expert"), "B's committed role after Reset");
        assertEquals(TEAM_B, seen[0], "what the editor showed while B was active");
        assertEquals(TYPED_FOR_A, seen[1], "the text typed for A, once A is active again");
    }

    /**
     * Review 1.146.0 ui-1, S4: A and B each have a role "Reviewer" of their own. Once B was
     * active, the text typed for A's was still on screen, and Delete, which asks nothing,
     * removed B's committed file. The person presses Delete where that text is on screen.
     */
    @Test
    void aDeleteMeantForOneOntologysRoleKeepsAnothersRole() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).create("Reviewer",
                "A's reviewer.");
        new RoleLibrary(b.roles(Collections::<String>emptyList)).create("Reviewer",
                "B's reviewer, committed by team B.");
        String committedA = stored(a, "reviewer");
        String committedB = stored(b, "reviewer");
        String typed = "# Reviewer\n\nA's reviewer, being edited.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "reviewer");
            editor.setText(typed);
            current.set(b);
            panel.reload();
            String shownForB = editor.typedText();
            if (typed.equals(shownForB)) {
                button(editor, "Delete").doClick();
            }
            current.set(a);
            panel.reload();
            return new String[] {shownForB, editor.typedText(),
                ((Role) editor.roleBox().getSelectedItem()).getId()};
        });

        assertEquals(committedB, stored(b, "reviewer"), "B's committed role after Delete");
        assertEquals(committedA, stored(a, "reviewer"), "A's committed role");
        assertEquals(committedB, seen[0], "what the editor showed while B was active");
        assertEquals(typed, seen[1], "the text typed for A, once A is active again");
        assertEquals("reviewer", seen[2]);
    }

    /**
     * Review 1.146.0 ui-1: a text typed before the ontology's first save is still carried to its
     * new folder (1.146.0, review 1.145.0 ui-4), but the line says that where edits go has
     * changed, so the next Save is made knowing where it writes.
     */
    @Test
    void aTextCarriedToTheOntologysFirstFolderSaysWhereEditsGoHasChanged() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);
        ProjectFolder project = ontologyIn("pizza");
        String typed = "Typed before the ontology had a file.";

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typed);
            current.set(project);
            panel.projectMayHaveChanged();
            String kept = editor.typedText();
            String line = editor.whereText();
            editor.saveButton().doClick();
            return new String[] {kept, line};
        });

        assertEquals(typed, seen[0]);
        assertTrue(seen[1].toLowerCase(java.util.Locale.ROOT).contains(
                "where edits go has changed"), seen[1]);
        assertTrue(seen[1].contains("roles/ next to the ontology"), seen[1]);
        assertEquals(typed, stored(project, "ontology-expert"), "the Save after that line");
    }

    /**
     * The review of the ui-2 fix: a waiting text's role was matched ignoring case alone, so where
     * the place holds two roles whose ids differ only in case - the preferences, or a folder on
     * Linux - a text typed for one came back on the other when it was picked, with the plain
     * line, and Save wrote it into the other's file. The role's own id comes first.
     */
    @Test
    void aTextTypedForOneOfTwoRolesDifferingInCaseStaysWithIt() throws Exception {
        store.roles().save("reviewer", "# reviewer\n\nThe lower one, stored.");
        store.roles().save("Reviewer", "# Reviewer\n\nThe upper one, stored.");
        store.setRoleId("reviewer");
        SettingsPanel panel = panel(null);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            javax.swing.JComboBox<Role> box = editor.roleBox();
            editor.setText("# reviewer\n\nTyped for the lower one.");
            for (int i = 0; i < box.getItemCount(); i++) {
                if (box.getItemAt(i).getId().equals("Reviewer")) {
                    box.setSelectedIndex(i);
                }
            }
            String upper = editor.typedText();
            String line = editor.whereText();
            for (int i = 0; i < box.getItemCount(); i++) {
                if (box.getItemAt(i).getId().equals("reviewer")) {
                    box.setSelectedIndex(i);
                }
            }
            return new String[] {upper, line, editor.typedText()};
        });

        assertEquals("# Reviewer\n\nThe upper one, stored.", seen[0], "Reviewer, picked");
        assertTrue(seen[1].contains("The text you typed for reviewer is kept"), seen[1]);
        assertEquals("# reviewer\n\nTyped for the lower one.", seen[2], "reviewer, picked again");
    }

    /**
     * Review 1.149.0 ui-2: on Linux a text typed for one's own "reviewer" and carried by the
     * first save was saved over a team's committed Reviewer.md, which there is another file.
     * Only where the file system keeps case: on Windows and macOS the two names are one file.
     */
    @Test
    void whereCaseIsKeptAnOwnRoleIsNotSavedOverATeamRoleInOtherCapitals() throws Exception {
        File roles = new File(new File(new File(dir, "g"), "assistant"), "roles");
        roles.mkdirs();
        File team = new File(roles, "Reviewer.md");
        java.nio.file.Files.write(team.toPath(), "# Reviewer\n\nTEAM: the committed checklist."
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assumptions.assumeFalse(new File(roles, "reviewer.md").exists(),
                "names in other capitals are one file here");
        store.roles().save("reviewer", "# reviewer\n\nMine.");
        store.setRoleId("reviewer");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText("# reviewer\n\nMINE: my private reviewer text.");
            current.set(ontologyIn("g"));
            panel.projectMayHaveChanged();
            button(editor, "Save").doClick();
            return null;
        });

        assertEquals("# Reviewer\n\nTEAM: the committed checklist.", new String(
                java.nio.file.Files.readAllBytes(team.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        File mine = new File(roles, "reviewer.md");
        assertTrue(mine.isFile(), "Save adds the role of one's own");
        assertTrue(new String(java.nio.file.Files.readAllBytes(mine.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).contains("MINE"));
    }

    /**
     * Review 1.146.0 ui-2: a role of your own, typed and carried to the ontology's first folder,
     * is not in that folder yet. Delete deleted nothing there and set the role choice back to
     * Ontology expert; it is off until Save adds the role, and the line says so.
     */
    @Test
    void aRoleCarriedToAFolderThatDoesNotHoldItCannotBeDeletedThere() throws Exception {
        Role own = new RoleLibrary(store.roles()).create("My role", "Mine.");
        store.setRoleId(own.getId());
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);
        String typed = "# My role\n\nMine, edited.";

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typed);
            current.set(ontologyIn("pizza"));
            panel.projectMayHaveChanged();
            javax.swing.AbstractButton delete = button(editor, "Delete");
            boolean offered = delete.isEnabled();
            delete.doClick();
            return new Object[] {offered, store.roleId(), editor.typedText(),
                editor.whereText()};
        });

        assertEquals(Boolean.FALSE, seen[0], "Delete offered for a role this folder lacks");
        assertEquals("my-role", seen[1], "the role choice");
        assertEquals(typed, seen[2]);
        assertTrue(((String) seen[3]).contains("Save adds it"), (String) seen[3]);
    }

    /**
     * Review 1.146.0 ui-5: after "Not saved: ... Press Save again to save there.", picking
     * another role replaced the text typed, and the line still asked for a second Save.
     */
    @Test
    void pickingAnotherRoleAfterANotSavedLineSaysOnlyWhereEditsGo() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText("Typed, not saved.");
            current.set(ontologyIn("pizza"));
            editor.saveButton().doClick();
            String refused = editor.whereText();
            choose(editor, "knowledge-engineer");
            return new String[] {refused, editor.whereText()};
        });

        assertTrue(seen[0].contains("Not saved"), seen[0]);
        assertFalse(seen[1].contains("Not saved"), seen[1]);
        assertFalse(seen[1].contains("Save again"), seen[1]);
        assertTrue(seen[1].contains("roles/ next to the ontology"), seen[1]);
    }

    /**
     * Review 1.146.0 ui-1: the view reads Settings again when the same ontology is reloaded, as
     * when another becomes active. The text typed for it stays (1.146.0, review 1.145.0 ui-4).
     */
    @Test
    void aTextTypedStaysWhenTheSameOntologyIsReadAgain() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(
                ontologyIn("a"));
        SettingsPanel panel = following(current);

        String kept = onEdt(() -> {
            panel.roleEditor().setText(TYPED_FOR_A);
            panel.reload();
            return panel.roleEditor().typedText();
        });

        assertEquals(TYPED_FOR_A, kept);
    }

    /**
     * Review 1.146.0 ui-1: the text typed for A waits for A on the role it was typed on. When
     * another role was picked meanwhile, A shows that one, the role the assistant uses; the line
     * says the text is kept, and picking its role shows it again.
     */
    @Test
    void aTextWaitingOnAnotherRoleComesBackWhenThatRoleIsPicked() throws Exception {
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(
                ontologyIn("a"));
        SettingsPanel panel = following(current);
        String typed = "Typed for A's knowledge engineer.";

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "knowledge-engineer");
            editor.setText(typed);
            current.set(ontologyIn("b"));
            panel.reload();
            choose(editor, "ontology-expert");
            current.set(ontologyIn("a"));
            panel.reload();
            String shownOnA = ((Role) editor.roleBox().getSelectedItem()).getId();
            String line = editor.whereText();
            choose(editor, "knowledge-engineer");
            return new String[] {shownOnA, line, editor.typedText()};
        });

        assertEquals("ontology-expert", seen[0], "the role the assistant uses");
        assertTrue(seen[1].contains("kept") && seen[1].contains("pick it"), seen[1]);
        assertEquals(typed, seen[2], "the text typed for A, once its role is picked");
    }

    /**
     * Review 1.147.0 ui-1: a text typed on A's own role, carried by Save As to a folder that does
     * not hold the role ("Reviewer is not there yet: Save adds it"), was put aside when B became
     * active ("kept until it is active again") and never shown again: the role was not in A's
     * list any more, so it could not be picked.
     */
    @Test
    void aTextOnARoleTheNewFolderLacksComesBackAfterAnotherOntology() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        ProjectFolder g = ontologyIn("g");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).create("Reviewer",
                "A's reviewer.");
        String typed = "# Reviewer\n\nA's reviewer, being edited.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "reviewer");
            editor.setText(typed);
            current.set(g);
            panel.projectMayHaveChanged();
            current.set(b);
            panel.reload();
            current.set(g);
            panel.reload();
            Role selected = (Role) editor.roleBox().getSelectedItem();
            String[] back = {editor.typedText(), selected == null ? "" : selected.getId(),
                editor.whereText()};
            editor.saveButton().doClick();
            return back;
        });

        assertEquals(typed, seen[0], "the text typed for A, once A is active again");
        assertEquals("reviewer", seen[1]);
        assertTrue(seen[2].contains("Save adds it"), seen[2]);
        assertEquals(typed, stored(g, "reviewer"), "the Save after A is active again");
    }

    /** Review 1.147.0 ui-1: the same for a role of your own kept in Protégé's preferences. */
    @Test
    void aTextOnAPreferencesRoleComesBackAfterTheFirstSaveAndAnotherOntology()
            throws Exception {
        Role own = new RoleLibrary(store.roles()).create("My role", "Mine.");
        store.setRoleId(own.getId());
        ProjectFolder first = ontologyIn("pizza");
        ProjectFolder b = ontologyIn("b");
        String typed = "# My role\n\nMine, edited.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typed);
            current.set(first);
            panel.projectMayHaveChanged();
            current.set(b);
            panel.reload();
            current.set(first);
            panel.reload();
            Role selected = (Role) editor.roleBox().getSelectedItem();
            return new String[] {editor.typedText(), selected == null ? "" : selected.getId()};
        });

        assertEquals(typed, seen[0], "the text typed, once the ontology is active again");
        assertEquals("my-role", seen[1]);
    }

    /**
     * Review 1.147.0 ui-4, S8: a text typed while the ontology had no file, carried by its first
     * save to a folder where the team's role is committed, was shown again after another ontology
     * without "Where edits go has changed": Save then wrote a text written as a private
     * preference edit over the team's role, and nothing said so.
     */
    @Test
    void aCarriedTextStillSaysWhereEditsGoHasChangedAfterAnotherOntology() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder f = ontologyIn("f");
        new RoleLibrary(f.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        String typed = "Typed while the ontology had no file.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typed);
            current.set(f);
            panel.projectMayHaveChanged();
            String carried = editor.whereText();
            current.set(a);
            panel.reload();
            current.set(f);
            panel.reload();
            String[] back = {carried, editor.typedText(), editor.whereText()};
            editor.saveButton().doClick();
            return back;
        });

        String changed = "where edits go has changed";
        assertTrue(seen[0].toLowerCase(java.util.Locale.ROOT).contains(changed), seen[0]);
        assertEquals(typed, seen[1], "the text typed, once its ontology is active again");
        assertTrue(seen[2].toLowerCase(java.util.Locale.ROOT).contains(changed),
                "the line once the ontology is active again: " + seen[2]);
        assertTrue(seen[2].contains(new File(f.key()).getParent()), seen[2]);
        assertEquals(typed, stored(f, "ontology-expert"), "the Save after that line");
    }

    /**
     * Review 1.147.0 ui-4: the same when another role was picked meanwhile, and the carried text's
     * role is picked again to go on with it.
     */
    @Test
    void aCarriedTextPickedAgainAfterAnotherOntologySaysWhereEditsGoHasChanged()
            throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder f = ontologyIn("f");
        new RoleLibrary(f.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        String typed = "Typed while the ontology had no file.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typed);
            current.set(f);
            panel.projectMayHaveChanged();
            current.set(a);
            panel.reload();
            choose(editor, "knowledge-engineer");
            current.set(f);
            panel.reload();
            String waiting = editor.whereText();
            choose(editor, "ontology-expert");
            return new String[] {waiting, editor.typedText(), editor.whereText()};
        });

        assertTrue(seen[0].contains("pick it"), seen[0]);
        assertEquals(typed, seen[1], "the text typed, once its role is picked");
        assertTrue(seen[2].toLowerCase(java.util.Locale.ROOT).contains(
                "where edits go has changed"), "the line once its role is picked: " + seen[2]);
    }

    // ------------------------------- 1.149.0: the role shown picked again, every text typed kept

    /** What a click on the open list's selected row does (BasicComboPopup, JDK 11 :907). */
    private static void pickTheRoleShownAgain(RoleEditor editor) {
        javax.swing.JComboBox<Role> box = editor.roleBox();
        box.setSelectedIndex(box.getSelectedIndex());
    }

    private static void assumeWindows() {
        Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
                "only Windows refuses to read a locked file");
    }

    /** The settings tab shown again, as the view or its tab showing does. */
    private static void shownAgain(SettingsPanel panel) {
        panel.dispatchEvent(new java.awt.event.HierarchyEvent(panel,
                java.awt.event.HierarchyEvent.HIERARCHY_CHANGED, panel, panel.getParent(),
                java.awt.event.HierarchyEvent.SHOWING_CHANGED));
    }

    /** The roles in the list whose id is "reviewer" in any case. */
    private static String reviewers(RoleEditor editor) {
        java.util.List<String> found = new java.util.ArrayList<String>();
        for (int i = 0; i < editor.roleBox().getItemCount(); i++) {
            if (editor.roleBox().getItemAt(i).getId().equalsIgnoreCase("reviewer")) {
                found.add(editor.roleBox().getItemAt(i).getId());
            }
        }
        return found.toString();
    }

    private static String selectedId(RoleEditor editor) {
        Role selected = (Role) editor.roleBox().getSelectedItem();
        return selected == null ? "" : selected.getId();
    }

    /**
     * Review 1.148.0 ui-1: clicking the role already shown in the open Role list - the list
     * fires an action for the same item (JDK 11 JComboBox.setSelectedItem) - replaced the text
     * typed with the role's stored text, without a word.
     */
    @Test
    void pickingTheRoleShownAgainKeepsTheTextTyped() throws Exception {
        ProjectFolder a = ontologyIn("a");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(a));

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            String line = editor.whereText();
            pickTheRoleShownAgain(editor);
            return new String[] {line, editor.typedText(), editor.whereText()};
        });

        assertEquals(TYPED_FOR_A, seen[1], "the text typed, after the role shown was picked again");
        assertEquals(seen[0], seen[2]);
    }

    /** Review 1.148.0 ui-1: the same for the role's first letter typed on the focused list. */
    @Test
    void typingTheFirstLetterOfTheRoleShownKeepsTheTextTyped() throws Exception {
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(ontologyIn("a")));

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            javax.swing.JComboBox<Role> box = editor.roleBox();
            AtomicInteger picks = new AtomicInteger();
            box.addActionListener(event -> picks.incrementAndGet());
            editor.setText(TYPED_FOR_A);
            String line = editor.whereText();
            java.awt.event.KeyEvent key = new java.awt.event.KeyEvent(box,
                    java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                    java.awt.event.KeyEvent.VK_O, 'O');
            for (java.awt.event.KeyListener listener : box.getKeyListeners()) {
                listener.keyPressed(key);
            }
            return new Object[] {picks.get(), line, editor.typedText(), editor.whereText()};
        });

        assertTrue((Integer) seen[0] > 0, "the key picked no role");
        assertEquals(TYPED_FOR_A, seen[2], "the text typed, after O was typed on the list");
        assertEquals(seen[1], seen[3]);
    }

    /**
     * Review 1.148.0 ui-1, with 1.148.0's ui-4: a text carried by the first save and brought back
     * after another ontology lost the text and its "Where edits go has changed" when its role was
     * picked again.
     */
    @Test
    void aTextBroughtBackKeepsItsWarningWhenItsRoleIsPickedAgain() throws Exception {
        ProjectFolder p = ontologyIn("p");
        new RoleLibrary(p.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>();
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            current.set(p);
            panel.projectMayHaveChanged();
            current.set(ontologyIn("b"));
            panel.reload();
            current.set(p);
            panel.reload();
            String before = editor.whereText();
            pickTheRoleShownAgain(editor);
            return new String[] {before, editor.typedText(), editor.whereText()};
        });

        String changed = "Where edits go has changed";
        assertTrue(seen[0].contains(changed), seen[0]);
        assertEquals(TYPED_FOR_A, seen[1], "the text brought back, its role picked again");
        assertTrue(seen[2].contains(changed), seen[2]);
    }

    /**
     * Review 1.147.0 ui-3 (deferred): picking another role while a text was typed replaced the
     * text. It waits for its role, the line says so, and picking the role brings it back.
     */
    @Test
    void pickingAnotherRoleKeepsTheTextTypedForItsRole() throws Exception {
        ProjectFolder a = ontologyIn("a");
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(a));

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            choose(editor, "knowledge-engineer");
            String shown = editor.typedText();
            String line = editor.whereText();
            choose(editor, "ontology-expert");
            return new String[] {shown, line, editor.typedText()};
        });

        assertAll(
            () -> assertEquals(RoleLibrary.shipped("knowledge-engineer"), seen[0]),
            () -> assertTrue(seen[1].contains("The text you typed for Ontology expert is kept:"
                    + " pick it to go on or to Discard it."), seen[1]),
            () -> assertEquals(TYPED_FOR_A, seen[2], "the text typed, once its role is picked"),
            () -> assertEquals(Collections.<String>emptyList(), roleFiles(a), "written"));
    }

    /**
     * Review 1.147.0 ui-3: one text waited per project, so the next one put aside replaced it.
     * Texts typed for two roles of one ontology both wait, and the line names both.
     */
    @Test
    void textsTypedForTwoRolesOfOneOntologyBothWait() throws Exception {
        String forKnowledgeEngineer = "# Knowledge engineer\n\nTyped for A's knowledge engineer.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(
                ontologyIn("a"));
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            choose(editor, "knowledge-engineer");
            editor.setText(forKnowledgeEngineer);
            choose(editor, "domain-expert");
            String line = editor.whereText();
            current.set(ontologyIn("b"));
            panel.reload();
            current.set(ontologyIn("a"));
            panel.reload();
            String lineBack = editor.whereText();
            choose(editor, "knowledge-engineer");
            String second = editor.typedText();
            choose(editor, "ontology-expert");
            return new String[] {line, lineBack, second, editor.typedText()};
        });

        assertAll(
            () -> assertTrue(seen[0].contains("Ontology expert")
                    && seen[0].contains("Knowledge engineer") && seen[0].contains("kept"), seen[0]),
            () -> assertTrue(seen[1].contains("Ontology expert")
                    && seen[1].contains("Knowledge engineer") && seen[1].contains("kept"), seen[1]),
            () -> assertEquals(forKnowledgeEngineer, seen[2]),
            () -> assertEquals(TYPED_FOR_A, seen[3]));
    }

    /**
     * Review 1.147.0 ui-3: after Save, Reset, or the same ontology read again, the line no longer
     * named the role a text typed here waits on, so nothing said it was kept.
     */
    @Test
    void theLineStillNamesTheTextKeptAfterSaveResetAndAReread() throws Exception {
        ProjectFolder a = ontologyIn("a");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            current.set(ontologyIn("b"));
            panel.reload();
            choose(editor, "knowledge-engineer");
            current.set(a);
            panel.reload();
            editor.setText("# Knowledge engineer\n\nEdited for A and saved.");
            editor.saveButton().doClick();
            String saved = editor.whereText();
            resetButton(editor).doClick();
            String reset = editor.whereText();
            panel.reload();
            return new String[] {saved, reset, editor.whereText()};
        });

        String kept = "The text you typed for Ontology expert is kept: pick it to go on or to"
                + " Discard it.";
        assertAll(
            () -> assertTrue(seen[0].contains(kept), "after Save: " + seen[0]),
            () -> assertTrue(seen[1].contains(kept), "after Reset: " + seen[1]),
            () -> assertTrue(seen[2].contains(kept), "read again: " + seen[2]));
    }

    /**
     * Review 1.147.0 ui-3, S4: A's roles could not be read when A was active again, and the line
     * did not say the text typed for A was kept. Read again for the same ontology, A showed its
     * stored text while the text typed waited unseen, until the next one put aside replaced it.
     */
    @Test
    void aTextKeptWhileItsOntologysRolesCouldNotBeReadComesBackWhenTheyAreReadAgain()
            throws Exception {
        assumeWindows();
        ProjectFolder a = ontologyIn("a");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        File role = new File(new File(a.getDirectory(), "roles"), "ontology-expert.md");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            current.set(ontologyIn("b"));
            panel.reload();
            String unread;
            try (java.io.RandomAccessFile open = new java.io.RandomAccessFile(role, "rw");
                    java.nio.channels.FileLock lock = open.getChannel().lock()) {
                current.set(a);
                panel.reload();
                unread = editor.whereText();
            }
            panel.reload();
            return new String[] {unread, editor.typedText(), editor.whereText()};
        });

        assertAll(
            () -> assertTrue(seen[0].contains("could not be read") && seen[0].contains("kept"),
                    seen[0]),
            () -> assertEquals(TYPED_FOR_A, seen[1], "the text typed for A, read again"),
            () -> assertTrue(seen[2].contains("typed for Ontology expert"), seen[2]));
    }

    /**
     * Review 1.147.0 ui-3, S4: after a failed read, Settings shown again read the roles only when
     * the project had changed, so the list stayed empty until another ontology was active.
     */
    @Test
    void settingsShownAgainReadsTheRolesAgainAfterAFailedRead() throws Exception {
        assumeWindows();
        ProjectFolder a = ontologyIn("a");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        File role = new File(new File(a.getDirectory(), "roles"), "ontology-expert.md");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            current.set(ontologyIn("b"));
            panel.reload();
            try (java.io.RandomAccessFile open = new java.io.RandomAccessFile(role, "rw");
                    java.nio.channels.FileLock lock = open.getChannel().lock()) {
                current.set(a);
                panel.reload();
            }
            shownAgain(panel);
            return new Object[] {editor.roleBox().getItemCount(), editor.typedText(),
                editor.textEnabled()};
        });

        assertAll(
            () -> assertEquals(3, seen[0], "the roles listed once Settings is shown again"),
            () -> assertEquals(TYPED_FOR_A, seen[1]),
            () -> assertEquals(Boolean.TRUE, seen[2]));
    }

    /**
     * Review 1.147.0 ui-3, S9: while B's roles could not be read, the emptied text area could be
     * typed in, and what was typed there was gone at the next switch; nothing said the text typed
     * for A was kept. Nothing can be typed or saved while no role is shown.
     */
    @Test
    void whileTheRolesCannotBeReadNothingCanBeTypedAndTheLineSaysTheTextIsKept()
            throws Exception {
        assumeWindows();
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        new RoleLibrary(b.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        File role = new File(new File(b.getDirectory(), "roles"), "ontology-expert.md");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            Object[] unread;
            try (java.io.RandomAccessFile open = new java.io.RandomAccessFile(role, "rw");
                    java.nio.channels.FileLock lock = open.getChannel().lock()) {
                current.set(b);
                panel.reload();
                unread = new Object[] {editor.whereText(), editor.textEnabled(),
                    editor.saveButton().isEnabled()};
            }
            current.set(a);
            panel.reload();
            return new Object[] {unread[0], unread[1], unread[2], editor.typedText()};
        });

        String line = (String) seen[0];
        assertAll(
            () -> assertTrue(line.contains("could not be read") && line.contains("kept"), line),
            () -> assertEquals(Boolean.FALSE, seen[1], "the text area while no role is shown"),
            () -> assertEquals(Boolean.FALSE, seen[2], "Save while no role is shown"),
            () -> assertEquals(TYPED_FOR_A, seen[3]));
    }

    /**
     * Review 1.147.0 ui-3, S5: a text typed for A's Ontology expert waited while A showed its own
     * role; Delete of that role showed Ontology expert's stored text, and the line no longer
     * mentioned the text typed. It comes back on Ontology expert, and the line says so.
     */
    @Test
    void aTextWaitingForItsRoleComesBackAfterDelete() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder b = ontologyIn("b");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).create("Mine", "A's own role.");
        new RoleLibrary(b.roles(Collections::<String>emptyList)).create("Mine", "B's own role.");
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(TYPED_FOR_A);
            current.set(b);
            panel.reload();
            choose(editor, "mine");
            current.set(a);
            panel.reload();
            String waiting = editor.whereText();
            button(editor, "Delete").doClick();
            return new String[] {waiting, selectedId(editor), editor.typedText(),
                editor.whereText()};
        });

        assertAll(
            () -> assertTrue(seen[0].contains("pick it"), seen[0]),
            () -> assertEquals("ontology-expert", seen[1]),
            () -> assertEquals(TYPED_FOR_A, seen[2], "the text typed for A, after Delete"),
            () -> assertTrue(seen[3].contains("typed for Ontology expert"), seen[3]));
    }

    /**
     * Review 1.148.0, the ui reviewer's R4: a text waiting on A's own Reviewer, while A showed
     * another role, stayed with A's old folder at Save As: Reviewer left the list and nothing was
     * said. The texts waiting for an ontology move with it, as a text typed is carried.
     */
    @Test
    void aTextWaitingOnARoleMovesWithTheOntologyAtSaveAs() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder g = ontologyIn("g");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).create("Reviewer",
                "A's reviewer.");
        String committedA = stored(a, "reviewer");
        String typed = "# Reviewer\n\nA's reviewer, being edited.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "reviewer");
            editor.setText(typed);
            current.set(ontologyIn("b"));
            panel.reload();
            choose(editor, "knowledge-engineer");
            current.set(a);
            panel.reload();
            current.set(g);
            panel.projectMayHaveChanged();
            String moved = editor.whereText();
            choose(editor, "reviewer");
            String[] back = {moved, editor.typedText(), editor.whereText()};
            editor.saveButton().doClick();
            return back;
        });

        assertAll(
            () -> assertTrue(seen[0].contains("The text you typed for Reviewer is kept"),
                    seen[0]),
            () -> assertEquals(typed, seen[1], "the text typed, its role picked after Save As"),
            () -> assertTrue(seen[2].contains("Where edits go has changed")
                    && seen[2].contains("Save adds it"), seen[2]),
            () -> assertEquals(typed, stored(g, "reviewer"), "the Save after Save As"),
            () -> assertEquals(committedA, stored(a, "reviewer"), "A's old folder"));
    }

    /**
     * Review 1.148.0, the ui reviewer's R5: "New role…" given the name of the role a text waits
     * on made the role with its placeholder text and said nothing of the text waiting. The text
     * comes back on the new role, and the line says so.
     */
    @Test
    void aNewRoleNamedAsTheRoleATextWaitsOnShowsThatText() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder g = ontologyIn("g");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).create("Reviewer",
                "A's reviewer.");
        String typed = "# Reviewer\n\nA's reviewer, being edited.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setNameAsker(() -> "Reviewer");
            choose(editor, "reviewer");
            editor.setText(typed);
            current.set(g);
            panel.projectMayHaveChanged();
            current.set(ontologyIn("b"));
            panel.reload();
            choose(editor, "knowledge-engineer");
            current.set(g);
            panel.reload();
            editor.createButton().doClick();
            return new String[] {selectedId(editor), editor.typedText(), editor.whereText()};
        });

        assertAll(
            () -> assertEquals("reviewer", seen[0]),
            () -> assertEquals(typed, seen[1], "the text waiting, after New role Reviewer"),
            () -> assertTrue(seen[2].contains("typed for Reviewer"), seen[2]),
            () -> assertTrue(seen[2].contains("Where edits go has changed"), seen[2]));
    }

    /** "New role…" while a text is typed: the text waits for its role, as when one is picked. */
    @Test
    void aNewRoleKeepsTheTextTypedForTheRoleShown() throws Exception {
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(ontologyIn("a")));

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setNameAsker(() -> "Second");
            editor.setText(TYPED_FOR_A);
            editor.createButton().doClick();
            String made = selectedId(editor);
            String line = editor.whereText();
            choose(editor, "ontology-expert");
            return new String[] {made, line, editor.typedText()};
        });

        assertAll(
            () -> assertEquals("second", seen[0]),
            () -> assertTrue(seen[1].contains("The text you typed for Ontology expert is kept"),
                    seen[1]),
            () -> assertEquals(TYPED_FOR_A, seen[2]));
    }

    /**
     * Review 1.148.0 ui-2, the ui reviewer's R6: the folder holds the team's Reviewer.md and a
     * text was typed on one's own reviewer. The list showed Reviewer twice, the line said
     * "Reviewer is not there yet: Save adds it.", Save wrote over Reviewer.md on Windows and
     * macOS, and the editor jumped to Ontology expert. The text goes on the folder's Reviewer,
     * and the line says that where edits go has changed. Where names in other capitals are one
     * file: on Linux they are two roles (1.150.0, review 1.149.0 ui-2), as
     * whereCaseIsKeptAnOwnRoleIsNotSavedOverATeamRoleInOtherCapitals shows.
     */
    @Test
    void aTextOnARoleGoesOnTheFoldersRoleWhoseNameDiffersOnlyInCase() throws Exception {
        ProjectFolder a = ontologyIn("a");
        ProjectFolder g = ontologyIn("g");
        new RoleLibrary(a.roles(Collections::<String>emptyList)).create("Reviewer",
                "A's reviewer.");
        File gRoles = new File(g.getDirectory(), "roles");
        assertTrue(gRoles.mkdirs());
        File team = new File(gRoles, "Reviewer.md");
        java.nio.file.Files.write(team.toPath(), "# Reviewer\n\nTeam G's reviewer, committed."
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(gRoles, "reviewer.md").exists(),
                "names in other capitals are two files here");
        String typed = "# Reviewer\n\nA's reviewer, being edited.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(a);
        SettingsPanel panel = following(current);

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "reviewer");
            editor.setText(typed);
            current.set(g);
            panel.projectMayHaveChanged();
            String listed = reviewers(editor);
            String line = editor.whereText();
            current.set(ontologyIn("b"));
            panel.reload();
            current.set(g);
            panel.reload();
            String listedBack = reviewers(editor);
            String lineBack = editor.whereText();
            String back = editor.typedText();
            editor.saveButton().doClick();
            return new String[] {listed, line, listedBack, lineBack, back, selectedId(editor),
                editor.typedText()};
        });

        String changed = "Where edits go has changed";
        assertAll(
            () -> assertEquals("[Reviewer]", seen[0], "listed after Save As"),
            () -> assertTrue(seen[1].contains(changed) && !seen[1].contains("not there yet"),
                    seen[1]),
            () -> assertEquals("[Reviewer]", seen[2], "listed after another ontology"),
            () -> assertTrue(seen[3].contains(changed) && !seen[3].contains("not there yet"),
                    seen[3]),
            () -> assertEquals(typed, seen[4]),
            () -> assertEquals(Arrays.asList("Reviewer.md"), roleFiles(g)),
            () -> assertEquals(typed, new String(java.nio.file.Files.readAllBytes(team.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8), "the folder's Reviewer after Save"),
            () -> assertEquals("Reviewer", seen[5], "the role shown after Save"),
            () -> assertEquals(typed, seen[6]));
    }

    // ------------------------------------ 1.150.0: Discard, and two listed roles of one name

    /** A text typed on a role and then abandoned (review 1.149.0 ui-1, B2). */
    private static final String ABANDONED = "# Ontology expert\n\nDRAFT I decided not to keep.";

    /**
     * Review 1.149.0 ui-1, B2: a text typed and abandoned came back whenever its role was shown,
     * and only Save, Reset or Delete - each changing the shared role file - showed the role's
     * stored text again. Discard drops it, shows the stored text, leaves nothing waiting and
     * writes no file.
     */
    @Test
    void discardDropsATextTypedAndShowsTheRolesStoredText() throws Exception {
        ProjectFolder c = ontologyIn("c");
        new RoleLibrary(c.roles(Collections::<String>emptyList)).edit("ontology-expert", TEAM_B);
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(c);
        SettingsPanel panel = following(current);

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(ABANDONED);
            choose(editor, "knowledge-engineer");
            choose(editor, "ontology-expert");
            String back = editor.typedText();
            button(editor, "Discard").doClick();
            String shown = editor.typedText();
            String line = editor.whereText();
            boolean enabled = button(editor, "Discard").isEnabled();
            choose(editor, "knowledge-engineer");
            String lineElsewhere = editor.whereText();
            choose(editor, "ontology-expert");
            String picked = editor.typedText();
            current.set(ontologyIn("b"));
            panel.reload();
            current.set(c);
            panel.reload();
            return new Object[] {back, shown, line, enabled, lineElsewhere, picked,
                editor.typedText()};
        });

        assertAll(
            () -> assertEquals(ABANDONED, seen[0], "the text typed, its role picked again"),
            () -> assertEquals(TEAM_B, seen[1], "shown after Discard"),
            () -> assertFalse(((String) seen[2]).contains("kept")
                    || ((String) seen[2]).contains("shown again"), (String) seen[2]),
            () -> assertEquals(Boolean.FALSE, seen[3], "Discard, with the stored text shown"),
            () -> assertFalse(((String) seen[4]).contains("kept"), "still waits: " + seen[4]),
            () -> assertEquals(TEAM_B, seen[5], "its role picked again after Discard"),
            () -> assertEquals(TEAM_B, seen[6], "after another ontology and back"),
            () -> assertEquals(Arrays.asList("ontology-expert.md"), roleFiles(c)),
            () -> assertEquals(TEAM_B, stored(c, "ontology-expert"), "the team's file"),
            () -> assertFalse(new RoleLibrary(store.roles()).get("ontology-expert").isEdited(),
                    "written into the preferences"));
    }

    /**
     * Review 1.149.0 ui-1, B1: after Save As of A into B's folder, the text typed for A is shown
     * and the one typed for B waits on the same role. Only Save, which wrote A's text over the
     * team's file, or Reset, which deleted that file, reached B's text. Discard drops A's text and
     * brings B's back, saying so, and writes no file.
     */
    @Test
    void discardBringsBackTheNextTextWaitingForTheRole() throws Exception {
        ProjectFolder a = ontologyIn("a1");
        ProjectFolder b = ontologyIn("b1");
        String team = "# Ontology expert\n\nTEAM b, committed.";
        new RoleLibrary(b.roles(Collections::<String>emptyList)).edit("ontology-expert", team);
        String typedForB = "# Ontology expert\n\nTB typed for B.";
        String typedForA = "# Ontology expert\n\nTA typed for A.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(b);
        SettingsPanel panel = following(current);

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typedForB);
            current.set(a);
            panel.reload();
            editor.setText(typedForA);
            current.set(b);
            panel.projectMayHaveChanged();
            String before = editor.whereText();
            String shownBefore = editor.typedText();
            button(editor, "Discard").doClick();
            String after = editor.whereText();
            String back = editor.typedText();
            boolean enabled = button(editor, "Discard").isEnabled();
            button(editor, "Discard").doClick();
            return new Object[] {before, shownBefore, after, back, enabled, editor.typedText(),
                editor.whereText(), button(editor, "Discard").isEnabled()};
        });

        String before = (String) seen[0];
        String after = (String) seen[2];
        assertAll(
            () -> assertEquals(typedForA, seen[1], "shown after Save As"),
            () -> assertTrue(before.contains("Another text you typed for Ontology expert is kept")
                    && before.contains("Discard"), before),
            () -> assertEquals(typedForB, seen[3], "after Discard"),
            () -> assertTrue(after.contains("The text you typed for Ontology expert is shown"
                    + " again: it is not saved yet."), after),
            () -> assertFalse(after.contains("Another text")
                    || after.contains("Where edits go has changed"), after),
            () -> assertEquals(Boolean.TRUE, seen[4], "Discard, with B's text shown"),
            () -> assertEquals(team, seen[5], "after a second Discard"),
            () -> assertFalse(((String) seen[6]).contains("kept"), (String) seen[6]),
            () -> assertEquals(Boolean.FALSE, seen[7], "Discard, with the stored text shown"),
            () -> assertEquals(team, stored(b, "ontology-expert"), "the team's file"),
            () -> assertEquals(Collections.<String>emptyList(), roleFiles(a), "written for A"));
    }

    /** Review 1.149.0 ui-1: Discard is on only while the text shown is not the role's own. */
    @Test
    void discardIsOffWhileTheTextShownIsTheRolesOwn() throws Exception {
        Boolean[] seen = onEdt(() -> {
            RoleEditor editor = panel(ontologyIn("a")).roleEditor();
            javax.swing.AbstractButton discard = button(editor, "Discard");
            boolean fresh = discard.isEnabled();
            editor.setText(TYPED_FOR_A);
            boolean typed = discard.isEnabled();
            editor.setText(RoleLibrary.shipped("ontology-expert"));
            boolean typedBack = discard.isEnabled();
            editor.setText(TYPED_FOR_A);
            choose(editor, "knowledge-engineer");
            return new Boolean[] {fresh, typed, typedBack, discard.isEnabled()};
        });

        assertEquals(Arrays.asList(false, true, false, false), Arrays.asList(seen),
                "nothing typed, typed, the stored text typed back, another role picked");
    }

    /**
     * Review 1.149.0 ui-1: Discard beside Save, in sight and not cut in the column at its
     * narrowest and with a larger font, where a fourth button wraps the row.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} px, font x{1}")
    @org.junit.jupiter.params.provider.CsvSource({"300, 1.0", "300, 1.5", "430, 1.0", "430, 1.5"})
    void discardIsInSightBesideSave(int width, float scale) throws Exception {
        java.util.List<String> wrong = onEdt(() -> {
            SettingsPanel built = panel(ontologyIn("a"));
            scaleFonts(built, scale);
            javax.swing.JScrollPane scroll = laidOut(built, width, 800);
            java.awt.Component view = scroll.getViewport().getView();
            int room = scroll.getViewport().getWidth();
            java.awt.Container row = button(built.roleEditor(), "Discard").getParent();
            java.util.List<String> problems = new java.util.ArrayList<String>();
            for (java.awt.Component part : row.getComponents()) {
                java.awt.Rectangle at = part.getBounds();
                if (!new java.awt.Rectangle(row.getSize()).contains(at)) {
                    problems.add(describe(part) + " at " + at + " in a row of " + row.getSize());
                }
                java.awt.Rectangle seen = SwingUtilities.convertRectangle(row, at, view);
                if (seen.x < 0 || seen.x + seen.width > room) {
                    problems.add(describe(part) + " spans " + seen.x + " to "
                            + (seen.x + seen.width) + " in " + room);
                }
                if (part.getWidth() < part.getPreferredSize().width) {
                    problems.add(describe(part) + " needs " + part.getPreferredSize().width
                            + " and has " + part.getWidth());
                }
            }
            return problems;
        });

        assertEquals(Collections.<String>emptyList(), wrong);
    }

    /** The list's rows as the person reads them: each role through the list's renderer. */
    private static java.util.List<String> rendered(RoleEditor editor) {
        javax.swing.JComboBox<Role> box = editor.roleBox();
        javax.swing.JList<Role> list = new javax.swing.JList<Role>();
        java.util.List<String> rows = new java.util.ArrayList<String>();
        for (int i = 0; i < box.getItemCount(); i++) {
            java.awt.Component row = box.getRenderer().getListCellRendererComponent(list,
                    box.getItemAt(i), i, false, false);
            rows.add(((javax.swing.JLabel) row).getText());
        }
        return rows;
    }

    /** A folder holding reviewer.md and reviewer-2.md, both headed "# Reviewer" (C2). */
    private ProjectFolder twoReviewers() throws java.io.IOException {
        ProjectFolder c = ontologyIn("c");
        RoleLibrary library = new RoleLibrary(c.roles(Collections::<String>emptyList));
        library.create("Reviewer", "TEAM reviewer.");
        library.create("Reviewer 2", "# Reviewer\n\nTEAM second reviewer, same heading.");
        return c;
    }

    /**
     * Review 1.149.0 ui-3, C2: with a text typed on reviewer and reviewer-2 shown, both named
     * Reviewer, the line said "The text you typed for Reviewer is kept: pick it to go on", which
     * sent the person to the role already shown. Each is named by its file, on the line and in
     * the list; a role whose name no other listed role has is named alone. In the list each of
     * the two also has its place among them first, which a narrow column does not cut (review
     * 1.150.0 ui-2).
     */
    @Test
    void twoRolesOfOneNameAreNamedByTheirFiles() throws Exception {
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(twoReviewers()));

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "reviewer");
            editor.setText("# Reviewer\n\nT3 typed on reviewer.");
            choose(editor, "reviewer-2");
            return new Object[] {editor.whereText(), rendered(editor)};
        });

        String line = (String) seen[0];
        assertAll(
            () -> assertTrue(line.contains("The text you typed for Reviewer (reviewer.md) is"
                    + " kept"), line),
            () -> assertEquals(Arrays.asList("Ontology expert", "Knowledge engineer",
                    "Domain expert", "1 · Reviewer (reviewer.md)",
                    "2 · Reviewer (reviewer-2.md)"), seen[1]));
    }

    /**
     * Review 1.149.0 ui-3: texts waiting on two roles of one name were counted by name - "The
     * text you typed for Reviewer is kept" - as if one text waited.
     */
    @Test
    void textsWaitingOnTwoRolesOfOneNameAreCountedPerRole() throws Exception {
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(twoReviewers()));

        String line = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "reviewer");
            editor.setText("# Reviewer\n\nT1 typed on reviewer.");
            choose(editor, "reviewer-2");
            editor.setText("# Reviewer\n\nT2 typed on reviewer-2.");
            choose(editor, "knowledge-engineer");
            return editor.whereText();
        });

        assertTrue(line.contains("The texts you typed for Reviewer (reviewer.md) and Reviewer"
                + " (reviewer-2.md) are kept: pick one to go on"), line);
    }

    /**
     * Review 1.149.0 ui-3, where the ontology has no file yet: a role kept in Protégé's
     * preferences has no file, so two roles of one name are named by their ids.
     */
    @Test
    void twoRolesOfOneNameInThePreferencesAreNamedByTheirIds() throws Exception {
        RoleLibrary library = new RoleLibrary(store.roles());
        library.create("Reviewer", "Mine.");
        library.create("Reviewer 2", "# Reviewer\n\nMine too, same heading.");
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>());

        java.util.List<String> rows = onEdt(() -> rendered(panel.roleEditor()));

        assertEquals(Arrays.asList("Ontology expert", "Knowledge engineer", "Domain expert",
                "1 · Reviewer (reviewer)", "2 · Reviewer (reviewer-2)"), rows);
    }

    // ------------------------- 1.151.0: Discard and its warning, two long roles of one name

    /**
     * Review 1.150.0 ui-1: a text typed while the new ontology had no file waits on Ontology
     * expert; its first save takes it into the folder of another ontology whose own text waits
     * on the same role. Picking the role shows the later text; Discard brings the carried one
     * back. The line was built before that text came back, so it lacked "Where edits go has
     * changed", and the next Save wrote a text typed as a private edit over the team's
     * committed role.
     */
    @Test
    void discardBringsBackACarriedTextWithWhereEditsGoHasChanged() throws Exception {
        File teamDir = new File(dir, "team");
        ProjectFolder team = ProjectFolder.of(new File(teamDir, "pizza.owl"));
        new RoleLibrary(team.roles(Collections::<String>emptyList)).edit("ontology-expert",
                "# Ontology expert\n\nTEAM committed.");
        String typedForNew = "# Ontology expert\n\nTyped while the new ontology had no file.";
        String typedForTeam = "# Ontology expert\n\nTyped for the team's ontology.";
        AtomicReference<ProjectFolder> current = new AtomicReference<ProjectFolder>(null);
        SettingsPanel panel = following(current);

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            editor.setText(typedForNew);
            current.set(team);
            panel.reload();
            editor.setText(typedForTeam);
            choose(editor, "knowledge-engineer");
            current.set(null);
            panel.reload();
            current.set(ProjectFolder.of(new File(teamDir, "new.owl")));
            panel.projectMayHaveChanged();
            choose(editor, "ontology-expert");
            String picked = editor.typedText();
            button(editor, "Discard").doClick();
            return new Object[] {picked, editor.typedText(), editor.whereText()};
        });

        String line = (String) seen[2];
        assertAll(
            () -> assertEquals(typedForTeam, seen[0], "the later text, when the role is picked"),
            () -> assertEquals(typedForNew, seen[1], "after Discard"),
            () -> assertTrue(line.startsWith("Where edits go has changed."), line),
            () -> assertTrue(line.endsWith("is shown again: it is not saved yet."), line));
    }

    /** Two roles named Materials science reviewer, longer than the column (review 1.150.0 ui-2). */
    private ProjectFolder twoLongReviewers() throws java.io.IOException {
        ProjectFolder r = ontologyIn("r");
        File roles = new File(r.getDirectory(), "roles");
        assertTrue(roles.mkdirs());
        for (String[] file : new String[][] {
            {"materials-science-reviewer.md", "# Materials science reviewer\n\nOne."},
            {"materials-science-reviewer-2.md", "# Materials science reviewer\n\nTwo."}}) {
            java.nio.file.Files.write(new File(roles, file[0]).toPath(),
                    file[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return r;
    }

    /** The closed list as it is painted, at the size the column gives it. */
    private static int[] painted(javax.swing.JComboBox<Role> box) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(box.getWidth(),
                box.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        box.paint(g);
        g.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0,
                image.getWidth());
    }

    /**
     * About what the closed list shows of its selected role, cut where the room beside the arrow
     * ends. For the message only: the test compares what is painted.
     */
    private static String cut(javax.swing.JComboBox<Role> box) {
        javax.swing.JLabel row = (javax.swing.JLabel) box.getRenderer()
                .getListCellRendererComponent(new javax.swing.JList<Role>(),
                        box.getItemAt(box.getSelectedIndex()), -1, false, false);
        row.setFont(box.getFont());
        int arrow = 0;
        for (java.awt.Component part : box.getComponents()) {
            if (part instanceof javax.swing.JButton) {
                arrow = part.getWidth();
            }
        }
        java.awt.Insets outer = box.getInsets();
        java.awt.Insets inner = row.getInsets();
        java.awt.Rectangle room = new java.awt.Rectangle(box.getWidth() - outer.left - outer.right
                - arrow - inner.left - inner.right, box.getHeight());
        return SwingUtilities.layoutCompoundLabel(row, row.getFontMetrics(row.getFont()),
                row.getText(), null, row.getVerticalAlignment(), row.getHorizontalAlignment(),
                row.getVerticalTextPosition(), row.getHorizontalTextPosition(), room,
                new java.awt.Rectangle(), new java.awt.Rectangle(), 0);
    }

    /**
     * Review 1.150.0 ui-2: two roles of one name were told apart by their files, written after
     * the name, and the column cuts the end first: at 300 px both read "Materials science
     * reviewer (mat...". Laid out at the column's narrowest, never shown, with the normal and a
     * larger font, the closed list paints the two differently.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "300 px, font x{0}")
    @org.junit.jupiter.params.provider.ValueSource(floats = {1.0f, 1.5f})
    void twoLongRolesOfOneNameReadDifferentlyInTheClosedListAtTheNarrowestColumn(float scale)
            throws Exception {
        ProjectFolder r = twoLongReviewers();

        Object[] seen = onEdt(() -> {
            SettingsPanel built = panel(r);
            scaleFonts(built, scale);
            laidOut(built, 300, 800);
            RoleEditor editor = built.roleEditor();
            javax.swing.JComboBox<Role> box = editor.roleBox();
            choose(editor, "materials-science-reviewer");
            int[] one = painted(box);
            String oneReads = cut(box);
            choose(editor, "materials-science-reviewer-2");
            return new Object[] {Arrays.equals(one, painted(box)), oneReads, cut(box),
                box.getWidth()};
        });

        assertFalse((Boolean) seen[0], "in a list " + seen[3] + " px wide both read alike: '"
                + seen[1] + "' and '" + seen[2] + "'");
    }

    /** Each row's tooltip in the open list: what the renderer gives the row. */
    private static java.util.List<String> rowTips(RoleEditor editor) {
        javax.swing.JComboBox<Role> box = editor.roleBox();
        javax.swing.JList<Role> list = new javax.swing.JList<Role>();
        java.util.List<String> tips = new java.util.ArrayList<String>();
        for (int i = 0; i < box.getItemCount(); i++) {
            tips.add(((javax.swing.JComponent) box.getRenderer().getListCellRendererComponent(
                    list, box.getItemAt(i), i, false, false)).getToolTipText());
        }
        return tips;
    }

    /**
     * Review 1.150.0 ui-2: no tooltip named the file of a role whose row the column cuts. The
     * list's tooltip names the selected role's file, and each row of the open list its own; the
     * line names a role of two by its file, as it did.
     */
    @Test
    void theListsTooltipNamesTheSelectedRolesFile() throws Exception {
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>(twoLongReviewers()));

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            javax.swing.JComboBox<Role> box = editor.roleBox();
            choose(editor, "materials-science-reviewer-2");
            String two = box.getToolTipText();
            choose(editor, "materials-science-reviewer");
            String one = box.getToolTipText();
            editor.setText("# Materials science reviewer\n\nT typed on the first of the two.");
            choose(editor, "materials-science-reviewer-2");
            return new Object[] {one, two, rowTips(editor), editor.whereText()};
        });

        String line = (String) seen[3];
        assertAll(
            () -> assertEquals("File: materials-science-reviewer.md", seen[0]),
            () -> assertEquals("File: materials-science-reviewer-2.md", seen[1]),
            () -> assertEquals(Arrays.asList("File: ontology-expert.md",
                    "File: knowledge-engineer.md", "File: domain-expert.md",
                    "File: materials-science-reviewer.md",
                    "File: materials-science-reviewer-2.md"), seen[2]),
            () -> assertTrue(line.contains("The text you typed for Materials science reviewer"
                    + " (materials-science-reviewer.md) is kept: pick it"), line));
    }

    /**
     * Review 1.150.0 ui-2, while the ontology has no file: a role in Protégé's preferences has
     * no file, so the tooltip names its id. A tooltip never starts with a role's heading: one
     * starting "<html>", committed by anyone, would be shown as HTML.
     */
    @Test
    void theListsTooltipNamesTheIdInThePreferencesAndNeverStartsWithTheHeading()
            throws Exception {
        new RoleLibrary(store.roles()).create("Html", "# <html><b>Bold</b> heading\n\nMine.");
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>());

        String[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "html");
            return new String[] {editor.roleBox().getToolTipText(), rowTips(editor).get(3)};
        });

        assertEquals(Arrays.asList("Id: html, in Protégé's preferences",
                "Id: html, in Protégé's preferences"), Arrays.asList(seen));
    }

    /**
     * Review of the 1.151.0 ui fix: a role whose name no other role has was shown by the look and
     * feel's own label as its heading, so a heading starting "<html>" - in a roles file anyone
     * can commit - was rendered as HTML, in the closed list and in its row, where it may load an
     * image from anywhere. It is shown as text.
     */
    @Test
    void aRoleWhoseHeadingStartsWithHtmlIsShownAsText() throws Exception {
        new RoleLibrary(store.roles()).create("Html", "# <html><b>Bold</b> heading\n\nMine.");
        SettingsPanel panel = following(new AtomicReference<ProjectFolder>());

        Object[] seen = onEdt(() -> {
            RoleEditor editor = panel.roleEditor();
            choose(editor, "html");
            javax.swing.JComboBox<Role> box = editor.roleBox();
            Role html = (Role) box.getSelectedItem();
            javax.swing.ListCellRenderer<? super Role> renderer = box.getRenderer();
            java.awt.Component closed = renderer.getListCellRendererComponent(
                    new javax.swing.JList<Role>(), html, -1, false, false);
            Object closedView = ((javax.swing.JComponent) closed).getClientProperty("html");
            java.awt.Component row = renderer.getListCellRendererComponent(
                    new javax.swing.JList<Role>(), html, 3, true, false);
            return new Object[] {html.getName(), closedView,
                ((javax.swing.JComponent) row).getClientProperty("html")};
        });

        assertTrue(String.valueOf(seen[0]).startsWith("<html>"), String.valueOf(seen[0]));
        assertEquals(null, seen[1], "the closed list rendered the heading as HTML");
        assertEquals(null, seen[2], "its row rendered the heading as HTML");
    }

    /**
     * Review 1.144.0 settings-ui: in another window the Settings tab usually stays selected, so
     * a choice made elsewhere never showed; and provenance was never read again.
     */
    @Test
    void theProjectPartIsReadAgainWhenTheViewIsShownAgain() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        project.setSavingConversations(true);
        SettingsPanel panel = onEdt(() -> panel(project));
        store.setConversationSharing(project.key(), "released");
        store.setProvenance(false);

        onEdt(() -> {
            panel.dispatchEvent(new java.awt.event.HierarchyEvent(panel,
                    java.awt.event.HierarchyEvent.HIERARCHY_CHANGED, panel, panel.getParent(),
                    java.awt.event.HierarchyEvent.SHOWING_CHANGED));
            return null;
        });
        String[] seen = onEdt(() -> new String[] {panel.sharingText(),
            String.valueOf(panel.provenanceBox().isSelected())});

        assertTrue(seen[0].contains("Released"), seen[0]);
        assertEquals("false", seen[1]);
    }

    /** The refresh when Settings is shown must not wipe a key the person is typing. */
    @Test
    void showingSettingsKeepsAHalfTypedKey() throws Exception {
        SettingsPanel panel = onEdt(() -> panel(ProjectFolder.of(new File(dir, "pizza.owl"))));

        onEdt(() -> {
            panel.connectionEditor().typeKey("half-typed");
            panel.setVisible(false);
            panel.setVisible(true);
            return null;
        });
        String typed = onEdt(() -> panel.connectionEditor().typedKey());

        assertEquals("half-typed", typed);
    }

    @Test
    void theQuestionSaysWhatEachChoiceMeans() {
        String question = SettingsPanel.SHARING_QUESTION;

        assertTrue(question.contains("assistant/.gitignore"), question);
        assertTrue(question.contains("Keys are removed"), question);
        assertTrue(question.contains("Anyone who can read the repository"), question);
        assertTrue(question.contains("commits nothing"), question);
        assertTrue(question.contains("colleagues choose for theirs"), question);
    }

    /** Review 1.141.0 I5: the role editor saves into the project with the keys it knows. */
    @Test
    void aRememberedKeyTypedIntoARoleIsNotWrittenToTheProject() throws Exception {
        String key = "Mx7pLq2Rt9Vw4Yz8Ab3Cd6Ef1Gh5Jk0";
        store.rememberKey("Claude", key);
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));

        onEdt(() -> {
            RoleEditor editor = panel(project).roleEditor();
            editor.setText("You help. The key is " + key + ".");
            editor.saveButton().doClick();
            return null;
        });

        File[] roles = new File(project.getDirectory(), "roles").listFiles();
        assertTrue(roles != null && roles.length > 0, "a role file was written");
        for (File role : roles) {
            String written = new String(java.nio.file.Files.readAllBytes(role.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(written.contains(key), role.getName() + ": " + written);
        }
    }

    private static void noRoleHolds(ProjectFolder project, String key)
            throws java.io.IOException {
        File[] roles = new File(project.getDirectory(), "roles").listFiles();
        assertTrue(roles != null && roles.length > 0, "a role file was written");
        for (File role : roles) {
            String written = new String(java.nio.file.Files.readAllBytes(role.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(written.contains(key), role.getName() + ": " + written);
        }
    }

    private void roleSaying(ProjectFolder project, String text) throws Exception {
        onEdt(() -> {
            RoleEditor editor = panel(project).roleEditor();
            editor.setText(text);
            editor.saveButton().doClick();
            return null;
        });
    }

    /** Review 1.144.0 privacy-01: unticking Remember forgot the key, and it was known no more. */
    @Test
    void aKeyForgottenInSettingsIsStillKeptOutOfARole() throws Exception {
        String key = "Fg4tPq8Rt2Vw6Yz0Ab3Cd7Ef1Gh5Jk9";
        store.rememberKey("Claude", key);
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        onEdt(() -> {
            panel(null).connectionEditor().rememberBox().doClick();
            return null;
        });
        assertFalse(store.isKeyRemembered("Claude"));

        roleSaying(project, "You help. The old key was " + key + ".");

        noRoleHolds(project, key);
    }

    /** Review 1.144.0 privacy-01: a corrected key replaced the typed one, which was known no more. */
    @Test
    void aReplacedTypedKeyIsStillKeptOutOfARole() throws Exception {
        String old = "Rp5aQ9wE3rT7yU1iO4pA8sD2fG6hJ0k";
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        try {
            onEdt(() -> {
                ConnectionEditor editor = panel(null).connectionEditor();
                editor.typeKey(old);
                editor.save();
                editor.typeKey("Nw7Corrected0Key1Typed2In3Settings");
                editor.save();
                return null;
            });

            roleSaying(project, "You help. The old key was " + old + ".");

            noRoleHolds(project, old);
        } finally {
            de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys.clear("Claude");
        }
    }

    /** Review 1.144.0 privacy-01: deleting a connection forgot its key. */
    @Test
    void aDeletedConnectionsKeyIsStillKeptOutOfARole() throws Exception {
        String key = "Dl3tEdQ7wR1tY5uI9oP2aS6dF0gH4jK";
        store.rememberKey("Claude", key);
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        onEdt(() -> {
            panel(null).removeButton().doClick();
            return null;
        });
        assertTrue(store.connections().isEmpty(), store.connections().toString());

        roleSaying(project, "You help. The old key was " + key + ".");

        noRoleHolds(project, key);
    }

    /**
     * Review 1.143.0 M-4: Java never sends a name and password written in an address, so such an
     * address cannot work - and it would be kept in plain text and shown in errors. It is refused.
     */
    @Test
    void anAddressWithANameAndPasswordIsNotSaved() throws Exception {
        String said = onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.fill("Proxy", ConnectionKind.OPENAI_COMPATIBLE,
                    "https://ontouser:s3cretPassw0rd@llm.example.org/v1", "m");
            editor.save();
            return editor.resultLine().getText();
        });

        for (Connection saved : store.connections()) {
            assertFalse(saved.getBaseUrl().contains("s3cretPassw0rd"), saved.getBaseUrl());
        }
        assertTrue(said.contains("name and password"), said);
        assertFalse(said.contains("s3cretPassw0rd"), said);
    }

    @Test
    void theSaveConversationsSwitchIsSavedInTheProject() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));

        onEdt(() -> {
            SettingsPanel panel = panel(project);
            assertTrue(panel.saveConversationsBox().isEnabled());
            panel.saveConversationsBox().doClick();
            return null;
        });

        assertTrue(project.isSavingConversations());
    }

    @Test
    void provenanceIsOnUntilSwitchedOff() throws Exception {
        boolean shownOn = onEdt(() -> panel(null).provenanceBox().isSelected());

        onEdt(() -> {
            panel(null).provenanceBox().doClick();
            return null;
        });

        assertTrue(shownOn);
        assertFalse(store.isProvenance());
    }

    @Test
    void theLocalOnlySwitchIsDisabledWithoutAProjectFolder() throws Exception {
        String[] state = onEdt(() -> {
            SettingsPanel panel = panel(null);
            return new String[] {String.valueOf(panel.localOnlyBox().isEnabled()),
                panel.localOnlyBox().getToolTipText()};
        });

        assertEquals("false", state[0]);
        assertTrue(state[1].contains("Save the ontology to a file first"), state[1]);
    }

    @Test
    void theLocalOnlySwitchIsSavedInTheProject() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));

        onEdt(() -> {
            SettingsPanel panel = panel(project);
            assertTrue(panel.localOnlyBox().isEnabled());
            panel.localOnlyBox().doClick();
            return null;
        });

        assertTrue(project.isLocalModelsOnly());
    }

    @Test
    void aRoleOverTheLimitCannotBeSaved() throws Exception {
        StringBuilder long9000 = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            long9000.append('x');
        }

        Object[] state = onEdt(() -> {
            RoleEditor editor = panel(null).roleEditor();
            editor.setText(long9000.toString());
            return new Object[] {editor.saveButton().isEnabled(),
                editor.countLabel().getForeground()};
        });

        assertEquals(Boolean.FALSE, state[0]);
        assertEquals(Color.RED, state[1]);
    }

    @Test
    void ollamaShowsContextLengthAndOthersDoNot() throws Exception {
        boolean[] shown = onEdt(() -> {
            ConnectionEditor editor = panel(null).connectionEditor();
            editor.setKind(ConnectionKind.OLLAMA);
            boolean ollama = editor.isContextLengthShown();
            editor.setKind(ConnectionKind.ANTHROPIC);
            return new boolean[] {ollama, editor.isContextLengthShown()};
        });

        assertTrue(shown[0]);
        assertFalse(shown[1]);
    }

    @Test
    void theEnvironmentVariableIsNamedWhenItIsUsed() throws Exception {
        String shown = onEdt(() -> new SettingsPanel(store, () -> null, () -> { },
                Collections.singletonMap("ANTHROPIC_API_KEY", "sk-env")).connectionEditor()
                .environmentText());

        assertTrue(shown.contains("Using $ANTHROPIC_API_KEY from the environment"), shown);
        assertFalse(shown.contains("sk-env"), shown);
    }
}
