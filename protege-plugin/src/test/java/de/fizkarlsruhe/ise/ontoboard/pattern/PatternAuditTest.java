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

    /**
     * A pattern as componency was before 1.155.0 curated the library: coherent, terms without a
     * label, no licence. A fixture of its own, since the library itself is curated now.
     */
    @Test
    void aPatternIsMeasured() throws Exception {
        File dir = new File("target/pattern-audit-test/measured-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        String ttl = "@prefix : <http://example.org/c#> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "<http://example.org/c> a owl:Ontology .\n"
                + ":hasComponent a owl:ObjectProperty ; owl:inverseOf :isComponentOf .\n"
                + ":isComponentOf a owl:ObjectProperty ; rdfs:label \"is component of\"@en .\n"
                + ":Entity a owl:Class ; rdfs:label \"entity\"@en .\n";
        java.nio.file.Files.write(new File(dir, "pattern.owl").toPath(), ttl.getBytes("UTF-8"));
        java.nio.file.Files.write(new File(dir, "pattern.ttl").toPath(), ttl.getBytes("UTF-8"));

        PatternAudit.Row row = new PatternAudit(stubs()).audit(dir);

        assertEquals("coherent", row.reasoner(), row.toString());
        assertTrue(row.unsatisfiable().isEmpty(), row.unsatisfiable().toString());
        assertEquals(java.util.Collections.singleton("http://example.org/c#hasComponent"), row.unlabelled(),
                "the audit says which terms have no label");
        boolean licence = false;
        for (QualityFinding error : row.errors()) {
            assertEquals(QualityFinding.Severity.ERROR, error.getSeverity(), error.toString());
            licence |= "missing_ontology_license".equals(error.getRule());
        }
        assertTrue(licence, "ODK's profile makes a missing licence an ERROR: " + row.errors());
    }

    /** An ontology IRI that names a term too is labelled where the OWL API keeps its label: the header. */
    @Test
    void anOntologyLabelledInItsHeaderIsLabelled() throws Exception {
        File dir = new File("target/pattern-audit-test/header-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        String ttl = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "<http://example.org/p> a owl:Ontology , owl:NamedIndividual ; rdfs:label \"p\" .\n";
        java.nio.file.Files.write(new File(dir, "pattern.owl").toPath(), ttl.getBytes("UTF-8"));
        java.nio.file.Files.write(new File(dir, "pattern.ttl").toPath(), ttl.getBytes("UTF-8"));

        PatternAudit.Row row = new PatternAudit(stubs()).audit(dir);

        assertTrue(row.unlabelled().isEmpty(), row.unlabelled().toString());
    }
}
