package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Update project files with nothing standing in, both ways on the same project: ODK's own
 * update in ODK's container, and OntoBoard's without one. The two projects that come of it are
 * held against each other - every file byte for byte, the edit file, which ROBOT writes, by
 * what it says.
 *
 * <p>{@code OdkUpdateTest} holds OntoBoard's update against a recording of ODK's. This asks
 * ODK itself, on a machine that has ODK v1.6.1's image; without it the test is skipped and says
 * so. How long each took is written to {@code target/side-by-side/update.txt}.
 */
class OdkUpdateLiveTest {

    private static String runtime;
    private static String image;
    private static OdkContainer.Host host;

    @BeforeAll
    static void lookForTheImage() {
        runtime = MakeRun.containerRuntime(ProcessRunner.real());
        if (runtime != null) {
            image = OdkImage.pinned(OdkImage.probe(runtime, ProcessRunner.real()));
            host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
        }
    }

    @Test
    void odksOwnUpdateAndOntoBoardsLeaveTheSameProject(@TempDir File temp) throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        File theirs = new File(temp, "theirs");
        File ours = new File(temp, "ours");
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, theirs);
        UpdateCases.write(before, ours);

        long started = System.currentTimeMillis();
        OdkUpdate.Ran inContainer = OdkUpdate.inContainer(runtime, image, host, theirs,
                UpdateCases.planAsOdkDoes(theirs), ProcessRunner.real());
        long odkTook = System.currentTimeMillis() - started;
        started = System.currentTimeMillis();
        List<File> written = OdkUpdate.apply(ours, UpdateCases.planAsOdkDoes(ours));
        long ontoBoardTook = System.currentTimeMillis() - started;

        assertTrue(inContainer.isOk(), inContainer.getFailure() + "\n"
                + String.join("\n", inContainer.getTranscript()));
        assertFalse(written.isEmpty());
        UpdateCases.assertSameProjects(temp, theirs, ours);
        assertEquals(UpdateCases.relative(theirs, inContainer.getWritten()),
                UpdateCases.relative(ours, written), "and both say they wrote the same files");

        File times = new File("target/side-by-side/update.txt");
        Files.createDirectories(times.getParentFile().toPath());
        Files.write(times.toPath(), ("Update project files, the recorded project with work in "
                + "it (egg-changed), one run each\nODK's own update in its container (" + image
                + "): " + odkTook + " ms\nOntoBoard's own: " + ontoBoardTook + " ms\n")
                .getBytes(StandardCharsets.UTF_8));
    }
}
