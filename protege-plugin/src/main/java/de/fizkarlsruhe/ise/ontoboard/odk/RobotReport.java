package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.ReportQueries;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.OntologyHelper;
import org.obolibrary.robot.ReportOperation;
import org.obolibrary.robot.export.RendererType;
import org.obolibrary.robot.providers.CURIEShortFormProvider;
import org.obolibrary.robot.providers.QuotedAnnotationValueShortFormProvider;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.ShortFormProvider;

/**
 * ROBOT's report as ROBOT 1.9 writes and prints it, made from the rows OntoBoard's engine found.
 *
 * <p>ROBOT's own {@code report} does not run inside Protégé ({@link OdkRobot} says why). What it
 * does is here, read from robot-core 1.9.8's bytecode and kept in ROBOT's order:
 * {@code ReportOperation.getProfile} reads the profile, {@code getReportQueries} finds each
 * rule's query - ROBOT's own, or a file of the project's - and {@code getReport} runs them over
 * the ontology as ROBOT's dataset holds it, failing where ROBOT fails, in ROBOT's words;
 * {@code ReportOperation.getViolationsFromResults} reads a row, {@code Report.addToTable} writes
 * its cells with the short forms {@code Report.getProvider} chooses, {@code Table.toList} and
 * {@code IOHelper.writeTable} (opencsv's CSVWriter) make the file, and
 * {@code ReportOperation.processReport} prints the counts and the first rows. Where ROBOT calls a
 * class of its own for a cell - an IRI from a term, a short form, the rendering - that class is
 * called here too.
 */
final class RobotReport {

    /** The five columns of ROBOT's table, as {@code Report} names them. */
    private static final String[] HEADER = {"Level", "Rule Name", "Subject", "Property", "Value"};

    /** ROBOT's levels, in the order of its table and of its counts. */
    private static final String[] LEVELS = {"ERROR", "WARN", "INFO"};

    private RobotReport() {
    }

    /**
     * How ROBOT writes a term in a cell. With {@code --labels} a term is its {@code rdfs:label}
     * in the input's ontologies, else its CURIE by ROBOT's prefixes, else its IRI; without, the
     * CURIE or the IRI ({@code Report.getProvider}). Each is rendered as
     * {@code OntologyHelper.renderManchester} renders a class: line breaks taken out, runs of
     * spaces made one.
     */
    static final class Cells {
        private final IOHelper io;
        private final ShortFormProvider provider;
        private final String ontology;
        private final OWLDataFactory factory = OWLManager.getOWLDataFactory();

        /**
         * @param ontology the report's input as ROBOT loaded it: its manager's ontologies are where
         *                 labels are looked up, its IRI is what a finding about it is called
         * @param io       the command's IOHelper: ROBOT's prefixes and the ones the line added
         * @param labels   whether {@code --labels} asked for labels
         */
        Cells(OWLOntology ontology, IOHelper io, boolean labels) {
            this.io = io;
            ShortFormProvider curies = new CURIEShortFormProvider(io.getPrefixes());
            this.provider = labels ? new QuotedAnnotationValueShortFormProvider(
                    ontology.getOWLOntologyManager(), curies, io.getPrefixManager(),
                    Collections.singletonList(factory.getRDFSLabel()),
                    Collections.<OWLAnnotationProperty, List<String>>emptyMap()) : curies;
            IRI named = ontology.getOntologyID().getOntologyIRI().orNull();
            this.ontology = named == null ? null : named.toString();
        }

        /**
         * The Subject of a row whose {@code ?entity} was bound. A term ROBOT cannot make an IRI
         * of - a blank node's label, or the text "blank node" some of its queries bind - is a
         * violation ROBOT calls "blank node"; the ontology's own IRI is written in full; any
         * other is a term.
         */
        String subject(String entity) {
            IRI iri = io.createIRI(entity);
            if (iri == null) {
                return "blank node";
            }
            if (ontology != null && iri.toString().equals(ontology)) {
                return ontology;
            }
            return term(iri);
        }

        /**
         * The Value of a row: a term when ROBOT can make an IRI of it, else the text as Jena
         * wrote it - for a literal its text, then {@code @language} or {@code ^^datatype}.
         */
        String value(String value) {
            IRI iri = io.createIRI(value);
            return iri == null ? value : term(iri);
        }

