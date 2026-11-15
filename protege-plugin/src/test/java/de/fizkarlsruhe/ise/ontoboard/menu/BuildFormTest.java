package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan;
import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.SeededProject;
import java.io.File;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the Build form says about where the build runs, when make runs it.
 *
 * <p>The sentence above the form was written for the build OntoBoard ran inside Protege for its
 * own projects - "inside Protege - nothing else needs installing" - and was shown for every
 * other route as well: over a build in ODK's container, which is neither. The container's own
 * sentence existed, with its tests, and nothing showed it.
 */
class BuildFormTest {

    private static final String IMAGE = "obolibrary/odkfull:v1.6.1";

    @Test
    void aBuildInOdksContainerSaysSoAboveTheForm(@TempDir File where) {
        File edit = SeededProject.in(where);
        BuildPlan container = BuildPlan.inContainer(edit, IMAGE, "docker");

        String above = BuildAction.explanationFor(MakeRun.Route.ODK_IN_CONTAINER, edit, IMAGE,
                "docker");

        assertEquals(container.explanation(), above);
        assertTrue(above.startsWith("Runs a target of this project's own Makefile in ODK's "
                + "container (obolibrary/odkfull:v1.6.1, through docker)"), above);
        assertEquals(container.help(), BuildAction.helpFor(MakeRun.Route.ODK_IN_CONTAINER, edit,
                IMAGE, "docker"));
    }

    @Test
    void noBuildThatMakeRunsIsSaidToRunInsideProtege(@TempDir File where) {
        File edit = SeededProject.in(where);

        for (MakeRun.Route route : Arrays.asList(MakeRun.Route.ODK_IN_CONTAINER,
                MakeRun.Route.ODK_NATIVE, MakeRun.Route.MAKE_ON_PATH)) {
            String above = BuildAction.explanationFor(route, edit, IMAGE, "podman");
            String help = BuildAction.helpFor(route, edit, IMAGE, "podman");

            for (String said : Arrays.asList(above, help)) {
                assertFalse(said.contains("inside Protege"), route + ": " + said);
                assertFalse(said.contains("nothing else needs installing"), route + ": " + said);
                assertFalse(said.contains("scaffolded"), route + ": " + said);
            }
            assertTrue(above.startsWith("Runs a target of this project's own Makefile"),
                    route + ": " + above);
            assertTrue(above.endsWith("The whole log is kept, and can be saved from the result."),
                    route + ": " + above);
            assertTrue(help.endsWith("What it rewrites that Protege has open is read again when "
                    + "it is done."), route + ": " + help);
        }
        assertTrue(BuildAction.explanationFor(MakeRun.Route.ODK_NATIVE, edit, IMAGE, "docker")
                .contains("with ODK's tools as they are installed on this machine"));
        assertTrue(BuildAction.explanationFor(MakeRun.Route.MAKE_ON_PATH, edit, IMAGE, "docker")
                .contains("with the make of this machine"));
    }
}
