package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class QualityReportTest {

    private static List<String[]> table(String[]... rows) {
        return new ArrayList<String[]>(Arrays.asList(rows));
    }

    @Test
    void parsesLevelRuleAndSubjectByColumnName() {
        List<QualityFinding> findings = QualityReport.parse(table(
                new String[] {"Level", "Rule Name", "Subject", "Property", "Value"},
                new String[] {"ERROR", "missing_label", "ex:Person", "rdfs:label", ""}));

        assertEquals(1, findings.size());
        QualityFinding finding = findings.get(0);
        assertEquals(QualityFinding.Severity.ERROR, finding.getSeverity());
        assertEquals("missing_label", finding.getRule());
        assertEquals("ex:Person", finding.getSubject());
    }

    /**
     * ROBOT's column order is not a contract. Reading by position would silently mislabel
     * every finding if a future version reordered them.
     */
    @Test
    void columnsAreFoundByNameNotPosition() {
        List<QualityFinding> findings = QualityReport.parse(table(
                new String[] {"Subject", "Value", "Level", "Rule Name"},
                new String[] {"ex:Person", "", "WARN", "missing_definition"}));

        assertEquals(1, findings.size());
        assertEquals(QualityFinding.Severity.WARN, findings.get(0).getSeverity());
        assertEquals("missing_definition", findings.get(0).getRule());
        assertEquals("ex:Person", findings.get(0).getSubject());
    }

    @Test
    void mostSevereComesFirstAndTheOrderIsStable() {
        List<QualityFinding> findings = QualityReport.parse(table(
                new String[] {"Level", "Rule Name", "Subject"},
                new String[] {"INFO", "zzz_rule", "ex:C"},
                new String[] {"ERROR", "bbb_rule", "ex:A"},
                new String[] {"WARN", "aaa_rule", "ex:B"},
                new String[] {"ERROR", "aaa_rule", "ex:D"}));

        assertEquals(4, findings.size());
        assertEquals(QualityFinding.Severity.ERROR, findings.get(0).getSeverity());
        assertEquals("aaa_rule", findings.get(0).getRule(), "ties break by rule name");
        assertEquals(QualityFinding.Severity.ERROR, findings.get(1).getSeverity());
        assertEquals(QualityFinding.Severity.WARN, findings.get(2).getSeverity());
        assertEquals(QualityFinding.Severity.INFO, findings.get(3).getSeverity());
    }

    /** A column ROBOT adds later must still reach the user rather than vanishing. */
    @Test
    void unrecognisedColumnsAreKeptInTheMessage() {
        List<QualityFinding> findings = QualityReport.parse(table(
                new String[] {"Level", "Rule Name", "Subject", "Property", "Something New"},
                new String[] {"ERROR", "r", "ex:P", "rdfs:label", "surprising detail"}));

        String message = findings.get(0).getMessage();
        assertTrue(message.contains("surprising detail"), "got: " + message);
        assertTrue(message.contains("Property"), "column names give the values context");
    }

    @Test
    void rowsWithAnUnknownLevelAreSkippedRatherThanMisreported() {
        List<QualityFinding> findings = QualityReport.parse(table(
                new String[] {"Level", "Rule Name", "Subject"},
                new String[] {"", "r", "ex:A"},
                new String[] {"NONSENSE", "r", "ex:B"},
                new String[] {"ERROR", "r", "ex:C"}));

        assertEquals(1, findings.size());
        assertEquals("ex:C", findings.get(0).getSubject());
    }

    @Test
    void anEmptyOrHeaderOnlyTableYieldsNoFindings() {
        assertTrue(QualityReport.parse(null).isEmpty());
        assertTrue(QualityReport.parse(new ArrayList<String[]>()).isEmpty());
        assertTrue(QualityReport.parse(table(
                new String[] {"Level", "Rule Name", "Subject"})).isEmpty());
    }

    @Test
    void shortRowsDoNotThrow() {
        List<QualityFinding> findings = QualityReport.parse(table(
                new String[] {"Level", "Rule Name", "Subject", "Property"},
                new String[] {"ERROR"}));

        assertEquals(1, findings.size());
        assertEquals("", findings.get(0).getSubject());
    }

    @Test
    void severityParsingIsCaseAndSuffixTolerant() {
        assertEquals(QualityFinding.Severity.ERROR, QualityReport.severityOf("error"));
        assertEquals(QualityFinding.Severity.WARN, QualityReport.severityOf(" Warning "));
        assertEquals(QualityFinding.Severity.INFO, QualityReport.severityOf("INFO"));
        assertEquals(null, QualityReport.severityOf("debug"));
        assertEquals(null, QualityReport.severityOf(null));
    }

    /**
     * End-to-end against real ROBOT and a real ontology. This asserts what actually happens
     * on the host rather than what we wish happened.
     *
     * <p>robot-core 1.9.8 is built against OWL API 4.5.29, whose Rio API uses RDF4J. Protege
     * 5.5.0 supplies OWL API 4.5.9, whose RioRenderer still takes a Sesame handler - the
     * switch landed in 4.5.25. So every ROBOT operation routed through Rio (report, query,
     * export) throws NoSuchMethodError at the call site. Reasoning, loading and saving are
     * unaffected, which is why the earlier compatibility probe passed.
     *
     * <p>The test accepts either outcome and pins the diagnosis: if a future host ships a
     * newer OWL API the report simply works, and if it does not, the failure must be the
     * documented incompatibility carrying an explanation - never a bare crash.
     */
    @Test
    void reportEitherRunsOrExplainsWhyTheHostCannotSupportIt() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
        try {
            List<QualityFinding> findings = QualityReport.run(ontology);
            for (QualityFinding finding : findings) {
                assertTrue(finding.getSeverity() != null);
                assertTrue(finding.getRule() != null);
            }
        } catch (QualityReport.QualityReportException expected) {
            assertTrue(expected.isHostIncompatibility(),
                    "a report failure must be the known OWL API incompatibility, not something "
                            + "unexplained; got: " + expected.getMessage());
            assertTrue(expected.getMessage().contains("4.5.25"),
                    "the message must name the OWL API version needed so a user can act on it");
        }
    }
}
