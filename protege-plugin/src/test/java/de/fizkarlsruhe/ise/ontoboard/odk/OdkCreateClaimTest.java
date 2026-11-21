package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A new project's first range is its creator's (1.155.0). ODK seeds two ranges allocated to
 * nobody, ONTOLOGY-CREATOR and ADDITIONAL EDITOR, and Protege 5.6 reads them each time the edit
 * file is read again - after every pattern imported into a new project - and asked "No Id Range
 * Found For Current User" every time.
 */
class OdkCreateClaimTest {

    @TempDir
    File parent;

    private OdkProjectConfig egg() {
        return new OdkProjectConfig("eggboil", "Test kitchen ontology",
                "Application ontology for boiling eggs in a test kitchen",
                "https://example.org/eggboil/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", parent);
    }

    /** A machine with nothing on it: no Docker, no Podman, no git. */
    private static ProcessRunner.Runner nothingInstalled() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                throw new IOException("Cannot run program \"" + command.get(0) + "\"");
            }
        };
    }

    private String ranges() throws IOException {
        return new String(Files.readAllBytes(
                new File(parent, "eggboil/src/ontology/eggboil-idranges.owl").toPath()),
                StandardCharsets.UTF_8);
    }

    @Test
    void aNewProjectsFirstRangeIsItsCreators() throws Exception {
        OdkProjectConfig config = egg();
        config.setCreator("Ada Lovelace");

        OdkCreate.create(config, null, nothingInstalled(), false);

        String text = ranges();
        assertTrue(text.contains("allocatedto: \"Ada Lovelace\""), text);
        assertFalse(text.contains("\"ONTOLOGY-CREATOR\""), text);
        assertTrue(text.contains("\"ADDITIONAL EDITOR\""), "the second stays for a second editor");
        assertNotNull(IdRanges.parse(text).rangeFor("Ada Lovelace"));
    }

    @Test
    void withoutACreatorTheRangesAreAsOdkWritesThem() throws Exception {
        OdkCreate.create(egg(), null, nothingInstalled(), false);

        assertTrue(ranges().contains("allocatedto: \"ONTOLOGY-CREATOR\""));
    }

    /** A name the file cannot hold between its quotes: the project is made, and it is said. */
    @Test
    void aNameTheFileCannotHoldIsSaidAndTheProjectIsMade() throws Exception {
        OdkProjectConfig config = egg();
        config.setCreator("Ada \"the\" Lovelace");

        OdkCreate.Created created = OdkCreate.create(config, null, nothingInstalled(), false);

        assertTrue(ranges().contains("allocatedto: \"ONTOLOGY-CREATOR\""));
        assertEquals(1, created.notes().stream().filter(note -> note.contains("ID range"))
                .count(), String.valueOf(created.notes()));
    }
}
