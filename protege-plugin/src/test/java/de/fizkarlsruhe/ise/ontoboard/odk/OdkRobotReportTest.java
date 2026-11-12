package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.obolibrary.robot.IOHelper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * {@code robot report} as ROBOT 1.9 writes and prints it, when OntoBoard runs the line itself.
 *
 * <p>What each cell holds is read from ROBOT's bytecode ({@code Report.addToTable},
 * {@code Report.getProvider}, {@code ReportOperation.getViolationsFromResults} and
 * {@code processReport}): a term is its label with {@code --labels true}, else its CURIE by
 * ROBOT's prefixes, else its IRI; the ontology's own IRI always in full; a literal as Jena
 * writes it; the rows level by level, rule by rule. The ontologies are made up.
 */
class OdkRobotReportTest {

    private static final String PREFIXES = "Prefix(:=<https://example.org/e/>)\n"
            + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
            + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
            + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
            + "Prefix(dcterms:=<http://purl.org/dc/terms/>)\n"
            + "Prefix(obo:=<http://purl.obolibrary.org/obo/>)\n";

    private static final String HEADER = "Level\tRule Name\tSubject\tProperty\tValue";

    /**
     * A labelled class with a definition that starts in lower case, and a class with no label:
     * what ROBOT's missing_label, lowercase_definition and missing_superclass find.
     */
    private static final String KITCHEN = ""
            + "Declaration(AnnotationProperty(obo:IAO_0000115))\n"
            + "AnnotationAssertion(rdfs:label obo:IAO_0000115 \"definition\")\n"
            + "Declaration(Class(:Egg))\n"
            + "AnnotationAssertion(rdfs:label :Egg \"boiled egg\"@en)\n"
            + "AnnotationAssertion(obo:IAO_0000115 :Egg \"an egg kept in boiling water.\"@en)\n"
            + "Declaration(Class(:Pan))\n"
            + "AnnotationAssertion(rdfs:comment :Pan \"no label\")\n";

    /** The three rules KITCHEN is about, out of order: ROBOT sorts the rules itself. */
    private static final String[] KITCHEN_RULES = {"INFO\tmissing_superclass",
        "ERROR\tmissing_label", "INFO\tlowercase_definition"};

