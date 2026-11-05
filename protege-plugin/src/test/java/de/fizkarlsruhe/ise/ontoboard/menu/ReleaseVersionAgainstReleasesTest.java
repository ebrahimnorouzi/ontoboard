package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion;
import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What <i>Release...</i> proposes, and refuses, given the releases a project already has.
 *
 * <p>Found preparing an expert test on two projects made by OntoBoard. Both edit files still
 * declare the scaffold's {@code owl:versionInfo 0.1.0}, because a release never writes the version
 * back, while {@code releases/} goes up to {@code 1.0.0}. The dialog proposed {@code 0.1.1} - from
 * the edit file alone - and accepted it, or {@code 1.0.0} again, which would publish a version
 * below the newest one or write over a release somebody may already have imported.
 */
class ReleaseVersionAgainstReleasesTest {

    private static final String TODAY = "2026-11-05";

    private static void aRelease(File projectRoot, String version, String id) throws Exception {
        File file = Release.releaseFile(projectRoot, id, version);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), "<rdf:RDF/>".getBytes(Charset.forName("UTF-8")));
    }

    private static OntologyVersion v(String text) {
        OntologyVersion parsed = OntologyVersion.parseOrNull(text);
        assertNotNull(parsed, text);
        return parsed;
    }

    @Test
    void theProposalFollowsTheNewestReleaseRatherThanAStaleVersionInfo(@TempDir File project)
            throws Exception {
        for (String each : new String[] {"0.1.0", "0.2.0", "0.4.0", "1.0.0"}) {
            aRelease(project, each, "pizzabfo");
        }
        assertEquals("1.0.1",
                ReleaseAction.proposedVersion(v("0.1.0"), project, "pizzabfo", TODAY).getText());
    }

    @Test
    void aDeclaredVersionAheadOfEveryReleaseIsStillWhatTheProposalFollows(@TempDir File project)
            throws Exception {
        aRelease(project, "1.0.0", "pizza");
        assertEquals("2.3.1",
                ReleaseAction.proposedVersion(v("2.3.0"), project, "pizza", TODAY).getText());
    }

    @Test
    void withoutReleasesTheProposalIsWhatItWasBefore(@TempDir File project) {
        assertEquals("0.1.1",
                ReleaseAction.proposedVersion(v("0.1.0"), project, "pizza", TODAY).getText());
        assertEquals(TODAY, ReleaseAction.proposedVersion(null, project, "pizza", TODAY).getText());
    }

    @Test
    void aSemanticVersionThatIsAlreadyReleasedIsRefused(@TempDir File project) throws Exception {
        aRelease(project, "1.0.0", "pizza");
        String why = ReleaseAction.clashWithReleases(project, "pizza", v("1.0.0"));
        assertNotNull(why);
        assertTrue(why.contains("releases/1.0.0") && why.contains("1.0.1"), why);
    }

    @Test
    void aSemanticVersionBelowTheNewestReleaseIsRefusedWithTheNextOneOffered(
            @TempDir File project) throws Exception {
        aRelease(project, "1.0.0", "pizza");
        String why = ReleaseAction.clashWithReleases(project, "pizza", v("0.1.1"));
        assertNotNull(why);
        assertTrue(why.contains("1.0.0") && why.contains("1.0.1"), why);
    }

    @Test
    void aVersionAboveTheNewestReleaseIsAccepted(@TempDir File project) throws Exception {
        aRelease(project, "1.0.0", "pizza");
        assertNull(ReleaseAction.clashWithReleases(project, "pizza", v("1.2.0")));
        assertNull(ReleaseAction.clashWithReleases(project, "pizza", v("1.0.1")));
    }

    @Test
    void aDatedReleaseMayBeCutAgainOnTheSameDay(@TempDir File project) throws Exception {
        // OBO's generated Makefile writes releases/$(TODAY) and a second run that day replaces it.
        aRelease(project, TODAY, "pizza");
        assertNull(ReleaseAction.clashWithReleases(project, "pizza", v(TODAY)));
    }

    @Test
    void aDatedReleaseOlderThanTheNewestIsRefused(@TempDir File project) throws Exception {
        aRelease(project, TODAY, "pizza");
        assertNotNull(ReleaseAction.clashWithReleases(project, "pizza", v("2026-11-01")));
    }

    @Test
    void releasesInTheOtherConventionDoNotCount(@TempDir File project) throws Exception {
        aRelease(project, "2026-01-01", "pizza");
        assertNull(ReleaseAction.clashWithReleases(project, "pizza", v("0.1.1")));
        assertEquals("0.1.1",
                ReleaseAction.proposedVersion(v("0.1.0"), project, "pizza", TODAY).getText());
    }

    @Test
    void aDirectoryWithoutTheReleaseFileIsNotARelease(@TempDir File project) throws Exception {
        new File(project, "releases/1.0.0").mkdirs();
        assertNull(ReleaseAction.clashWithReleases(project, "pizza", v("0.1.1")));
    }
}
