package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A row added to the report from outside the probe: the assistant's model. */
class ToolchainTest {

    @Test
    void plusAppendsAToolAndLeavesCapabilitiesAlone() {
        List<Toolchain.Tool> tools = new ArrayList<Toolchain.Tool>(Arrays.asList(
                Toolchain.Tool.of("Java", Toolchain.State.PRESENT, "running", "")));
        List<Toolchain.Capability> capabilities = Arrays.asList(
                new Toolchain.Capability("Edit", true, "Built in"));
        Toolchain.Report report = new Toolchain.Report(tools, capabilities);

        Toolchain.Report plus = report.plus(Toolchain.Tool.of("Assistant model",
                Toolchain.State.ABSENT, "not set up", "Set up a model"));

        assertEquals(2, plus.getTools().size());
        assertEquals("Assistant model", plus.getTools().get(1).getName());
        assertEquals(Toolchain.State.ABSENT, plus.getTools().get(1).getState());
        assertSame(capabilities, plus.getCapabilities());
        assertTrue(plus.isEverythingAvailable(),
                "someone without a model can still do everything the toolchain offers");
        assertEquals(1, report.getTools().size(), "the original report is unchanged");
    }

    /**
     * On a machine where no program answers at all - no container program, no make, no shell,
     * no ODK - nothing OntoBoard does is out of reach, and no row sends anybody to install one
     * of them in order to work: each says what it would be for.
     */
    @Test
    void withNothingInstalledNothingIsOutOfReachAndNoRowSaysOtherwise() {
        Toolchain.Report report = Toolchain.probe(
                new de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Runner() {
                    @Override
                    public de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Outcome run(
                            java.io.File workingDirectory, List<String> command,
                            long timeoutMinutes,
                            de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Sink sink)
                            throws java.io.IOException {
                        throw new java.io.IOException("Cannot run program \"" + command.get(0)
                                + "\"");
                    }
                });

        assertTrue(report.isEverythingAvailable(), Toolchain.asText(report));
        for (Toolchain.Tool tool : report.getTools()) {
            String remedy = tool.getRemedy();
            assertTrue(!remedy.contains("Use Docker or Podman here")
                    && !remedy.contains("is the only route"), tool.getName() + ": " + remedy);
            if (tool.getName().equals("GNU make")) {
                // OntoBoard has no Makefile of its own any more (1.153.0): a project of
                // its older layout is carried over, not built.
                assertTrue(remedy.startsWith("Only needed to run a Makefile that is not "
                        + "ODK's: "), "what a make of the machine's own would be for: "
                        + remedy);
                assertTrue(!remedy.contains("OntoBoard's"), remedy);
            }
            if (tool.getName().equals("Native ODK environment")) {
                assertTrue(remedy.contains("OntoBoard builds an ODK project itself")
                        || remedy.contains("Set native ODK environment..."),
                        "not needed, or how to set one: " + remedy);
            }
        }
    }

    /** No program can be started: what a machine with nothing installed answers. */
    private static final de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Runner NOTHING =
            new de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Runner() {
                @Override
                public de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Outcome run(
                        java.io.File workingDirectory, List<String> command, long timeoutMinutes,
                        de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Sink sink)
                        throws java.io.IOException {
                    throw new java.io.IOException("Cannot run program \"" + command.get(0)
                            + "\"");
                }
            };

    /**
     * Without Docker OntoBoard is complete: a missing container program and a missing image are
     * said to be fine, each row saying what it would be for - none reads as if it were needed.
     */
    @Test
    void aMissingContainerProgramAndAMissingImageAreSaidToBeFine() {
        Toolchain.Report report = Toolchain.probe(NOTHING);

        List<String> seen = new ArrayList<String>();
        for (Toolchain.Tool tool : report.getTools()) {
            String name = tool.getName();
            if (name.equals("Docker") || name.equals("Podman")
                    || name.equals("Container for ODK's targets")
                    || name.equals(OdkImagePull.NAME)) {
                seen.add(name);
                assertTrue(tool.getRemedy().contains("OntoBoard runs ODK's targets itself"),
                        name + ": " + tool.getRemedy());
                assertTrue(tool.getState() != Toolchain.State.NOT_WORKING, name);
            }
        }
        assertEquals(Arrays.asList("Docker", "Podman", "Container for ODK's targets",
                OdkImagePull.NAME), seen);
        assertTrue(report.isEverythingAvailable(), Toolchain.asText(report));
    }

