package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import org.junit.jupiter.api.Test;

/**
 * After OntoBoard changed a project's configuration - Import terms, the pattern library, a
 * question's check, Project configuration - the project's files are brought in line with it by
 * ODK's own update, in the project's own image, or by OntoBoard's own update for a project on
 * the pinned ODK. A sync never moves a project to another ODK: where neither can run, the
 * configuration alone is written, and that is said.
 */
class OdkSyncTest {

    private static final String NONE = "Neither Docker nor Podman answers on this machine";
    private static final String V16 = "obolibrary/odkfull:v1.6";

    @Test
    void aV16ProjectSyncsInItsOwnImage() {
        OdkSync.Way way = OdkSync.decide("v1.6", OdkRoute.Use.AUTOMATIC, "docker", V16, null);

        assertEquals(OdkSync.How.CONTAINER, way.how());
        assertEquals(V16, way.image());
        assertEquals("docker", way.runtime());
        assertNull(way.why());
        assertTrue(way.said().contains(V16), way.said());
    }

    /** A project on the pinned ODK, with its image here: ODK's own update, in the container. */
    @Test
    void aPinnedProjectSyncsInItsImageWhereThatIsHere() {
        String pinned = OdkPin.IMAGE_REPOSITORY + ":" + OdkPin.VERSION;

        OdkSync.Way way = OdkSync.decide(OdkPin.VERSION, OdkRoute.Use.AUTOMATIC, "podman",
                pinned, null);

        assertEquals(OdkSync.How.CONTAINER, way.how());
        assertEquals(pinned, way.image());
    }

    @Test
    void aPinnedProjectWithoutDockerSyncsInProcess() {
        OdkSync.Way way = OdkSync.decide(OdkPin.VERSION, OdkRoute.Use.AUTOMATIC, null, null,
                NONE);

        assertEquals(OdkSync.How.ITSELF, way.how());
        assertNull(way.why());
        assertTrue(way.said().contains("OntoBoard itself"), way.said());
        assertTrue(way.said().contains(NONE), "and why not a container: " + way.said());
    }

    /** "Never in a container" is kept to, whatever runs containers here. */
    @Test
    void neverInAContainerSyncsAPinnedProjectInProcess() {
        OdkSync.Way way = OdkSync.decide(OdkPin.VERSION, OdkRoute.Use.NEVER_CONTAINER, "docker",
                OdkPin.IMAGE_REPOSITORY + ":" + OdkPin.VERSION, "A container is switched off");

        assertEquals(OdkSync.How.ITSELF, way.how());
    }

    /**
     * OntoBoard's own update is the pinned ODK's: run on a v1.6 project it would move the
     * project to that version, which only Update project files does, when asked. So without
     * the project's own image the configuration alone is written.
     */
    @Test
    void withoutThatImageOnlyTheConfigIsWrittenAndItSaysSo() {
        OdkSync.Way way = OdkSync.decide("v1.6", OdkRoute.Use.AUTOMATIC, "docker", null,
                "This project's ODK image (v1.6) is not on this machine");

        assertEquals(OdkSync.How.CONFIG_ONLY, way.how());
        assertNull(way.image());
        assertTrue(way.why().startsWith("This project's ODK image (v1.6) is not on this "
                + "machine"), way.why());
        assertTrue(way.why().contains("ODK v1.6"), way.why());
        assertTrue(way.why().contains("OntoBoard > Project > Update project files..."), way.why());
        assertTrue(way.why().contains("configuration"), way.why());
    }

    @Test
    void aProjectOfAnUnknownVersionIsNotSyncedInProcessEither() {
        OdkSync.Way way = OdkSync.decide(null, OdkRoute.Use.AUTOMATIC, null, null, NONE);

        assertEquals(OdkSync.How.CONFIG_ONLY, way.how());
        assertTrue(way.why().startsWith(NONE), way.why());
    }

    @Test
    void alwaysInAContainerWithoutOneWritesTheConfigOnlyAndNamesTheSetting() {
        OdkSync.Way way = OdkSync.decide(OdkPin.VERSION, OdkRoute.Use.ALWAYS_CONTAINER, null,
                null, NONE);

        assertEquals(OdkSync.How.CONFIG_ONLY, way.how());
        assertTrue(way.why().startsWith(NONE), way.why());
        assertTrue(way.why().contains("\"" + OdkRoute.Use.ALWAYS_CONTAINER.label() + "\""),
                way.why());
        assertTrue(way.why().contains("OntoBoard > Project > Update project files..."), way.why());
    }

    /** Where the person chose to sync later - unsaved changes - that is the reason said. */
    @Test
    void aSyncLeftForLaterSaysWhy() {
        OdkSync.Way way = OdkSync.later("The ontology had changes that are not saved");

        assertEquals(OdkSync.How.CONFIG_ONLY, way.how());
        assertTrue(way.why().startsWith("The ontology had changes that are not saved"),
                way.why());
        assertTrue(way.why().contains("OntoBoard > Project > Update project files..."), way.why());
    }
}
