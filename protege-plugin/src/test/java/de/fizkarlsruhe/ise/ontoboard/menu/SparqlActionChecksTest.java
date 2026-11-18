package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks;
import de.fizkarlsruhe.ise.ontoboard.odk.RecordedProject;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Check and test &gt; SPARQL..., "Run this project's checks": in an ODK project those are the
 * checks its configuration lists, asked of the ontology by itself as ODK's test asks them,
 * and the project's questions - the .rq files - asked with its imports. A check file that
 * is not listed is named, not run. In a project of the older layout it is every .rq file,
 * as before.
 */
class SparqlActionChecksTest {

    private static final String NAME = "every_dataset_has_part";

    private static File odkProject(File temp) throws IOException {
        File root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        write(new File(root, "src/ontology/eggboil-odk.yaml"),
                OdkChecks.add(Goldens.config("eggboil"), NAME));
        write(OdkChecks.fileFor(root, NAME), "SELECT ?term WHERE { ?term a <urn:x:Nothing> }\n");
        write(new File(root, "src/sparql/mine.rq"), "SELECT * WHERE { ?s ?p ?o }\n");
        write(new File(root, "src/sparql/other-violation.sparql"), "SELECT * WHERE { ?s ?p ?o }\n");
        return root;
    }

    private static void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(List<File> files) {
        List<String> names = new ArrayList<String>();
        for (File file : files) {
            names.add(file.getName());
        }
        return names;
    }

    @Test
    void inAnOdkProjectTheChecksAreTheListedOnesAndTheQuestions(@TempDir File temp)
            throws Exception {
        File root = odkProject(temp);
        File edit = new File(root, "src/ontology/eggboil-edit.owl");

        SparqlAction.ProjectChecks checks = SparqlAction.checksOf(edit);

        assertEquals(Arrays.asList("owldef-self-reference-violation.sparql",
                "iri-range-violation.sparql", "label-with-iri-violation.sparql",
                "multiple-replaced_by-violation.sparql", NAME + "-violation.sparql",
                "mine.rq"), names(checks.files()),
                "ODK's checks in the configuration's order, then the questions");
        assertEquals(Arrays.asList("mine.rq"), names(checks.questions()));
        assertTrue(checks.notRun().contains("other-violation.sparql"), checks.notRun());
        assertTrue(checks.notRun().contains("not listed under robot_report: "
                + "custom_sparql_checks in eggboil-odk.yaml, so ODK's test does not run it"),
                checks.notRun());
        assertTrue(checks.notRun().contains("mine.rq") && checks.notRun().contains(
                "the rule that asks a project's questions is not in eggboil.Makefile"),
                "a question nothing asks yet: " + checks.notRun());
        assertTrue(checks.notRun().contains(
                "OntoBoard > Check and test > Write a test from a question..."),
                checks.notRun());
        assertTrue(checks.missing().contains("check_labels"), "listed, with no file: "
                + checks.missing());
        assertFalse(checks.missing().contains(NAME), checks.missing());
        String help = SparqlAction.help(checks);
        assertFalse(help.contains(".rq extension"), help);
        assertTrue(help.contains("eggboil-odk.yaml") && help.contains(NAME), help);
        assertTrue(help.contains(checks.notRun()), help);

        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/eggboil/eggboil.owl"));
        OperationResult result = SparqlAction.runChecks(checks, ontology, null);

        List<String> ran = new ArrayList<String>();
        for (List<String> row : result.getRows()) {
            ran.add(row.get(0));
        }
        assertEquals(names(checks.files()), ran, "the listed checks and the question ran");
        assertTrue(result.getLog().contains(checks.notRun()), result.getLog().toString());
        assertTrue(result.getLog().contains(checks.missing()), result.getLog().toString());
        assertTrue(result.getSummary().startsWith("6 checks: "), result.getSummary());

        // With the rule in the project's own makefile the question is asked by ODK's test.
        de.fizkarlsruhe.ise.ontoboard.odk.OdkQuestions.ensure(root);
        SparqlAction.ProjectChecks kept = SparqlAction.checksOf(edit);
        assertFalse(kept.notRun().contains("mine.rq"), kept.notRun());
        assertTrue(kept.where().contains("with its imports")
                && kept.where().contains("eggboil.Makefile"), kept.where());
    }

