package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.FallbackEligibility;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkSync;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkYaml;
import de.fizkarlsruhe.ise.ontoboard.odk.SeededProject;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F8: Project configuration's save is carried through to ODK's update ({@link ConfigSync}), so
 * that the Makefile follows the configuration again - without it OntoBoard's engine refuses
 * every target of the project, its Makefile no longer ODK's render. The mistake is made here: a
 * value the Makefile carries is changed and saved; then comes the update the save runs.
 */
class ConfigSaveRunsThroughToTheUpdateTest {

    private static final String NONE = "Neither Docker nor Podman answers on this machine";

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Whoever has the project open; nothing to decide here. */
    private static final class Open implements OdkUpdateForm.Session {
        @Override
        public boolean stopped() {
            return false;
        }

        @Override
        public boolean isOpen(File editFile) {
            return true;
        }

        @Override
        public List<ImportGuard.Decision> decide(List<ImportGuard.Finding> findings) {
            throw new AssertionError("nothing to decide: " + findings);
        }

        @Override
        public String readAgain(File editFile, Map<String, File> imports) {
            return null;
        }

        @Override
        public void writtenAsItWas(File editFile) {
            // Nothing to note.
        }
    }

    @Test
    void aSavedConfigurationLeavesTheMakefileOdksRenderOfIt(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        File ontology = edit.getParentFile();
        File root = ontology.getParentFile().getParentFile();
        File config = new File(ontology, "eggboil-odk.yaml");
        String before = text(config);
        // What the save writes (ProjectConfigAction.save -> writeIfUnchanged).
        OdkBuildSettings.writeIfUnchanged(config, before,
                OdkYaml.withValue(before, "robot_report.fail_on", "WARN"));
        assertNotNull(FallbackEligibility.notOdksMakefile(edit),
                "the Makefile is the old configuration's until the update");

        // What the save runs then (ConfigSync.afterConfigChange -> run), without a container.
        OdkSync.Way way = OdkSync.decide(OdkPin.VERSION, OdkRoute.Use.AUTOMATIC, null, null,
                NONE);
        OperationResult synced = OdkUpdateForm.run("Update project files", root, true,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, null, null, null, NONE, null),
                (directory, command, minutes, sink) -> {
                    throw new IOException("no container was to be started: " + command);
                }, new Open());
        ConfigSync.Done done = ConfigSync.done(way, synced, root);

        assertTrue(done.inLine(), done.said());
        assertNull(FallbackEligibility.notOdksMakefile(edit),
                "the Makefile follows the configuration again");
        assertTrue(text(new File(ontology, "Makefile")).contains("WARN"),
                "with the value saved");
    }
}
