package de.fizkarlsruhe.ise.ontoboard.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The everyday git commands, and the judgement around them.
 *
 * <p>Cloning was the whole of the git integration, so everything else this plugin does to an ODK
 * project - minting a term, allocating an ID range, writing an import module, cutting a release -
 * produced changes somebody had to leave Protege to record.
 *
 * <p>Two things here are worth more than the command strings. The porcelain status format, because
 * it is the one git promises not to change and parsing the human-readable one is how a tool breaks
 * on somebody else's machine a year later. And the unsaved-changes warning, because Protege holds
 * edits in memory while git reads the disk, so a commit made with unsaved work succeeds and simply
 * does not contain it.
 */
class GitRepoTest {

    // ---------- commands ----------

    /** The human-readable format is explicitly not stable between versions. */
    @Test
    void statusIsAskedForInTheFormatGitPromisesNotToChange() {
        assertTrue(GitRepo.statusCommand().contains("--porcelain=v1"),
                GitRepo.statusCommand().toString());
        assertTrue(GitRepo.statusCommand().contains("--branch"),
                "the branch header is where ahead/behind come from");
    }

    /**
     * A new import module, a new release directory and a first catalog are exactly what this
     * plugin produces, and a commit that silently left them out would look like it worked.
     */
    @Test
    void committingIncludesFilesGitHasNeverSeen() {
        assertTrue(GitRepo.stageCommand().contains("-A"), GitRepo.stageCommand().toString());
    }

