package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameFile;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameProduct;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.read;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.rows;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByTheReport;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.starting;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.util.VersionInfo;

/**
 * Each task of an ODK project run both ways on fresh copies of the same project - ODK's own
 * program in ODK's Docker image, and OntoBoard's own code without a container - the two results
 * held against each other, each way timed, and the table written to
 * {@code target/side-by-side/} ({@code results.csv}, {@code report.md}).
 *
 * <p>The project is the egg fixture ODK's recordings were made on (the eggboil seed with
 * {@code equivalence/egg/overlay} over it), every file of it of the recording's age, and make's
 * command line is the recording's. What "the same" means for each task is what the tests of the
 * pieces already hold OntoBoard's side to ({@link RunComparisons}, {@code SeedLiveParityTest}):
 * here the other side is ODK running now instead of a recording. The two rows that refresh
 * imports run on the project ODK's import targets were recorded on ({@link ImportCases}, id
 * loim), its upstream ontologies read from a folder inside the copy, as
 * {@code ImportChainEquivalenceTest} holds OntoBoard's refresh against the recording.
 *
 * <p>Where OntoBoard's route refuses a task, the row says why and the test does not fail; where
 * both run, they must agree. Skipped unless a container program answers and ODK v1.6.1's image
 * is on the machine. {@code -Dsidebyside.runs=N} runs each task N counted times after one that
 * is not counted, each on a fresh copy; by default each task once.
 */
class SideBySideLiveTest {

    /** make's command line in ODK's recording (tools/odk-goldens/equivalence_goldens.py). */
    private static final Map<String, String> AS_RECORDED = new LinkedHashMap<String, String>();

    static {
        AS_RECORDED.put("VERSION", "1.0.0");
        AS_RECORDED.put("TODAY", "2026-11-11");
        AS_RECORDED.put("OBODATE", "11:11:2026 12:00");
    }

    private static final String EDIT = "src/ontology/eggboil-edit.owl";

    /** ODK's copies of ROBOT's plugin jars: not goldens in the recording, not compared here. */
    private static final String PLUGINS = "src/ontology/tmp/plugins/";

    private static final File OUT = new File("target/side-by-side");

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

    @Test
    void eachTaskBothWaysComparedAndTimed(@TempDir File temp) throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        int runs = Integer.getInteger("sidebyside.runs", 0);
        File reference = new File(temp, "reference");
        EGG.writeTo(reference);
        Map<String, String> egg = hashes(reference);
        File afterATest = new File(temp, "reference-after-a-test");
        AFTER_A_TEST.writeTo(afterATest);
        Map<String, String> tested = hashes(afterATest);

        List<SideBySide.Row> rows = new ArrayList<SideBySide.Row>();
        rows.add(SideBySide.run("New project", "eggboil", runs, new File(temp, "seed"),
                EMPTY, seededInDocker(), seededHere(), SideBySide.byAssertions(
                        new SideBySide.Check<File>() {
                            @Override
                            public void check(File odks, File ontoBoards) {
                                Goldens.assertTreeEquals(Goldens.tree(odks),
                                        Goldens.tree(ontoBoards));
                            }
                        })));
        rows.add(SideBySide.run("Update project files", "egg-changed", runs,
                new File(temp, "update"), CHANGED, updatedInDocker(), updatedHere(),
                asOdksUpdate(new File(temp, "update"))));
        File imports = new File(temp, "reference-imports");
        LOIM.writeTo(imports);
        rows.add(refresh("Refresh imports", "refresh-imports", runs, temp,
                new File(imports, "project")));
        rows.add(refresh("Refresh one import", "refresh-ro", runs, temp,
                new File(imports, "project")));
        rows.add(make("Test, fast", "test_fast", runs, temp, EGG, egg));
        rows.add(make("Test, full", "test", runs, temp, EGG, egg));
        rows.add(make("Release, fast", "prepare_release_fast", runs, temp, EGG, egg));
        rows.add(make("Release, full", "prepare_release", runs, temp, EGG, egg));
        rows.add(make("Clean", "clean", runs, temp, AFTER_A_TEST, tested));

