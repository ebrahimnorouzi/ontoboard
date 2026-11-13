package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "Add them" names what it will do before it is pressed. In an ODK project that is more than a
 * module and a catalog entry: the import is declared in the configuration, the project's files
 * follow it, and ODK builds the module - said, because the Makefile and the edit file change.
 */
class FindTermsSaysWhatAnOdkImportDoesTest {

    @Test
    void inAnOdkProjectTheConfigurationAndWhatFollowsItAreNamed() {
        String steps = FindTermsAction.importSteps("demo-odk.yaml");

        assertTrue(steps.contains("demo-odk.yaml"), steps);
        assertTrue(steps.contains("the Makefile, the catalog and your edit file"), steps);
        assertTrue(steps.contains("OntoBoard > Project > Update project files..."), steps);
        assertTrue(steps.contains("ODK's own refresh"), steps);
        assertFalse(steps.contains("with a catalog entry beside it"),
                "the catalog is ODK's own there: " + steps);
        for (String line : steps.split("\n")) {
            assertTrue(line.length() <= 86, "no wider than the lines above it: " + line);
        }
    }

    /** A project of the older layout, or no project at all: as it always said. */
    @Test
    void elsewhereItSaysWhatItAlwaysSaid() {
        assertEquals("  - import that module into your ontology, with a catalog entry beside it\n",
                FindTermsAction.importSteps(null));
    }
}
