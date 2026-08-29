package de.fizkarlsruhe.ise.ontoboard.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
     * Only when the link named one. Passing the default branch explicitly fails on a repository
     * whose default is not what you guessed, and "main" is wrong for everything made before 2020.
     */
    @Test
    void aBranchIsPassedOnlyWhenTheLinkNamedOne() {
        assertFalse(GitClone.cloneCommand(MWO, new File("/tmp/mwo")).contains("--branch"));

        List<String> withBranch = GitClone.cloneCommand(
                GitHubUrl.parse("https://github.com/ISE-FIZKarlsruhe/mwo/tree/issue-42"),
                new File("/tmp/mwo"));

        assertTrue(withBranch.contains("--branch"), withBranch + "");
        assertEquals("issue-42", withBranch.get(withBranch.indexOf("--branch") + 1));
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
        File config = new File(new File(directory, ".git"), "config");
        config.getParentFile().mkdirs();
        Files.write(config.toPath(), ("[remote \"origin\"]\n\turl = " + url + "\n")
                .getBytes("UTF-8"));
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

    @Test
    void aBranchThatIsNotThereNamesTheBranch() {
        GitHubUrl onABranch = GitHubUrl.parse(
                "https://github.com/ISE-FIZKarlsruhe/mwo/tree/no-such-branch");

        String message = GitClone.describeFailure(onABranch,
                failedWith("fatal: Remote branch no-such-branch not found in upstream origin"));

        assertTrue(message.contains("no-such-branch"), message);
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
