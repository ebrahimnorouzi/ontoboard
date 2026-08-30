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
}
