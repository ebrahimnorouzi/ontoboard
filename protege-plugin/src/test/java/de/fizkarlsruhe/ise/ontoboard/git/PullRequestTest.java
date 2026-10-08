package de.fizkarlsruhe.ise.ontoboard.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opening a pull request from inside Prot&eacute;g&eacute;.
 *
 * <p>The JSON below is not invented. It is what {@code gh pr list --repo
 * INCATools/ontology-development-kit --json number,title,state,headRefName,isDraft,url,author}
 * printed, kept verbatim - including {@code author} being a nested object and the fields arriving
 * in alphabetical order rather than the order they were asked for, which is the detail a parser
 * written against a guessed shape gets wrong.
 */
class PullRequestTest {

    /** Real output, two pull requests, from a real ODK repository. */
    private static final String REAL = "[{\"author\":{\"is_bot\":true,"
            + "\"login\":\"app/github-actions\"},\"headRefName\":\"update-obo-epm\","
            + "\"isDraft\":false,\"number\":1380,\"state\":\"OPEN\","
            + "\"title\":\"Update OBO Extended Prefix Map\","
            + "\"url\":\"https://github.com/INCATools/ontology-development-kit/pull/1380\"},"
            + "{\"author\":{\"is_bot\":true,\"login\":\"app/github-actions\"},"
            + "\"headRefName\":\"update-constraints\",\"isDraft\":false,\"number\":1370,"
            + "\"state\":\"OPEN\",\"title\":\"Update constraints.txt\","
            + "\"url\":\"https://github.com/INCATools/ontology-development-kit/pull/1370\"}]";

    // ---------------------------------------------------------------- parsing

    @Test
    void itReadsWhatGhActuallyPrinted() {
        List<PullRequest.Summary> open = PullRequest.parseList(REAL);

        assertEquals(2, open.size());
        assertEquals(1380, open.get(0).getNumber());
        assertEquals("Update OBO Extended Prefix Map", open.get(0).getTitle());
        assertEquals("OPEN", open.get(0).getState());
        assertEquals("update-obo-epm", open.get(0).getHead());
        assertFalse(open.get(0).isDraft());
        assertEquals("https://github.com/INCATools/ontology-development-kit/pull/1380",
                open.get(0).getUrl());
        assertEquals(1370, open.get(1).getNumber());
    }

    /**
     * The author is a nested object, and its login is read out of it.
     *
     * <p>A parser that split on every brace would end a pull request at the author's closing one
     * and lose every field after it - which, in alphabetical order, is all of them.
     */
    @Test
    void theNestedAuthorDoesNotEndTheObject() {
        List<PullRequest.Summary> open = PullRequest.parseList(REAL);

        assertEquals("app/github-actions", open.get(0).getAuthor());
        assertEquals("update-obo-epm", open.get(0).getHead(),
                "a field after the nested object still has to be found");
    }

    /** No pull requests is the empty array, and it is not an error. */
    @Test
    void noPullRequestsIsNotAFailure() {
        assertTrue(PullRequest.parseList("[]").isEmpty());
        assertTrue(PullRequest.parseList("  []  ").isEmpty());
        assertTrue(PullRequest.parseList("").isEmpty());
        assertTrue(PullRequest.parseList(null).isEmpty());
    }

    /** A title with a quote, a brace or a newline in it survives. */
    @Test
    void anAwkwardTitleIsReadWhole() {
        String json = "[{\"number\":7,\"title\":\"Fix \\\"broken\\\" {braces} and\\na newline\","
                + "\"state\":\"OPEN\",\"headRefName\":\"fix\",\"baseRefName\":\"main\","
                + "\"isDraft\":true,\"url\":\"https://example.org/7\"}]";

        PullRequest.Summary one = PullRequest.parseList(json).get(0);

        assertEquals("Fix \"broken\" {braces} and\na newline", one.getTitle());
        assertEquals("fix", one.getHead(), "the brace inside the string must not end the object");
        assertTrue(one.isDraft());
    }

    /** A draft reads as a draft rather than as open. */
    @Test
    void aDraftSaysSo() {
        String json = "[{\"number\":1,\"title\":\"t\",\"state\":\"OPEN\",\"isDraft\":true,"
                + "\"headRefName\":\"b\",\"baseRefName\":\"main\",\"url\":\"u\"}]";

        assertEquals("draft", PullRequest.parseList(json).get(0).describeState());
        assertEquals("merged", PullRequest.parseList(
                "[{\"number\":1,\"title\":\"t\",\"state\":\"MERGED\",\"isDraft\":false,"
                        + "\"headRefName\":\"b\",\"baseRefName\":\"m\",\"url\":\"u\"}]")
                .get(0).describeState());
    }

    /** Anything that is not a pull request is skipped, not guessed at. */
    @Test
    void somethingWithNoNumberIsSkipped() {
        assertTrue(PullRequest.parseList("[{\"title\":\"no number\"}]").isEmpty());
        assertTrue(PullRequest.parseList("not json at all").isEmpty());
    }

    // ---------------------------------------------------------------- commands

    /** Creating one, with everything the dialog collected. */
    @Test
    void theCreateCommandCarriesWhatWasTyped() {
        List<String> command = PullRequest.createCommand("main", "  Add the ro import  ",
                "Terms for RO.", true);

        assertEquals(Arrays.asList("gh", "pr", "create", "--title", "Add the ro import",
                "--body", "Terms for RO.", "--base", "main", "--draft"), command);
    }

    /**
     * The body is always passed, even when empty.
     *
     * <p>Without {@code --body}, gh opens an editor - inside a process this plugin is waiting on,
     * which is a hang with no window to close.
     */
    @Test
    void anEmptyBodyIsStillPassed() {
        assertTrue(PullRequest.createCommand("main", "t", "", false).contains("--body"));
        assertTrue(PullRequest.createCommand("main", "t", null, false).contains("--body"));
        assertFalse(PullRequest.createCommand("main", "t", "", false).contains("--draft"));
    }

