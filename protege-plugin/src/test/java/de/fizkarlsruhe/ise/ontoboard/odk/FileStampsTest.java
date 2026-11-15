package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which files a run wrote, and which of those it changed: told apart from what the files held
 * and when each was written, before the run and after it.
 */
class FileStampsTest {

    private static final long BEFORE = 1_794_398_400_000L;

    @TempDir
    File folder;

    private File a;
    private File b;
    private File notes;

    @BeforeEach
    void threeFilesOfOneAge() throws IOException {
        a = write("a_import.owl", "Ontology(<https://example.org/a>)\n");
        b = write("b_import.owl", "Ontology(<https://example.org/b>)\n");
        notes = write("a_terms.txt", "https://example.org/A\n");
    }

    private File write(String name, String text) throws IOException {
        File file = new File(folder, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(BEFORE));
        return file;
    }

    @Test
    void whatNobodyTouchedWasNeitherWrittenNorChanged() {
        FileStamps before = FileStamps.of(Arrays.asList(a, b));

        FileStamps after = before.now();

        assertEquals(Collections.<File>emptyList(), after.writtenSince(before));
        assertEquals(Collections.<File>emptyList(), after.changedSince(before));
    }

    /** Written again as it was: written - its time says so - and not changed. */
    @Test
    void aFileWrittenAgainAsItWasIsWrittenAndNotChanged() throws IOException {
        FileStamps before = FileStamps.of(Arrays.asList(a, b));
        Files.write(a.toPath(), Files.readAllBytes(a.toPath()));
        assertTrue(a.setLastModified(BEFORE + 2000));

        FileStamps after = before.now();

        assertEquals(Collections.singletonList(a), after.writtenSince(before));
        assertEquals(Collections.<File>emptyList(), after.changedSince(before));
    }

    @Test
    void aFileWithOtherContentIsWrittenAndChanged() throws IOException {
        FileStamps before = FileStamps.of(Arrays.asList(a, b));
        Files.write(b.toPath(), "Ontology(<https://example.org/b2>)\n".getBytes(
                StandardCharsets.UTF_8));
        assertTrue(b.setLastModified(BEFORE + 2000));

        FileStamps after = before.now();

        assertEquals(Collections.singletonList(b), after.writtenSince(before));
        assertEquals(Collections.singletonList(b), after.changedSince(before));
    }

    /**
     * Another content of the same length within the time the file system tells apart: the
     * content says so, where the time cannot.
     */
    @Test
    void anotherContentAtTheSameTimeAndLengthIsSeenToo() throws IOException {
        FileStamps before = FileStamps.of(Arrays.asList(a, b));
        Files.write(a.toPath(), "Ontology(<https://example.org/z>)\n".getBytes(
                StandardCharsets.UTF_8));
        assertTrue(a.setLastModified(BEFORE));

        FileStamps after = before.now();

        assertEquals(Collections.singletonList(a), after.writtenSince(before));
        assertEquals(Collections.singletonList(a), after.changedSince(before));
    }

    /** Files stamped by name: one that is not there yet, and comes, was written. */
    @Test
    void aFileThatCameIsWrittenAndChanged() throws IOException {
        File later = new File(folder, "c_import.owl");
        FileStamps before = FileStamps.of(Arrays.asList(a, later));
        Files.write(later.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));

        FileStamps after = before.now();

        assertEquals(Collections.singletonList(later), after.writtenSince(before));
        assertEquals(Collections.singletonList(later), after.changedSince(before));
    }

    /** One that went is not among them: there is nothing of it to read again. */
    @Test
    void aFileThatWentIsNotAmongThem() {
        FileStamps before = FileStamps.of(Arrays.asList(a, b));
        assertTrue(b.delete());

        FileStamps after = before.now();

        assertEquals(Collections.<File>emptyList(), after.writtenSince(before));
        assertEquals(Collections.<File>emptyList(), after.changedSince(before));
    }

    /** A folder is listed again: a module that is new there is written; another kind of file is not looked at. */
    @Test
    void aFolderIsListedAgainForTheFilesOfOneKind() throws IOException {
        FileStamps before = FileStamps.inFolder(folder, "_import.owl");
        File c = new File(folder, "c_import.owl");
        Files.write(c.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));
        Files.write(notes.toPath(), "https://example.org/B\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(notes.setLastModified(BEFORE + 2000));

        FileStamps after = before.now();

        assertEquals(Collections.singletonList(c), after.writtenSince(before));
        assertEquals(Collections.singletonList(c), after.changedSince(before));
        assertEquals(Arrays.asList(a, b, c), after.files());
    }

    /** In the order of their paths, whichever order they were named in. */
    @Test
    void theFilesComeInTheOrderOfTheirPaths() throws IOException {
        FileStamps before = FileStamps.of(Arrays.asList(b, a));
        for (File file : Arrays.asList(a, b)) {
            Files.write(file.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(Arrays.asList(a, b), before.now().writtenSince(before));
    }

    /** A folder that is not there holds nothing, and is no error. */
    @Test
    void aFolderThatIsNotThereHoldsNothing() {
        FileStamps none = FileStamps.inFolder(new File(folder, "nowhere"), "_import.owl");

        assertEquals(Collections.<File>emptyList(), none.files());
        assertEquals(Collections.<File>emptyList(), none.now().writtenSince(none));
    }
}