        /** ROBOT's IRI for a term, or null when it cannot make one ({@code IOHelper.createIRI}). */
        IRI iri(String term) {
            return io.createIRI(term);
        }

        /** A term as a cell: ROBOT makes a class of it and renders that. */
        String term(IRI iri) {
            return OntologyHelper.renderManchester(factory.getOWLClass(iri), provider,
                    RendererType.OBJECT_RENDERER);
        }
    }

    /** ROBOT's table, and how many violations it counted at each level and for each rule. */
    static final class Table {
        private final List<String[]> rows;
        private final Map<String, Integer> levels;
        private final Map<String, Integer> rules;

        private Table(List<String[]> rows, Map<String, Integer> levels,
                Map<String, Integer> rules) {
            this.rows = Collections.unmodifiableList(rows);
            this.levels = levels;
            this.rules = Collections.unmodifiableMap(rules);
        }

        /** The header, then the rows in ROBOT's order. */
        List<String[]> rows() {
            return rows;
        }

        /** The violations at a level: also those without a row of their own. */
        int count(String level) {
            Integer count = levels.get(level);
            return count == null ? 0 : count;
        }

        int total() {
            return count("ERROR") + count("WARN") + count("INFO");
        }

        /** "LEVEL rule" to its violations, in the table's order. */
        Map<String, Integer> rules() {
            return rules;
        }
    }

    /**
     * ROBOT's table of the rows: each read as {@code getViolationsFromResults} reads it, written
     * as {@code Report.addToTable} writes it.
     *
     * <p>A row without {@code ?entity} fails the report. A row about a term of RDF Schema or OWL
     * ({@code /rdf-schema#}, {@code /owl#}) is left out, and so, when there are base namespaces,
     * is a row whose entity is in none of them. Every other row is a violation; a violation
     * without {@code ?property} is counted but has no line. The lines go level by level (ERROR,
     * WARN, INFO), rule by rule by the names' {@code String.compareTo}, and a rule's lines in the
     * order its query gave them.
     *
     * @param rows  the rows, with their bound terms ({@link QualityFinding#getBound()})
     * @param bases the base namespaces of {@code --base-iri}; empty for none
     * @throws IllegalStateException in ROBOT's words, when a row has no entity or ROBOT could
     *                               not make an IRI of its property
     */
    static Table table(List<QualityFinding> rows, Cells cells, Collection<String> bases) {
        Map<String, TreeMap<String, List<String[]>>> lines =
                new LinkedHashMap<String, TreeMap<String, List<String[]>>>();
        Map<String, TreeMap<String, Integer>> counted =
                new LinkedHashMap<String, TreeMap<String, Integer>>();
        for (String level : LEVELS) {
            lines.put(level, new TreeMap<String, List<String[]>>());
            counted.put(level, new TreeMap<String, Integer>());
        }
        for (QualityFinding row : rows) {
            String level = row.getSeverity().name();
            String rule = row.getRule();
            QualityFinding.Bound bound = row.getBound();
            String entity = bound == null ? null : bound.entity();
            if (entity == null) {
                throw missingEntity(rule);
            }
            if (entity.contains("/rdf-schema#") || entity.contains("/owl#")) {
                continue;
            }
            if (!bases.isEmpty() && !inOneOf(entity, bases)) {
                continue;
            }
            String subject = cells.subject(entity);
            Integer before = counted.get(level).get(rule);
            counted.get(level).put(rule, before == null ? 1 : before + 1);
            if (!lines.get(level).containsKey(rule)) {
                lines.get(level).put(rule, new ArrayList<String[]>());
            }
            if (bound.property() == null) {
                continue;
            }
            IRI property = cells.iri(bound.property());
            if (property == null) {
                // ROBOT makes a class of it outside its try: the rule's query is said to fail.
                throw missingEntity(rule);
            }
            lines.get(level).get(rule).add(new String[] {level, rule, subject,
                cells.term(property), bound.value() == null ? "" : cells.value(bound.value())});
        }
        List<String[]> table = new ArrayList<String[]>();
        table.add(HEADER.clone());
        Map<String, Integer> levels = new LinkedHashMap<String, Integer>();
        Map<String, Integer> rules = new LinkedHashMap<String, Integer>();
        for (String level : LEVELS) {
            int count = 0;
            for (Map.Entry<String, Integer> rule : counted.get(level).entrySet()) {
                count += rule.getValue();
                rules.put(level + " " + rule.getKey(), rule.getValue());
                table.addAll(lines.get(level).get(rule.getKey()));
            }
            levels.put(level, count);
        }
        return new Table(table, levels, rules);
    }

