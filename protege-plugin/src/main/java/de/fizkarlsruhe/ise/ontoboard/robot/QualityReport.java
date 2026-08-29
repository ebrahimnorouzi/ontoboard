package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.ReportOperation;
import org.obolibrary.robot.checks.Report;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Runs ROBOT's quality report against the ontology open in Protege.
 *
 * <p>This is the capability that justified building a Protege plugin rather than a Miro app:
 * {@code robot-core} runs <em>in-process</em>, against the live {@link OWLOntology}, with no
 * subprocess, no Docker, and without writing the ontology to a temporary file first. The
 * retired web application needed a Java container to do the same thing.
 *
 * <p>Results come from {@code Report.toTable(...).toList(...)} rather than the {@code error}
 * / {@code warn} / {@code info} fields, which are deprecated in robot-core 1.9.8 and have no
 * supported replacement returning {@code Violation} objects. The table route is also the one
 * ROBOT's own CLI uses, and it avoids Apache POI - whose logging backend this bundle
 * deliberately excludes, so touching {@code asWorkbook} would fail at runtime.
 */
public final class QualityReport {

    /** ROBOT emits a header row first; these name the columns we care about. */
    private static final String LEVEL = "level";
    private static final String RULE = "rule";
    private static final String SUBJECT = "subject";

    private QualityReport() {
    }

    /** ROBOT's own option keys, spelled once so a typo cannot silently do nothing. */
    public static final String OPTION_PROFILE = "profile";
    public static final String OPTION_FAIL_ON = "fail-on";
    public static final String OPTION_LABELS = "labels";
    public static final String OPTION_LIMIT = "limit";

    /**
     * The profile file an ODK project declares, or null.
     *
     * <p>Both this plugin's scaffold and the reference project put {@code profile.txt} beside the
     * edit file, and both their Makefiles pass {@code --profile profile.txt} to ROBOT. Running the
     * report here against ROBOT's built-in defaults instead would mean the plugin and the
     * project's own CI disagree about what counts as a violation - the plugin reporting problems
     * the project has deliberately downgraded, or missing ones it has promoted. Detecting the file
     * is what keeps the two answers the same.
     */
    public static File profileBeside(File ontologyFile) {
        if (ontologyFile == null || ontologyFile.getParentFile() == null) {
            return null;
        }
        File profile = new File(ontologyFile.getParentFile(), "profile.txt");
        return profile.isFile() ? profile : null;
    }

    /**
     * Options that reproduce what {@code make report} would do for this project.
     *
     * <p>The starting point for the parameter dialog, so its defaults are the project's own rules
     * rather than ROBOT's.
     */
    public static Map<String, String> optionsFor(File ontologyFile) {
        Map<String, String> options = ReportOperation.getDefaultOptions();
        File profile = profileBeside(ontologyFile);
        if (profile != null) {
            options.put(OPTION_PROFILE, profile.getAbsolutePath());
        }
        return options;
    }

    /** Every violation ROBOT's default profile finds, most severe first. */
    public static List<QualityFinding> run(OWLOntology ontology) {
        return run(ontology, ReportOperation.getDefaultOptions());
    }

    /**
     * Every violation, using {@code options}.
     *
     * @param options ROBOT's own option map - see {@link #optionsFor(File)}, which fills in the
     *     project's profile so this agrees with what its CI would report
     * @throws QualityReportException if ROBOT cannot run. Callers must surface this rather
     *     than showing an empty list, which for a quality tool would be a dangerous lie.
     */
    public static List<QualityFinding> run(OWLOntology ontology, Map<String, String> options) {
        List<String[]> rows;
        try {
            IOHelper ioHelper = new IOHelper();
            Map<String, String> effective = options == null
                    ? ReportOperation.getDefaultOptions()
                    : new java.util.LinkedHashMap<String, String>(options);
            Report report = ReportOperation.getReport(ontology, ioHelper, effective);
            rows = report.toTable("tsv").toList("tsv");
        } catch (LinkageError incompatible) {
            // robot-core 1.9.8 is built against OWL API 4.5.29, whose Rio API uses RDF4J.
            // Protege 5.5.0 ships OWL API 4.5.9, whose RioRenderer still takes a Sesame
            // handler - the switch happened at 4.5.25. Any ROBOT operation routed through
            // Rio therefore fails at the call, not at load time, so it cannot be detected
            // up front. Adding RDF4J jars does not help: the incompatible signature is
            // inside OWL API itself.
            throw new QualityReportException(
                    "ROBOT's report needs OWL API 4.5.25 or newer, but this Protege supplies "
                            + "4.5.9. Reasoning and conversion still work; the report, SPARQL "
                            + "query and export do not. Protege 5.6.x ships OWL API 4.5.29 and "
                            + "resolves this.", incompatible);
        } catch (Exception failure) {
            throw new QualityReportException(failure);
        }
        return parse(rows);
    }

