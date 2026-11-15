package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Project configuration... edits the one {@code *-odk.yaml} beside the open ontology, and says
 * why not where there is none, or more than one.
 */
class ProjectConfigFileTest {

    private static void write(File file) throws Exception {
        Files.write(file.toPath(), "id: egg\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theOneConfigurationBesideTheOntologyIsEdited(@TempDir File directory) throws Exception {
        write(new File(directory, "egg-odk.yaml"));
        // Another YAML file beside it is not a second configuration.
        write(new File(directory, "metadata.yaml"));

        assertNull(ProjectConfigAction.whyNoConfiguration(directory));
        assertEquals(new File(directory, "egg-odk.yaml"), OdkBuildSettings.yamlIn(directory));
    }

    @Test
    void aFolderWithoutOneIsNotAProject(@TempDir File directory) {
        assertEquals("There is no *-odk.yaml in " + directory.getAbsolutePath() + ", so this "
                + "ontology is not in an ODK project and has no project configuration to edit.",
                ProjectConfigAction.whyNoConfiguration(directory));
    }

    @Test
    void twoOfThemAreNotChosenBetween(@TempDir File directory) throws Exception {
        write(new File(directory, "egg-odk.yaml"));
        write(new File(directory, "ham-odk.yaml"));

        assertEquals("There are 2 *-odk.yaml files in " + directory.getAbsolutePath() + ", so "
                + "which of them configures this project cannot be decided. Leave one.",
                ProjectConfigAction.whyNoConfiguration(directory));
    }
}
