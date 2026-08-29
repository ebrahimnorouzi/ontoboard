package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shape every OntoBoard operation reports in.
 *
 * <p>Worth its own tests because a dozen menu entries depend on it, and the failure it prevents is
 * a quiet one: an operation that finishes with warnings and shows only its results looks like a
 * success. The status is therefore derived from what was logged rather than set by hand, so
 * warning and forgetting to downgrade is not possible.
 *
 * <p>Saving is tested too, because "save the log" is worthless if what lands on disk omits the
 * timing and the warnings - which is exactly what a naive dump of the table would do.
 */
class OperationResultTest {

    private static Date on(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = new GregorianCalendar(year, month - 1, day, hour, minute, second);
        return calendar.getTime();
    }

    // ---------- status ----------

    @Test
    void anOperationThatRanCleanlySucceeds() {
        OperationResult result = OperationResult.of("Measure").summary("42 metrics").build();

        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus());
        assertTrue(result.isSuccess());
    }

    /**
     * The failure this class exists to prevent: warning about something and still reporting a
     * clean success, so the warning is never read.
     */
    @Test
    void aWarningDowngradesTheStatusWithoutBeingAskedTo() {
        OperationResult result = OperationResult.of("Report")
                .warn("3 terms have no definition").build();

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus());
        assertTrue(result.isSuccess(), "a warning is not a failure");
    }

    @Test
    void aFailureIsNotUpgradedByALaterNote() {
        OperationResult result = OperationResult.of("Reason")
                .failed("no reasoner is running")
                .note("tried ELK")
                .warn("something else")
                .build();

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertFalse(result.isSuccess());
    }

    @Test
    void aFailureWithNoReasonStillSaysSomething() {
        OperationResult result = OperationResult.failed("Extract", "");

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertFalse(result.getSummary().trim().isEmpty(),
                "a failure with a blank reason tells the user nothing at all");
    }

    /** A result with nothing to say would render as an empty dialog, which reads as a bug. */
    @Test
    void aSummaryIsDerivedWhenNoneWasGiven() {
        assertEquals("Finished, with nothing to report.",
                OperationResult.of("Measure").build().getSummary());
        assertEquals("2 results.",
                OperationResult.of("Report").columns("a").row("1").row("2").build().getSummary());
        assertEquals("1 result.",
                OperationResult.of("Report").columns("a").row("1").build().getSummary());
    }

    @Test
    void anOperationMustBeNamedToBeReportedUnder() {
        assertThrows(IllegalArgumentException.class, () -> OperationResult.of(""));
        assertThrows(IllegalArgumentException.class, () -> OperationResult.of(null));
    }

    // ---------- the log survives ----------

    @Test
    void blankNotesAreNotLogged() {
        OperationResult result = OperationResult.of("Measure")
                .note("").note("   ").note(null).note("real").build();

        assertEquals(1, result.getLog().size());
        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus(),
                "an empty note is not a warning");
    }

    /**
     * The half-truth this guards against: a saved report that shows the table and drops the
     * warnings that produced it.
     */
    @Test
    void thePlainTextCarriesTheLogTheTimingAndTheStatus() {
        String text = OperationResult.of("Report")
                .summary("2 violations")
                .columns("level", "rule")
                .row("ERROR", "missing_label")
                .row("WARN", "missing_definition")
                .warn("ran against the edit file, not the release")
                .at(on(2026, 8, 28, 14, 30, 5), 1234)
                .build()
                .toPlainText();

        assertTrue(text.contains("SUCCEEDED_WITH_WARNINGS"), text);
        assertTrue(text.contains("1234 ms"), text);
        assertTrue(text.contains("2026-08-28 14:30:05"), text);
        assertTrue(text.contains("missing_label"), text);
        assertTrue(text.contains("ran against the edit file"),
                "the warning must survive into the saved text: " + text);
    }

    @Test
    void tabsAndNewlinesInACellDoNotBreakTheColumns() {
        String text = OperationResult.of("Report").columns("a", "b")
                .row("has\ttab", "has\nnewline").build().toPlainText();

        String[] lines = text.split("\n");
        String dataLine = lines[lines.length - 1].isEmpty() ? lines[lines.length - 2]
                : lines[lines.length - 1];
        assertEquals(2, dataLine.split("\t").length,
                "a tab inside a cell would silently add a column: " + dataLine);
    }

    @Test
    void filesWrittenAreListedSoTheyCanBeFound() {
        String text = OperationResult.of("Release")
                .wrote(new File("/tmp/mwo.owl")).wrote(null).build().toPlainText();

        assertTrue(text.contains("mwo.owl"), text);
        assertTrue(text.contains("files written"), text);
    }

    // ---------- saving ----------

    @Test
    void savingWritesEverythingThePlainTextHas(@TempDir Path dir) throws Exception {
        OperationResult result = OperationResult.of("Measure")
                .summary("42 metrics").warn("extended metrics were skipped").build();
        File target = new File(dir.toFile(), "out.txt");

        result.saveTo(target);

        String written = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertEquals(result.toPlainText(), written);
        assertTrue(written.contains("extended metrics were skipped"));
    }

    /** The suggested name has to sort by time and say what it was, or a folder of them is useless. */
    @Test
    void theSuggestedFileNameIsSortableAndSaysWhatItWas() {
        String name = OperationResult.of("ROBOT report")
                .at(on(2026, 8, 28, 14, 30, 5), 1).build().suggestedFileName();

        assertEquals("ontoboard-robot-report-20260828-143005.txt", name);
    }

    @Test
    void aWildOperationNameStillProducesAUsableFileName() {
        String name = OperationResult.of("Extract terms (STAR) -- from IAO!")
                .at(on(2026, 1, 2, 3, 4, 5), 1).build().suggestedFileName();

        assertEquals("ontoboard-extract-terms-star-from-iao-20260102-030405.txt", name);
        assertFalse(name.contains("("), name);
        assertFalse(name.contains("--"), name);
    }

    // ---------- reading it back ----------

    @Test
    void theFirstRowCanBeReadByColumnName() {
        OperationResult result = OperationResult.of("Measure")
                .columns("metric", "value").row("class_count", "42").build();

        assertEquals("42", result.firstRow().get("value"));
        assertTrue(OperationResult.of("Measure").build().firstRow().isEmpty());
    }

    @Test
    void aResultWithNoColumnsHasNoTableToRender() {
        assertFalse(OperationResult.of("Measure").summary("done").build().hasTable());
        assertTrue(OperationResult.of("Measure").columns("a").build().hasTable());
    }
}
