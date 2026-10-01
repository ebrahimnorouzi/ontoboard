package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The build that needs nothing installed.
 *
 * <p>A scaffolded project's Makefile shells out to nine programs, and every one of its ROBOT
 * invocations already has an in-process implementation in this bundle. These tests hold that
 * claim to the only standard worth anything: scaffold a project, run its targets, and look at
 * what appeared on disk.
 */
class InProcessTargetsTest {

    private static File scaffold(File where, String id) {
        OdkProjectConfig config = new OdkProjectConfig(id, "Test ontology",
                "Scaffolded by a test", "http://purl.obolibrary.org/obo/" + id + ".owl",
                "https://creativecommons.org/licenses/by/4.0/", where);
        OdkScaffold.create(config);
        return new File(new File(new File(config.getProjectRoot(), "src"), "ontology"),
                id + "-edit.owl");
    }

    private static OWLOntology load(File owl) throws Exception {
        return OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(IRI.create(owl.toURI()));
    }

    // ---------- what it will and will not touch ----------

    /** A project this plugin scaffolded is eligible, and the check is about the Makefile. */
    @Test
    void aFreshlyScaffoldedProjectIsEligible(@TempDir File where) {
        File edit = scaffold(where, "demo");

        assertNull(InProcessTargets.whyNotEligible(edit));
    }

    /**
     * An edited Makefile is refused by name.
     *
     * <p>The whole safety of this path is that it runs what the Makefile says. Once the Makefile
     * says something else, running the generated steps anyway would execute a build the project
     * does not describe - silently, and differently from its CI.
     */
    @Test
    void anEditedMakefileIsRefused(@TempDir File where) throws Exception {
        File edit = scaffold(where, "demo");
        File makefile = new File(edit.getParentFile(), "Makefile");
        Files.write(makefile.toPath(),
                ("# somebody changed this\n" + new String(
                        Files.readAllBytes(makefile.toPath()), "UTF-8")).getBytes("UTF-8"));

        String why = InProcessTargets.whyNotEligible(edit);

        assertNotNull(why, "an edited Makefile must not be run from memory");
        assertTrue(why.contains("not the one OntoBoard generates"), why);
    }

