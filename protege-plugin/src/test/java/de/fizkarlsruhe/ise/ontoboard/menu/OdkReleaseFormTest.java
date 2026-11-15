package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRun;
import de.fizkarlsruhe.ise.ontoboard.odk.VersionIriShape;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Release..., for an ODK project: what the dialog proposes, what it refuses, what it shows
 * before git records anything, and what the result says.
 *
 * <p>The dialog itself is Swing; everything it decides is here.
 */
class OdkReleaseFormTest {

    private static final String BASE = "https://example.org/eggboil/eggboil";

    /** nfdicore's release script, shortened: its own base, its own version IRI. */
    private static final String SCRIPT = "\n\nVERSION=3.0.5\nPRIOR_VERSION=3.0.4\n"
            + "ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology \n"
            + "ANNOTATE_ONTOLOGY_VERSION=\"annotate -V $ONTBASE/$VERSION/\\$@ --annotation "
            + "owl:versionInfo $VERSION\"\n\n\nsh run.sh make clean\n\n"
            + "sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE ANNOTATE_ONTOLOGY_VERSION="
            + "\"$ANNOTATE_ONTOLOGY_VERSION\" prepare_release\n\n"
            + "sh run.sh make VERSION=$VERSION PRIOR_VERSION=$PRIOR_VERSION "
            + "update-ontology-annotations\n";

    @TempDir
    File temp;

    private File root;
    private File ontology;
    private File edit;
    private List<String> tags = new ArrayList<String>();
    /** What git says of the working tree: clean, on main. */
    private List<String> status = Arrays.asList("## main");

    /** What the script held when ODK was asked to run it. */
    private String scriptAsRun;
    private boolean buildRewritesTheEditFile;
    private boolean buildFails;

    private static void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String owl(String versionIri) {
        return "Prefix(:=<https://example.org/eggboil/>)\n"
                + "Ontology(<https://example.org/eggboil/eggboil.owl>\n"
                + (versionIri == null ? "" : "<" + versionIri + ">\n")
                + "Declaration(Class(:Egg))\n)\n";
    }

    @BeforeEach
    void aProjectOntoBoardMade() throws IOException {
        root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        assertTrue(new File(root, ".git").mkdir());
        ontology = new File(root, "src/ontology");
        edit = new File(ontology, "eggboil-edit.owl");
    }

