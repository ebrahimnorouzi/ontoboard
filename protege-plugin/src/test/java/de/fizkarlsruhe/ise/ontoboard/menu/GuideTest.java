package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
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
        write(new File(root, "docs/requirements.md"), "# Requirements\n\n| CQ | Question |\n|---|---|\n"
                + "| CQ1 | Which customer placed a given order? |\n");
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

    /**
     * The guide's own buttons write a requirements template and a test template. Ticking the
     * steps for those told a newcomer the work was done when it had not begun.
     */
    @Test
    void aStubIsNotWork(@org.junit.jupiter.api.io.TempDir File root) throws Exception {
        File edit = new File(root, "src/ontology/shop-edit.owl");
        assertTrue(edit.getParentFile().mkdirs());
        write(new File(root, "docs/requirements.md"), Guide.requirementsTemplate("Shop"));
        write(new File(root, "src/sparql/cq1_your_first_question.rq"), Guide.testTemplate());

        Guide.Facts facts = Guide.factsOf(edit, null);

        assertTrue(!facts.hasRequirements, "an empty question table is not requirements");
        assertEquals(0, facts.ownTests, "the template is not a test");
    }

    /**
     * In an ODK project a test of one's own is a question - a .rq file in src/sparql - or a
     * check the configuration lists, with its file. ODK's own checks and the label check
     * are not one, and nor is a check file that no list names.
     */
    @Test
    void inAnOdkProjectTheTestsAreTheListedChecks(@TempDir File temp) throws Exception {
        File root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens
                .seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), file.getValue());
        }
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        File yaml = new File(root, "src/ontology/eggboil-odk.yaml");
        String config = de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens.config("eggboil");
        write(yaml, config);
        write(new File(root, "src/sparql/check_labels-violation.sparql"), "SELECT * {}");
        write(new File(root, "src/sparql/check_labels.rq"), "SELECT * {}");
        write(new File(root, "src/sparql/other-violation.sparql"), "SELECT * {}");

        assertEquals(0, Guide.factsOf(edit, null).ownTests,
                "ODK's own, the label check in either form, and a check nobody lists");

        config = de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks.add(config, "dc-properties");
        write(yaml, config);
        write(new File(root, "src/sparql/dc-properties-violation.sparql"), "SELECT * {}");
        assertEquals(0, Guide.factsOf(edit, null).ownTests, "one of ODK's own checks");

        config = de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks.add(config, "no_file_yet");
        write(yaml, config);
        assertEquals(0, Guide.factsOf(edit, null).ownTests, "listed, with no file");

        config = de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks.add(config,
                "every_dataset_has_part");
        write(yaml, config);
        write(new File(root, "src/sparql/every_dataset_has_part-violation.sparql"),
                "SELECT * {}");
        Guide.Facts facts = Guide.factsOf(edit, null);
        assertEquals(1, facts.ownTests, "a question's check");
        assertEquals(Guide.State.DONE, Guide.progress(Guide.steps(Guide.Start.NEW), facts)
                .get("tests"));

        write(new File(root, "src/sparql/every_egg_is_boiled.rq"), "SELECT * {}");
        assertEquals(2, Guide.factsOf(edit, null).ownTests, "and a question");
    }

    /** A question alone is a test of one's own: what Write a test from a question writes. */
    @Test
    void inAnOdkProjectAQuestionIsATestOfOnesOwn(@TempDir File temp) throws Exception {
        File root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens
                .seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), file.getValue());
        }
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        assertEquals(0, Guide.factsOf(edit, null).ownTests);

        write(new File(root, "src/sparql/every_egg_is_boiled.rq"), "SELECT * {}");

        Guide.Facts facts = Guide.factsOf(edit, null);
        assertEquals(1, facts.ownTests);
        assertEquals(Guide.State.DONE, Guide.progress(Guide.steps(Guide.Start.NEW), facts)
                .get("tests"));
    }

    /**
     * A project ODK released has git tags {@code v<version>} and no releases/ folder. Its
     * releases count, and what the guide says it counts is true of it.
     */
    @Test
    void theGuideCountsTaggedReleases(@TempDir File root) throws Exception {
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");
        File edit = new File(root, "src/ontology/demo-edit.owl");
        write(edit, "<rdf:RDF/>");
        git(root, "init", "-q");
        for (String version : new String[] {"1.0.0", "1.1.0"}) {
            write(new File(root, "demo.owl"), "Ontology(<https://example.org/demo.owl>\n"
                    + "Annotation(<http://www.w3.org/2002/07/owl#versionInfo> \"" + version
                    + "\"))\n");
            git(root, "add", "-f", "demo.owl");
            git(root, "commit", "-q", "-m", "release(" + version + ")");
            git(root, "tag", "-a", "v" + version, "-m", "demo " + version);
        }

        Guide.Facts facts = Guide.factsOf(edit, null);

        assertFalse(new File(root, "releases").exists());
        assertEquals(2, facts.releases);
        Map<String, Guide.State> states = Guide.progress(Guide.steps(Guide.Start.NEW), facts);
        assertEquals(Guide.State.DONE, states.get("release"));
        assertEquals(Guide.State.DONE, states.get("history"));
        for (Guide.Step step : Guide.steps(Guide.Start.NEW)) {
            if (step.getId().equals("release") || step.getId().equals("history")) {
                assertFalse(step.getDoneWhen().contains("releases/")
                        && !step.getDoneWhen().contains("tag"), step.getDoneWhen());
            }
        }
    }

    private static void git(File root, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<String>(java.util.Arrays.asList(
                "git", "-c", "user.name=Test", "-c", "user.email=test@example.org", "-c",
                "core.autocrlf=false"));
        command.addAll(java.util.Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
    }
}
