package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a release asks of ODK's make, of a project's own release script, and of git.
 *
 * <p>A release is ODK's {@code prepare_release} - or the project's {@code release.sh}, when it
 * has one - and then a commit and a tag. Everything here is the deciding and the wording of
 * that; nothing is run.
 */
class ReleaseRunTest {

    private static final String EGG = "https://example.org/eggboil/eggboil";

    private static final String NFDICORE_SCRIPT = "\n\nVERSION=3.0.5\nPRIOR_VERSION=3.0.4\n"
            + "ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology \n"
            + "ANNOTATE_ONTOLOGY_VERSION=\"annotate -V $ONTBASE/$VERSION/\\$@ --annotation "
            + "owl:versionInfo $VERSION\"\n\n\nsh run.sh make clean\n\n"
            + "sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE ANNOTATE_ONTOLOGY_VERSION="
            + "\"$ANNOTATE_ONTOLOGY_VERSION\" prepare_release\n"
            + "#sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE prepare_release\n\n"
            + "sh run.sh make VERSION=$VERSION PRIOR_VERSION=$PRIOR_VERSION "
            + "update-ontology-annotations\n";

    private static File write(File folder, String name, String text) throws IOException {
        File file = new File(folder, name);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    // ------------------------------------------------------------------ make

    @Test
    void aPlainReleaseIsPrepareReleaseWithTheVersion() {
        assertEquals(Arrays.asList("make", "prepare_release", "VERSION=1.0.0"),
                ReleaseRun.makeArguments("1.0.0", VersionIriShape.of(VersionIriShape.Kind.ODK),
                        EGG, true));
    }

    /** As nfdicore's script hands it to make: one more variable on the command line. */
    @Test
    void anotherShapeIsOneMoreArgument() {
        assertEquals(Arrays.asList("make", "prepare_release", "VERSION=1.0.0",
                "ANNOTATE_ONTOLOGY_VERSION=annotate -V https://example.org/eggboil/eggboil/1.0.0/$@"
                        + " --annotation owl:versionInfo 1.0.0"),
                ReleaseRun.makeArguments("1.0.0",
                        VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE), EGG, true));
    }

    @Test
    void theFastOneWhenImportsAreNotRefreshed() {
        assertEquals(Arrays.asList("make", "prepare_release_fast", "VERSION=1.0.0"),
                ReleaseRun.makeArguments("1.0.0", VersionIriShape.of(VersionIriShape.Kind.ODK),
                        EGG, false));
    }