    @Test
    void aCommitWithoutAMessageIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GitRepo.commitCommand(""));
        assertThrows(IllegalArgumentException.class, () -> GitRepo.commitCommand("   "));
        assertThrows(IllegalArgumentException.class, () -> GitRepo.commitCommand(null));
    }

    /**
     * Always fast-forward. A merge or rebase can conflict, and resolving an OWL conflict is not
     * something a menu item should begin without being asked.
     */
    @Test
    void pullingNeverMergesOrRebases() {
        assertTrue(GitRepo.pullCommand().contains("--ff-only"),
                GitRepo.pullCommand().toString());
    }

    @Test
    void aBranchWithNoUpstreamGetsOneWhenItIsPushed() {
        assertTrue(GitRepo.pushNewBranchCommand("issue-12").contains("--set-upstream"));
        assertTrue(GitRepo.pushNewBranchCommand("issue-12").contains("issue-12"));
        assertFalse(GitRepo.pushCommand().contains("--set-upstream"));
    }

    @Test
    void aBranchNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> GitRepo.createBranchCommand(" "));
        assertEquals(Arrays.asList("git", "checkout", "-b", "issue-12"),
                GitRepo.createBranchCommand("issue-12"));
    }

    // ---------- reading the status ----------

    @Test
    void aCleanCheckoutReadsAsClean() {
        GitRepo.Status status = GitRepo.parseStatus(
                Arrays.asList("## main...origin/main"));

        assertTrue(status.isClean());
        assertEquals("main", status.getBranch());
        assertTrue(status.hasUpstream());
        assertEquals(0, status.getAhead());
        assertEquals(0, status.getBehind());
    }

    @Test
    void changedAndNewFilesAreToldApart() {
        GitRepo.Status status = GitRepo.parseStatus(Arrays.asList(
                "## main...origin/main",
                " M src/ontology/mwo-edit.owl",
                "M  src/ontology/catalog-v001.xml",
                "?? src/ontology/imports/iao_import.owl"));

        assertEquals(Arrays.asList("src/ontology/mwo-edit.owl", "src/ontology/catalog-v001.xml"),
                status.getChanged());
        assertEquals(Arrays.asList("src/ontology/imports/iao_import.owl"),
                status.getUntracked());
        assertFalse(status.isClean());
    }

    /**
     * A conflicted file is not a changed file, and the difference is destructive.
     *
     * <p>Until 1.123.0 the parser had two buckets - {@code ??} and everything else - so
     * {@code UU ontology.owl} read as an ordinary change. The commit path then ran
     * {@code git add -A} and {@code git commit}, which exit 0 on a conflicted tree and record a
     * blob containing {@code <<<<<<< HEAD}; the plugin reported "Committed 1 files." Reproduced
     * on a real conflicted repository. An .owl full of conflict markers does not parse, so the
     * commit destroys the ontology and says it worked.
     */
    @Test
    void anUnmergedFileIsNotCountedAsAnOrdinaryChange() {
        GitRepo.Status status = GitRepo.parseStatus(Arrays.asList(
                "## main...origin/main",
                "UU src/ontology/mwo-edit.owl",
                " M src/ontology/catalog-v001.xml",
                "?? notes.txt"));

        assertEquals(Arrays.asList("src/ontology/mwo-edit.owl"), status.getConflicted());
        assertEquals(Arrays.asList("src/ontology/catalog-v001.xml"), status.getChanged(),
                "the conflicted file must not also be reported as changed");
        assertEquals(Arrays.asList("notes.txt"), status.getUntracked());
        assertTrue(status.hasConflicts());
        assertFalse(status.isClean());
    }

    /**
     * All seven unmerged codes, because DD has no U in it.
     *
     * <p>Spelling the set out is the point: a check for "contains a U" would file a
     * both-deleted as an ordinary change, and that is the one of the seven whose resolution is
     * least obvious to the person who has to make it.
     */
    @Test
    void everyUnmergedCodeGitDocumentsIsRecognised() {
        for (String code : new String[] {"UU", "AA", "DD", "AU", "UA", "DU", "UD"}) {
            GitRepo.Status status = GitRepo.parseStatus(Arrays.asList(
                    "## main", code + " ontology.owl"));

            assertEquals(Arrays.asList("ontology.owl"), status.getConflicted(),
                    code + " is an unmerged state and git-status(1) lists it as one");
            assertTrue(status.getChanged().isEmpty(), code + " must not also count as changed");
        }
    }

    /** A clean tree has no conflicts, and nothing asserts one into existence. */
    @Test
    void anOrdinaryTreeReportsNoConflicts() {
        GitRepo.Status status = GitRepo.parseStatus(Arrays.asList(
                "## main...origin/main", " M a.owl", "?? b.owl"));

        assertFalse(status.hasConflicts());
        assertTrue(status.getConflicted().isEmpty());
    }

    /** Pushing before pulling is the commonest way to be rejected, so this has to be visible. */
    @Test
    void aheadAndBehindAreRead() {
        GitRepo.Status status = GitRepo.parseStatus(
                Arrays.asList("## main...origin/main [ahead 2, behind 3]"));

        assertEquals(2, status.getAhead());
        assertEquals(3, status.getBehind());
    }

    @Test
    void aheadOnItsOwnIsRead() {
        assertEquals(4, GitRepo.parseStatus(
                Arrays.asList("## main...origin/main [ahead 4]")).getAhead());
        assertEquals(0, GitRepo.parseStatus(
                Arrays.asList("## main...origin/main [ahead 4]")).getBehind());
    }

    /** A branch that has never been pushed needs a different push command. */
    @Test
    void aBranchWithNoUpstreamIsRecognised() {
        GitRepo.Status status = GitRepo.parseStatus(Arrays.asList("## issue-12"));

        assertEquals("issue-12", status.getBranch());
        assertFalse(status.hasUpstream());
    }

    @Test
    void emptyOutputIsNotACrash() {
        assertTrue(GitRepo.parseStatus(Collections.<String>emptyList()).isClean());
        assertTrue(GitRepo.parseStatus(null).isClean());
    }

    // ---------- the warning that saves work ----------

    /**
     * The trap that costs real work: Protege holds edits in memory, git reads the disk, and a
     * commit made with unsaved changes succeeds while simply not containing them.
     */
    @Test
    void committingWithUnsavedChangesIsWarnedAboutBeforehand() {
        String warning = GitRepo.unsavedWarning(true);

        assertNotNull(warning);
        assertTrue(warning.contains("git commits what is on disk"), warning);
        assertTrue(warning.contains("save first"), warning);
    }

    @Test
    void aSavedOntologyNeedsNoWarning() {
        assertNull(GitRepo.unsavedWarning(false));
    }

    // ---------- failures a user can act on ----------

    @Test
    void nothingToCommitPointsAtUnsavedWorkAsTheLikelyCause() {
        String explanation = GitRepo.explain("commit", 1,
                Arrays.asList("nothing to commit, working tree clean"));

        assertTrue(explanation.contains("Protege has saved"), explanation);
    }

    /** Stops a commit dead and has nothing to do with the repository. */
    @Test
    void noConfiguredIdentityGivesTheExactCommandsToFixIt() {
        String explanation = GitRepo.explain("commit", 128,
                Arrays.asList("*** Please tell me who you are.", "fatal: empty ident name"));

        assertTrue(explanation.contains("git config --global user.name"), explanation);
        assertTrue(explanation.contains("user.email"), explanation);
    }

    @Test
    void aRejectedPushSaysToPullFirst() {
        String explanation = GitRepo.explain("push", 1,
                Arrays.asList("! [rejected] main -> main (non-fast-forward)",
                        "error: failed to push some refs"));

        assertTrue(explanation.contains("Pull first"), explanation);
    }

    /** Merging an ontology is not something to start on somebody's behalf. */
    @Test
    void aPullThatCannotFastForwardSaysToSortItOutInATerminal() {
        String explanation = GitRepo.explain("pull", 128,
                Arrays.asList("fatal: Not possible to fast-forward, aborting."));

        assertTrue(explanation.contains("terminal"), explanation);
        assertTrue(explanation.contains("diverged") || explanation.contains("commits here"),
                explanation);
    }

    @Test
    void anUnpushedBranchIsPointedAtTheRightCommand() {
        String explanation = GitRepo.explain("push", 128,
                Arrays.asList("fatal: The current branch issue-12 has no upstream branch."));

        assertTrue(explanation.contains("Publish this branch"), explanation);
    }

    @Test
    void refusedCredentialsSayNothingHereCanPrompt() {
        String explanation = GitRepo.explain("push", 128,
                Arrays.asList("fatal: could not read Username for 'https://github.com'"));

        assertTrue(explanation.contains("Nothing here can prompt"), explanation);
        assertTrue(explanation.contains("terminal"), explanation);
    }

    @Test
    void anUnrecognisedFailureCarriesGitsOwnLastWord() {
        String explanation = GitRepo.explain("push", 3,
                Arrays.asList("some noise", "fatal: something new"));

        assertTrue(explanation.contains("something new"), explanation);
        assertTrue(explanation.contains("exit 3"), explanation);
    }

    @Test
    void successNeedsNoExplanation() {
        assertNull(GitRepo.explain("push", 0, Arrays.asList("Everything up-to-date")));
    }

    // ---------- finding the repository ----------

    @Test
    void theRepositoryIsFoundFromAFileDeepInsideIt(@TempDir File root) throws Exception {
        new File(root, ".git").mkdirs();
        File deep = new File(root, "src/ontology");
        deep.mkdirs();
        File ontology = new File(deep, "mwo-edit.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        assertEquals(root.getCanonicalFile(),
                GitRepo.repositoryFor(ontology).getCanonicalFile());
    }

    @Test
    void somethingOutsideAnyCheckoutHasNoRepository(@TempDir File root) throws Exception {
        File ontology = new File(root, "loose.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        assertNull(GitRepo.repositoryFor(ontology));
        assertNull(GitRepo.repositoryFor(null));
    }

    // ---------- what is deliberately absent ----------

    /**
     * Merge, rebase, reset and force each need a conflict resolved or a history rewritten. Their
     * absence is a decision, and a test is the only thing that keeps a decision from being
     * quietly undone.
     */
    @Test
    void theDangerousHalfOfGitIsNotOffered() {
        List<List<String>> offered = Arrays.asList(
                GitRepo.statusCommand(), GitRepo.stageCommand(), GitRepo.commitCommand("m"),
                GitRepo.pullCommand(), GitRepo.pushCommand(),
                GitRepo.pushNewBranchCommand("b"), GitRepo.createBranchCommand("b"));

        for (List<String> command : offered) {
            for (String dangerous : new String[] {"merge", "rebase", "reset", "--force", "-f"}) {
                assertFalse(command.contains(dangerous),
                        command + " contains " + dangerous);
            }
        }
    }

    /**
     * Which stages git kept, read from real {@code git ls-files -u} output.
     *
     * <p>The lines below are copied from actual merges: three stages for a content conflict, two
     * for a file one side deleted, two with no ancestor for a file both sides created.
     *
     * <p>THE FIXTURES CONTAIN REAL TAB CHARACTERS, because git's output does and the parser finds
     * the stage number by looking for one. An editor that converts them to spaces will make these
     * tests fail rather than pass quietly, which is the right way round.
     */
    @Test
    void theStagesGitKeptAreReadFromItsOwnListing() {
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2, 3)),
                GitRepo.parseUnmergedStages(Arrays.asList(
                        "100644 043efd375c53041a03d2fbd1254a1a649442240d 1	o.ttl",
                        "100644 dbbc0484228369cb328b384a774fd237898c7b08 2	o.ttl",
                        "100644 4636212c1b44ed84d2db0fb81a66811f1822854c 3	o.ttl")));

        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 3)),
                GitRepo.parseUnmergedStages(Arrays.asList(
                        "100644 043efd375c53041a03d2fbd1254a1a649442240d 1	o.ttl",
                        "100644 4636212c1b44ed84d2db0fb81a66811f1822854c 3	o.ttl")),
                "a file this branch deleted keeps the ancestor and theirs, and no stage 2");

        assertEquals(new TreeSet<Integer>(Arrays.asList(2, 3)),
                GitRepo.parseUnmergedStages(Arrays.asList(
                        "100644 dbbc0484228369cb328b384a774fd237898c7b08 2	o.ttl",
                        "100644 4636212c1b44ed84d2db0fb81a66811f1822854c 3	o.ttl")),
                "a file both sides created has no common ancestor to keep");

        assertTrue(GitRepo.parseUnmergedStages(Collections.<String>emptyList()).isEmpty(),
                "a file git does not consider conflicted keeps nothing");
    }

    /**
     * A path with a space in it is still read correctly.
     *
     * <p>The stage is the last field BEFORE the tab. A reader that split the whole line on
     * whitespace would take a word of the path for a stage number on exactly the projects where
     * a wrong answer is hardest to notice.
     */
    @Test
    void aPathWithSpacesDoesNotConfuseTheStageNumber() {
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)),
                GitRepo.parseUnmergedStages(Arrays.asList(
                        "100644 043efd375c53041a03d2fbd1254a1a649442240d 1	src/my ontology.owl",
                        "100644 dbbc0484228369cb328b384a774fd237898c7b08 2	src/my ontology.owl")));
    }

    /** Anything that is not a stage line is ignored rather than guessed at. */
    @Test
    void outputThatIsNotAStageListingIsIgnored() {
        assertTrue(GitRepo.parseUnmergedStages(Arrays.asList(
                "fatal: not a git repository",
                "",
                "100644 043efd375c53041a03d2fbd1254a1a649442240d 9	o.ttl",
                "100644 043efd375c53041a03d2fbd1254a1a649442240d x	o.ttl",
                "no tab at all here")).isEmpty(),
                "9 is not a stage, x is not a number, and neither is an error message");
    }

    /** The listing is asked for one path, quoted past any option that looks like a flag. */
    @Test
    void theStageListingIsAskedForOnePathOnly() {
        List<String> command = GitRepo.unmergedStagesCommand("src/ontology/mwo-edit.owl");

        assertEquals(Arrays.asList("git", "ls-files", "-u", "--",
                "src/ontology/mwo-edit.owl"), command);
        assertThrows(IllegalArgumentException.class, () -> GitRepo.unmergedStagesCommand(" "));
    }

    /**
     * A conflicted path git quoted is unquoted, so the report is not silently skipped.
     *
     * <p>MEASURED on a real merge: a conflicted {@code src/my ontology.owl} comes back as
     * {@code UU "src/my ontology.owl"}, quoted for nothing but the space. Left quoted, the path
     * ends in a double quote rather than {@code .owl}, so ConflictReview decided it was not an
     * ontology and reported nothing at all - a silent failure on any project whose folders have
     * spaces in them.
     */
    @Test
    void aQuotedConflictedPathIsUnquoted() {
        GitRepo.Status status = GitRepo.parseStatus(Arrays.asList(
                "## master", "UU \"src/my ontology.owl\""));

        assertEquals(Arrays.asList("src/my ontology.owl"), status.getConflicted());
    }

    /**
     * The octal escapes git writes for a non-ASCII path are decoded as the UTF-8 they are.
     *
     * <p>git prints each byte of a UTF-8 character as its own octal escape, so a path has to be
     * decoded byte by byte and converted once. Decoding each escape separately turns the umlaut
     * in a German term into two broken characters - and a wrong path is a path `git show` cannot
     * find, which is the silent-skip failure again by another route.
     *
     * <p>THE FIXTURE IS BUILT FROM EXPLICIT CHARACTERS rather than written as a string literal.
     * Java reads {@code \\303} in source as an octal escape of its own, so a literal spelling of
     * git's output silently becomes the DECODED character and the test stops testing anything.
     * That is not hypothetical: the first version of this test was written that way and failed
     * against a function measured to be correct.
     */
    @Test
    void anOctalEscapedPathIsDecodedAsUtf8() {
        String slash = String.valueOf((char) 92);
        String quote = String.valueOf((char) 34);

        assertEquals("src/Gr" + ((char) 0xF6) + ((char) 0xDF) + "e.owl",
                GitRepo.unquotePath(quote + "src/Gr" + slash + "303" + slash + "266"
                        + slash + "303" + slash + "237" + "e.owl" + quote),
                "the four octal escapes are two characters of UTF-8, not four of Latin-1");

        assertEquals("a" + ((char) 9) + "b.owl",
                GitRepo.unquotePath(quote + "a" + slash + "t" + "b.owl" + quote));
        assertEquals("say " + quote + "hello" + quote + ".owl",
                GitRepo.unquotePath(quote + "say " + slash + quote + "hello" + slash + quote
                        + ".owl" + quote));
    }

    /** A path git did not quote is returned untouched, quotes and all if it really has them. */
    @Test
    void anUnquotedPathIsLeftAlone() {
        assertEquals("src/ontology/mwo-edit.owl",
                GitRepo.unquotePath("src/ontology/mwo-edit.owl"));
        assertEquals("", GitRepo.unquotePath(""));
        assertNull(GitRepo.unquotePath(null));
    }
}