    private static File ontology(File folder, String body) throws IOException {
        File file = new File(folder, "kitchen.ofn");
        Files.write(file.toPath(), (PREFIXES + "Ontology(<https://example.org/e/kitchen.owl>\n"
                + body + ")\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static File profile(File folder, String... rules) throws IOException {
        File file = new File(folder, "profile.txt");
        Files.write(file.toPath(), (String.join("\n", rules) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String path(File file) {
        return file.getAbsolutePath();
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** A report line: the global options, then report and its own. */
    private static OdkRobot.Result report(List<String> global, String... options) {
        List<String> line = new ArrayList<String>(global);
        line.add("report");
        line.addAll(Arrays.asList(options));
        return OdkRobot.run(line);
    }

    private static final List<String> NO_GLOBALS = Collections.<String>emptyList();

    private static final List<String> EX = Arrays.asList("--prefix", "ex: https://example.org/e/");

    // ------------------------------------------------------------------ what a cell holds

    @ParameterizedTest
    @ValueSource(strings = {"true", "yes", "TRUE"})
    void withLabelsATermIsItsLabelElseItsCurieElseItsIri(String labels, @TempDir File temp)
            throws Exception {
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = report(NO_GLOBALS, "--input", path(ontology(temp, KITCHEN)),
                "--profile", path(profile(temp, KITCHEN_RULES)), "--labels", labels,
                "--fail-on", "none", "--output", path(report));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n"
                + "ERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t\n"
                + "INFO\tlowercase_definition\tboiled egg\tdefinition\t"
                + "an egg kept in boiling water.@en\n"
                + "INFO\tmissing_superclass\tboiled egg\trdfs:subClassOf\t\n"
                + "INFO\tmissing_superclass\thttps://example.org/e/Pan\trdfs:subClassOf\t\n",
                text(report));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "no", ""})
    void withoutLabelsATermIsItsCurieElseItsIri(String labels, @TempDir File temp)
            throws Exception {
        File report = new File(temp, "report.tsv");
        List<String> options = new ArrayList<String>(Arrays.asList("--input",
                path(ontology(temp, KITCHEN)), "--profile", path(profile(temp, KITCHEN_RULES)),
                "--fail-on", "none", "--output", path(report)));
        if (!labels.isEmpty()) {
            options.addAll(Arrays.asList("--labels", labels));
        }

        OdkRobot.Result result = report(NO_GLOBALS, options.toArray(new String[0]));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n"
                + "ERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t\n"
                + "INFO\tlowercase_definition\thttps://example.org/e/Egg\tIAO:0000115\t"
                + "an egg kept in boiling water.@en\n"
                + "INFO\tmissing_superclass\thttps://example.org/e/Egg\trdfs:subClassOf\t\n"
                + "INFO\tmissing_superclass\thttps://example.org/e/Pan\trdfs:subClassOf\t\n",
                text(report));
    }

    /** A prefix given to ROBOT is one of ROBOT's prefixes: the unlabelled term is a CURIE. */
    @Test
    void aPrefixGivenToRobotMakesTheCurie(@TempDir File temp) throws Exception {
        File labelled = new File(temp, "labelled.tsv");
        File plain = new File(temp, "plain.tsv");
        File kitchen = ontology(temp, KITCHEN);
        File rules = profile(temp, KITCHEN_RULES);

        OdkRobot.Result withLabels = report(EX, "--input", path(kitchen), "--profile",
                path(rules), "-l", "true", "--fail-on", "none", "-o", path(labelled));
        OdkRobot.Result without = report(EX, "--input", path(kitchen), "--profile",
                path(rules), "--fail-on", "none", "-o", path(plain));

        assertTrue(withLabels.ok(), String.valueOf(withLabels.failure()));
        assertTrue(without.ok(), String.valueOf(without.failure()));
        assertTrue(text(labelled).contains("\nINFO\tmissing_superclass\tboiled egg\t"
                + "rdfs:subClassOf\t\nINFO\tmissing_superclass\tex:Pan\trdfs:subClassOf\t\n"),
                text(labelled));
        assertTrue(text(plain).contains("\nINFO\tmissing_superclass\tex:Egg\trdfs:subClassOf\t\n"
                + "INFO\tmissing_superclass\tex:Pan\trdfs:subClassOf\t\n"), text(plain));
    }

    /**
     * A typed literal ends in its datatype's IRI, a plain one is its text; a value that is an
     * IRI is a term like any other. A label is written as ROBOT renders it, runs of spaces as
     * one, and the file is UTF-8.
     */
    @Test
    void literalsAsJenaWritesThemAndIrisAsTerms(@TempDir File temp) throws Exception {
        String body = ""
                + "Declaration(Class(:Egg))\nAnnotationAssertion(rdfs:label :Egg \"egg\")\n"
                + "Declaration(Class(:Pan))\nAnnotationAssertion(rdfs:label :Pan \"frying  pan\")\n"
                + "Declaration(Class(:Pot))\nAnnotationAssertion(rdfs:label :Pot \"crème pot\")\n"
                + "Declaration(Class(:Lid))\nAnnotationAssertion(rdfs:label :Lid \"lid\")\n"
                + "AnnotationAssertion(owl:deprecated :Pot \"1\"^^xsd:integer)\n"
                + "AnnotationAssertion(owl:deprecated :Lid \"yes\")\n"
                + "AnnotationAssertion(obo:IAO_0100001 :Egg :Pan)\n";
        File kitchen = ontology(temp, body);
        File rules = profile(temp, "ERROR\tmisused_replaced_by",
                "ERROR\tdeprecated_boolean_datatype");
        File labelled = new File(temp, "labelled.tsv");
        File plain = new File(temp, "plain.tsv");

        OdkRobot.Result withLabels = report(NO_GLOBALS, "--input", path(kitchen), "--profile",
                path(rules), "--labels", "true", "--fail-on", "none", "--output", path(labelled));
        OdkRobot.Result without = report(NO_GLOBALS, "--input", path(kitchen), "--profile",
                path(rules), "--fail-on", "none", "--output", path(plain));

        assertTrue(withLabels.ok(), String.valueOf(withLabels.failure()));
        assertTrue(without.ok(), String.valueOf(without.failure()));
        assertEquals(HEADER + "\n"
                + "ERROR\tdeprecated_boolean_datatype\tlid\towl:deprecated\tyes\n"
                + "ERROR\tdeprecated_boolean_datatype\tcrème pot\towl:deprecated\t"
                + "1^^http://www.w3.org/2001/XMLSchema#integer\n"
                + "ERROR\tmisused_replaced_by\tegg\tIAO:0100001\tfrying pan\n", text(labelled));
        assertEquals(HEADER + "\n"
                + "ERROR\tdeprecated_boolean_datatype\thttps://example.org/e/Lid\t"
                + "owl:deprecated\tyes\n"
                + "ERROR\tdeprecated_boolean_datatype\thttps://example.org/e/Pot\t"
                + "owl:deprecated\t1^^http://www.w3.org/2001/XMLSchema#integer\n"
                + "ERROR\tmisused_replaced_by\thttps://example.org/e/Egg\tIAO:0100001\t"
                + "https://example.org/e/Pan\n", text(plain));
    }

    /** What ROBOT finds about the ontology itself names it by its IRI, in full. */
    @Test
    void theOntologyItselfIsItsIriInFull(@TempDir File temp) throws Exception {
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = report(EX, "--input", path(ontology(temp,
                "Annotation(dcterms:description \"A test kitchen\")\n")), "--profile",
                path(profile(temp, "ERROR\tmissing_ontology_title")), "--labels", "true",
                "--fail-on", "none", "--output", path(report));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n" + "ERROR\tmissing_ontology_title\t"
                + "https://example.org/e/kitchen.owl\tdc:title\t\n", text(report));
    }

    /**
     * ROBOT's deprecated_class_reference binds "blank node" as the entity of a restriction, and
     * that is what the Subject says; with --base-iri it is not about the project's terms.
     */
    @Test
    void aBlankNodeIsSaidAndBaseIrisKeepOnlyTheirOwn(@TempDir File temp) throws Exception {
        String body = ""
                + "Declaration(Class(:OldEgg))\nAnnotationAssertion(rdfs:label :OldEgg \"old egg\")\n"
                + "AnnotationAssertion(owl:deprecated :OldEgg \"true\"^^xsd:boolean)\n"
                + "Declaration(ObjectProperty(:partOf))\n"
                + "Declaration(Class(:Egg))\nAnnotationAssertion(rdfs:label :Egg \"egg\")\n"
                + "SubClassOf(:Egg ObjectSomeValuesFrom(:partOf :OldEgg))\n"
                + "AnnotationAssertion(rdfs:comment :Pan \"no label\")\n";
        File kitchen = ontology(temp, body);
        File rules = profile(temp, "ERROR\tdeprecated_class_reference", "ERROR\tmissing_label");
        File all = new File(temp, "all.tsv");
        File own = new File(temp, "own.tsv");

        OdkRobot.Result everything = report(EX, "--input", path(kitchen), "--profile",
                path(rules), "--labels", "true", "--fail-on", "none", "--output", path(all));
        OdkRobot.Result ours = report(EX, "--input", path(kitchen), "--profile", path(rules),
                "--base-iri", "ex", "--labels", "true", "--fail-on", "none", "--print", "5",
                "--output", path(own));

        assertTrue(everything.ok(), String.valueOf(everything.failure()));
        assertTrue(ours.ok(), String.valueOf(ours.failure()));
        assertEquals(HEADER + "\n"
                + "ERROR\tdeprecated_class_reference\tblank node\towl:someValuesFrom\told egg\n"
                + "ERROR\tmissing_label\tex:Pan\trdfs:label\t\n", text(all));
        assertEquals(HEADER + "\n" + "ERROR\tmissing_label\tex:Pan\trdfs:label\t\n", text(own));
        assertEquals("Violations: 1", ours.output().get(0), ours.output().toString());
    }

    /** A term of RDF Schema's or OWL's own is not reported, and not counted. */
    @Test
    void termsOfRdfSchemaAndOwlAreNotReported(@TempDir File temp) throws Exception {
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = report(NO_GLOBALS, "--input", path(ontology(temp,
                "AnnotationAssertion(rdfs:comment rdfs:seeAlso \"see\")\n"
                + "AnnotationAssertion(rdfs:comment owl:Thing \"top\")\n")), "--profile",
                path(profile(temp, "ERROR\tmissing_label")), "--fail-on", "ERROR", "--print", "5",
                "--output", path(report));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n", text(report));
        assertEquals(Arrays.asList("No violations found.", "", "First 0 violations:"),
                result.output());
    }

    // ------------------------------------------------------------------ the file

    /**
     * ROBOT's table writer quotes a cell only when it holds a quotation mark, the separator or a
     * line break, and doubles the marks; Jena has already written each mark in a literal as \".
     * The same report as CSV: other cells are quoted there.
     */
    @Test
    void cellsAreQuotedAsRobotsTableWriterQuotesThem(@TempDir File temp) throws Exception {
        String body = ""
                + "Declaration(AnnotationProperty(:note))\nAnnotationAssertion(rdfs:label :note \"note\")\n"
                + "Declaration(Class(:Egg))\nAnnotationAssertion(rdfs:label :Egg \"egg\")\n"
                + "AnnotationAssertion(:note :Egg \"a \\\"soft\\\" egg, boiled \")\n"
                + "Declaration(Class(:Pan))\nAnnotationAssertion(rdfs:label :Pan \"pan\")\n"
                + "AnnotationAssertion(:note :Pan \"hot\tpan \")\n";
        File kitchen = ontology(temp, body);
        File rules = profile(temp, "WARN\tannotation_whitespace");
        File tsv = new File(temp, "report.tsv");
        File csv = new File(temp, "report.csv");

        OdkRobot.Result asTsv = report(NO_GLOBALS, "--input", path(kitchen), "--profile",
                path(rules), "--labels", "true", "--fail-on", "none", "--print", "5", "--output",
                path(tsv));
        OdkRobot.Result asCsv = report(NO_GLOBALS, "--input", path(kitchen), "--profile",
                path(rules), "--labels", "true", "--fail-on", "none", "--print", "5", "--output",
                path(csv));

        assertTrue(asTsv.ok(), String.valueOf(asTsv.failure()));
        assertTrue(asCsv.ok(), String.valueOf(asCsv.failure()));
        assertEquals(HEADER + "\n"
                + "WARN\tannotation_whitespace\tegg\tnote\t\"a \\\"\"soft\\\"\" egg, boiled \"\n"
                + "WARN\tannotation_whitespace\tpan\tnote\t\"hot\tpan \"\n", text(tsv));
        assertEquals("Level,Rule Name,Subject,Property,Value\n"
                + "WARN,annotation_whitespace,egg,note,\"a \\\"\"soft\\\"\" egg, boiled \"\n"
                + "WARN,annotation_whitespace,pan,note,hot\tpan \n", text(csv));
        // What is printed is joined, not quoted.
        assertTrue(asTsv.output().contains("WARN\tannotation_whitespace\tegg\tnote\t"
                + "a \\\"soft\\\" egg, boiled "), asTsv.output().toString());
        assertTrue(asCsv.output().contains("WARN,annotation_whitespace,pan,note,hot\tpan "),
                asCsv.output().toString());
    }

    /** --format wins over the file's name, and a format ROBOT has no writer of its own for is TSV. */
    @Test
    void theFormatIsTheOptionElseTheFileNameAndTsvForTheRest(@TempDir File temp)
            throws Exception {
        File kitchen = ontology(temp, KITCHEN);
        File rules = profile(temp, "ERROR\tmissing_label");
        File named = new File(temp, "report.txt");
        File told = new File(temp, "report.tsv");

        OdkRobot.Result txt = report(NO_GLOBALS, "--input", path(kitchen), "--profile",
                path(rules), "--fail-on", "none", "--output", path(named));
        OdkRobot.Result csv = report(NO_GLOBALS, "--input", path(kitchen), "--profile",
                path(rules), "--format", "CSV", "--fail-on", "none", "--output", path(told));

        assertTrue(txt.ok(), String.valueOf(txt.failure()));
        assertTrue(csv.ok(), String.valueOf(csv.failure()));
        assertEquals(HEADER + "\nERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t\n",
                text(named));
        assertEquals("Level,Rule Name,Subject,Property,Value\n"
                + "ERROR,missing_label,https://example.org/e/Pan,rdfs:label,\n", text(told));
    }

    /** The formats ROBOT writes with writers of their own are refused by name, before running. */
    @ParameterizedTest
    @ValueSource(strings = {"yaml", "JSON", "html", "html5", "xlsx"})
    void aFormatNotPortedIsRefusedByName(String format, @TempDir File temp) throws Exception {
        File kitchen = ontology(temp, KITCHEN);
        List<String> line = Arrays.asList("report", "--input", path(kitchen), "--format", format,
                "--output", path(new File(temp, "report.out")));

        String why = OdkRobot.whyNot(line);

        assertNotNull(why);
        assertTrue(why.contains(format), why);
        assertThrows(IllegalArgumentException.class, () -> OdkRobot.run(line));
        assertFalse(new File(temp, "report.out").exists());
    }

    // ------------------------------------------------------------------ what is printed

    /** ROBOT's own block: the counts, an empty line, and the first rows, as the recording has it. */
    @Test
    void whatRobotPrintsForAReport(@TempDir File temp) throws Exception {
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = report(NO_GLOBALS, "--input", path(ontology(temp, KITCHEN)),
                "--profile", path(profile(temp, KITCHEN_RULES)), "-l", "true", "--fail-on",
                "ERROR", "--print", "2", "-o", path(report));

        assertFalse(result.ok(), "missing_label is an ERROR");
        assertEquals(Arrays.asList("Violations: 4", "-----------------", "ERROR:      1",
                "WARN:       0", "INFO:       3", "", "First 2 violations:",
                "ERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t",
                "INFO\tlowercase_definition\tboiled egg\tdefinition\t"
                + "an egg kept in boiling water.@en"), result.output());
    }

    /** More asked for than there are: all of them, and as many as there are said. */
    @Test
    void printingMoreThanThereAreNamesAsManyAsThereAre(@TempDir File temp) throws Exception {
        File report = new File(temp, "report.csv");

        OdkRobot.Result result = report(NO_GLOBALS, "--input", path(ontology(temp, KITCHEN)),
                "--profile", path(profile(temp, KITCHEN_RULES)), "--fail-on", "none",
                "--print", " 10 ", "-o", path(report));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(Arrays.asList("Violations: 4", "-----------------", "ERROR:      1",
                "WARN:       0", "INFO:       3", "", "First 4 violations:",
                "ERROR,missing_label,https://example.org/e/Pan,rdfs:label,",
                "INFO,lowercase_definition,https://example.org/e/Egg,IAO:0000115,"
                + "an egg kept in boiling water.@en",
                "INFO,missing_superclass,https://example.org/e/Egg,rdfs:subClassOf,",
                "INFO,missing_superclass,https://example.org/e/Pan,rdfs:subClassOf,"),
                result.output());
    }

    /** --print that is not a number: ROBOT has said its counts, and fails before any file. */
    @Test
    void printThatIsNotANumberFailsAsRobotFails(@TempDir File temp) throws Exception {
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = report(NO_GLOBALS, "--input", path(ontology(temp, KITCHEN)),
                "--profile", path(profile(temp, KITCHEN_RULES)), "--fail-on", "none",
                "--print", "five", "-o", path(report));

        assertFalse(result.ok());
        assertEquals("PRINT NUMBER ERROR --print argument 'five' must be an integer.",
                result.failure());
        assertEquals("Violations: 4", result.output().get(0));
        assertFalse(report.exists(), "ROBOT fails before it writes the file");
    }

    /**
     * ODK's own line on the ontology ODK's image merged, against what ROBOT 1.9.10 wrote there:
     * the file byte for byte, and the printed block line for line - all but the two lines
     * "WARN: '...' query is missing ?value variable", which ROBOT 1.9.10 says and 1.9.8 does not
     * have.
     */
    @Test
    void theRecordedReportByteForByte(@TempDir File top) throws Exception {
        File ontology = EggFixture.write(top, true);
        File reports = new File(ontology, "reports");
        assertTrue(reports.mkdirs());
        String report = "reports/eggboil-edit.owl-obo-report.tsv";

        OdkRobot.Result result = OdkRobot.run(Arrays.asList("--catalog",
                path(new File(ontology, "catalog-v001.xml")), "report", "-i",
                path(new File(ontology, "tmp/merged-eggboil-edit.ofn")), "-l", "true",
                "--profile", path(new File(ontology, "profile.txt")), "--fail-on", "ERROR",
                "--print", "5", "-o", path(new File(ontology, report))));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(new String(de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens.equivalence(
                "test_fast/files/src/ontology/" + report), StandardCharsets.UTF_8),
                text(new File(ontology, report)));
        List<String> printed = new ArrayList<String>();
        boolean inside = false;
        for (String line : new String(de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens.equivalence(
                "test_fast/transcript.txt"), StandardCharsets.UTF_8).split("\n")) {
            if (inside && line.startsWith("robot ")) {
                break;
            }
            if (inside && !line.startsWith("WARN: '")) {
                printed.add(line);
            }
            inside |= line.startsWith("robot ") && line.contains(" report ");
        }
        assertEquals(12, printed.size(), printed.toString());
        assertEquals(printed, result.output());
    }

    // ------------------------------------------------------------------ rows ROBOT reads

    private static QualityFinding row(String level, String rule, String entity, String property,
            String value) {
        return new QualityFinding(QualityFinding.Severity.of(level), rule,
                entity == null ? "" : entity, "", property, value,
                new QualityFinding.Bound(entity, property, value));
    }

    private static RobotReport.Cells cells(boolean labels) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(
                org.semanticweb.owlapi.model.IRI.create("https://example.org/e/kitchen.owl"));
        return new RobotReport.Cells(ontology, new IOHelper(), labels);
    }

    /** A row without ?property is a violation ROBOT counts and has no line of the table for. */
    @Test
    void aRowWithoutAPropertyIsCountedButNotWritten() throws Exception {
        RobotReport.Table table = RobotReport.table(Arrays.asList(
                row("WARN", "custom", "https://example.org/e/Egg", null, null),
                row("WARN", "custom", "https://example.org/e/Pan",
                        "http://www.w3.org/2000/01/rdf-schema#label", "pan@en")),
                cells(false), Collections.<String>emptySet());

        assertEquals(2, table.count("WARN"));
        assertEquals(2, table.total());
        assertEquals(2, table.rows().size(), "the header and one row");
        assertEquals(Arrays.asList("WARN", "custom", "https://example.org/e/Pan", "rdfs:label",
                "pan@en"), Arrays.asList(table.rows().get(1)));
    }

    /** A row without ?entity fails the report, in ROBOT's words. */
    @Test
    void aRowWithoutAnEntityFailsTheReport() throws Exception {
        Exception failed = assertThrows(Exception.class, () -> RobotReport.table(
                Collections.singletonList(row("ERROR", "custom", null,
                        "http://www.w3.org/2000/01/rdf-schema#label", null)),
                cells(false), Collections.<String>emptySet()));

        assertEquals("report#MISSING ENTITY BINDING query 'custom' must include an '?entity'",
                failed.getMessage());
    }

    /**
     * The levels in ROBOT's order, the rules by String.compareTo inside a level (capitals
     * first), a rule's rows in the order its query gave them.
     */
    @Test
    void rowsAreByLevelThenRuleNameThenAsTheQueryGaveThem() throws Exception {
        String label = "http://www.w3.org/2000/01/rdf-schema#label";
        RobotReport.Table table = RobotReport.table(Arrays.asList(
                row("INFO", "b_rule", "https://example.org/e/Z", label, null),
                row("INFO", "a_rule", "https://example.org/e/Y", label, null),
                row("ERROR", "c_rule", "https://example.org/e/X", label, null),
                row("INFO", "b_rule", "https://example.org/e/A", label, null),
                row("INFO", "B_rule", "https://example.org/e/W", label, null),
                row("WARN", "z_rule", "https://example.org/e/V", label, null)),
                cells(false), Collections.<String>emptySet());

        List<String> order = new ArrayList<String>();
        for (String[] cells : table.rows()) {
            order.add(cells[0] + " " + cells[1] + " " + cells[2]);
        }
        assertEquals(Arrays.asList("Level Rule Name Subject",
                "ERROR c_rule https://example.org/e/X", "WARN z_rule https://example.org/e/V",
                "INFO B_rule https://example.org/e/W", "INFO a_rule https://example.org/e/Y",
                "INFO b_rule https://example.org/e/Z", "INFO b_rule https://example.org/e/A"),
                order);
    }
}
