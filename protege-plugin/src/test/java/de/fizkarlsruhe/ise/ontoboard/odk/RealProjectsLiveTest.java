package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Two real projects whose imports are built by rules of their own - nfdicore and mwo, at the
 * commits below - brought to ODK v1.6.1 as OntoBoard's Update project files brings them, and
 * run twice: by ODK's own make in ODK's container, and by OntoBoard's engine. A refresh of each
 * import a rule of theirs builds, the mirror downloaded by each route, and then their fast test.
 * Held as OwnRulesEquivalenceTest holds the recorded runs: make's lines in order, the files
 * made, each ontology made as the same ontology.
 *
 * <p>Skipped without ODK's image or without the network; the times of both routes are kept in
 * {@code target/real-projects/}.
 */
class RealProjectsLiveTest {

    private static final Map<String, String> RUN = new LinkedHashMap<String, String>();

    static {
        RUN.put("VERSION", "1.0.0");
        RUN.put("TODAY", "2026-11-16");
    }

    private static final String PLUGINS = "src/ontology/tmp/plugins/";
    private static final File OUT = new File("target/real-projects");

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

    /**
     * The projects, at the commits these runs were made with (2026-11-16), and whether one needs
     * Protégé 5.6.9's OWL API: nfdicore's mirror of DCAT imports SKOS, PROV-O and DC Terms by
     * http addresses that move to https, which the OWL API of Protégé 5.5.0 (4.5.9) does not
     * follow when it loads an import - ROBOT reads the page about the move. Run with
     * {@code -Dowlapi.version=4.5.29} for it.
     */
    static Stream<Arguments> projects() {
        return Stream.of(
                Arguments.of("nfdicore", "https://github.com/ISE-FIZKarlsruhe/nfdicore.git",
                        "631e18a27f4a43f7f664264708466ce0c86215aa", true),
                Arguments.of("mwo", "https://github.com/ISE-FIZKarlsruhe/mwo.git",
                        "0dcb29f5708b2d45106fc3fc19b5224b684af711", false));
    }

    @ParameterizedTest
    @MethodSource("projects")
    void itsOwnRulesRunHereAsMakeRunsThemInOdksContainer(String name, String url, String commit,
            boolean importsThatMove, @TempDir File temp) throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        assumeTrue(!importsThatMove || RunComparisons.onOdksOwlApi(), name + " imports by "
                + "addresses that move from http to https, which this OWL API does not follow "
                + "for an import; run with -Dowlapi.version=4.5.29, Protégé 5.6.9's");
        File clone = new File(temp, "clone");
        ProcessRunner.Outcome cloned = ProcessRunner.real().run(temp, Arrays.asList("git",
                "clone", "--quiet", url, clone.getPath()), 10, null);
        assumeTrue(cloned.isSuccess(), "no network: " + cloned.lastMeaningfulLine());
        assertTrue(ProcessRunner.real().run(clone, Arrays.asList("git", "checkout", "--quiet",
                commit), 5, null).isSuccess(), commit);
        OdkUpdate.apply(clone, OdkUpdate.plan(clone));

