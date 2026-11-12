package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's releases, read from its git tags.
 *
 * <p>ODK keeps a release's files out of git until they are committed, and a release is then a
 * commit and a tag {@code v<version>} - which is where the release history, the comparison with
 * the previous release and the identifiers already given out are read from, for a project that
 * has no {@code releases/} folder of its own.
 */
class GitTagsTest {

    @TempDir
    File root;

    private void git(String... arguments) throws IOException {
        List<String> command = new ArrayList<String>();
        command.addAll(Arrays.asList("git", "-c", "user.name=Test", "-c",
                "user.email=test@example.org", "-c", "core.autocrlf=false"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
    }

    private void release(String version, String content) throws IOException {
        Files.write(new File(root, "eggboil.owl").toPath(), content.getBytes(StandardCharsets.UTF_8));
        git("add", "-f", "eggboil.owl");
        git("commit", "-q", "-m", "release(" + version + ")");
        git("tag", "-a", "v" + version, "-m", "eggboil " + version);
    }

    @BeforeEach
    void aRepositoryWithThreeReleasesAndOtherTags() throws IOException {
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");
        git("init", "-q");
        release("1.0.0", "one\n");
        release("1.10.0", "ten\r\nwith Windows endings and an ä\r\n");
        release("1.2.0", "two\n");
        git("tag", "nightly");
        git("tag", "vNext");
        git("tag", "v2.0.0-rc1");
    }

    /** By version, not by name and not by date made: 1.10.0 is after 1.2.0. */
    @Test
    void theReleasesAreTheVersionTagsNewestFirst() {
        assertEquals(Arrays.asList("v2.0.0-rc1", "v1.10.0", "v1.2.0", "v1.0.0"),
                GitTags.releases(root, ProcessRunner.real()));
    }

    @Test
    void theVersionOfATagIsWhatFollowsTheV() {
        assertEquals("1.10.0", GitTags.versionOf("v1.10.0"));
        assertEquals("2026-11-11", GitTags.versionOf("v2026-11-11"));
        assertNull(GitTags.versionOf("vNext"));
        assertNull(GitTags.versionOf("nightly"));
        assertNull(GitTags.versionOf("1.0.0"), "a release's tag starts with v");
        assertEquals("v1.0.0", GitTags.tagFor("1.0.0"));
    }

    /** Byte for byte: an ontology's letters and line endings are as they were released. */
    @Test
    void aFileAtATagIsWhatWasReleased() {
        assertArrayEquals("one\n".getBytes(StandardCharsets.UTF_8),
                GitTags.fileAt(root, "v1.0.0", "eggboil.owl"));
        assertArrayEquals("ten\r\nwith Windows endings and an ä\r\n"
                .getBytes(StandardCharsets.UTF_8), GitTags.fileAt(root, "v1.10.0", "eggboil.owl"));
    }

    @Test
    void aFileTheReleaseDidNotHaveIsNull() {
        assertNull(GitTags.fileAt(root, "v1.0.0", "eggboil-base.owl"));
        assertNull(GitTags.fileAt(root, "v9.9.9", "eggboil.owl"));
    }

    /**
     * A project may be a folder of a larger repository. Its file is read from that folder, not
     * from the repository's top, where a file of the same name is another thing.
     */
    @Test
    void aProjectInAFolderOfARepositoryReadsItsOwnFile() throws IOException {
        File project = new File(root, "ontologies/eggboil");
        Files.createDirectories(project.toPath());
        Files.write(new File(project, "eggboil.owl").toPath(),
                "the folder's\n".getBytes(StandardCharsets.UTF_8));
        git("add", "-f", "ontologies/eggboil/eggboil.owl");
        git("commit", "-q", "-m", "release(3.0.0)");
        git("tag", "-a", "v3.0.0", "-m", "eggboil 3.0.0");

        assertArrayEquals("the folder's\n".getBytes(StandardCharsets.UTF_8),
                GitTags.fileAt(project, "v3.0.0", "eggboil.owl"));
        assertArrayEquals("two\n".getBytes(StandardCharsets.UTF_8),
                GitTags.fileAt(root, "v3.0.0", "eggboil.owl"));
    }

    /** A folder that is no repository, or a machine without git, has no releases - no error. */
    @Test
    void whereThereIsNoRepositoryThereAreNoReleases(@TempDir File plain) {
        assertEquals(Collections.<String>emptyList(),
                GitTags.releases(plain, ProcessRunner.real()));
        assertEquals(Collections.<String>emptyList(), GitTags.releases(root,
                new ProcessRunner.Runner() {
                    @Override
                    public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                            long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                        throw new IOException("Cannot run program \"git\"");
                    }
                }));
        assertNull(GitTags.fileAt(plain, "v1.0.0", "eggboil.owl"));
    }
}
