package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRecord;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release of an ontology in no project records itself too (1.155.0): Release... made it by
 * OntoBoard alone, so its RELEASE.md says what that release checked - the reasoner and what it
 * said, the quality report as it ran - and claims nothing of ODK's.
 */
class StandaloneReleaseRecordTest {

    private static File write(File folder, String path, String text) throws Exception {
        File file = new File(folder, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final String PIZZA = "Prefix(:=<http://example.org/pizza#>)\n"
            + "Ontology(<http://example.org/pizza>\nDeclaration(Class(:Pizza))\n)\n";

    @Test
    void aStandaloneReleaseHasItsRecord(@TempDir File folder) throws Exception {
        File edit = write(folder, "pizza.owl", PIZZA);
        File dated = write(folder, "releases/2026-11-20/pizza.owl", PIZZA);

        File record = ReleaseAction.recordBeside(dated, edit, "pizza", "2026-11-20", "2026-11-20",
                "HermiT said consistent, every class satisfiable.",
                "OntoBoard's quality report, with ROBOT's built-in profile: no errors.", null, null);

        String text = new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8);
        assertEquals(new File(dated.getParentFile(), "RELEASE.md").getAbsoluteFile(), record.getAbsoluteFile());
        assertTrue(text.startsWith("# pizza 2026-11-20\n"), text);
        assertTrue(text.contains("OntoBoard alone"), text);
        assertEquals("HermiT said consistent, every class satisfiable.", ReleaseRecord.verdictIn(text));
        assertTrue(text.contains("OntoBoard's quality report, with ROBOT's built-in profile: no errors."), text);
        assertTrue(text.contains("| Classes |"), "the dated copy measured: " + text);
        assertFalse(text.contains("ODK"), "nothing claimed of ODK's: " + text);
    }
}
