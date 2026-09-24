package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.obolibrary.robot.ReportOperation;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Runs ROBOT's quality report against the ontology open in Protege.
 *
 * <p>This is the capability that justified building a Protege plugin rather than a Miro app:
 * {@code robot-core} runs <em>in-process</em>, against the live {@link OWLOntology}, with no
 * subprocess, no Docker, and without writing the ontology to a temporary file first. The
 * retired web application needed a Java container to do the same thing.
 *
 * <p><b>Why this does not call {@code ReportOperation.getReport}.</b> It, all six {@code report}
 * overloads and all three {@code getTDBReport} overloads reach ROBOT's query files through
 * {@code ClassLoader.getResource("report_queries")} and accept only the {@code file} and {@code jar}
 * URL protocols. Felix answers {@code bundle}, so all of them throw "Cannot access report query
 * files". {@link ReportQueries} reads ROBOT's own profile and ROBOT's own SPARQL as streams instead,
 * and {@link RuleRunner} executes them over a Jena model from {@link OntologyDataset}. The rules,
 * the severities and the queries are ROBOT's; only the plumbing is ours.
 *
 * <p>{@code getViolations} is the one public entry point that escapes that lookup, because it is
 * handed the SPARQL. {@link ReportQueries} explains why it is not used and what would make it the
 * better choice.
 *
 * <p>Apache POI is still untouched on this path - {@code asWorkbook} would fail at runtime, because
 * the bundle deliberately excludes POI's logging backend.
 */
public final class QualityReport {

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
        // Not ReportOperation.getReport. That - and all six report() overloads, and all three
        // getTDBReport overloads - route through a private getDefaultQueryStrings which locates
        // ROBOT's queries with getResource("report_queries") and accepts only "file" and "jar" URL
        // protocols. Inside an OSGi bundle Felix answers "bundle", so it throws
        // "Cannot access report query files" and the quality report has never worked inside this
        // plugin, on either supported Protege. The failure is in ~/.Protege/logs/protege.log from
        // 2026-08-28 under OntoBoard 1.15.0; it sat there for nine versions while the docs blamed
        // the OWL API and promised Protege 5.6.x had the full surface.
        //
        // Directory *enumeration* is the only thing that fails. getResourceAsStream on a known
        // path works, so ReportQueries reads ROBOT's own profile and ROBOT's own SPARQL out of the
        // embedded robot-core jar, and RuleRunner executes them over a Jena model built by
        // OntologyDataset. The rules, severities and queries are all ROBOT's; only the plumbing is
        // ours. See ReportQueries for what that costs.
        try {
            Map<String, String> effective = options == null
                    ? ReportOperation.getDefaultOptions()
                    : new java.util.LinkedHashMap<String, String>(options);
            // A copy, because RuleRunner hands back an unmodifiable list and the order it comes
            // in is the profile's, not the one this report presents.
            List<QualityFinding> findings = new ArrayList<QualityFinding>(
                    RuleRunner.run(ontology, severitiesFor(effective)));
            sortMostSevereFirst(findings);
            return findings;
        } catch (RobotException cannotRun) {
            throw new QualityReportException(cannotRun.getMessage(), cannotRun);
        } catch (LinkageError incompatible) {
            // Kept, narrowed, and no longer about the report. Nothing on this path touches Rio
            // any more - OntologyDataset goes through RDF/XML bytes precisely to avoid it - so if
            // a LinkageError still arrives it is a genuine host incompatibility and should say so
            // without naming a cause we have ruled out.
            throw new QualityReportException(
                    "ROBOT could not run against this Protege's OWL API: "
                            + incompatible.getMessage() + ". Protege 5.6.x ships OWL API 4.5.29, "
                            + "which is the version robot-core is built against.", incompatible);
        } catch (RuntimeException failure) {
            throw new QualityReportException(failure);
        }
    }

    /**
     * The rule-to-severity map this run should use.
     *
     * <p>A project's own {@code profile.txt} wins when one is configured, so the plugin and that
     * project's CI agree about what counts as a violation - which is the whole reason
     * {@link #optionsFor(File)} exists. Otherwise ROBOT's defaults, read from ROBOT's own profile
     * rather than from a list written here.
     */
    static Map<String, String> severitiesFor(Map<String, String> options) {
        String profilePath = options == null ? null : options.get(OPTION_PROFILE);
        if (profilePath != null && !profilePath.trim().isEmpty()) {
            Map<String, String> fromProject = ReportQueries.severitiesIn(new File(profilePath));
            if (!fromProject.isEmpty()) {
                return fromProject;
            }
        }
        return ReportQueries.defaultSeverities();
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

    /**
     * Signals that the report could not be produced, as distinct from finding nothing.
     *
     * <p>A {@link RobotException} with the report's own wording. Kept as its own type because the
     * difference between "no violations" and "nothing looked" matters more here than anywhere
     * else: an empty quality report is the answer a user most wants to believe.
     */
    public static class QualityReportException extends RobotException {
        private static final long serialVersionUID = 1L;

        QualityReportException(Throwable cause) {
            super("ROBOT could not produce a quality report: " + describe(cause), cause);
        }

        /**
         * The cause in words a user can act on.
         *
         * <p>{@code getMessage()} alone renders a message-less exception - an NPE, an
         * {@code UnsupportedOperationException} - as the literal text "null", which tells a user
         * nothing and tells a maintainer almost nothing. The class name is a poor explanation but
         * it is an explanation.
         */
        private static String describe(Throwable cause) {
            if (cause == null) {
                return "no reason given";
            }
            String message = cause.getMessage();
            return message == null || message.trim().isEmpty()
                    ? cause.getClass().getName()
                    : message;
        }

        QualityReportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
