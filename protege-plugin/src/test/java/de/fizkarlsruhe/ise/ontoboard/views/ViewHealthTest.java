package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The register that makes the host self-test tell the truth about the canvas.
 *
 * <p>It exists because the self-test reported "opened, its views constructed" without checking,
 * and Protege's {@code View.createContent} catches whatever {@code initialise()} throws and puts
 * an error label in the view's place - so a canvas that crashed on every open produced the same
 * PASS as one that worked. 1.73.0 shipped that way.
 */
class ViewHealthTest {

    @BeforeEach
    void startClean() {
        ViewHealth.forget();
    }

    /** Nothing has happened, so nothing is claimed. */
    @Test
    void aFreshRegisterClaimsNothing() {
        assertNull(ViewHealth.whatFailed());
        assertTrue(ViewHealth.built().isEmpty());
    }

    /** A view that built says so, by name. */
    @Test
    void aBuiltViewIsNamed() {
        ViewHealth.constructed("SchemaCanvasView");

        assertEquals("[SchemaCanvasView]", ViewHealth.built().toString());
        assertNull(ViewHealth.whatFailed(), "building is not failing");
    }

    /**
     * A failure carries the exception type and the first frame of ours.
     *
     * <p>The frame matters more than the message. This failure class is overwhelmingly a
     * NullPointerException whose message is null, and "NullPointerException: null" in a receipt
     * is a line nobody can act on - it is exactly what the user saw and exactly what made the
     * crash take a log dig to place.
     */
    @Test
    void aFailureNamesTheTypeAndOurOwnFrame() {
        ViewHealth.failed("SchemaCanvasView", thrownFromHere());

        String what = ViewHealth.whatFailed();
        assertNotNull(what);
        assertTrue(what.contains("SchemaCanvasView"), what);
        assertTrue(what.contains("NullPointerException"), what);
        assertTrue(what.contains("de.fizkarlsruhe.ise.ontoboard"),
                "a frame in our own code is the part that locates it: " + what);
    }

    /** The first failure is kept: it is the one with the cause in it. */
    @Test
    void theFirstFailureSurvivesTheSecond() {
        ViewHealth.failed("SchemaCanvasView", new IllegalStateException("the real cause"));
        ViewHealth.failed("SchemaCanvasView", new RuntimeException("a later symptom"));

        assertTrue(ViewHealth.whatFailed().contains("the real cause"), ViewHealth.whatFailed());
    }

    /** A throwable with nothing to say still produces a usable line. */
    @Test
    void anEmptyThrowableStillReportsItsType() {
        ViewHealth.failed("SchemaCanvasView", new NullPointerException());

        assertTrue(ViewHealth.whatFailed().contains("NullPointerException"),
                ViewHealth.whatFailed());
        assertNotNull(ViewHealth.whatFailed());
    }

    /** Forgetting clears both, so each self-test run reports on that run. */
    @Test
    void forgettingClearsBoth() {
        ViewHealth.constructed("SchemaCanvasView");
        ViewHealth.failed("SchemaCanvasView", new RuntimeException("boom"));

        ViewHealth.forget();

        assertNull(ViewHealth.whatFailed());
        assertTrue(ViewHealth.built().isEmpty());
    }

    /** The returned set is a copy; a caller cannot quietly edit the register. */
    @Test
    void theReportIsACopy() {
        ViewHealth.constructed("SchemaCanvasView");

        try {
            ViewHealth.built().clear();
        } catch (UnsupportedOperationException expected) {
            // Either refusing or copying is fine; silently accepting the edit is not.
        }

        assertEquals(1, ViewHealth.built().size());
    }

    /** A throwable whose stack really passes through this package. */
    private static NullPointerException thrownFromHere() {
        try {
            String nothing = null;
            nothing.length();
            throw new IllegalStateException("unreachable");
        } catch (NullPointerException thrown) {
            return thrown;
        }
    }
}
