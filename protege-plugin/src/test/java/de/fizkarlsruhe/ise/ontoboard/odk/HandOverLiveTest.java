package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

/**
 * A project handed from one to the other and carried on there, both ways, with nothing standing
 * in: ODK's own make and update in ODK's container, as somebody who works with ODK runs them;
 * OntoBoard's own engine and release, as Build... and Release... run them without a container;
 * and git itself.
 *
 * <p>Made by OntoBoard (New ODK project...), worked on and released with OntoBoard, then carried
 * on with ODK - its update, its test, its release - and back to OntoBoard for the next release.
 * Made by ODK's own seed and released with ODK, then carried on with OntoBoard - one more term of
 * an import, a class, a question, a release - and back to ODK. At each hand-over the other side
 * goes on without a word: ODK's update changes no more than it changes in a project its own seed
 * made, and nothing the second time; ODK's test asks the questions OntoBoard wrote; each side's
 * release is held against the other's.
 *
 * <p>The imports come from the small ontologies in the project's {@code upstream/} folder, given
 * to make as OBOBASE, so nothing is fetched from the network. Skipped without ODK v1.6.1's image
 * or without git.
 */
class HandOverLiveTest {

    /** Where make in ODK's container finds {@code upstream/}: the project is /work there. */
    private static final String UPSTREAM_IN_DOCKER = "file:///work/upstream";

    /**
     * What ODK v1.6.1's first update writes in a project its own seed made (docs/limitations.md,
     * "A project just made shows two small changes"), besides the edit file, which it writes again
     * through ROBOT ({@code robot odk:import ... convert -f ofn}): the same ontology, as ROBOT
     * writes it. The update after it writes nothing.
     */
    private static final Set<String> ODKS_FIRST_UPDATE = new TreeSet<String>(Arrays.asList(
            ".gitignore", "src/ontology/catalog-v001.xml"));

    private static final String EGG = "https://example.org/eggboil/";
    private static final String OBO = "http://purl.obolibrary.org/obo/";

    private static String runtime;
    private static String image;
    private static OdkContainer.Host host;

    @BeforeAll
    static void lookForTheImage() {
        runtime = MakeRun.containerRuntime(ProcessRunner.real());
        if (runtime != null) {
            image = OdkImage.pinned(OdkImage.probe(runtime, ProcessRunner.real()));
            host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
        }
    }

    // ------------------------------------------------------------------ OntoBoard, then ODK

    @Test
    void aProjectOntoBoardMadeIsCarriedOnWithOdkAndBack(@TempDir File temp) throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");

        // OntoBoard: New ODK project..., a class, a question, Build..., Release...
        OdkCreate.Created made = OdkCreate.create(new OdkProjectConfig("eggboil",
                "Test kitchen ontology", "Application ontology for boiling eggs in a test "
                        + "kitchen", "https://example.org/eggboil/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", temp), null,
                ProcessRunner.real());
        assumeTrue(made.committed(), "git made no first commit here: " + made.notRecorded());
        File root = new File(temp, "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        identify(root);
        String boiling = EGG + "EGGBOIL_0000001";
        add(edit, klass(boiling, "egg boiling"));
        String labelled = question(root, QuestionTests.Template.EVERY_ONE_HAS_A_LABEL,
                "Does every egg boiling have a label?", boiling, null);
        builtByOntoBoard(edit, Collections.<String, String>emptyMap(), labelled);
        releasedByOntoBoard(edit, "1.0.0", false);

        // ODK: its update, its test, its release.
        updatedByOdk(root, "src/ontology/eggboil-edit.owl");
        List<String> tested = odk(root, "make", "test");
        assertTrue(asked(tested, labelled), "ODK's test asks OntoBoard's question:\n"
                + String.join("\n", tested));
        releasedByOdk(root, "1.1.0");

        // OntoBoard again: a class more, Build..., Release..., held against ODK's 1.1.0.
        String timer = EGG + "EGGBOIL_0000002";
        add(edit, klass(timer, "egg timer"));
        builtByOntoBoard(edit, Collections.<String, String>emptyMap(), labelled);
        String said = releasedByOntoBoard(edit, "1.2.0", false);
        assertTrue(said.contains("Compared with the 1.1.0 release"),
                "OntoBoard reads the release ODK made:\n" + said);
        String notes = read(new File(root, "releases/1.2.0/CHANGES.md"));
        assertTrue(notes.contains("EGGBOIL_0000002"), notes);
        assertEquals(Arrays.asList("v1.2.0", "v1.1.0", "v1.0.0"),
                GitTags.releases(root, ProcessRunner.real()));

        // And ODK once more, on what OntoBoard left.
        assertTrue(asked(odk(root, "make", "test"), labelled));
    }

    // ------------------------------------------------------------------ ODK, then OntoBoard

    @Test
    void aProjectOdkMadeIsCarriedOnWithOntoBoardAndBack(@TempDir File temp) throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");
        String offline = "OBOBASE=" + UPSTREAM_IN_DOCKER;

