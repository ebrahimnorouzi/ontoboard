package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * SPARQL over the ontology Protege has open, and over the project's own committed checks.
 *
 * <p>This closes the gap the roadmap called "the wrong way round": OntoBoard's ODK scaffold writes
 * {@code src/sparql/check_labels.rq} and generates a {@code sparql_test} target that runs it, and
 * until now nothing in the plugin could run the check it had just written. A curator had to leave
 * Protege, start Docker and run {@code make sparql_test} to find out whether the query they were
 * editing did anything.
 *
 * <p><b>Why this was blocked, and what changed.</b> ROBOT's own route from an ontology to a queryable
 * graph is {@code QueryOperation.loadOntologyAsModel}, which constructs an OWL API
 * {@code RioRenderer} - and no RDF4J package is visible inside this bundle, because neither
 * Protege's OWL API bundle exports one and OntoBoard embeds none. So that call fails on <em>both</em>
 * supported hosts, not just on 5.5.0 as the documentation claimed for five versions.
 * {@link OntologyDataset} goes through RDF/XML bytes instead, which needs no RDF4J at all, so the
 * graph is reachable.
 *
 * <p><b>And ROBOT's own {@code QueryOperation} cannot be called at all.</b> Not one method of it:
 * its constant pool carries
 * {@code RioRenderer.<init>(OWLOntology, org.eclipse.rdf4j.rio.RDFHandler, OWLDocumentFormat,
 * org.eclipse.rdf4j.model.Resource[])}, so resolving the class fails with
 * {@code NoClassDefFoundError: org/eclipse/rdf4j/rio/RDFHandler} before any method body runs. An
 * earlier reading of this said only the {@code loadOntology*} methods were affected and that
 * {@code execQuery} was safe, which was wrong - it came from a per-method disassembly whose range
 * never captured the body. Calling {@code execQuery} is what proved it.
 *
 * <p>So the query is executed with Jena directly, exactly as {@link RuleRunner} executes ROBOT's
 * report rules. That is the same engine ROBOT uses - {@code QueryOperation.execQuery} is a thin
 * wrapper over {@code QueryExecutionFactory} - and {@link RobotParityTest} has already shown the
 * report path this mirrors produces results identical to real ROBOT's.
 *
 * <p>Pure OWL API, Jena and robot-core; no Protege types and no Swing.
 */
public final class SparqlQuery {

    /** Where an ODK project keeps the checks its build runs. */
    public static final String CHECKS_DIRECTORY = "src/sparql";

    private SparqlQuery() {
    }

    /** One query's answer: the columns it selected and the rows it returned. */
    public static final class Answer {
        private final List<String> columns;
        private final List<List<String>> rows;

        Answer(List<String> columns, List<List<String>> rows) {
            this.columns = Collections.unmodifiableList(columns);
            this.rows = Collections.unmodifiableList(rows);
        }

        public List<String> getColumns() {
            return columns;
        }

        public List<List<String>> getRows() {
            return rows;
        }

        public int size() {
            return rows.size();
        }
    }

    /**
     * One check file and what it found.
     *
     * <p>A check is a {@code SELECT} whose rows <em>are</em> the violations - that is ROBOT's
     * {@code verify} convention, and why {@code robot verify} exits non-zero when a query returns
     * anything. So "passed" means "returned nothing", which reads backwards until you remember that
     * the query is written to find problems.
     */
    public static final class Check {
        private final String name;
        private final Answer answer;
        private final String failure;

        Check(String name, Answer answer, String failure) {
            this.name = name;
            this.answer = answer;
            this.failure = failure;
        }

        public String getName() {
            return name;
        }

        /** Null when the query could not be run at all; see {@link #getFailure()}. */
        public Answer getAnswer() {
            return answer;
        }

        /** Why the query could not run, or null. Distinct from a query that ran and found nothing. */
        public String getFailure() {
            return failure;
        }

        public boolean isPassed() {
            return failure == null && answer != null && answer.size() == 0;
        }

        public int getViolations() {
            return answer == null ? 0 : answer.size();
        }
    }

