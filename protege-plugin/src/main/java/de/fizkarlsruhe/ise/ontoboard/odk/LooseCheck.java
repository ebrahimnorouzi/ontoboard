package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Check this ontology, without a project: what Build checks of an ODK project, asked of
 * whatever is open - a loose {@code .owl} file too. The testers of 1.132.0 asked for it:
 * "Today Build needs a project."
 *
 * <p>Three checks, each with its verdict in a line: the reasoner (ELK, as ODK's release
 * reasons, and HermiT for ten seconds where ELK may miss something - Build's second opinion,
 * {@link OdkTargets#secondOpinion}); ROBOT's quality report - in a project as Quality report...
 * asks it, with the project's profile and on its own terms ({@link Options#forFile}), else with
 * ODK's default profile, which at ODK v1.6.1 is ROBOT's own, rule for rule (the seed's
 * {@code profile.txt}, compared); and
 * OntoBoard's label check, ODK's check of a project made by OntoBoard
 * ({@link OdkExtras#checkLabels}), asked of the ontology by itself as ODK asks it. Nothing is
 * written.
 */
public final class LooseCheck {

    static final String REASONER = "Reasoner";
    static final String REPORT = "Quality report";
    static final String LABELS = "Labels";

    /** How many of a check's findings its line names; the rest are counted. */
    static final int NAMED = 5;

    /** What is asked. */
    public static final class Options {
        private final boolean secondOpinion;
        private final Map<String, String> report;

        private Options(boolean secondOpinion, Map<String, String> report) {
            this.secondOpinion = secondOpinion;
            this.report = report;
        }

        /** ELK, and HermiT for ten seconds where ELK may miss something - as Build does. */
        public static Options defaults() {
            return new Options(true, null);
        }

        /**
         * The defaults, with the quality report asked as {@code Quality report...} asks it of
         * this file: in a project, with the project's profile and on its own terms, as ODK's
         * test counts them; for a file in no project that is ROBOT's own, as by default.
         */
        public static Options forFile(java.io.File file) {
            return new Options(true, QualityReport.optionsFor(file));
        }

        /** ELK alone. */
        public Options withoutSecondOpinion() {
            return new Options(false, report);
        }

        public boolean asksASecondOpinion() {
            return secondOpinion;
        }

        /** ROBOT's options for the report; null for ROBOT's own defaults. */
        public Map<String, String> reportOptions() {
            return report;
        }
    }

    /** One check: its name, whether it passed, its verdict, and what it found, one a line. */
    public static final class Line {
        private final String check;
        private final boolean passed;
        private final String verdict;
        private final List<String> details;

        Line(String check, boolean passed, String verdict, List<String> details) {
            this.check = check;
            this.passed = passed;
            this.verdict = verdict;
            this.details = Collections.unmodifiableList(new ArrayList<String>(details));
        }

        public String check() {
            return check;
        }

        public boolean passed() {
            return passed;
        }

        /** One sentence: what the check found. */
        public String verdict() {
            return verdict;
        }

        /** Each finding, for the log. */
        public List<String> details() {
            return details;
        }

        @Override
        public String toString() {
            return check + (passed ? " passed: " : " FAILED: ") + verdict;
        }
    }

    /** The three checks' lines, in the order they ran. */
    public static final class Result {
        private final List<Line> lines;

        Result(List<Line> lines) {
            this.lines = Collections.unmodifiableList(new ArrayList<Line>(lines));
        }

        public List<Line> lines() {
            return lines;
        }

        /** Whether every check passed. */
        public boolean passed() {
            for (Line line : lines) {
                if (!line.passed()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return lines.toString();
        }
    }

    private LooseCheck() {
    }

    /** Checks the ontology - with what it imports, where that is loaded - and writes nothing. */
    public static Result run(OWLOntology ontology, Options options) {
        List<Line> lines = new ArrayList<Line>();
        Options asked = options == null ? Options.defaults() : options;
        lines.add(reasoner(ontology, asked));
        lines.add(report(ontology, asked.reportOptions()));
        lines.add(labels(ontology));
        return new Result(lines);
    }

    // ------------------------------------------------------------------ the reasoner

    private static Line reasoner(OWLOntology ontology, Options options) {
        OWLReasonerFactory elk = Reasoners.Choice.ELK.newFactory();
        OWLReasoner reasoner = null;
        List<String> details = new ArrayList<String>();
        try {
            reasoner = elk.createReasoner(ontology);
            if (!reasoner.isConsistent()) {
                return new Line(REASONER, false, "ELK: the ontology is inconsistent - nothing "
                        + "can be true of it. Check and test > Explain... says which axioms "
                        + "make it so.", details);
            }
            List<String> unsatisfiable = new ArrayList<String>();
            for (OWLClass empty : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                unsatisfiable.add(nameOf(ontology, empty));
            }
            Collections.sort(unsatisfiable);
            details.addAll(unsatisfiable);
            String second = options.asksASecondOpinion()
                    ? OdkTargets.secondOpinion(ontology, elk) : null;
            if (second != null) {
                details.add(second);
            }
            if (!unsatisfiable.isEmpty()) {
                return new Line(REASONER, false, "ELK: " + counted(unsatisfiable.size(),
                        "class", "classes") + " can have no members - " + named(unsatisfiable)
                        + ". Check and test > Explain... says which axioms make "
                        + (unsatisfiable.size() == 1 ? "it" : "each") + " so."
                        + (second == null ? "" : " " + second), details);
            }
            return new Line(REASONER, true, "ELK: consistent, and every class can have members."
                    + (second == null ? "" : " " + second), details);
        } catch (RuntimeException | LinkageError cannotReason) {
            return new Line(REASONER, false, "ELK could not reason over it: "
                    + (cannotReason.getMessage() == null ? cannotReason.getClass().getSimpleName()
                            : cannotReason.getMessage()), details);
        } finally {
            if (reasoner != null) {
                reasoner.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ the quality report

    /**
     * ROBOT's quality report: with {@code reportOptions} as {@code Quality report...} takes them
     * from a project - its profile.txt, and only its own terms, as ODK's test counts them - or,
     * null, with ROBOT's own profile: ODK v1.6.1's profile.txt, rule for rule.
     */
    private static Line report(OWLOntology ontology, Map<String, String> reportOptions) {
        String profile = reportOptions == null ? null
                : reportOptions.get(QualityReport.OPTION_PROFILE);
        List<String> bases = QualityReport.baseIrisIn(reportOptions);
        String asked = (profile == null ? "ODK's default profile"
                : "The project's " + new java.io.File(profile).getName())
                + (bases.isEmpty() ? "" : profile == null ? ", on the project's own terms"
                        : ", on its own terms");
        List<QualityFinding> findings;
        try {
            findings = reportOptions == null ? QualityReport.run(ontology)
                    : QualityReport.run(ontology, reportOptions);
        } catch (RuntimeException cannotRun) {
            return new Line(REPORT, false, "ROBOT's quality report could not run: "
                    + cannotRun.getMessage(), Collections.<String>emptyList());
        }
        Map<QualityFinding.Severity, Integer> counts =
                new java.util.EnumMap<QualityFinding.Severity, Integer>(
                        QualityFinding.Severity.class);
        Map<String, Integer> errors = new TreeMap<String, Integer>();
        List<String> details = new ArrayList<String>();
        if (profile != null) {
            details.add("The profile: " + profile);
        }
        if (!bases.isEmpty()) {
            details.add("Counted: terms whose IRI starts with " + String.join(" or ", bases)
                    + " - the project's own, as ODK's test counts them");
        }
        for (QualityFinding finding : findings) {
            Integer before = counts.get(finding.getSeverity());
            counts.put(finding.getSeverity(), before == null ? 1 : before + 1);
            if (finding.getSeverity() == QualityFinding.Severity.ERROR) {
                Integer rule = errors.get(finding.getRule());
                errors.put(finding.getRule(), rule == null ? 1 : rule + 1);
            }
            details.add(finding.getSeverity() + " " + finding.getRule() + " "
                    + finding.getSubject() + (finding.getValue() == null ? ""
                            : " " + finding.getValue()));
        }
        String others = others(counts);
        if (!errors.isEmpty()) {
            List<String> rules = new ArrayList<String>();
            for (Map.Entry<String, Integer> rule : errors.entrySet()) {
                rules.add(rule.getKey() + (rule.getValue() == 1 ? "" : " (" + rule.getValue()
                        + ")"));
            }
            int count = counts.get(QualityFinding.Severity.ERROR);
            return new Line(REPORT, false, asked + ": " + counted(count, "error",
                    "errors") + " - " + named(rules) + others + ". "
                    + "Check and test > Quality report... lists them one by one.", details);
        }
        return new Line(REPORT, true, asked + ": no errors" + others + ".", details);
    }

    /** The warnings and remarks, as the end of a sentence; empty when there are none. */
    private static String others(Map<QualityFinding.Severity, Integer> counts) {
        Integer warnings = counts.get(QualityFinding.Severity.WARN);
        Integer remarks = counts.get(QualityFinding.Severity.INFO);
        return (warnings == null ? "" : "; " + counted(warnings, "warning", "warnings"))
                + (remarks == null ? "" : "; " + counted(remarks, "remark", "remarks"));
    }

    // ------------------------------------------------------------------ the labels

    private static Line labels(OWLOntology ontology) {
        SparqlQuery.Answer answer;
        try {
            answer = SparqlQuery.runAlone(ontology, OdkExtras.checkLabels());
        } catch (RuntimeException cannotAsk) {
            return new Line(LABELS, false, "The label check could not run: "
                    + cannotAsk.getMessage(), Collections.<String>emptyList());
        }
        List<String> unlabelled = new ArrayList<String>();
        for (List<String> row : answer.getRows()) {
            String iri = row.isEmpty() ? "" : row.get(0).replaceAll("^<|>$", "");
            unlabelled.add(iri);
        }
        if (unlabelled.isEmpty()) {
            return new Line(LABELS, true, "Every class the ontology describes has a label.",
                    unlabelled);
        }
        return new Line(LABELS, false, counted(unlabelled.size(), "class", "classes")
                + " without a label - " + named(unlabelled) + ".", unlabelled);
    }

    // ------------------------------------------------------------------ the words

    private static String nameOf(OWLOntology ontology, OWLClass type) {
        String label = DisplayLabels.forEntity(ontology, type);
        if (label != null && !label.trim().isEmpty()
                && !label.equals(type.getIRI().toString())) {
            return label;
        }
        String shortName = DisplayLabels.shortNameOf(type.getIRI());
        return shortName == null || shortName.isEmpty() ? type.getIRI().toString() : shortName;
    }

    private static String counted(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    /** The first few, and how many more. */
    private static String named(List<String> all) {
        List<String> first = all.subList(0, Math.min(NAMED, all.size()));
        return String.join(", ", first) + (all.size() > NAMED ? " and " + (all.size() - NAMED)
                + " more" : "");
    }
}
