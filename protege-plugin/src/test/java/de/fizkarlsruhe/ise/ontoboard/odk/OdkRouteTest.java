package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which program runs ODK's container, and whether one is used at all: the two settings a person
 * has for it, and the search when they leave it to OntoBoard.
 */
class OdkRouteTest {

    private static final List<String> NO_PLACES = Collections.emptyList();

    /** The programs that were asked whether they answer, in the order asked. */
    private final List<String> asked = new ArrayList<String>();

    /** A machine on which exactly these programs answer {@code info}. */
    private ProcessRunner.Runner answering(final String... programs) {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                asked.add(command.get(0));
                if (command.size() == 2 && command.get(1).equals("info")
                        && Arrays.asList(programs).contains(command.get(0))) {
                    return new ProcessRunner.Outcome(0, Collections.<String>emptyList(), false);
                }
                throw new IOException("Cannot run program \"" + command.get(0) + "\"");
            }
        };
    }

    // ------------------------------------------------------------------ the search

    @Test
    void leftToOntoBoardDockerComesFirst() {
        assertEquals("docker", OdkRoute.runtime(OdkRoute.Use.AUTOMATIC, null, NO_PLACES,
                answering("docker", "podman")));
    }

    @Test
    void podmanWhenDockerDoesNotAnswer() {
        assertEquals("podman", OdkRoute.runtime(OdkRoute.Use.AUTOMATIC, null, NO_PLACES,
                answering("podman")));
    }

    @Test
    void noneWhenNeitherAnswers() {
        assertNull(OdkRoute.runtime(OdkRoute.Use.AUTOMATIC, null, NO_PLACES, answering()));
    }

    /**
     * A Protégé started from the desktop often has a shorter PATH than a terminal. Then the
     * places these programs are usually installed to are looked at - those that hold a file.
     */
    @Test
    void aProgramInAUsualPlaceIsFoundWhenThePathHasNone(@TempDir File folder) throws IOException {
        File docker = new File(folder, "docker.exe");
        Files.write(docker.toPath(), new byte[0]);
        File notThere = new File(folder, "podman.exe");

        String found = OdkRoute.runtime(OdkRoute.Use.AUTOMATIC, null,
                Arrays.asList(notThere.getPath(), docker.getPath()),
                answering(docker.getPath(), notThere.getPath()));

        assertEquals(docker.getPath(), found);
        assertFalse(asked.contains(notThere.getPath()), "a place that holds no file is not run");
    }

    // ------------------------------------------------------------------ the two settings

    @Test
    void neverIsNoneWhateverAnswersAndNothingIsAsked() {
        assertNull(OdkRoute.runtime(OdkRoute.Use.NEVER_CONTAINER, null, NO_PLACES,
                answering("docker")));
        assertTrue(asked.isEmpty(), asked.toString());
    }

    /**
     * Somebody who names a program means that program: when it does not answer, OntoBoard does
     * not quietly take another one - Podman is often chosen because Docker may not be used.
     */
    @Test
    void aChosenProgramIsTheOnlyOneAsked() {
        assertNull(OdkRoute.runtime(OdkRoute.Use.AUTOMATIC, "C:/tools/podman.exe", NO_PLACES,
                answering("docker")));
        assertEquals(Arrays.asList("C:/tools/podman.exe"), asked);
    }

    @Test
    void aChosenProgramThatAnswersIsUsedAsItWasChosen() {
        assertEquals("C:/tools/podman.exe", OdkRoute.runtime(OdkRoute.Use.AUTOMATIC,
                "C:/tools/podman.exe", NO_PLACES, answering("docker", "C:/tools/podman.exe")));
    }

    @Test
    void alwaysFindsTheProgramAsTheSearchDoesButDoesNotGoOnWithoutOne() {
        assertEquals("docker", OdkRoute.runtime(OdkRoute.Use.ALWAYS_CONTAINER, null, NO_PLACES,
                answering("docker")));
        assertFalse(OdkRoute.mayRunWithoutContainer(OdkRoute.Use.ALWAYS_CONTAINER));
        assertTrue(OdkRoute.mayRunWithoutContainer(OdkRoute.Use.AUTOMATIC));
        assertTrue(OdkRoute.mayRunWithoutContainer(OdkRoute.Use.NEVER_CONTAINER));
    }

    @Test
    void theSettingsAreKeptAndForgotten() throws Exception {
        Preferences node = Preferences.userRoot().node("ontoboard-test-odkroute-"
                + System.nanoTime());
        try {
            assertEquals(OdkRoute.Use.AUTOMATIC, OdkRoute.use(node));
            assertNull(OdkRoute.containerProgram(node));

            OdkRoute.setUse(node, OdkRoute.Use.NEVER_CONTAINER);
            OdkRoute.setContainerProgram(node, "  C:/x/docker.exe ");
            assertEquals(OdkRoute.Use.NEVER_CONTAINER, OdkRoute.use(node));
            assertEquals("C:/x/docker.exe", OdkRoute.containerProgram(node));

            OdkRoute.setContainerProgram(node, null);
            assertNull(OdkRoute.containerProgram(node));
            OdkRoute.setUse(node, OdkRoute.Use.AUTOMATIC);
            assertEquals(OdkRoute.Use.AUTOMATIC, OdkRoute.use(node));

            node.put(OdkRoute.USE_KEY, "a-value-of-another-version");
            assertEquals(OdkRoute.Use.AUTOMATIC, OdkRoute.use(node));
        } finally {
            node.removeNode();
        }
    }

    /**
     * What a person settles reaches every operation: they all ask in one place for the program
     * to run a container with. The settings of this test are kept under a node of its own.
     */
    @Test
    void whatAPersonSettlesIsWhatEveryOperationFinds() throws Exception {
        String before = System.getProperty(OdkRoute.SETTINGS_PROPERTY);
        String path = "ontoboard-test-odkroute-" + System.nanoTime();
        Preferences node = Preferences.userRoot().node(path);
        try {
            System.setProperty(OdkRoute.SETTINGS_PROPERTY, "node:" + path);
            // First read what this test wrote into its own node: were the settings read from
            // anywhere else, the test would stop here, before it writes through OdkRoute.
            node.put(OdkRoute.USE_KEY, OdkRoute.Use.NEVER_CONTAINER.name());
            assertEquals(OdkRoute.Use.NEVER_CONTAINER, OdkRoute.use(), "read from this test's "
                    + "own node");
            assertNull(MakeRun.containerRuntime(answering("docker")));
            assertTrue(OdkRoute.whyNoContainer(answering("docker"))
                    .contains(OdkRoute.Use.NEVER_CONTAINER.label()));

            OdkRoute.setUse(OdkRoute.Use.AUTOMATIC);
            OdkRoute.setContainerProgram("C:/tools/podman.exe");
            assertEquals("C:/tools/podman.exe",
                    MakeRun.containerRuntime(answering("docker", "C:/tools/podman.exe")));
        } finally {
            if (before == null) {
                System.clearProperty(OdkRoute.SETTINGS_PROPERTY);
            } else {
                System.setProperty(OdkRoute.SETTINGS_PROPERTY, before);
            }
            node.removeNode();
        }
    }

    /** A test run has no settings: what it finds is what the search finds. */
    @Test
    void aTestRunIsNotTouchedByThePersonsOwnSettings() {
        assertEquals("none", System.getProperty(OdkRoute.SETTINGS_PROPERTY),
                "set before the first test, by OwnSettingsOff");
        assertEquals("podman", MakeRun.containerRuntime(answering("podman")));
        assertTrue(OdkRoute.whyNoContainer(answering()).contains("Docker"));
    }

    /** For a sentence that goes on: "..., so OntoBoard runs ODK's own recipes itself". */
    @Test
    void whyThereIsNoneAsTheStartOfASentence() {
        assertEquals("A container is switched off in OntoBoard's settings",
                OdkRoute.noContainerBecause(OdkRoute.Use.NEVER_CONTAINER, null, NO_PLACES,
                        answering("docker")));
        assertEquals("Neither Docker nor Podman answers on this machine",
                OdkRoute.noContainerBecause(OdkRoute.Use.AUTOMATIC, null, NO_PLACES,
                        answering()));
        String chosen = OdkRoute.noContainerBecause(OdkRoute.Use.AUTOMATIC,
                "C:/tools/podman.exe", NO_PLACES, answering("docker"));
        assertTrue(chosen.contains("C:/tools/podman.exe") && !chosen.endsWith("."), chosen);
        assertNull(OdkRoute.noContainerBecause(OdkRoute.Use.AUTOMATIC, null, NO_PLACES,
                answering("docker")), "one answers");
    }

    // ------------------------------------------------------------------ what is said

    /** Podman's command line is Docker's but for a few options: which it is, the name says. */
    @Test
    void podmanIsToldByTheProgramsOwnName() {
        assertTrue(OdkRoute.isPodman("podman"));
        assertTrue(OdkRoute.isPodman("C:\\Program Files\\RedHat\\Podman\\podman.exe"));
        assertTrue(OdkRoute.isPodman("/opt/podman/bin/podman"));
        assertFalse(OdkRoute.isPodman("docker"));
        assertFalse(OdkRoute.isPodman("/opt/podman-tools/bin/docker"));
        assertFalse(OdkRoute.isPodman(null));
    }

    @Test
    void whyThereIsNoContainerNamesTheSettingWhenTheSettingIsTheReason() {
        String never = OdkRoute.whyNoContainer(OdkRoute.Use.NEVER_CONTAINER, null, NO_PLACES,
                answering("docker"));
        assertTrue(never.contains(OdkRoute.Use.NEVER_CONTAINER.label()), never);

        String chosen = OdkRoute.whyNoContainer(OdkRoute.Use.AUTOMATIC, "C:/tools/podman.exe",
                NO_PLACES, answering("docker"));
        assertTrue(chosen.contains("C:/tools/podman.exe"), chosen);

        String none = OdkRoute.whyNoContainer(OdkRoute.Use.AUTOMATIC, null, NO_PLACES,
                answering());
        assertTrue(none.contains("Docker") && none.contains("Podman"), none);

        assertNull(OdkRoute.whyNoContainer(OdkRoute.Use.AUTOMATIC, null, NO_PLACES,
                answering("docker")), "one answers: there is nothing to explain");
    }
}
