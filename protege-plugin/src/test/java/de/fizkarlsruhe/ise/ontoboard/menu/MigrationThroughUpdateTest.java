package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkMigration;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkQuestions;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <em>Update project files...</em> on a project OntoBoard 1.135.x made: first it shows what
 * the project becomes, and writes nothing; asked to write, it makes the project an ODK
 * project - the conversion of what is the project's own, then ODK's update - and asks about
 * an import the configuration cannot declare before anything at all is written.
 *
 * <p>On a copy of the project 1.135.2 wrote
 * ({@code src/test/resources/odk/migration/eggboil-1.135.2}).
 */
class MigrationThroughUpdateTest {

    private static final String OPERATION = "Update project files";
    private static final String NONE = "Neither Docker nor Podman answers on this machine";
    private static final String CONFIG = "src/ontology/eggboil-odk.yaml";
    private static final String EDIT = "src/ontology/eggboil-edit.owl";
    private static final String UPSTREAM = "https://example.org/mini.owl";

    @TempDir
    File temp;

    private File root;

    /** What the container was asked to run. */
    private final List<List<String>> ran = new ArrayList<List<String>>();

    @BeforeEach
    void aCopyOfTheProject1352Made() throws IOException {
        root = new File(temp, "eggboil");
        File module = new File(System.getProperty("basedir", System.getProperty("user.dir")));
        final Path source = new File(module,
                "src/test/resources/odk/migration/eggboil-1.135.2").toPath();
        final Path target = root.toPath();
        try (Stream<Path> all = Files.walk(source)) {
            for (Path one : (Iterable<Path>) all::iterator) {
                Path there = target.resolve(source.relativize(one).toString());
                if (Files.isDirectory(one)) {
                    Files.createDirectories(there);
                } else {
                    Files.createDirectories(there.getParent());
                    Files.copy(one, there);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private OdkUpdateForm.Way itself() {
        return OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, null, null, null, NONE, null);
    }

    private ProcessRunner.Runner noContainer() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                throw new IOException("no container was to be started: " + command);
            }
        };
    }

    private File in(String path) {
        return new File(root, path.replace('/', File.separatorChar));
    }

    private String text(String path) throws IOException {
        return new String(Files.readAllBytes(in(path).toPath()), StandardCharsets.UTF_8);
    }