    private ProcessRunner.Runner git() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                if (command.contains("--list")) {
                    return new ProcessRunner.Outcome(0, tags, false);
                }
                if (command.contains("status")) {
                    return new ProcessRunner.Outcome(0, status, false);
                }
                return new ProcessRunner.Outcome(0, Arrays.asList("git version 2.47.0"), false);
            }
        };
    }

    private OdkRelease.Odk odk() {
        return new OdkRelease.Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> inContainer, boolean passThroughRunSh)
                    throws IOException {
                if (inContainer.contains("show_release_assets")) {
                    return new ProcessRunner.Outcome(0,
                            Arrays.asList("../../eggboil.owl ../../eggboil-base.owl"), false);
                }
                if (buildFails && !inContainer.contains("clean")) {
                    return new ProcessRunner.Outcome(2, Arrays.asList(
                            "make: *** [Makefile:124: reason_test] Error 1"), false);
                }
                File script = new File(ontology, "release.sh");
                scriptAsRun = script.isFile() ? read(script) : null;
                write(new File(root, "eggboil.owl"), owl(null));
                write(new File(root, "eggboil-base.owl"), owl(null));
                if (buildRewritesTheEditFile) {
                    write(edit, read(edit) + "\n# rewritten by the release\n");
                }
                return new ProcessRunner.Outcome(0, Arrays.asList("Release files are now in "
                        + "../.."), false);
            }

            @Override
            public String shown(List<String> inContainer, boolean passThroughRunSh) {
                return "docker run --rm ... " + String.join(" ", inContainer);
            }
        };
    }

    private OdkReleaseForm.Start start() {
        return OdkReleaseForm.start(edit, null, "2026-11-12", git());
    }

    private static Parameter named(List<Parameter> parameters, String key) {
        for (Parameter parameter : parameters) {
            if (parameter.getKey().equals(key)) {
                return parameter;
            }
        }
        return null;
    }

    private static List<String> keys(List<Parameter> parameters) {
        List<String> keys = new ArrayList<String>();
        for (Parameter parameter : parameters) {
            keys.add(parameter.getKey());
        }
        return keys;
    }

    private Map<String, String> chosen(OdkReleaseForm.Start start, String... keysAndValues) {
        Map<String, String> chosen = new LinkedHashMap<String, String>(
                OdkReleaseForm.defaults(start));
        for (int i = 0; i < keysAndValues.length; i += 2) {
            chosen.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return chosen;
    }

    // ------------------------------------------------------------------ the version proposed

    /** The project's own release page says a first release is 1.0.0. */
    @Test
    void aFirstReleaseOfAProjectOntoBoardMadeIsProposedAs100() {
        assertEquals("1.0.0", OdkReleaseForm.defaults(start()).get(OdkReleaseForm.VERSION));
    }

    /** ODK's own default is the day's date: {@code VERSION = $(TODAY)}. */
    @Test
    void aFirstReleaseOfAnotherOdkProjectIsProposedAsTheDate() throws IOException {
        write(new File(ontology, "eggboil-odk.yaml"), "id: eggboil\ntitle: Theirs\n");

        assertEquals("2026-11-12", OdkReleaseForm.defaults(start()).get(OdkReleaseForm.VERSION));
    }

    /** A release may be a folder, a tag or both; the next number follows the newest of them. */
    @Test
    void theNextReleaseFollowsTheNewestOfTheFoldersAndTheTags() throws IOException {
        write(new File(root, "releases/1.0.0/eggboil.owl"), owl(null));
        tags = Arrays.asList("v1.2.0", "v1.0.0");

        assertEquals("1.2.1", OdkReleaseForm.defaults(start()).get(OdkReleaseForm.VERSION));
    }

    /** nfdicore writes the released version back into its edit file; a seed declares none. */
    @Test
    void whatTheEditFileDeclaresCountsWhenItIsNewerThanTheReleases() {
        tags = Arrays.asList("v1.2.0");

        assertEquals("2.0.1", OdkReleaseForm.defaults(OdkReleaseForm.start(edit,
                OntologyVersion.of("2.0.0"), "2026-11-12", git())).get(OdkReleaseForm.VERSION));
        assertEquals("1.2.1", OdkReleaseForm.defaults(OdkReleaseForm.start(edit,
                OntologyVersion.of("0.1.0"), "2026-11-12", git())).get(OdkReleaseForm.VERSION));
    }

    // ------------------------------------------------------------------ the version IRI

    @Test
    void aFirstReleaseIsOfferedOdksShapeAndTheOthers() {
        OdkReleaseForm.Start start = start();

        Parameter shape = named(OdkReleaseForm.parameters(start, OdkReleaseForm.defaults(start)),
                OdkReleaseForm.SHAPE);

        assertEquals(Arrays.asList(
                BASE + "/releases/<version>/eggboil.owl  (ODK's)",
                BASE + "/<version>/eggboil.owl",
                BASE + "/<version>",
                OdkReleaseForm.OWN_PATTERN), shape.getChoices());
        assertEquals(BASE + "/releases/<version>/eggboil.owl  (ODK's)", shape.getDefaultValue());
        assertTrue(shape.getHelp().contains("first release"), shape.getHelp());
    }

    /**
     * A version IRI cannot be changed once somebody has imported it, so the shape offered is
     * the one the last release has, and the dialog says what that release's IRI is.
     */
    @Test
    void theShapeOfferedIsTheLastReleases() throws IOException {
        write(new File(root, "releases/0.9.0/eggboil.owl"), owl(BASE + "/0.9.0/eggboil.owl"));
        OdkReleaseForm.Start start = start();

        Parameter shape = named(OdkReleaseForm.parameters(start, OdkReleaseForm.defaults(start)),
                OdkReleaseForm.SHAPE);

        assertEquals(BASE + "/<version>/eggboil.owl", shape.getDefaultValue());
        assertTrue(shape.getHelp().contains("0.9.0"), shape.getHelp());
        assertTrue(shape.getHelp().contains(BASE + "/0.9.0/eggboil.owl"), shape.getHelp());
    }

    @Test
    void eachChoiceIsItsShape() {
        OdkReleaseForm.Start start = start();

        assertEquals(VersionIriShape.Kind.ODK, OdkReleaseForm.request(start,
                chosen(start)).shape().kind());
        assertEquals(VersionIriShape.Kind.VERSION_AND_FILE, OdkReleaseForm.request(start,
                chosen(start, OdkReleaseForm.SHAPE, BASE + "/<version>/eggboil.owl")).shape()
                .kind());
        assertEquals(VersionIriShape.Kind.VERSION_ONLY, OdkReleaseForm.request(start,
                chosen(start, OdkReleaseForm.SHAPE, BASE + "/<version>")).shape().kind());
        VersionIriShape own = OdkReleaseForm.request(start, chosen(start, OdkReleaseForm.SHAPE,
                OdkReleaseForm.OWN_PATTERN, OdkReleaseForm.PATTERN,
                " {ontbase}/v{version}/{file} ")).shape();
        assertEquals(VersionIriShape.Kind.CUSTOM, own.kind());
        assertEquals("{ontbase}/v{version}/{file}", own.pattern());
    }

    /** A pattern typed while another choice is selected is not what is released. */
    @Test
    void thePatternCountsOnlyWhenItIsTheChoice() {
        OdkReleaseForm.Start start = start();

        OdkRelease.Request request = OdkReleaseForm.request(start, chosen(start,
                OdkReleaseForm.PATTERN, "{ontbase}/v{version}/{file}"));

        assertEquals(VersionIriShape.Kind.ODK, request.shape().kind());
        assertEquals(Collections.<String>emptyList(), OdkReleaseForm.problems(start,
                chosen(start, OdkReleaseForm.PATTERN, "not a pattern at all")));
    }

    /** Without a base the Makefile says plainly, only ODK's own shape can be written. */
    @Test
    void withoutABaseOnlyOdksShapeIsOffered() throws IOException {
        File makefile = new File(ontology, "Makefile");
        write(makefile, read(makefile).replace("ONTBASE=                    " + BASE,
                "ONTBASE=                    $(shell ./base.sh)"));
        OdkReleaseForm.Start start = start();

        List<Parameter> parameters = OdkReleaseForm.parameters(start,
                OdkReleaseForm.defaults(start));

        assertEquals(1, named(parameters, OdkReleaseForm.SHAPE).getChoices().size());
        assertNull(named(parameters, OdkReleaseForm.PATTERN));
        assertEquals(VersionIriShape.Kind.ODK,
                OdkReleaseForm.request(start, chosen(start)).shape().kind());
    }

    // ------------------------------------------------------------------ what the dialog refuses

    @Test
    void whatCannotBeReleasedIsSaidInTheDialog() throws IOException {
        write(new File(root, "releases/1.0.0/eggboil.owl"), owl(null));
        tags = Arrays.asList("v1.1.0");
        OdkReleaseForm.Start start = start();

        assertEquals(Collections.<String>emptyList(),
                OdkReleaseForm.problems(start, chosen(start)), "the proposal itself is fine");
        assertTrue(OdkReleaseForm.problems(start, chosen(start, OdkReleaseForm.VERSION,
                "next week")).get(0).contains("'next week' is neither a date"));
        assertTrue(OdkReleaseForm.problems(start, chosen(start, OdkReleaseForm.VERSION,
                "1.1.0")).get(0).contains("v1.1.0 is there already"));
        assertTrue(OdkReleaseForm.problems(start, chosen(start, OdkReleaseForm.VERSION,
                "1.0.0")).get(0).contains("releases/1.0.0 is there already"));
        String below = OdkReleaseForm.problems(start, chosen(start, OdkReleaseForm.VERSION,
                "1.0.5")).get(0);
        assertTrue(below.contains("1.1.0") && below.contains("1.1.1") && below.contains("1.2.0"),
                "what is out, and what to type instead: " + below);
        assertTrue(OdkReleaseForm.problems(start, chosen(start, OdkReleaseForm.SHAPE,
                OdkReleaseForm.OWN_PATTERN, OdkReleaseForm.PATTERN, "{ontbase}/latest"))
                .get(0).contains("{version}"));
    }

    /** A leading v, as in the tag, is not part of the version. */
    @Test
    void aLeadingVIsNotPartOfTheVersion() {
        OdkReleaseForm.Start start = start();

        assertEquals("1.0.0", OdkReleaseForm.request(start,
                chosen(start, OdkReleaseForm.VERSION, " v1.0.0 ")).version());
    }

    // ------------------------------------------------------------------ a project's own script

    @Test
    void aProjectWithItsOwnScriptIsAskedForTheVersionsOnly() throws IOException {
        write(new File(ontology, "release.sh"), SCRIPT);
        tags = Arrays.asList("v3.0.5", "v3.0.4");
        OdkReleaseForm.Start start = start();

        Map<String, String> defaults = OdkReleaseForm.defaults(start);
        List<Parameter> parameters = OdkReleaseForm.parameters(start, defaults);

        assertEquals(Arrays.asList(OdkReleaseForm.VERSION, OdkReleaseForm.PRIOR,
                OdkReleaseForm.REFUSE), keys(parameters));
        assertEquals("3.0.6", defaults.get(OdkReleaseForm.VERSION));
        assertEquals("3.0.5", defaults.get(OdkReleaseForm.PRIOR));
        String explanation = OdkReleaseForm.explanation(start);
        assertTrue(explanation.contains("release.sh"), explanation);
        assertTrue(explanation.contains("annotate -V $ONTBASE/$VERSION/\\$@ --annotation "
                + "owl:versionInfo $VERSION"), "the version IRI is the script's, shown as it "
                + "writes it: " + explanation);
    }

    /** A maintainer who has set the script to the next version already is not second-guessed. */
    @Test
    void aVersionTheScriptAlreadyNamesAndNobodyReleasedIsProposed() throws IOException {
        write(new File(ontology, "release.sh"), SCRIPT.replace("VERSION=3.0.5\n",
                "VERSION=3.1.0\n").replace("PRIOR_VERSION=3.0.4", "PRIOR_VERSION=3.0.5"));
        tags = Arrays.asList("v3.0.5", "v3.0.4");

        assertEquals("3.1.0", OdkReleaseForm.defaults(start()).get(OdkReleaseForm.VERSION));
    }

    /** What OntoBoard cannot run is said when Release... is chosen, not after the form. */
    @Test
    void aScriptThatCannotBeRunIsSaidBeforeTheForm() throws IOException {
        assertNull(start().refusal());

        write(new File(ontology, "release.sh"), SCRIPT.replace("\n", "\r\n"));
        assertTrue(start().refusal().contains("Windows line endings"), start().refusal());

        write(new File(ontology, "release.sh"), "VERSION=$1\nsh run.sh make prepare_release\n");
        assertTrue(start().refusal().contains("VERSION="), start().refusal());
        assertFalse(start().refusal().contains("null"), start().refusal());
    }

    /** The two lines of the script, and not another character of it. */
    @Test
    void theScriptsLinesAreSetBeforeItRuns() throws IOException {
        write(new File(ontology, "release.sh"), SCRIPT);
        tags = Arrays.asList("v3.0.5");
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, null);

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(SCRIPT.replace("VERSION=3.0.5\n", "VERSION=3.0.6\n")
                .replace("PRIOR_VERSION=3.0.4", "PRIOR_VERSION=3.0.5"), scriptAsRun);
        assertTrue(result.getLog().toString().contains("release.sh: VERSION=3.0.6, "
                + "PRIOR_VERSION=3.0.5"), result.getLog().toString());
    }

    @Test
    void aPreviousVersionThatIsNotOneIsRefused() throws IOException {
        write(new File(ontology, "release.sh"), SCRIPT);
        OdkReleaseForm.Start start = start();

        List<String> problems = OdkReleaseForm.problems(start,
                chosen(start, OdkReleaseForm.PRIOR, "the last one"));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("'the last one'"), problems.get(0));
    }

    // ------------------------------------------------------------------ work that is not committed

    /**
     * A release's commit takes changed files with it and leaves new ones out, as decided: a
     * stray file stays where it is. But a new import module that the changed edit file
     * imports, left out, makes a tag whose project does not build - seen in a live run, where
     * the pattern library had just written one. So new files are said before the release is
     * built, with the way to commit them, instead of only in the question after it.
     */
    @Test
    void newFilesAreSaidBeforeTheReleaseIsBuilt() {
        status = Arrays.asList("## main", " M src/ontology/eggboil-edit.owl",
                " M src/ontology/catalog-v001.xml", "?? src/ontology/imports/",
                "?? \"my notes.txt\"");

        String notice = OdkReleaseForm.newFilesNotice(start());

        assertTrue(notice.contains("2 new files"), notice);
        assertTrue(notice.contains("src/ontology/imports/") && notice.contains("my notes.txt"),
                notice);
        assertTrue(notice.contains("OntoBoard > Share > Git..."), notice);
        assertTrue(notice.contains("Commit everything, with a message"), notice);
        assertFalse(notice.contains("eggboil-edit.owl"), "a changed file goes along and is not "
                + "what this is about: " + notice);
    }

    @Test
    void oneNewFileIsSaidInTheSingular() {
        status = Arrays.asList("## main", "?? src/sparql/every_egg_has_a_timer-violation.sparql");

        String notice = OdkReleaseForm.newFilesNotice(start());

        assertTrue(notice.contains("1 new file in this project: "
                + "src/sparql/every_egg_has_a_timer-violation.sparql."), notice);
        assertTrue(notice.contains("leaves it out"), notice);
    }

    /** A dozen new files are not a dozen lines: the first five are named. */
    @Test
    void manyNewFilesAreCountedAndTheFirstNamed() {
        List<String> lines = new ArrayList<String>(Arrays.asList("## main"));
        for (int i = 1; i <= 12; i++) {
            lines.add("?? new-" + i + ".txt");
        }
        status = lines;

        String notice = OdkReleaseForm.newFilesNotice(start());

        assertTrue(notice.contains("12 new files"), notice);
        assertTrue(notice.contains("new-5.txt and 7 more"), notice);
        assertFalse(notice.contains("new-6.txt"), notice);
    }

    @Test
    void withNothingNewThereIsNothingToSay() {
        status = Arrays.asList("## main", " M src/ontology/eggboil-edit.owl");

        assertNull(OdkReleaseForm.newFilesNotice(start()));
    }

    /** No repository, or no git: nothing is committed there, so nothing can be left out. */
    @Test
    void withoutGitThereIsNothingToSay() {
        OdkReleaseForm.Start start = OdkReleaseForm.start(edit, null, "2026-11-12",
                new ProcessRunner.Runner() {
                    @Override
                    public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                            long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                        throw new IOException("Cannot run program \"git\"");
                    }
                });

        assertNull(OdkReleaseForm.newFilesNotice(start));
        assertEquals("1.0.0", OdkReleaseForm.defaults(start).get(OdkReleaseForm.VERSION));
    }

    // ------------------------------------------------------------------ before git records it

    @Test
    void whatIsAboutToBeCommittedIsShown() throws IOException {
        write(new File(root, "eggboil.owl"), owl(null));
        ReleaseRun.Staging staging = ReleaseRun.staging(root, "eggboil", "1.0.0",
                Collections.singletonList(new File(root, "eggboil.owl")),
                Arrays.asList("## main...origin/main", "?? notes.txt"));

        String shown = OdkReleaseForm.confirmation(staging, root);

        assertTrue(shown.contains(root.getAbsolutePath()), shown);
        assertTrue(shown.contains("on branch main"), shown);
        assertTrue(shown.contains("git add -u\ngit add -f -- eggboil.owl\n"
                + "git commit -m \"release(1.0.0)\"\ngit tag -a v1.0.0 -m \"eggboil 1.0.0\""),
                shown);
        assertTrue(shown.contains("notes.txt"), shown);
        assertTrue(shown.contains("Nothing is pushed"), shown);
    }

    /** A commit made with no branch checked out is reachable through its tag only. */
    @Test
    void aDetachedHeadIsSaidBeforeTheCommit() throws IOException {
        write(new File(root, "eggboil.owl"), owl(null));
        ReleaseRun.Staging staging = ReleaseRun.staging(root, "eggboil", "1.0.0",
                Collections.singletonList(new File(root, "eggboil.owl")),
                Collections.singletonList("## HEAD (no branch)"));

        String shown = OdkReleaseForm.confirmation(staging, root);

        assertTrue(shown.contains("no branch is checked out"), shown);
        assertFalse(shown.contains("on branch"), shown);
    }

    // ------------------------------------------------------------------ the result

    @Test
    void theResultSaysWhatWasReleasedAndWhatIsLeftToDo() {
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, null);

        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus(),
                result.getLog().toString());
        assertTrue(result.getSummary().contains("v1.0.0"), result.getSummary());
        assertTrue(result.getSummary().contains("git push --follow-tags"), result.getSummary());
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("Version IRI: " + BASE + "/releases/1.0.0/eggboil.owl"), log);
        assertTrue(log.contains("docker run --rm ... make prepare_release VERSION=1.0.0"), log);
        assertTrue(result.getFiles().contains(new File(root, "eggboil.owl")));
        assertTrue(result.getFiles().contains(new File(root, "releases/1.0.0/eggboil.owl")));
        assertNull(scriptAsRun, "no script in this project, none written");
    }

    /**
     * Not committed: the commands to run later are the last lines of the log, below whatever
     * the release warned of, where they can be found and copied.
     */
    @Test
    void whenNotCommittedTheCommandsAreTheLastLinesOfTheLog() {
        buildRewritesTheEditFile = true;
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> false, null);

        List<String> log = result.getLog();
        assertEquals(Arrays.asList("git commit -m \"release(1.0.0)\"",
                "git tag -a v1.0.0 -m \"eggboil 1.0.0\"", "git push --follow-tags"),
                log.subList(log.size() - 3, log.size()));
        assertTrue(log.indexOf("To record the release later, in " + root.getAbsolutePath()
                + ":") > log.size() - 9, log.toString());
        assertTrue(result.getSummary().contains("Not committed"), result.getSummary());
        assertFalse(result.getSummary().contains("git add"), result.getSummary());
    }

    /** The image is not the ODK that wrote the project's Makefile: said, as Build says it. */
    @Test
    void anImageThatIsNotTheProjectsOwnIsSaid() {
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, "This project was made with ODK v1.5; v1.6.1 runs it.");

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus());
        assertTrue(result.getLog().contains("This project was made with ODK v1.5; v1.6.1 runs "
                + "it."));
    }

    /**
     * Protégé holds the ontology in memory and does not notice a file changing under it. A
     * project's script may rewrite the edit file (nfdicore's writes the version into it); the
     * next save from Protégé would put the old one back.
     */
    @Test
    void aFileRewrittenUnderProtegeIsSaid() {
        buildRewritesTheEditFile = true;
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, null);

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus());
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("eggboil-edit.owl") && log.contains("reopen"), log);
    }

    /**
     * Where whoever has the project open reads again what the release rewrote, that is
     * what is said - and nobody is told to reopen anything.
     */
    @Test
    void aFileRewrittenUnderProtegeIsReadAgainWhereItCanBe() {
        buildRewritesTheEditFile = true;
        OdkReleaseForm.Start start = start();
        final List<List<File>> asked = new ArrayList<List<File>>();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, null, written -> {
                    asked.add(written);
                    return new WhatARunWrote.Done(written,
                            java.util.Collections.<String>emptyList());
                });

        assertEquals(1, asked.size(), asked.toString());
        assertTrue(asked.get(0).stream().anyMatch(
                file -> file.getName().equals("eggboil-edit.owl")), asked.toString());
        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus());
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("Protege has read again what the run rewrote: "
                + "eggboil-edit.owl."), log);
        assertFalse(log.contains("reopen"), log);
    }

    /** What could not be read again is said with its reason, as a warning. */
    @Test
    void aFileThatCouldNotBeReadAgainIsSaid() {
        buildRewritesTheEditFile = true;
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, null, written -> new WhatARunWrote.Done(
                        java.util.Collections.<File>emptyList(), java.util.Collections
                                .singletonList("eggboil-edit.owl could not be read again: "
                                        + "it is in use.")));

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus());
        assertTrue(String.join("\n", result.getLog()).contains("eggboil-edit.owl could not "
                + "be read again: it is in use."), result.getLog().toString());
    }

    @Test
    void aReleaseThatFailsIsAFailedResultWithItsTranscript() {
        buildFails = true;
        OdkReleaseForm.Start start = start();

        OperationResult result = OdkReleaseForm.run("Release", start, chosen(start), odk(),
                git(), staging -> true, null);

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertTrue(result.getSummary().contains("prepare_release"), result.getSummary());
        assertTrue(String.join("\n", result.getLog()).contains("reason_test] Error 1"));
        assertNotNull(result.getSummary());
    }

    // ------------------------------------------------------------------ who builds it

    private static final String NO_CONTAINER = "Neither Docker nor Podman answers on this machine";

    private static de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRoute itself(String whyNotFull) {
        return de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRoute.decide(false, false,
                de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute.Use.AUTOMATIC, NO_CONTAINER, null,
                null, null, whyNotFull);
    }

    /**
     * Without a container OntoBoard builds the release itself. The form says so, and why, so
     * that nobody thinks ODK's container ran; everything else of it is as ever.
     */
    @Test
    void builtByOntoBoardItselfTheFormSaysSoAndWhy() {
        OdkReleaseForm.Start start = start().withRoute(itself(null));

        String explanation = OdkReleaseForm.explanation(start);

        assertTrue(explanation.startsWith(NO_CONTAINER + ", so OntoBoard builds the release "
                + "itself"), explanation);
        assertTrue(explanation.contains("prepare_release"), explanation);
        assertFalse(explanation.contains("in ODK's container"), explanation);
        assertTrue(explanation.contains("Nothing is pushed."), explanation);
        assertTrue(keys(OdkReleaseForm.parameters(start, OdkReleaseForm.defaults(start)))
                .contains(OdkReleaseForm.REFRESH), "nothing has to be fetched for this project, "
                        + "so the full release is on offer");
        assertTrue(OdkReleaseForm.request(start, chosen(start)).refreshesImports());
    }

    /**
     * Where OntoBoard itself cannot run the full release for a project - the one that
     * fetches the imported ontologies again first - the form does not offer it, says that
     * the import modules are used as they are and why, in the engine's own first sentence,
     * and the release is the fast one whatever was left in the form.
     */
    @Test
    void whereTheFullReleaseCannotRunHereTheFormDoesNotOfferItAndSaysWhy() {
        OdkReleaseForm.Start start = start().withRoute(
                itself("'prepare_release' needs something OntoBoard's own engine does not "
                        + "do: the program 'wget' (Makefile:498). Nothing was run. It can "
                        + "be run some other way."));

        assertFalse(keys(OdkReleaseForm.parameters(start, OdkReleaseForm.defaults(start)))
                .contains(OdkReleaseForm.REFRESH));
        assertFalse(OdkReleaseForm.defaults(start).containsKey(OdkReleaseForm.REFRESH));
        assertFalse(OdkReleaseForm.request(start,
                chosen(start, OdkReleaseForm.REFRESH, "true")).refreshesImports());
        String explanation = OdkReleaseForm.explanation(start);
        assertTrue(explanation.contains("import modules are used as they are"), explanation);
        assertTrue(explanation.contains("the program 'wget' (Makefile:498)."), explanation);
        assertFalse(explanation.contains("Nothing was run"), explanation);
        assertFalse(explanation.contains("is not something OntoBoard itself does"),
                "fetching is something it does: " + explanation);
    }

    /** With ODK's own tools installed on the machine, the form names that. */
    @Test
    void inANativeEnvironmentTheFormSaysSo() {
        OdkReleaseForm.Start start = start().withRoute(
                de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRoute.decide(true, false,
                        de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute.Use.AUTOMATIC, NO_CONTAINER,
                        null, null, null, null));

        String explanation = OdkReleaseForm.explanation(start);

        assertTrue(explanation.contains("in the native ODK environment"), explanation);
        assertFalse(explanation.contains("in ODK's container"), explanation);
        assertTrue(keys(OdkReleaseForm.parameters(start, OdkReleaseForm.defaults(start)))
                .contains(OdkReleaseForm.REFRESH));
    }

    /** Built in the container - the route when nothing else is said - the form is as it was. */
    @Test
    void inTheContainerTheFormIsAsItWas() {
        OdkReleaseForm.Start start = start();

        assertTrue(OdkReleaseForm.explanation(start).contains("in ODK's container"));
        assertEquals(OdkReleaseForm.explanation(start), OdkReleaseForm.explanation(
                start.withRoute(de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRoute.decide(false,
                        true, de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute.Use.AUTOMATIC, null,
                        null, null, null, null))));
    }
}
