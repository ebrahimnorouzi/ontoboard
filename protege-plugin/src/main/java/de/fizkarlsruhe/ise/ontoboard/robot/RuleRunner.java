package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Runs ROBOT's report rules over an ontology, one query at a time.
 *
 * <p>What {@code ReportOperation} would do if it could reach its own query files inside an OSGi
 * bundle. See {@link ReportQueries} for why it cannot and what is ours versus ROBOT's: the rules,
 * the severities and the SPARQL all come out of the embedded {@code robot-core}; the loop is here.
 *
 * <p>Per rule rather than in one pass, deliberately. 32 rules over a small ontology is 32 fast
 * queries, and running them separately means a rule whose query cannot be parsed costs that rule
 * and not the report - which matters because a project may name a rule in its own
 * {@code profile.txt} that this ROBOT does not ship.
 *
 * <p>Pure OWL API and Jena; no Protege types and no Swing.
 */
public final class RuleRunner {

    /** All 33 of ROBOT's report queries are {@code SELECT DISTINCT ?entity ?property ?value}. */
    private static final String ENTITY = "entity";
    private static final String PROPERTY = "property";
    private static final String VALUE = "value";

    /** One rule that could not be run, and why - reported rather than thrown. */
    public static final class Skipped {
        private final String rule;
        private final String why;

        Skipped(String rule, String why) {
            this.rule = rule;
            this.why = why;
        }

        public String getRule() {
            return rule;
        }

        public String getWhy() {
            return why;
        }

        @Override
        public String toString() {
            return rule + ": " + why;
        }
    }

    /** What a run produced: the findings, and the rules that could not be checked. */
    public static final class Outcome {
        private final List<QualityFinding> findings;
        private final List<Skipped> skipped;

        Outcome(List<QualityFinding> findings, List<Skipped> skipped) {
            this.findings = java.util.Collections.unmodifiableList(findings);
            this.skipped = java.util.Collections.unmodifiableList(skipped);
        }

        public List<QualityFinding> getFindings() {
            return findings;
        }

        /**
         * Rules that did not run.
         *
         * <p>Surfaced because a silently skipped check is the failure this whole class exists to
         * correct. An earlier version of the scaffold ran 7 of ROBOT's 32 rules and said nothing;
         * a report that quietly checks less than it claims is worse than one that fails.
         */
        public List<Skipped> getSkipped() {
            return skipped;
        }
    }

    private RuleRunner() {
    }

    /** The findings only, for callers that just want the list. */
    public static List<QualityFinding> run(OWLOntology ontology, Map<String, String> severities) {
        return runWithDetail(ontology, severities).getFindings();
    }

    /**
     * Runs every rule in {@code severities} and reports what it found and what it could not check.
     *
     * @param severities rule name to {@code ERROR}/{@code WARN}/{@code INFO}
     * @throws RobotException if the ontology cannot be read as RDF at all
     */
    public static Outcome runWithDetail(OWLOntology ontology, Map<String, String> severities) {
        List<QualityFinding> findings = new ArrayList<QualityFinding>();
        List<Skipped> skipped = new ArrayList<Skipped>();
        if (ontology == null || severities == null || severities.isEmpty()) {
            return new Outcome(findings, skipped);
        }

        // Once, not per rule. Serialising the ontology 32 times would dominate the runtime.
        Model model = OntologyDataset.modelOf(ontology);

        for (Map.Entry<String, String> rule : severities.entrySet()) {
            String name = rule.getKey();
            String sparql = ReportQueries.queryFor(name);
            if (sparql == null) {
                skipped.add(new Skipped(name,
                        "this ROBOT does not ship a query for it, so it was not checked"));
                continue;
            }
            try {
                findings.addAll(violations(model, name, rule.getValue(), sparql));
            } catch (RuntimeException cannotRun) {
                // One unparseable or unsupported query must not cost the other 31.
                skipped.add(new Skipped(name, String.valueOf(cannotRun.getMessage())));
            }
        }
        return new Outcome(findings, skipped);
    }

    private static List<QualityFinding> violations(Model model, String rule, String severity,
            String sparql) {
        List<QualityFinding> found = new ArrayList<QualityFinding>();
        Query query = QueryFactory.create(sparql);
        QueryExecution execution = QueryExecutionFactory.create(query, model);
        try {
            ResultSet results = execution.execSelect();
            while (results.hasNext()) {
                QuerySolution row = results.next();
                String subject = text(row, ENTITY);
                if (subject.isEmpty()) {
                    // All 33 of ROBOT's queries SELECT ?entity, but SPARQL does not guarantee it
                    // is bound in every row, and a blank node renders as nothing useful. Without a
                    // subject there is nothing for a curator to open, so such a row is not a
                    // finding this report can present.
                    continue;
                }
                found.add(new QualityFinding(QualityFinding.Severity.of(severity), rule, subject,
                        message(row)));
            }
        } finally {
            execution.close();
        }
        return found;
    }

    /** What is wrong, in the terms the query bound. */
    private static String message(QuerySolution row) {
        String property = text(row, PROPERTY);
        String value = text(row, VALUE);
        if (property.isEmpty() && value.isEmpty()) {
            return "";
        }
        if (value.isEmpty()) {
            return property;
        }
        return property.isEmpty() ? value : property + ": " + value;
    }

    private static String text(QuerySolution row, String variable) {
        if (row == null || !row.contains(variable)) {
            return "";
        }
        RDFNode node = row.get(variable);
        if (node == null) {
            return "";
        }
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        return node.isURIResource() ? node.asResource().getURI() : node.toString();
    }
}
