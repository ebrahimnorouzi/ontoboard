package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The History tab: this project's saved conversations, newest first, read-only. */
class HistoryPanelTest {

    @TempDir
    File dir;

    @BeforeEach
    void needsADisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
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

    @Test
    void listsNewestFirstAndShowsThePickedOne() throws Exception {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza-edit.owl"));
        File conversations = new File(project.getDirectory(), "conversations");
        conversations.mkdirs();
        Files.write(new File(conversations, "2026-11-05-090000-ollama.md").toPath(),
                "older".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(conversations, "2026-11-06-143012-claude.md").toPath(),
                "# newer conversation".getBytes(StandardCharsets.UTF_8));

        Object[] seen = onEdt(() -> {
            HistoryPanel panel = new HistoryPanel(() -> project);
            panel.reload();
            List<String> names = panel.listedNames();
            panel.select(0);
            return new Object[] {names, panel.shownText()};
        });

        assertEquals(Arrays.asList("2026-11-06-143012-claude.md", "2026-11-05-090000-ollama.md"),
                seen[0]);
        assertEquals("# newer conversation", seen[1]);
    }

    @Test
    void withoutAProjectItSaysHowToSaveConversations() throws Exception {
        String shown = onEdt(() -> {
            HistoryPanel panel = new HistoryPanel(() -> null);
            panel.reload();
            return panel.shownText();
        });

        assertTrue(shown.contains("Save conversations in this project"), shown);
    }
}