    /**
     * What OntoBoard makes is an ODK project, built by ODK's own Makefile; no line speaks of
     * the scaffold of 1.135.x, and none says what OntoBoard's engine does - fetching from the
     * network - needs a container.
     */
    @Test
    void noCapabilitySpeaksOfTheOldScaffoldOrSendsTheNetworkToAContainer() {
        for (Toolchain.Capability capability : Toolchain.capabilities(false, null,
                OdkRoute.Use.AUTOMATIC, false)) {
            String said = capability.getName() + " - " + capability.getHow();
            assertTrue(!said.contains("scaffold"), said);
            assertTrue(!said.contains("network"), said);
            assertTrue(capability.isAvailable(), said);
        }
    }

    /**
     * Who settled "Always in a container" builds in the container and nowhere else: that
     * capability does not promise OntoBoard's engine where the image is missing.
     */
    @Test
    void alwaysInAContainerIsNotSaidToFallBackToOntoBoardsEngine() {
        for (Toolchain.Capability capability : Toolchain.capabilities(false, "docker",
                OdkRoute.Use.ALWAYS_CONTAINER, false)) {
            if (capability.getName().equals("Build an ODK project")) {
                assertTrue(!capability.getHow().contains("OntoBoard itself"),
                        capability.getHow());
                assertTrue(capability.getHow().contains(OdkRoute.Use.ALWAYS_CONTAINER.label()),
                        capability.getHow());
                return;
            }
        }
        throw new AssertionError("no row for building an ODK project");
    }

    /**
     * A container the settings say not to use is not used - said as a row that does not
     * matter, grey and "[off ]", not as one that answers: 1.153.0's minor 8 (it had the green
     * disc). This test's settings are kept under a node of its own.
     */
    @Test
    void aContainerTheSettingsDoNotUseIsSaidAsNotUsed() throws Exception {
        String before = System.getProperty(OdkRoute.SETTINGS_PROPERTY);
        String path = "ontoboard-test-toolchain-" + System.nanoTime();
        java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot().node(path);
        try {
            System.setProperty(OdkRoute.SETTINGS_PROPERTY, "node:" + path);
            node.put(OdkRoute.USE_KEY, OdkRoute.Use.NEVER_CONTAINER.name());
            Toolchain.Report report = Toolchain.probe(NOTHING);

            Toolchain.Tool container = null;
            for (Toolchain.Tool tool : report.getTools()) {
                if (tool.getName().equals("Container for ODK's targets")) {
                    container = tool;
                }
            }
            assertEquals(Toolchain.State.NOT_USED, container.getState());
            assertTrue(container.getDetail().startsWith("not used: the setting is"),
                    container.getDetail());
            assertTrue(Toolchain.asText(report).contains("  [off ] Container for ODK's targets"),
                    Toolchain.asText(report));
            assertTrue(report.isEverythingAvailable(), "not needed, so nothing is missing");
            assertEquals(RequirementsPanel.GREY,
                    RequirementsPanel.colourFor(Toolchain.State.NOT_USED));
            assertEquals(RequirementsPanel.GREEN,
                    RequirementsPanel.colourFor(Toolchain.State.PRESENT));
        } finally {
            if (before == null) {
                System.clearProperty(OdkRoute.SETTINGS_PROPERTY);
            } else {
                System.setProperty(OdkRoute.SETTINGS_PROPERTY, before);
            }
            node.removeNode();
        }
    }
}
