package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 1.155.0: what the release records of itself, beside its copy.
        expected.add("releases/1.0.0/RELEASE.md");
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
        assertTrue(gitRan.contains(Arrays.asList("git", "tag", "-a", "v1.0.0",
                "--cleanup=verbatim", "-F", "releases/1.0.0/RELEASE.md")), gitRan.toString());
    }

    /**
     * F1, to the end: the project OntoBoard 1.135.2 made, carried over - the conversion, then
     * ODK's update as OntoBoard itself makes it - and released by the engine says one version,
     * its own. Until 1.154.0 it said 1.135's "0.1.0" beside it.
     */
    @Test
    void aCarriedOverProjectsReleaseSaysOneVersion(@TempDir File where) throws Exception {
        File carried = carriedOver(RecordedProject.of1352(new File(where, "egg")));
        Map<String, String> version = new LinkedHashMap<String, String>();
        version.put("VERSION", "0.2.0");

        OdkEngine.Outcome done = OdkEngine.run(carried, "prepare_release_fast", version, null);

        assertTrue(done.isOk(), done.getFailure());
        assertEquals(Collections.singletonList("0.2.0"), RunComparisons.versionInfos(
                new File(carried.getParentFile(), "../../eggboil.owl")));
    }

    /** The conversion, then ODK's update as OntoBoard itself makes it; the edit file. */
    private static File carriedOver(File editFile) throws IOException {
        File project = editFile.getAbsoluteFile().getParentFile().getParentFile()
                .getParentFile();
        OdkMigration.convert(project, OdkMigration.plan(project));
        OdkUpdate.apply(project, OdkUpdate.plan(project));
        return editFile;
    }

    /**
     * ODK's test builds the ontology it validates - the file a release puts at the top of the
     * project - and stamps it with the day. A release made afterwards must not take that file
     * for its own: a release cleans first, which takes that file away. Found in 1.153.0's
     * own work: a release numbered 1.0.0, made after a test, carried
     * the day as its version.
     */
    @Test
    void aReleaseAfterATestCarriesItsOwnVersionNotTheDays() throws Exception {
        OdkEngine.Outcome tested = OdkEngine.run(edit, "test_fast",
                new java.util.LinkedHashMap<String, String>(), null);
        assertTrue(tested.isOk(), tested.getFailure());
        File validated = new File(edit.getParentFile(), "eggboil.owl");
        assertTrue(validated.isFile(), "ODK's test has built the ontology it validates");
        assertTrue(!versionIriOf(validated).contains("/releases/1.0.0/"),
                "under the day's version: " + versionIriOf(validated));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        String log = String.join("\n", outcome.getTranscript());
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + log);
        for (String product : Arrays.asList("eggboil.owl", "eggboil-base.owl",
                "eggboil-full.owl")) {
            assertEquals("https://example.org/eggboil/eggboil/releases/1.0.0/" + product,
                    versionIriOf(new File(root, product)), product);
        }
        assertTrue(log.contains("$ make prepare_release_fast VERSION=1.0.0"),
                log);
        assertTrue(log.contains("$ make clean"), log);
    }

    /**
     * Seen live (1.153.0), in the guide's own order: a pattern imported, then Release... -
     * "ERROR: You have configured ... as a custom module; This rule needs to be overwritten".
     * A module OntoBoard keeps as it is - a pattern, a MIREOT import, a file of this machine -
     * is declared custom, and ODK's rule for a custom module is an error that says to write
     * one's own. make leaves that rule alone while the module is there; a release made with
     * {@code make -B}, which makes everything again, ran it. A release cleans first instead,
     * as the release scripts of real projects do, and makes what is missing.
     */
    @Test
    void aProjectWithAModuleKeptAsItIsIsReleased(@TempDir File where) throws Exception {
        File frozen = new File(where, "froz");
        for (java.util.Map.Entry<String, byte[]> file
                : de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens.seedTree("frozen").entrySet()) {
            File target = new File(frozen, file.getKey());
            assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
            java.nio.file.Files.write(target.toPath(), file.getValue());
        }
        assertTrue(new File(frozen, ".git").mkdir(), "a repository");
        File frozenEdit = new File(frozen, "src/ontology/froz-edit.owl");
        assertTrue(frozenEdit.isFile());

        for (boolean refreshImports : new boolean[] {false, true}) {
            OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(frozenEdit,
                    refreshImports ? "1.1.0" : "1.0.0",
                    VersionIriShape.of(VersionIriShape.Kind.ODK), refreshImports, true),
                    OdkRelease.byOntoBoardItself(frozenEdit, null), git(), new OdkRelease.Asks() {
                        @Override
                        public boolean commit(ReleaseRun.Staging staging) {
                            return true;
                        }
                    });

            String log = String.join("\n", outcome.getTranscript());
            assertTrue(!log.contains("as a custom module"), log);
            assertTrue(log.contains("$ make clean"), log);
            if (refreshImports) {
                // The recorded project also has a module with a source on the web, at an
                // address made up for it: the release gets as far as fetching that - and since
                // Task 19 the failure names the import and its address, not make's target.
                assertTrue(outcome.isOk() || outcome.getFailure().contains("import handmade"),
                        outcome.getFailure() + "\n" + log);
            } else {
                assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + log);
            }
        }
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

    /**
     * A release that does not build is not recorded, and the reason is ROBOT's own word.
     *
     * <p>Two classes each the other's subclass: consistent, and every class can have members, so
     * the release check lets it through (1.155.0) - but ODK reasons with
     * {@code --equivalent-classes-allowed asserted-only}, and the equivalence is inferred.
     */
    @Test
    void aReleaseThatDoesNotBuildIsNotRecordedAndSaysWhy() throws Exception {
        String text = new String(Files.readAllBytes(edit.toPath()), StandardCharsets.UTF_8);
        int end = text.lastIndexOf(')');
        Files.write(edit.toPath(), (text.substring(0, end)
                + "Declaration(Class(<https://example.org/eggboil/EGGBOIL_9999998>))\n"
                + "Declaration(Class(<https://example.org/eggboil/EGGBOIL_9999999>))\n"
                + "SubClassOf(<https://example.org/eggboil/EGGBOIL_9999999> "
                + "<https://example.org/eggboil/EGGBOIL_9999998>)\n"
                + "SubClassOf(<https://example.org/eggboil/EGGBOIL_9999998> "
                + "<https://example.org/eggboil/EGGBOIL_9999999>)\n" + text.substring(end))
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertFalse(outcome.isOk());
        assertEquals(ReleaseVerdict.Kind.CLEAN, outcome.getVerdict().kind(),
                "the check found nothing; the build did");
        assertTrue(outcome.getFailure().contains("Nothing was copied, committed or tagged"),
                outcome.getFailure());
        assertTrue(String.join("\n", outcome.getTranscript()).contains("EGGBOIL_9999999"),
                "which classes are inferred equivalent is in the log: " + outcome.getTranscript());
        assertFalse(new File(root, "eggboil.owl").exists());
        assertEquals(new ArrayList<List<String>>(), gitRan);
    }

    /**
     * A class that can have no members stops the release before anything is built (D1), with the
     * axioms that cause it - on OntoBoard's own engine, the route nothing is installed for.
     */
    @Test
    void aClassWithNoMembersStopsTheReleaseBeforeTheBuild() throws Exception {
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
        assertEquals(ReleaseVerdict.Kind.UNSATISFIABLE, outcome.getVerdict().kind());
        assertTrue(outcome.getFailure().contains("Nothing was built, copied, committed or tagged"),
                outcome.getFailure());
        assertTrue(outcome.getFailure().contains("EGGBOIL_9999999"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("DisjointWith"), outcome.getFailure());
        assertFalse(new File(root, "eggboil.owl").exists());
        assertEquals(new ArrayList<List<String>>(), gitRan);
    }

    /**
     * A run the person stopped comes back as stopped, as a process that was ended does:
     * whoever reads the outcome says "you stopped it", not "make failed".
     */
    @Test
    void aRunThatWasStoppedComesBackAsStopped() throws Exception {
        ProcessRunner.Outcome ran = OdkRelease.byOntoBoardItself(edit, () -> true)
                .run(Arrays.asList("make", "test_fast"), false);

        assertFalse(ran.isSuccess());
        assertTrue(ran.wasCancelled(), String.join("\n", ran.getOutput()));
        assertTrue(MakeRun.explain("test_fast", ran).startsWith("You stopped the build"),
                MakeRun.explain("test_fast", ran));
    }

    /**
     * Ruling 9 of 1.154.0: a release made by make runs the project's own rules - here one of
     * theirs for the base, which writes a note into it. The released base carries the note.
     */
    @Test
    void aReleaseByMakeRunsTheProjectsOwnRules() throws Exception {
        Files.write(new File(edit.getParentFile(), "eggboil.Makefile").toPath(), ("## mine\n"
                + "$(ONT)-base.owl: $(EDIT_PREPROCESSED)\n"
                + "\t$(ROBOT) annotate --input $< --annotation rdfs:comment \"built by a rule "
                + "of the project\" --ontology-iri $(ONTBASE)/$@ "
                + "$(ANNOTATE_ONTOLOGY_VERSION) --output $@\n").getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertTrue(outcome.isOk(), outcome.getFailure() + "\n"
                + String.join("\n", outcome.getTranscript()));
        String base = new String(Files.readAllBytes(new File(root, "eggboil-base.owl").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(base.contains("built by a rule of the project"),
                String.join("\n", outcome.getTranscript()));
    }

    /** A project's own release.sh stays the container's to run, as in 1.153.0. */
    @Test
    void aProjectWithItsOwnReleaseScriptIsStillNotReleasedByTheEngine() throws Exception {
        Files.write(new File(edit.getParentFile(), "release.sh").toPath(),
                "#!/bin/sh\nmake prepare_release\n".getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertFalse(outcome.isOk());
        String said = outcome.getFailure() + "\n" + String.join("\n", outcome.getTranscript());
        assertTrue(said.contains("release.sh"), said);
        assertFalse(new File(root, "eggboil.owl").exists());
        assertEquals(new ArrayList<List<String>>(), gitRan);
    }

    /**
     * A project's own release script is read and run as a script, line by line - not handed to
     * make as a target (1.155.0: before, OntoBoard's engine refused every script).
     */
    @Test
    void aScriptOfTheProjectsOwnIsRunAsAScriptNotAsATarget() throws Exception {
        Files.write(new File(edit.getParentFile(), "release.sh").toPath(),
                "VERSION=1.0.0\nsh run.sh make clean\n".getBytes(StandardCharsets.UTF_8));

        ProcessRunner.Outcome ran = OdkRelease.byOntoBoardItself(edit, null)
                .run(Arrays.asList("sh", "release.sh"), true);

        String said = String.join("\n", ran.getOutput());
        assertTrue(ran.isSuccess(), said);
        assertTrue(said.contains("line 2: make clean"), said);
        assertFalse(said.contains("no target of make"), said);
    }

    /**
     * What OntoBoard itself does not build - here a Makefile somebody changed by hand, which is
     * no longer ODK's - is not built, and the release says why: the engine's own reason, since
     * nothing was run that could have said anything.
     */
    @Test
    void anEditedMakefileIsReleasedAsItIsAndTheLogSaysSo() throws Exception {
        File makefile = new File(edit.getParentFile(), "Makefile");
        Files.write(makefile.toPath(), (new String(Files.readAllBytes(makefile.toPath()),
                StandardCharsets.UTF_8) + "\n# a line of their own\n")
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        // 1.155.0: as make in ODK's container runs it - the difference said before the run,
        // once in the release, not again for each call of make (clean, the build, the files).
        assertTrue(outcome.isOk(), outcome.getFailure());
        String log = String.join("\n", outcome.getTranscript());
        String notice = "This project's Makefile is not the one ODK";
        assertTrue(log.contains(notice), "how the Makefile differs is in the log: " + log);
        assertEquals(log.indexOf(notice), log.lastIndexOf(notice), "said once: " + log);
        assertTrue(new File(root, "eggboil.owl").exists());
    }

    /**
     * A script calls make line after line. How the Makefile differs from ODK's is said once,
     * before the script's first call of make - not before each (1.155.0: nfdicore's release
     * said it four times, and MWO's three).
     */
    @Test
    void howTheMakefileDiffersIsSaidOnceInAScriptsRun() throws Exception {
        File makefile = new File(edit.getParentFile(), "Makefile");
        Files.write(makefile.toPath(), (new String(Files.readAllBytes(makefile.toPath()),
                StandardCharsets.UTF_8) + "\n# a line of their own\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(edit.getParentFile(), "release.sh").toPath(), ("VERSION=1.0.0\n"
                + "sh run.sh make clean\n"
                + "sh run.sh make VERSION=$VERSION prepare_release_fast\n")
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertTrue(outcome.isOk(), outcome.getFailure());
        String log = String.join("\n", outcome.getTranscript());
        String notice = "This project's Makefile is not the one ODK";
        int first = log.indexOf(notice);
        assertTrue(first >= 0, log);
        assertEquals(first, log.lastIndexOf(notice), "said once: " + log);
        assertTrue(log.contains("line 3: make VERSION=1.0.0 prepare_release_fast"), log);
    }

    /**
     * The reasoner the person chose reaches ROBOT on OntoBoard's own engine too: make takes
     * {@code REASONER=} from its command line over the Makefile's line, and so must the engine
     * (1.155.0).
     */
    @Test
    void theEngineReasonsWithTheReasonerGiven() throws Exception {
        OdkRelease.Outcome outcome = OdkRelease.run(
                new OdkRelease.Request(edit, "1.0.0", null, false, true)
                        .withReasoner("HermiT", ReleaseVerdict.DEFAULT_LIMIT_MILLIS),
                OdkRelease.byOntoBoardItself(edit, null), git(), new OdkRelease.Asks() {
                    @Override
                    public boolean commit(ReleaseRun.Staging staging) {
                        return true;
                    }
                });

        assertTrue(outcome.isOk(), outcome.getFailure());
        String transcript = String.join("\n", outcome.getTranscript());
        assertTrue(transcript.contains("--reasoner HermiT"), transcript);
        assertFalse(transcript.contains("--reasoner ELK"), transcript);
    }

    /**
     * A project's own release script, run by OntoBoard itself: its variables, its call of make
     * through run.sh, and a copy - each line said as it starts (1.155.0; nfdicore and MWO are
     * released by such scripts).
     */
    @Test
    void aProjectsOwnScriptIsRunByOntoBoardItself() throws Exception {
        Files.write(new File(edit.getParentFile(), "release.sh").toPath(), ("set -e\r\n"
                + "VERSION=1.0.0\r\n"
                + "sh run.sh make VERSION=$VERSION prepare_release_fast\r\n"
                + "cp ../../eggboil.owl ../../eggboil-as-released.owl\r\n")
                .getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertTrue(outcome.isOk(), outcome.getFailure());
        String log = String.join("\n", outcome.getTranscript());
        assertTrue(log.contains("line 3: make VERSION=1.0.0 prepare_release_fast"), log);
        assertTrue(log.contains("line 4: cp ../../eggboil.owl ../../eggboil-as-released.owl"),
                log);
        assertTrue(new File(root, "eggboil-as-released.owl").isFile());
        assertTrue(versionIriOf(new File(root, "eggboil.owl")).contains("1.0.0"));
    }

    @Test
    void aScriptLineOntoBoardCannotRunStopsTheReleaseBeforeAnythingRuns() throws Exception {
        Files.write(new File(edit.getParentFile(), "release.sh").toPath(), ("VERSION=1.0.0\n"
                + "sh run.sh make VERSION=$VERSION prepare_release_fast\n"
                + "git push origin main\n").getBytes(StandardCharsets.UTF_8));

        OdkRelease.Outcome outcome = release(VersionIriShape.of(VersionIriShape.Kind.ODK));

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Line 3"), outcome.getFailure());
        assertFalse(new File(root, "eggboil.owl").exists(), "nothing was built");
    }

    @Test
    void whetherOntoBoardCanRunAScriptIsAskedBeforeTheRelease() throws Exception {
        File script = new File(edit.getParentFile(), "release.sh");
        Files.write(script.toPath(), ("VERSION=1.0.0\n"
                + "sh run.sh make VERSION=$VERSION prepare_release_fast\n")
                .getBytes(StandardCharsets.UTF_8));
        assertNull(ProjectScript.whyNotOnTheEngine(edit, script));

        Files.write(script.toPath(), ("VERSION=1.0.0\nsh run.sh make no_such_target\n")
                .getBytes(StandardCharsets.UTF_8));
        String why = ProjectScript.whyNotOnTheEngine(edit, script);
        assertTrue(why != null && why.contains("Line 2"), why);
    }
}
