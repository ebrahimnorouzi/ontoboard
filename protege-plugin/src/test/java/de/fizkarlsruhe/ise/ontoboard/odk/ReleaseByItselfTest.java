package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A release of an ODK project on a machine without a container: OntoBoard itself in ODK's
 * place. ODK's own Makefile builds the release - read and run by OntoBoard's engine, ROBOT
 * inside the JVM - and everything around it is what it is for any release: the files the
 * Makefile names, the comparison, the commit and the tag.
 *
 * <p>The engine here is the real one, on the project ODK's own release was recorded for; git is
 * a stand-in that answers and remembers.
 */
class ReleaseByItselfTest {

    @TempDir
    File root;

    private File edit;
    private final List<List<String>> gitRan = new ArrayList<List<String>>();

    @BeforeEach
    void theProjectOdksReleaseWasRecordedFor() throws IOException {
        File ontology = EggFixture.write(root, false);
        assertTrue(new File(root, ".git").mkdir(), "a repository");
        edit = new File(ontology, "eggboil-edit.owl");
    }

    private ProcessRunner.Runner git() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (!"git".equals(command.get(0))) {
                    throw new IOException("Cannot run program \"" + command.get(0) + "\"");
                }
                if (command.contains("--version")) {
                    return said("git version 2.47.0");
                }
                if (command.contains("--list")) {
                    return said();
                }
                if (command.contains("status")) {
                    return said("## main");
                }
                gitRan.add(command);
                return said();
            }
        };
    }

    private static ProcessRunner.Outcome said(String... lines) {
        return new ProcessRunner.Outcome(0, Arrays.asList(lines), false);
    }

    private OdkRelease.Outcome release(VersionIriShape shape) {
        return OdkRelease.run(new OdkRelease.Request(edit, "1.0.0", shape, false, true),
                OdkRelease.byOntoBoardItself(edit, null), git(), new OdkRelease.Asks() {
                    @Override
                    public boolean commit(ReleaseRun.Staging staging) {
                        return true;
                    }
                });
    }

    private static String versionIriOf(File file) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(file);
        return ontology.getOntologyID().getVersionIRI().get().toString();
    }

    @Test
    void aReleaseIsBuiltWithoutAContainerAndRecordedAsAnyOther() throws Exception {
        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        String log = String.join("\n", outcome.getTranscript());
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + log);
        Set<String> written = new TreeSet<String>();
        for (File file : outcome.getWritten()) {
            written.add(root.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        }
        Set<String> expected = new TreeSet<String>();
        for (String product : Arrays.asList("eggboil.owl", "eggboil.obo", "eggboil-base.owl",
                "eggboil-base.obo", "eggboil-full.owl", "eggboil-full.obo")) {
            expected.add(product);
            expected.add("releases/1.0.0/" + product);
        }
        assertEquals(expected, written, "the files ODK's Makefile names as the release, at the "
                + "project's top - and, since OntoBoard made this project, their copy under "
                + "releases/1.0.0, where ODK's version IRIs point");
        assertEquals("https://example.org/eggboil/eggboil/releases/1.0.0/eggboil.owl",
                versionIriOf(new File(root, "eggboil.owl")), "ODK's version IRI");
        assertTrue(log.contains("$ make prepare_release_fast VERSION=1.0.0"), log);
        assertTrue(log.contains("run by OntoBoard itself"), "the log says who ran it: " + log);
        assertTrue(log.contains("PASS Rule ../sparql/check_labels-violation.sparql"),
                "the tests ran as part of it: " + log);
        assertTrue(outcome.isCommitted());
        assertEquals("v1.0.0", outcome.getTag());
        assertTrue(gitRan.contains(Arrays.asList("git", "tag", "-a", "v1.0.0", "-m",
                "eggboil 1.0.0")), gitRan.toString());
    }

    /**
     * The shape of the version IRI is the person's to choose. Any other than ODK's reaches
     * make as a variable of its command line, with make's own {@code $@} in it for the file
     * that is being written.
     */
    @Test
    void aVersionIriOfAnotherShapeIsWrittenAsChosen() throws Exception {
        OdkRelease.Outcome outcome = release(
                VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE));

        assertTrue(outcome.isOk(), outcome.getFailure() + "\n"
                + String.join("\n", outcome.getTranscript()));
        assertEquals("https://example.org/eggboil/eggboil/1.0.0/eggboil.owl",
                versionIriOf(new File(root, "eggboil.owl")));
        assertEquals("https://example.org/eggboil/eggboil/1.0.0/eggboil-base.owl",
                versionIriOf(new File(root, "eggboil-base.owl")));
    }

    /** A release that does not build is not recorded, and the reason is ROBOT's own word. */
    @Test
    void aReleaseThatDoesNotBuildIsNotRecordedAndSaysWhy() throws Exception {
        String text = new String(Files.readAllBytes(edit.toPath()), StandardCharsets.UTF_8);
        int end = text.lastIndexOf(')');
        Files.write(edit.toPath(), (text.substring(0, end)
                + "Declaration(Class(<https://example.org/eggboil/EGGBOIL_9999998>))\n"
                + "Declaration(Class(<https://example.org/eggboil/EGGBOIL_9999999>))\n"
                + "DisjointClasses(<https://example.org/eggboil/EGGBOIL_9999998> "
                + "<https://example.org/eggboil/EGGBOIL_0000000>)\n"
                + "SubClassOf(<https://example.org/eggboil/EGGBOIL_9999999> "
                + "<https://example.org/eggboil/EGGBOIL_9999998>)\n"
                + "SubClassOf(<https://example.org/eggboil/EGGBOIL_9999999> "
                + "<https://example.org/eggboil/EGGBOIL_0000000>)\n" + text.substring(end))
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Nothing was copied, committed or tagged"),
                outcome.getFailure());
        assertTrue(String.join("\n", outcome.getTranscript()).contains("EGGBOIL_9999999"),
                "which class cannot have members is in the log: " + outcome.getTranscript());
        assertFalse(new File(root, "eggboil.owl").exists());
        assertEquals(new ArrayList<List<String>>(), gitRan);
    }

    /** A project's own release script is a shell script: it is not a target of make. */
    @Test
    void aScriptOfTheProjectsOwnIsNotRunThisWay() throws Exception {
        ProcessRunner.Outcome ran = OdkRelease.byOntoBoardItself(edit, null)
                .run(Arrays.asList("sh", "release.sh"), true);

        assertFalse(ran.isSuccess());
        String said = String.join("\n", ran.getOutput());
        assertTrue(said.contains("'sh release.sh' is no target of make"), "it is not handed to "
                + "make as if it were one of its targets: " + said);
    }

    /**
     * What OntoBoard itself does not build - here a Makefile somebody changed by hand, which is
     * no longer ODK's - is not built, and the release says why: the engine's own reason, since
     * nothing was run that could have said anything.
     */
    @Test
    void whatOntoBoardItselfDoesNotBuildIsSaidWithItsReason() throws Exception {
        File makefile = new File(edit.getParentFile(), "Makefile");
        Files.write(makefile.toPath(), (new String(Files.readAllBytes(makefile.toPath()),
                StandardCharsets.UTF_8) + "\n# a line of their own\n")
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertFalse(outcome.isOk());
        String log = String.join("\n", outcome.getTranscript());
        assertTrue(log.contains("This project's Makefile is not the one ODK"),
                "why nothing was run is in the log: " + log);
        assertFalse(new File(root, "eggboil.owl").exists());
        assertEquals(new ArrayList<List<String>>(), gitRan);
    }
}
