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
}
