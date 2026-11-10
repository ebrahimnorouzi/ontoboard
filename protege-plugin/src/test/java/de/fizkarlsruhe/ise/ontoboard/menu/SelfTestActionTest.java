package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.ui.workspace.WorkspaceTab;

/**
 * Closing the tab the self-test opened. Protege's {@code Disposable.dispose} declares Exception,
 * so a tab that cannot be closed has to reach the check's verdict some other way than a checked
 * exception nothing on the event thread catches.
 */
class SelfTestActionTest {

    /** A tab whose dispose throws what it is given. */
    private static WorkspaceTab tabThatThrows(final Exception thrown) {
        return new WorkspaceTab() {
            private static final long serialVersionUID = 1L;

            @Override
            public void initialise() {
            }

            @Override
            public void dispose() throws Exception {
                throw thrown;
            }
        };
    }

    @Test
    void aTabThatCannotBeClosedFailsTheCheckWithTheReason() {
        IOException cause = new IOException("the layout could not be saved");

        IllegalStateException reported = assertThrows(IllegalStateException.class,
                () -> SelfTestAction.close(tabThatThrows(cause)));

        assertEquals("closing it: java.io.IOException: the layout could not be saved",
                reported.getMessage());
        assertSame(cause, reported.getCause());
    }

    /**
     * The result names the OntoBoard it tested, from the jar's own build properties: a study
     * participant is told to read the version here, and the window named none.
     */
    @Test
    void theResultSaysWhichOntoBoardItTested() {
        String which = SelfTestAction.whichOntoBoard();

        assertTrue(which.matches("OntoBoard \\d+\\.\\d+\\.\\d+"), which);
    }

    @Test
    void anUncheckedFailureIsPassedOnAsItIs() {
        IllegalArgumentException thrown = new IllegalArgumentException("no such view");

        assertSame(thrown, assertThrows(IllegalArgumentException.class,
                () -> SelfTestAction.close(tabThatThrows(thrown))));
    }

    /**
     * The quality report's row says its findings are the sample's, and expected: "6 findings, 2
     * of them errors" in a row marked ok read as a failure (1.135.1, Windows study rehearsal).
     */
    @Test
    void theQualityReportRowSaysItsFindingsAreTheSamplesAsExpected() {
        String said = SelfTestAction.reported(SelfTestAction.QUALITY_ROW, "6 findings, 2 of them errors");
        assertEquals("the sample's 6 findings, as expected", said);
        assertTrue(said.length() <= 40, said);
        assertEquals("1 row", SelfTestAction.reported("Check and test > SPARQL...", "1 row"));
        assertEquals("No violations.", SelfTestAction.reported(SelfTestAction.QUALITY_ROW,
                "No violations."));
    }

    /**
     * No findings on the sample are not "as expected" (1.135.1 review): the sample is built to
     * have some, so a report that silently stopped finding anything read "the sample's 0
     * findings, as expected" in a row marked ok.
     */
    @Test
    void noFindingsOnTheSampleAreNotCalledExpected() {
        assertEquals("0 findings, 0 of them errors", SelfTestAction.reported(
                SelfTestAction.QUALITY_ROW, "0 findings, 0 of them errors"));
        assertEquals("the sample's 12 findings, as expected", SelfTestAction.reported(
                SelfTestAction.QUALITY_ROW, "12 findings, 0 of them errors"));
    }
}