    /** A real ODK project is refused, and told where its build does belong. */
    @Test
    void aForeignProjectIsRefused(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "Makefile").toPath(),
                "all:\n\techo not ours\n".getBytes("UTF-8"));
        File edit = new File(ontology, "mwo-edit.owl");
        Files.write(edit.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        String why = InProcessTargets.whyNotEligible(edit);

        assertNotNull(why);
        assertTrue(why.contains("Docker") || why.contains("native ODK"),
                "a refusal has to say where the build does belong: " + why);
    }

    /** The composite targets expand the way the generated Makefile declares them. */
    @Test
    void compositeTargetsExpandAsTheMakefileSaysTheyDo() {
        assertEquals(java.util.Arrays.asList("reason", "report"),
                InProcessTargets.stepsFor("all"));
        assertEquals(java.util.Arrays.asList("reason", "report", "sparql_test",
                "validate_profile"), InProcessTargets.stepsFor("test"));
        assertEquals(java.util.Collections.singletonList("report"),
                InProcessTargets.stepsFor("report"));
    }

    /** prepare_release is handed to the action that already does it with more care. */
    @Test
    void prepareReleaseIsSentToTheReleaseAction(@TempDir File where) throws Exception {
        File edit = scaffold(where, "demo");
        OWLOntology ontology = load(edit);

        InProcessTargets.Outcome outcome =
                InProcessTargets.run(edit, ontology, null, "prepare_release");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("Project > Release"), outcome.getFailure());
    }

    // ---------- it actually builds ----------

    /**
     * The whole point, end to end: targets that produce files, with nothing installed.
     *
     * <p>No make, no robot binary, no container - this test would pass on a machine with none of
     * them, which is the claim being made.
     */
    @Test
    void aScaffoldedProjectBuildsWithNothingInstalled(@TempDir File where) throws Exception {
        File edit = scaffold(where, "demo");
        OWLOntology ontology = load(edit);
        org.semanticweb.owlapi.reasoner.OWLReasonerFactory elk =
                de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK.newFactory();
        int axiomsBefore = ontology.getAxiomCount();

        InProcessTargets.Outcome all = InProcessTargets.run(edit, ontology, elk, "all");

        assertTrue(all.isOk(), String.valueOf(all.getFailure()));
        File reasoned = new File(edit.getParentFile(), "demo.owl");
        File report = new File(edit.getParentFile(), "report.tsv");
        assertTrue(reasoned.isFile(), "reason must write the published file");
        assertTrue(report.isFile(), "report must write report.tsv");
        assertTrue(reasoned.length() > 0 && report.length() > 0);

        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "the ontology Protege has open must not be touched by a build");

        InProcessTargets.Outcome clean = InProcessTargets.run(edit, ontology, elk, "clean");

        assertTrue(clean.isOk(), String.valueOf(clean.getFailure()));
        assertFalse(reasoned.isFile(), "clean removes what the build produced");
        assertFalse(report.isFile());
    }

    /**
     * A new project passes its own quality gate.
     *
     * <p>It did not, until the scaffold was made to declare the three dcterms annotation
     * properties it uses in the header. OWL 2 DL requires every annotation property that is used
     * to be declared, so `validate_profile` reported three violations on a project that had just
     * been created and contained no terms - which means `make test` failed and the generated CI
     * went red on the first push. Found by running the scaffold's own build against its own
     * output, which is the only way it could have been found.
     */
    @Test
    void aNewProjectPassesItsOwnTestTarget(@TempDir File where) throws Exception {
        File edit = scaffold(where, "demo");
        OWLOntology ontology = load(edit);
        org.semanticweb.owlapi.reasoner.OWLReasonerFactory elk =
                de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK.newFactory();

        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology, elk, "test");

        assertTrue(outcome.isOk(),
                "a project OntoBoard just created must pass its own test target: "
                        + outcome.getFailure() + "\n" + outcome.getTranscript());
    }

    /** The project root is two levels above the edit file, and absent outside a project. */
    @Test
    void theProjectRootIsFoundFromTheEditFile(@TempDir File where) {
        File edit = scaffold(where, "demo");

        assertEquals(new File(where, "demo"), InProcessTargets.projectRoot(edit));
        assertNull(InProcessTargets.projectRoot(null));
    }

    // ---------- the documentation site ----------

    /**
     * A scaffolded project publishes to GitHub Pages, the way an ODK one does.
     *
     * <p>Until 1.71.0 it did not: the scaffold wrote `qc.yml` and nothing else, so a project
     * created here had no documentation site at all while an ODK-created one published on its
     * first push. The layout is taken from a real ODK repository - mkdocs.yaml at the root, pages
     * under docs/, and a workflow using mhausenblas/mkdocs-deploy-gh-pages.
     */
    @Test
    void aScaffoldedProjectGetsADocumentationSite(@TempDir File where) {
        File edit = scaffold(where, "demo");
        File root = InProcessTargets.projectRoot(edit);

        assertTrue(new File(root, "mkdocs.yaml").isFile(), "mkdocs.yaml at the repository root");
        assertTrue(new File(root, "docs/index.md").isFile());
        assertTrue(new File(root, "docs/editing.md").isFile());
        assertTrue(new File(root, "docs/release.md").isFile());
        assertTrue(new File(root, ".github/workflows/docs.yml").isFile());
    }

    /**
     * Every page the nav names exists.
     *
     * <p>mkdocs fails the build on a nav entry pointing at a file that is not there, so a typo
     * here would break the site on the first push with an error about the config rather than
     * about the missing page.
     */
    @Test
    void everyPageInTheNavIsWritten(@TempDir File where) throws Exception {
        File edit = scaffold(where, "demo");
        File root = InProcessTargets.projectRoot(edit);
        String mkdocs = new String(
                Files.readAllBytes(new File(root, "mkdocs.yaml").toPath()), "UTF-8");

        java.util.regex.Matcher pages = java.util.regex.Pattern
                .compile("(?m)^\\s+-\\s+[^:]+:\\s*(\\S+\\.md)\\s*$").matcher(mkdocs);
        int found = 0;
        while (pages.find()) {
            found++;
            assertTrue(new File(new File(root, "docs"), pages.group(1)).isFile(),
                    "nav names docs/" + pages.group(1) + ", which is not there");
        }
        assertTrue(found >= 3, "expected the nav to list the seeded pages, found " + found);
    }

    /**
     * The workflow names the config file explicitly.
     *
     * <p>ODK's config is {@code mkdocs.yaml}; the action defaults to {@code mkdocs.yml}. Without
     * CONFIG_FILE the deployment fails with "config file not found" on a repository that looks
     * entirely correct, which is a bad first experience of a feature nobody asked to debug.
     */
    @Test
    void theDocsWorkflowNamesTheConfigFile(@TempDir File where) throws Exception {
        File edit = scaffold(where, "demo");
        File root = InProcessTargets.projectRoot(edit);
        String workflow = new String(Files.readAllBytes(
                new File(root, ".github/workflows/docs.yml").toPath()), "UTF-8");

        assertTrue(workflow.contains("CONFIG_FILE: mkdocs.yaml"), workflow);
        assertTrue(workflow.contains("mkdocs-deploy-gh-pages"), workflow);
        assertTrue(workflow.contains("contents: write"),
                "the action pushes a gh-pages branch, so it needs write permission");
    }

    /**
     * The site is seeded, not regenerated.
     *
     * <p>mkdocs.yaml carries a nav the author curates and the pages are theirs to write.
     * Re-rendering either would throw away the only part of the site worth having, so neither
     * may appear in the regenerable set. The workflow that publishes them may, because nobody
     * edits that.
     */
    @Test
    void thePagesAreSeededAndTheWorkflowIsRegenerable() {
        OdkProjectConfig config = new OdkProjectConfig("demo", "Test ontology", "d",
                "http://purl.obolibrary.org/obo/demo.owl",
                "https://creativecommons.org/licenses/by/4.0/", new File("."));
        java.util.Map<String, String> regenerable =
                OdkScaffold.regenerableFiles(config, OdkScaffold.ROBOT_VERSION);

        assertFalse(regenerable.containsKey("mkdocs.yaml"),
                "regenerating this would discard the author's nav");
        assertFalse(regenerable.containsKey("docs/index.md"),
                "regenerating this would discard what they wrote");
        assertTrue(regenerable.containsKey(".github/workflows/docs.yml"),
                "the publishing workflow is machinery and should stay current");
    }

    // ---------- the toolchain report ----------

    /** What the user can do never depends on a tool they do not need. */
    @Test
    void capabilitiesDoNotDemandToolsThatAreNotNeeded() {
        List<Toolchain.Capability> bare =
                Toolchain.capabilities(false, false, false, false);

        int available = 0;
        for (Toolchain.Capability capability : bare) {
            if (capability.isAvailable()) {
                available++;
            }
        }
        assertTrue(available >= 3,
                "editing, scaffolding and building a scaffolded project need nothing installed");

        for (Toolchain.Capability capability : bare) {
            if (capability.getName().startsWith("Build an existing ODK")) {
                assertFalse(capability.isAvailable(),
                        "with no native env and no container, an ODK repo cannot be built");
                assertTrue(capability.getHow().toLowerCase(java.util.Locale.ROOT)
                        .contains("docker"), capability.getHow());
            }
        }
    }

    /** A native environment is preferred over a container, and said so. */
    @Test
    void aNativeEnvironmentIsPreferredOverAContainer() {
        for (Toolchain.Capability capability
                : Toolchain.capabilities(true, true, true, true)) {
            if (capability.getName().startsWith("Build an existing ODK")) {
                assertTrue(capability.isAvailable());
                assertTrue(capability.getHow().contains("no container"), capability.getHow());
            }
        }
    }

    /**
     * The activation command survives a path with a space, which on Windows is the normal case.
     *
     * <p>{@code .} rather than {@code source}: the script is POSIX and {@code source} is a
     * bashism that {@code dash} - {@code /bin/sh} on Debian and Ubuntu - does not have.
     */
    @Test
    void theNativeCommandQuotesItsPath(@TempDir File root) throws Exception {
        File bin = new File(root, "my env/bin");
        assertTrue(bin.mkdirs());
        File script = new File(bin, "activate-odk-environment.sh");
        Files.write(script.toPath(), "#".getBytes("UTF-8"));

        List<String> command = Toolchain.nativeCommand(script, "test");

        assertEquals("sh", command.get(0));
        assertEquals("-c", command.get(1));
        assertTrue(command.get(2).startsWith(". '"), command.get(2));
        assertTrue(command.get(2).contains("my env"), command.get(2));
        assertTrue(command.get(2).endsWith("&& make test"), command.get(2));
        // odk-helper comes from `pip install --user odk-core` and lives in the pip bin
        // directory, which the activation script does not add. A real ODK Makefile calls
        // it for every release artefact (check_rdfxml_%), so without this a native build
        // dies at the first one with "odk-helper: No such file or directory" - reproduced
        // on Ubuntu 22.04 while verifying the route.
        assertTrue(command.get(2).contains("$HOME/.local/bin"), command.get(2));
        assertFalse(command.get(2).startsWith("source"), "dash has no source: " + command.get(2));
    }

    /** A single quote in a path cannot end the quoting early. */
    @Test
    void aQuoteInThePathIsEscaped() {
        assertEquals("'it'\\''s here'", Toolchain.quote("it's here"));
    }

    /** The environment is recognised whether the user picks the directory or its bin. */
    @Test
    void theActivationScriptIsFoundEitherWay(@TempDir File root) throws Exception {
        assertNull(Toolchain.activationScript(root), "nothing there yet");

        File bin = new File(root, "bin");
        assertTrue(bin.mkdirs());
        File script = new File(bin, "activate-odk-environment.sh");
        Files.write(script.toPath(), "#".getBytes("UTF-8"));

        assertEquals(script, Toolchain.activationScript(root), "the environment directory");
        assertEquals(script, Toolchain.activationScript(bin), "or its bin");
        assertNull(Toolchain.activationScript(null));
    }

    /** Paths and command lines reach the dialog as text, not as markup. */
    @Test
    void theRequirementsPanelEscapesWhatItShows() {
        assertEquals("C:\\dev\\&lt;odk&gt;", RequirementsPanel.escape("C:\\dev\\<odk>"));
        assertEquals("a &amp; b", RequirementsPanel.escape("a & b"));
        assertEquals("", RequirementsPanel.escape(null));
    }
}