    @Test
    void aVersionThatIsNotOneIsRefusedBeforeMakeSeesIt() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseRun.makeArguments(
                "1.0.0 IMP=false", VersionIriShape.of(VersionIriShape.Kind.ODK), EGG, true));
        assertThrows(IllegalArgumentException.class, () -> ReleaseRun.makeArguments("",
                VersionIriShape.of(VersionIriShape.Kind.ODK), EGG, true));
    }

    // ------------------------------------------------------------------ the project's own script

    @Test
    void aProjectWithoutAScriptHasNone(@TempDir File ontology) {
        assertNull(ReleaseRun.scriptIn(ontology));
    }

    @Test
    void theScriptsThreeLinesAreRead(@TempDir File ontology) throws IOException {
        write(ontology, "release.sh", NFDICORE_SCRIPT);

        ReleaseRun.Script script = ReleaseRun.scriptIn(ontology);

        assertEquals("3.0.5", script.version());
        assertEquals("3.0.4", script.priorVersion());
        assertEquals("annotate -V $ONTBASE/$VERSION/\\$@ --annotation owl:versionInfo $VERSION",
                script.annotate());
    }

    /** The script is the project's. Only the values of those lines change, and nothing else. */
    @Test
    void onlyThoseLinesChange(@TempDir File ontology) throws IOException {
        write(ontology, "release.sh", NFDICORE_SCRIPT);
        ReleaseRun.Script script = ReleaseRun.scriptIn(ontology);

        String changed = script.withLines("3.1.0", "3.0.5", null);

        assertEquals(NFDICORE_SCRIPT.replace("VERSION=3.0.5\nPRIOR_VERSION=3.0.4\n",
                "VERSION=3.1.0\nPRIOR_VERSION=3.0.5\n"), changed);
        assertEquals(NFDICORE_SCRIPT, script.withLines("3.0.5", "3.0.4", null),
                "the same values, the same file");
    }

    /** A script saved on Windows keeps its line endings; a shell in the container minds them. */
    @Test
    void theScriptsLineEndingsAreKept(@TempDir File ontology) throws IOException {
        String windows = NFDICORE_SCRIPT.replace("\n", "\r\n");
        write(ontology, "release.sh", windows);

        ReleaseRun.Script script = ReleaseRun.scriptIn(ontology);

        assertEquals("3.0.5", script.version());
        assertEquals(windows.replace("VERSION=3.0.5\r\nPRIOR", "VERSION=3.1.0\r\nPRIOR"),
                script.withLines("3.1.0", "3.0.4", null));
        assertTrue(script.hasWindowsLineEndings());
    }

    @Test
    void aScriptWithoutAVersionLineSaysSo(@TempDir File ontology) throws IOException {
        write(ontology, "release.sh", "#!/bin/sh\nsh run.sh make prepare_release\n");

        ReleaseRun.Script script = ReleaseRun.scriptIn(ontology);

        assertNull(script.version());
        assertNull(script.priorVersion());
        assertNull(script.annotate());
        assertThrows(IllegalStateException.class, () -> script.withLines("1.0.0", null, null));
    }

    /** What is written into the script is a version: nothing a shell would run. */
    @Test
    void aValueThatWouldBeShellIsRefused(@TempDir File ontology) throws IOException {
        write(ontology, "release.sh", NFDICORE_SCRIPT);
        ReleaseRun.Script script = ReleaseRun.scriptIn(ontology);

        assertThrows(IllegalArgumentException.class,
                () -> script.withLines("3.1.0; rm -rf /work", "3.0.5", null));
        assertThrows(IllegalArgumentException.class,
                () -> script.withLines("3.1.0", "$(id)", null));
    }

    // ------------------------------------------------------------------ releases/<version>/

    /**
     * ODK's version IRIs point at {@code <ONTBASE>/releases/<version>/<file>}; a project
     * OntoBoard made keeps a copy of each release there. An ODK repository OntoBoard did not
     * make is released as its maintainers release it, and gets no folder it never had.
     */
    @Test
    void copiesAreForOntoBoardsProjectsOnly(@TempDir File temp) throws IOException {
        File made = new File(temp, "made");
        write(made, "src/ontology/eggboil-odk.yaml", Goldens.config("eggboil"));
        File theirs = new File(temp, "theirs");
        write(theirs, "src/ontology/nfdicore-odk.yaml", "id: nfdicore\ntitle: NFDIcore\n");
        File migrated = new File(temp, "migrated");
        write(migrated, "src/ontology/old-odk.yaml", "id: old\n");
        write(migrated, "releases/1.0.0/old.owl", "x");

        assertTrue(ReleaseRun.keepsReleasesFolder(made));
        assertFalse(ReleaseRun.keepsReleasesFolder(theirs));
        assertTrue(ReleaseRun.keepsReleasesFolder(migrated), "it has kept one so far");
    }

    @Test
    void theAssetsAreCopiedUnderTheVersion(@TempDir File root) throws IOException {
        File owl = write(root, "eggboil.owl", "owl");
        File base = write(root, "eggboil-base.obo", "obo");
        File subset = write(root, "subsets/breakfast.owl", "subset");

        List<File> copied = ReleaseRun.copyToReleases(root, "1.0.0",
                Arrays.asList(owl, base, subset));

        assertEquals(Arrays.asList(new File(root, "releases/1.0.0/eggboil.owl"),
                new File(root, "releases/1.0.0/eggboil-base.obo"),
                new File(root, "releases/1.0.0/subsets/breakfast.owl")), copied);
        assertArrayEquals("subset".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(copied.get(2).toPath()));
    }

    /** A version IRI cannot be taken back: a release that is there is not written over. */
    @Test
    void aReleaseThatIsThereIsNotWrittenOver(@TempDir File root) throws IOException {
        File owl = write(root, "eggboil.owl", "new");
        write(root, "releases/1.0.0/eggboil.owl", "released");

        assertEquals("releases/1.0.0 is there already", shortened(
                ReleaseRun.whyNotThisVersion(root, "1.0.0", Collections.<String>emptyList())));
        assertThrows(IllegalStateException.class,
                () -> ReleaseRun.copyToReleases(root, "1.0.0", Collections.singletonList(owl)));
        assertArrayEquals("released".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(new File(root, "releases/1.0.0/eggboil.owl").toPath()));
    }

    @Test
    void aVersionThatHasATagIsNotReleasedAgain(@TempDir File root) {
        assertNull(ReleaseRun.whyNotThisVersion(root, "1.1.0", Arrays.asList("v1.0.0")));
        assertEquals("the tag v1.0.0 is there already", shortened(
                ReleaseRun.whyNotThisVersion(root, "1.0.0", Arrays.asList("v1.1.0", "v1.0.0"))));
    }

    private static String shortened(String why) {
        assertTrue(why.contains("cannot be taken back"), why);
        return why.substring(0, why.indexOf(" already") + " already".length())
                .replaceFirst("^Version \\S+ has been released: ", "");
    }

    /** An asset that is not in the project is not copied from wherever it is. */
    @Test
    void anAssetOutsideTheProjectIsRefused(@TempDir File temp) throws IOException {
        File root = new File(temp, "project");
        assertTrue(root.mkdirs());
        File outside = write(temp, "elsewhere.owl", "x");

        assertThrows(IllegalArgumentException.class,
                () -> ReleaseRun.copyToReleases(root, "1.0.0", Collections.singletonList(outside)));
    }

    // ------------------------------------------------------------------ git

    /**
     * What is shown before it is run. ODK keeps release files out of git (.gitignore), so they
     * are added by name, with -f - ODK's own documented way; tracked files the release changed
     * go in; a file git has never heard of is listed and left out.
     */
    @Test
    void whatIsStaged(@TempDir File root) throws IOException {
        File owl = write(root, "eggboil.owl", "owl");
        File base = write(root, "eggboil-base.owl", "base");
        write(root, "releases/1.0.0/eggboil.owl", "owl");

        ReleaseRun.Staging staging = ReleaseRun.staging(root, "eggboil", "1.0.0",
                Arrays.asList(owl, base), Arrays.asList("?? src/ontology/Untitled.ttl",
                        "?? releases/", " M src/ontology/eggboil-edit.owl", "?? notes.txt"));

        assertEquals(Arrays.asList(
                Arrays.asList("git", "add", "-u", "--", "."),
                Arrays.asList("git", "add", "-f", "--", "eggboil.owl", "eggboil-base.owl"),
                Arrays.asList("git", "add", "--", "releases/1.0.0"),
                Arrays.asList("git", "commit", "-m", "release(1.0.0)"),
                Arrays.asList("git", "tag", "-a", "v1.0.0", "-m", "eggboil 1.0.0")),
                staging.commands());
        assertEquals(Arrays.asList("src/ontology/Untitled.ttl", "notes.txt"), staging.leftOut());
    }

    /** An ODK repository OntoBoard did not make has no releases/ folder to add. */
    @Test
    void withoutACopyThereIsNothingOfItToAdd(@TempDir File root) throws IOException {
        File owl = write(root, "nfdicore.owl", "owl");

        ReleaseRun.Staging staging = ReleaseRun.staging(root, "nfdicore", "3.0.5",
                Collections.singletonList(owl), Collections.<String>emptyList());

        assertEquals(4, staging.commands().size());
        assertFalse(staging.commands().toString().contains("releases"));
        assertEquals(Collections.<String>emptyList(), staging.leftOut());
    }

    /**
     * The commit goes to the branch that is checked out, and the person is shown which. git's
     * status names it in its first line - before the first commit too.
     */
    @Test
    void theBranchTheCommitGoesToIsReadOffTheStatus(@TempDir File root) throws IOException {
        List<File> assets = Collections.singletonList(write(root, "eggboil.owl", "owl"));

        assertEquals("main", ReleaseRun.staging(root, "eggboil", "1.0.0", assets,
                Arrays.asList("## main...origin/main [ahead 2]", "?? notes.txt")).branch());
        assertEquals("release-1", ReleaseRun.staging(root, "eggboil", "1.0.0", assets,
                Collections.singletonList("## release-1")).branch());
        assertEquals("master", ReleaseRun.staging(root, "eggboil", "1.0.0", assets,
                Collections.singletonList("## No commits yet on master")).branch());
        ReleaseRun.Staging unsaid = ReleaseRun.staging(root, "eggboil", "1.0.0", assets,
                Collections.<String>emptyList());
        assertNull(unsaid.branch());
        assertFalse(unsaid.onNoBranch(), "not known is not the same as none");
    }

    /** With no branch checked out the commit is on none, and only its tag leads back to it. */
    @Test
    void aDetachedHeadIsNoBranch(@TempDir File root) throws IOException {
        ReleaseRun.Staging staging = ReleaseRun.staging(root, "eggboil", "1.0.0",
                Collections.singletonList(write(root, "eggboil.owl", "owl")),
                Collections.singletonList("## HEAD (no branch)"));

        assertNull(staging.branch());
        assertTrue(staging.onNoBranch());
    }

    /** A file name git had to quote - a blank, an umlaut - is listed as the name it is. */
    @Test
    void aQuotedFileNameIsListedUnquoted(@TempDir File root) throws IOException {
        ReleaseRun.Staging staging = ReleaseRun.staging(root, "eggboil", "1.0.0",
                Collections.singletonList(write(root, "eggboil.owl", "owl")),
                Arrays.asList("?? \"my notes.txt\"", "?? \"B\\303\\244ume.txt\""));

        assertEquals(Arrays.asList("my notes.txt", "Bäume.txt"), staging.leftOut());
    }

    /**
     * make's arguments for another shape hold blanks and make's {@code $@}. In the transcript
     * they stand so that a shell reads them back as the one argument they are.
     */
    @Test
    void aCommandIsShownAsAShellReadsItBack() {
        assertEquals("make prepare_release VERSION=1.0.0 \"ANNOTATE_ONTOLOGY_VERSION=annotate "
                + "-V " + EGG + "/1.0.0/\\$@ --annotation owl:versionInfo 1.0.0\"",
                ReleaseRun.shown(ReleaseRun.makeArguments("1.0.0",
                        VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE), EGG, true)));
    }

    /**
     * The commands as the dialog shows them: one a line, and an argument a shell would not take
     * as one word - a blank in it, a parenthesis - in quotes, so that the lines can be pasted
     * into a terminal as they stand.
     */
    @Test
    void theCommandsAreShownAsTheyWouldBeTyped(@TempDir File root) throws IOException {
        File owl = write(root, "eggboil.owl", "owl");

        String shown = ReleaseRun.staging(root, "eggboil", "1.0.0", Collections.singletonList(owl),
                Collections.<String>emptyList()).shown();

        assertEquals("git add -u -- .\ngit add -f -- eggboil.owl\ngit commit -m \"release(1.0.0)\"\n"
                + "git tag -a v1.0.0 -m \"eggboil 1.0.0\"", shown);
    }

    // ------------------------------------------------------------------ review of 1.153.0

    /**
     * A project that is a folder of a larger repository: only what is in the project is
     * staged - "git add -u" without a path takes the whole repository, somebody's unrelated
     * work with it - and what is left out is named as the project names it. git's status
     * names files from the repository's top.
     */
    @Test
    void inAFolderOfALargerRepositoryOnlyTheProjectIsStagedAndNamed(@TempDir File repository)
            throws IOException {
        File root = new File(repository, "ontologies/eggboil");
        File owl = write(root, "eggboil.owl", "owl");
        write(root, "releases/1.0.0/eggboil.owl", "owl");

        ReleaseRun.Staging staging = ReleaseRun.staging(root, repository, "eggboil", "1.0.0",
                Collections.singletonList(owl), Arrays.asList("## main",
                        "?? ontologies/eggboil/releases/", "?? ontologies/eggboil/notes.txt",
                        " M ontologies/eggboil/src/ontology/eggboil-edit.owl",
                        " M website/index.html", "?? website/draft.html"));

        assertEquals(Arrays.asList("git", "add", "-u", "--", "."), staging.commands().get(0));
        assertEquals(Collections.singletonList("notes.txt"), staging.leftOut(),
                "releases/ is added, and what lies elsewhere in the repository is not the "
                        + "release's to speak of");
    }

    /**
     * A release cleans first, and ODK's clean empties tmp/, mirror/ and target/. A file there
     * that git tracks - src/ontology/tmp/README.md, which older ODKs seeded - would be staged
     * as deleted and go into the release's commit. It is put back first; a file that is gone
     * anywhere else is the person's own doing and is staged as it is.
     */
    @Test
    void aTrackedFileThatCleaningRemovedIsPutBackAndNotCommittedAsDeleted(@TempDir File root)
            throws IOException {
        File owl = write(root, "eggboil.owl", "owl");

        ReleaseRun.Staging staging = ReleaseRun.staging(root, "eggboil", "1.0.0",
                Collections.singletonList(owl), Arrays.asList("## main",
                        " D src/ontology/tmp/README.md", " D src/ontology/mirror/keep.txt",
                        " D src/ontology/old-notes.md", " M src/ontology/eggboil-edit.owl"));

        assertEquals(Arrays.asList("git", "checkout", "--", "src/ontology/tmp/README.md",
                "src/ontology/mirror/keep.txt"), staging.commands().get(0));
        assertEquals(Arrays.asList("git", "add", "-u", "--", "."), staging.commands().get(1));
    }

    /**
     * A copy that fails part of the way leaves no folder: a folder under releases/ says the
     * version has been released, and the next try would be refused for it.
     */
    @Test
    void aCopyThatFailsLeavesNothingOfTheReleaseBehind(@TempDir File root) throws IOException {
        final File owl = write(root, "eggboil.owl", "owl");
        final File project = root;

        assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                // The same file twice: the second copy finds the first in its way.
                ReleaseRun.copyToReleases(project, "1.0.0", Arrays.asList(owl, owl));
            }
        });

        assertFalse(new File(root, "releases/1.0.0").exists());
        assertNull(ReleaseRun.whyNotThisVersion(root, "1.0.0", Collections.<String>emptyList()));
    }

    /**
     * "Changes nothing else in it" holds for a script in any encoding: read as UTF-8, a
     * comment in Latin-1 came back with every such letter replaced.
     */
    @Test
    void aScriptKeepsEveryByteButTheVersionWhateverItsEncoding(@TempDir File ontology)
            throws IOException {
        String before = "#!/bin/sh\n# Ver\u00f6ffentlichung\nVERSION=1.0.0\nmake prepare_release\n";
        String after = before.replace("VERSION=1.0.0", "VERSION=1.0.1");
        for (java.nio.charset.Charset encoding : new java.nio.charset.Charset[] {
            StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8}) {
            File file = new File(ontology, "release.sh");
            Files.write(file.toPath(), before.getBytes(encoding));

            ReleaseRun.Script script = ReleaseRun.scriptIn(ontology);
            script.write(script.withLines("1.0.1", null, null));

            assertArrayEquals(after.getBytes(encoding), Files.readAllBytes(file.toPath()),
                    encoding.name());
            assertEquals("1.0.1", ReleaseRun.scriptIn(ontology).version());
        }
    }

    /** 1.0 and 1.0.0 are one number: released once, under whichever spelling (review of 1.153.0). */
    @Test
    void aVersionThatIsAnotherSpellingOfAReleasedOneIsRefused(@TempDir File root)
            throws IOException {
        write(root, "releases/1.0.0/eggboil.owl", "owl");

        String byFolder = ReleaseRun.whyNotThisVersion(root, "1.0",
                Collections.<String>emptyList());
        String byTag = ReleaseRun.whyNotThisVersion(new File(root, "another"), "2.0",
                Collections.singletonList("v2.0.0"));

        assertTrue(byFolder != null && byFolder.contains("1.0.0"), String.valueOf(byFolder));
        assertTrue(byTag != null && byTag.contains("2.0.0"), String.valueOf(byTag));
        assertNull(ReleaseRun.whyNotThisVersion(root, "1.0.1", Collections.<String>emptyList()));
    }
}
