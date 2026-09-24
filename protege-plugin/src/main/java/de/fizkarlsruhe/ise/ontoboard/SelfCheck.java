package de.fizkarlsruhe.ise.ontoboard;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologyDataset;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.ReportQueries;
import de.fizkarlsruhe.ise.ontoboard.robot.RuleRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Checks, at startup, the things only a running Protege can prove.
 *
 * <p><b>Why this exists.</b> The plugin's quality report was broken on every host for nine versions
 * while the whole unit suite stayed green, because the defect was in the environment rather than in
 * the logic: {@code ReportOperation} locates its query files with
 * {@code ClassLoader.getResource("report_queries")} and rejects any URL protocol but {@code file}
 * and {@code jar}, and Felix answers {@code bundle}. Maven runs the tests against an exploded
 * classpath where that call succeeds, so no test could have failed.
 *
 * <p>The fix - reading those resources as streams - has exactly the same property: it can only be
 * proved in a bundle. Add to that the fact that {@code robot-core} and Jena are <em>nested jars</em>
 * on {@code Bundle-ClassPath} rather than exploded classes, and "the resources are reachable" is a
 * claim about Felix's classloader, not about this code. So the plugin now makes that claim at
 * startup, in the host, and writes the answer to {@code protege.log}, where
 * {@code tools/smoke.ps1} asserts it.
 *
 * <p>Cheap on purpose: a three-axiom ontology held in memory, no file and no network, so the cost to
 * a user opening Protege is milliseconds. This is a smoke check, not a test suite - it covers the
 * ROBOT paths whose in-bundle behaviour cannot be established any other way, not the 21 menu items.
 *
 * <p>No Protege types and no Swing, so it is testable directly - though a green test here proves
 * only that the logic works on a classpath. {@link OntoBoardStartup} is what runs it where it counts.
 */
public final class SelfCheck {

    /** robot-core 1.9.8's own profile lists this many rules. */
    static final int EXPECTED_RULES = 32;

    private SelfCheck() {
    }

    /** One check and what it found. */
    public static final class Check {
        private final String name;
        private final boolean passed;
        private final String detail;

        Check(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }

        public String getName() {
            return name;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return (passed ? "ok   " : "FAIL ") + name + ": " + detail;
        }
    }

    /** Every check, and the one-line verdict a log scanner can match on. */
    public static final class Result {
        private final List<Check> checks;

        Result(List<Check> checks) {
            this.checks = Collections.unmodifiableList(checks);
        }

        public List<Check> getChecks() {
            return checks;
        }

        public int getPassed() {
            int passed = 0;
            for (Check check : checks) {
                if (check.isPassed()) {
                    passed++;
                }
            }
            return passed;
        }

        public boolean isPassed() {
            return getPassed() == checks.size() && !checks.isEmpty();
        }

        /**
         * The line {@code tools/smoke.ps1} greps for.
         *
         * <p>Stable wording, deliberately: it is an assertion in a script and in a release receipt,
         * so changing it silently turns a failing host into a passing one.
         */
        public String summary() {
            return "OntoBoard self-check: " + (isPassed() ? "PASS" : "FAIL") + " "
                    + getPassed() + "/" + checks.size();
        }
    }

    /**
     * Runs every check. Never throws - a broken self-check must not stop Protege starting.
     *
     * <p>A check that throws is reported as a failure carrying the exception, which is the useful
     * outcome: the point is to get the diagnosis into the log, not to punish the user for it.
     */
    public static Result run() {
        List<Check> checks = new ArrayList<Check>();
        Map<String, String> severities = null;

        try {
            severities = ReportQueries.defaultSeverities();
            checks.add(new Check("robot report profile readable",
                    severities.size() == EXPECTED_RULES,
                    severities.size() + " rules read from robot-core's report_profile.txt, expected "
                            + EXPECTED_RULES));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot report profile readable", false, describe(cannot)));
        }

        try {
            int withQuery = 0;
            List<String> missing = new ArrayList<String>();
            if (severities != null) {
                for (String rule : severities.keySet()) {
                    if (ReportQueries.queryFor(rule) != null) {
                        withQuery++;
                    } else {
                        missing.add(rule);
                    }
                }
            }
            int total = severities == null ? 0 : severities.size();
            checks.add(new Check("robot report queries readable",
                    total > 0 && withQuery == total,
                    withQuery + "/" + total + " rules resolved a .rq file"
                            + (missing.isEmpty() ? "" : ", missing: " + missing)));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot report queries readable", false, describe(cannot)));
        }

        OWLOntology subject = null;
        try {
            subject = tinyOntology();
            long triples = OntologyDataset.modelOf(subject).size();
            checks.add(new Check("jena reads what the owl api writes", triples > 0,
                    triples + " triples round-tripped through RDF/XML"));
        } catch (Exception | LinkageError cannot) {
            checks.add(new Check("jena reads what the owl api writes", false, describe(cannot)));
        }

        try {
            List<QualityFinding> findings = subject == null || severities == null
                    ? Collections.<QualityFinding>emptyList()
                    : RuleRunner.run(subject, severities);
            checks.add(new Check("robot report runs end to end", !findings.isEmpty(),
                    findings.size() + " findings over a three-axiom ontology that has no labels, "
                            + "no title and no licence"));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot report runs end to end", false, describe(cannot)));
        }

        try {
            // new IOHelper() and createExportTable are the two calls on the export path that could
            // fail here and nowhere else: IOHelper reads its own resources, and export.Table is the
            // class that references Apache POI - whose logging backend this bundle cannot embed.
            // Only Table.asWorkbook touches POI, so this must pass; if it ever does not, "Export
            // terms..." is broken in the host while every test still passes, which is precisely the
            // failure this whole class exists to catch.
            List<String> columns = TermExport.defaultColumns();
            TermExport.Result export = subject == null
                    ? null
                    : TermExport.run(subject, columns, TermExport.defaultOptions());
            checks.add(new Check("robot export runs end to end",
                    export != null && !export.getRows().isEmpty(),
                    export == null
                            ? "no ontology to export"
                            : export.getTermCount() + " terms across " + columns.size()
                                    + " columns, written as " + export.getFormat()));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot export runs end to end", false, describe(cannot)));
        }

        return new Result(checks);
    }

    /**
     * An ontology that every default rule can be run against and several must flag.
     *
     * <p>Person has a superclass, so it is not excluded by the {@code rdf:type}-only filter in
     * ROBOT's {@code missing_label} query; the ontology declares no title, description or licence.
     * So a working report cannot come back empty, which is what makes "0 findings" a failure here
     * rather than good news.
     */
    private static OWLOntology tinyOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://www.ontoboard.org/self-check"));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://www.ontoboard.org/self-check#Person")),
                factory.getOWLClass(IRI.create("http://www.ontoboard.org/self-check#Agent"))));
        return ontology;
    }

    /** The throwable in words, since a bare {@code getMessage()} is often null. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}
