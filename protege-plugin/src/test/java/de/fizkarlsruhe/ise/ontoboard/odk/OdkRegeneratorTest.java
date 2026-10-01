package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The regenerator, and the fixed-point property the whole of Phase 3 rests on. */
class OdkRegeneratorTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CC0 = "https://creativecommons.org/publicdomain/zero/1.0/";

    private static File scaffold(File into, String id, String baseIri) {
        OdkProjectConfig config = new OdkProjectConfig(id, "A Test Ontology",
                "Built by a test.", baseIri, CC0, into);
        OdkScaffold.create(config);
        return config.getProjectRoot();
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), UTF8);
    }

    /**
     * The generator is a fixed point: scaffold, regenerate, nothing changes.
     *
     * <p>This is condition F6 of the plan, and it is the one property that makes a regenerator safe
     * to point at somebody's real project. A generator that is not a fixed point rewrites files on
     * every run, which means a user cannot tell a real change from noise - and the first time they
     * stop reading the diff is the time it deletes something.
     */
    @Test
    void regeneratingAFreshProjectChangesNothing(@TempDir File dir) {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");

        OdkRegenerator.Plan plan = OdkRegenerator.plan(project);

        assertTrue(plan.isUpToDate(),
                "the scaffold and the regenerator must agree exactly; these differ: "
                        + describe(plan));
        assertFalse(plan.getChanges().isEmpty(), "it should have rendered something to compare");
    }

    /** Applying an up-to-date plan writes nothing, so `make` is not made to rebuild for nothing. */
    @Test
    void applyingAnUpToDatePlanWritesNothing(@TempDir File dir) {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");

        assertEquals(new ArrayList<File>(),
                OdkRegenerator.apply(project, OdkRegenerator.plan(project)));
    }

    /**
     * A base IRI ending in {@code .owl} survives the round trip.
     *
     * <p>The YAML's {@code uribase} drops the last segment, so
     * {@code http://purl.obolibrary.org/obo/mwo.owl} is recorded as
     * {@code http://purl.obolibrary.org/obo} and cannot be rebuilt from it. The settings reader
     * takes the base IRI from the edit file's {@code xml:base} instead. An extension-free IRI
     * round-trips through {@code uribase} by luck, which is why this test uses one that does not.
     */
    @Test
    void thebaseIriSurvivesEvenWithAnExtension(@TempDir File dir) {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");

        OdkProjectSettings.Settings settings = OdkProjectSettings.read(project);

        assertEquals("http://purl.obolibrary.org/obo/mwo.owl", settings.getConfig().getBaseIri());
        assertTrue(OdkRegenerator.plan(project).isUpToDate(),
                "a base IRI the reader got wrong would show up as a changed README");
    }

    /** Every field the generated files depend on comes back. */
    @Test
    void theSettingsRoundTrip(@TempDir File dir) {
        File project = scaffold(dir, "mwo", "https://w3id.org/mwo");

        OdkProjectSettings.Settings settings = OdkProjectSettings.read(project);

        assertEquals("mwo", settings.getConfig().getOntologyId());
        assertEquals("A Test Ontology", settings.getConfig().getTitle());
        assertEquals("Built by a test.", settings.getConfig().getDescription());
        assertEquals("https://w3id.org/mwo", settings.getConfig().getBaseIri());
        assertEquals(CC0, settings.getConfig().getLicense());
        assertEquals(OdkScaffold.ROBOT_VERSION, settings.getRobotVersion(),
                "the project records the ROBOT version it was created with");
    }

    /**
     * The ROBOT version comes from the project, not from whichever plugin regenerates it.
     *
     * <p>Otherwise re-rendering would move somebody's CI onto a different ROBOT because a different
     * OntoBoard happened to run - a side effect "re-render the generated files" is not allowed to
     * have, and one that would also make the regenerated file depend on the tool rather than the
     * project, breaking the fixed point.
     */
    @Test
    void theProjectsOwnRobotVersionIsUsed(@TempDir File dir) throws Exception {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        File yaml = new File(new File(new File(project, "src"), "ontology"), "mwo-odk.yaml");
        Files.write(yaml.toPath(), read(yaml)
                .replace("robot_version: " + OdkScaffold.ROBOT_VERSION, "robot_version: 1.9.0")
                .getBytes(UTF8));

        OdkRegenerator.Plan plan = OdkRegenerator.plan(project);

        assertEquals("1.9.0", plan.getRobotVersion());
        assertTrue(plan.isRobotVersionFromProject());
        // The CI is the only generated file carrying it, so that is the one that must change.
        List<String> changed = paths(plan.getChanged());
        assertEquals(java.util.Arrays.asList(".github/workflows/qc.yml"), changed,
                "only the workflow mentions the ROBOT version: " + changed);
        assertTrue(plan.getChanged().get(0).getAfter().contains("1.9.0"));
    }

    /** A project predating robot_version says so rather than silently taking the plugin's. */
    @Test
    void aprojectWithNoRecordedRobotVersionIsFlagged(@TempDir File dir) throws Exception {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        File yaml = new File(new File(new File(project, "src"), "ontology"), "mwo-odk.yaml");
        List<String> kept = new ArrayList<String>();
        for (String line : read(yaml).split("\n", -1)) {
            if (!line.startsWith("robot_version:")) {
                kept.add(line);
            }
        }
        Files.write(yaml.toPath(), String.join("\n", kept).getBytes(UTF8));

        OdkRegenerator.Plan plan = OdkRegenerator.plan(project);

        assertFalse(plan.isRobotVersionFromProject(),
                "the caller must be able to warn that this would write the plugin's version");
        assertEquals(OdkScaffold.ROBOT_VERSION, plan.getRobotVersion());
    }

    /**
     * The files somebody owns are never in the plan.
     *
     * <p>Two of these hold state the generator cannot know: the ID ranges carry every editor's
     * allocation, and the catalog carries real import mappings that Catalog.addEntry wrote. The
     * template for each would delete that.
     */
    @Test
    void theSeededFilesAreNeverRegenerated(@TempDir File dir) {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");

        List<String> planned = paths(OdkRegenerator.plan(project).getChanges());

        for (String owned : OdkScaffold.seededFilesFor("mwo")) {
            assertFalse(planned.contains(owned), owned + " must never be regenerated: " + planned);
        }
    }

    /** A real edit, re-rendered. This is what the action exists to do. */
    @Test
    void astaleGeneratedFileIsBroughtUpToDate(@TempDir File dir) throws Exception {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        File makefile = new File(new File(new File(project, "src"), "ontology"), "Makefile");
        Files.write(makefile.toPath(), "# an older generator wrote something else\n".getBytes(UTF8));

        OdkRegenerator.Plan plan = OdkRegenerator.plan(project);
        assertEquals(java.util.Arrays.asList("src/ontology/Makefile"), paths(plan.getChanged()));
        assertTrue(plan.getChanged().get(0).changedLines() > 10);

        List<File> written = OdkRegenerator.apply(project, plan);

        assertEquals(1, written.size());
        assertTrue(read(makefile).contains("$(ROBOT) reason"));
        assertTrue(OdkRegenerator.plan(project).isUpToDate(), "and now it is a fixed point again");
    }

    /** A CRLF checkout is not a change. Git commonly rewrites line endings on checkout. */
    @Test
    void crlfLineEndingsDoNotCountAsAChange(@TempDir File dir) throws Exception {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        File readme = new File(project, "README.md");
        Files.write(readme.toPath(), read(readme).replace("\n", "\r\n").getBytes(UTF8));

        assertTrue(OdkRegenerator.plan(project).isUpToDate(),
                "core.autocrlf=true would otherwise make every generated file look changed");
    }

    /** A missing file is a change, and creating it is how a project gains a new generated file. */
    @Test
    void adeletedGeneratedFileIsPutBack(@TempDir File dir) {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        File workflow = new File(new File(new File(project, ".github"), "workflows"), "qc.yml");
        assertTrue(workflow.delete());

        OdkRegenerator.Plan plan = OdkRegenerator.plan(project);

        assertEquals(java.util.Arrays.asList(".github/workflows/qc.yml"), paths(plan.getChanged()));
        assertTrue(plan.getChanged().get(0).isNew());
        OdkRegenerator.apply(project, plan);
        assertTrue(workflow.isFile());
    }

    /**
     * A relaxed report rule survives an update.
     *
     * <p>The generated Makefile tells the user to edit profile.txt - "change ERROR to WARN or INFO
     * rather than deleting the line" - so a regenerator that overwrote it would re-arm every rule
     * the project had deliberately relaxed, and their next CI run would fail for something nobody
     * touched. The same argument covers their SPARQL checks.
     */
    @Test
    void aprojectsOwnProfileAndChecksAreLeftAlone(@TempDir File dir) throws Exception {
        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        File profile = new File(new File(new File(project, "src"), "ontology"), "profile.txt");
        File checks = new File(new File(new File(project, "src"), "sparql"), "check_labels.rq");
        Files.write(profile.toPath(), "INFO\tmissing_label\n".getBytes(UTF8));
        Files.write(checks.toPath(), "# our own check\n".getBytes(UTF8));

        assertTrue(OdkRegenerator.plan(project).isUpToDate(),
                "neither is regenerable, so neither can be out of date");
        OdkRegenerator.apply(project, OdkRegenerator.plan(project));

        assertEquals("INFO\tmissing_label\n", read(profile),
                "a relaxed rule must survive - overwriting it turns CI red for nothing");
        assertEquals("# our own check\n", read(checks));
    }

    @Test
    void somethingThatIsNotAProjectIsRefusedWithAReason(@TempDir File dir) {
        OdkProjectSettings.UnreadableProjectException thrown =
                assertThrows(OdkProjectSettings.UnreadableProjectException.class,
                        () -> OdkRegenerator.plan(dir));
        assertTrue(thrown.getMessage().contains("src/ontology"), thrown.getMessage());

        assertThrows(OdkProjectSettings.UnreadableProjectException.class,
                () -> OdkRegenerator.plan(new File(dir, "nowhere")));
    }

    /**
     * Condition F6, in its own words: regenerate a committed project and {@code git status
     * --porcelain} is empty.
     *
     * <p>The fixed-point test above compares in memory. This one goes through git, which is the
     * form the condition is written in and the form that catches one thing the other cannot: line
     * endings. {@code core.autocrlf=true} is a common global setting, so a checked-out generated
     * file can be CRLF on disk while its blob is LF - and a regenerator that rewrote every file
     * because of that would produce a diff nobody reads.
     *
     * <p>Skipped where git is absent rather than failed, like the Docker-dependent tests.
     */
    @Test
    void regeneratingACommittedProjectLeavesGitClean(@TempDir File dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitWorks(dir), "git is not available");

        File project = scaffold(dir, "mwo", "http://purl.obolibrary.org/obo/mwo.owl");
        // The project root is dir/mwo, so the repository goes there - the same shape as a real
        // ODK repository, where the project root IS the repository root.
        run(project, "git", "init", "-q");
        run(project, "git", "config", "user.email", "test@example.org");
        run(project, "git", "config", "user.name", "Test");
        run(project, "git", "add", "-A");
        run(project, "git", "commit", "-q", "-m", "scaffolded");

        assertEquals("", run(project, "git", "status", "--porcelain").trim(),
                "the scaffold's own output should be committed cleanly");

        OdkRegenerator.apply(project, OdkRegenerator.plan(project));

        assertEquals("", run(project, "git", "status", "--porcelain").trim(),
                "regenerating a committed project must change nothing - F6");
    }

    private static boolean gitWorks(File where) {
        try {
            run(where, "git", "--version");
            return true;
        } catch (Exception notThere) {
            return false;
        }
    }

    /** Runs a command in a directory and returns its combined output. */
    private static String run(File in, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(in);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        java.io.InputStream out = process.getInputStream();
        for (int read = out.read(chunk); read >= 0; read = out.read(chunk)) {
            captured.write(chunk, 0, read);
        }
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new java.io.IOException("timed out: " + java.util.Arrays.toString(command));
        }
        String output = new String(captured.toByteArray(), UTF8);
        if (process.exitValue() != 0) {
            throw new java.io.IOException(java.util.Arrays.toString(command) + " exited "
                    + process.exitValue() + ": " + output);
        }
        return output;
    }

    private static List<String> paths(List<OdkRegenerator.Change> changes) {
        List<String> paths = new ArrayList<String>();
        for (OdkRegenerator.Change change : changes) {
            paths.add(change.getPath());
        }
        return paths;
    }

    private static String describe(OdkRegenerator.Plan plan) {
        StringBuilder text = new StringBuilder();
        for (OdkRegenerator.Change change : plan.getChanged()) {
            text.append(change.getPath()).append(" (").append(change.changedLines())
                    .append(" lines) ");
        }
        return text.toString();
    }

    // ---------- regeneration must never reach a project OntoBoard did not scaffold ----------

    /**
     * An ODK repository is refused, and told the command that does work.
     *
     * <p>The danger this prevents is total. Regeneration rewrites src/ontology/Makefile from
     * OntoBoard's template, which has eight targets; MWO's real one is 767 lines carrying every
     * import rule, release artefact and quality target the project has. Replacing one with the
     * other is not an update, it is deletion.
     *
     * <p>It became reachable in 1.72.0 and not before. Until then baseIriIn could not read an OWL
     * functional-syntax edit file, so every real ODK project failed earlier with "records no
     * xml:base" - an accident that protected them. Teaching the reader functional syntax removed
     * that accident, so this guard replaces it deliberately.
     */
    @Test
    void anOdkRepositoryIsRefusedBecauseOdkRegeneratesItself(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "run.sh").toPath(),
                "docker run --rm -ti obolibrary/odkfull \"$@\"".getBytes("UTF-8"));

        String why = OdkRegenerator.whyNotOurs(root);

        assertNotNull(why, "an ODK repository must never be regenerated from here");
        assertTrue(why.contains("run.sh update_repo"),
                "a refusal has to name the command that does work: " + why);

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> OdkRegenerator.plan(root));
        assertTrue(refused.getMessage().contains("ODK repository"), refused.getMessage());
    }

    /** ODK's own Makefile is recognised even where the wrapper has been moved or removed. */
    @Test
    void anOdkMakefileIsRecognisedWithoutTheWrapper(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "Makefile").toPath(),
                ("# ODK generated\nODK_VERSION_MAKEFILE = v1.6\nall: reason\n").getBytes("UTF-8"));

        String why = OdkRegenerator.whyNotOurs(root);

        assertNotNull(why, "the Makefile alone identifies it");
        assertTrue(why.contains("generated by ODK"), why);
    }

    /** A project OntoBoard scaffolded is not refused - the guard must not block its own work. */
    @Test
    void aScaffoldedProjectIsNotRefused(@TempDir File where) {
        OdkProjectConfig config = new OdkProjectConfig("demo", "Demo", "d",
                "http://purl.obolibrary.org/obo/demo.owl",
                "https://creativecommons.org/licenses/by/4.0/", where);
        OdkScaffold.create(config);

        assertNull(OdkRegenerator.whyNotOurs(config.getProjectRoot()));
        assertEquals(0, OdkRegenerator.plan(config.getProjectRoot()).getChanged().size(),
                "a project just created is already up to date");
    }

    // ---------- the base IRI of an ODK edit file ----------

    /**
     * An OWL functional-syntax edit file yields its ontology IRI.
     *
     * <p>Reported from the field against MWO: "mwo-edit.owl records no xml:base and no
     * owl:Ontology rdf:about". It records neither, because it is not RDF/XML - it opens with
     * Prefix(...) declarations and then Ontology(&lt;iri&gt;). ODK's own release recipe ends in
     * convert -f ofn, and Protege writes the same when asked for functional syntax, so this is
     * the normal shape of an ODK edit file rather than an unusual one.
     */
    @Test
    void aFunctionalSyntaxEditFileYieldsItsIri(@TempDir File directory) throws Exception {
        File edit = new File(directory, "mwo-edit.owl");
        Files.write(edit.toPath(), ("Prefix(:=<http://purls.helmholtz-metadaten.de/mwo/mwo.owl#>)\n"
                + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
                + "\n"
                + "Ontology(<http://purls.helmholtz-metadaten.de/mwo/mwo.owl>\n"
                + "Import(<http://purls.helmholtz-metadaten.de/mwo/mwo/imports/iao_import.owl>)\n"
                + ")\n").getBytes("UTF-8"));

        assertEquals("http://purls.helmholtz-metadaten.de/mwo/mwo.owl",
                OdkProjectSettings.baseIriIn(edit));
    }

    /** RDF/XML still wins where both could match, since xml:base is the authored value. */
    @Test
    void rdfXmlIsStillPreferred(@TempDir File directory) throws Exception {
        File edit = new File(directory, "demo-edit.owl");
        Files.write(edit.toPath(), ("<rdf:RDF xml:base=\"http://example.org/demo.owl\">\n"
                + "  <owl:Ontology rdf:about=\"http://example.org/other.owl\"/>\n"
                + "</rdf:RDF>\n").getBytes("UTF-8"));

        assertEquals("http://example.org/demo.owl", OdkProjectSettings.baseIriIn(edit));
    }

    /** An anonymous ontology still fails, and says what to do about it. */
    @Test
    void anAnonymousOntologyIsStillRefused(@TempDir File directory) throws Exception {
        File edit = new File(directory, "demo-edit.owl");
        Files.write(edit.toPath(), "Ontology(\n)\n".getBytes("UTF-8"));

        RuntimeException failed = assertThrows(RuntimeException.class,
                () -> OdkProjectSettings.baseIriIn(edit));
        assertTrue(failed.getMessage().contains("give it an IRI"), failed.getMessage());
    }
}
