package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding a project's own scripts, and working out what would run each one.
 *
 * <p>They are not peripheral to an ODK build: a real Makefile sets
 * {@code SHELL = $(SCRIPTSDIR)/run-command.sh}, so every recipe line already executes through
 * one. And on a real project they are container-only - its three scripts need
 * {@code /usr/bin/time}, {@code /tools/odk.py} and {@code amm}, none of which exists on a
 * developer's machine - so the interesting question is never "can we exec this here".
 */
class ProjectScriptsTest {

    /** A project laid out the way ODK lays one out. */
    private static File projectWith(File root, String... nameThenContent) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        File scripts = new File(new File(root, "src"), "scripts");
        assertTrue(ontology.mkdirs() && scripts.mkdirs());
        for (int at = 0; at < nameThenContent.length; at += 2) {
            Files.write(new File(scripts, nameThenContent[at]).toPath(),
                    nameThenContent[at + 1].getBytes(StandardCharsets.UTF_8));
        }
        return ontology;
    }

    // ---------- discovery ----------

    /** Scripts are found, in a stable order. */
    @Test
    void theScriptsAreListedInNameOrder(@TempDir File root) throws Exception {
        File ontology = projectWith(root,
                "zebra.sh", "#!/bin/sh\necho z\n",
                "alpha.py", "print('a')\n");

        List<ProjectScripts.Script> scripts = ProjectScripts.in(ontology);

        assertEquals(2, scripts.size());
        assertEquals("alpha.py", scripts.get(0).getName());
        assertEquals("zebra.sh", scripts.get(1).getName());
    }

    /** A project with no scripts directory has no scripts, and does not throw looking. */
    @Test
    void noScriptsDirectoryIsNotAnError(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());

        assertTrue(ProjectScripts.in(ontology).isEmpty());
        assertTrue(ProjectScripts.in(null).isEmpty());
    }

    /**
     * A Makefile that moves the directory is honoured.
     *
     * <p>Assuming {@code src/scripts} would tell a project that moved it that it has no
     * scripts, which is a confident wrong answer rather than a missing feature.
     */
    @Test
    void theMakefileCanMoveTheScriptsDirectory(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "ignored.sh", "#!/bin/sh\n");
        File elsewhere = new File(root, "tools");
        assertTrue(elsewhere.mkdirs());
        Files.write(new File(elsewhere, "real.sh").toPath(),
                "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(ontology, "Makefile").toPath(),
                "SCRIPTSDIR = ../../tools\nall:\n\techo hi\n".getBytes(StandardCharsets.UTF_8));

        List<ProjectScripts.Script> scripts = ProjectScripts.in(ontology);

        assertEquals(1, scripts.size(), scripts.toString());
        assertEquals("real.sh", scripts.get(0).getName());
    }

    /** A SCRIPTSDIR built from other variables cannot be resolved, so the default is used. */
    @Test
    void anUnresolvableScriptsDirFallsBack(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "here.sh", "#!/bin/sh\n");
        Files.write(new File(ontology, "Makefile").toPath(),
                "SCRIPTSDIR = $(ROOT)/scripts\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("here.sh", ProjectScripts.in(ontology).get(0).getName());
    }

    // ---------- what would run it ----------

    /** A shebang wins, and the path in it is reduced to the program. */
    @Test
    void theShebangNamesTheInterpreter() {
        assertEquals("sh", ProjectScripts.interpreterFromShebang("#!/bin/sh"));
        assertEquals("bash", ProjectScripts.interpreterFromShebang("#!/usr/bin/bash -e"));
        assertNull(ProjectScripts.interpreterFromShebang("echo not a shebang"));
        assertNull(ProjectScripts.interpreterFromShebang(null));
    }

    /** {@code env} names the program in its argument, which is how most scripts are written. */
    @Test
    void envNamesTheProgramAfterIt() {
        assertEquals("python3", ProjectScripts.interpreterFromShebang("#!/usr/bin/env python3"));
        assertEquals("ruby", ProjectScripts.interpreterFromShebang("#!/usr/bin/env ruby"));
    }

    /** With no shebang, the extension decides - and only from a known table. */
    @Test
    void theExtensionDecidesWhenThereIsNoShebang(@TempDir File root) throws Exception {
        File ontology = projectWith(root,
                "update_repo.sh", "ROOTDIR=../..\n",
                "validate.sc", "import $ivy.`x`\n",
                "mystery.dat", "not a script\n");

        List<ProjectScripts.Script> scripts = ProjectScripts.in(ontology);

        assertEquals("amm", byName(scripts, "validate.sc").getInterpreter());
        assertEquals("sh", byName(scripts, "update_repo.sh").getInterpreter());

        ProjectScripts.Script mystery = byName(scripts, "mystery.dat");
        assertNull(mystery.getInterpreter());
        assertFalse(mystery.isRunnable());
        assertTrue(mystery.getWhyNotRunnable().contains("no #! line"),
                mystery.getWhyNotRunnable());
    }

    /**
     * Windows line endings are reported, because the failure they cause names the wrong thing.
     *
     * <p>A shell in a Linux container reading a script whose first line ends with a carriage
     * return says "cannot execute: required file not found" - which reads as a missing
     * interpreter, and costs an afternoon. Every script in the project this was measured
     * against has CRLF endings.
     */
    @Test
    void windowsLineEndingsAreReported(@TempDir File root) throws Exception {
        File ontology = projectWith(root,
                "crlf.sh", "#!/bin/sh\r\necho hi\r\n",
                "unix.sh", "#!/bin/sh\necho hi\n");

        assertTrue(byName(ProjectScripts.in(ontology), "crlf.sh").hasWindowsLineEndings());
        assertFalse(byName(ProjectScripts.in(ontology), "unix.sh").hasWindowsLineEndings());
    }

    // ---------- the container path ----------

    /** A script under the repository root becomes a path the container can see. */
    @Test
    void aScriptBecomesAContainerPath(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh\n");
        File script = new File(new File(new File(root, "src"), "scripts"), "x.sh");

        assertEquals("/work/src/scripts/x.sh",
                ProjectScripts.containerPathOf(script, ontology));
    }

    /**
     * A script outside the mount has no container path, and gets null rather than a guess.
     *
     * <p>A path the container cannot see fails inside it with a message about the file, which
     * sends somebody looking for a missing script rather than at the mount.
     */
    @Test
    void aScriptOutsideTheMountHasNoContainerPath(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh\n");
        File outside = new File(root.getParentFile(), "elsewhere.sh");

        assertNull(ProjectScripts.containerPathOf(outside, ontology));
        assertNull(ProjectScripts.containerPathOf(null, ontology));
        assertNull(ProjectScripts.containerPathOf(new File("x.sh"), null));
    }

    /**
     * A path that climbs out of the mount is refused, however it is written.
     *
     * <p>{@code <root>/../elsewhere/x.sh} begins with the root's own text, so comparing the
     * paths as written accepts it and composes {@code /work/../elsewhere/x.sh} - a path the
     * container cannot see, for which it reports a missing file rather than a missing mount.
     */
    @Test
    void aPathThatClimbsOutOfTheMountIsRefused(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh");
        File climbing = new File(root, ".." + File.separator + "elsewhere" + File.separator
                + "x.sh");

        assertNull(ProjectScripts.containerPathOf(climbing, ontology));
    }

    /**
     * A sibling directory whose name starts with the root's is refused.
     *
     * <p>Root {@code .../p} and script {@code .../p-backup/x.sh}: a bare prefix test accepts it
     * and yields {@code /work/-backup/x.sh}.
     */
    @Test
    void aSiblingWithALongerNameIsRefused(@TempDir File root) throws Exception {
        File project = new File(root, "p");
        File ontology = projectWith(project, "x.sh", "#!/bin/sh");
        File sibling = new File(new File(root, "p-backup"), "x.sh");

        assertNull(ProjectScripts.containerPathOf(sibling, ontology));
    }

    /**
     * The container path has no {@code ..} in it, because the user has to read the command.
     *
     * <p>A real project declares {@code SCRIPTSDIR = ../scripts}, which would otherwise reach
     * the confirmation dialog as {@code /work/src/ontology/../scripts/run-command.sh}.
     */
    @Test
    void theContainerPathIsTidy(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "run-command.sh", "#!/bin/sh");
        Files.write(new File(ontology, "Makefile").toPath(),
                "SCRIPTSDIR = ../scripts\n".getBytes(StandardCharsets.UTF_8));
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        String path = ProjectScripts.containerPathOf(script.getFile(), ontology);

        assertEquals("/work/src/scripts/run-command.sh", path);
    }

    // ---------- the route ----------

    /** With a container runtime, the script runs in the project's image. */
    @Test
    void theContainerIsTheNormalRoute(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "update_repo.sh", "ROOTDIR=../..\n");
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        ScriptRun.Plan plan = ScriptRun.planFor(script, ontology, "docker", null, false);

        assertEquals(ScriptRun.Route.IN_CONTAINER, plan.getRoute());
        assertTrue(plan.getCommand().contains("docker"), plan.asCommandLine());
        assertTrue(plan.getCommand().contains("/work/src/scripts/update_repo.sh"),
                plan.asCommandLine());
        assertTrue(plan.asCommandLine().contains("-w /work/src/ontology"),
                "the working directory the project's own wrapper uses: " + plan.asCommandLine());
    }

    /** With nothing at all, the refusal says what to install rather than failing later. */
    @Test
    void withNothingAvailableItRefusesAndAdvises(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh\n");
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        ScriptRun.Plan plan = ScriptRun.planFor(script, ontology, null, null, false);

        assertEquals(ScriptRun.Route.NOT_RUNNABLE, plan.getRoute());
        assertNull(plan.getCommand());
        assertNotNull(plan.getAdvice());
        assertTrue(plan.getAdvice().contains("Check requirements"), plan.getAdvice());
    }

    /**
     * Without a container, a native ODK environment runs it.
     *
     * <p>ODK supports a native environment on Linux and macOS, and a user who installed one
     * should not be told to start Docker.
     */
    @Test
    void theNativeEnvironmentIsTheSecondChoice(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh\n");
        File bin = new File(new File(root, "odkenv"), "bin");
        assertTrue(bin.mkdirs());
        File activation = new File(bin, "activate-odk-environment.sh");
        Files.write(activation.toPath(), "export PATH\n".getBytes(StandardCharsets.UTF_8));
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        ScriptRun.Plan plan = ScriptRun.planFor(script, ontology, null, activation, false);

        assertEquals(ScriptRun.Route.NATIVE, plan.getRoute());
        assertTrue(plan.asCommandLine().contains("x.sh"), plan.asCommandLine());
    }

    /** The host is last, and only with the interpreter actually on PATH. */
    @Test
    void theHostIsTheLastChoice(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh\n");
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        ScriptRun.Plan plan = ScriptRun.planFor(script, ontology, null, null, true);

        assertEquals(ScriptRun.Route.HOST, plan.getRoute());
        assertEquals("sh", plan.getCommand().get(0));
        assertEquals(2, plan.getCommand().size(), plan.asCommandLine());
    }

    /**
     * The decision reads nothing but its arguments.
     *
     * <p>It would be shorter to look the native environment up inside {@code planFor}, but it
     * lives in a user preference, and then this machine's settings would decide what the test
     * asserts.
     */
    @Test
    void theDecisionDependsOnNothingAmbient(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "x.sh", "#!/bin/sh\n");
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        for (int repeat = 0; repeat < 2; repeat++) {
            assertEquals(ScriptRun.Route.NOT_RUNNABLE,
                    ScriptRun.planFor(script, ontology, null, null, false).getRoute());
        }
    }

    /** A script nothing can interpret is refused before any route is considered. */
    @Test
    void anUninterpretableScriptIsRefused(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "mystery.dat", "x\n");
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        assertEquals(ScriptRun.Route.NOT_RUNNABLE,
                ScriptRun.planFor(script, ontology, "docker", null, true).getRoute());
    }

    /** The CRLF warning fires for the container, where it actually bites. */
    @Test
    void theLineEndingWarningIsForTheContainer(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "crlf.sh", "#!/bin/sh\r\n");
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        assertNotNull(ScriptRun.warningFor(script, ScriptRun.Route.IN_CONTAINER));
        assertTrue(ScriptRun.warningFor(script, ScriptRun.Route.IN_CONTAINER)
                .contains("required file not found"));
        assertNull(ScriptRun.warningFor(script, ScriptRun.Route.HOST),
                "a Windows host runs a CRLF script perfectly well");
    }

    /** A script's timeout is shorter than a build's, because nothing can cancel it yet. */
    @Test
    void aScriptGetsAShorterLeashThanABuild() {
        assertTrue(ScriptRun.TIMEOUT_MINUTES < MakeRun.TIMEOUT_MINUTES,
                "arbitrary code must not hold a thread as long as a known build may");
    }

    private static ProjectScripts.Script byName(List<ProjectScripts.Script> scripts, String name) {
        for (ProjectScripts.Script script : scripts) {
            if (name.equals(script.getName())) {
                return script;
            }
        }
        throw new AssertionError(name + " not among " + scripts);
    }
}
