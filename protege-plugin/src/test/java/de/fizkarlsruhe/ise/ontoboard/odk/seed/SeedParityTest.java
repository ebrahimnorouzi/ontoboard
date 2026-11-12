package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Acceptance 1: without Docker, a project OntoBoard seeds is the project {@code odk.py seed}
 * writes - the same files, the same bytes.
 *
 * <p>The expected trees were written by ODK itself in {@code obolibrary/odkfull} v1.6.1
 * ({@code tools/odk-goldens/generate.py}); nothing here was typed in by hand.
 */
class SeedParityTest {

    @ParameterizedTest
    @ValueSource(strings = {"eggboil", "evidence-eggboil", "oboimports", "custombase",
        "components", "artefacts", "nodefaults", "frozen"})
    void theSeedIsOdksByteForByte(String name) {
        Goldens.assertTreeEquals(Goldens.seedTree(name),
                OdkInstaller.renderSeed(Goldens.config(name)).files());
    }

    /**
     * The places where "nearly ODK's" would show: each of these lines is one a careful
     * reimplementation gets wrong, and each was wrong in an earlier attempt or would be without
     * Python's rules.
     */
    @Test
    void theEggSeedShowsPythonsRules() {
        Map<String, byte[]> files =
                OdkInstaller.renderSeed(Goldens.config("evidence-eggboil")).files();
        String makefile = text(files, "src/ontology/Makefile");
        String[] lines = makefile.split("\n", -1);

        assertEquals("# ODK Version: v1.6.1", lines[3]);
        assertEquals("CONFIG_HASH=                "
                + "8a5a8b7d2e2f0c8698940dd146c9113a5d914b7cd9da41d128e55ef029ec30b6", lines[12]);
        assertEquals(".PHONY: extra_robot_plugins", lines[150]);
        assertEquals("extra_robot_plugins: ", lines[151]);
        assertEquals("SUBSETS = ", lines[204]);
        assertTrue(lines[513].endsWith("$(OTHER_SRC)) "), lines[513]);
        assertTrue(makefile.endsWith("include eggboil.Makefile"), "the Makefile ends without "
                + "a newline");
        assertTrue(text(files, "src/metadata/eggboil.md").contains("\n  email: \n"));
        assertEquals(0, files.get("src/ontology/imports/iao_terms.txt").length);
        assertEquals(8, text(files, "src/ontology/run.sh").split("\u00a0", -1).length - 1);
        // ODK's CONTRIBUTING.md template has Windows line endings; what it renders has not.
        assertTrue(new String(TemplateSet.bundled().entry("CONTRIBUTING.md.jinja2").bytes(),
                StandardCharsets.UTF_8).contains("\r\n"));
        assertTrue(text(files, "CONTRIBUTING.md").contains("EGGBOIL"));
        assertFalse(text(files, "CONTRIBUTING.md").contains("\r"));
        // No profile of its own asked for, so ODK's is not copied in.
        assertFalse(files.containsKey("src/ontology/profile.txt"));
    }

    /** Review focus 2: a config saved on Windows hashes, and so renders, like the same on Linux. */
    @Test
    void aConfigWithWindowsLineEndingsGivesTheSameFilesAndKeepsItsOwnBytes() {
        String unix = Goldens.config("eggboil");
        assertFalse(unix.contains("\r"));
        String windows = unix.replace("\n", "\r\n");
        Map<String, byte[]> expected =
                new LinkedHashMap<String, byte[]>(OdkInstaller.renderSeed(unix).files());
        Map<String, byte[]> actual =
                new LinkedHashMap<String, byte[]>(OdkInstaller.renderSeed(windows).files());

        assertArrayEquals(windows.getBytes(StandardCharsets.UTF_8),
                actual.remove("src/ontology/eggboil-odk.yaml"));
        expected.remove("src/ontology/eggboil-odk.yaml");
        Goldens.assertTreeEquals(expected, actual);
    }

    @Test
    void whatOdkMarksExecutableIsMarkedSo() {
        Set<String> executable = OdkInstaller.renderSeed(Goldens.config("eggboil")).executable();

        assertEquals(new TreeSet<String>(Arrays.asList("src/ontology/run.sh",
                "src/scripts/run-command.sh")), new TreeSet<String>(executable));
    }

