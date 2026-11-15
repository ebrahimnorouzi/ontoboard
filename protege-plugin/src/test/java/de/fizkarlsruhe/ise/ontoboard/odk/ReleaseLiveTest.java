package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release with nothing standing in: ODK's own container builds it and git itself records it.
 *
 * <p>{@code OdkReleaseTest} holds every decision of a release against stand-ins. This asks the
 * real ones, on a machine that has ODK v1.6.1's image and git: a project as Create makes it is
 * released three times - the first release, a second with another shape of version IRI and
 * with notes, and one that drops a term and is refused. Without the image or git it is skipped
 * and says so.
 */
class ReleaseLiveTest {

    private static final String BASE = "https://example.org/eggboil/eggboil";

    private static String runtime;
    private static String image;
    private static OdkContainer.Host host;

    @BeforeAll
    static void lookForTheImage() {
        runtime = MakeRun.containerRuntime(ProcessRunner.real());
        if (runtime != null) {
            image = OdkImage.pinned(OdkImage.probe(runtime, ProcessRunner.real()));
            host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
        }
    }

    private static List<String> git(File root, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Collections.singletonList("git"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
        return outcome.getOutput();
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The version IRI a released file carries, read as the dialog reads it. */
    private static String versionIriOf(File released) throws IOException {
        String text = read(released);
        return OdkRelease.versionIriIn(text.substring(0, Math.min(text.length(), 20000)));
    }

    private static String said(OdkRelease.Outcome outcome) {
        return outcome.getFailure() + "\n" + outcome.getWarnings() + "\n"
                + String.join("\n", outcome.getTranscript());
    }

    @Test
    void odkBuildsAndGitRecordsTheReleasesOfAProjectOntoBoardMade(@TempDir File temp)
            throws IOException {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");

        // The project as Create makes it, with its first commit.
        OdkCreate.Created made = OdkCreate.create(new OdkProjectConfig("eggboil",
                "Test kitchen ontology", "Application ontology for boiling eggs in a test "
                        + "kitchen", "https://example.org/eggboil/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", temp), null,
                ProcessRunner.real());
        assumeTrue(made.committed(), "git made no first commit here: " + made.notRecorded());
        File root = new File(temp, "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.org");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "tag.gpgsign", "false");
        assertTrue(OdkRelease.isOdkProject(edit));
        OdkRelease.Odk odk = OdkRelease.inContainer(runtime, image, host,
                new File(root, "src/ontology"), ProcessRunner.real(), null);

        // ---------------------------------------------------------------- the first release
        OdkRelease.Outcome first = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0", null,
                false, true), odk, ProcessRunner.real(), staging -> true);

        assertTrue(first.isOk(), said(first));
        assertTrue(first.isCommitted(), said(first));
        assertEquals("v1.0.0", first.getTag());
        for (String name : new String[] {"eggboil.owl", "eggboil.obo", "eggboil-base.owl",
            "eggboil-full.owl"}) {
            assertTrue(new File(root, name).isFile(), name + " at the top of the project");
            assertArrayEquals(Files.readAllBytes(new File(root, name).toPath()),
                    Files.readAllBytes(new File(root, "releases/1.0.0/" + name).toPath()),
                    name + " under releases/1.0.0");
            assertArrayEquals(Files.readAllBytes(new File(root, name).toPath()),
                    GitTags.fileAt(root, "v1.0.0", name), name + " in the commit, byte for byte");
            assertArrayEquals(Files.readAllBytes(new File(root, name).toPath()),
                    GitTags.fileAt(root, "v1.0.0", "releases/1.0.0/" + name));
        }
        assertEquals(BASE + "/releases/1.0.0/eggboil.owl",
                versionIriOf(new File(root, "eggboil.owl")), "ODK's shape, by ODK's Makefile");
        assertFalse(new File(root, "releases/1.0.0/CHANGES.md").exists());
        assertEquals(Collections.<String>emptyList(), git(root, "status", "--porcelain"),
                "nothing of the release is left uncommitted");
        assertEquals(Collections.singletonList("release(1.0.0)"),
                git(root, "log", "-1", "--format=%s"));

        // ---------------------------------------------------------------- the second
        // What the dialog would now propose: the shape the first release has.
        OdkRelease.Proposal proposal = OdkRelease.proposal(edit, ProcessRunner.real());
        assertEquals("1.0.0", proposal.lastVersion());
        assertEquals(BASE + "/releases/1.0.0/eggboil.owl", proposal.lastVersionIri());
        assertEquals(VersionIriShape.Kind.ODK, proposal.shape().kind());

        String timer = "Declaration(Class(<https://example.org/eggboil/EGGBOIL_0000001>))\n"
                + "AnnotationAssertion(rdfs:label <https://example.org/eggboil/EGGBOIL_0000001>"
                + " \"egg timer\"@en)\n";
        String before = read(edit);
        int end = before.lastIndexOf(')');
        Files.write(edit.toPath(), (before.substring(0, end) + timer + before.substring(end))
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome second = OdkRelease.run(new OdkRelease.Request(edit, "1.1.0",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE), false, true), odk,
                ProcessRunner.real(), staging -> true);

        assertTrue(second.isOk(), said(second));
        assertEquals("v1.1.0", second.getTag());
        assertEquals(BASE + "/1.1.0/eggboil.owl", versionIriOf(new File(root, "eggboil.owl")),
                "the shape chosen, through make's command line and its sub-make");
        assertEquals(BASE + "/1.1.0/eggboil-base.owl",
                versionIriOf(new File(root, "eggboil-base.owl")), "each file its own");
        String notes = read(new File(root, "releases/1.1.0/CHANGES.md"));
        assertTrue(notes.contains("EGGBOIL_0000001") && notes.contains("egg timer"), notes);
        assertEquals(Arrays.asList("v1.1.0", "v1.0.0"),
                GitTags.releases(root, ProcessRunner.real()));
        assertEquals(Collections.<String>emptyList(), git(root, "status", "--porcelain"));
        assertEquals(VersionIriShape.Kind.VERSION_AND_FILE,
                OdkRelease.proposal(edit, ProcessRunner.real()).shape().kind());

        // ---------------------------------------------------------------- one that drops a term
        Files.write(edit.toPath(), before.getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome third = OdkRelease.run(new OdkRelease.Request(edit, "1.2.0",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE), false, true), odk,
                ProcessRunner.real(), staging -> true);

        assertFalse(third.isOk(), said(third));
        assertTrue(third.getFailure().contains("EGGBOIL_0000001"), third.getFailure());
        assertTrue(third.getFailure().contains("1.1.0"), third.getFailure());
        assertFalse(new File(root, "releases/1.2.0").exists());
        assertEquals(Arrays.asList("v1.1.0", "v1.0.0"),
                GitTags.releases(root, ProcessRunner.real()));
    }
}