    // ------------------------------------------------------------------ the rules ROBOT runs

    /**
     * ROBOT's profile as {@code ReportOperation.getProfile} reads it: rule to level, in a
     * HashMap as ROBOT's (the order ROBOT's sets of rules are made in follows it), and the rules
     * in the order of their first lines, which is the order they are run in here.
     */
    static final class Profile {
        private final Map<String, String> levels;
        private final List<String> order;

        private Profile(Map<String, String> levels, List<String> order) {
            this.levels = levels;
            this.order = order;
        }
    }

    /** One of ROBOT's report queries ({@code ReportQuery}): its name, level and query. */
    static final class Rule {
        private final String name;
        private final String level;
        private final String query;

        private Rule(String name, String level, String query) {
            this.name = name;
            this.level = level;
            this.query = query;
        }
    }

    /**
     * The profile, read as ROBOT reads it: the file given, else ROBOT's own; each line trimmed
     * and split at tabs, its first field a level - INFO, WARN or ERROR in any case - and its
     * second the rule. Anything else fails in ROBOT's words: a blank line and a comment too are
     * levels ROBOT does not know. A level without a rule fails as ROBOT's does, with the JVM's
     * word for a field that is not there; a file that is not there as the JVM says it.
     */
    static Profile profile(String path) throws IOException {
        InputStream in = path == null
                ? ReportOperation.class.getResourceAsStream("/report_profile.txt")
                : new FileInputStream(path);
        Map<String, String> levels = new HashMap<String, String>();
        List<String> order = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8));
        try {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.trim().split("\t");
                String level = parts[0].toUpperCase(Locale.ROOT);
                if (!"INFO".equals(level) && !"WARN".equals(level) && !"ERROR".equals(level)) {
                    throw new IllegalArgumentException(String.format(
                            "report#REPORT LEVEL ERROR '%s' is not a valid reporting level.",
                            parts[0]));
                }
                String rule = parts[1];
                if (!levels.containsKey(rule)) {
                    order.add(rule);
                }
                levels.put(rule, level);
            }
        } finally {
            reader.close();
        }
        return new Profile(levels, order);
    }

    /**
     * The profile's rules with their queries, found as {@code getReportQueries} finds them:
     * first every name ROBOT ships a query for, failing in ROBOT's words when one is not
     * (all such names, in the order of ROBOT's HashSet); then every {@code file:} rule's file,
     * failing when one is not there; a {@code file:} rule is named after its file, without the
     * folder and the extension. A path that does not start at a root is read against the folder
     * the line runs in, as ROBOT reads it against its own.
     *
     * @param folder the folder the line runs in - for a recipe, the folder make runs in - or
     *               null when it is not known
     * @throws IllegalStateException for a {@code file:} rule by a path that does not start at a
     *                               root when the folder is not known - refused before the line
     *                               runs ({@link #whyNotRules}); here only should the profile
     *                               have changed in between
     */
    static List<Rule> rules(Profile profile, File folder) throws Exception {
        Set<String> shipped = new HashSet<String>();
        Set<String> own = new HashSet<String>();
        split(profile, shipped, own);
        Map<String, String> queries = new HashMap<String, String>();
        for (String rule : new ArrayList<String>(shipped)) {
            String query = shipped(rule);
            if (query != null) {
                queries.put(rule, query);
                shipped.remove(rule);
            }
        }
        if (!shipped.isEmpty()) {
            throw new IOException(String.format("report#UNKNOWN REPORT QUERY one or more rule "
                    + "names ('%s') are not valid default rules", String.join(", ", shipped)));
        }
        for (String rule : own) {
            // ROBOT's own word on the file is its path as the profile has it.
            File named = queryFile(rule);
            File file = where(named, folder);
            if (file == null) {
                throw new IllegalStateException(notHere(rule));
            }
            if (!file.exists()) {
                throw new IOException(String.format(
                        "report#MISSING QUERY ERROR query at '%s' does not exist.",
                        named.getPath()));
            }
            queries.put(rule, new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
        }
        List<Rule> rules = new ArrayList<Rule>();
        for (String key : profile.order) {
            String name = key.startsWith("file:")
                    ? key.substring(key.lastIndexOf("/") + 1, key.lastIndexOf(".")) : key;
            rules.add(new Rule(name, profile.levels.get(key), queries.get(key)));
        }
        return rules;
    }

    /**
     * Why the profile's rules cannot be run here as ROBOT runs them, or null: a {@code file:}
     * rule by a path that does not start at a root, which ROBOT reads against the folder it
     * runs in, when that folder is not known. Null as well where ROBOT fails before it gets to
     * that rule - a profile it cannot read, a rule it does not ship, a file before it that is
     * not there: the line then runs and fails as ROBOT fails.
     *
     * @param folder the folder the line runs in, or null when it is not known
     */
    static String whyNotRules(String path, File folder) {
        Set<String> shipped = new HashSet<String>();
        Set<String> own = new HashSet<String>();
        try {
            split(profile(path), shipped, own);
            for (String rule : shipped) {
                if (shipped(rule) == null) {
                    return null;
                }
            }
            for (String rule : own) {
                File file = where(queryFile(rule), folder);
                if (file == null) {
                    return notHere(rule);
                }
                if (!file.exists()) {
                    return null;
                }
            }
        } catch (Exception failsWhenRun) {
            return null;
        }
        return null;
    }

    private static String notHere(String rule) {
        return "The profile's rule '" + rule + "' names its query by a path that does not start "
                + "at a root. ROBOT reads such a path against the folder it runs in, and for this "
                + "line that folder is not known here, so the report is not run. Give the path "
                + "from its root.";
    }

    /**
     * Where a rule's file is: where its path says when that starts at a root, else in the folder
     * the line runs in; null when the path does not start at a root and no folder is known.
     */
    private static File where(File named, File folder) {
        if (named.isAbsolute()) {
            return named;
        }
        return folder == null ? null : new File(folder, named.getPath());
    }

    /** The profile's rules as ROBOT sorts them: names it ships, and {@code file:} rules. */
    private static void split(Profile profile, Set<String> shipped, Set<String> own) {
        for (String rule : profile.levels.keySet()) {
            if (rule.startsWith("file:")) {
                own.add(rule);
            } else {
                shipped.add(rule);
            }
        }
    }

    /**
     * The query ROBOT ships under exactly this name, from its first "PREFIX" on, as
     * {@code getDefaultQueryStrings} keeps it; null for a name it ships none under.
     */
    private static String shipped(String rule) {
        if (rule.isEmpty() || !rule.equals(rule.trim()) || rule.indexOf('/') >= 0
                || rule.indexOf('\\') >= 0) {
            return null;
        }
        String query = ReportQueries.queryFor(rule);
        return query == null ? null : query.substring(query.indexOf("PREFIX"));
    }

    /** A {@code file:} rule's file: a {@code file:///} URL, else the path after "file:". */
    private static File queryFile(String rule) throws Exception {
        if (rule.startsWith("file:///")) {
            return new File(new URL(rule).toURI());
        }
        return new File(rule.substring(5));
    }

    /**
     * Every rule's query over the dataset, as {@code getReport} runs it: the lines that start
     * with "#" left out, each row's terms as ROBOT reads them. A query that cannot be parsed or
     * run, or a row without {@code ?entity}, fails the report in ROBOT's words; a row that
     * cannot be read ends its query's rows, as in ROBOT.
     */
    static List<QualityFinding> rows(List<Rule> rules, Dataset dataset) {
        List<QualityFinding> rows = new ArrayList<QualityFinding>();
        for (Rule rule : rules) {
            List<String> kept = new ArrayList<String>();
            for (String line : rule.query.split("\n")) {
                if (!line.startsWith("#")) {
                    kept.add(line);
                }
            }
            try {
                QueryExecution execution = QueryExecutionFactory.create(String.join("\n", kept),
                        dataset);
                try {
                    ResultSet results = execution.execSelect();
                    while (results.hasNext()) {
                        QuerySolution row;
                        try {
                            row = results.next();
                        } catch (RuntimeException cannotRead) {
                            break;
                        }
                        String entity = term(row, "entity");
                        if (entity == null) {
                            throw missingEntity(rule.name);
                        }
                        rows.add(new QualityFinding(QualityFinding.Severity.of(rule.level),
                                rule.name, entity, "", "", "", new QualityFinding.Bound(entity,
                                        term(row, "property"), term(row, "value"))));
                    }
                } finally {
                    execution.close();
                }
            } catch (RuntimeException cannotRun) {
                throw missingEntity(rule.name);
            }
        }
        return rows;
    }

    /** A term of a row as ROBOT reads it: Jena's {@code RDFNode.toString()}, null if unbound. */
    private static String term(QuerySolution row, String variable) {
        RDFNode node = row.get(variable);
        return node == null ? null : node.toString();
    }

    private static boolean inOneOf(String entity, Collection<String> bases) {
        for (String base : bases) {
            if (entity.startsWith(base)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException missingEntity(String rule) {
        return new IllegalStateException(String.format(
                "report#MISSING ENTITY BINDING query '%s' must include an '?entity'", rule));
    }

    /** Whether ROBOT's option is true: "true" or "yes", trimmed, in any case. */
    static boolean isTrue(String option) {
        if (option == null) {
            return false;
        }
        String value = option.trim().toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("yes");
    }

    // ------------------------------------------------------------------ what ROBOT prints

    /** What ROBOT prints first, before anything is written: the counts. */
    static void printCounts(Table table, PrintStream out) {
        int total = table.total();
        if (total == 0) {
            out.println("No violations found.");
            return;
        }
        out.println("Violations: " + total);
        out.println("-----------------");
        out.println("ERROR:      " + table.count("ERROR"));
        out.println("WARN:       " + table.count("WARN"));
        out.println("INFO:       " + table.count("INFO"));
    }

    /**
     * {@code --print} as ROBOT reads it, trimmed: how many rows to print.
     *
     * @throws IllegalArgumentException in ROBOT's words, when it is not an integer
     */
    static int print(String option) {
        String print = (option == null ? "0" : option).trim();
        try {
            return Integer.parseInt(print);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(String.format(
                    "report#PRINT NUMBER ERROR --print argument '%s' must be an integer.", print));
        }
    }

    /**
     * The first rows, as ROBOT prints them for {@code --print}: an empty line, how many, and each
     * row's cells joined by the separator - not quoted. More asked for than there are: all.
     */
    static void printFirst(List<String[]> table, int print, String separator, PrintStream out) {
        int first = table.size() > print ? print : table.size() - 1;
        out.println(String.format("\nFirst %d violations:", first));
        for (int i = 0; i < first; i++) {
            out.println(String.join(separator, table.get(i + 1)));
        }
    }

    // ------------------------------------------------------------------ the file

    /** The format ROBOT writes: {@code --format}, else the output's name after its last dot. */
    static String format(String format, String output) {
        if (format == null && output != null) {
            return output.substring(output.lastIndexOf('.') + 1);
        }
        return format == null ? "tsv" : format;
    }

    /**
     * Why a format is not written here, or null. ROBOT writes YAML, JSON, HTML and Excel with
     * writers of their own, and renders the cells as HTML for any format whose name starts with
     * "html"; none of that is ported. CSV is written, and every other format is TSV, as ROBOT
     * writes it.
     */
    static String whyNot(String format) {
        String lower = format.toLowerCase(Locale.ROOT);
        if (lower.equals("yaml") || lower.equals("json") || lower.equals("xlsx")
                || lower.startsWith("html")) {
            return "'report' as " + format + ": ROBOT writes that format with a writer of its "
                    + "own, which is not run inside Protege. Here a report is written as TSV or "
                    + "CSV, which is what ODK's Makefile asks for.";
        }
        return null;
    }

    /** The separator of the table: a comma for CSV, a tab for every other format written. */
    static String separator(String format) {
        return format.toLowerCase(Locale.ROOT).equals("csv") ? "," : "\t";
    }

    /**
     * The file, as ROBOT's table writer writes it (opencsv's CSVWriter with {@code "} to quote
     * and to escape): a cell is quoted only when it holds a quotation mark, the separator or a
     * line break, and its quotation marks are doubled; every row ends with a line feed, the
     * last one too.
     */
    static String text(List<String[]> table, String separator) {
        StringBuilder text = new StringBuilder();
        for (String[] row : table) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    text.append(separator);
                }
                text.append(cell(row[i], separator));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String cell(String value, String separator) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('"') < 0 && !value.contains(separator) && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
