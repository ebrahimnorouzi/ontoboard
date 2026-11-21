package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A row of History leads to its release on GitHub (1.155.0, asked by the user): the tag's files
 * there, or the release's folder where the release is a folder only.
 */
class HistoryGitHubLinkTest {

    @Test
    void aTaggedReleaseLeadsToItsTag() {
        assertEquals("https://github.com/ISE-FIZKarlsruhe/mwo/tree/v3.0.2",
                HistoryAction.gitHubLink("ISE-FIZKarlsruhe/mwo", "v3.0.2", null));
    }

    @Test
    void aReleaseThatIsAFolderOnlyLeadsToTheFolder() {
        assertEquals("https://github.com/kitchen-lab/egg/tree/HEAD/releases/2026-11-19",
                HistoryAction.gitHubLink("kitchen-lab/egg", null, "2026-11-19"));
    }

    @Test
    void aTagIsWrittenAsAPathCanHoldIt() {
        assertEquals("https://github.com/kitchen-lab/egg/tree/release%201.0%2Bfinal",
                HistoryAction.gitHubLink("kitchen-lab/egg", "release 1.0+final", null));
    }

    /** No GitHub origin, or a row that is no release - what is open: no link. */
    @Test
    void withoutGitHubOrAReleaseThereIsNoLink() {
        assertNull(HistoryAction.gitHubLink(null, "v1.0.0", null));
        assertNull(HistoryAction.gitHubLink("kitchen-lab/egg", null, null));
    }
}
