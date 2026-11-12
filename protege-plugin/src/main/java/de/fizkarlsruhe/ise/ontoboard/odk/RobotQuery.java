package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.cli.CommandLine;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.ResultSetMgr;
import org.apache.jena.riot.resultset.ResultSetLang;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

/**
 * {@code robot query} as ODK's recipes use it, on OntoBoard's own SPARQL engine.
 *
 * <p>ROBOT's own {@code QueryOperation} cannot even be loaded inside Protégé: its constant pool
 * names the OWL API's RDF4J renderer, which no Protégé bundle exports (see
 * {@code robot.SparqlQuery}). What it does is small, and copied here from its bytecode (ROBOT
 * 1.9.8): the ontology is made a Jena dataset, each query file is read, its output file opened,
 * the query run, and its rows written by Jena's own writer for the format - nothing at all when
 * there is no row, so the file is left empty.
 *
 * <p>The format is the {@code --format} value, else the output's extension; its name is
 * lowercased, and {@code tsv} is TSV and {@code csv} CSV. A name ROBOT does not know is CSV: that
 * is why ODK's {@code --format --csv} (the parser takes {@code --csv}, no option of query, as the
 * value) writes CSV - and so does {@code --format --tsv}. The names ROBOT maps to RDF or XML
 * writers ({@code ttl jsonld nt nq xml sxml}) are refused here.
 *
 * <p>Refused by name before anything runs: every option of query ODK's recipes do not use -
 * {@code --update}, {@code --temporary-file}, the TDB options, {@code --output},
 * {@code --output-dir}, {@code --queries}, {@code --construct}, {@code --input-iri} - and a query
 * that is no SELECT, when its file can be read beforehand.
 */
final class RobotQuery {

    /** query's own options that ODK's recipes do not use, by long name. */
    static final List<String> NOT_USED = Arrays.asList("update", "temporary-file", "tdb",
            "create-tdb", "keep-tdb-mappings", "tdb-directory", "output", "output-dir", "queries",
            "construct", "input-iri");

    /** The format names ROBOT gives to writers of RDF or XML: no CSV or TSV is written. */
    static final List<String> NOT_WRITTEN = Arrays.asList("ttl", "jsonld", "nt", "nq", "xml",
            "sxml");

    private RobotQuery() {
    }

    /** One query of a call: its file and its output file, as given. */
    static final class Pair {
        final String query;
        final String output;

        Pair(String query, String output) {
            this.query = query;
            this.output = output;
        }
    }

    /** The pairs in ROBOT's order: every --query, then every --select. */
    static List<Pair> pairs(CommandLine line) {
        List<Pair> pairs = new ArrayList<Pair>();
        for (String option : new String[] {"query", "select"}) {
            String[] values = line.getOptionValues(option);
            for (int i = 0; values != null && i + 1 < values.length; i += 2) {
                pairs.add(new Pair(values[i], values[i + 1]));
            }
        }
        return pairs;
    }

    /**
     * Why this call of query is not run here, or null. Reads nothing but the query files, and
     * those only when they are there: a non-SELECT query is refused before anything runs.
     */
    static String whyNot(CommandLine line) {
        for (String option : NOT_USED) {
            if (line.hasOption(option)) {
                return "'query --" + option + "' is not run inside Protege: OntoBoard runs robot "
                        + "query with the options ODK's recipes give it (--input, --format, "
                        + "--query, --select, --use-graphs), on its own SPARQL engine. A recipe "
                        + "that needs --" + option + " needs ODK's container.";
            }
        }
        List<Pair> pairs = pairs(line);
        if (pairs.isEmpty()) {
            return "'query' without a query: give it --query <file> <output>, as ODK's recipes "
                    + "do.";
        }
        for (Pair pair : pairs) {
            String format = formatOf(line, pair.output);
            if (NOT_WRITTEN.contains(format)) {
                return "'query' as " + format + " (" + pair.output + ") is not written inside "
                        + "Protege: OntoBoard writes the results of a query as CSV or TSV, the "
                        + "formats ODK's recipes ask for.";
            }
            File file = new File(pair.query);
            if (file.isFile()) {
                String type = typeOf(file);
                if (type != null && !type.equals("SELECT")) {
                    return "'query " + pair.query + "' is a " + type + " query; inside Protege "
                            + "robot query runs SELECT queries only, as ODK's recipes give it. "
                            + "Nothing was run.";
                }
            }
        }
        return null;
    }

