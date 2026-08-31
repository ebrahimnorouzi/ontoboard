package de.fizkarlsruhe.ise.ontoboard.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The decisions around cloning, without cloning anything.
 *
 * <p>The failure messages are most of what is being tested, and they are most of the value. Git's
 * own are written for people who already know git: "Repository not found" is what it says for a
 * private repository, for a typo and for one that has been deleted, and a user looking at that
 * repository in their browser reads it as the tool being broken.
 */
class GitCloneTest {

    private static final GitHubUrl MWO =
            GitHubUrl.parse("https://github.com/ISE-FIZKarlsruhe/mwo");

    private static GitClone.Outcome failedWith(String output) {
        return new GitClone.Outcome(128, output);
    }

    // ---------- the command ----------

    @Test
    void theCommandClonesOverHttpsIntoTheGivenDirectory() {
        List<String> command = GitClone.cloneCommand(MWO, new File("/tmp/mwo"));

        assertEquals("git", command.get(0));
        assertEquals("clone", command.get(1));
        assertTrue(command.contains("https://github.com/ISE-FIZKarlsruhe/mwo.git"), command + "");
        assertEquals(new File("/tmp/mwo").getAbsolutePath(), command.get(command.size() - 1));
    }

    /**
     * Never, even when the link named one. The ref taken from a GitHub URL is a guess - there is
     * no way to tell offline where a ref ends and a path begins - and a wrong guess handed to
     * git clone fails with "Remote branch 'feature' not found", naming a branch the user never
     * typed about a repository where feature/x exists and is exactly what they asked for.
     */
    @Test
    void theCloneNeverGuessesABranch() {
        assertFalse(GitClone.cloneCommand(MWO, new File("/tmp/mwo")).contains("--branch"));
        assertFalse(GitClone.cloneCommand(
                GitHubUrl.parse("https://github.com/ISE-FIZKarlsruhe/mwo/tree/issue-42"),
                new File("/tmp/mwo")).contains("--branch"),
                "the branch is resolved after cloning, against the branches that exist");
    }

    // ---------- resolving the ref against what the repository has ----------

    @Test
    void theBranchesAreReadWithoutTheRemotePrefix() {
        List<String> branches = GitClone.remoteBranches(
                "origin/main\norigin/feature/x\norigin/release/2024-01-01\n");

        assertEquals(Arrays.asList("main", "feature/x", "release/2024-01-01"), branches);
    }

    /** origin/HEAD is a symbolic ref; offering it would let a link resolve to a non-branch. */
    @Test
    void theSymbolicHeadIsNotOfferedAsABranch() {
        List<String> branches = GitClone.remoteBranches(
                "origin/HEAD -> origin/main\norigin/main\n");

        assertEquals(Arrays.asList("main"), branches);
    }

    @Test
    void noBranchesAtAllIsAnEmptyListRatherThanAFailure() {
        assertTrue(GitClone.remoteBranches("").isEmpty());
        assertTrue(GitClone.remoteBranches(null).isEmpty());
        assertTrue(GitClone.remoteBranches("   \n \n").isEmpty());
    }

    @Test
    void theCommandsForResolvingAreTheOnesGitUnderstands() {
        assertEquals(Arrays.asList("git", "branch", "-r", "--format=%(refname:short)"),
                GitClone.remoteBranchesCommand());
        assertEquals(Arrays.asList("git", "checkout", "feature/x"),
                GitClone.checkoutCommand("feature/x"));
    }

    @Test
    void theCheckoutIsNamedAfterTheRepository() {
        assertEquals("mwo", GitClone.directoryFor(new File("/projects"), MWO).getName());
    }

    // ---------- recognising a checkout that is already here ----------

    /**
     * By its origin, not its directory name. Two repositories called mwo from different owners are
     * not the same repository, and pulling one into the other's directory is a mess nobody
     * diagnoses quickly.
     */
    @Test
    void anExistingCheckoutIsRecognisedByItsOrigin(@TempDir File directory) throws Exception {
        gitConfigWithOrigin(directory, "https://github.com/ISE-FIZKarlsruhe/mwo.git");

        assertTrue(GitClone.isCheckoutOf(directory, MWO));
    }

    @Test
    void anSshOriginIsRecognisedToo(@TempDir File directory) throws Exception {
        gitConfigWithOrigin(directory, "git@github.com:ISE-FIZKarlsruhe/mwo.git");

        assertTrue(GitClone.isCheckoutOf(directory, MWO));
    }

    @Test
    void someoneElsesRepositoryOfTheSameNameIsNotAMatch(@TempDir File directory) throws Exception {
        gitConfigWithOrigin(directory, "https://github.com/someone-else/mwo.git");

        assertFalse(GitClone.isCheckoutOf(directory, MWO));
    }

