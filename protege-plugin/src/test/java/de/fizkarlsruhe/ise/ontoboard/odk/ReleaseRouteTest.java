package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Who builds a release of an ODK project: ODK in a native environment, ODK in its container, or
 * OntoBoard itself - and when nobody does, why.
 */
class ReleaseRouteTest {

    private static final String NONE = "Neither Docker nor Podman answers on this machine";

    /** Everything a route is decided from, with a machine that has nothing as the start. */
    private static final class Machine {
        boolean nativeEnvironment;
        boolean container;
        OdkRoute.Use use = OdkRoute.Use.AUTOMATIC;
        String howToGetOne;
        String script;
        String whyNotFast;
        String whyNotFull;

        ReleaseRoute route() {
            return ReleaseRoute.decide(nativeEnvironment, container, use,
                    container ? null : NONE, howToGetOne, script, whyNotFast, whyNotFull);
        }
    }

    @Test
    void withAContainerOdkBuildsItThere() {
        Machine machine = new Machine();
        machine.container = true;

        assertEquals(ReleaseRoute.Kind.CONTAINER, machine.route().kind());
        assertNull(machine.route().refusal());
    }

    /** As for Build: where ODK's tools are installed on the machine, they are used first. */
    @Test
    void aNativeEnvironmentComesFirstUnlessTheSettingIsAlwaysAContainer() {
        Machine machine = new Machine();
        machine.nativeEnvironment = true;
        machine.container = true;
        assertEquals(ReleaseRoute.Kind.NATIVE, machine.route().kind());

        machine.use = OdkRoute.Use.ALWAYS_CONTAINER;
        assertEquals(ReleaseRoute.Kind.CONTAINER, machine.route().kind());

        machine.use = OdkRoute.Use.NEVER_CONTAINER;
        machine.container = false;
        assertEquals(ReleaseRoute.Kind.NATIVE, machine.route().kind());
    }

    @Test
    void withoutEitherOntoBoardBuildsItItself() {
        Machine machine = new Machine();

        ReleaseRoute route = machine.route();

        assertEquals(ReleaseRoute.Kind.ITSELF, route.kind());
        assertNull(route.refusal());
        assertTrue(route.refreshesImports(), "nothing stands in the way of the full release");
        assertEquals(NONE, route.noContainer());
    }

    /**
     * The full release fetches the imported ontologies again, which OntoBoard itself does not
     * do. The release is then built on the import modules as they are, and the route says so.
     */
    @Test
    void whereTheFullReleaseCannotRunItIsTheFastOne() {
        Machine machine = new Machine();
        machine.whyNotFull = "it fetches the project's imports again";

        ReleaseRoute route = machine.route();

        assertEquals(ReleaseRoute.Kind.ITSELF, route.kind());
        assertNull(route.refusal());
        assertEquals(false, route.refreshesImports());
        assertEquals("it fetches the project's imports again", route.whyNotFull());
    }

    @Test
    void whenTheSettingIsAlwaysAContainerAndThereIsNoneNothingIsBuilt() {
        Machine machine = new Machine();
        machine.use = OdkRoute.Use.ALWAYS_CONTAINER;
        machine.howToGetOne = "Fetch it once with: docker pull an-image (about 5 GB).";

        ReleaseRoute route = machine.route();

        assertNull(route.kind());
        assertTrue(route.refusal().startsWith(NONE), route.refusal());
        assertTrue(route.refusal().contains(OdkRoute.Use.ALWAYS_CONTAINER.label()),
                route.refusal());
        assertTrue(route.refusal().contains("docker pull an-image"), route.refusal());
    }

    /**
     * A project's own release script is a shell script, and its lines call ODK through the
     * project's {@code run.sh}, which starts a container whatever is installed (ODK's run.sh
     * knows Docker and Singularity, nothing else). So such a project is released in the
     * container, also on a machine with a native environment - or not at all.
     */
    @Test
    void aProjectReleasedByItsOwnScriptIsReleasedInTheContainerOrNotAtAll() {
        Machine machine = new Machine();
        machine.script = "release.sh";

        ReleaseRoute route = machine.route();

        assertNull(route.kind());
        assertTrue(route.refusal().contains("release.sh"), route.refusal());
        assertTrue(route.refusal().startsWith(NONE), route.refusal());

        machine.nativeEnvironment = true;
        assertNull(machine.route().kind(), "a native environment does not run its run.sh lines");
        assertTrue(machine.route().refusal().contains("release.sh"));

        machine.container = true;
        assertEquals(ReleaseRoute.Kind.CONTAINER, machine.route().kind());
    }

    /** What OntoBoard itself cannot build at all - a Makefile with rules of the project's own. */
    @Test
    void whenEvenTheFastReleaseCannotRunTheReasonIsSaid() {
        Machine machine = new Machine();
        machine.whyNotFast = "the project's own makefile changes what prepare_release runs "
                + "(eggboil.Makefile:12)";
        machine.whyNotFull = machine.whyNotFast;

        ReleaseRoute route = machine.route();

        assertNull(route.kind());
        assertTrue(route.refusal().startsWith(NONE), route.refusal());
        assertTrue(route.refusal().contains("eggboil.Makefile:12"), route.refusal());
    }
}
