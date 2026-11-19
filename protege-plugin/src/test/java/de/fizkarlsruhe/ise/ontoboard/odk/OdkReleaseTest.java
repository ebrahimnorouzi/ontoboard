package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release of an ODK project, from the version chosen to the tag.
 *
 * <p>ODK builds the release - {@code prepare_release} in its container, or the project's own
 * release script - and OntoBoard does what a maintainer does around it: refuses a version that
 * exists, holds the new release against the previous one, keeps the copy under
 * {@code releases/<version>/} for projects it made, and shows the git commands before it runs
 * them. Here ODK and git are stand-ins that do what the real ones do to the folder;
 * {@code ReleaseLiveTest} runs the real ones.
 */
class OdkReleaseTest {

    @TempDir
    File temp;

    private File root;
    private File ontology;
    private File edit;

    /** What ODK was asked to run, and what git was. */
    private final List<List<String>> odkRan = new ArrayList<List<String>>();
    private final List<Boolean> withPassThrough = new ArrayList<Boolean>();
    private final List<List<String>> gitRan = new ArrayList<List<String>>();
    private final List<ReleaseRun.Staging> asked = new ArrayList<ReleaseRun.Staging>();

    private String released = owl("Egg", "Pan");
    private boolean buildFails;
    /** What a build that fails says, where a test has something particular for it to say. */
    private List<String> failsSaying;
    private boolean stoppedAfterTheBuild;
    private boolean gitInstalled = true;
    private boolean commitFails;
    private boolean tagFails;
    private boolean conflict;
    private boolean sayYes = true;
    private List<String> tags = new ArrayList<String>();

    private static String owl(String... classes) {
        StringBuilder text = new StringBuilder("Prefix(:=<https://example.org/eggboil/>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<https://example.org/eggboil/eggboil.owl>\n");
        for (String name : classes) {
            text.append("Declaration(Class(:").append(name).append("))\n")
                    .append("AnnotationAssertion(rdfs:label :").append(name).append(" \"")
                    .append(name.toLowerCase()).append("\")\n");
        }
        return text.append(")\n").toString();
    }

