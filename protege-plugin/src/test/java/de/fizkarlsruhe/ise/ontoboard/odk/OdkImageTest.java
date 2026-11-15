package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which ODK image runs a project.
 *
 * <p>A project is built by the ODK that wrote its Makefile when that image is on the machine,
 * because building somebody's ontology with a different ODK than their CI uses is how a release
 * stops being reproducible. OntoBoard never downloads an image on its own, so when that one is
 * missing the pinned ODK runs the project - and says so.
 */
class OdkImageTest {

    private static final String MAKEFILE_16 = "# ODK Version: v1.6\n\n"
            + "ODK_VERSION_MAKEFILE =      v1.6\n\nall: test\n";

    /** A machine's images: {@code reference=the ODK_VERSION in its environment}. */
    private static OdkImage.Probe machine(String... referencesAndVersions) {
        final Map<String, String> images = new LinkedHashMap<String, String>();
        for (String entry : referencesAndVersions) {
            int equals = entry.indexOf('=');
            images.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        return new OdkImage.Probe() {
            @Override
            public boolean isPresent(String reference) {
                return images.containsKey(reference);
            }

            @Override
            public String odkVersionOf(String reference) {
                return images.get(reference);
            }

            @Override
            public List<String> localTags(String repository) {
                List<String> tags = new ArrayList<String>();
                for (String reference : images.keySet()) {
                    if (reference.startsWith(repository + ":")) {
                        tags.add(reference.substring(repository.length() + 1));
                    }
                }
                return tags;
            }
        };
    }

    private static File project(File root, String makefile, String runShConf) throws IOException {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        if (makefile != null) {
            Files.write(new File(ontology, "Makefile").toPath(),
                    makefile.getBytes(StandardCharsets.UTF_8));
        }
        if (runShConf != null) {
            Files.write(new File(ontology, "run.sh.conf").toPath(),
                    runShConf.getBytes(StandardCharsets.UTF_8));
        }
        return ontology;
    }

    // ------------------------------------------------------------------ the pinned image

    @Test
    void thePinnedImageIsTheOneTaggedWithItsVersion() {
        assertEquals("obolibrary/odkfull:v1.6.1", OdkImage.pinned(machine(
                "obolibrary/odkfull:latest=v1.6.1", "obolibrary/odkfull:v1.6.1=v1.6.1")));
    }

    /**
     * Review focus 4. Whoever ran {@code docker pull obolibrary/odkfull} has the pinned ODK
     * under the tag {@code latest} and no other: asking them to download five gigabytes they
     * already have would be the bug.
     */
    @Test
    void thePinnedImageUnderAnotherTagIsRecognised() {
        assertEquals("obolibrary/odkfull:latest",
                OdkImage.pinned(machine("obolibrary/odkfull:latest=v1.6.1")));
    }

    @Test
    void anImageOfAnotherVersionIsNotThePinnedOne() {
        assertNull(OdkImage.pinned(machine("obolibrary/odkfull:latest=v1.7",
                "obolibrary/odkfull:v1.5.4=v1.5.4")));
    }

    @Test
    void noImageIsNull() {
        assertNull(OdkImage.pinned(machine()));
    }

    // ------------------------------------------------------------------ a project's image

    @Test
    void aProjectRunsInTheImageItsMakefileNames(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(project(root, MAKEFILE_16, null),
                machine("obolibrary/odkfull:v1.6=v1.6", "obolibrary/odkfull:latest=v1.6.1"));

        assertEquals("obolibrary/odkfull:v1.6", choice.reference());
        assertNull(choice.note(), "nothing to say when the project's own ODK runs it");
    }

    @Test
    void withoutThatTagThePinnedImageRunsItAndSaysSo(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(project(root, MAKEFILE_16, null),
                machine("obolibrary/odkfull:latest=v1.6.1"));

        assertEquals("obolibrary/odkfull:latest", choice.reference());
        assertNotNull(choice.note());
        assertTrue(choice.note().contains("v1.6") && choice.note().contains("v1.6.1"),
                choice.note());
    }

    /** A Makefile written without the image (ODK then writes "Unknown"), or not ODK's at all. */
    @Test
    void aMakefileThatNamesNoOdkRunsInThePinnedImage(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(
                project(root, "ODK_VERSION_MAKEFILE =      \"Unknown\"\n", null),
                machine("obolibrary/odkfull:v1.6.1=v1.6.1"));

        assertEquals("obolibrary/odkfull:v1.6.1", choice.reference());
        assertNotNull(choice.note());
    }

    /** {@code run.sh} reads run.sh.conf first, so what it says there is what the project runs in. */
    @Test
    void runShConfWins(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(
                project(root, MAKEFILE_16, "# our CI pins this\nODK_TAG=v1.5.4\n"),
                machine("obolibrary/odkfull:v1.5.4=v1.5.4", "obolibrary/odkfull:v1.6=v1.6"));

        assertEquals("obolibrary/odkfull:v1.5.4", choice.reference());
        assertNull(choice.note());
    }

    /** As run.sh reads it: a tag inside ODK_IMAGE beats ODK_TAG, and the default tag is latest. */
    @Test
    void runShConfIsReadAsRunShReadsIt(@TempDir File root) throws IOException {
        OdkImage.Probe machine = machine("obolibrary/odklite:v1.6=v1.6",
                "obolibrary/odklite:latest=v1.6.1", "obolibrary/odkfull:latest=v1.6.1");

        assertEquals("obolibrary/odklite:v1.6", OdkImage.forProject(project(new File(root, "a"),
                MAKEFILE_16, "export ODK_IMAGE=\"odklite:v1.6\"\nODK_TAG=v9\n"), machine)
                .reference());
        assertEquals("obolibrary/odklite:latest", OdkImage.forProject(project(new File(root, "b"),
                MAKEFILE_16, "ODK_IMAGE='odklite'   # the small one\n"), machine).reference());
    }

    /**
     * For what must not move a project to another ODK - bringing its files in line after
     * its configuration changed: the image of the very ODK that wrote its Makefile, or
     * none. Never the pinned one in its place.
     */
    @Test
    void theImageOfTheProjectsOwnOdkOrNone(@TempDir File root) throws IOException {
        File ontology = project(root, MAKEFILE_16, null);

        assertEquals("obolibrary/odkfull:v1.6", OdkImage.ofProjectsOwnVersion(ontology,
                machine("obolibrary/odkfull:v1.6=v1.6", "obolibrary/odkfull:latest=v1.6.1")));
        assertEquals("obolibrary/odkfull:older", OdkImage.ofProjectsOwnVersion(ontology,
                machine("obolibrary/odkfull:older=v1.6", "obolibrary/odkfull:latest=v1.6.1")),
                "under whatever tag");
        assertNull(OdkImage.ofProjectsOwnVersion(ontology,
                machine("obolibrary/odkfull:latest=v1.6.1")), "another ODK's is not its own");
        assertEquals("v1.6", OdkImage.versionOfProject(ontology));
    }

    @Test
    void aProjectWhoseMakefileDoesNotSayHasNoImageOfItsOwn(@TempDir File root)
            throws IOException {
        File ontology = project(root, "all: test\n", null);

        assertNull(OdkImage.versionOfProject(ontology));
        assertNull(OdkImage.ofProjectsOwnVersion(ontology,
                machine("obolibrary/odkfull:latest=v1.6.1")));
        assertNull(OdkImage.versionOfProject(new File(root, "no-such-folder")));
    }

    /** Naming an image is a decision; OntoBoard neither downloads it nor swaps in another. */
    @Test
    void whatRunShConfNamesIsNotReplacedWhenItIsMissing(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(
                project(root, MAKEFILE_16, "ODK_TAG=v1.5.4\n"),
                machine("obolibrary/odkfull:latest=v1.6.1"));

        assertNull(choice.reference());
        assertTrue(choice.note().contains("obolibrary/odkfull:v1.5.4"), choice.note());
        assertTrue(choice.note().contains("run.sh.conf"), choice.note());
    }

    /**
     * go-ontology's run.sh names {@code obolibrary/odkfull:v1.5.4} and says in a comment that
     * the version must be coordinated with its pipeline. A tag written out in the project's own
     * wrapper is its pin.
     */
    @Test
    void aTagTheProjectsOwnRunShNamesIsItsPin(@TempDir File root) throws IOException {
        File ontology = project(root, MAKEFILE_16, null);
        Files.write(new File(ontology, "run.sh").toPath(), ("#!/bin/sh\n"
                + "# see obolibrary/odkfull for details\n"
                + "docker run -m 12g -v $PWD/../../:/work --rm -ti obolibrary/odkfull:v1.5.4 \"$@\"\n")
                .getBytes(StandardCharsets.UTF_8));

        OdkImage.Choice here = OdkImage.forProject(ontology,
                machine("obolibrary/odkfull:v1.5.4=v1.5.4", "obolibrary/odkfull:v1.6=v1.6"));
        OdkImage.Choice missing = OdkImage.forProject(ontology,
                machine("obolibrary/odkfull:latest=v1.6.1"));

        assertEquals("obolibrary/odkfull:v1.5.4", here.reference());
        assertNull(here.note());
        assertNull(missing.reference(), "a pin is kept, or the run refused");
        assertTrue(missing.note().contains("run.sh") && missing.note().contains("v1.5.4"),
                missing.note());
    }

    /** ODK's own wrappers compose the name or leave the tag out: neither pins anything. */
    @Test
    void odksOwnWrappersPinNothing() {
        String runSh = new String(de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens
                .seedTree("eggboil").get("src/ontology/run.sh"), StandardCharsets.UTF_8);

        assertTrue(runSh.contains("obolibrary/$ODK_IMAGE:$ODK_TAG"), "ODK's run.sh, as seeded");
        assertNull(OdkImage.pinnedBy(runSh));
        assertNull(OdkImage.pinnedBy(
                "docker run -v %cd%\\..\\..\\:/work --rm -ti obolibrary/odkfull %*"));
        assertNull(OdkImage.pinnedBy(null));
        assertEquals("obolibrary/odklite:v1.4", OdkImage.pinnedBy(
                "IMAGE=odklite\ndocker run --rm obolibrary/odklite:v1.4 \"$@\""));
    }

    /** A value the shell would work out is not guessed at. */
    @Test
    void aRunShConfValueTheShellComputesIsLeftAlone(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(
                project(root, MAKEFILE_16, "ODK_TAG=${MY_TAG:-v1.5.4}\n"),
                machine("obolibrary/odkfull:v1.6=v1.6"));

        assertEquals("obolibrary/odkfull:v1.6", choice.reference());
        assertTrue(choice.note().contains("run.sh.conf"), choice.note());
    }

    @Test
    void withNoImageAtAllTheNoteSaysWhatToDo(@TempDir File root) throws IOException {
        OdkImage.Choice choice = OdkImage.forProject(project(root, MAKEFILE_16, null), machine());

        assertNull(choice.reference());
        assertTrue(choice.note().contains("Check requirements"), choice.note());
    }

    // ------------------------------------------------------------------ asking the runtime

    @Test
    void theProbeAsksTheRuntimeAndReadsItsAnswers() {
        final List<String> asked = new ArrayList<String>();
        ProcessRunner.Runner runtime = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                String line = String.join(" ", command);
                asked.add(line);
                if (line.equals("podman image ls --format {{.Tag}} obolibrary/odkfull")) {
                    return answer(0, "latest", "<none>", "v1.5.4");
                }
                if (line.equals("podman image inspect --format {{json .Config.Env}} "
                        + "obolibrary/odkfull:latest")) {
                    return answer(0, "[\"PATH=/usr/local/bin:/usr/bin\",\"ODK_VERSION=v1.6.1\","
                            + "\"JAVA_HOME=/usr/lib/jvm\"]");
                }
                if (line.equals("podman image inspect --format {{json .Config.Env}} "
                        + "obolibrary/odkfull:v1.5.4")) {
                    return answer(0, "[\"ODK_VERSION=v1.5.4\"]");
                }
                return answer(1, "Error: obolibrary/odkfull:v1.6.1: image not known");
            }
        };

