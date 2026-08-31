package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the releases a project has kept, so a new one can be compared with the last.
 *
 * <p>This is what the dated copies are for. A release that overwrote one file - which is what the
 * scaffold's own prepare_release used to do - leaves nothing to compare against, so "what changed"
 * becomes unanswerable the moment it matters.
 */
class ReleaseHistoryTest {

    private static void aRelease(File projectRoot, String date, String id) throws Exception {
        File file = Release.releaseFile(projectRoot, id, date);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
    }

    // ---------- the previous release ----------

    @Test
    void theMostRecentEarlierReleaseIsFound(@TempDir File project) throws Exception {
        aRelease(project, "2026-01-01", "mwo");
        aRelease(project, "2026-06-15", "mwo");
        aRelease(project, "2025-12-31", "mwo");

        assertEquals("2026-06-15",
                ReleaseAction.previousRelease(project, "mwo", "2026-08-31"));
    }

    /** ISO dates sort lexically, which is most of why the format is insisted on elsewhere. */
    @Test
    void datesAreComparedAsDatesNotAsFilenames(@TempDir File project) throws Exception {
        aRelease(project, "2026-09-01", "mwo");
        aRelease(project, "2026-10-01", "mwo");

        assertEquals("2026-09-01",
                ReleaseAction.previousRelease(project, "mwo", "2026-10-01"));
    }

    /** A re-release on the same date compares against the one before it, not against itself. */
    @Test
    void aReleaseOnTheSameDateIsNotItsOwnPredecessor(@TempDir File project) throws Exception {
        aRelease(project, "2026-01-01", "mwo");
        aRelease(project, "2026-08-31", "mwo");

        assertEquals("2026-01-01",
                ReleaseAction.previousRelease(project, "mwo", "2026-08-31"));
    }

    @Test
    void theFirstReleaseHasNoPredecessor(@TempDir File project) throws Exception {
        assertNull(ReleaseAction.previousRelease(project, "mwo", "2026-08-31"));
    }

    /** A dated directory with no ontology in it is not a release to compare against. */
    @Test
    void anEmptyReleaseDirectoryIsNotAPredecessor(@TempDir File project) throws Exception {
        new File(new File(project, "releases"), "2026-01-01").mkdirs();

        assertNull(ReleaseAction.previousRelease(project, "mwo", "2026-08-31"));
    }

    @Test
    void somethingThatIsNotADatedDirectoryIsIgnored(@TempDir File project) throws Exception {
        new File(new File(project, "releases"), "draft").mkdirs();
        aRelease(project, "2026-01-01", "mwo");

        assertEquals("2026-01-01",
                ReleaseAction.previousRelease(project, "mwo", "2026-08-31"));
    }

    // ---------- the list offered for comparison ----------

    @Test
    void releasesAreOfferedNewestFirst(@TempDir File project) throws Exception {
        File ontology = new File(project, "src/ontology/mwo-edit.owl");
        ontology.getParentFile().mkdirs();
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
        aRelease(project, "2026-01-01", "mwo");
        aRelease(project, "2026-06-15", "mwo");

        List<String> releases = CompareReleasesAction.releasesOf(ontology);

        assertEquals("2026-06-15", releases.get(0),
                "the comparison people want is nearly always against the last release");
        assertEquals(2, releases.size());
    }

    @Test
    void aProjectWithNoReleasesOffersNothing(@TempDir File project) throws Exception {
        File ontology = new File(project, "src/ontology/mwo-edit.owl");
        ontology.getParentFile().mkdirs();
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        assertTrue(CompareReleasesAction.releasesOf(ontology).isEmpty());
        assertTrue(CompareReleasesAction.releasesOf(null).isEmpty());
    }

    /**
     * The ODK layout puts releases two directories above the edit file, so a project that keeps
     * them correctly must not appear to have none.
     */
    @Test
    void releasesAreFoundFromTheOdkLayout(@TempDir File project) throws Exception {
        File ontology = new File(project, "src/ontology/mwo-edit.owl");
        ontology.getParentFile().mkdirs();
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
        aRelease(project, "2026-01-01", "mwo");

        assertEquals(1, CompareReleasesAction.releasesOf(ontology).size());
    }
}
