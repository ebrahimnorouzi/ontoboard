package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who builds an ODK project on this machine, as Check requirements says it, and the command
 * that runs make with ODK's tools as they are installed on the machine.
 *
 * <p>These stood in the test of the build OntoBoard ran for its own projects, up to 1.135,
 * where the toolchain's report was first written. That build went with 1.153.0; what they hold
 * is the toolchain's.
 */
class ToolchainRoutesTest {

    private static Toolchain.Capability buildingAnOdkProject(List<Toolchain.Capability> all) {
        for (Toolchain.Capability capability : all) {
            if (capability.getName().equals("Build an ODK project")) {
                return capability;
            }
        }
        throw new AssertionError("no row for building an ODK project in " + all);
    }

    /** What the user can do never depends on a tool they do not need. */
    @Test
    void capabilitiesDoNotDemandToolsThatAreNotNeeded() {
        List<Toolchain.Capability> bare =
                Toolchain.capabilities(false, null, OdkRoute.Use.AUTOMATIC, false);

        for (Toolchain.Capability capability : bare) {
            assertTrue(capability.isAvailable(), capability.getName() + ": editing, making a "
                    + "project and building one need nothing installed - " + capability.getHow());
        }
        Toolchain.Capability odk = buildingAnOdkProject(bare);
        assertTrue(odk.getHow().contains("OntoBoard itself"), odk.getHow());
    }

    /** A native environment is preferred over a container, and said so. */
    @Test
    void aNativeEnvironmentIsPreferredOverAContainer() {
        Toolchain.Capability odk = buildingAnOdkProject(
                Toolchain.capabilities(true, "docker", OdkRoute.Use.AUTOMATIC, true));

        assertTrue(odk.isAvailable());
        assertTrue(odk.getHow().contains("no container"), odk.getHow());
    }

    /** The program that answers is named by what it is, also when it is a path. */
    @Test
    void theContainerProgramIsNamedByWhatItIs() {
        assertTrue(buildingAnOdkProject(Toolchain.capabilities(false, "docker",
                OdkRoute.Use.AUTOMATIC, false)).getHow().contains("Docker"));
        assertTrue(buildingAnOdkProject(Toolchain.capabilities(false,
                "C:\\Program Files\\RedHat\\Podman\\podman.exe", OdkRoute.Use.AUTOMATIC, false))
                .getHow().contains("Podman"));
    }

    /**
     * Somebody who settled "Always in a container" and has none cannot build an ODK project
     * now: that is said, with the setting's name, since the setting is what stands in the way.
     */
    @Test
    void alwaysInAContainerWithoutOneIsSaidAsNotPossibleNow() {
        Toolchain.Capability odk = buildingAnOdkProject(Toolchain.capabilities(false, null,
                OdkRoute.Use.ALWAYS_CONTAINER, false));

        assertFalse(odk.isAvailable());
        assertTrue(odk.getHow().contains(OdkRoute.Use.ALWAYS_CONTAINER.label()), odk.getHow());
    }

    /**
     * The activation command survives a path with a space, which on Windows is the normal case.
     *
     * <p>{@code .} rather than {@code source}: the script is POSIX and {@code source} is a
     * bashism that {@code dash} - {@code /bin/sh} on Debian and Ubuntu - does not have.
     */
    @Test
    void theNativeCommandQuotesItsPath(@TempDir File root) throws Exception {
        File bin = new File(root, "my env/bin");
        assertTrue(bin.mkdirs());
        File script = new File(bin, "activate-odk-environment.sh");
        Files.write(script.toPath(), "#".getBytes("UTF-8"));

        List<String> command = Toolchain.nativeCommand(script, "test");

        assertEquals("sh", command.get(0));
        assertEquals("-c", command.get(1));
        assertTrue(command.get(2).startsWith(". '"), command.get(2));
        assertTrue(command.get(2).contains("my env"), command.get(2));
        assertTrue(command.get(2).endsWith("&& make test"), command.get(2));
        // odk-helper comes from `pip install --user odk-core` and lives in the pip bin
        // directory, which the activation script does not add. A real ODK Makefile calls
        // it for every release artefact (check_rdfxml_%), so without this a native build
        // dies at the first one with "odk-helper: No such file or directory" - reproduced
        // on Ubuntu 22.04 while verifying the route.
        assertTrue(command.get(2).contains("$HOME/.local/bin"), command.get(2));
        assertFalse(command.get(2).startsWith("source"), "dash has no source: " + command.get(2));
    }

    /** A single quote in a path cannot end the quoting early. */
    @Test
    void aQuoteInThePathIsEscaped() {
        assertEquals("'it'\\''s here'", Toolchain.quote("it's here"));
    }

    /** The environment is recognised whether the user picks the directory or its bin. */
    @Test
    void theActivationScriptIsFoundEitherWay(@TempDir File root) throws Exception {
        assertNull(Toolchain.activationScript(root), "nothing there yet");

        File bin = new File(root, "bin");
        assertTrue(bin.mkdirs());
        File script = new File(bin, "activate-odk-environment.sh");
        Files.write(script.toPath(), "#".getBytes("UTF-8"));

        assertEquals(script, Toolchain.activationScript(root), "the environment directory");
        assertEquals(script, Toolchain.activationScript(bin), "or its bin");
        assertNull(Toolchain.activationScript(null));
    }

    /** Paths and command lines reach the dialog as text, not as markup. */
    @Test
    void theRequirementsPanelEscapesWhatItShows() {
        assertEquals("C:\\dev\\&lt;odk&gt;", RequirementsPanel.escape("C:\\dev\\<odk>"));
        assertEquals("a &amp; b", RequirementsPanel.escape("a & b"));
        assertEquals("", RequirementsPanel.escape(null));
    }
}