    @Test
    void aDirectoryThatIsNotAGitCheckoutIsNotAMatch(@TempDir File directory) {
        assertFalse(GitClone.isCheckoutOf(directory, MWO));
        assertFalse(GitClone.isCheckoutOf(new File(directory, "nothing-here"), MWO));
    }

    private static void gitConfigWithOrigin(File directory, String url) throws IOException {
        gitConfig(directory, "[remote \"origin\"]\n\turl = " + url + "\n");
    }

    private static void gitConfig(File directory, String contents) throws IOException {
        File config = new File(new File(directory, ".git"), "config");
        config.getParentFile().mkdirs();
        Files.write(config.toPath(), contents.getBytes("UTF-8"));
    }

    /**
     * The standard OBO contribution workflow: clone your own fork, add upstream as a second
     * remote. A substring search over the config calls that a checkout of upstream - so the user
     * is told "already cloned", their fork is pulled and opened, and the result names a
     * repository they are not on. They then edit and push believing otherwise.
     */
    @Test
    void aForkWithAnUpstreamRemoteIsNotACheckoutOfUpstream(@TempDir File directory)
            throws Exception {
        gitConfig(directory,
                "[remote \"origin\"]\n\turl = https://github.com/ebrahimnorouzi/mwo.git\n"
                        + "[remote \"upstream\"]\n"
                        + "\turl = https://github.com/ISE-FIZKarlsruhe/mwo.git\n");

        assertFalse(GitClone.isCheckoutOf(directory, MWO),
                "the upstream remote was mistaken for the origin");
        assertTrue(GitClone.isCheckoutOf(directory,
                GitHubUrl.parse("https://github.com/ebrahimnorouzi/mwo")),
                "its own origin should still match");
    }

    /** A submodule url in the config is somebody else's repository, not this checkout. */
    @Test
    void aSubmoduleUrlIsNotTheOrigin(@TempDir File directory) throws Exception {
        gitConfig(directory,
                "[remote \"origin\"]\n\turl = https://github.com/someone/other.git\n"
                        + "[submodule \"vendor/mwo\"]\n"
                        + "\turl = https://github.com/ISE-FIZKarlsruhe/mwo.git\n");

        assertFalse(GitClone.isCheckoutOf(directory, MWO));
    }

    /** owner/mwo must not match owner/mwo-tools. */
    @Test
    void theMatchEndsAtTheRepositoryName(@TempDir File directory) throws Exception {
        gitConfigWithOrigin(directory, "https://github.com/ISE-FIZKarlsruhe/mwo-tools.git");

        assertFalse(GitClone.isCheckoutOf(directory, MWO));
    }

    @Test
    void theOriginIsReadOutOfTheConfigSection(@TempDir File directory) throws Exception {
        gitConfig(directory,
                "[core]\n\tbare = false\n"
                        + "[remote \"origin\"]\n"
                        + "\tfetch = +refs/heads/*:refs/remotes/origin/*\n"
                        + "\turl = https://github.com/ISE-FIZKarlsruhe/mwo.git\n");

        assertEquals("https://github.com/ISE-FIZKarlsruhe/mwo.git",
                GitClone.originOf(directory));
    }

    /** A worktree or submodule has .git as a file; refusing beats pulling into somebody's tree. */
    @Test
    void aCheckoutWhoseGitIsAFileIsNotClaimed(@TempDir File directory) throws Exception {
        Files.write(new File(directory, ".git").toPath(),
                "gitdir: /somewhere/else/.git/worktrees/x".getBytes("UTF-8"));

        assertFalse(GitClone.isCheckoutOf(directory, MWO));
        assertNull(GitClone.originOf(directory));
    }

    // ---------- failures a user can act on ----------

    /**
     * The commonest one by far, and the most misleading. Someone looking at the repository in
     * their browser is told it does not exist.
     */
    @Test
    void repositoryNotFoundExplainsThatItIsUsuallyPrivate() {
        String message = GitClone.describeFailure(MWO,
                failedWith("remote: Repository not found.\nfatal: repository not found"));

        assertTrue(message.contains("private"), message);
        assertTrue(message.contains("ISE-FIZKarlsruhe/mwo"), message);
        assertTrue(message.toLowerCase().contains("credential") || message.contains("gh"),
                "it has to say what to do about it: " + message);
    }

    @Test
    void beingOfflineSaysSoRatherThanBlamingTheRepository() {
        String message = GitClone.describeFailure(MWO,
                failedWith("fatal: unable to access 'https://github.com/x/y.git/': "
                        + "Could not resolve host: github.com"));

        assertTrue(message.contains("Could not reach github.com"), message);
        assertTrue(message.contains("proxy"), "a proxy is the other common cause: " + message);
    }

