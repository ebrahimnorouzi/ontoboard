package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Acceptance 2: with Docker, a new project is seeded by ODK itself, and what ODK seeds on this
 * machine today is what OntoBoard's bundled renderer writes.
 *
 * <p>The goldens of {@code SeedParityTest} were made once and are kept. This asks the image
 * again, each time it is run on a machine that has it - so an image that changed under the same
 * version, or a difference between the machine the goldens were made on and this one, shows.
 * Without the image the live tests are skipped and say so; the tests of the command itself run
 * everywhere.
 */
class SeedLiveParityTest {

    private static final String IMAGE = "obolibrary/odkfull:v1.6.1";

    private static String runtime;
    private static String pinned;
    private static OdkContainer.Host host;

    @BeforeAll
    static void lookForTheImage() {
        runtime = MakeRun.containerRuntime(ProcessRunner.real());
        if (runtime != null) {
            pinned = OdkImage.pinned(OdkImage.probe(runtime, ProcessRunner.real()));
            host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
        }
    }

    // ------------------------------------------------------------------ live

    @ParameterizedTest
    @ValueSource(strings = {"eggboil", "oboimports", "components", "artefacts", "frozen"})
    void theImagesSeedIsTheBundledOne(String name, @TempDir File temp) {
        assumeTrue(pinned != null, "ODK v1.6.1's image is not on this machine");
        File root = new File(temp, name);

        List<File> written = OdkSeedInContainer.run(runtime, pinned, host, Goldens.config(name),
                root, null, ProcessRunner.real());

        Goldens.assertTreeEquals(Goldens.tree(root),
                OdkInstaller.renderSeed(Goldens.config(name)).files());
        assertEquals(Goldens.tree(root).size(), written.size());
    }

    /** Review focus 1: ODK's run.sh refuses such a path; its seed, given arguments, does not. */
    @Test
    void aFolderWithSpacesAndUmlautsIsSeeded(@TempDir File temp) {
        assumeTrue(pinned != null, "ODK v1.6.1's image is not on this machine");
        File root = new File(temp, "José Müller/My Ontologies/eggboil");
        assertTrue(root.getParentFile().mkdirs());

        OdkSeedInContainer.run(runtime, pinned, host, Goldens.config("eggboil"), root, null,
                ProcessRunner.real());

        Goldens.assertTreeEquals(Goldens.seedTree("eggboil"), Goldens.tree(root));
    }

    /** {@code odk.py seed --source}: the ontology given becomes the edit file, byte for byte. */
    @Test
    void adoptingAnOntologyIsOdksOwnSource(@TempDir File temp) {
        assumeTrue(pinned != null, "ODK v1.6.1's image is not on this machine");
        byte[] source = "Ontology(<https://example.org/mine>)\n".getBytes(StandardCharsets.UTF_8);
        File root = new File(temp, "eggboil");

        OdkSeedInContainer.run(runtime, pinned, host, Goldens.config("eggboil"), root, source,
                ProcessRunner.real());

        Goldens.assertTreeEquals(Goldens.tree(root),
                OdkInstaller.renderSeed(Goldens.config("eggboil"), source).files());
    }

    /** What ODK refuses, it says; and nothing of a refused seed is left behind. */
    @Test
    void aConfigOdkRefusesIsRefusedInItsWords(@TempDir File temp) {
        assumeTrue(pinned != null, "ODK v1.6.1's image is not on this machine");
        File root = new File(temp, "broken");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> OdkSeedInContainer.run(runtime, pinned, host,
                        "id: broken\nimport_group:\n  products:\n    - title: no id here\n", root,
                        null, ProcessRunner.real()));