    /**
     * One query, two answers: as one of ODK's checks it is asked of the ontology by itself
     * and finds nothing; as a question it is asked with the imports and names what the
     * import says. The dialog gives each the answer ODK's test gives it.
     */
    @Test
    void aCheckIsAskedOfTheOntologyAloneAndAQuestionWithItsImports(@TempDir File temp)
            throws Exception {
        File root = odkProject(temp);
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        String labelled = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "SELECT ?x WHERE { ?x rdfs:label \"a\" }\n";
        write(OdkChecks.fileFor(root, NAME), labelled);
        write(new File(root, "src/sparql/mine.rq"), labelled);
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology imported = manager.createOntology(
                IRI.create("https://example.org/imported.owl"));
        manager.addAxiom(imported, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), IRI.create("https://example.org/A"),
                factory.getOWLLiteral("a")));
        OWLOntology ontology = manager.createOntology(
                IRI.create("https://example.org/eggboil/eggboil.owl"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(
                        IRI.create("https://example.org/imported.owl"))));

        OperationResult result = SparqlAction.runChecks(SparqlAction.checksOf(edit), ontology,
                null);

        java.util.Map<String, String> answers = new java.util.LinkedHashMap<String, String>();
        java.util.Map<String, String> details = new java.util.LinkedHashMap<String, String>();
        for (List<String> row : result.getRows()) {
            answers.put(row.get(0), row.get(1));
            details.put(row.get(0), row.get(2));
        }
        assertEquals("pass", answers.get(NAME + "-violation.sparql"),
                "ODK's check does not see the import");
        assertEquals("FAIL", answers.get("mine.rq"), "the question does");
        // One is said as one ("1 violations" was 1.153.0's: its minor 4).
        assertEquals("1 violation", details.get("mine.rq"));
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("mine.rq found 1 violation, so this project's `make test` "
                + "would fail"), log);
    }

    /** An empty list is ODK's "no checks", and the action says so instead of running the folder. */
    @Test
    void anEmptyListRunsNothing(@TempDir File temp) throws Exception {
        File root = odkProject(temp);
        write(new File(root, "src/ontology/eggboil-odk.yaml"), "id: eggboil\nrobot_report:\n"
                + "  custom_sparql_checks: []\n");

        SparqlAction.ProjectChecks checks =
                SparqlAction.checksOf(new File(root, "src/ontology/eggboil-edit.owl"));

        assertTrue(checks.noneFound().contains("eggboil-odk.yaml"), checks.noneFound());
        assertEquals(Arrays.asList("mine.rq"), names(checks.files()),
                "none of ODK's checks; the question is the project's own");
        assertTrue(checks.notRun().contains(NAME + "-violation.sparql"), checks.notRun());
    }

    /**
     * The older layout - the project OntoBoard 1.135.2 made, as recorded: every .rq file in
     * src/sparql is a check. What is said no longer speaks of a wizard that writes
     * check_labels.rq, or of a Makefile OntoBoard generates: neither is there any more.
     */
    @Test
    void inAProjectOfTheOlderLayoutEveryRqFileIsACheck(@TempDir File temp) throws Exception {
        File root = new File(temp, "eggboil");
        File edit = RecordedProject.of1352(root);
        write(new File(root, "src/sparql/stray-violation.sparql"), "SELECT * WHERE { }\n");

        SparqlAction.ProjectChecks checks = SparqlAction.checksOf(edit);

        assertEquals(SparqlQuery.checksIn(root), checks.files());
        assertNull(checks.notRun());
        assertNull(checks.missing());
        assertEquals("Checks live in src/sparql: every .rq file there is one.",
                checks.where());
        assertEquals("2 checks found: check_labels.rq, every_dataset_has_part.rq. Each is a "
                + "SELECT whose rows are the violations - it passes by returning nothing, "
                + "which is the same convention robot verify and the build's sparql_test "
                + "target use.", SparqlAction.help(checks));

        assertTrue(new File(root, "src/sparql/check_labels.rq").delete());
        assertTrue(new File(root, "src/sparql/every_dataset_has_part.rq").delete());
        checks = SparqlAction.checksOf(edit);
        String writesOne = "OntoBoard > Check and test > Write a test from a question... "
                + "writes one.";
        assertEquals("No .rq files under src/sparql in " + root.getAbsolutePath() + ". "
                + writesOne, checks.noneFound());
        assertEquals("This project has no src/sparql directory, or nothing with a .rq extension "
                + "in it. " + writesOne, SparqlAction.help(checks));
        assertEquals("No .rq files under src/sparql. " + writesOne,
                SparqlAction.checksOf(null).noneFound());
    }
}
