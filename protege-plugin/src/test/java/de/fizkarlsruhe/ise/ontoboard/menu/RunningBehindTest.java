package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What waits while a Build or Release goes on behind (Keep working), and what a release kept
 * behind does by default (the 1.155.0 review's I4, I5 and the reviewer's open question).
 */
class RunningBehindTest {

    /**
     * Everything that writes the project or runs make or git in it waits for a run kept behind:
     * two makes in one project write the same files. What only reads goes on.
     */
    @Test
    void whatWritesTheProjectWaitsAndWhatReadsGoesOn() {
        List<OntoBoardAction> writing = Arrays.<OntoBoardAction>asList(new BuildAction(),
                new ReleaseAction(), new RefreshImportsAction(), new UpdateProjectAction(),
                new ImportTermsAction(), new ImportsAction(), new IdRangesAction(),
                new GitAction(), new PullRequestAction(), new WidocoAction());
        List<OntoBoardAction> reading = Arrays.<OntoBoardAction>asList(
                new QualityReportAction(), new ExplainAction(), new MeasureAction(),
                new ProfileAction(), new SparqlAction(), new CompareReleasesAction(),
                new SheetEditorAction(), new RenameAction(), new TransformAction());

        for (OntoBoardAction action : writing) {
            assertTrue(action.writesTheProject(), action.operationName() + " does not wait");
        }
        for (OntoBoardAction action : reading) {
            assertFalse(action.writesTheProject(), action.operationName() + " waits");
        }
    }

    /**
     * A release of an ontology outside an ODK project reads the ontology open in Protege - the
     * report, the reasoner, the copy released - on the run's own thread, so it does not go on
     * behind while that ontology is edited. Until a release is set up, none does.
     */
    @Test
    void aReleaseNotSetUpForOdkKeepsItsWindow() {
        assertFalse(new ReleaseAction().canRunBehind());
        assertTrue(new BuildAction().canRunBehind());
    }

    /** Behind, or with files saved meanwhile, the question to commit defaults to Not now. */
    @Test
    void theSafeAnswerIsTheDefaultBehind() {
        assertEquals("Commit and tag", ReleaseAction.commitDefault(false, false));
        assertEquals("Not now", ReleaseAction.commitDefault(true, false));
        assertEquals("Not now", ReleaseAction.commitDefault(false, true));
        assertEquals("Yes", ReleaseAction.withoutTheCheckDefault(false));
        assertEquals("No", ReleaseAction.withoutTheCheckDefault(true));
    }
}