        // A refresh of each import a rule of theirs builds, then the fast test. In that order:
        // their rules name the mirror as what the module is made from, the fast test downloads
        // nothing (MIR=false), and on a fresh clone, with no mirror, ODK's own make stops there
        // ("No rule to make target 'mirror/ro.owl'") - as a maintainer's refresh first fetches.
        List<String> targets = new ArrayList<String>();
        File ontology = new File(clone, "src/ontology");
        for (String id : importsOfTheirOwn(ontology)) {
            targets.add("refresh-" + id);
        }
        assertFalse(targets.isEmpty(), name + " has import rules of its own");
        targets.add("test_fast");
        File odks = copy(clone, new File(temp, "odk"));
        File ours = copy(clone, new File(temp, "ontoboard"));
        allOfTheSameAge(odks);
        allOfTheSameAge(ours);
        List<String> times = new ArrayList<String>();
        for (String target : targets) {
            Map<String, String> before = hashes(odks);
            Map<String, String> ourBefore = hashes(ours);

            long start = System.nanoTime();
            ProcessRunner.Outcome made = ProcessRunner.real().run(null,
                    MakeRun.containerCommandFor(runtime, new File(odks, "src/ontology"), image,
                            host, line(target)), MakeRun.TIMEOUT_MINUTES, null);
            long inTheContainer = System.nanoTime() - start;
            File edit = OdkProjectLoader.editFileOf(new File(ours, "src/ontology"));
            assertNotNull(edit, name);
            start = System.nanoTime();
            OdkEngine.Outcome ran = OdkEngine.run(edit, target, RUN, null);
            long here = System.nanoTime() - start;
            times.add(String.format("%s %s: container %.1f s, OntoBoard %.1f s", name, target,
                    inTheContainer / 1e9, here / 1e9));
            keep(name + "-" + target + ".odk.txt", made.getOutput());
            keep(name + "-" + target + ".ontoboard.txt", ran.getTranscript());

            assertTrue(made.isSuccess(), name + " " + target + " in ODK's container: "
                    + made.lastMeaningfulLine());
            assertTrue(ran.isOk(), name + " " + target + " here: " + ran.getFailure());
            assertEquals(String.join("\n", saidByMake(lines(String.join("\n",
                    made.getOutput())))), String.join("\n", saidByMake(lines(String.join("\n",
                    ran.getTranscript())))), name + " " + target + ": make's lines, in order");
            Set<String> theirs = withoutPlugins(madeSince(before, odks));
            assertEquals(theirs, withoutPlugins(madeSince(ourBefore, ours)),
                    name + " " + target + ": the files made");
            for (String path : theirs) {
                if (path.endsWith(".owl") || path.endsWith(".ofn")) {
                    assertSameOntology(name + " " + path, new File(odks, path),
                            new File(ours, path));
                }
            }
        }
        keep(name + "-times.txt", times);
    }

    /** The ids of the import modules a rule of the project's own makefile builds. */
    private static List<String> importsOfTheirOwn(File ontology) throws IOException {
        File[] own = ontology.listFiles((folder, file) -> file.endsWith(".Makefile"));
        assertTrue(own != null && own.length == 1, "one makefile of the project's own");
        Matcher rule = Pattern.compile("(?m)^\\$\\(IMPORTDIR\\)/([A-Za-z0-9_-]+)_import\\.owl\\s*:")
                .matcher(new String(Files.readAllBytes(own[0].toPath()), StandardCharsets.UTF_8));
        List<String> ids = new ArrayList<String>();
        while (rule.find()) {
            ids.add(rule.group(1));
        }
        return ids;
    }

    private static List<String> line(String target) {
        List<String> line = new ArrayList<String>(Arrays.asList("make", target));
        for (Map.Entry<String, String> variable : RUN.entrySet()) {
            line.add(variable.getKey() + "=" + variable.getValue());
        }
        return line;
    }

    private static Set<String> withoutPlugins(Set<String> made) {
        for (Iterator<String> path = made.iterator(); path.hasNext();) {
            if (path.next().startsWith(PLUGINS)) {
                path.remove();
            }
        }
        return made;
    }

    /** A copy of the clone, without its .git. */
    private static File copy(File from, File to) throws IOException {
        Path source = from.toPath();
        try (Stream<Path> all = Files.walk(source)) {
            for (Path path : all.collect(Collectors.toList())) {
                Path relative = source.relativize(path);
                if (relative.getNameCount() > 0 && relative.getName(0).toString()
                        .equals(".git")) {
                    continue;
                }
                Path target = to.toPath().resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
        assertFalse(new File(to, ".git").exists());
        return to;
    }

    private static void keep(String name, List<String> lines) throws IOException {
        Files.createDirectories(OUT.toPath());
        Files.write(new File(OUT, name).toPath(), String.join("\n", lines)
                .getBytes(StandardCharsets.UTF_8));
    }
}
