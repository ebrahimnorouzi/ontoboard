package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.OntologyHelper;
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
 * <p>ROBOT's own {@code report} does not run inside Protégé ({@link OdkRobot} says why); the
 * engine runs ROBOT's rules and hands over every row with the terms it bound
 * ({@link de.fizkarlsruhe.ise.ontoboard.robot.QualityReport#rows}). What ROBOT makes of the rows
 * is here, read from robot-core 1.9.8's bytecode and kept in ROBOT's order:
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
