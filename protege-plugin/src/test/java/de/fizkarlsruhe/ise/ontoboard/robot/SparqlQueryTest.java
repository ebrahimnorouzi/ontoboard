package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;

/** SPARQL over the open ontology, and over an ODK project's own committed checks. */
class SparqlQueryTest {

    private static final String CC0 = "https://creativecommons.org/publicdomain/zero/1.0/";

    /** Every value in one column, across all rows. */
    private static List<String> column(SparqlQuery.Answer answer, String name) {
        int index = answer.getColumns().indexOf(name);
        List<String> values = new ArrayList<String>();
        for (List<String> row : answer.getRows()) {
            values.add(index >= 0 && index < row.size() ? row.get(index) : "");
        }
        return values;
    }

    @Test
    void selectsOverTheOpenOntology() throws Exception {
        SparqlQuery.Answer answer = SparqlQuery.run(PizzaOntology.v2(),
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                        + "SELECT ?c WHERE { ?c a owl:Class . FILTER(!isBlank(?c)) }");

        assertEquals(Arrays.asList("c"), answer.getColumns());
        assertTrue(answer.size() > 0, "the pizza has classes");
        assertTrue(column(answer, "c").contains("http://example.org/pizza#VegetarianPizza"),
                column(answer, "c").toString());
    }

    /**
     * The example query must parse and run, because it is what the panel opens with.
     *
     * <p>An example that does not work is worse than an empty box: the first thing a user does is
     * press Run, and the first thing they would learn is that the tool is broken.
     */
    @Test
    void theExampleQueryRuns() throws Exception {
        SparqlQuery.Answer answer = SparqlQuery.run(PizzaOntology.v1(), SparqlQuery.exampleQuery());

        assertEquals(Arrays.asList("class"), answer.getColumns());
    }

    /** A query with a syntax error must say where, not just that something went wrong. */
    @Test
    void abrokenQueryReportsTheParseError() throws Exception {
        RobotException failed = assertThrows(RobotException.class,
                () -> SparqlQuery.run(PizzaOntology.v1(), "SELECT ?x WHERE { ?x a"));

        assertTrue(failed.getMessage().toLowerCase().contains("line")
                        || failed.getMessage().toLowerCase().contains("encountered"),
                "Jena names the line and column; that is the useful part: " + failed.getMessage());
    }

    @Test
    void anEmptyQueryIsRefusedRatherThanRun() throws Exception {
        OWLOntology pizza = PizzaOntology.v1();
        assertThrows(RobotException.class, () -> SparqlQuery.run(pizza, ""));
        assertThrows(RobotException.class, () -> SparqlQuery.run(pizza, "   "));
        assertThrows(RobotException.class, () -> SparqlQuery.run(pizza, null));
        assertThrows(IllegalArgumentException.class,
                () -> SparqlQuery.run(null, SparqlQuery.exampleQuery()));
    }

    /**
     * The scaffold's own check runs, and this is the gap that closes.
     *
     * <p>OntoBoard writes {@code src/sparql/check_labels.rq} and a {@code sparql_test} target that
     * runs it, and until now nothing in the plugin could run the file it had just written.
     */
    @Test
    void theScaffoldsOwnCheckIsFoundAndRun(@TempDir File dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("demo", "Demo", "",
                "http://example.org/demo.owl", CC0, dir);
        OdkScaffold.create(config);

        List<File> checks = SparqlQuery.checksIn(config.getProjectRoot());
        assertFalse(checks.isEmpty(), "the scaffold writes src/sparql/check_labels.rq");
        assertEquals("check_labels.rq", checks.get(0).getName());

        List<SparqlQuery.Check> results = SparqlQuery.verify(PizzaOntology.v2(), checks);
        assertEquals(1, results.size());
        assertEquals(null, results.get(0).getFailure(),
                "the scaffold's own check must at least parse and run");
    }

