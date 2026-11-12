package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
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
 * checks its configuration lists - what ODK's test runs - and a query in src/sparql that is not
 * listed is named, not run. In a project of the older layout it is every .rq file, as before.
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
    void inAnOdkProjectTheChecksAreTheListedOnes(@TempDir File temp) throws Exception {
        File root = odkProject(temp);
        File edit = new File(root, "src/ontology/eggboil-edit.owl");

        SparqlAction.ProjectChecks checks = SparqlAction.checksOf(edit);

        assertEquals(Arrays.asList("owldef-self-reference-violation.sparql",
                "iri-range-violation.sparql", "label-with-iri-violation.sparql",
                "multiple-replaced_by-violation.sparql", NAME + "-violation.sparql"),
                names(checks.files()), "in the configuration's order");
        assertTrue(checks.notRun().contains("mine.rq")
                && checks.notRun().contains("other-violation.sparql"), checks.notRun());
        assertTrue(checks.notRun().contains("not listed under robot_report: "
                + "custom_sparql_checks in eggboil-odk.yaml, so ODK's test does not run them"),
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
        assertEquals(names(checks.files()), ran, "the listed checks, and nothing else, ran");
        assertTrue(result.getLog().contains(checks.notRun()), result.getLog().toString());
        assertTrue(result.getLog().contains(checks.missing()), result.getLog().toString());
        assertTrue(result.getSummary().startsWith("5 checks: "), result.getSummary());
    }

    /** An empty list is ODK's "no checks", and the action says so instead of running the folder. */
    @Test
    void anEmptyListRunsNothing(@TempDir File temp) throws Exception {
        File root = odkProject(temp);
        write(new File(root, "src/ontology/eggboil-odk.yaml"), "id: eggboil\nrobot_report:\n"
                + "  custom_sparql_checks: []\n");

        SparqlAction.ProjectChecks checks =
                SparqlAction.checksOf(new File(root, "src/ontology/eggboil-edit.owl"));

        assertTrue(checks.files().isEmpty());
        assertTrue(checks.noneFound().contains("eggboil-odk.yaml"), checks.noneFound());
        assertTrue(checks.notRun().contains(NAME + "-violation.sparql"), checks.notRun());
        assertFalse(checks.noneFound().contains(".rq"), checks.noneFound());
    }

    /** The older layout: every .rq file in src/sparql, with the words it had. */
    @Test
    void inAProjectOfTheOlderLayoutEveryRqFileIsACheck(@TempDir File temp) throws Exception {
        OdkProjectConfig project = new OdkProjectConfig("demo", "Demo", "",
                "http://example.org/demo.owl",
                "https://creativecommons.org/publicdomain/zero/1.0/", temp);
        OdkScaffold.create(project);
        File root = project.getProjectRoot();
        File edit = new File(root, "src/ontology/demo-edit.owl");
        write(new File(root, "src/sparql/stray-violation.sparql"), "SELECT * WHERE { }\n");

        SparqlAction.ProjectChecks checks = SparqlAction.checksOf(edit);

        assertEquals(SparqlQuery.checksIn(root), checks.files());
        assertNull(checks.notRun());
        assertNull(checks.missing());
        assertEquals("Checks live in src/sparql and are what the generated sparql_test target "
                + "runs.", checks.where());
        assertEquals("1 check found: check_labels.rq. Each is a SELECT whose rows are the "
                + "violations - it passes by returning nothing, which is the same convention "
                + "robot verify and the build's sparql_test target use.",
                SparqlAction.help(checks));

        assertTrue(new File(root, "src/sparql/check_labels.rq").delete());
        checks = SparqlAction.checksOf(edit);
        assertEquals("No .rq files under src/sparql in " + root.getAbsolutePath() + ". The ODK "
                + "wizard writes check_labels.rq there.", checks.noneFound());
        assertEquals("This project has no src/sparql directory, or nothing with a .rq extension "
                + "in it. The ODK wizard writes check_labels.rq there, and the generated "
                + "sparql_test target runs everything it finds.", SparqlAction.help(checks));
        assertEquals("No .rq files under src/sparql. The ODK wizard writes check_labels.rq "
                + "there.", SparqlAction.checksOf(null).noneFound());
    }
}
