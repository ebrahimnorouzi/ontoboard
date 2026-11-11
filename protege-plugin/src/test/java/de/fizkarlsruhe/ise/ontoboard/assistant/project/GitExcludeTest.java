package de.fizkarlsruhe.ise.ontoboard.assistant.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The path as the person opened it, which the clone excludes are worked out from. */
class GitExcludeTest {

    @TempDir
    File dir;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * CI on JDK 25 (run 42611129392): the path as opened was the canonical one, which kept a
     * junction on JDK 11 and resolves it on JDK 24 and later - so no junction was met on the way,
     * and the clone that holds it got no rules though Git for Windows goes into it.
     */
    @Test
    void thePathAsOpenedKeepsAJunctionOnTheWay() throws Exception {
        assumeTrue(isWindows(), "a junction is made only on Windows");
        File target = new File(dir, "real");
        assertTrue(new File(target, "project").mkdirs());
        File link = new File(dir, "link");
        Links.link(link, target);
        Path base = dir.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);

        assertEquals(base.resolve("link").resolve("project"),
                GitExclude.asOpened(new File(link, "project")));
    }

    /** What the canonical form gave before: each name that exists spelled as on disk. */
    @Test
    void thePathAsOpenedIsSpelledAsOnDisk() throws Exception {
        assumeTrue(isWindows(), "names in other capitals are the same file only on Windows here");
        File target = new File(dir, "real");
        assertTrue(new File(target, "Project").mkdirs());
        File link = new File(dir, "link");
        Links.link(link, target);
        Path base = dir.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);

        assertEquals(base.resolve("link").resolve("Project"),
                GitExclude.asOpened(new File(new File(dir, "LINK"), "project")));
    }

    /** A folder not made yet keeps its names as written, after the part that exists. */
    @Test
    void whatIsNotMadeYetIsKeptAsWritten() throws Exception {
        File project = new File(dir, "Project");
        assertTrue(project.mkdirs());

        assertEquals(GitExclude.asOpened(project).resolve("assistant").resolve("conversations"),
                GitExclude.asOpened(new File(new File(project, "assistant"), "conversations")));
    }

    /**
     * A part whose names cannot be read - its parent may be entered but not listed, which cannot
     * be made here - is kept as written, as the canonical form kept it: said at every save, it
     * would read as a link that could not be followed.
     */
    @Test
    void aPathWhoseNamesCannotBeReadIsKeptAsWritten() throws Exception {
        File conversations = new File(new File(dir, "assistant"), "conversations");
        assertTrue(conversations.mkdirs());
        GitExclude.Follow before = GitExclude.spelled;
        GitExclude.spelled = folder -> {
            throw new java.nio.file.AccessDeniedException(folder.toString());
        };
        try {
            assertEquals(conversations.getAbsoluteFile().toPath().normalize(),
                    GitExclude.asOpened(conversations));
        } finally {
            GitExclude.spelled = before;
        }
    }
}
