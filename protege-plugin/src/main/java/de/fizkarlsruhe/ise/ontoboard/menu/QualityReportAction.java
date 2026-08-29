package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import java.util.List;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Quality report - what ROBOT's default profile finds wrong.
 *
 * <p>Needs Protege 5.6.x: ROBOT's report goes through the RDF layer that moved from Sesame to
 * RDF4J at OWL API 4.5.25, and 5.5.0 ships 4.5.9. The failure is explained rather than shown as a
 * stack trace, because "it does not work on your Protege" is actionable and a NoSuchMethodError is
 * not.
 */
public class QualityReportAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected String operationName() {
        return "Quality report";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        List<QualityFinding> findings = QualityReport.run(ontology);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Severity", "Rule", "Subject", "Message");
        int errors = 0;
        for (QualityFinding finding : findings) {
            result.row(finding.getSeverity().name(), finding.getRule(), finding.getSubject(),
                    finding.getMessage());
            if (finding.getSeverity() == QualityFinding.Severity.ERROR) {
                errors++;
            }
        }
        if (findings.isEmpty()) {
            return result.summary("No violations. ROBOT's default profile is happy with this "
                    + "ontology.").build();
        }
        if (errors > 0) {
            // An ERROR fails an ODK release, so it is a warning on the dialog rather than a row
            // among many - the count is the thing a maintainer needs to see.
            result.warn(errors + " of " + findings.size()
                    + " findings are errors, which would fail an ODK release.");
        }
        return result.summary(findings.size() + " findings, " + errors + " of them errors")
                .build();
    }
}
