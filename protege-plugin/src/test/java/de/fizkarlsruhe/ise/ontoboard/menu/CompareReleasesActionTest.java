package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareReleasesActionTest {

    /**
     * Releases are ordered as versions, not as text.
     *
     * <p>As text, 0.10.0 sorts before 0.9.0, and "From must be an earlier release than To" refused
     * to compare a semantic project's ninth minor release with its tenth.
     */
    @Test
    void versionsCompareAsVersions() {
        assertTrue(CompareReleasesAction.isEarlier("0.9.0", "0.10.0"));
        assertFalse(CompareReleasesAction.isEarlier("0.10.0", "0.9.0"));
        assertFalse(CompareReleasesAction.isEarlier("1.0.0", "1.0.0"));
        assertTrue(CompareReleasesAction.isEarlier("2026-09-30", "2026-10-31"));
    }

    /** The releases a project kept are listed newest first, by version. */
    @Test
    void theReleasesAreListedNewestFirst(@TempDir File project) {
        for (String version : new String[] {"0.9.0", "0.10.0", "0.2.0"}) {
            assertTrue(new File(project, "releases/" + version).mkdirs());
        }
        File edit = new File(project, "src/ontology/menu-edit.owl");

        assertEquals("[0.10.0, 0.9.0, 0.2.0]", CompareReleasesAction.releasesOf(edit).toString());
    }
}