    private void replace(String path, String old, String now) throws IOException {
        String before = text(path);
        assertTrue(before.contains(old), path + " holds " + old);
        Files.write(in(path).toPath(), before.replace(old, now).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, byte[]> tree() throws IOException {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        final Path base = root.toPath();
        try (Stream<Path> all = Files.walk(base)) {
            for (Path one : (Iterable<Path>) all::iterator) {
                if (Files.isRegularFile(one)) {
                    files.put(base.relativize(one).toString().replace('\\', '/'),
                            Files.readAllBytes(one));
                }
            }
        }
        return files;
    }

    private Set<String> differing(Map<String, byte[]> before) throws IOException {
        Map<String, byte[]> after = tree();
        Set<String> paths = new TreeSet<String>();
        Set<String> all = new TreeSet<String>(before.keySet());
        all.addAll(after.keySet());
        for (String path : all) {
            if (!before.containsKey(path) || !after.containsKey(path)
                    || !Arrays.equals(before.get(path), after.get(path))) {
                paths.add(path);
            }
        }
        return paths;
    }

    private static Map<String, String> states(OperationResult result) {
        Map<String, String> states = new LinkedHashMap<String, String>();
        for (List<String> row : result.getRows()) {
            states.put(row.get(0), row.get(1));
        }
        return states;
    }

    private Set<String> written(OperationResult result) {
        Set<String> paths = new TreeSet<String>();
        for (File file : result.getFiles()) {
            paths.add(root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath())
                    .toString().replace('\\', '/'));
        }
        return paths;
    }

    /** The edit file also imports the ontology the mini module is cut from, whole. */
    private void theEditFileAlsoImportsTheWholeUpstream() throws IOException {
        replace(EDIT, "        <owl:imports rdf:resource=\"https://example.org/eggboil/eggboil/"
                + "imports/mini_import.owl\"/>\n",
                "        <owl:imports rdf:resource=\"https://example.org/eggboil/eggboil/"
                        + "imports/mini_import.owl\"/>\n"
                        + "        <owl:imports rdf:resource=\"" + UPSTREAM + "\"/>\n");
        replace("src/ontology/catalog-v001.xml", "</catalog>",
                "    <uri name=\"" + UPSTREAM + "\" uri=\"upstream/mini.owl\"/>\n</catalog>");
    }

    /** What a session answers, and what it was asked. */
    private static final class Open implements OdkUpdateForm.Session {
        private boolean stopped;
        private int readAgain;
        private int asked;
        /** What is answered about the imports; null to answer nothing. */
        private List<ImportGuard.Decision> decisions;
        private final List<String> configWhenAsked = new ArrayList<String>();
        private File config;

        @Override
        public boolean stopped() {
            return stopped;
        }

        @Override
        public boolean isOpen(File editFile) {
            return true;
        }

        @Override
        public List<ImportGuard.Decision> decide(List<ImportGuard.Finding> findings) {
            asked++;
            try {
                configWhenAsked.add(new String(Files.readAllBytes(config.toPath()),
                        StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                throw new AssertionError(unreadable);
            }
            return decisions;
        }

        @Override
        public String readAgain(File editFile, Map<String, File> imports) {
            readAgain++;
            return null;
        }

        @Override
        public void writtenAsItWas(File editFile) {
            // Nothing to note here.
        }
    }

    private Open open() {
        Open open = new Open();
        open.config = in(CONFIG);
        return open;
    }

    // ------------------------------------------------------------------ looking

    @Test
    void firstItShowsWhatTheProjectBecomesAndWritesNothing() throws IOException {
        Map<String, byte[]> before = tree();

        OperationResult result = OdkUpdateForm.run(OPERATION, root, false, itself(),
                noContainer(), open());

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(new TreeSet<String>(), differing(before), "looking writes nothing");
        assertEquals(new ArrayList<File>(), result.getFiles());
        assertEquals(new ArrayList<List<String>>(), ran);

        Map<String, String> states = states(result);
        assertTrue(states.get(CONFIG).startsWith("changes"), states.get(CONFIG));
        assertTrue(states.get("src/ontology/eggboil.Makefile").startsWith("changes: the rule "
                + "that asks the project's questions"), states.toString());
        assertFalse(states.containsKey("src/sparql/check_labels.rq"),
                "their queries are not among what changes: " + states);
        assertEquals("changes", states.get("src/ontology/Makefile"));
        assertEquals("new", states.get("src/ontology/run.sh"));

        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("OntoBoard 1.135"), log);
        assertTrue(log.contains("becomes an ODK project"), log);
        assertTrue(log.contains("measurement: declared as kept as it is"), log);
        assertTrue(log.contains("unused_import.owl is a module nothing imports"), log);
        assertTrue(log.contains("Your 2 test queries in src/sparql (check_labels.rq, "
                + "every_dataset_has_part.rq) stay as they are and are asked of the ontology "
                + "with its imports"), log);
        assertTrue(log.contains("| id: eggboil"), "the configuration as it becomes: " + log);
        assertTrue(log.contains("|     - id: mini"), "with its indentation: " + log);
        assertTrue(log.contains("Nothing was written - 'Write the changes' was off."), log);
        assertTrue(result.getSummary().contains("would change"), result.getSummary());
        assertTrue(result.getSummary().contains("becomes an ODK project"), result.getSummary());
    }

    // ------------------------------------------------------------------ writing

    @Test
    void writingMakesItAnOdkProject() throws IOException {
        Open open = open();

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open);

        assertTrue(result.isSuccess(), result.getSummary());
        assertFalse(OdkMigration.isOntoBoards1x(root));
        assertTrue(OdkRelease.isOdkProject(in(EDIT)));
        assertEquals(0, open.asked, "every import is a module of the project: nothing to ask");
        assertEquals(1, open.readAgain, "the edit file was rewritten, and is read again");
        assertEquals(new ArrayList<List<String>>(), ran);

        Set<String> written = written(result);
        for (String path : new String[] {CONFIG, EDIT, "src/ontology/Makefile",
                "src/ontology/run.sh", "src/ontology/eggboil.Makefile",
                ".github/workflows/qc.yml"}) {
            assertTrue(written.contains(path), path + " in " + written);
        }
        assertEquals(result.getFiles().size(), new TreeSet<File>(result.getFiles()).size(),
                "each file is listed once");
        assertTrue(in("src/sparql/check_labels.rq").isFile(), "their queries stay");
        assertTrue(in("src/sparql/every_dataset_has_part.rq").isFile());
        assertTrue(OdkQuestions.isKeptIn(root));
        assertTrue(result.getSummary().startsWith(OdkUpdateForm.UPDATED), result.getSummary());
        assertTrue(result.getSummary().contains("an ODK project now"), result.getSummary());
        assertTrue(result.getSummary().contains(result.getFiles().size() + " files"),
                result.getSummary());

        // And from here on it is an ODK project like any other: nothing left to do.
        OperationResult again = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open);
        assertTrue(again.getSummary().startsWith(OdkUpdateForm.ALREADY_UP_TO_DATE),
                again.getSummary());
    }

