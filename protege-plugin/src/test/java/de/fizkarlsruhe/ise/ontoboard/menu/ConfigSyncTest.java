package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkSync;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * What a person is told after an action changed the configuration and the project's files were
 * - or were not - brought in line with it.
 */
class ConfigSyncTest {

    private static final String NONE = "Neither Docker nor Podman answers on this machine";

    private static OdkSync.Way itself() {
        return OdkSync.decide(OdkPin.VERSION, OdkRoute.Use.AUTOMATIC, null, null, NONE);
    }

    private static OperationResult updated() {
        return OperationResult.of("Update project files").note("Project: /p")
                .wrote(new File("/p/src/ontology/Makefile"))
                .summary("Updated 1 file, by OntoBoard itself.").build();
    }

    @Test
    void filesThatWereWrittenAreInLineAndTheUpdatesOwnSentenceIsSaid() {
        ConfigSync.Done done = ConfigSync.done(itself(), updated());

        assertTrue(done.inLine());
        assertEquals("Then the project's files were brought in line with it, as "
                + "OntoBoard > Project > Update project files... does: Updated 1 file, by "
                + "OntoBoard itself.", done.said());
        assertFalse(done.needsALook());
    }

    /** Nothing to write is in line as well. */
    @Test
    void aProjectThatWasInLineAlreadyIsInLine() {
        ConfigSync.Done done = ConfigSync.done(itself(), OperationResult.of("Update project "
                + "files").summary(OdkUpdateForm.ALREADY_UP_TO_DATE + ": the project's files "
                        + "are what ODK v1.6.1's update writes.").build());

        assertTrue(done.inLine());
        assertTrue(done.said().contains("needed no change"), done.said());
        assertFalse(done.needsALook());
    }

    /** Nobody answered the question about the imports, or the wait was given up: not in line. */
    @Test
    void anUpdateThatWroteNothingIsNotInLineAndSaysWhatBringsItInLine() {
        ConfigSync.Done done = ConfigSync.done(itself(), OperationResult.of("Update project "
                + "files").summary("Nothing was written.").build());

        assertFalse(done.inLine());
        assertTrue(done.said().startsWith("Nothing was written."), done.said());
        assertTrue(done.said().contains("OntoBoard > Project > Update project files..."),
                done.said());
    }

    /** The imports were declared, and then the wait was given up: files, and not in line. */
    @Test
    void anUpdateGivenUpAfterTheImportsWereDeclaredIsNotInLine() {
        ConfigSync.Done done = ConfigSync.done(itself(), OperationResult.of("Update project "
                + "files").wrote(new File("/p/src/ontology/p-odk.yaml"))
                .summary("3 files would have changed, but this was abandoned.").build());

        assertFalse(done.inLine());
        assertTrue(done.said().startsWith("3 files would have changed, but this was "
                + "abandoned."), done.said());
    }

    @Test
    void anUpdateThatFailedIsNotInLineAndIsWorthALook() {
        ConfigSync.Done done = ConfigSync.done(itself(), OperationResult.failed("Update project "
                + "files", "The project could not be looked at: its Makefile is locked."));

        assertFalse(done.inLine());
        assertTrue(done.said().startsWith("The project could not be looked at: its Makefile is "
                + "locked."), done.said());
        assertTrue(done.said().contains("The configuration was written"), done.said());
        assertTrue(done.needsALook(), "the whole result is shown: it says where it stopped");
    }

    /** A failure is never in line, whatever its sentence begins with. */
    @Test
    void aFailedUpdateIsNotInLineEvenWhenItsSentenceBeginsLikeASuccess() {
        ConfigSync.Done done = ConfigSync.done(itself(), OperationResult.failed("Update "
                + "project files", OdkUpdateForm.UPDATED + " 2 files, and then the edit "
                        + "file could not be put in place."));

        assertFalse(done.inLine());
        assertTrue(done.needsALook());
        assertTrue(done.said().contains("once that is sorted out"), done.said());
    }

    /** Written, and something to know about it - the edit file could not be read again. */
    @Test
    void anUpdateWithAWarningIsInLineAndWorthALook() {
        ConfigSync.Done done = ConfigSync.done(itself(), OperationResult.of("Update project "
                + "files").wrote(new File("/p/src/ontology/p-edit.owl"))
                .warn("The edit file was rewritten on disk, and reading it again did not work.")
                .summary("Updated 1 file, by OntoBoard itself.").build());

        assertTrue(done.inLine());
        assertTrue(done.needsALook());
    }

    /** Where nobody could bring them in line, the reason and what to do is the whole of it. */
    @Test
    void whereNobodyCouldTheReasonIsSaid() {
        OdkSync.Way later = OdkSync.decide("v1.6", OdkRoute.Use.AUTOMATIC, null, null, NONE);

        ConfigSync.Done done = ConfigSync.done(later, null);

        assertFalse(done.inLine());
        assertEquals(later.why(), done.said());
        assertFalse(done.needsALook());
    }

    /**
     * Which files: a count does not say that the workflow somebody edited by hand is among
     * those ODK's update wrote again (review of 1.153.0).
     */
    @Test
    void theFilesThatWereWrittenAgainAreNamedAsTheProjectNamesThem() {
        File root = new File("/p").getAbsoluteFile();
        OperationResult synced = OperationResult.of("Update project files")
                .wrote(new File(root, "src/ontology/Makefile"))
                .wrote(new File(root, ".github/workflows/qc.yml"))
                .wrote(new File(root, "src/sparql/owldef-violation.sparql"))
                .summary("Updated 3 files, by OntoBoard itself.").build();

        ConfigSync.Done done = ConfigSync.done(itself(), synced, root);

        assertTrue(done.inLine());
        assertTrue(done.said().endsWith("Updated 3 files, by OntoBoard itself: "
                + "src/ontology/Makefile, .github/workflows/qc.yml, "
                + "src/sparql/owldef-violation.sparql."), done.said());
    }

    /** A long list is cut, and says how many more. */
    @Test
    void ofManyFilesTheFirstAreNamedAndTheRestCounted() {
        File root = new File("/p").getAbsoluteFile();
        OperationResult.Builder synced = OperationResult.of("Update project files");
        for (int i = 1; i <= 9; i++) {
            synced.wrote(new File(root, "docs/page" + i + ".md"));
        }

        ConfigSync.Done done = ConfigSync.done(itself(),
                synced.summary("Updated 9 files, by OntoBoard itself.").build(), root);

        assertTrue(done.said().endsWith("docs/page6.md and 3 more."), done.said());
    }
}
