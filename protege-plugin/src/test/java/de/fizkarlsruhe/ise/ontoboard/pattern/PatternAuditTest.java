package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * The pattern audit (1.155.0): what a library pattern's own file says to a reasoner, to the label
 * check and to ODK's quality report, measured the way a project that reuses it would see it.
 */
class PatternAuditTest {

    /** The pattern directories, which sit beside the module rather than inside it. */
    private static File patternsDirectory() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile()
                .getParentFile(), "patterns");
    }

    /** Stubs under target/, not @TempDir: the OWL API holds them open on Windows. */
    private static File stubs() {
        File stubs = new File("target", "pattern-audit-stubs");
        assertTrue(stubs.isDirectory() || stubs.mkdirs(), "could not make " + stubs);
        return stubs;
    }

    @Test
    void componencyIsMeasured() throws Exception {
        PatternAudit.Row row = new PatternAudit(stubs())
                .audit(new File(patternsDirectory(), "componency"));

        assertEquals("coherent", row.reasoner(), row.toString());
        assertTrue(row.unsatisfiable().isEmpty(), row.unsatisfiable().toString());
        assertFalse(row.unlabelled().isEmpty(),
                "componency declares terms without rdfs:label - the audit must say which");
        boolean licence = false;
        for (QualityFinding error : row.errors()) {
            assertEquals(QualityFinding.Severity.ERROR, error.getSeverity(), error.toString());
            licence |= "missing_ontology_license".equals(error.getRule());
        }
        assertTrue(licence, "ODK's profile makes a missing licence an ERROR: " + row.errors());
    }
}