    /** {@code odk.py seed --source}: the given ontology becomes the edit file. */
    @Test
    void aSourceOntologyTakesTheStubsPlace() {
        byte[] source = "Ontology(<https://example.org/mine>)\n".getBytes(StandardCharsets.UTF_8);

        Map<String, byte[]> files =
                OdkInstaller.renderSeed(Goldens.config("eggboil"), source).files();

        assertArrayEquals(source, files.get("src/ontology/eggboil-edit.owl"));
        assertEquals(Goldens.seedTree("eggboil").keySet(), files.keySet());
    }

    /**
     * ODK then writes {@code project.yaml} with PyYAML's dumper, whose bytes are not reproduced
     * here. Seeding without the file would be a project that differs from ODK's unseen.
     */
    @Test
    void aConfigThatAsksForProjectYamlIsRefusedRatherThanSeededWithoutIt() {
        OdkConfigException refused = assertThrows(OdkConfigException.class,
                () -> OdkInstaller.renderSeed(Goldens.config("eggboil")
                        + "export_project_yaml: true\n"));

        assertTrue(refused.getMessage().contains("export_project_yaml"), refused.getMessage());
        assertTrue(refused.getMessage().contains("ODK's container"), refused.getMessage());
        assertFalse(refused.getMessage().contains("Start Docker"), "a container is one way, "
                + "not an order: " + refused.getMessage());
    }

    /** A config comes from wherever the project came from; its names must stay in the project. */
    @ParameterizedTest
    @ValueSource(strings = {"../../../outside.owl", "/etc/outside.owl", "a\\..\\outside.owl",
        "C:/outside.owl", "sub//outside.owl"})
    void aFileNameThatLeavesTheProjectIsRefused(String filename) {
        String config = "id: esc\ncomponents:\n  products:\n    - filename: '" + filename + "'\n";

        OdkConfigException refused = assertThrows(OdkConfigException.class,
                () -> OdkInstaller.renderSeed(config));

        assertTrue(refused.getMessage().contains("outside.owl"), refused.getMessage());
    }

    @Test
    void aConfigWithoutAnIdIsRefused() {
        OdkConfigException refused = assertThrows(OdkConfigException.class,
                () -> OdkInstaller.renderSeed("title: No id\n"));

        assertTrue(refused.getMessage().contains("id"), refused.getMessage());
    }

    // ------------------------------------------------------------------ onto the disk

    @Test
    void seedWritesTheWholeProjectIntoANewFolder(@TempDir File temp) {
        File root = new File(temp, "eggboil");

        List<File> written = OdkInstaller.seed(Goldens.config("eggboil"), root, null);

        Goldens.assertTreeEquals(Goldens.seedTree("eggboil"), Goldens.tree(root));
        assertEquals(Goldens.seedTree("eggboil").size(), written.size());
        assertTrue(written.contains(new File(root, "src/ontology/Makefile")));
    }

    @Test
    void anEmptyFolderIsAsGoodAsNone(@TempDir File temp) {
        OdkInstaller.seed(Goldens.config("eggboil"), temp, null);

        Goldens.assertTreeEquals(Goldens.seedTree("eggboil"), Goldens.tree(temp));
    }

    @Test
    void aFolderThatHoldsSomethingIsRefusedAndLeftAlone(@TempDir File temp) throws IOException {
        Files.write(new File(temp, "notes.txt").toPath(), new byte[] {1});

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> OdkInstaller.seed(Goldens.config("eggboil"), temp, null));

