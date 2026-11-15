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
}
