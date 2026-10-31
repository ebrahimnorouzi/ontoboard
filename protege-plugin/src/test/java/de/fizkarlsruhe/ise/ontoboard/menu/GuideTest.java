package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

class GuideTest {

    /**
     * The path runs from a project to sharing it, and where somebody starts decides the first
     * step's button.
     *
     * <p>Asked for: "if the user does not know ODK, ROBOT and eXtreme Design they are lost -
     * where to start, what the steps to a release are."
     */
    @Test
    void thePathRunsFromAProjectToSharingIt() {
        List<Guide.Step> steps = Guide.steps(Guide.Start.NEW);

        StringBuilder titles = new StringBuilder();
        for (Guide.Step step : steps) {
            titles.append(titles.length() == 0 ? "" : " > ").append(step.getId());
        }
        assertEquals("project > requirements > pattern > terms > model > tests > build > release"
                + " > history > share", titles.toString());
        assertTrue(steps.get(0).getAction().endsWith(".NewProjectAction"));
        assertTrue(Guide.steps(Guide.Start.GITHUB).get(0).getAction()
                .endsWith(".OpenFromGitHubAction"));
        assertTrue(Guide.steps(Guide.Start.PROJECT).get(0).getAction()
                .endsWith(".OpenProjectAction"));
    }

    /** Every menu item a step runs is one that exists, so no button does nothing. */
    @Test
    void everyStepsActionExists() throws Exception {
        for (Guide.Start start : Guide.Start.values()) {
            for (Guide.Step step : Guide.steps(start)) {
                if (step.getAction().contains(".")) {
                    Class.forName(step.getAction());
                }
            }
        }
    }

    /** With nothing open, the first step is the one to do, and Build is never finished. */
    @Test
    void aNewcomerStartsAtTheFirstStep() {
        Map<String, Guide.State> states = Guide.progress(Guide.steps(Guide.Start.NEW),
                Guide.factsOf(null, null));

        assertEquals(Guide.State.NEXT, states.get("project"));
        assertEquals(Guide.State.TO_DO, states.get("requirements"));
        assertEquals(Guide.State.OPTIONAL, states.get("terms"));
        assertEquals(Guide.State.EVERY_TIME, states.get("build"));
    }

    /** How far a project has got is read from what is in it. */
    @Test
    void progressIsReadFromTheProject(@TempDir File root) throws Exception {
        File edit = new File(root, "src/ontology/menu-edit.owl");
        assertTrue(edit.getParentFile().mkdirs());
        write(new File(root, "docs/requirements.md"), "# Requirements");
        write(new File(root, "src/sparql/check_labels.rq"), "SELECT * WHERE { }");
        write(new File(root, "src/sparql/cq1_every_order_has_a_customer.rq"), "SELECT * {}");
        assertTrue(new File(root, "releases/1.0.0").mkdirs());
        assertTrue(new File(root, "releases/1.1.0").mkdirs());
        assertTrue(new File(root, ".git").mkdirs());

        Map<String, Guide.State> states = Guide.progress(Guide.steps(Guide.Start.NEW),
                Guide.factsOf(edit, null));

        assertEquals(Guide.State.DONE, states.get("project"));
        assertEquals(Guide.State.DONE, states.get("requirements"));
        assertEquals(Guide.State.NEXT, states.get("pattern"), "no pattern imported yet");
        assertEquals(Guide.State.TO_DO, states.get("model"));
        assertEquals(Guide.State.DONE, states.get("tests"), "the label check does not count");
        assertEquals(Guide.State.DONE, states.get("release"));
        assertEquals(Guide.State.DONE, states.get("history"));
        assertEquals(Guide.State.DONE, states.get("share"));
    }

    /**
     * The first test the guide writes passes until somebody writes a rule into it.
     *
     * <p>A starter that failed would fail the build the moment the guide wrote it.
     */
    @Test
    void theStarterTestPassesUntilItIsWritten(@TempDir File root) throws Exception {
        File test = new File(root, "cq1_your_first_question.rq");
        write(test, Guide.testTemplate());
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/your-ontology"));

        List<SparqlQuery.Check> checks =
                SparqlQuery.verify(ontology, Collections.singletonList(test));

        assertEquals(null, checks.get(0).getFailure(), "it runs");
        assertTrue(checks.get(0).isPassed());
    }

    private static void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