        // ODK: its seed, somebody's ontology on its three imports, the first commit, its
        // refresh of the imports, its test, its release.
        File root = new File(temp, "loim");
        OdkSeedInContainer.run(runtime, image, host, Goldens.config("localimports"), root, null,
                ProcessRunner.real());
        ImportCases.lay(root, "equivalence/loim/overlay");
        for (java.util.Map.Entry<String, byte[]> file : Goldens.tree(new File(Goldens.root(),
                "equivalence/loim/upstream")).entrySet()) {
            File to = new File(root, "upstream/" + file.getKey());
            Files.createDirectories(to.getParentFile().toPath());
            Files.write(to.toPath(), file.getValue());
        }
        git(root, "init", "-q");
        identify(root);
        Files.write(new File(root, ".git/info/exclude").toPath(),
                "upstream/\n".getBytes(StandardCharsets.UTF_8));
        git(root, "add", "-A");
        git(root, "commit", "-q", "-m", "Initial commit");
        odk(root, "make", "refresh-imports", offline);
        odk(root, "make", "test", offline);
        releasedByOdk(root, "1.0.0", offline);

        // OntoBoard: one more term of RO in its term list (Import terms...), its module built
        // again, a class on the imported terms, a question, Build..., Release....
        File edit = new File(root, "src/ontology/loim-edit.owl");
        Map<String, String> upstream = Collections.singletonMap("OBOBASE",
                ImportCases.upstream(root));
        ImportCases.lay(root, "equivalence/loim/more");
        OdkEngine.Outcome refreshed = OdkEngine.run(edit, "refresh-ro", upstream, null);
        assertTrue(refreshed.isOk(), said(refreshed));
        assertTrue(read(new File(root, "src/ontology/imports/ro_import.owl"))
                .contains("RO_0002577"), "the module has the term asked for");
        String timer = OBO + "LOIM_0000100";
        add(edit, klass(timer, "egg timer") + someOf(timer, OBO + "RO_0002234",
                OBO + "BFO_0000015"));
        String outputs = question(root, QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME,
                "Does every kind of egg timer have some output?", timer, OBO + "RO_0002234");
        builtByOntoBoard(edit, upstream, outputs);
        String said = releasedByOntoBoard(edit, "1.1.0", false);
        assertTrue(said.contains("Compared with the 1.0.0 release"),
                "OntoBoard reads the release ODK made:\n" + said);

