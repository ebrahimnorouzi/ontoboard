package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.UpdateCases;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Update project files for an ODK project, as the menu does it: first what would change, shown
 * and nothing written; then, when asked, the update - by ODK's own program in its container
 * where that runs, else by OntoBoard itself.
 */
class OdkUpdateFormTest {

    private static final String EDIT = "src/ontology/eggboil-edit.owl";
    private static final String NONE = "Neither Docker nor Podman answers on this machine";

    @TempDir
    File root;

    /** What the container was asked to run. */
    private final List<List<String>> ran = new ArrayList<List<String>>();

    private OdkUpdateForm.Way itself() {
        return OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, null, null, null, NONE, null);
    }

    /** No container is to be started. */
    private ProcessRunner.Runner noContainer() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                throw new IOException("no container was to be started: " + command);
            }
        };
    }

    private static Map<String, String> states(OperationResult result) {
        Map<String, String> states = new LinkedHashMap<String, String>();
        for (List<String> row : result.getRows()) {
            states.put(row.get(0), row.get(1));
        }
        return states;
    }

    private void assertSameTree(Map<String, byte[]> expected) {
        Map<String, byte[]> actual = Goldens.tree(root);
        assertEquals(new TreeSet<String>(expected.keySet()), new TreeSet<String>(actual.keySet()),
                "the files of the project");
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey());
        }
    }

    // ------------------------------------------------------------------ looking

    @Test
    void firstItShowsWhatWouldChangeAndWritesNothing() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);

        OperationResult result = OdkUpdateForm.run("Update project files", root, false,
                itself(), noContainer());

        assertTrue(result.isSuccess(), result.getSummary());
        Map<String, String> states = states(result);
        assertEquals("changes", states.get("src/ontology/Makefile"));
        assertEquals("new", states.get("src/ontology/imports/ro_terms.txt"));
        assertEquals("changes", states.get(".gitignore"));
        assertEquals("as it is", states.get("src/sparql/basic-report.sparql"));
        assertTrue(states.get(EDIT).startsWith("changes"), "the edit file's imports are "
                + "brought in line: " + states.get(EDIT));
        assertFalse(states.containsKey("README.md"), "a file a person writes is not listed: "
                + "the update does not write it");
        assertTrue(result.getSummary().contains("12 files would change"), result.getSummary());
        assertTrue(String.join("\n", result.getLog()).contains("Nothing was written"));
        assertSameTree(before);
        assertEquals(new ArrayList<List<String>>(), ran);
    }

    /** What stops ODK's own update half way is said when looking, before anybody writes. */
    @Test
    void anImportThatCannotBeLoadedIsSaidWhenLooking() {
        Map<String, byte[]> before = UpdateCases.before("egg-unloadable");
        UpdateCases.write(before, root);

        OperationResult result = OdkUpdateForm.run("Update project files", root, false,
                itself(), noContainer());

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertTrue(result.getSummary().contains("whole.owl"), result.getSummary());
        assertSameTree(before);
    }

    @Test
    void aProjectThatIsUpToDateIsSaidToBe() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        OdkUpdateForm.run("Update project files", root, true, itself(), noContainer());

        OperationResult again = OdkUpdateForm.run("Update project files", root, false,
                itself(), noContainer());

        assertTrue(again.isSuccess(), again.getSummary());
        assertTrue(again.getSummary().startsWith("Already up to date"), again.getSummary());
        for (String state : states(again).values()) {
            assertEquals("as it is", state);
        }
    }

    // ------------------------------------------------------------------ writing

    @Test
    void writtenByOntoBoardItselfItIsWhatOdksOwnUpdateLeaves() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);

        OperationResult result = OdkUpdateForm.run("Update project files", root, true,
                itself(), noContainer());

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(12, result.getFiles().size(), result.getFiles().toString());
        assertTrue(result.getFiles().contains(new File(root, EDIT.replace('/',
                File.separatorChar)).getAbsoluteFile()), "the edit file is among them, so that "
                        + "whoever shows it knows to read it again");
        assertTrue(result.getSummary().startsWith("Updated 12 files"), result.getSummary());
        assertTrue(String.join("\n", result.getLog()).contains("by OntoBoard itself"),
                "the log says who made the update");
        Map<String, byte[]> odks = UpdateCases.after("egg-changed");
        Map<String, byte[]> ours = Goldens.tree(root);
        for (Map.Entry<String, byte[]> file : odks.entrySet()) {
            if (!file.getKey().equals(EDIT)) {
                assertArrayEquals(file.getValue(), ours.get(file.getKey()), file.getKey());
            }
        }
        assertEquals(new ArrayList<List<String>>(), ran);
    }

    /** With a container and ODK's image, ODK's own program makes the update. */
    @Test
    void inAContainerOdksOwnProgramMakesIt() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        final File makefile = new File(root, "src/ontology/Makefile");
        ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                Files.write(makefile.toPath(), "# ODK's own\n".getBytes(StandardCharsets.UTF_8));
                return new ProcessRunner.Outcome(0, Arrays.asList(
                        "Ontology repository update successfully completed."), false);
            }
        };

        OperationResult result = OdkUpdateForm.run("Update project files", root, true,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, "docker", "an-image:v1",
                        OdkContainer.Host.unknown(), null, null), container);

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(1, ran.size());
        assertEquals(Arrays.asList("odk.py", "update"),
                ran.get(0).subList(ran.get(0).size() - 2, ran.get(0).size()));
        assertEquals(java.util.Collections.singletonList(makefile.getAbsoluteFile()),
                result.getFiles());
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("$ docker "), log);
        assertTrue(log.contains("Ontology repository update successfully completed."), log);
    }

    /** ODK's own update that stops is a failed result, with what it had written by then. */
    @Test
    void aContainerRunThatStopsIsAFailedResultNamingWhatItWrote() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        final File makefile = new File(root, "src/ontology/Makefile");
        ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                Files.write(makefile.toPath(), "# half\n".getBytes(StandardCharsets.UTF_8));
                return new ProcessRunner.Outcome(1, Arrays.asList(
                        "Traceback (most recent call last):", "RuntimeError: it stopped"), false);
            }
        };

        OperationResult result = OdkUpdateForm.run("Update project files", root, true,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, "docker", "an-image:v1",
                        OdkContainer.Host.unknown(), null, null), container);

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertTrue(result.getSummary().contains("RuntimeError: it stopped"),
                result.getSummary());
        assertEquals(java.util.Collections.singletonList(makefile.getAbsoluteFile()),
                result.getFiles());
    }

    /**
     * OntoBoard's own update that cannot write one of its files stops there; the result names
     * the file and lists what was written before it, so that nobody has to guess.
     */
    @Test
    void anUpdateThatStopsPartWayNamesWhatItHadWritten() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);
        OperationResult look = OdkUpdateForm.run("Update project files", root, false, itself(),
                noContainer());
        String last = null;
        for (Map.Entry<String, String> row : states(look).entrySet()) {
            if (row.getValue().equals("changes") && !row.getKey().equals(EDIT)) {
                last = row.getKey();
            }
        }
        File locked = new File(root, last);
        assertTrue(locked.setReadOnly());
        assumeTrue(!Files.isWritable(locked.toPath()), "this machine writes a file that is "
                + "read-only all the same");
        try {
            OperationResult result = OdkUpdateForm.run("Update project files", root, true,
                    itself(), noContainer());

            assertEquals(OperationResult.Status.FAILED, result.getStatus());
            assertTrue(result.getSummary().contains(locked.getName()), result.getSummary());
            Map<String, byte[]> now = Goldens.tree(root);
            TreeSet<File> changed = new TreeSet<File>();
            for (Map.Entry<String, byte[]> file : now.entrySet()) {
                if (!Arrays.equals(file.getValue(), before.get(file.getKey()))) {
                    changed.add(new File(root, file.getKey()).getAbsoluteFile());
                }
            }
            assertFalse(changed.isEmpty(), "nothing was written before the stop, so this test "
                    + "shows nothing: " + last);
            assertEquals(changed, new TreeSet<File>(result.getFiles()), "what the stopped "
                    + "update had written, and nothing left beside it");
            assertArrayEquals(before.get(EDIT), now.get(EDIT), "the edit file is replaced "
                    + "last: an update that stopped has left it alone");
        } finally {
            locked.setWritable(true);
        }
    }

    /**
     * A project with a file that cannot be read - another program holds it - is a failed
     * result with the reason, not an error. (Where a lock stops nobody from reading, as on
     * Linux, there is nothing to show.)
     */
    @Test
    void aProjectThatCannotBeReadIsAFailedResult() throws IOException {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);
        File makefile = new File(root, "src/ontology/Makefile");
        try (FileChannel held = FileChannel.open(makefile.toPath(), StandardOpenOption.WRITE);
                FileLock lock = held.lock()) {
            boolean read;
            try {
                Files.readAllBytes(makefile.toPath());
                read = true;
            } catch (IOException locked) {
                read = false;
            }
            assumeTrue(!read, "a lock stops nobody from reading on this machine");

            OperationResult result = OdkUpdateForm.run("Update project files", root, true,
                    itself(), noContainer());

            assertEquals(OperationResult.Status.FAILED, result.getStatus());
            assertTrue(result.getSummary().contains("Makefile"), result.getSummary());
            assertTrue(result.getSummary().contains("Nothing was written"),
                    result.getSummary());
            assertEquals(new ArrayList<File>(), result.getFiles());
        }
        assertSameTree(before);
    }

    // ------------------------------------------------------------------ whoever has it open

    /** What a session answers, and what it was asked. */
    private static final class Open implements OdkUpdateForm.Session {
        private final boolean stopped;
        private final String cannotReadAgain;
        private boolean open = true;
        private int readAgain;

        Open(boolean stopped, String cannotReadAgain) {
            this.stopped = stopped;
            this.cannotReadAgain = cannotReadAgain;
        }

        @Override
        public boolean stopped() {
            return stopped;
        }

        @Override
        public boolean isOpen(File editFile) {
            return open;
        }

        @Override
        public String readAgain(File editFile) {
            readAgain++;
            return cannotReadAgain;
        }
    }

    @Test
    void whoStoppedWaitingGetsNothingWritten() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);
        Open open = new Open(true, null);

        OperationResult result = OdkUpdateForm.run("Update project files", root, true, itself(),
                noContainer(), open);

        assertSameTree(before);
        assertTrue(String.join("\n", result.getLog()).contains("Nothing was written"),
                result.getLog().toString());
        assertEquals(0, open.readAgain);
    }

    /** The edit file is rewritten on disk, so what is open is read again from it - once. */
    @Test
    void afterTheEditFileWasWrittenItIsReadAgain() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        Open open = new Open(false, null);

        OperationResult result = OdkUpdateForm.run("Update project files", root, true, itself(),
                noContainer(), open);

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus());
        assertEquals(1, open.readAgain);
        assertTrue(String.join("\n", result.getLog()).contains("read again"),
                result.getLog().toString());

        // Looking, and an update that leaves the edit file as it is, read nothing again.
        OdkUpdateForm.run("Update project files", root, false, itself(), noContainer(), open);
        File makefile = new File(root, "src/ontology/Makefile");
        Files.write(makefile.toPath(), "# changed by hand\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        OperationResult second = OdkUpdateForm.run("Update project files", root, true, itself(),
                noContainer(), open);
        assertEquals(java.util.Collections.singletonList(makefile.getAbsoluteFile()),
                second.getFiles());
        assertEquals(1, open.readAgain);
    }

    /** A project that is updated while something else is open has nothing to read again. */
    @Test
    void whatIsNotOpenIsNotReadAgain() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        Open open = new Open(false, null);
        open.open = false;

        OperationResult result = OdkUpdateForm.run("Update project files", root, true, itself(),
                noContainer(), open);

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(0, open.readAgain);
        assertFalse(String.join("\n", result.getLog()).contains("read again"),
                result.getLog().toString());
    }

    /** The update is made all the same; that what is open is now behind the file is said. */
    @Test
    void whenReadingAgainFailsTheResultWarnsAndSaysWhatToDo() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        Open open = new Open(false, "an import could not be loaded");

        OperationResult result = OdkUpdateForm.run("Update project files", root, true, itself(),
                noContainer(), open);

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus());
        assertTrue(result.getSummary().startsWith("Updated 12 files"), result.getSummary());
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("an import could not be loaded"), log);
        assertTrue(log.contains("File > Reload"), "what to do about it: " + log);
    }

    // ------------------------------------------------------------------ who makes it

    @Test
    void withoutAContainerOntoBoardMakesItUnlessTheSettingIsAlways() {
        assertNull(itself().refusal());
        assertFalse(itself().inContainer());

        OdkUpdateForm.Way always = OdkUpdateForm.way(OdkRoute.Use.ALWAYS_CONTAINER, null, null,
                null, NONE, "Fetch it once with: docker pull an-image (about 5 GB).");
        assertTrue(always.refusal().startsWith(NONE), always.refusal());
        assertTrue(always.refusal().contains(OdkRoute.Use.ALWAYS_CONTAINER.label()),
                always.refusal());
        assertTrue(always.refusal().contains("docker pull an-image"), always.refusal());

        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        OperationResult refused = OdkUpdateForm.run("Update project files", root, true, always,
                noContainer());
        assertEquals(OperationResult.Status.FAILED, refused.getStatus());
        assertEquals(always.refusal(), refused.getSummary());
        assertSameTree(UpdateCases.before("egg-changed"));
    }

    /** Looking writes nothing and starts nothing: nobody refuses it, and it says who would write. */
    @Test
    void lookingIsNeverRefusedAndSaysWhoWouldWrite() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);
        OdkUpdateForm.Way always = OdkUpdateForm.way(OdkRoute.Use.ALWAYS_CONTAINER, null, null,
                null, NONE, "Fetch it once with: docker pull an-image (about 5 GB).");

        OperationResult refusedToWrite = OdkUpdateForm.run("Update project files", root, false,
                always, noContainer());
        OperationResult byItself = OdkUpdateForm.run("Update project files", root, false,
                itself(), noContainer());
        OperationResult inContainer = OdkUpdateForm.run("Update project files", root, false,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, "docker", "an-image:v1",
                        OdkContainer.Host.unknown(), null, null), noContainer());

        assertTrue(refusedToWrite.isSuccess(), refusedToWrite.getSummary());
        assertTrue(refusedToWrite.getSummary().contains("12 files would change"),
                refusedToWrite.getSummary());
        assertTrue(String.join("\n", refusedToWrite.getLog()).contains(always.refusal()),
                "why nobody would write them: " + refusedToWrite.getLog());
        assertTrue(String.join("\n", byItself.getLog()).contains("OntoBoard itself writes them"),
                byItself.getLog().toString());
        assertTrue(String.join("\n", inContainer.getLog()).contains("an-image:v1"),
                inContainer.getLog().toString());
        assertTrue(String.join("\n", inContainer.getLog()).contains("ODK's own update writes "
                + "them"), inContainer.getLog().toString());
        assertEquals(new ArrayList<List<String>>(), ran, "no container is started to look");
        assertSameTree(before);
    }

    @Test
    void aProgramWithoutOdksImageIsNoContainer() {
        OdkUpdateForm.Way way = OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, "docker", null, null,
                "ODK's image is not on this machine", null);

        assertFalse(way.inContainer(), "nothing is fetched to make an update");
        assertNull(way.refusal());
    }

    @Test
    void aFolderThatIsNoOdkProjectIsAFailedResultWithTheReason() {
        assertTrue(new File(root, "src/ontology").mkdirs());

        OperationResult result = OdkUpdateForm.run("Update project files", root, false,
                itself(), noContainer());

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertTrue(result.getSummary().contains("-odk.yaml"), result.getSummary());
    }
}
