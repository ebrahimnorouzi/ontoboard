package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Quality report - what ROBOT finds wrong, by this project's own rules.
 *
 * <p>"By this project's own rules" is the part that matters. An ODK project ships a
 * {@code profile.txt} declaring which checks are errors, which are warnings and which are ignored,
 * and its Makefile passes it to ROBOT. Running the report here against ROBOT's built-in defaults
 * would make the plugin and the project's CI disagree - the plugin flagging problems the
 * maintainers deliberately downgraded, or staying quiet about ones they promoted. The profile is
 * therefore detected and offered as the default, and the result records which one was used, so two
 * reports can be compared.
 *
 * <p>Runs on both supported hosts as of 1.25.0. This said "Needs Protege 5.6.x" and blamed the
 * OWL API's move from Sesame to RDF4J at 4.5.25, which was the wrong diagnosis twice over: the
 * report failed on 5.6.x as well, because {@code ReportOperation} cannot reach its own query files
 * inside an OSGi bundle, and nothing on the current path touches the RDF layer at all - see
 * {@link de.fizkarlsruhe.ise.ontoboard.robot.ReportQueries}. The 1.25.0 smoke receipt records it
 * passing on 5.5.0's OWL API 4.5.9.
 */
public class QualityReportAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private volatile Map<String, String> options;
    private volatile String profileDescription = "ROBOT's built-in profile";

    @Override
    protected String operationName() {
        return "Quality report";
    }

    @Override
    protected boolean configure() {
        File ontologyFile = ontologyFileOf(getOWLModelManager().getActiveOntology());
        File projectProfile = QualityReport.profileBeside(ontologyFile);
        Map<String, String> starting = QualityReport.optionsFor(ontologyFile);

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(QualityReport.OPTION_PROFILE, "Report profile",
                        Parameter.Kind.FILE)
                        .defaultValue(projectProfile == null ? ""
                                : projectProfile.getAbsolutePath())
                        .help("A profile file decides which checks are errors, which are warnings "
                                + "and which are ignored. An ODK project keeps one as profile.txt "
                                + "beside the ontology and its build passes it to ROBOT - leaving "
                                + "this set to that file is what makes this report agree with "
                                + "what the project's CI will say. Clear it to use ROBOT's "
                                + "built-in rules instead.")
                        .build(),
                Parameter.of(QualityReport.OPTION_FAIL_ON, "Treat as failing",
                        Parameter.Kind.CHOICE)
                        .choices("error", "warn", "info", "none")
                        .defaultValue(starting.containsKey(QualityReport.OPTION_FAIL_ON)
                                ? starting.get(QualityReport.OPTION_FAIL_ON) : "error")
                        .help("Which severity counts as a failure. This is what an ODK release "
                                + "gate uses: at 'error' a build stops only for errors, at 'warn' "
                                + "warnings stop it too. It does not change what is found, only "
                                + "what is called a failure.")
                        .build(),
                Parameter.of(QualityReport.OPTION_LABELS, "Show labels instead of IRIs",
                        Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("Reports the rdfs:label of each offending term rather than its full "
                                + "IRI. Much easier to read for an OBO ontology, where an IRI is "
                                + "an opaque number, and slower on a very large ontology because "
                                + "every label has to be looked up.")
                        .build(),
                Parameter.of(QualityReport.OPTION_LIMIT, "Stop after this many violations",
                        Parameter.Kind.NUMBER)
                        .defaultValue("")
                        .help("A safety valve for an ontology with thousands of problems, where "
                                + "collecting them all takes far longer than reading the first "
                                + "hundred would. Leave empty for no limit.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Quality report",
                projectProfile == null
                        ? "This project has no profile.txt, so ROBOT's built-in rules are used."
                        : "Defaults come from this project's own profile.txt, so the result "
                                + "matches what its build would report.",
                parameters);
        if (chosen == null) {
            return false;
        }
        options = starting;
        String profile = chosen.get(QualityReport.OPTION_PROFILE);
        if (profile == null || profile.trim().isEmpty()) {
            options.remove(QualityReport.OPTION_PROFILE);
            profileDescription = "ROBOT's built-in profile";
        } else {
            options.put(QualityReport.OPTION_PROFILE, profile.trim());
            profileDescription = profile.trim();
        }
        options.put(QualityReport.OPTION_FAIL_ON,
                chosen.get(QualityReport.OPTION_FAIL_ON));
        options.put(QualityReport.OPTION_LABELS, chosen.get(QualityReport.OPTION_LABELS));
        String limit = chosen.get(QualityReport.OPTION_LIMIT);
        if (limit != null && !limit.trim().isEmpty()) {
            options.put(QualityReport.OPTION_LIMIT, limit.trim());
        } else {
            options.remove(QualityReport.OPTION_LIMIT);
        }
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        List<QualityFinding> findings = QualityReport.run(ontology, options);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Severity", "Rule", "Subject", "Message");
        // Recorded so two reports can be compared. A list of violations with no record of the
        // rules that produced it cannot be set beside another run.
        result.note("Profile: " + profileDescription);
        result.note("Failing at: " + options.get(QualityReport.OPTION_FAIL_ON));

        int errors = 0;
        for (QualityFinding finding : findings) {
            result.row(finding.getSeverity().name(), finding.getRule(), finding.getSubject(),
                    finding.getMessage());
            if (finding.getSeverity() == QualityFinding.Severity.ERROR) {
                errors++;
            }
        }
        if (findings.isEmpty()) {
            return result.summary("No violations. This ontology passes the rules in "
                    + profileDescription + ".").build();
        }
        int failing = countAtOrAbove(findings, options.get(QualityReport.OPTION_FAIL_ON));
        if (failing > 0) {
            result.warn(failing + " of " + findings.size() + " findings are at or above '"
                    + options.get(QualityReport.OPTION_FAIL_ON)
                    + "', which would fail this project's build.");
        }
        return result.summary(findings.size() + " findings, " + errors + " of them errors")
                .build();
    }

    /** How many findings would trip the chosen gate. */
    private static int countAtOrAbove(List<QualityFinding> findings, String failOn) {
        if (failOn == null || "none".equalsIgnoreCase(failOn)) {
            return 0;
        }
        int rank = rankOf(failOn);
        int counted = 0;
        for (QualityFinding finding : findings) {
            if (rankOf(finding.getSeverity().name()) <= rank) {
                counted++;
            }
        }
        return counted;
    }

    /** Lower is more severe, so "at or above" is a single comparison. */
    private static int rankOf(String severity) {
        String name = severity == null ? "" : severity.toLowerCase(Locale.ROOT);
        if (name.startsWith("error")) {
            return 0;
        }
        if (name.startsWith("warn")) {
            return 1;
        }
        if (name.startsWith("info")) {
            return 2;
        }
        return 3;
    }

    /** The ontology's own file, or null when it has never been saved. */
    private File ontologyFileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
