package de.fizkarlsruhe.ise.ontoboard.assistant.project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;

/** A folder reached by another name: a junction on Windows, a symbolic link elsewhere. */
public final class Links {

    private Links() {
    }

    /** Makes {@code link} lead to {@code target}; the test is skipped where that cannot be done. */
    public static void link(File link, File target) throws IOException, InterruptedException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            // A junction needs no privilege, where a symbolic link on Windows does.
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.getAbsolutePath(), target.getAbsolutePath()).redirectErrorStream(true)
                            .start();
            byte[] ignored = new byte[1024];
            while (process.getInputStream().read(ignored) >= 0) {
                // Read it so that cmd never blocks on a full pipe.
            }
            Assumptions.assumeTrue(process.waitFor() == 0 && link.isDirectory(),
                    "no junction could be made");
            return;
        }
        try {
            Files.createSymbolicLink(link.toPath(), target.getAbsoluteFile().toPath());
        } catch (UnsupportedOperationException | IOException cannot) {
            Assumptions.assumeTrue(false, "no symbolic link could be made: " + cannot);
        }
    }

    /**
     * Until closed, a link or a junction of this name cannot be followed to where it really is,
     * as toRealPath fails under a folder that may be entered but not listed - which cannot be
     * made here, where the session may list every folder. For tests outside this package, which
     * cannot reach {@link GitExclude#follow}.
     */
    public static AutoCloseable unfollowable(final String name) {
        final GitExclude.Follow before = GitExclude.follow;
        GitExclude.follow = folder -> {
            if (folder.getFileName() != null && folder.getFileName().toString().equals(name)) {
                throw new java.nio.file.AccessDeniedException(folder.toString(), null,
                        "Access is denied.\r\n");
            }
            return before.of(folder);
        };
        return () -> GitExclude.follow = before;
    }
}
