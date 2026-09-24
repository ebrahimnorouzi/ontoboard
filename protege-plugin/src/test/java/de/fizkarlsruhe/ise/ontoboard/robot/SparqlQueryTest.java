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
}