    /**
     * Runs one SPARQL {@code SELECT} or {@code ASK} against the open ontology.
     *
     * @throws RobotException if the query cannot be parsed or executed - a curator editing a query
     *     needs the parser's own message, which names the line and column
     */
    public static Answer run(OWLOntology ontology, String sparql) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to query");
        }
        if (sparql == null || sparql.trim().isEmpty()) {
            throw new RobotException("There is no query to run.");
        }

        Query query;
        try {
            query = QueryFactory.create(sparql);
        } catch (RuntimeException cannotParse) {
            // Jena's parse errors name the line and column, which is the whole value of the message
            // to somebody editing a query.
            throw new RobotException(describe(cannotParse), cannotParse);
        }

        Dataset dataset = datasetOf(ontology);
        QueryExecution execution = QueryExecutionFactory.create(query, dataset);
        try {
            if (query.isAskType()) {
                // ASK has no columns. Rendering it as one row of one column keeps every caller -
                // the table in the result window, the check runner - working on one shape.
                boolean answer = execution.execAsk();
                List<List<String>> rows = new ArrayList<List<String>>();
                if (answer) {
                    rows.add(Collections.singletonList("true"));
                }
                return new Answer(Collections.singletonList("ASK"), rows);
            }
            if (!query.isSelectType()) {
                throw new RobotException("Only SELECT and ASK are supported here. A "
                        + describeQueryType(query) + " query changes or builds an ontology, which "
                        + "this panel deliberately does not do - use ROBOT's own commands for that.");
            }

            ResultSet results = execution.execSelect();
            List<String> columns = new ArrayList<String>(results.getResultVars());
            List<List<String>> rows = new ArrayList<List<String>>();
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                List<String> row = new ArrayList<String>();
                for (String column : columns) {
                    row.add(text(solution, column));
                }
                rows.add(Collections.unmodifiableList(row));
            }
            return new Answer(columns, rows);
        } catch (RobotException alreadyExplained) {
            throw alreadyExplained;
        } catch (RuntimeException cannotRun) {
            throw new RobotException("The query could not be run: " + describe(cannotRun),
                    cannotRun);
        } finally {
            execution.close();
        }
    }

    private static String describeQueryType(Query query) {
        if (query.isConstructType()) {
            return "CONSTRUCT";
        }
        if (query.isDescribeType()) {
            return "DESCRIBE";
        }
        return "non-SELECT";
    }

    /**
     * Runs every check file, the way {@code make sparql_test} does.
     *
     * <p>One file's failure does not stop the others: a project with six checks and one unparseable
     * query should still learn about the other five, which is the difference between a report and a
     * stack trace.
     */
    public static List<Check> verify(OWLOntology ontology, List<File> queryFiles) {
        List<Check> checks = new ArrayList<Check>();
        if (queryFiles == null || queryFiles.isEmpty()) {
            return checks;
        }
        for (File file : queryFiles) {
            String name = file.getName();
            try {
                String sparql = new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
                checks.add(new Check(name, run(ontology, sparql), null));
            } catch (IOException cannotRead) {
                checks.add(new Check(name, null, "could not be read: " + cannotRead.getMessage()));
            } catch (RobotException cannotRun) {
                checks.add(new Check(name, null, cannotRun.getMessage()));
            }
        }
        return checks;
    }

    /**
     * The {@code .rq} files an ODK project commits, in a stable order.
     *
     * <p>{@code src/sparql} relative to the project root - where OntoBoard's scaffold writes them
     * and where the generated {@code sparql_test} target looks. Sorted by name so two runs list the
     * checks in the same order.
     */
    public static List<File> checksIn(File projectRoot) {
        List<File> found = new ArrayList<File>();
        if (projectRoot == null) {
            return found;
        }
        File directory = new File(projectRoot, CHECKS_DIRECTORY);
        File[] children = directory.listFiles();
        if (children == null) {
            return found;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase().endsWith(".rq")) {
                found.add(child);
            }
        }
        Collections.sort(found, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return found;
    }

    /**
     * A query that returns every class with no label - a starting point that always parses.
     *
     * <p>Offered as the initial text of the query box rather than an empty field, because the first
     * thing anybody needs from a SPARQL panel is a working example of the prefixes.
     */
    public static String exampleQuery() {
        return join(Arrays.asList(
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>",
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>",
                "",
                "SELECT ?class WHERE {",
                "  ?class a owl:Class .",
                "  FILTER NOT EXISTS { ?class rdfs:label ?label }",
                "  FILTER (!isBlank(?class))",
                "}",
                "ORDER BY ?class"));
    }

    /**
     * Writes an answer as TSV, the way ODK's {@code custom_reports} target does.
     *
     * <p>That target is {@code robot query -f tsv -i <edit> -s <query>.sparql <report>.tsv} for each
     * export a project configures, and the file it produces is the point: a report somebody reads
     * in a spreadsheet or a script diffs between releases. Running the query and looking at it on
     * screen is the other half of the same job, which is why both are offered.
     *
     * <p>Tabs and newlines inside a value become spaces. A TSV whose cells contain tabs is not a
     * TSV, and a definition with a newline in it would silently shift every following column.
     *
     * @throws RobotException if the file cannot be written
     */
    public static void writeTsv(Answer answer, File file) {
        if (answer == null || file == null) {
            throw new IllegalArgumentException("an answer and a file are both needed");
        }
        StringBuilder text = new StringBuilder();
        appendRow(text, answer.getColumns());
        for (List<String> row : answer.getRows()) {
            appendRow(text, row);
        }
        try {
            Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException cannotWrite) {
            throw new RobotException("Could not write " + file.getName() + ": "
                    + cannotWrite.getMessage(), cannotWrite);
        }
    }

    private static void appendRow(StringBuilder text, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append('\t');
            }
            String value = values.get(i);
            text.append(value == null ? "" : value.replaceAll("[\\t\\r\\n]", " "));
        }
        text.append('\n');
    }

    /** The ontology and its imports as a Jena dataset ROBOT can query. */
    private static Dataset datasetOf(OWLOntology ontology) {
        // DatasetFactory.create copies the model into the default graph, which is what an
        // unqualified SPARQL pattern matches - wrap() would leave it addressable only by name.
        return DatasetFactory.create(OntologyDataset.modelOf(ontology));
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

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        return text.toString();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getName()
                : message;
    }
}