    /**
     * A check "passes" when it returns nothing, and that has to be right way round.
     *
     * <p>ROBOT's verify convention is that the rows a query returns <em>are</em> the violations,
     * which is why {@code robot verify} exits non-zero when a query finds something.
     */
    @Test
    void aCheckPassesWhenItReturnsNothingAndFailsWhenItReturnsRows(@TempDir File dir)
            throws Exception {
        File findsNothing = new File(dir, "finds-nothing.rq");
        Files.write(findsNothing.toPath(),
                ("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                        + "SELECT ?c WHERE { ?c a owl:NoSuchThingAtAll }")
                        .getBytes(StandardCharsets.UTF_8));
        File findsEverything = new File(dir, "finds-everything.rq");
        Files.write(findsEverything.toPath(),
                ("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                        + "SELECT ?c WHERE { ?c a owl:Class . FILTER(!isBlank(?c)) }")
                        .getBytes(StandardCharsets.UTF_8));

        List<SparqlQuery.Check> checks = SparqlQuery.verify(PizzaOntology.v2(),
                Arrays.asList(findsEverything, findsNothing));

        // Sorted by nothing here - verify keeps the order it was given.
        assertEquals("finds-everything.rq", checks.get(0).getName());
        assertFalse(checks.get(0).isPassed(), "a query that returns rows has found violations");
        assertTrue(checks.get(0).getViolations() > 0);

        assertTrue(checks.get(1).isPassed(), "a query that returns nothing has found nothing");
        assertEquals(0, checks.get(1).getViolations());
    }

    /** One unparseable check must not cost the others their result. */
    @Test
    void onebrokenCheckDoesNotStopTheRest(@TempDir File dir) throws Exception {
        File broken = new File(dir, "a-broken.rq");
        Files.write(broken.toPath(), "SELECT ?x WHERE { ?x a".getBytes(StandardCharsets.UTF_8));
        File fine = new File(dir, "b-fine.rq");
        Files.write(fine.toPath(),
                ("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                        + "SELECT ?c WHERE { ?c a owl:NoSuchThingAtAll }")
                        .getBytes(StandardCharsets.UTF_8));

        List<SparqlQuery.Check> checks = SparqlQuery.verify(PizzaOntology.v1(),
                Arrays.asList(broken, fine));

        assertEquals(2, checks.size());
        assertFalse(checks.get(0).getFailure() == null, "the broken one must report why");
        assertFalse(checks.get(0).isPassed(),
                "a check that could not run has not passed, whatever else it did");
        assertTrue(checks.get(1).isPassed(), "the other one still ran");
    }

    /** Imported axioms are queryable, because that is what the release will contain. */
    @Test
    void theImportsClosureIsQueryable() throws Exception {
        OWLOntology withImport = PizzaOntology.v4("https://orcid.org/0000-0002-1825-0097",
                "2026-09-23");

        SparqlQuery.Answer answer = SparqlQuery.run(withImport,
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                        + "SELECT ?c WHERE { ?c a owl:Class . FILTER(!isBlank(?c)) }");

        assertTrue(answer.size() > 0);
    }

    @Test
    void noChecksDirectoryIsEmptyRatherThanAFailure(@TempDir File dir) {
        assertTrue(SparqlQuery.checksIn(dir).isEmpty());
        assertTrue(SparqlQuery.checksIn(null).isEmpty());
        assertTrue(SparqlQuery.verify(null, new ArrayList<File>()).isEmpty());
    }

    /** Newline and tab as characters, so no escape has to survive a shell on the way here. */
    private static final String NL = String.valueOf((char) 10);
    private static final String TAB = String.valueOf((char) 9);

    /**
     * Results write as TSV, which is what ODK's custom_reports target produces.
     *
     * <p>That target is {@code robot query -f tsv -s <query>.sparql <report>.tsv} for each export a
     * project configures, and the file is the point - something a spreadsheet opens, or a script
     * diffs between releases.
     */
    @Test
    void resultsWriteAsTsvWithAHeaderRow(@TempDir File dir) throws Exception {
        SparqlQuery.Answer answer = SparqlQuery.run(PizzaOntology.v2(),
                "PREFIX owl: <http://www.w3.org/2002/07/owl#>" + NL
                        + "SELECT ?c WHERE { ?c a owl:Class . FILTER(!isBlank(?c)) }");
        File tsv = new File(dir, "classes.tsv");

        SparqlQuery.writeTsv(answer, tsv);

        String text = new String(Files.readAllBytes(tsv.toPath()), StandardCharsets.UTF_8);
        String[] lines = text.split(NL);
        assertEquals("c", lines[0], "the first line must be the header");
        assertEquals(answer.size() + 1, lines.length, "one line per row, plus the header");
        assertTrue(text.contains("http://example.org/pizza#"), text.substring(0, 120));
    }

    /**
     * A tab or newline inside a value becomes a space.
     *
     * <p>A TSV whose cells contain tabs is not a TSV: a definition with a newline in it would
     * silently shift every following column, and the file would look correct until somebody
     * counted.
     */
    @Test
    void tabsAndNewlinesInValuesDoNotBreakTheColumns(@TempDir File dir) throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        OWLOntology awkward = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/odd"));
        org.semanticweb.owlapi.model.OWLDataFactory factory = manager.getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLClass thing = factory.getOWLClass(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/odd#Thing"));
        manager.addAxiom(awkward, factory.getOWLDeclarationAxiom(thing));
        manager.addAxiom(awkward, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                thing.getIRI(), factory.getOWLLiteral("has" + TAB + "a tab and" + NL + "a line")));

        SparqlQuery.Answer answer = SparqlQuery.run(awkward,
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>" + NL
                        + "SELECT ?c ?l WHERE { ?c rdfs:label ?l }");
        File tsv = new File(dir, "odd.tsv");
        SparqlQuery.writeTsv(answer, tsv);

        String text = new String(Files.readAllBytes(tsv.toPath()), StandardCharsets.UTF_8);
        for (String line : text.split(NL)) {
            assertEquals(2, line.split(TAB, -1).length,
                    "every line must have exactly two columns: " + line);
        }
    }

    @Test
    void writingNeedsBothAnAnswerAndAFile(@TempDir File dir) throws Exception {
        SparqlQuery.Answer answer =
                SparqlQuery.run(PizzaOntology.v1(), SparqlQuery.exampleQuery());

        assertThrows(IllegalArgumentException.class, () -> SparqlQuery.writeTsv(answer, null));
        assertThrows(IllegalArgumentException.class,
                () -> SparqlQuery.writeTsv(null, new File(dir, "x.tsv")));
    }
}