    /**
     * What the conversion warns of - here an address ODK names otherwise in what it
     * releases - makes the result one with warnings, looking and writing: a line among
     * forty of a log that ended "Finished" is not read (mutant F05 of the round on this
     * step made it a note, and lived).
     */
    @Test
    void whatTheConversionWarnsOfMakesTheResultOneWithWarnings() throws IOException {
        replace(EDIT, "https://example.org/eggboil/eggboil", "https://example.org/kitchen/egg");
        replace("src/ontology/catalog-v001.xml", "https://example.org/eggboil/eggboil",
                "https://example.org/kitchen/egg");
        replace(CONFIG, "uribase: https://example.org/eggboil\n",
                "uribase: https://example.org/kitchen\n");

        OperationResult look = OdkUpdateForm.run(OPERATION, root, false, itself(),
                noContainer(), open());

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, look.getStatus(),
                look.getSummary());
        assertTrue(String.join("\n", look.getLog()).contains(
                "https://example.org/kitchen/eggboil.owl"), look.getLog().toString());

        OperationResult written = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open());

        assertTrue(written.getSummary().startsWith(OdkUpdateForm.UPDATED),
                written.getSummary());
        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, written.getStatus(),
                written.getSummary());
    }

    @Test
    void whoStoppedWaitingGetsNothingWritten() throws IOException {
        Map<String, byte[]> before = tree();
        Open open = open();
        open.stopped = true;

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open);

        assertEquals(new TreeSet<String>(), differing(before));
        assertEquals(new ArrayList<File>(), result.getFiles());
        assertTrue(result.getSummary().contains("abandoned"), result.getSummary());
    }

    /**
     * ODK's own update is started on the project as the conversion leaves it: the
     * configuration is ODK's by then, and the rule for the questions is in its place.
     */
    @Test
    void inAContainerOdksOwnUpdateRunsOnTheConvertedProject() throws IOException {
        final File makefile = in("src/ontology/Makefile");
        final List<String> seen = new ArrayList<String>();
        ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                seen.add(text(CONFIG).split("\n")[0]);
                seen.add(String.valueOf(OdkQuestions.isKeptIn(root)));
                Files.write(makefile.toPath(), "# ODK's own\n".getBytes(StandardCharsets.UTF_8));
                return new ProcessRunner.Outcome(0, Arrays.asList(
                        "Ontology repository update successfully completed."), false);
            }
        };

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, "docker", "an-image:v1",
                        OdkContainer.Host.unknown(), null, null), container, open());

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(1, ran.size());
        assertEquals(Arrays.asList("# ODK configuration for Test kitchen ontology, written by "
                + "OntoBoard for ODK v1.6.1.", "true"), seen);
        assertTrue(written(result).contains(CONFIG), written(result).toString());
        assertTrue(written(result).contains("src/ontology/Makefile"), written(result).toString());
    }

    /** ODK's update that fails after the conversion: said, with what makes the rest. */
    @Test
    void whenOdksUpdateFailsAfterTheConversionThatIsSaidWithWhatGoesOn() throws IOException {
        ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                return new ProcessRunner.Outcome(1, Arrays.asList("Traceback", "boom"), false);
            }
        };

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, "docker", "an-image:v1",
                        OdkContainer.Host.unknown(), null, null), container, open());

        assertFalse(result.isSuccess());
        assertTrue(result.getSummary().contains("ODK's own update stopped"), result.getSummary());
        assertTrue(result.getSummary().contains("goes on from here"), result.getSummary());
        assertTrue(written(result).contains(CONFIG), "what was converted is listed: "
                + written(result));
        assertTrue(OdkMigration.isOntoBoards1x(root), "the Makefile is still OntoBoard's");

        // The next try has nothing to convert, and makes the update.
        OperationResult next = OdkUpdateForm.run(OPERATION, root, true, itself(), noContainer(),
                open());
        assertTrue(next.isSuccess(), next.getSummary());
        assertFalse(OdkMigration.isOntoBoards1x(root));
    }

    // ------------------------------------------------------------------ other imports

    /**
     * An import that is no module of the project - the whole upstream ontology - is not in
     * the configuration the conversion writes, and ODK's update would take it out. Asked
     * about first; without an answer nothing at all is written, the conversion included.
     */
    @Test
    void withoutAnAnswerAboutAnotherImportNothingAtAllIsWritten() throws IOException {
        theEditFileAlsoImportsTheWholeUpstream();
        Map<String, byte[]> before = tree();
        Open open = open();
        open.decisions = null;

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open);

        assertEquals(1, open.asked);
        assertEquals(new TreeSet<String>(), differing(before), "the conversion included");
        assertEquals(new ArrayList<File>(), result.getFiles());
        assertTrue(result.getSummary().startsWith("Nothing was written"), result.getSummary());
        assertTrue(open.configWhenAsked.get(0).contains("calls robot directly"),
                "asked before the configuration was converted");
    }

    @Test
    void lookingNamesTheImportTheUpdateWouldTakeOut() throws IOException {
        theEditFileAlsoImportsTheWholeUpstream();

        OperationResult result = OdkUpdateForm.run(OPERATION, root, false, itself(),
                noContainer(), open());

        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("The edit file imports " + UPSTREAM), log);
        assertTrue(result.getSummary().contains("1 import the update would take out"),
                result.getSummary());
    }

    /** Kept whole: declared in the converted configuration, and the project is migrated. */
    @Test
    void anImportThatIsKeptIsDeclaredInTheConvertedConfiguration() throws IOException {
        theEditFileAlsoImportsTheWholeUpstream();
        Open open = open();
        open.decisions = Arrays.asList(ImportGuard.Decision.WHOLE);

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open);

        assertTrue(result.isSuccess(), result.getSummary());
        assertFalse(OdkMigration.isOntoBoards1x(root));
        String config = text(CONFIG);
        assertTrue(config.startsWith("# ODK configuration for Test kitchen ontology, written "
                + "by OntoBoard for ODK v1.6.1.\n"), config);
        List<ImportProducts.Product> products = ImportProducts.declaredIn(config);
        assertEquals(3, products.size(), config);
        assertEquals(result.getFiles().size(), new TreeSet<File>(result.getFiles()).size(),
                "each file is listed once, the configuration too");
        assertFalse(text(EDIT).contains("<" + UPSTREAM + ">"),
                "its module takes the import's place: " + text(EDIT));
    }

    /** Taken out, as said. */
    @Test
    void anImportThatIsTakenOutIsGoneAfterIt() throws IOException {
        theEditFileAlsoImportsTheWholeUpstream();
        Open open = open();
        open.decisions = Arrays.asList(ImportGuard.Decision.REMOVE);

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open);

        assertTrue(result.isSuccess(), result.getSummary());
        assertFalse(text(EDIT).contains(UPSTREAM), text(EDIT));
        assertEquals(2, ImportProducts.declaredIn(text(CONFIG)).size(), text(CONFIG));
    }

    // ------------------------------------------------------------------ what cannot be carried over

    @Test
    void aProjectThatCannotBeCarriedOverIsAFailedResultAndNothingIsWritten() throws IOException {
        Files.delete(in(EDIT).toPath());
        Map<String, byte[]> before = tree();

        OperationResult result = OdkUpdateForm.run(OPERATION, root, true, itself(),
                noContainer(), open());

        assertFalse(result.isSuccess());
        assertTrue(result.getSummary().contains("eggboil-edit.owl"), result.getSummary());
        assertEquals(new TreeSet<String>(), differing(before));
    }
}