        // ODK again: its update, its test, which asks OntoBoard's question, its release.
        updatedByOdk(root, "src/ontology/loim-edit.owl");
        List<String> tested = odk(root, "make", "test", offline);
        assertTrue(asked(tested, outputs), "ODK's test asks OntoBoard's question:\n"
                + String.join("\n", tested));
        releasedByOdk(root, "1.2.0", offline);
        assertEquals(Arrays.asList("v1.2.0", "v1.1.0", "v1.0.0"),
                GitTags.releases(root, ProcessRunner.real()));
    }

    // ------------------------------------------------------------------ OntoBoard's side

    /** Build... as the engine runs it: ODK's test passes, and asks the project's question. */
    private static void builtByOntoBoard(File edit, Map<String, String> commandLine,
            String question) {
        OdkEngine.Outcome built = OdkEngine.run(edit, "test", commandLine, null);
        assertTrue(built.isOk(), said(built));
        assertTrue(asked(built.getTranscript(), question), "OntoBoard's build asks the "
                + "question:\n" + said(built));
    }

    /**
     * Release... by OntoBoard itself, its new files committed first, recorded by git.
     *
     * @return what the release said
     */
    private static String releasedByOntoBoard(File edit, String version,
            boolean refreshImports) {
        OdkRelease.Outcome outcome = OdkRelease.run(new OdkRelease.Request(edit, version, null,
                refreshImports, true, false, true), OdkRelease.byOntoBoardItself(edit, () -> false),
                ProcessRunner.real(), staging -> true);
        String said = outcome.getFailure() + "\n" + outcome.getWarnings() + "\n"
                + String.join("\n", outcome.getTranscript());
        assertTrue(outcome.isOk(), said);
        assertTrue(outcome.isCommitted(), said);
        assertEquals("v" + version, outcome.getTag());
        return said;
    }

    /** A question's test as Write a test from a question... saves it in an ODK project. */
    private static String question(File root, QuestionTests.Template template, String question,
            String cls, String property) throws IOException {
        String sparql = QuestionTests.query(template, question, IRI.create(cls),
                property == null ? null : IRI.create(property), null, 0);
        File file = new File(root, "src/sparql/" + QuestionTests.fileNameFor(question));
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), sparql.getBytes(StandardCharsets.UTF_8));
        OdkQuestions.ensure(root);
        return file.getName();
    }

    /** Axioms added to the edit file as Protégé saves it: before the document's last bracket. */
    private static void add(File edit, String axioms) throws IOException {
        String before = read(edit);
        int end = before.lastIndexOf(')');
        Files.write(edit.toPath(), (before.substring(0, end) + axioms + before.substring(end))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String klass(String iri, String label) {
        return "Declaration(Class(<" + iri + ">))\n"
                + "AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <" + iri
                + "> \"" + label + "\"@en)\n";
    }

    private static String someOf(String iri, String property, String filler) {
        return "SubClassOf(<" + iri + "> ObjectSomeValuesFrom(<" + property + "> <" + filler
                + ">))\n";
    }

    // ------------------------------------------------------------------ ODK's side

    /** A command in ODK's container, in src/ontology, as {@code sh run.sh ...} runs it. */
    private static List<String> odk(File root, String... command) throws IOException {
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(null,
                MakeRun.containerCommandFor(runtime, new File(root, "src/ontology"), image, host,
                        Arrays.asList(command)), MakeRun.TIMEOUT_MINUTES, null);
        assertTrue(outcome.isSuccess(), String.join(" ", command) + " in ODK's container, exit "
                + outcome.getExitCode() + ":\n" + String.join("\n", outcome.getOutput()));
        return outcome.getOutput();
    }

    /**
     * ODK's update, twice, as ODK's Makefile says to run it - {@code sh run.sh odk.py update},
     * not the deprecated {@code make update_repo}, which points ROBOT at tmp/plugins that a clean
     * or a fresh clone leaves empty - and what it wrote committed: the first changes no more than
     * it changes in a project ODK's own seed made, the second nothing.
     */
    private static void updatedByOdk(File root, String edit) throws Exception {
        File before = new File(root.getParentFile(), "edit-file-before-odks-update.owl");
        Files.write(before.toPath(), String.join("\n", git(root, "show", "HEAD:" + edit))
                .getBytes(StandardCharsets.UTF_8));
        odk(root, "odk.py", "update");
        Set<String> changed = changed(root);
        Set<String> mayChange = new TreeSet<String>(ODKS_FIRST_UPDATE);
        mayChange.add(edit);
        assertTrue(mayChange.containsAll(changed), "ODK's update changed " + changed
                + "; in a project its own seed made it changes no more than " + mayChange
                + ":\n" + String.join("\n", git(root, "diff")));
        RunComparisons.assertSameOntology(edit + ", as ODK's update writes it again through ROBOT",
                before, new File(root, edit));
        if (!changed.isEmpty()) {
            git(root, "commit", "-q", "-a", "-m", "Update with ODK");
        }
        odk(root, "odk.py", "update");
        assertEquals(Collections.<String>emptySet(), changed(root),
                "ODK's second update changes nothing");
    }

    /**
     * ODK's release as its instructions have it: make clean, make prepare_release, then the
     * release files - which ODK's .gitignore keeps out - added by name, committed and tagged. The
     * clean first, as ODK's maintainers say a release should: without it the products the test
     * before built, stamped with ODK's default version (the day), are up to date for make and go
     * out as they are - the hand-over of the rehearsal's project met that.
     */
    private static void releasedByOdk(File root, String version, String... more)
            throws IOException {
        odk(root, "make", "clean");
        List<String> line = new ArrayList<String>(Arrays.asList("make", "prepare_release",
                "VERSION=" + version));
        line.addAll(Arrays.asList(more));
        odk(root, line.toArray(new String[0]));
        String released = read(new File(root, root.getName() + ".owl"));
        String iri = OdkRelease.versionIriIn(released.substring(0, Math.min(released.length(),
                20000)));
        assertTrue(iri != null && iri.contains("/releases/" + version + "/"),
                "ODK's release " + version + " says which it is: " + iri);
        git(root, "add", "-A");
        for (String asset : String.join(" ", odk(root, "make", "-s", "show_release_assets"))
                .trim().split("\\s+")) {
            if (asset.startsWith("../../") && new File(root, asset.substring(6)).isFile()) {
                git(root, "add", "-f", asset.substring(6));
            }
        }
        git(root, "commit", "-q", "-m", "Release " + version);
        git(root, "tag", "v" + version);
    }

    // ------------------------------------------------------------------ both

    /** Whether a run asked the question and it passed, as ROBOT's verify says it. */
    private static boolean asked(List<String> transcript, String question) {
        for (String line : transcript) {
            if (line.contains("PASS Rule") && line.contains(question)) {
                return true;
            }
        }
        return false;
    }

    /** What git sees changed or new in the working tree, by path. */
    private static Set<String> changed(File root) throws IOException {
        Set<String> paths = new TreeSet<String>();
        for (String entry : git(root, "status", "--porcelain")) {
            if (entry.length() > 3) {
                paths.add(entry.substring(3).trim());
            }
        }
        return paths;
    }

    private static void identify(File root) throws IOException {
        git(root, "config", "user.name", "Test");
        git(root, "config", "user.email", "test@example.org");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "tag.gpgsign", "false");
    }

    private static List<String> git(File root, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Collections.singletonList("git"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 2, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
        return outcome.getOutput();
    }

    private static String said(OdkEngine.Outcome outcome) {
        return outcome.getFailure() + "\n" + String.join("\n", outcome.getTranscript());
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