        SideBySide.write(OUT, rows, machine());

        List<Executable> agreements = new ArrayList<Executable>();
        for (final SideBySide.Row row : rows) {
            agreements.add(new Executable() {
                @Override
                public void execute() {
                    assertTrue(row.isRefused() || row.agrees(),
                            row.getTask() + ": " + row.getDiffers());
                }
            });
        }
        assertAll("every task both ways ran, the same (see " + OUT + "/report.md)", agreements);
    }

    // ------------------------------------------------------------------ the projects

    /** Nothing: a new project is made into a folder of its own. */
    private static final SideBySide.Fixture EMPTY = new SideBySide.Fixture() {
        @Override
        public void writeTo(File folder) throws Exception {
            Files.createDirectories(folder.toPath());
        }
    };

    /** The egg fixture, every file of the recording's age. */
    private static final SideBySide.Fixture EGG = new SideBySide.Fixture() {
        @Override
        public void writeTo(File folder) throws Exception {
            EggFixture.write(folder, false);
            allOfTheSameAge(folder);
        }
    };

    /** The egg fixture with what ODK's fast test made in it, as a clean finds a project. */
    private static final SideBySide.Fixture AFTER_A_TEST = new SideBySide.Fixture() {
        @Override
        public void writeTo(File folder) throws Exception {
            EggFixture.write(folder, false);
            File made = new File(Goldens.root(), "equivalence/egg/test_fast/files");
            for (Map.Entry<String, byte[]> file : Goldens.tree(made).entrySet()) {
                File to = new File(folder, file.getKey());
                Files.createDirectories(to.getParentFile().toPath());
                Files.write(to.toPath(), file.getValue());
            }
            allOfTheSameAge(folder);
        }
    };

    // ------------------------------------------------------------------ a new project

    private static SideBySide.Route<File> seededInDocker() {
        return new SideBySide.Route<File>() {
            @Override
            public File run(File project) {
                File root = new File(project, "eggboil");
                OdkSeedInContainer.run(runtime, image, host, Goldens.config("eggboil"), root, null,
                        ProcessRunner.real());
                return root;
            }
        };
    }

    private static SideBySide.Route<File> seededHere() {
        return new SideBySide.Route<File>() {
            @Override
            public File run(File project) {
                File root = new File(project, "eggboil");
                OdkInstaller.seed(Goldens.config("eggboil"), root, null);
                return root;
            }
        };
    }

    // ------------------------------------------------------------------ the update

    /** The recorded project with work in it, as ODK's update was recorded on it. */
    private static final SideBySide.Fixture CHANGED = new SideBySide.Fixture() {
        @Override
        public void writeTo(File folder) {
            UpdateCases.write(UpdateCases.before("egg-changed"), folder);
        }
    };

    /** What an update left: how it ended, the files it says it wrote, the project. */
    private static final class Updated {
        final String failure;
        final Set<String> written;
        final File root;

        Updated(String failure, Set<String> written, File root) {
            this.failure = failure;
            this.written = written;
            this.root = root;
        }
    }

    /**
     * ODK's own update in its container. The plan agrees to take out what ODK takes out without
     * a word - an import the configuration does not declare - as OdkUpdateLiveTest's does:
     * OntoBoard's update is not made while that is undecided.
     */
    private static SideBySide.Route<Updated> updatedInDocker() {
        return new SideBySide.Route<Updated>() {
            @Override
            public Updated run(File project) {
                OdkUpdate.Ran ran = OdkUpdate.inContainer(runtime, image, host, project,
                        UpdateCases.planAsOdkDoes(project), ProcessRunner.real());
                return new Updated(ran.isOk() ? null : ran.getFailure() + "\n"
                        + String.join("\n", ran.getTranscript()),
                        UpdateCases.relative(project, ran.getWritten()), project);
            }
        };
    }

    private static SideBySide.Route<Updated> updatedHere() {
        return new SideBySide.Route<Updated>() {
            @Override
            public Updated run(File project) {
                List<File> written = OdkUpdate.apply(project, UpdateCases.planAsOdkDoes(project));
                return new Updated(null, UpdateCases.relative(project, written), project);
            }
        };
    }

    /** As OdkUpdateLiveTest compares the two updates. */
    private static SideBySide.Comparison<Updated> asOdksUpdate(final File scratch) {
        return SideBySide.byAssertions(new SideBySide.Check<Updated>() {
            @Override
            public void check(Updated odks, Updated ours) throws Exception {
                assertNull(odks.failure, "ODK's update did not finish");
                assertTrue(!ours.written.isEmpty(), "OntoBoard's update wrote nothing");
                UpdateCases.assertSameProjects(scratch, odks.root, ours.root);
                assertEquals(odks.written, ours.written, "and both say they wrote the same files");
            }
        });
    }

    // ------------------------------------------------------------------ a target

    /**
     * What a run of a target left: how it ended, what it said, the project - and, for a refresh
     * of imports, the address make was given for the folder of the ontologies to import.
     */
    private static final class Made {
        final String failure;
        final List<String> transcript;
        final File root;
        final String upstream;

        Made(String failure, List<String> transcript, File root) {
            this(failure, transcript, root, null);
        }

        Made(String failure, List<String> transcript, File root, String upstream) {
            this.failure = failure;
            this.transcript = transcript;
            this.root = root;
            this.upstream = upstream;
        }
    }

    // ------------------------------------------------------------------ refreshing imports

    /** Where ODK's make in the container finds the ontologies to import: inside the copy. */
    private static final String UPSTREAM_IN_DOCKER = "file:///work/upstream";

    private static final String PRE_SEED = "src/ontology/tmp/pre_seed.txt";

    /**
     * ODK's seed of {@code configs/localimports.yaml} (id loim) with the recording's work in it
     * ({@link ImportCases#fresh}), and the small ontologies it mirrors moved into it, under
     * {@code project/upstream/}: the copy's project is what ODK's make sees as {@code /work},
     * so they must be inside it to be read there.
     */
    private static final SideBySide.Fixture LOIM = new SideBySide.Fixture() {
        @Override
        public void writeTo(File folder) throws Exception {
            ImportCases.fresh(folder);
            Files.move(new File(folder, "upstream").toPath(),
                    new File(folder, "project/upstream").toPath());
            allOfTheSameAge(new File(folder, "project"));
        }
    };

    private static SideBySide.Row refresh(String task, String target, int runs, File temp,
            File reference) throws Exception {
        return SideBySide.run(task, "loim", runs, new File(temp, target), LOIM,
                refreshedInDocker(target), refreshedHere(target),
                asOdksRefresh(target, reference));
    }

    /** ODK's make in its image, given the upstream folder inside the mounted copy. */
    private static SideBySide.Route<Made> refreshedInDocker(final String target) {
        return new SideBySide.Route<Made>() {
            @Override
            public Made run(File copy) throws Exception {
                File project = new File(copy, "project");
                List<String> line = new ArrayList<String>(Arrays.asList("make", target,
                        "OBOBASE=" + UPSTREAM_IN_DOCKER));
                for (Map.Entry<String, String> variable : AS_RECORDED.entrySet()) {
                    line.add(variable.getKey() + "=" + variable.getValue());
                }
                ProcessRunner.Outcome outcome = ProcessRunner.real().run(null,
                        MakeRun.containerCommandFor(runtime, new File(project, "src/ontology"),
                                image, host, line),
                        MakeRun.TIMEOUT_MINUTES, null);
                return new Made(outcome.isSuccess() ? null : "exit " + outcome.getExitCode()
                        + ": " + outcome.lastMeaningfulLine(), outcome.getOutput(), project,
                        UPSTREAM_IN_DOCKER);
            }
        };
    }

    /** OntoBoard's engine, given the same upstream folder as a file: address. */
    private static SideBySide.Route<Made> refreshedHere(final String target) {
        return new SideBySide.Route<Made>() {
            @Override
            public Made run(File copy) throws Exception {
                File project = new File(copy, "project");
                String upstream = new File(project, "upstream").toURI().toString()
                        .replaceFirst("/$", "");
                Map<String, String> line = new LinkedHashMap<String, String>();
                line.put("OBOBASE", upstream);
                line.putAll(AS_RECORDED);
                OdkEngine.Outcome outcome = OdkEngine.run(
                        new File(project, "src/ontology/loim-edit.owl"), target, line, null);
                if (!outcome.isOk() && !outcome.ranAnything()) {
                    throw new SideBySide.Refused(outcome.getFailure());
                }
                return new Made(outcome.getFailure(), outcome.getTranscript(), project,
                        upstream);
            }
        };
    }

    /**
     * As ImportChainEquivalenceTest holds OntoBoard's refresh against ODK's recorded one: every
     * line make said, in order, with the two upstream addresses written {@code <UPSTREAM>}; the
     * files made or changed - the upstream folder not among them, and the terms collected for
     * the modules counted as changed only when their rows are; each download byte for byte
     * against the upstream file it came from; each mirror and module as the same ontology and
     * the same file; the seed of terms by its lines, the collected terms by their rows in any
     * order; any other file made as the targets' rows compare it.
     */
    private static SideBySide.Comparison<Made> asOdksRefresh(final String target,
            final File reference) {
        return SideBySide.byAssertions(new SideBySide.Check<Made>() {
            @Override
            public void check(Made odks, Made ours) throws Exception {
                keep(target + ".odk.txt", odks.transcript);
                keep(target + ".ontoboard.txt", ours.transcript);
                assertNull(odks.failure, "ODK's make did not finish");
                assertNull(ours.failure, "OntoBoard's run did not finish");
                assertSameLines("every recipe line make said, in the order it ran them",
                        placed(saidByMake(lines(String.join("\n", odks.transcript))),
                                odks.upstream),
                        placed(saidByMake(lines(String.join("\n", ours.transcript))),
                                ours.upstream));
                Set<String> made = refreshed(reference, odks.root);
                assertEquals(made, refreshed(reference, ours.root),
                        "the files the run made or changed");
                for (String path : made) {
                    File theirs = new File(odks.root, path);
                    File here = new File(ours.root, path);
                    if (path.endsWith("-download.owl")) {
                        String id = path.substring(path.lastIndexOf('/') + 1,
                                path.length() - "-download.owl".length());
                        assertArrayEquals(Files.readAllBytes(new File(odks.root,
                                "upstream/" + id + ".owl").toPath()),
                                Files.readAllBytes(theirs.toPath()),
                                path + ": what curl fetched in ODK's image");
                        assertArrayEquals(Files.readAllBytes(new File(ours.root,
                                "upstream/" + id + ".owl").toPath()),
                                Files.readAllBytes(here.toPath()),
                                path + ": what OntoBoard's stand-in for curl fetched");
                    } else if (path.startsWith("src/ontology/mirror/")
                            || path.startsWith("src/ontology/imports/")) {
                        assertSameOntology(path, theirs, here);
                        assertSameFile(path, theirs, here);
                    } else if (path.equals(PRE_SEED)) {
                        assertSameLines(path + ": its rows in any order", sortedLines(theirs),
                                sortedLines(here));
                    } else if (path.endsWith("/seed.txt")) {
                        assertSameLines(path, lines(read(theirs)), lines(read(here)));
                    } else {
                        assertSameMadeFile(path, false, theirs, here);
                    }
                }
            }
        });
    }

    /** make's lines with the address of the upstream folder written {@code <UPSTREAM>}. */
    private static List<String> placed(List<String> said, String upstream) {
        List<String> placed = new ArrayList<String>();
        for (String line : said) {
            placed.add(line.replace(upstream, "<UPSTREAM>"));
        }
        return placed;
    }

    /**
     * What a refresh made or changed in a copy, against the fixture as written: not the
     * upstream folder, not ODK's plugin jars, and the collected terms only when their rows
     * changed (their order is the store's) - for a file that was there before the run.
     */
    private static Set<String> refreshed(File reference, File root) throws Exception {
        Set<String> made = madeSince(hashes(reference), root);
        for (Iterator<String> path = made.iterator(); path.hasNext();) {
            String next = path.next();
            if (next.startsWith(PLUGINS) || next.startsWith("upstream/")) {
                path.remove();
            }
        }
        File before = new File(reference, PRE_SEED);
        if (before.isFile() && made.contains(PRE_SEED)
                && sortedLines(before).equals(sortedLines(new File(root, PRE_SEED)))) {
            made.remove(PRE_SEED);
        }
        return made;
    }

    private static List<String> sortedLines(File file) throws Exception {
        List<String> sorted = new ArrayList<String>(lines(read(file)));
        java.util.Collections.sort(sorted);
        return sorted;
    }

    private static SideBySide.Row make(String task, String target, int runs, File temp,
            SideBySide.Fixture fixture, Map<String, String> before) throws Exception {
        return SideBySide.run(task, "egg", runs, new File(temp, target), fixture,
                madeInDocker(target), madeHere(target), asOdksMake(target, before));
    }

    /** ODK's make in ODK's image, mounted as OntoBoard mounts a project for a build. */
    private static SideBySide.Route<Made> madeInDocker(final String target) {
        return new SideBySide.Route<Made>() {
            @Override
            public Made run(File project) throws Exception {
                List<String> line = new ArrayList<String>(Arrays.asList("make", target));
                for (Map.Entry<String, String> variable : AS_RECORDED.entrySet()) {
                    line.add(variable.getKey() + "=" + variable.getValue());
                }
                ProcessRunner.Outcome outcome = ProcessRunner.real().run(null,
                        MakeRun.containerCommandFor(runtime, new File(project, "src/ontology"),
                                image, host, line),
                        MakeRun.TIMEOUT_MINUTES, null);
                return new Made(outcome.isSuccess() ? null : "exit " + outcome.getExitCode()
                        + ": " + outcome.lastMeaningfulLine(), outcome.getOutput(), project);
            }
        };
    }

    /** OntoBoard's engine; a target it does not run is refused, with its reason. */
    private static SideBySide.Route<Made> madeHere(final String target) {
        return new SideBySide.Route<Made>() {
            @Override
            public Made run(File project) throws Exception {
                OdkEngine.Outcome outcome = OdkEngine.run(new File(project, EDIT), target,
                        AS_RECORDED, null);
                if (!outcome.isOk() && !outcome.ranAnything()) {
                    throw new SideBySide.Refused(outcome.getFailure());
                }
                return new Made(outcome.getFailure(), outcome.getTranscript(), project);
            }
        };
    }

    /**
     * As FallbackEquivalenceTest holds OntoBoard's run against ODK's recorded one: every line
     * make said, in order; what ROBOT said of each check; for a test, what ROBOT's report said
     * and the recipe's last word; the files the run made; and each of those files - a table by
     * its header and rows, the profile's verdict by its text, an OBO product and a release's
     * products as {@link RunComparisons#assertSameProduct}, any other ontology by what it says,
     * anything else byte for byte. For a clean, what is left on disk.
     */
    private static SideBySide.Comparison<Made> asOdksMake(final String target,
            final Map<String, String> before) {
        final boolean test = target.startsWith("test");
        final boolean release = target.startsWith("prepare_release");
        return SideBySide.byAssertions(new SideBySide.Check<Made>() {
            @Override
            public void check(Made odks, Made ours) throws Exception {
                keep(target + ".odk.txt", odks.transcript);
                keep(target + ".ontoboard.txt", ours.transcript);
                assertNull(odks.failure, "ODK's make did not finish");
                assertNull(ours.failure, "OntoBoard's run did not finish");
                if (target.equals("clean")) {
                    Goldens.assertTreeEquals(Goldens.tree(odks.root), Goldens.tree(ours.root));
                    return;
                }
                // Line by line, as FallbackEquivalenceTest reads both: the engine keeps a recipe
                // line that goes on over several lines as one entry, the process's output is
                // read a line at a time.
                List<String> theirs = lines(String.join("\n", odks.transcript));
                List<String> here = lines(String.join("\n", ours.transcript));
                assertSameLines("every recipe line make said, in the order it ran them",
                        saidByMake(theirs), saidByMake(here));
                assertSameLines("what ROBOT said of each check", starting("PASS Rule ", theirs),
                        starting("PASS Rule ", here));
                if (test) {
                    assertSameLines("what ROBOT's report said: of its rules, then the counts and "
                            + "the first findings", saidByTheReport(theirs),
                            saidByTheReport(here));
                    assertEquals(beforeLast(theirs), beforeLast(here),
                            "the recipe's own last word, before make leaves the folder");
                }
                Set<String> made = madeSince(before, odks.root);
                for (Iterator<String> path = made.iterator(); path.hasNext();) {
                    if (path.next().startsWith(PLUGINS)) {
                        path.remove();
                    }
                }
                assertEquals(made, madeSince(before, ours.root), "the files the run made");
                for (String path : made) {
                    assertSameMadeFile(path, release, new File(odks.root, path),
                            new File(ours.root, path));
                }
            }
        });
    }

    private static void assertSameMadeFile(String path, boolean release, File odks, File ours)
            throws Exception {
        if (path.endsWith(".tsv")) {
            String theirs = read(odks);
            String here = read(ours);
            assertSameLines(path + ": its header, and its rows in any order", rows(theirs),
                    rows(here));
            assertEquals(theirs.contains("\r"), here.contains("\r"),
                    path + ": lines end as in ODK's table");
        } else if (path.endsWith(".txt")) {
            assertEquals(read(odks).trim(), read(ours).trim(), path);
        } else if (path.endsWith(".obo") || release && (path.indexOf('/') < 0
                || path.equals("src/ontology/tmp/validate.ofn"))) {
            assertSameProduct(path, odks, ours);
        } else if (path.endsWith(".owl") || path.endsWith(".ofn")) {
            assertSameOntology(path, odks, ours);
        } else {
            assertArrayEquals(Files.readAllBytes(odks.toPath()), Files.readAllBytes(ours.toPath()),
                    path + ", byte for byte");
        }
    }

    /** Fails at the first line in which the two differ, naming it and both readings. */
    private static void assertSameLines(String what, List<String> odks, List<String> ours) {
        for (int at = 0; at < Math.max(odks.size(), ours.size()); at++) {
            String theirs = at < odks.size() ? odks.get(at) : null;
            String here = at < ours.size() ? ours.get(at) : null;
            if (theirs == null || !theirs.equals(here)) {
                fail(what + ": line " + (at + 1) + " of " + odks.size() + " (ODK's) and "
                        + ours.size() + " (OntoBoard's) - ODK's "
                        + (theirs == null ? "has none" : "[" + theirs + "]") + ", OntoBoard's "
                        + (here == null ? "has none" : "[" + here + "]"));
            }
        }
    }

    private static String beforeLast(List<String> transcript) {
        return transcript.size() < 2 ? "" : transcript.get(transcript.size() - 2);
    }

    /** Kept under target/side-by-side/transcripts, for a person to read after a run. */
    private static void keep(String name, List<String> lines) throws Exception {
        File folder = new File(OUT, "transcripts");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the machine

    private static SideBySide.Machine machine() throws Exception {
        String robotInImage = first(runtime, "run", "--rm", image, "robot", "--version");
        return new SideBySide.Machine(
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"),
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor")
                        + ")",
                first(runtime, "version", "--format", "{{.Server.Version}}"), image,
                first(runtime, "image", "inspect", "--format", "{{.Id}}", image),
                VersionInfo.getVersionInfo().getVersion(), LocalDate.now().toString(),
                OdkScaffold.ROBOT_VERSION,
                robotInImage.replaceFirst("(?i)^ROBOT version ", ""));
    }

    /** The first line a command printed, or what went wrong. */
    private static String first(String... command) {
        try {
            ProcessRunner.Outcome outcome = ProcessRunner.real().run(null,
                    Arrays.asList(command), 5, null);
            for (String line : outcome.getOutput()) {
                if (!line.trim().isEmpty() && !line.startsWith("WARNING")) {
                    return line.trim();
                }
            }
            return "(nothing said, exit " + outcome.getExitCode() + ")";
        } catch (Exception failed) {
            return "(not known: " + failed.getMessage() + ")";
        }
    }
}