    @Test
    void refusedCredentialsSayWhatGitWants() {
        String message = GitClone.describeFailure(MWO,
                failedWith("fatal: Authentication failed for 'https://github.com/x/y.git/'"));

        assertTrue(message.toLowerCase().contains("token")
                || message.toLowerCase().contains("credential"), message);
    }

    /**
     * It must not name a branch. The one it used to print came from splitting the URL at the
     * first slash, so on a slash-bearing ref it named a branch the user never typed.
     */
    @Test
    void aMissingBranchDoesNotInventAName() {
        GitHubUrl onABranch = GitHubUrl.parse(
                "https://github.com/ISE-FIZKarlsruhe/mwo/tree/feature/x");

        String message = GitClone.describeFailure(onABranch,
                failedWith("fatal: Remote branch feature not found in upstream origin"));

        assertFalse(message.contains("'feature'"),
                "it named a branch that was never asked for: " + message);
        assertTrue(message.contains("ISE-FIZKarlsruhe/mwo"), message);
    }

    @Test
    void aDirectoryInTheWaySaysToChooseAnother() {
        String message = GitClone.describeFailure(MWO, failedWith(
                "fatal: destination path 'mwo' already exists and is not an empty directory."));

        assertTrue(message.toLowerCase().contains("empty"), message);
    }

    /** git missing is a setup problem, and saying "exit 127" would send someone nowhere. */
    @Test
    void gitNotBeingInstalledIsExplainedAsSetup() {
        String message = GitClone.describeFailure(MWO,
                new GitClone.Outcome(127, "Cannot run program \"git\""));

        assertTrue(message.contains("PATH"), message);
        assertTrue(message.contains("File > Open"),
                "there is a way round it and it should be offered: " + message);
    }

    /**
     * git says "Permission denied" for a folder it cannot create as readily as for a key it
     * cannot use. Blaming GitHub sends the user to configure an access token for a public
     * repository, which will not help and cannot be undone by trying harder.
     */
    @Test
    void aFolderTheUserCannotWriteIsNotBlamedOnGitHub() {
        String message = GitClone.describeFailure(MWO, failedWith(
                "fatal: could not create work tree dir 'C:/Program Files/mwo': Permission denied"));

        assertTrue(message.contains("could not write to that folder"), message);
        assertTrue(message.contains("nothing to do with GitHub"), message);
        assertFalse(message.toLowerCase().contains("token"),
                "a local permission problem must not send anybody to make a token: " + message);
    }

    /** An expired token gives a 401 wrapped in "unable to access" - a credentials problem. */
    @Test
    void anExpiredTokenIsReportedAsCredentialsRatherThanAsTheNetwork() {
        String message = GitClone.describeFailure(MWO, failedWith(
                "fatal: unable to access 'https://github.com/ISE-FIZKarlsruhe/mwo.git/': "
                        + "The requested URL returned error: 401"));

        assertTrue(message.toLowerCase().contains("credential"), message);
        assertFalse(message.contains("Could not reach github.com"),
                "401 is not a connectivity problem: " + message);
    }

    /** A genuine SSH key refusal still reads as credentials. */
    @Test
    void arefusedKeyIsStillReportedAsCredentials() {
        String message = GitClone.describeFailure(MWO,
                failedWith("git@github.com: Permission denied (publickey)."));

        assertTrue(message.toLowerCase().contains("credential")
                || message.toLowerCase().contains("token"), message);
    }

    /** An unrecognised failure must still carry git's own last line, not swallow it. */
    @Test
    void anUnrecognisedFailureStillShowsWhatGitSaid() {
        String message = GitClone.describeFailure(MWO,
                failedWith("fatal: something entirely new went wrong"));

        assertTrue(message.contains("something entirely new went wrong"), message);
        assertTrue(message.contains("128"), message);
    }

    @Test
    void theLastMeaningfulLineIsTheOneShown() {
        assertEquals("fatal: the reason",
                GitClone.lastLineOf("Cloning...\nfatal: the reason\n\n   \n"));
        assertEquals("", GitClone.lastLineOf(""));
        assertEquals("", GitClone.lastLineOf(null));
    }

    // ---------- is git there at all ----------

    @Test
    void gitIsAvailableWhenTheVersionCommandSucceeds() {
        assertTrue(GitClone.isAvailable((directory, command) -> {
            assertEquals("git", command.get(0));
            assertEquals("--version", command.get(1));
            return new GitClone.Outcome(0, "git version 2.44.0");
        }));
    }

    @Test
    void gitIsNotAvailableWhenLaunchingItThrows() {
        assertFalse(GitClone.isAvailable((directory, command) -> {
            throw new IOException("Cannot run program \"git\"");
        }));
    }

    @Test
    void gitIsNotAvailableWhenItFails() {
        assertFalse(GitClone.isAvailable(
                (directory, command) -> new GitClone.Outcome(127, "not found")));
    }
}