        assertTrue(refused.getMessage().contains("odk.py seed"), refused.getMessage());
        assertFalse(root.exists(), "nothing is left of a seed that failed");
    }

    // ------------------------------------------------------------------ the command

    private static OdkContainer.Host windows() {
        return new OdkContainer.Host(true, null, null, 0, Collections.<String, String>emptyMap());
    }

    private static String slashed(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }

    @Test
    void theCommandIsOdksOwnSeedWithoutGit(@TempDir File temp) {
        File configs = new File(temp, "cfg");
        File parent = new File(temp, "projects");

        assertEquals(Arrays.asList("docker", "run", "--rm", "-v",
                slashed(configs) + ":/seedconfig:ro", "-v", slashed(parent) + ":/work", "-w",
                "/work", IMAGE, "odk.py", "seed", "--skipgit", "-C", "/seedconfig/seed-odk.yaml",
                "-D", "/work/eggboil"),
                OdkSeedInContainer.command("docker", IMAGE, windows(), configs, "seed-odk.yaml",
                        parent, "eggboil", null));
    }

    @Test
    void onLinuxAndMacTheProjectBelongsToTheUser(@TempDir File temp) {
        List<String> command = OdkSeedInContainer.command("podman", IMAGE,
                new OdkContainer.Host(false, "501", "20", 0,
                        Collections.<String, String>emptyMap()),
                new File(temp, "cfg"), "seed-odk.yaml", new File(temp, "projects"), "eggboil",
                null);

        int image = command.indexOf(IMAGE);
        assertEquals(Arrays.asList("-e", "ODK_USER_ID=501", "-e", "ODK_GROUP_ID=20"),
                command.subList(image - 4, image));
    }

    @Test
    void adoptingHandsTheOntologyToSeedAsItsSource(@TempDir File temp) {
        List<String> command = OdkSeedInContainer.command("docker", IMAGE, windows(),
                new File(temp, "cfg"), "seed-odk.yaml", new File(temp, "projects"), "eggboil",
                "source.owl");

        assertEquals(Arrays.asList("-D", "/work/eggboil", "-s", "/seedconfig/source.owl"),
                command.subList(command.size() - 4, command.size()));
    }

    @Test
    void aFolderNameWithSpacesIsOneArgument(@TempDir File temp) {
        List<String> command = OdkSeedInContainer.command("docker", IMAGE, windows(),
                new File(temp, "cfg"), "seed-odk.yaml", new File(temp, "My Ontologies"),
                "Test kitchen", null);

        assertEquals("/work/Test kitchen", command.get(command.size() - 1));
        assertTrue(command.contains(slashed(new File(temp, "My Ontologies")) + ":/work"));
    }

    /** The folder's name goes into a path inside the container; it names one folder, no more. */
    @ParameterizedTest
    @ValueSource(strings = {"", "..", "a/b", "a\\b", "../eggboil", "."})
    void aNameThatIsNotOneFolderIsRefused(String folder) {
        assertThrows(IllegalArgumentException.class, () -> OdkSeedInContainer.command("docker",
                IMAGE, windows(), new File("cfg"), "seed-odk.yaml", new File("projects"), folder,
                null));
    }

    // ------------------------------------------------------------------ running it, with no Docker

    /** A runtime that does what ODK's seed would: writes one file, or fails saying why. */
    private static ProcessRunner.Runner pretend(final File root, final boolean succeeds,
            final List<List<String>> ran) {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                // The config is where the command says it is, while the command runs.
                String mount = command.get(command.indexOf("-v") + 1);
                File configs = new File(mount.substring(0, mount.length() - ":/seedconfig:ro"
                        .length()));
                assertTrue(new File(configs, "seed-odk.yaml").isFile(), mount);
                File ontology = new File(root, "src/ontology");
                assertTrue(ontology.mkdirs());
                Files.write(new File(ontology, "Makefile").toPath(), new byte[] {'x'});
                return succeeds
                        ? new ProcessRunner.Outcome(0, Arrays.asList("Repository files have been "
                                + "successfully copied, but no git commands have been run."),
                                false)
                        : new ProcessRunner.Outcome(1, Arrays.asList("Traceback (most recent call "
                                + "last):", "dacite.exceptions.MissingValueError: missing value "
                                + "for field \"import_group.products.id\"", ""), false);
            }
        };
    }

    @Test
    void whatWasSeededIsReturnedAndTheConfigCopyIsCleanedUp(@TempDir File temp) {
        File root = new File(temp, "eggboil");
        List<List<String>> ran = new ArrayList<List<String>>();

        List<File> written = OdkSeedInContainer.run("docker", IMAGE, windows(), "id: eggboil\n",
                root, null, pretend(root, true, ran));

        assertEquals(Collections.singletonList(new File(root, "src/ontology/Makefile")), written);
        String mount = ran.get(0).get(ran.get(0).indexOf("-v") + 1);
        assertFalse(new File(mount.substring(0, mount.length() - ":/seedconfig:ro".length()))
                .exists(), "the config's temporary copy is gone");
    }

    @Test
    void aSeedThatFailsSaysWhatOdkSaidAndLeavesNothing(@TempDir File temp) {
        File root = new File(temp, "eggboil");

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> OdkSeedInContainer.run("docker", IMAGE, windows(), "id: eggboil\n", root,
                        null, pretend(root, false, new ArrayList<List<String>>())));

        assertTrue(failed.getMessage().contains("missing value for field"), failed.getMessage());
        assertTrue(failed.getMessage().contains("odk.py seed"), failed.getMessage());
        assertFalse(root.exists());
    }

    @Test
    void aFolderThatHoldsSomethingIsRefusedBeforeAnythingRuns(@TempDir File temp)
            throws IOException {
        Files.write(new File(temp, "notes.txt").toPath(), new byte[] {1});
        List<List<String>> ran = new ArrayList<List<String>>();

        assertThrows(IllegalStateException.class, () -> OdkSeedInContainer.run("docker", IMAGE,
                windows(), "id: eggboil\n", temp, null, pretend(temp, true, ran)));

        assertTrue(ran.isEmpty());
        assertEquals(1, temp.list().length);
    }
}
