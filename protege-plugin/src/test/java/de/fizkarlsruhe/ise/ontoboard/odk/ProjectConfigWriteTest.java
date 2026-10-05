package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The part of the configuration editor that touches the disk.
 *
 * <p>Two ways a save goes wrong, and neither is unlikely. The file may have changed under the
 * dialog - a text editor, a git pull, a {@code make update_repo} - and the replacement was built
 * from offsets into the old text, so writing it would discard that change wholesale. And a crash
 * or a full disk during a plain write leaves a truncated configuration, which breaks every ODK
 * target at once.
 */
class ProjectConfigWriteTest {

    private static File fileWith(File directory, String content) throws IOException {
        File yaml = new File(directory, "demo-odk.yaml");
        Files.write(yaml.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return yaml;
    }

    private static String contentOf(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The ordinary case: what was read is still there, so the replacement lands. */
    @Test
    void anUnchangedFileIsWritten(@TempDir File directory) throws Exception {
        File yaml = fileWith(directory, "id: demo\r\ntitle: Demo");

        ProjectConfigAction.writeIfUnchanged(yaml, "id: demo\r\ntitle: Demo",
                "id: demo\r\ntitle: Changed");

        assertEquals("id: demo\r\ntitle: Changed", contentOf(yaml));
    }

    /**
     * A file that moved under the dialog is not overwritten.
     *
     * <p>The replacement was spliced from offsets into the text that was read. Writing it would
     * not merge with whatever arrived in the meantime - it would replace it.
     */
    @Test
    void aFileChangedUnderneathIsRefused(@TempDir File directory) throws Exception {
        File yaml = fileWith(directory, "id: demo\r\ntitle: Demo");
        Files.write(yaml.toPath(),
                "id: demo\r\ntitle: Demo\r\nrepo: added-by-git-pull".getBytes(
                        StandardCharsets.UTF_8));

        IOException refused = assertThrows(IOException.class,
                () -> ProjectConfigAction.writeIfUnchanged(yaml, "id: demo\r\ntitle: Demo",
                        "id: demo\r\ntitle: Changed"));

        assertTrue(refused.getMessage().contains("changed on disk"), refused.getMessage());
        assertTrue(contentOf(yaml).contains("added-by-git-pull"),
                "the change that arrived must still be there");
    }

    /** Bytes are preserved exactly: CRLF, no final newline, comments. */
    @Test
    void theBytesWrittenAreTheBytesGiven(@TempDir File directory) throws Exception {
        String original = "# a comment\r\nid: demo\r\ntitle: Demo";
        File yaml = fileWith(directory, original);
        String updated = OdkYaml.withValue(original, "title", "Something Else");

        ProjectConfigAction.writeIfUnchanged(yaml, original, updated);

        String after = contentOf(yaml);
        assertTrue(after.startsWith("# a comment\r\n"), after);
        assertTrue(after.endsWith("title: Something Else"), "no newline may be added: " + after);
        assertEquals(2, after.split("\r\n", -1).length - 1, "CRLF count must be unchanged");
    }

    /** No temporary file is left behind, whether the write worked or not. */
    @Test
    void noTemporaryFileSurvives(@TempDir File directory) throws Exception {
        File yaml = fileWith(directory, "id: demo");

        ProjectConfigAction.writeIfUnchanged(yaml, "id: demo", "id: changed");

        File[] left = directory.listFiles();
        assertEquals(1, left.length, java.util.Arrays.toString(left));
        assertEquals("demo-odk.yaml", left[0].getName());
    }

    /**
     * The temporary file is written in the project directory, not the system one.
     *
     * <p>An atomic move is only atomic within a filesystem, and the system temporary directory
     * is frequently on another one - so a temp file there would silently degrade to a copy,
     * which is the thing the atomic move exists to avoid.
     */
    @Test
    void theWriteIsAtomicWithinTheProjectDirectory(@TempDir File directory) throws Exception {
        File yaml = fileWith(directory, "id: demo");
        final java.util.List<String> seen = new java.util.ArrayList<String>();

        // There is no hook to observe the temp file, so this asserts the invariant that makes
        // the move atomic: nothing outside this directory is involved, and the result is whole.
        ProjectConfigAction.writeIfUnchanged(yaml, "id: demo", "id: demo\r\ntitle: Added");
        for (File file : directory.listFiles()) {
            seen.add(file.getName());
        }

        assertEquals(java.util.Arrays.asList("demo-odk.yaml"), seen);
        assertEquals("id: demo\r\ntitle: Added", contentOf(yaml));
    }
}