    /** SELECT, ASK, CONSTRUCT, DESCRIBE; null when the file does not parse. */
    private static String typeOf(File file) {
        try {
            Query query = QueryFactory.create(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
            return query.isSelectType() ? "SELECT" : query.isAskType() ? "ASK"
                    : query.isConstructType() ? "CONSTRUCT" : query.isDescribeType() ? "DESCRIBE"
                    : "non-SELECT";
        } catch (IOException | RuntimeException unreadable) {
            // The run says what is wrong with it, where ROBOT says it.
            return null;
        }
    }

    /** ROBOT's format name: --format, else the output's extension; lowercased. */
    static String formatOf(CommandLine line, String output) {
        String format = line.getOptionValue("format");
        if (format == null) {
            String name = output.substring(Math.max(output.lastIndexOf('/'),
                    output.lastIndexOf('\\')) + 1);
            int dot = name.lastIndexOf('.');
            format = dot < 0 ? "" : name.substring(dot + 1);
        }
        return format.toLowerCase(Locale.ROOT);
    }

    /** ROBOT's QueryOperation.getFormatLang, for result sets: TSV for tsv, CSV for the rest. */
    static Lang langOf(String format) {
        return format.equals("tsv") ? ResultSetLang.SPARQLResultSetTSV : Lang.CSV;
    }

    /**
     * Runs every query of the call over the ontology and writes each output file.
     *
     * @throws IOException where ROBOT fails: a query file that is not there, one that does not
     *                     parse, an output that cannot be written
     */
    static void run(OWLOntology ontology, CommandLine line, boolean useGraphs)
            throws IOException {
        Dataset dataset = datasetOf(ontology, useGraphs);
        try {
            for (Pair pair : pairs(line)) {
                File queryFile = new File(pair.query);
                if (!queryFile.isFile()) {
                    throw new IOException("File '" + pair.query + "' does not exist");
                }
                String text = new String(Files.readAllBytes(queryFile.toPath()),
                        StandardCharsets.UTF_8);
                Lang lang = langOf(formatOf(line, pair.output));
                // ROBOT opens the output before it parses the query, and writes only rows.
                OutputStream out = new FileOutputStream(pair.output);
                try {
                    Query query;
                    try {
                        query = QueryFactory.create(text);
                    } catch (RuntimeException cannotParse) {
                        throw new IOException("QUERY PARSE ERROR query cannot be parsed: "
                                + cannotParse.getMessage(), cannotParse);
                    }
                    if (!query.isSelectType()) {
                        throw new IOException("'query " + pair.query + "' is no SELECT query; "
                                + "inside Protege robot query runs SELECT queries only.");
                    }
                    QueryExecution execution = QueryExecutionFactory.create(query, dataset);
                    try {
                        ResultSet rows = execution.execSelect();
                        if (rows.hasNext()) {
                            ResultSetMgr.write(out, rows, lang);
                        }
                    } finally {
                        execution.close();
                    }
                } finally {
                    out.close();
                }
            }
        } finally {
            dataset.close();
        }
    }

    // ------------------------------------------------------------------ the dataset

    /**
     * The ontology as ROBOT's {@code QueryOperation.loadOntologyAsDataset} makes it: a named graph
     * for the ontology - and, with {@code useGraphs}, one for each ontology of its imports
     * closure - named by the ontology's IRI, and the union of them as the default graph. Each
     * graph holds one ontology's own statements, its import declarations among them.
     */
    static Dataset datasetOf(OWLOntology ontology, boolean useGraphs) throws IOException {
        Set<OWLOntology> ontologies = new LinkedHashSet<OWLOntology>();
        ontologies.add(ontology);
        if (useGraphs) {
            ontologies.addAll(ontology.getImports());
        }
        Dataset dataset = DatasetFactory.createGeneral();
        for (OWLOntology one : ontologies) {
            String name = one.getOntologyID().getOntologyIRI().isPresent()
                    ? one.getOntologyID().getOntologyIRI().get().toString()
                    : "urn:uuid:" + UUID.randomUUID();
            dataset.addNamedModel(name, modelOf(one));
        }
        dataset.setDefaultModel(dataset.getUnionModel());
        return dataset;
    }

    /**
     * One ontology's own statements as a Jena model: written by the OWL API as RDF/XML, which
     * every OWL API in a Protégé can do, and read by Jena (ROBOT goes through the RDF4J renderer
     * instead, which is the part Protégé lacks).
     */
    private static Model modelOf(OWLOntology ontology) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        try {
            ontology.getOWLOntologyManager().saveOntology(ontology, new RDFXMLDocumentFormat(),
                    bytes);
        } catch (OWLOntologyStorageException cannotWrite) {
            throw new IOException("The ontology could not be written as RDF for the query: "
                    + cannotWrite.getMessage(), cannotWrite);
        }
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(bytes.toByteArray()), Lang.RDFXML);
        return model;
    }
}
