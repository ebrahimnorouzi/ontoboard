package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkMigration;
import de.fizkarlsruhe.ise.ontoboard.odk.RecordedProject;
import de.fizkarlsruhe.ise.ontoboard.odk.SeededProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Collections;

/**
 * Release... on a project OntoBoard 1.135 or before made.
 *
 * <p>Up to 1.135 such a project was released here, from what was open, into OntoBoard's own
 * layout. A project is ODK's now and its release is ODK's; a project of the older layout is
 * carried over first, and Release... says so instead of making one more release the old way.
 */
class ReleaseOfAnOlderProjectTest {

    @Test
    void aProjectOfTheOlderLayoutIsCarriedOverBeforeItIsReleased(@TempDir File where)
            throws Exception {
        File edit = RecordedProject.of1352(new File(where, "eggboil"));

        assertEquals(OdkMigration.CARRY_OVER + " After that, Release... makes ODK's release of "
                + "it.", ReleaseAction.carriedOverFirst(edit));
    }

    /** An ODK project is released by ODK; a file in no project is released where it is. */
    @Test
    void anOdkProjectAndAFileInNoProjectAreNotSentAway(@TempDir File where) throws Exception {
        File loose = new File(where, "loose.owl");
        Files.write(loose.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        assertNull(ReleaseAction.carriedOverFirst(SeededProject.in(where)));
        assertNull(ReleaseAction.carriedOverFirst(loose));
        assertNull(ReleaseAction.carriedOverFirst(null));
    }

    // ------------------------------------------------------------------ review of 1.153.0

    /**
     * A release runs make clean first. Where OntoBoard itself would build, it is asked about
     * clean before the form is shown, as it is about the release's own target: a project
     * with a clean of its own was refused only after the form had been filled in.
     */
    @Test
    void theEngineIsAskedAboutCleanBeforeTheFormIsShown(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        assertNull(ReleaseAction.whyTheEngineCannot(edit,
                Collections.singletonMap("VERSION", "1.0.0"))[0], "ODK's own clean");

        File own = new File(edit.getParentFile(), "eggboil.Makefile");
        Files.write(own.toPath(), (new String(Files.readAllBytes(own.toPath()),
                StandardCharsets.UTF_8) + "\nclean:\n\trm -rf build\n")
                        .getBytes(StandardCharsets.UTF_8));

        String[] cannot = ReleaseAction.whyTheEngineCannot(edit,
                Collections.singletonMap("VERSION", "1.0.0"));

        assertTrue(cannot[0] != null && cannot[0].contains("eggboil.Makefile")
                && cannot[0].contains("clean"), String.valueOf(cannot[0]));
        assertEquals(cannot[0], cannot[1], "and so can neither release be built here");
    }
}