    private static String text(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void aProjectOntoBoardMade() throws IOException {
        root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        assertTrue(new File(root, ".git").mkdir(), "a repository, as Create leaves it");
        ontology = new File(root, "src/ontology");
        edit = new File(ontology, "eggboil-edit.owl");
    }

    /** ODK: writes the release files at the project's root, and says which they are. */
    private OdkRelease.Odk odk() {
        return new OdkRelease.Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> inContainer, boolean passThroughRunSh)
                    throws IOException {
                odkRan.add(inContainer);
                withPassThrough.add(passThroughRunSh);
                if (inContainer.contains("show_release_assets")) {
                    return new ProcessRunner.Outcome(0, Arrays.asList(
                            "../../eggboil.obo ../../eggboil.owl ../../eggboil-base.owl"), false);
                }
                if (buildFails && !inContainer.contains("clean")) {
                    return new ProcessRunner.Outcome(2, failsSaying != null ? failsSaying
                            : Arrays.asList("robot reason ...",
                                    "make: *** [Makefile:124: reason_test] Error 1"), false);
                }
                write(new File(root, "eggboil.owl"), released);
                write(new File(root, "eggboil.obo"), "format-version: 1.2\n");
                write(new File(root, "eggboil-base.owl"), released);
                return new ProcessRunner.Outcome(0, Arrays.asList("robot merge ...",
                        "Release files are now in ../.."), false);
            }

            @Override
            public String shown(List<String> inContainer, boolean passThroughRunSh) {
                return "docker run --rm ... " + String.join(" ", inContainer);
            }
        };
    }

    /** git: answers what it is asked, and remembers the commands that change something. */
    private ProcessRunner.Runner git() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (!gitInstalled || !"git".equals(command.get(0))) {
                    throw new IOException("Cannot run program \"" + command.get(0) + "\"");
                }
                if (command.contains("--version")) {
                    return ok("git version 2.47.0");
                }
                if (command.contains("--list")) {
                    return ok(tags.toArray(new String[0]));
                }
                if (command.contains("status")) {
                    return ok("## main...origin/main", "?? eggboil.owl", "?? notes.txt",
                            (conflict ? "UU" : " M") + " src/ontology/eggboil-edit.owl");
                }
                gitRan.add(command);
                if (commitFails && command.contains("commit")) {
                    return new ProcessRunner.Outcome(1, Arrays.asList(
                            "error: gpg failed to sign the data"), false);
                }
                if (tagFails && command.contains("tag")) {
                    return new ProcessRunner.Outcome(128, Arrays.asList(
                            "fatal: no tag message?"), false);
                }
                return ok();
            }
        };
    }

    private static ProcessRunner.Outcome ok(String... lines) {
        return new ProcessRunner.Outcome(0, Arrays.asList(lines), false);
    }

    private OdkRelease.Outcome release(String version, VersionIriShape shape, boolean refresh) {
        return OdkRelease.run(new OdkRelease.Request(edit, version, shape, refresh, true), odk(),
                git(), new OdkRelease.Asks() {
                    @Override
                    public boolean commit(ReleaseRun.Staging staging) {
                        asked.add(staging);
                        return sayYes;
                    }

                    @Override
                    public boolean stopped() {
                        return stoppedAfterTheBuild && !odkRan.isEmpty();
                    }
                });
    }

    private OdkRelease.Outcome release(String version) {
        return release(version, VersionIriShape.of(VersionIriShape.Kind.ODK), true);
    }

    // ------------------------------------------------------------------ the whole of it

    /** What git says in {@code folder}, line by line. */
    private static List<String> gitSays(File folder, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Arrays.asList("git"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(folder, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
        return outcome.getOutput();
    }

    /**
     * After an import - a new module and its term list that git has never been told about -
     * the release commits them first, in a commit of their own, and then itself: the tag holds
     * the module, and the project builds for whoever fetches it. With git itself.
     */
    @Test
    void afterAnImportTheReleasesTagHoldsTheModule() throws Exception {
        ReleaseHistoryTest.assumeGit();
        Files.delete(new File(root, ".git").toPath());
        ReleaseHistoryTest.git(root, "init", "-q");
        for (String[] setting : new String[][] {{"user.name", "Test"},
            {"user.email", "test@example.org"}, {"commit.gpgsign", "false"},
            {"tag.gpgsign", "false"}}) {
            ReleaseHistoryTest.git(root, "config", setting[0], setting[1]);
        }
        ReleaseHistoryTest.git(root, "add", "-A");
        ReleaseHistoryTest.git(root, "commit", "-q", "-m", "the project");
        write(new File(ontology, "imports/egg_import.owl"), owl("Spoon"));
        write(new File(ontology, "imports/egg_terms.txt"), "https://example.org/eggboil/Spoon\n");

        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0", null,
                true, true, false, true), odk(), ProcessRunner.real(), new OdkRelease.Asks() {
                    @Override
                    public boolean commit(ReleaseRun.Staging staging) {
                        asked.add(staging);
                        return true;
                    }

                    @Override
                    public boolean stopped() {
                        return false;
                    }
                });

        assertTrue(outcome.isOk() && outcome.isCommitted(), outcome.getSummary());
        assertEquals(Arrays.asList("release(1.0.0)", "Files of the project before release 1.0.0",
                "the project"), gitSays(root, "log", "--format=%s"));
        List<String> tagged = gitSays(root, "ls-tree", "-r", "--name-only", "v1.0.0");
        assertTrue(tagged.contains("src/ontology/imports/egg_import.owl"), tagged.toString());
        assertTrue(tagged.contains("src/ontology/imports/egg_terms.txt"), tagged.toString());
        assertTrue(outcome.getSummary().contains("after the project's new folder in a commit of "
                + "its own (\"Files of the project before release 1.0.0\")"),
                "git lists the new imports folder whole: " + outcome.getSummary());
    }

    @Test
    void aReleaseIsBuiltKeptAndRecorded() {
        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("make", "clean"), odkRan.get(0),
                "what a test left behind is not taken for the release's own");
        assertEquals(Arrays.asList("make", "prepare_release", "VERSION=1.0.0"), odkRan.get(1));
        assertEquals(Arrays.asList("make", "-s", "show_release_assets"), odkRan.get(2));
        assertEquals(Arrays.asList(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE),
                withPassThrough);
        assertTrue(new File(root, "releases/1.0.0/eggboil.owl").isFile());
        assertTrue(new File(root, "releases/1.0.0/eggboil-base.owl").isFile());
        assertFalse(new File(root, "releases/1.0.0/CHANGES.md").exists(), "a first release has "
                + "nothing to have changed from");
        assertEquals(Arrays.asList(
                Arrays.asList("git", "add", "-u", "--", "."),
                Arrays.asList("git", "add", "-f", "--", "eggboil.obo", "eggboil.owl",
                        "eggboil-base.owl"),
                Arrays.asList("git", "add", "--", "releases/1.0.0"),
                Arrays.asList("git", "commit", "-m", "release(1.0.0)"),
                Arrays.asList("git", "tag", "-a", "v1.0.0", "--cleanup=verbatim", "-F",
                        "releases/1.0.0/RELEASE.md")),
                gitRan);
        assertTrue(outcome.isCommitted());
        assertEquals("v1.0.0", outcome.getTag());
        // 1.155.0: what the release records of itself, kept with it and the tag's message.
        String record = text(new File(root, "releases/1.0.0/RELEASE.md"));
        assertTrue(record.startsWith("# eggboil 1.0.0\n"), record);
        assertTrue(record.contains("- **Reasoner:** HermiT"), record);
        assertTrue(record.contains("## How it was checked"), record);
        assertEquals(1, asked.size(), "the commands were shown once, before they ran");
        assertEquals(Collections.singletonList("notes.txt"), asked.get(0).leftOut());
        assertTrue(outcome.getSummary().contains("1.0.0") && outcome.getSummary().contains("v1.0.0"),
                outcome.getSummary());
        assertTrue(outcome.getSummary().contains("git push --follow-tags"), outcome.getSummary());
        assertTrue(String.join("\n", outcome.getTranscript())
                .contains("docker run --rm ... make prepare_release VERSION=1.0.0"));
    }

    /**
     * With ODK's own tools installed on the machine (ODK Core's native environment), ODK's
     * commands run in a shell, after the environment's activation script. Each word is quoted
     * for that shell: make's {@code $@} in a version IRI's pattern is for make to read.
     */
    @Test
    void inANativeEnvironmentTheCommandsRunAfterTheActivationScript() throws Exception {
        final List<List<String>> ran = new ArrayList<List<String>>();
        final List<File> where = new ArrayList<File>();
        ProcessRunner.Runner shell = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                ran.add(command);
                where.add(workingDirectory);
                return ok("made");
            }
        };
        File script = new File(temp, "odk env/bin/activate-odk-environment.sh");
        List<String> make = Arrays.asList("make", "prepare_release", "VERSION=1.0.0",
                "ANNOTATE_ONTOLOGY_VERSION=annotate -V https://example.org/eggboil/eggboil/"
                        + "1.0.0/$@ --annotation owl:versionInfo 1.0.0");

        OdkRelease.Odk odk = OdkRelease.inNativeEnvironment(script, ontology, shell, null);
        ProcessRunner.Outcome outcome = odk.run(make, false);

        assertTrue(outcome.isSuccess());
        assertEquals(ontology, where.get(0), "where make is to run");
        assertEquals(Arrays.asList("sh", "-c"), ran.get(0).subList(0, 2));
        String line = ran.get(0).get(2);
        assertTrue(line.startsWith(". '" + script.getAbsolutePath() + "' && "), line);
        assertTrue(line.endsWith(" && 'make' 'prepare_release' 'VERSION=1.0.0' "
                + "'ANNOTATE_ONTOLOGY_VERSION=annotate -V https://example.org/eggboil/eggboil/"
                + "1.0.0/$@ --annotation owl:versionInfo 1.0.0'"), line);
        String shown = odk.shown(make, false);
        assertTrue(shown.contains("make prepare_release VERSION=1.0.0"), shown);
        assertTrue(shown.contains("native ODK environment"), shown);
    }

    @Test
    void theShapeChosenAndTheFastBuildReachMake() {
        release("1.0.0", VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE), false);

        assertEquals(Arrays.asList("make", "prepare_release_fast", "VERSION=1.0.0",
                "ANNOTATE_ONTOLOGY_VERSION=annotate -V https://example.org/eggboil/eggboil/1.0.0/$@"
                        + " --annotation owl:versionInfo 1.0.0"), odkRan.get(1));
    }

    // ------------------------------------------------------------------ what stops it

    @Test
    void aVersionThatHasBeenReleasedIsRefusedBeforeAnythingRuns() {
        tags = Arrays.asList("v1.0.0");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("v1.0.0"), outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
    }

    @Test
    void aBuildThatFailsStopsThereWithItsTranscript() {
        buildFails = true;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("prepare_release"), outcome.getFailure());
        assertTrue(String.join("\n", outcome.getTranscript()).contains("reason_test] Error 1"));
        assertEquals(2, odkRan.size(), "clean and the build: nothing is asked of a build "
                + "that failed");
        assertFalse(new File(root, "releases").exists());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertEquals(0, asked.size());
    }

    /**
     * A term that is gone rather than obsoleted breaks every ontology that imported it. The
     * release files are built by then - ODK built them - but nothing is copied, committed or
     * tagged, and the failure names the term.
     */
    @Test
    void aTermThePreviousReleaseHadStopsTheCommit() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), owl("Egg", "Pan", "Timer"));

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Timer"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("0.9.0"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("owl:deprecated"), outcome.getFailure());
        // ODK's prepare_release has put its files at the top by then: that is said, and
        // "nothing was copied" is said of releases/ only (review of 1.153.0).
        assertTrue(outcome.getFailure().contains("at the top of the project"),
                outcome.getFailure());
        assertTrue(outcome.getFailure().contains("nothing was copied to releases/"),
                outcome.getFailure());
        assertFalse(new File(root, "releases/1.0.0").exists());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertEquals(0, asked.size());
    }

    /** A last release that no reader takes for an ontology. */
    private static final String UNREADABLE = "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf="
            + "\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<owl:Ontology rdf:about=";

    /**
     * F6: a last release that cannot be read leaves the new one uncompared, and the comparison
     * is the last check before the release is a commit somebody pulls - so it stops there, as
     * for a term that is gone, and says how to go on.
     */
    @Test
    void aReleaseThatCannotBeComparedWithTheLastStopsBeforeTheCommit() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), UNREADABLE);

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk(), "released uncompared");
        assertTrue(outcome.getFailure().contains("Could not compare with the 0.9.0 release"),
                outcome.getFailure());
        assertTrue(outcome.getFailure().contains(OdkRelease.UNCOMPARED_LABEL),
                outcome.getFailure());
        assertFalse(new File(root, "releases/1.0.0").exists());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertEquals(0, asked.size());
    }

    /**
     * The 1.154.0 review's M2: when it is the release just built that cannot be read, the
     * failure says so - not that the last release is to be put where it is kept.
     */
    @Test
    void anUnreadableNewReleaseIsNotBlamedOnTheLastOne() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), owl("Egg"));
        released = UNREADABLE;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk(), "released uncompared");
        assertTrue(outcome.getFailure().contains("just built"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("eggboil.owl"), outcome.getFailure());
        assertFalse(outcome.getFailure().contains("Put the 0.9.0 release"), outcome.getFailure());
        assertFalse(new File(root, "releases/1.0.0").exists());
    }

    /** With the box ticked the person has said they know: it is released, and the warning stays. */
    @Test
    void aReleaseUncomparedOnPurposeGoesOnAndTheWarningStays() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), UNREADABLE);

        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0",
                VersionIriShape.of(VersionIriShape.Kind.ODK), true, true, true), odk(), git(),
                staging -> true);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertTrue(outcome.getWarnings().toString().contains("Could not compare with the 0.9.0 "
                + "release"), outcome.getWarnings().toString());
        assertTrue(new File(root, "releases/1.0.0/eggboil.owl").isFile());
    }

    /** With the check off, the person has said they know: it is released, and the notes say so. */
    @Test
    void aTermDroppedOnPurposeIsReleasedAndNamedInTheNotes() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), owl("Egg", "Pan", "Timer"));

        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0",
                VersionIriShape.of(VersionIriShape.Kind.ODK), true, false), odk(), git(),
                staging -> true);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertTrue(outcome.getWarnings().toString().contains("Timer"),
                outcome.getWarnings().toString());
        String notes = new String(Files.readAllBytes(
                new File(root, "releases/1.0.0/CHANGES.md").toPath()), StandardCharsets.UTF_8);
        assertTrue(notes.contains("Timer"), notes);
    }

    @Test
    void fromTheSecondReleaseOnTheNotesSayWhatChanged() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), owl("Egg"));

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        String notes = new String(Files.readAllBytes(
                new File(root, "releases/1.0.0/CHANGES.md").toPath()), StandardCharsets.UTF_8);
        assertTrue(notes.contains("Pan"), notes);
        assertTrue(outcome.getWritten().contains(new File(root, "releases/1.0.0/CHANGES.md")));
    }

    // ------------------------------------------------------------------ git

    @Test
    void whenThePersonSaysNoNothingIsCommittedAndTheReleaseStays() {
        sayYes = false;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(outcome.isCommitted());
        assertNull(outcome.getTag());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertTrue(new File(root, "eggboil.owl").isFile());
        assertTrue(new File(root, "releases/1.0.0/eggboil.owl").isFile());
        // The commands to run later are lines of their own, to be copied: a sentence in a
        // dialog's label cannot be. The summary stays a sentence and says where they are.
        assertEquals(Arrays.asList("To record the release later, in " + root.getAbsolutePath()
                + ":", "git add -u -- .",
                "git add -f -- eggboil.obo eggboil.owl eggboil-base.owl",
                "git add -- releases/1.0.0", "git commit -m \"release(1.0.0)\"",
                "git tag -a v1.0.0 --cleanup=verbatim -F releases/1.0.0/RELEASE.md", "git push --follow-tags"),
                outcome.getLater());
        assertFalse(outcome.getSummary().contains("git add"), outcome.getSummary());
        assertFalse(outcome.getSummary().contains("\n"), outcome.getSummary());
        assertTrue(outcome.getSummary().contains("Not committed")
                && outcome.getSummary().contains("end of the log"), outcome.getSummary());
    }

    @Test
    void aReleaseThatIsRecordedLeavesNothingForLater() {
        assertEquals(Collections.<String>emptyList(), release("1.0.0").getLater());
    }

    /** Review focus 5: a machine without git still gets its release, and is told. */
    @Test
    void withoutGitTheReleaseIsWrittenAndSaysWhatWasNotRecorded() {
        gitInstalled = false;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(outcome.isCommitted());
        assertEquals(0, asked.size());
        assertTrue(new File(root, "releases/1.0.0/eggboil.owl").isFile());
        assertTrue(outcome.getWarnings().toString().contains("git is not installed"),
                outcome.getWarnings().toString());
    }

    @Test
    void aFolderThatIsNoRepositoryIsNotMadeOne() throws IOException {
        assertTrue(new File(root, ".git").delete());

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(outcome.isCommitted());
        assertEquals(0, asked.size());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertTrue(outcome.getWarnings().toString().contains("not a git repository"),
                outcome.getWarnings().toString());
    }

    @Test
    void aCommitThatFailsStopsBeforeTheTagAndSaysWhy() {
        commitFails = true;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("gpg failed to sign"), outcome.getFailure());
        assertFalse(gitRan.toString().contains("tag"), gitRan.toString());
        assertFalse(outcome.isCommitted());
        assertTrue(new File(root, "releases/1.0.0/eggboil.owl").isFile(), "the release itself "
                + "is there");
        assertEquals(Arrays.asList("Not run yet, in " + root.getAbsolutePath() + ":",
                "git commit -m \"release(1.0.0)\"", "git tag -a v1.0.0 --cleanup=verbatim -F releases/1.0.0/RELEASE.md",
                "git push --follow-tags"), outcome.getLater());
    }

    /**
     * What stops a release is said in a few sentences: the dialog that shows it keeps the
     * build's log on screen below it.
     */
    @Test
    void aRefusalIsShortEnoughToLeaveTheLogItsRoom() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), owl("Egg", "Pan", "Timer", "Spoon"));

        OdkRelease.Outcome outcome = release("1.0.0");
        String failure = outcome.getFailure();

        assertTrue(failure.contains("2 terms") && failure.contains("among them"), failure);
        assertTrue(failure.length() < 480, failure.length() + " characters: " + failure);
        assertTrue(outcome.getWarnings().toString().contains("Removed: Timer")
                && outcome.getWarnings().toString().contains("Removed: Spoon"),
                "each of them is a line of the log: " + outcome.getWarnings());
    }

    // ------------------------------------------------------------------ whose project it is

    /** An ODK repository OntoBoard did not make is released as its maintainers release it. */
    @Test
    void aProjectOntoBoardDidNotMakeGetsNoReleasesFolder() throws IOException {
        write(new File(ontology, "eggboil-odk.yaml"), "id: eggboil\ntitle: Theirs\n");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(new File(root, "releases").exists());
        assertFalse(gitRan.toString().contains("releases"), gitRan.toString());
        // 1.155.0: its record is in the release's log; its repository gets no file of it.
        String log = String.join("\n", outcome.getTranscript());
        assertTrue(log.contains("# eggboil 1.0.0"), log);
        assertTrue(log.contains("## How it was checked"), log);
        assertTrue(gitRan.toString().contains("-m, eggboil 1.0.0"), gitRan.toString());
    }

    /**
     * nfdicore and mwo are released by a script of their own, which calls {@code sh run.sh make
     * ...} line after line. It runs in the container with a run.sh that runs make where it
     * stands, since a container cannot start a container.
     */
    @Test
    void aProjectWithItsOwnScriptIsReleasedByIt() throws IOException {
        write(new File(ontology, "release.sh"), "VERSION=1.0.0\nPRIOR_VERSION=0.9.0\n"
                + "sh run.sh make VERSION=$VERSION prepare_release\n");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("sh", "release.sh"), odkRan.get(0));
        assertEquals(Boolean.TRUE, withPassThrough.get(0));
    }

    /** The script and the dialog must be releasing the same version. */
    @Test
    void aScriptThatSaysAnotherVersionIsNotRun() throws IOException {
        write(new File(ontology, "release.sh"), "VERSION=0.9.0\nsh run.sh make prepare_release\n");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("VERSION=0.9.0"), outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
    }

    /** Which version a script releases has to be readable, or the tag could name another. */
    @Test
    void aScriptThatNamesNoVersionIsNotRun() throws IOException {
        write(new File(ontology, "release.sh"), "sh run.sh make prepare_release\n");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("VERSION="), outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
    }

    /**
     * A shell in the container reads the script. A script with Windows line endings - how Git
     * for Windows checks nfdicore and MWO out - is run with the carriage returns taken off and
     * the file left as it is, and the log says so (1.155.0: before, the release was refused).
     */
    @Test
    void aScriptWithWindowsLineEndingsRunsWithoutTheCarriageReturns() throws IOException {
        File file = new File(ontology, "release.sh");
        write(file, "VERSION=1.0.0\r\nsh run.sh make VERSION=$VERSION prepare_release\r\n");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("sh", "-c", "sed 's/\\r$//' release.sh > /tmp/release.sh"
                + " && exec sh -c '. /tmp/release.sh' release.sh"), odkRan.get(0));
        assertEquals(Boolean.TRUE, withPassThrough.get(0));
        assertTrue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .contains("VERSION=1.0.0\r\n"), "the file keeps its line endings");
        String transcript = String.join("\n", outcome.getTranscript());
        assertTrue(transcript.contains("release.sh has Windows line endings"), transcript);
    }

    // ------------------------------------------------------------------ what else stops it

    @Test
    void aFolderThatIsNoOdkProjectIsRefused() throws IOException {
        assertTrue(new File(ontology, "Makefile").delete());

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Makefile"), outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
    }

    @Test
    void aVersionThatIsNotOneIsRefused() {
        OdkRelease.Outcome outcome = release("next week");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("'next week' is not a version"),
                outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
    }

    /**
     * Which files a release is made of is the Makefile's to say - {@code git add -f} is not
     * pointed at a guess. An older Makefile that cannot say leaves the release built and
     * nothing recorded.
     */
    @Test
    void aMakefileThatDoesNotNameItsReleaseFilesStopsBeforeGit() {
        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0",
                VersionIriShape.of(VersionIriShape.Kind.ODK), true, true), new OdkRelease.Odk() {
                    @Override
                    public ProcessRunner.Outcome run(List<String> inContainer,
                            boolean passThroughRunSh) throws IOException {
                        if (inContainer.contains("show_release_assets")) {
                            return new ProcessRunner.Outcome(2, Arrays.asList("make: *** No rule "
                                    + "to make target 'show_release_assets'.  Stop."), false);
                        }
                        return odk().run(inContainer, passThroughRunSh);
                    }

                    @Override
                    public String shown(List<String> inContainer, boolean passThroughRunSh) {
                        return String.join(" ", inContainer);
                    }
                }, git(), staging -> true);

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("show_release_assets"), outcome.getFailure());
        assertTrue(new File(root, "eggboil.owl").isFile(), "the release was built");
        assertFalse(new File(root, "releases").exists());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
    }

    /** A commit made while a merge is unfinished records the conflict markers. */
    @Test
    void anUnfinishedMergeIsNotCommittedOver() {
        conflict = true;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(outcome.isCommitted());
        assertEquals(0, asked.size());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertTrue(outcome.getWarnings().toString().contains("src/ontology/eggboil-edit.owl"),
                outcome.getWarnings().toString());
    }

    /** The commit goes to the branch that is checked out; the person is shown which. */
    @Test
    void theBranchTheCommitGoesToIsShownWithTheCommands() {
        release("1.0.0");

        assertEquals("main", asked.get(0).branch());
    }

    // ------------------------------------------------------------------ which release came before

    /**
     * A patch to an older line is not held against a release that came after it: 1.0.1 follows
     * 1.0.0, whatever 2.0.0 has since said.
     */
    @Test
    void aLaterReleaseIsNotTheOneBefore() throws IOException {
        write(new File(root, "releases/2.0.0/eggboil.owl"), owl("Egg", "Pan", "Timer"));

        OdkRelease.Outcome outcome = release("1.0.1");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(new File(root, "releases/1.0.1/CHANGES.md").exists());
        assertFalse(String.join("\n", outcome.getTranscript()).contains("Compared with"),
                "nothing came before it, so nothing was compared");
    }

    /**
     * A project that released by date and now releases by number has two histories. Holding
     * one against the other would report every term of the first as dropped.
     */
    @Test
    void aReleaseNumberedAnotherWayIsNotTheOneBefore() throws IOException {
        write(new File(root, "releases/2026-01-01/eggboil.owl"), owl("Egg", "Pan", "Timer"));

        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(new File(root, "releases/1.0.0/CHANGES.md").exists());
        assertFalse(String.join("\n", outcome.getTranscript()).contains("Compared with"),
                "nothing came before it, so nothing was compared");
    }

    /** The other way round: a dated release is not held against a numbered one either. */
    @Test
    void aDatedReleaseIsNotHeldAgainstANumberedOne() throws IOException {
        write(new File(root, "releases/1.0.0/eggboil.owl"), owl("Egg", "Pan", "Timer"));

        OdkRelease.Outcome outcome = release("2026-11-12");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertFalse(new File(root, "releases/2026-11-12/CHANGES.md").exists());
        assertFalse(String.join("\n", outcome.getTranscript()).contains("Compared with"),
                "nothing came before it, so nothing was compared");
    }

    // ------------------------------------------------------------------ which files it is

    /** ODK as {@link #odk()}, except for what it says the release's files are. */
    private OdkRelease.Odk odkThatLists(final String... lines) {
        return new OdkRelease.Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> inContainer, boolean passThroughRunSh)
                    throws IOException {
                return inContainer.contains("show_release_assets")
                        ? new ProcessRunner.Outcome(0, Arrays.asList(lines), false)
                        : odk().run(inContainer, passThroughRunSh);
            }

            @Override
            public String shown(List<String> inContainer, boolean passThroughRunSh) {
                return String.join(" ", inContainer);
            }
        };
    }

    /** A runtime prints lines of its own around a command's output. They are not file names. */
    @Test
    void aRuntimesOwnLinesAroundTheListAreNotTakenForFiles() {
        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0",
                null, true, true), odkThatLists(
                        "WARNING: The requested image's platform (linux/amd64) does not match "
                                + "the detected host platform (linux/arm64/v8)",
                        "../../eggboil.obo ../../eggboil.owl ../../eggboil-base.owl",
                        "time=\"2026-11-12T10:00:00Z\" level=warning msg=\"something\""),
                git(), staging -> true);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("git", "add", "-f", "--", "eggboil.obo", "eggboil.owl",
                "eggboil-base.owl"), gitRan.get(1));
    }

    /**
     * {@code git add} stops at a name that is not there. A file the Makefile names and the
     * build did not leave - a project's script may build less than ODK's target - is said, and
     * the rest is recorded.
     */
    @Test
    void aFileTheMakefileNamesAndTheBuildDidNotLeaveIsSaidAndNotStaged() throws IOException {
        write(new File(temp, "elsewhere.owl"), owl("Egg"));

        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, "1.0.0",
                null, true, true), odkThatLists(
                        "../../eggboil.owl ../../eggboil-full.owl ../../../elsewhere.owl"),
                git(), staging -> true);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("git", "add", "-f", "--", "eggboil.owl"), gitRan.get(1));
        assertTrue(outcome.getWarnings().toString().contains("../../eggboil-full.owl as a "
                + "release file, and the build left no such file"),
                outcome.getWarnings().toString());
        assertTrue(outcome.getWarnings().toString().contains("../../../elsewhere.owl as a "
                + "release file. That is outside the project's folder"),
                "a file that is there, but not in the project, is not a file of its release: "
                        + outcome.getWarnings());
    }

    /** A Makefile of an older ODK says its base through its other variables. */
    @Test
    void aBaseSaidThroughOtherVariablesIsRead() throws IOException {
        File makefile = new File(ontology, "Makefile");
        String text = new String(Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8);
        String plain = "ONTBASE=                    https://example.org/eggboil/eggboil";
        assertTrue(text.contains(plain));
        write(makefile, text.replace(plain, "ONTBASE=                    $(URIBASE)/${ONT}"));

        assertEquals("https://example.org/eggboil/eggboil", OdkRelease.Facts.of(edit).ontbase());
    }

    /** A shape other than ODK's is written from the base; without one it cannot be. */
    @Test
    void anotherShapeNeedsABaseTheMakefileSays() throws IOException {
        File makefile = new File(ontology, "Makefile");
        String text = new String(Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8);
        write(makefile, text.replace("ONTBASE=                    https://example.org/eggboil/"
                + "eggboil", "ONTBASE=                    $(shell ./base.sh)"));

        OdkRelease.Outcome outcome = release("1.0.0",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_ONLY), true);

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("ONTBASE"), outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
        assertTrue(release("1.0.0").isOk(), "ODK's own shape is the Makefile's and needs none");
    }

    // ------------------------------------------------------------------ git, when it stops

    /** A tag that fails comes after the commit: the commit is there, and that is said. */
    @Test
    void aTagThatFailsLeavesTheCommitAndSaysSo() {
        tagFails = true;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.isCommitted());
        assertNull(outcome.getTag());
        assertTrue(outcome.getFailure().contains("is committed"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("no tag message"), "git's own reason: "
                + outcome.getFailure());
        assertEquals(Arrays.asList("Not run yet, in " + root.getAbsolutePath() + ":",
                "git tag -a v1.0.0 --cleanup=verbatim -F releases/1.0.0/RELEASE.md", "git push --follow-tags"),
                outcome.getLater());
        assertTrue(outcome.getFailure().contains("end of the log"), outcome.getFailure());
    }

    // ------------------------------------------------------------------ with git itself

    private void realGit(String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Collections.singletonList("git"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
    }

    /** A repository as Create leaves it: everything committed. Who commits is set here only. */
    private void aRealRepository() throws IOException {
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");
        assertTrue(new File(root, ".git").delete());
        realGit("init", "-q");
        realGit("config", "user.name", "Test");
        realGit("config", "user.email", "test@example.org");
        realGit("config", "commit.gpgsign", "false");
        realGit("config", "tag.gpgsign", "false");
        realGit("config", "core.autocrlf", "false");
        realGit("add", "-A");
        realGit("commit", "-q", "-m", "first");
    }

    private OdkRelease.Outcome releaseWithGitItself(String version) {
        return OdkRelease.run(new OdkRelease.Request(edit, version,
                VersionIriShape.of(VersionIriShape.Kind.ODK), true, true), odk(),
                ProcessRunner.real(), staging -> true);
    }

    /**
     * ODK's {@code .gitignore} keeps the release files out of git, so that they are not
     * committed between releases. The release's commit has them all the same, and the copy.
     */
    @Test
    void withGitItselfAReleaseIsACommitAndATagThatHoldItsFiles() throws IOException {
        aRealRepository();

        OdkRelease.Outcome outcome = releaseWithGitItself("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertTrue(outcome.isCommitted());
        assertEquals(Collections.singletonList("v1.0.0"),
                GitTags.releases(root, ProcessRunner.real()));
        assertArrayEquals(released.getBytes(StandardCharsets.UTF_8),
                GitTags.fileAt(root, "v1.0.0", "eggboil.owl"));
        assertArrayEquals(released.getBytes(StandardCharsets.UTF_8),
                GitTags.fileAt(root, "v1.0.0", "releases/1.0.0/eggboil-base.owl"));
        assertEquals(Collections.<String>emptyList(), ProcessRunner.real().run(root,
                Arrays.asList("git", "status", "--porcelain"), 1, null).getOutput(),
                "nothing of the release is left uncommitted");
    }

    /**
     * An ODK repository OntoBoard did not make keeps no releases folder: what it released last
     * is at that release's tag, and the new release is held against it there.
     */
    @Test
    void aProjectThatKeepsNoFolderIsComparedWithTheReleaseAtItsLastTag() throws IOException {
        write(new File(ontology, "eggboil-odk.yaml"), "id: eggboil\ntitle: Theirs\n");
        aRealRepository();
        released = owl("Egg", "Pan", "Timer");
        assertTrue(releaseWithGitItself("0.9.0").isCommitted());
        released = owl("Egg", "Pan");

        OdkRelease.Outcome outcome = releaseWithGitItself("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Timer"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("0.9.0"), outcome.getFailure());
        assertEquals(Collections.singletonList("v0.9.0"),
                GitTags.releases(root, ProcessRunner.real()));
        assertFalse(new File(root, "releases").exists());
    }

    /** The shape proposed is read off the last release wherever that is kept. */
    @Test
    void theShapeOfAReleaseThatIsOnlyATagIsProposedToo() throws IOException {
        write(new File(ontology, "eggboil-odk.yaml"), "id: eggboil\ntitle: Theirs\n");
        aRealRepository();
        released = owl("Egg").replace("Ontology(<https://example.org/eggboil/eggboil.owl>\n",
                "Ontology(<https://example.org/eggboil/eggboil.owl>\n"
                        + "<https://example.org/eggboil/eggboil/0.9.0/eggboil.owl>\n");
        assertTrue(releaseWithGitItself("0.9.0").isCommitted());

        OdkRelease.Proposal proposal = OdkRelease.proposal(edit, ProcessRunner.real());

        assertEquals("0.9.0", proposal.lastVersion());
        assertEquals("https://example.org/eggboil/eggboil/0.9.0/eggboil.owl",
                proposal.lastVersionIri());
        assertEquals(VersionIriShape.Kind.VERSION_AND_FILE, proposal.shape().kind());
    }

    // ------------------------------------------------------------------ what the dialog proposes

    @Test
    void theShapeProposedIsThePreviousReleases() throws IOException {
        assertEquals(VersionIriShape.Kind.ODK, OdkRelease.proposedShape(edit, git()).kind(),
                "a first release: ODK's");

        write(new File(root, "releases/0.9.0/eggboil.owl"), owl("Egg").replace(
                "Ontology(<https://example.org/eggboil/eggboil.owl>\n",
                "Ontology(<https://example.org/eggboil/eggboil.owl>\n"
                        + "<https://example.org/eggboil/eggboil/0.9.0>\n"));

        assertEquals(VersionIriShape.Kind.VERSION_ONLY,
                OdkRelease.proposedShape(edit, git()).kind());
    }

    /**
     * The dialog reads the last release's version IRI when it opens, from the start of the
     * file only: a release may be hundreds of megabytes. ODK writes RDF/XML; a release made
     * another way may be in another syntax.
     */
    @Test
    void theVersionIriIsReadFromTheStartOfAReleaseInAnySyntax() {
        assertEquals("https://example.org/x/releases/1.0.0/x.owl", OdkRelease.versionIriIn(
                "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns=\"https://example.org/x.owl#\">\n"
                        + "    <owl:Ontology rdf:about=\"https://example.org/x.owl\">\n"
                        + "        <owl:versionIRI rdf:resource=\"https://example.org/x/"
                        + "releases/1.0.0/x.owl\"/>\n"));
        assertEquals("https://example.org/x/1.0.0/x.ttl", OdkRelease.versionIriIn(
                "<https://example.org/x.owl> rdf:type owl:Ontology ;\n"
                        + "    owl:versionIRI <https://example.org/x/1.0.0/x.ttl> ;\n"));
        assertEquals("https://example.org/x/1.0.0", OdkRelease.versionIriIn(
                "Prefix(:=<https://example.org/x#>)\n\nOntology(<https://example.org/x.owl>\n"
                        + "<https://example.org/x/1.0.0>\n"));
        assertEquals("https://example.org/x/1.0.0", OdkRelease.versionIriIn(
                "Prefix: : <https://example.org/x#>\n\nOntology: <https://example.org/x.owl>\n"
                        + "<https://example.org/x/1.0.0>\n"));
        assertNull(OdkRelease.versionIriIn("Ontology(<https://example.org/x.owl>\n"
                + "Declaration(Class(<https://example.org/x#A>))\n)"), "a header without one");
    }

    /**
     * A project of OntoBoard's older layout has a Makefile too, one OntoBoard wrote. It is
     * no ODK project until it has been carried over.
     */
    @Test
    void anOdkProjectIsOneWithOdksMakefile() throws IOException {
        assertTrue(OdkRelease.isOdkProject(edit));

        File makefile = new File(ontology, "Makefile");
        write(makefile, OdkMigration.OLD_MAKEFILE_HEADER + "\n" + new String(
                Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8));
        assertFalse(OdkRelease.isOdkProject(edit), "a Makefile OntoBoard's older layout has");

        assertTrue(makefile.delete());
        assertFalse(OdkRelease.isOdkProject(edit), "no Makefile");
        assertFalse(OdkRelease.isOdkProject(new File(temp, "loose.owl")), "a file on its own");
        assertFalse(OdkRelease.isOdkProject(null));
    }

    /** What has been released: the folders and the tags, each version once, newest first. */
    @Test
    void whatHasBeenReleasedIsTheFoldersAndTheTags() throws IOException {
        write(new File(root, "releases/1.0.0/eggboil.owl"), owl("Egg"));
        write(new File(root, "releases/1.2.0/eggboil.owl"), owl("Egg"));
        assertTrue(new File(root, "releases/9.9.9").mkdirs(), "an empty folder is no release");
        assertTrue(new File(root, "releases/latest").mkdirs());

        List<OntologyVersion> released = OdkRelease.released(OdkRelease.Facts.of(edit),
                Arrays.asList("v1.10.0", "v1.2.0"));

        assertEquals(Arrays.asList("1.10.0", "1.2.0", "1.0.0"), texts(released));
    }

    private static List<String> texts(List<OntologyVersion> versions) {
        List<String> texts = new ArrayList<String>();
        for (OntologyVersion version : versions) {
            texts.add(version.getText());
        }
        return texts;
    }

    @Test
    void theProjectsNamesAreReadFromItsMakefile() {
        OdkRelease.Facts facts = OdkRelease.Facts.of(edit);

        assertEquals("eggboil", facts.id());
        assertEquals("https://example.org/eggboil/eggboil", facts.ontbase());
        assertEquals(root.getAbsoluteFile(), facts.root().getAbsoluteFile());
        assertNotNull(facts.ontology());
    }

    // ------------------------------------------------------------------ review of 1.153.0

    /**
     * Stopped once the build is done - while the release is held against the one before, say:
     * nothing is copied to releases/, so the version is not taken for released the next
     * time, and nothing is asked or recorded.
     */
    @Test
    void aReleaseStoppedAfterItWasBuiltCopiesAndRecordsNothing() {
        stoppedAfterTheBuild = true;

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().startsWith("You stopped the release"),
                outcome.getFailure());
        assertFalse(new File(root, "releases").exists());
        assertNull(ReleaseRun.whyNotThisVersion(root, "1.0.0", tags));
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertEquals(0, asked.size());
    }

    /**
     * A release that stops at a check says which check and which terms, as Build says of the
     * same run - not make's last line, which is a row of the check's answer.
     */
    @Test
    void aBuildThatStopsAtACheckSaysWhichCheckAndItsTerms() {
        buildFails = true;
        failsSaying = Arrays.asList(
                "robot --catalog catalog-v001.xml verify -i tmp/merged-eggboil-edit.ofn "
                        + "--queries ../sparql/a-violation.sparql ../sparql/b-violation.sparql "
                        + "-O reports",
                "PASS Rule ../sparql/a-violation.sparql: 0 violation(s)",
                "FAIL Rule ../sparql/b-violation.sparql: 1 violation(s)",
                "term,label",
                "https://example.org/eggboil/t1,coarse salt",
                "make: *** [Makefile:269: sparql_test] Error 1");

        String failure = release("1.0.0").getFailure();

        assertTrue(failure.startsWith("b-violation.sparql: coarse salt."), failure);
        assertTrue(failure.contains("'prepare_release' stopped at sparql_test"), failure);
        assertTrue(failure.endsWith("Nothing was copied, committed or tagged."), failure);
    }

    /**
     * An ontology outside OWL 2 DL is no finding of the quality report, though the file that
     * says so lies under reports/ and the log is full of the word "violation".
     */
    @Test
    void aBuildThatStopsAtTheProfileIsNotBlamedOnTheQualityReport() {
        buildFails = true;
        failsSaying = Arrays.asList(
                "PASS Rule ../sparql/a-violation.sparql: 0 violation(s)",
                "robot --catalog catalog-v001.xml validate-profile --profile DL -i "
                        + "tmp/validate.ofn -o reports/validate_profile_owl2dl_eggboil.owl.txt "
                        + "|| { cat reports/validate_profile_owl2dl_eggboil.owl.txt && exit 1; }",
                "PROFILE VIOLATION ERROR https://example.org/eggboil.owl violates profile DL",
                "OWL 2 DL Profile Report: Ontology and imports closure NOT in profile. The "
                        + "following violations are present:",
                "Use of undeclared class: <https://example.org/A> [SubClassOf("
                        + "<https://example.org/A> <https://example.org/B>) in OntologyID("
                        + "OntologyIRI(<https://example.org/eggboil.owl>))]",
                "",
                "make: *** [Makefile:251: reports/validate_profile_owl2dl_eggboil.owl.txt] "
                        + "Error 1");

        String failure = release("1.0.0").getFailure();

        assertFalse(failure.toLowerCase().contains("quality report"), failure);
        assertTrue(failure.startsWith("The ontology is not in OWL 2 DL"), failure);
    }

    /**
     * make keeps a variable's last value, and ODK's Makefile reads the project's own last: a
     * base that file sets is the base make uses, and the one a version IRI of another shape
     * than ODK's has to be written with (review of 1.153.0: the first value was kept).
     */
    @Test
    void aBaseTheProjectsOwnMakefileSetsIsTheOneMakeUses() throws IOException {
        File own = new File(ontology, "eggboil.Makefile");
        String text = own.isFile() ? new String(Files.readAllBytes(own.toPath()),
                StandardCharsets.UTF_8) : "";

        write(own, text + "\nONTBASE=https://w3id.org/kitchen/eggboil\n");
        assertEquals("https://w3id.org/kitchen/eggboil", OdkRelease.Facts.of(edit).ontbase());

        write(own, text + "\nONTBASE?=https://w3id.org/kitchen/eggboil\n");
        assertEquals("https://example.org/eggboil/eggboil", OdkRelease.Facts.of(edit).ontbase(),
                "?= gives a value only to what has none");
    }

    // ------------------------------------------------------------------ the reasoner (1.155.0)

    /** The egg project's contradiction in one file: only a reasoner reading inverses sees it. */
    private void anInconsistentEditFile() throws IOException {
        write(edit, "Prefix(obo:=<http://purl.obolibrary.org/obo/>)\n"
                + "Ontology(<https://example.org/eggboil/eggboil.owl>\n"
                + "SubClassOf(obo:BFO_0000019 obo:BFO_0000020)\n"
                + "DisjointClasses(obo:BFO_0000020 obo:BFO_0000031)\n"
                + "SubClassOf(obo:IAO_0000003 obo:BFO_0000031)\n"
                + "InverseObjectProperties(obo:IAO_0000039 obo:IAO_0000040)\n"
                + "ObjectPropertyRange(obo:IAO_0000039 obo:IAO_0000003)\n"
                + "SubClassOf(obo:EGG_0000001 obo:BFO_0000019)\n"
                + "SubClassOf(obo:EGG_0000001 ObjectSomeValuesFrom(obo:IAO_0000040 "
                + "obo:EGG_0000000))\n"
                + "ClassAssertion(obo:EGG_0000001 obo:EGG_0000002)\n)\n");
    }

    private boolean goOn;
    private final List<ReleaseVerdict> verdictsAsked = new ArrayList<ReleaseVerdict>();

    private OdkRelease.Outcome releaseWith(String reasoner, long limitMillis) {
        return OdkRelease.run(new OdkRelease.Request(edit, "1.0.0", null, true, true)
                .withReasoner(reasoner, limitMillis), odk(), git(), new OdkRelease.Asks() {
                    @Override
                    public boolean commit(ReleaseRun.Staging staging) {
                        asked.add(staging);
                        return sayYes;
                    }

                    @Override
                    public boolean goOnWithoutVerdict(ReleaseVerdict verdict) {
                        verdictsAsked.add(verdict);
                        return goOn;
                    }
                });
    }

    /** D1: inconsistent by the reasoner the person chose - nothing is built, nothing is run. */
    @Test
    void anInconsistentOntologyIsNotBuilt() throws IOException {
        anInconsistentEditFile();

        OdkRelease.Outcome outcome = releaseWith("HermiT", 60_000);

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("inconsistent"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("Nothing was built"), outcome.getFailure());
        assertEquals(new ArrayList<List<String>>(), odkRan, "ODK was asked to run nothing");
        assertEquals(ReleaseVerdict.Kind.INCONSISTENT, outcome.getVerdict().kind());
    }

    /** The person chose ELK: ELK is asked - it sees nothing here - and ODK reasons with it. */
    @Test
    void theChosenReasonerReachesOdk() throws IOException {
        anInconsistentEditFile();

        OdkRelease.Outcome outcome = releaseWith("ELK", 60_000);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("make", "clean"), odkRan.get(0));
        assertEquals(Arrays.asList("make", "prepare_release", "VERSION=1.0.0", "REASONER=ELK"),
                odkRan.get(1));
        assertEquals(ReleaseVerdict.Kind.CLEAN, outcome.getVerdict().kind());
        assertTrue(String.join("\n", outcome.getWarnings()).contains("OWL 2 EL"),
                outcome.getWarnings().toString());
    }

    /** A project's own release.sh chooses its own reasoner; the check runs all the same. */
    @Test
    void aReleaseScriptKeepsItsReasoner() throws IOException {
        write(new File(ontology, "release.sh"), "VERSION=1.0.0\nPRIOR_VERSION=0.9.0\n"
                + "sh run.sh make VERSION=$VERSION prepare_release\n");

        OdkRelease.Outcome outcome = releaseWith("HermiT", 60_000);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("sh", "release.sh"), odkRan.get(0));
        assertEquals(ReleaseVerdict.Kind.CLEAN, outcome.getVerdict().kind());
        assertTrue(String.join("\n", outcome.getWarnings()).contains(
                "release.sh reasons with the reasoner it chooses"),
                outcome.getWarnings().toString());
    }

    /** Not finished is not a finding: the person is asked, and no means no. */
    @Test
    void notFinishedAsksAndStopsWhenTheAnswerIsNo() throws IOException {
        goOn = false;

        OdkRelease.Outcome outcome = releaseWith("HermiT", 1);

        assertFalse(outcome.isOk());
        assertEquals(1, verdictsAsked.size());
        assertEquals(ReleaseVerdict.Kind.TIMED_OUT, verdictsAsked.get(0).kind());
        assertTrue(outcome.getFailure().contains("not checked"), outcome.getFailure());
        assertEquals(new ArrayList<List<String>>(), odkRan);
    }

    @Test
    void notFinishedGoesOnWhenThePersonSaysSo() throws IOException {
        goOn = true;

        OdkRelease.Outcome outcome = releaseWith("HermiT", 1);

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertTrue(String.join("\n", outcome.getWarnings()).contains("not checked"),
                outcome.getWarnings().toString());
    }

    /** No reasoner named (an older caller): checked with what the form offers first. */
    @Test
    void aRequestThatNamesNoReasonerIsStillChecked() throws IOException {
        anInconsistentEditFile();

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertEquals("HermiT", outcome.getVerdict().reasoner());
        assertEquals(new ArrayList<List<String>>(), odkRan);
    }
}
