package de.fizkarlsruhe.ise.ontoboard.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Every shape of GitHub link a person actually pastes.
 *
 * <p>Nobody pastes a clone URL. They paste the address bar, which depending on what they were
 * looking at is the repository, a branch, a directory, or a file three levels down; or the "raw"
 * link; or the SSH remote out of a README. Refusing six of the seven with "that is not a valid
 * GitHub URL" is a tool telling a user they are wrong when they are not.
 */
class GitHubUrlTest {

    private static final String REPO = "https://github.com/ISE-FIZKarlsruhe/mwo";

    // ---------- the forms ----------

    @Test
    void theAddressBarOnTheRepositoryPage() {
        GitHubUrl url = GitHubUrl.parse(REPO);

        assertEquals("ISE-FIZKarlsruhe", url.getOwner());
        assertEquals("mwo", url.getRepository());
        assertNull(url.getBranch());
        assertNull(url.getPath());
    }

    @Test
    void aTrailingSlash() {
        assertEquals("ISE-FIZKarlsruhe/mwo", GitHubUrl.parse(REPO + "/").getFullName());
    }

    @Test
    void theCloneUrlWithDotGit() {
        assertEquals("ISE-FIZKarlsruhe/mwo", GitHubUrl.parse(REPO + ".git").getFullName());
    }

    @Test
    void anSshRemoteOutOfAReadme() {
        GitHubUrl url = GitHubUrl.parse("git@github.com:ISE-FIZKarlsruhe/mwo.git");

        assertEquals("ISE-FIZKarlsruhe/mwo", url.getFullName());
    }

    @Test
    void ownerSlashRepoTyped() {
        assertEquals("ISE-FIZKarlsruhe/mwo",
                GitHubUrl.parse("ISE-FIZKarlsruhe/mwo").getFullName());
    }

    @Test
    void withoutTheScheme() {
        assertEquals("ISE-FIZKarlsruhe/mwo",
                GitHubUrl.parse("github.com/ISE-FIZKarlsruhe/mwo").getFullName());
    }

    @Test
    void withWww() {
        assertEquals("ISE-FIZKarlsruhe/mwo",
                GitHubUrl.parse("https://www.github.com/ISE-FIZKarlsruhe/mwo").getFullName());
    }

    // ---------- the branch and path are the point ----------

    /**
     * Someone who pasted a link to a file on a branch has said which file on which branch. Opening
     * the default branch's default ontology instead is not a smaller version of that request.
     */
    @Test
    void aLinkToAFileKeepsTheBranchAndThePath() {
        GitHubUrl url = GitHubUrl.parse(
                REPO + "/blob/issue-42/src/ontology/mwo-edit.owl");

        assertEquals("ISE-FIZKarlsruhe/mwo", url.getFullName());
        assertEquals("issue-42", url.getBranch());
        assertEquals("src/ontology/mwo-edit.owl", url.getPath());
    }

    @Test
    void aLinkToADirectoryKeepsTheBranchAndThePath() {
        GitHubUrl url = GitHubUrl.parse(REPO + "/tree/main/src/ontology");

        assertEquals("main", url.getBranch());
        assertEquals("src/ontology", url.getPath());
    }

    @Test
    void aLinkToJustABranchKeepsTheBranchAndNoPath() {
        GitHubUrl url = GitHubUrl.parse(REPO + "/tree/dev");

        assertEquals("dev", url.getBranch());
        assertNull(url.getPath());
    }

    @Test
    void aRawLink() {
        GitHubUrl url = GitHubUrl.parse(
                "https://raw.githubusercontent.com/ISE-FIZKarlsruhe/mwo/main/"
                        + "src/ontology/mwo-edit.owl");

        assertEquals("ISE-FIZKarlsruhe/mwo", url.getFullName());
        assertEquals("main", url.getBranch());
        assertEquals("src/ontology/mwo-edit.owl", url.getPath());
    }

    /** A branch name with a slash is normal and must not be mistaken for part of the path. */
    @Test
    void aFileLinkWithNoPathAfterTheBranchIsJustTheBranch() {
        assertNull(GitHubUrl.parse(REPO + "/tree/main/").getPath());
    }

    /** Anything else under the repository still names the repository, which is what was asked. */
    @Test
    void aLinkToTheIssuesPageStillNamesTheRepository() {
        GitHubUrl url = GitHubUrl.parse(REPO + "/issues/17");

        assertEquals("ISE-FIZKarlsruhe/mwo", url.getFullName());
        assertNull(url.getBranch());
        assertNull(url.getPath());
    }

    // ---------- copied out of prose ----------

    @Test
    void aFragmentFromADeepLinkIsDropped() {
        GitHubUrl url = GitHubUrl.parse(REPO + "/blob/main/README.md#installation");

        assertEquals("main", url.getBranch());
        assertEquals("README.md", url.getPath());
    }

    @Test
    void trailingPunctuationFromASentenceIsDropped() {
        assertEquals("ISE-FIZKarlsruhe/mwo", GitHubUrl.parse(REPO + ").").getFullName());
        assertEquals("ISE-FIZKarlsruhe/mwo", GitHubUrl.parse(REPO + ",").getFullName());
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals("ISE-FIZKarlsruhe/mwo", GitHubUrl.parse("  " + REPO + "  \n").getFullName());
    }

    // ---------- how it is cloned ----------

    /**
     * HTTPS even when SSH was pasted. SSH fails for anyone without a key on the machine, which for
     * someone opening a link they were sent is most people, and the failure is an authentication
     * error that says nothing about keys.
     */
    @Test
    void cloningAlwaysUsesHttpsEvenFromAnSshRemote() {
        assertEquals("https://github.com/ISE-FIZKarlsruhe/mwo.git",
                GitHubUrl.parse("git@github.com:ISE-FIZKarlsruhe/mwo.git").getCloneUrl());
        assertEquals("https://github.com/ISE-FIZKarlsruhe/mwo.git",
                GitHubUrl.parse(REPO).getCloneUrl());
    }

    // ---------- refusals a user can act on ----------

    /** "Invalid URL" on its own leaves a user with nothing to try next. */
    @Test
    void somethingThatIsNotGitHubIsRefusedWithAnExampleThatWorks() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GitHubUrl.parse("https://gitlab.com/someone/something"));

        assertTrue(refused.getMessage().contains("gitlab.com/someone/something"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("https://github.com/"),
                "showing a form that works is most of the help: " + refused.getMessage());
    }

    @Test
    void nothingPastedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GitHubUrl.parse(""));
        assertThrows(IllegalArgumentException.class, () -> GitHubUrl.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> GitHubUrl.parse(null));
    }

    @Test
    void aBareWordIsNotARepository() {
        assertThrows(IllegalArgumentException.class, () -> GitHubUrl.parse("mwo"));
    }

    // ---------- what it says it is ----------

    @Test
    void theDescriptionNamesTheBranchAndFileWhenThereAreAny() {
        assertEquals("ISE-FIZKarlsruhe/mwo", GitHubUrl.parse(REPO).toString());
        assertEquals("ISE-FIZKarlsruhe/mwo @main src/ontology/mwo-edit.owl",
                GitHubUrl.parse(REPO + "/blob/main/src/ontology/mwo-edit.owl").toString());
    }
}
