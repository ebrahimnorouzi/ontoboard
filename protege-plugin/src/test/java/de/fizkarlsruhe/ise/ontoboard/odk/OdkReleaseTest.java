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
                if (buildFails) {
                    return new ProcessRunner.Outcome(2, Arrays.asList("robot reason ...",
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
                });
    }

    private OdkRelease.Outcome release(String version) {
        return release(version, VersionIriShape.of(VersionIriShape.Kind.ODK), true);
    }

    // ------------------------------------------------------------------ the whole of it

    @Test
    void aReleaseIsBuiltKeptAndRecorded() {
        OdkRelease.Outcome outcome = release("1.0.0");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Arrays.asList("make", "prepare_release", "VERSION=1.0.0"), odkRan.get(0));
        assertEquals(Arrays.asList("make", "-s", "show_release_assets"), odkRan.get(1));
        assertEquals(Arrays.asList(Boolean.FALSE, Boolean.FALSE), withPassThrough);
        assertTrue(new File(root, "releases/1.0.0/eggboil.owl").isFile());
        assertTrue(new File(root, "releases/1.0.0/eggboil-base.owl").isFile());
        assertFalse(new File(root, "releases/1.0.0/CHANGES.md").exists(), "a first release has "
                + "nothing to have changed from");
        assertEquals(Arrays.asList(
                Arrays.asList("git", "add", "-u"),
                Arrays.asList("git", "add", "-f", "--", "eggboil.obo", "eggboil.owl",
                        "eggboil-base.owl"),
                Arrays.asList("git", "add", "--", "releases/1.0.0"),
                Arrays.asList("git", "commit", "-m", "release(1.0.0)"),
                Arrays.asList("git", "tag", "-a", "v1.0.0", "-m", "eggboil 1.0.0")), gitRan);
        assertTrue(outcome.isCommitted());
        assertEquals("v1.0.0", outcome.getTag());
        assertEquals(1, asked.size(), "the commands were shown once, before they ran");
        assertEquals(Collections.singletonList("notes.txt"), asked.get(0).leftOut());
        assertTrue(outcome.getSummary().contains("1.0.0") && outcome.getSummary().contains("v1.0.0"),
                outcome.getSummary());
        assertTrue(outcome.getSummary().contains("git push --follow-tags"), outcome.getSummary());
        assertTrue(String.join("\n", outcome.getTranscript())
                .contains("docker run --rm ... make prepare_release VERSION=1.0.0"));
    }

    @Test
    void theShapeChosenAndTheFastBuildReachMake() {
        release("1.0.0", VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE), false);

        assertEquals(Arrays.asList("make", "prepare_release_fast", "VERSION=1.0.0",
                "ANNOTATE_ONTOLOGY_VERSION=annotate -V https://example.org/eggboil/eggboil/1.0.0/$@"
                        + " --annotation owl:versionInfo 1.0.0"), odkRan.get(0));
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
        assertEquals(1, odkRan.size(), "nothing is asked of a build that failed");
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
        assertFalse(new File(root, "releases/1.0.0").exists());
        assertEquals(Collections.<List<String>>emptyList(), gitRan);
        assertEquals(0, asked.size());
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
                + ":", "git add -u", "git add -f -- eggboil.obo eggboil.owl eggboil-base.owl",
                "git add -- releases/1.0.0", "git commit -m \"release(1.0.0)\"",
                "git tag -a v1.0.0 -m \"eggboil 1.0.0\"", "git push --follow-tags"),
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
                "git commit -m \"release(1.0.0)\"", "git tag -a v1.0.0 -m \"eggboil 1.0.0\"",
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
     * A shell in the container reads the script. With Windows line endings every line ends in
     * a character that shell takes for part of the last word: the version, the target. It is
     * refused with that reason, since the failure it would cause names the wrong thing.
     */
    @Test
    void aScriptWithWindowsLineEndingsIsNotRun() throws IOException {
        write(new File(ontology, "release.sh"), "VERSION=1.0.0\r\n"
                + "sh run.sh make VERSION=$VERSION prepare_release\r\n");

        OdkRelease.Outcome outcome = release("1.0.0");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Windows line endings"), outcome.getFailure());
        assertEquals(Collections.<List<String>>emptyList(), odkRan);
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
                "git tag -a v1.0.0 -m \"eggboil 1.0.0\"", "git push --follow-tags"),
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
     * A project of OntoBoard's older layout has a Makefile too, one OntoBoard wrote and builds
     * itself. It is released the older way until it has been converted.
     */
    @Test
    void anOdkProjectIsOneWithOdksMakefile() throws IOException {
        assertTrue(OdkRelease.isOdkProject(edit));

        File makefile = new File(ontology, "Makefile");
        write(makefile, OdkScaffold.GENERATED_MAKEFILE_HEADER + "\n" + new String(
                Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8));
        assertFalse(OdkRelease.isOdkProject(edit), "a Makefile OntoBoard's old scaffold wrote");

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
}