    /**
     * Turns ROBOT's table into findings. Package-private so the parsing - the part with real
     * edge cases - is testable without running ROBOT or opening a window.
     */
    static List<QualityFinding> parse(List<String[]> rows) {
        List<QualityFinding> findings = new ArrayList<QualityFinding>();
        if (rows == null || rows.isEmpty()) {
            return findings;
        }
        String[] header = rows.get(0);
        // Locate columns by name rather than position: ROBOT's column order is not a
        // contract, and silently reading the wrong column would mislabel every finding.
        int levelAt = indexOf(header, LEVEL);
        int ruleAt = indexOf(header, RULE);
        int subjectAt = indexOf(header, SUBJECT);

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row == null || row.length == 0) {
                continue;
            }
            QualityFinding.Severity severity = severityOf(cell(row, levelAt));
            if (severity == null) {
                continue;
            }
            findings.add(new QualityFinding(severity, cell(row, ruleAt),
                    cell(row, subjectAt), detailOf(header, row, levelAt, ruleAt, subjectAt)));
        }
        sortMostSevereFirst(findings);
        return findings;
    }

    /**
     * Everything that is not level, rule or subject, joined into one readable line, so a
     * column ROBOT adds in a future version still reaches the user instead of vanishing.
     */
    private static String detailOf(String[] header, String[] row, int levelAt, int ruleAt,
            int subjectAt) {
        StringBuilder detail = new StringBuilder();
        for (int c = 0; c < row.length; c++) {
            if (c == levelAt || c == ruleAt || c == subjectAt) {
                continue;
            }
            String value = row[c];
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (detail.length() > 0) {
                detail.append("; ");
            }
            String name = c < header.length && header[c] != null ? header[c].trim() : "";
            detail.append(name.isEmpty() ? value.trim() : name + " " + value.trim());
        }
        return detail.toString();
    }

    private static void sortMostSevereFirst(List<QualityFinding> findings) {
        // ROBOT's output order is not stable, and a table that reshuffles between runs is
        // unusable for working through problems one at a time.
        Collections.sort(findings, new Comparator<QualityFinding>() {
            @Override
            public int compare(QualityFinding a, QualityFinding b) {
                int bySeverity = a.getSeverity().compareTo(b.getSeverity());
                if (bySeverity != 0) {
                    return bySeverity;
                }
                int byRule = a.getRule().compareToIgnoreCase(b.getRule());
                return byRule != 0 ? byRule : a.getSubject().compareTo(b.getSubject());
            }
        });
    }

    static QualityFinding.Severity severityOf(String level) {
        if (level == null) {
            return null;
        }
        String normalised = level.trim().toUpperCase();
        if (normalised.startsWith("ERROR")) {
            return QualityFinding.Severity.ERROR;
        }
        if (normalised.startsWith("WARN")) {
            return QualityFinding.Severity.WARN;
        }
        if (normalised.startsWith("INFO")) {
            return QualityFinding.Severity.INFO;
        }
        return null;
    }

    private static int indexOf(String[] header, String wanted) {
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null
                    && header[i].trim().toLowerCase().startsWith(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(String[] row, int index) {
        if (index < 0 || index >= row.length || row[index] == null) {
            return "";
        }
        return row[index].trim();
    }

    /** Signals that the report could not be produced, as distinct from finding nothing. */
    public static class QualityReportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        QualityReportException(Throwable cause) {
            super("ROBOT could not produce a quality report: " + cause.getMessage(), cause);
        }

        QualityReportException(String message, Throwable cause) {
            super(message, cause);
        }

        /** True when the host's OWL API is too old, as opposed to a transient failure. */
        public boolean isHostIncompatibility() {
            return getCause() instanceof LinkageError;
        }
    }
}
