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
                assertTrue(remedy.contains("neither ODK's nor OntoBoard's"),
                        "what a make of the machine's own would be for: " + remedy);
            }
            if (tool.getName().equals("Native ODK environment")) {
                assertTrue(remedy.contains("OntoBoard builds an ODK project itself")
                        || remedy.contains("Set native ODK environment..."),
                        "not needed, or how to set one: " + remedy);
            }
        }
    }
}