        OdkImage.Probe probe = OdkImage.probe("podman", runtime);

        assertEquals(Arrays.asList("latest", "v1.5.4"), probe.localTags("obolibrary/odkfull"));
        assertEquals("v1.6.1", probe.odkVersionOf("obolibrary/odkfull:latest"));
        assertNull(probe.odkVersionOf("obolibrary/odkfull:v1.6.1"));
        assertEquals("obolibrary/odkfull:latest", OdkImage.pinned(probe));
        assertTrue(asked.contains("podman image inspect --format {{.Id}} "
                + "obolibrary/odkfull:v1.6.1"), asked.toString());
    }

    /** For what wants the pinned ODK's image and no other: which one is missing, and what to do. */
    @Test
    void aMissingPinnedImageIsNamedWithWhatToDoAboutIt() {
        assertEquals("ODK " + OdkPin.VERSION + "'s image (obolibrary/odkfull:" + OdkPin.VERSION
                + ") is not on this machine", OdkImage.pinnedIsNotHere());
        assertTrue(OdkImage.howToFetch().contains("does not download an image on its own"),
                OdkImage.howToFetch());
        assertTrue(OdkImage.howToFetch().contains("Check requirements"), OdkImage.howToFetch());
    }

    /**
     * The image's size is said from one place, as {@code docker images} reports it for ODK
     * v1.6.1's image (3.37GB): "about 3.4 GB". No other sentence of OntoBoard's names a size of
     * its own.
     */
    @Test
    void theImagesSizeIsSaidFromOnePlace() throws IOException {
        assertEquals("about 3.4 GB", OdkImage.SIZE);
        assertTrue(OdkImage.howToFetch().contains(OdkImage.SIZE), OdkImage.howToFetch());

        java.util.regex.Pattern literal = java.util.regex.Pattern.compile(
                "\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
        java.util.regex.Pattern size = java.util.regex.Pattern.compile(
                "(?i)\\d\\s*GB\\b|gigabyte");
        List<String> found = new ArrayList<String>();
        java.util.stream.Stream<java.nio.file.Path> sources =
                Files.walk(new File("src/main/java").toPath());
        try {
            for (Object each : sources.toArray()) {
                java.nio.file.Path source = (java.nio.file.Path) each;
                if (!source.toString().endsWith(".java")) {
                    continue;
                }
                int number = 0;
                for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                    number++;
                    java.util.regex.Matcher literals = literal.matcher(line);
                    while (literals.find()) {
                        if (size.matcher(literals.group(1)).find()) {
                            found.add(source.getFileName() + ":" + number + ": "
                                    + literals.group(1));
                        }
                    }
                }
            }
        } finally {
            sources.close();
        }
        assertEquals(1, found.size(), "one place says the size: " + found);
        assertTrue(found.get(0).startsWith("OdkImage.java:"), found.toString());
    }

    /** A runtime that cannot be started at all is a machine with no images, not a crash. */
    @Test
    void aRuntimeThatDoesNotAnswerHasNoImages() {
        ProcessRunner.Runner broken = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                throw new IOException("Cannot run program \"docker\"");
            }
        };

        assertNull(OdkImage.pinned(OdkImage.probe("docker", broken)));
    }

    private static ProcessRunner.Outcome answer(int exitCode, String... lines) {
        return new ProcessRunner.Outcome(exitCode, exitCode == 0 ? Arrays.asList(lines)
                : Collections.singletonList(lines[0]), false);
    }
}