        assertTrue(refused.getMessage().contains(temp.getName()), refused.getMessage());
        assertArrayEquals(new String[] {"notes.txt"}, temp.list());
    }

    /** A seed that cannot finish leaves the folder as it found it, so it can be tried again. */
    @Test
    void aSeedThatFailsHalfwayTakesBackWhatItWrote(@TempDir File temp) {
        OdkInstaller.Rendered rendered = OdkInstaller.renderSeed(Goldens.config("eggboil"));
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>(rendered.files());
        // One more file, which cannot be written: README.md is a file by then, not a folder.
        files.put("README.md/cannot", new byte[] {1});

        assertThrows(RuntimeException.class,
                () -> OdkInstaller.write(files, rendered.executable(), temp));

        assertEquals(0, temp.list().length);
    }

    // ------------------------------------------------------------------ policies

    @Test
    void theUpdatePoliciesAreOdks() {
        PyObject egg = OdkProjectModel.load(Goldens.config("eggboil"));
        PyObject evidence = OdkProjectModel.load(Goldens.config("evidence-eggboil"));
        String always = "CODE_OF_CONDUCT.md=NEVER, CONTRIBUTING.md=NEVER, issue_template.md=NEVER, "
                + "README.md=NEVER, src/patterns/data/default/example.tsv=NEVER, "
                + "src/patterns/dosdp-patterns/example.yaml=NEVER, src/ontology/Makefile=ALWAYS, "
                + "src/ontology/run.sh=ALWAYS, src/ontology/catalog-v001.xml=NEVER, "
                + "src/sparql/*=ALWAYS, docs/odk-workflows/*=ALWAYS, .gitignore=NEVER, "
                + ".github/workflows/qc.yml=ALWAYS";

        // The egg project has a profile of its own, so ODK installs its copy when it is missing.
        assertEquals(always, shown(OdkInstaller.updatePolicies(egg)));
        assertEquals(always + ", src/ontology/profile.txt=NEVER",
                shown(OdkInstaller.updatePolicies(evidence)));
        assertEquals("", shown(OdkInstaller.seedPolicies(egg)));
        assertEquals("src/ontology/profile.txt=NEVER", shown(OdkInstaller.seedPolicies(evidence)));
    }

    @Test
    void aRuleMatchesAsPythonsFnmatchDoes() {
        OdkInstaller.Rule sparql = new OdkInstaller.Rule("src/sparql/*", OdkInstaller.Policy.ALWAYS);
        OdkInstaller.Rule readme = new OdkInstaller.Rule("README.md", OdkInstaller.Policy.NEVER);

        assertTrue(sparql.matches("src/sparql/terms.sparql"));
        assertTrue(sparql.matches("src/sparql/deeper/terms.sparql"), "* crosses a slash");
        assertTrue(sparql.matches("src/sparql/"), "* matches nothing at all");
        assertFalse(sparql.matches("src/sparql"));
        assertTrue(readme.matches("README.md"));
        assertFalse(readme.matches("src/sparql/README.md"));
        assertFalse(readme.matches("readme.md"));
    }

    /**
     * What {@code odk.py update} would write over a project that is exactly the seed: the files
     * ODK always rewrites, and none of those it only adds when missing.
     */
    @Test
    void anUpdateOverTheSeedWritesOnlyWhatOdkAlwaysRewrites() {
        PyObject project = OdkProjectModel.load(Goldens.config("eggboil"));
        final Set<String> onDisk = Goldens.seedTree("eggboil").keySet();

        Map<String, byte[]> written = OdkInstaller.install(project,
                OdkInstaller.updatePolicies(project), onDisk::contains).files();

        Set<String> expected = new TreeSet<String>();
        for (String path : onDisk) {
            if (path.startsWith("src/sparql/") || path.equals("src/ontology/Makefile")
                    || path.equals("src/ontology/run.sh")
                    || path.equals(".github/workflows/qc.yml")) {
                expected.add(path);
            }
        }
        assertEquals(expected, new TreeSet<String>(written.keySet()));
        for (String path : expected) {
            assertArrayEquals(Goldens.seedTree("eggboil").get(path), written.get(path), path);
        }
    }

    /** With no policy at all every file the templates give is there, the profile included. */
    @Test
    void renderAllIsEveryFileTheTemplatesGive() {
        PyObject project = OdkProjectModel.load(Goldens.config("evidence-eggboil"));

        Set<String> all = OdkInstaller.renderAll(project).files().keySet();

        Set<String> expected = new TreeSet<String>(Goldens.seedTree("evidence-eggboil").keySet());
        expected.remove("src/ontology/eggboil-odk.yaml");
        expected.add("src/ontology/profile.txt");
        assertEquals(expected, new TreeSet<String>(all));
    }

    // ------------------------------------------------------------------ helpers

    private static String text(Map<String, byte[]> files, String path) {
        assertTrue(files.containsKey(path), "no " + path + " among " + files.keySet());
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    private static String shown(List<OdkInstaller.Rule> rules) {
        StringBuilder out = new StringBuilder();
        for (OdkInstaller.Rule rule : rules) {
            out.append(out.length() == 0 ? "" : ", ").append(rule.pattern()).append('=')
                    .append(rule.policy());
        }
        return out.toString();
    }
}