    /** --head is never passed, so the dialog cannot disagree with the checkout. */
    @Test
    void theBranchComesFromTheCheckoutAndNotFromUs() {
        assertFalse(PullRequest.createCommand("main", "t", "b", false).contains("--head"));
    }

    /** A pull request with no title is refused here rather than by gh. */
    @Test
    void aTitlelessPullRequestIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PullRequest.createCommand("main", "   ", "b", false));
        assertThrows(IllegalArgumentException.class,
                () -> PullRequest.createCommand("main", null, "b", false));
    }

    /** No base means gh's own default, rather than a guess at 'main'. */
    @Test
    void anAbsentBaseIsLeftToGh() {
        assertFalse(PullRequest.createCommand(null, "t", "b", false).contains("--base"));
        assertFalse(PullRequest.createCommand("  ", "t", "b", false).contains("--base"));
    }

    /** The dry run is the same command with one flag, so the two cannot drift. */
    @Test
    void theDryRunIsTheRealCommandPlusAFlag() {
        List<String> real = PullRequest.createCommand("main", "t", "b", false);
        List<String> dry = PullRequest.dryRunOf(real);

        assertEquals(real.size() + 1, dry.size());
        assertEquals(real, dry.subList(0, real.size()));
        assertEquals("--dry-run", dry.get(dry.size() - 1));
    }

    /** The list asks for exactly the fields the parser reads. */
    @Test
    void theListAsksForWhatIsParsed() {
        List<String> command = PullRequest.listCommand();

        assertTrue(command.contains("--json"));
        String fields = command.get(command.indexOf("--json") + 1);
        for (String needed : new String[] {"number", "title", "state", "headRefName",
                "baseRefName", "isDraft", "url", "author"}) {
            assertTrue(fields.contains(needed), needed + " is parsed but not requested");
        }
        assertTrue(command.contains(String.valueOf(PullRequest.MOST_LISTED)));
    }

    // ---------------------------------------------------------------- readiness

    /** gh missing and gh logged out are different problems with different remedies. */
    @Test
    void theTwoWaysGhIsUnusableAreToldApart() {
        String missing = PullRequest.whyUnavailable(-1,
                Arrays.asList("Cannot run program \"gh\""));
        assertNotNull(missing);
        assertTrue(missing.contains("cli.github.com"), missing);

        String loggedOut = PullRequest.whyUnavailable(1,
                Arrays.asList("You are not logged into any GitHub hosts."));
        assertNotNull(loggedOut);
        assertTrue(loggedOut.contains("gh auth login"), loggedOut);
        assertFalse(loggedOut.contains("cli.github.com"),
                "an installed gh should not be told to install gh");
    }

    /** And a working gh is no complaint at all. */
    @Test
    void anAuthenticatedGhIsReady() {
        assertNull(PullRequest.whyUnavailable(0,
                Arrays.asList("github.com", "  Logged in to github.com account someone")));
    }

    /** Windows says it differently, and that still has to read as "not installed". */
    @Test
    void windowsPhrasingIsRecognised() {
        String missing = PullRequest.whyUnavailable(1, Arrays.asList(
                "'gh' is not recognized as an internal or external command"));

        assertNotNull(missing);
        assertTrue(missing.contains("cli.github.com"), missing);
    }

    // ---------------------------------------------------------------- the fallback

    /** The browser form, which needs nothing installed. */
    @Test
    void theCompareUrlOpensTheForm() {
        assertEquals("https://github.com/ebrahimnorouzi/ontoboard/compare/add-ro?expand=1",
                PullRequest.compareUrl("https://github.com/ebrahimnorouzi/ontoboard.git",
                        "add-ro"));
    }

    /** It works from an SSH remote too, which is what a cloned ODK repo often has. */
    @Test
    void anSshRemoteStillGivesAUrl() {
        String url = PullRequest.compareUrl("git@github.com:INCATools/ontology-development-kit.git",
                "my-branch");

        assertNotNull(url);
        assertTrue(url.startsWith("https://github.com/INCATools/ontology-development-kit/compare/"),
                url);
    }

    /** And nothing at all for a remote that is not GitHub. */
    @Test
    void aNonGitHubRemoteHasNoForm() {
        assertNull(PullRequest.compareUrl("https://gitlab.com/group/project.git", "b"));
        assertNull(PullRequest.compareUrl(null, "b"));
        assertNull(PullRequest.compareUrl("https://github.com/o/r.git", null));
        assertNull(PullRequest.compareUrl("https://github.com/o/r.git", "  "));
    }

    // ---------------------------------------------------------------- the branch

    /** The three reasons a branch cannot be proposed, each said as itself. */
    @Test
    void aBranchThatCannotBeProposedSaysWhich() {
        String onBase = PullRequest.whyBranchCannotBeProposed("main", "main", true);
        assertNotNull(onBase);
        assertTrue(onBase.contains("Create a branch"), onBase);

        String unpushed = PullRequest.whyBranchCannotBeProposed("work", "main", false);
        assertNotNull(unpushed);
        assertTrue(unpushed.contains("pushed"), unpushed);

        String detached = PullRequest.whyBranchCannotBeProposed("", "main", true);
        assertNotNull(detached);
        assertTrue(detached.contains("detached"), detached);
    }

    /** A pushed branch that is not the base is proposable. */
    @Test
    void aPushedFeatureBranchIsFine() {
        assertNull(PullRequest.whyBranchCannotBeProposed("add-ro-import", "main", true));
    }
}
