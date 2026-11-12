package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.BuildInfo;
import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The programs ODK's recipes name that are not the shell's own - robot, odk-info, dicer-cli,
 * check-rdfxml - as OntoBoard runs them without ODK's image.
 */
class OdkToolsTest {

    @TempDir
    File temp;

    /** src/ontology of a project: where make runs. */
    private File folder;
    private final List<String> said = new ArrayList<String>();

    @BeforeEach
    void aProjectFolder() throws IOException {
        folder = new File(temp, "myont/src/ontology");
        Files.createDirectories(folder.toPath());
    }

    private void write(String path, String text) throws IOException {
        write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String path, byte[] bytes) throws IOException {
        File file = new File(folder, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), bytes);
    }

    private String whyNot(String line) {
        return RecipeShell.parse(line).whyNot(OdkTools.forProject());
    }

    /** Asked first, as the engine asks; then run in the project's src/ontology. */
    private int run(String line) {
        RecipeShell.Script script = RecipeShell.parse(line);
        assertNull(script.whyNot(OdkTools.forProject()), line);
        return script.run(OdkTools.forProject(), folder, said);
    }

    private static final String ONTOLOGY = "Prefix(:=<https://example.org/myont/>)\n"
            + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
            + "Ontology(<https://example.org/myont/myont.owl>\n"
            + "Declaration(Class(:Egg))\nAnnotationAssertion(rdfs:label :Egg \"egg\")\n)\n";

    @Test
    void theToolsAreTheShellsOwnAndFourMore() {
        Map<String, RecipeShell.Tool> tools = OdkTools.forProject();

        assertTrue(tools.keySet().containsAll(RecipeShell.builtins().keySet()));
        assertTrue(tools.keySet().containsAll(Arrays.asList("robot", "odk-info", "dicer-cli",
                "check-rdfxml")));
        assertEquals(RecipeShell.builtins().size() + 4, tools.size(), tools.keySet().toString());
    }

    // ------------------------------------------------------------------ robot

    @Test
    void robotRunsWithTheProjectsPathsAndSaysThemAsWritten() throws IOException {
        write("myont-edit.ofn", ONTOLOGY);
        write("catalog-v001.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<catalog prefer=\"public\" xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "\n</catalog>\n");
        write("tmp/.gitkeep", "");

        assertEquals(0, run("robot --catalog catalog-v001.xml convert --input myont-edit.ofn "
                + "--format ofn --output tmp/myont-preprocess.owl"), said.toString());

        assertTrue(new File(folder, "tmp/myont-preprocess.owl").isFile());
        assertFalse(new File("tmp/myont-preprocess.owl").exists(), "not in the JVM's folder");
    }

    /**
     * A report's profile may name a check of the project's own by a path from the folder make
     * runs in ({@code file:../sparql/...}). ROBOT reads such a path against its own folder;
     * here that is the recipe's.
     */
    @Test
    void aCheckTheProfileNamesFromTheRecipesFolderIsRun() throws IOException {
        write("myont-edit.ofn", ONTOLOGY);
        write("../sparql/every-label.rq", "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "SELECT ?entity ?property ?value WHERE {\n"
                + "  VALUES ?property { rdfs:label }\n  ?entity ?property ?value .\n}\n");
        write("profile.txt", "INFO\tfile:../sparql/every-label.rq\n");
        write("reports/.gitkeep", "");

        assertEquals(0, run("robot report -i myont-edit.ofn --profile profile.txt "
                + "--fail-on ERROR -o reports/report.tsv"), said.toString());

        String report = new String(Files.readAllBytes(
                new File(folder, "reports/report.tsv").toPath()), StandardCharsets.UTF_8);
        assertTrue(report.contains("INFO\tevery-label\t"), report);
    }

    @Test
    void robotSaysAFailureWithThePathsAsWritten() {
        assertEquals(1, run("robot convert --input not-there.owl --format ofn --output x.ofn"));

        String text = String.join("\n", said);
        assertTrue(text.contains("not-there.owl"), text);
        assertFalse(text.contains(folder.getAbsolutePath() + File.separator), text);
    }

    @Test
    void robotRefusesWhatItCannotPlaceOrRun() {
        assertTrue(whyNot("robot convert --frobnicate x.owl").contains("--frobnicate"),
                whyNot("robot convert --frobnicate x.owl"));
        assertTrue(whyNot("robot frobnicate -i x.owl").contains("frobnicate"));
        assertTrue(whyNot("robot expand -i x.owl").contains("'expand'"));
        assertTrue(whyNot("robot report -i x.owl --fail-on ERROR").contains("--output"));
        assertNull(whyNot("robot --catalog catalog-v001.xml merge -i x.owl reason --reasoner ELK "
                + "--output y.owl"));
    }

    @Test
    void whatRobotRefusesNamesNoFolderOfItsOwn() {
        String why = whyNot("robot query -i tmp/x.owl --update ../sparql/u.ru");

        assertTrue(why.contains("--update"), why);
        assertFalse(why.contains(File.separator + "ontoboard-"), why);
    }

    // ------------------------------------------------------------------ odk-info

    private static String robotVersion() throws IOException {
        // ROBOT's own record of its version, beside its classes.
        try (InputStream in = OdkToolsTest.class.getClassLoader().getResourceAsStream(
                "META-INF/maven/org.obolibrary.robot/robot-command/pom.properties")) {
            assertNotNull(in);
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        }
    }

    private static String firstLine() {
        return "OntoBoard " + BuildInfo.version() + " running ODK v1.6.1's recipes without "
                + "ODK's image";
    }

    @Test
    void odkInfoSaysWhatRunsTheRecipes() throws IOException {
        assertNotEquals("unknown", BuildInfo.version());

        assertEquals(0, run("odk-info --tools"));
        assertEquals(Arrays.asList(firstLine(), "ROBOT version " + robotVersion()), said);

        said.clear();
        assertEquals(0, run("odk-info"));
        assertEquals(Collections.singletonList(firstLine()), said);
    }

    @Test
    void odkInfoWithAnArgumentItDoesNotKnowSaysHowToCallIt() {
        assertEquals(0, run("odk-info --frobnicate"));

        assertEquals(Collections.singletonList("Usage: odk-info [--tools|--all-python|--all]"),
                said);
    }

    // ------------------------------------------------------------------ dicer-cli

    private static final String DICER = "dicer-cli policy --assume-manchester "
            + "--show-owlapi-error ";

    @Test
    void dicerSaysNothingOfAPolicy() throws IOException {
        write("myont-idranges.owl", Goldens.seedTree("eggboil").get(
                "src/ontology/eggboil-idranges.owl"));

        assertEquals(0, run(DICER + "myont-idranges.owl"));

        assertEquals(Collections.<String>emptyList(), said);
    }

    @Test
    void dicerSaysWhyAFileIsNoPolicy() throws IOException {
        write("myont-idranges.owl", ONTOLOGY);

        assertEquals(1, run(DICER + "myont-idranges.owl"));

        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("myont-idranges.owl"), said.toString());
        assertTrue(said.get(0).endsWith("."), said.toString());
    }

    @Test
    void dicerSaysSoOfAFileThatIsNotThere() {
        assertEquals(1, run(DICER + "myont-idranges.owl"));

        assertTrue(said.get(0).contains("myont-idranges.owl"), said.toString());
    }

    @Test
    void dicerIsCalledAsOdksRecipeCallsItOnly() {
        assertNotNull(whyNot("dicer-cli policy myont-idranges.owl"));
        assertNotNull(whyNot("dicer-cli --version"));
        assertNotNull(whyNot(DICER + "a.owl b.owl"));
        assertNull(whyNot(DICER + "myont-idranges.owl"));
    }

    // ------------------------------------------------------------------ check-rdfxml

    @Test
    void checkRdfxmlSaysOkOfRdfXml() throws IOException {
        write("myont.owl", Goldens.equivalence("prepare_release_fast/files/eggboil.owl"));

        assertEquals(0, run("check-rdfxml  myont.owl"));

        assertEquals(Arrays.asList("Checking RDF/XML file myont.owl...", "  OntoBoard: OK"), said);
    }

    @Test
    void checkRdfxmlSaysWhatTheParserSaidOfABrokenFile() throws IOException {
        write("myont.owl", "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/"
                + "02/22-rdf-syntax-ns#\">\n  <rdf:Description rdf:about=\"https://example.org/a\">"
                + "\n</rdf:RDF>\n");

        assertEquals(1, run("check-rdfxml myont.owl"));

        assertEquals(2, said.size(), said.toString());
        assertEquals("Checking RDF/XML file myont.owl...", said.get(0));
        assertTrue(said.get(1).startsWith("  OntoBoard: "), said.toString());
        assertNotEquals("  OntoBoard: OK", said.get(1));
    }

    @Test
    void checkRdfxmlWithoutAFileSaysHowToCallIt() {
        assertEquals(10, run("check-rdfxml --jena"));

        assertEquals(Collections.singletonList(
                "Usage: check-rdfxml [[--no-]lightrdf] [[--no-]rdflib] [[--no-]jena] FILE"), said);
    }

    @Test
    void checkRdfxmlOfAFileThatIsNotThere() {
        assertEquals(11, run("check-rdfxml myont.owl"));

        assertEquals(Collections.singletonList("check-rdfxml: myont.owl: file not found"), said);
    }

    /** With every parser switched off ODK's script checks nothing; so does the stand-in. */
    @Test
    void checkRdfxmlWithEveryParserOffChecksNothing() throws IOException {
        write("myont.owl", "not RDF at all");

        assertEquals(0, run("check-rdfxml --no-lightrdf myont.owl"));
        assertEquals(Collections.singletonList("Checking RDF/XML file myont.owl..."), said);

        said.clear();
        assertEquals(1, run("check-rdfxml --no-lightrdf --rdflib myont.owl"));
    }

    // ------------------------------------------------------------------ every line of the fast targets

    /**
     * Lines refused on purpose, with why, one entry per seed, target and reason. On Java 11 and
     * later: none. On Java 8 (Protégé 5.5) the lines that write JSON products - artefacts' fast
     * release writes arte.json, arte-base.json and more - because obographs, which writes JSON,
     * needs Java 11 (OdkRobot.whyNoJson).
     */
    private static List<String> refused() {
        if (!System.getProperty("java.specification.version").startsWith("1.")) {
            return Collections.emptyList();
        }
        return Collections.singletonList("artefacts prepare_release_fast: 'convert': "
                + OdkRobot.whyNoJson(System.getProperty("java.specification.version")));
    }

    /**
     * Every recipe line of the fast test, the fast release and clean, for all eight seeds - the
     * sub-makes opened - can be run with OntoBoard's tools: the shell reads it and every program
     * in it says yes before anything runs.
     */
    @Test
    void everyRecipeLineOfTheFastTargetsCanBeRun() {
        Map<String, RecipeShell.Tool> tools = OdkTools.forProject();
        TreeSet<String> refused = new TreeSet<String>();
        int lines = 0;
        int robots = 0;
        for (String seed : Goldens.seedNames()) {
            for (String goal : Arrays.asList("test_fast", "prepare_release_fast", "clean")) {
                MakeFile make = MakeFile.read("Makefile", new SeedDisk(seed),
                        Goldens.makeFixedVariables());
                for (String command : commands(make.runPlan(goal))) {
                    lines++;
                    robots += command.contains("robot ") ? 1 : 0;
                    String why;
                    try {
                        why = RecipeShell.parse(command).whyNot(tools);
                    } catch (RecipeShell.NotRead notRead) {
                        why = notRead.getMessage();
                    }
                    if (why != null) {
                        refused.add(seed + " " + goal + ": " + why);
                    }
                }
            }
        }
        assertTrue(lines > 200, "the plans were read: " + lines + " lines");
        assertTrue(robots > 100, "robot lines: " + robots);
        assertEquals(new TreeSet<String>(refused()), refused);
    }

    private static List<String> commands(List<MakeFile.Step> steps) {
        List<String> commands = new ArrayList<String>();
        for (MakeFile.Step step : steps) {
            if (step.kind() == MakeFile.Step.Kind.COMMAND) {
                commands.add(step.command());
            }
            commands.addAll(commands(step.steps()));
        }
        return commands;
    }

    /** A seeded project as make's recorded runs saw it: every file one age. */
    private static final class SeedDisk implements MakeFile.Disk {
        private final Map<String, byte[]> files;

        SeedDisk(String seed) {
            files = Goldens.seedTree(seed);
        }

        private String key(String path) {
            List<String> parts = new ArrayList<String>(Arrays.asList("src", "ontology"));
            if (path.startsWith("/")) {
                return null;
            }
            for (String part : path.split("/")) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (parts.isEmpty()) {
                        return null;
                    }
                    parts.remove(parts.size() - 1);
                } else {
                    parts.add(part);
                }
            }
            return String.join("/", parts);
        }

        @Override
        public boolean exists(String path) {
            String key = key(path);
            if (key == null) {
                return path.startsWith("/tools/robot-plugins");
            }
            if (key.isEmpty() || files.containsKey(key)) {
                return true;
            }
            for (String file : files.keySet()) {
                if (file.startsWith(key + "/")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long modified(String path) {
            return 1_762_862_400_000L;
        }

        @Override
        public List<String> list(String folder) {
            if (folder.startsWith("/tools/robot-plugins")) {
                return Arrays.asList("kgcl.jar", "odk.jar", "sssom.jar");
            }
            String key = key(folder);
            TreeSet<String> names = new TreeSet<String>();
            if (key != null) {
                String prefix = key.isEmpty() ? "" : key + "/";
                for (String file : files.keySet()) {
                    if (file.startsWith(prefix) && file.length() > prefix.length()) {
                        String rest = file.substring(prefix.length());
                        names.add(rest.contains("/") ? rest.substring(0, rest.indexOf('/'))
                                : rest);
                    }
                }
            }
            return new ArrayList<String>(names);
        }

        @Override
        public String read(String path) {
            String key = key(path);
            byte[] bytes = key == null ? null : files.get(key);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String folder() {
            return "/work/src/ontology";
        }
    }

    // ------------------------------------------------------------------ the fast test, on the fixture

    /**
     * The robot lines of ODK's own fast test, as its make printed them, up to and including
     * verify - run on the fixture in a temporary folder through the shell reader and these tools.
     * What verify says is what ROBOT said in ODK's image, word for word.
     */
    @Test
    void theRobotLinesOfTheFastTestRunOnTheFixture() throws IOException {
        File ontology = EggFixture.write(new File(temp, "egg"), false);
        String transcript = new String(Goldens.equivalence("test_fast/transcript.txt"),
                StandardCharsets.UTF_8);
        List<String> robotLines = new ArrayList<String>();
        List<String> passLines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String printed : transcript.split("\n", -1)) {
            if (printed.startsWith("PASS Rule ")) {
                passLines.add(printed);
            }
            line.append(printed);
            if (printed.endsWith("\\")) {
                line.append('\n');
                continue;
            }
            String command = line.toString();
            line.setLength(0);
            if (command.startsWith("robot ") && (robotLines.isEmpty()
                    || !robotLines.get(robotLines.size() - 1).contains(" verify "))) {
                robotLines.add(command);
            }
        }
        assertEquals(4, robotLines.size(), robotLines.toString());
        assertEquals(5, passLines.size());
        Map<String, RecipeShell.Tool> tools = OdkTools.forProject();

        List<String> verifySaid = null;
        for (String command : robotLines) {
            RecipeShell.Script script = RecipeShell.parse(command);
            assertNull(script.whyNot(tools), command);
            List<String> heard = new ArrayList<String>();
            assertEquals(0, script.run(tools, ontology, heard), command + "\n" + heard);
            verifySaid = heard;
        }

        assertTrue(new File(ontology, "tmp/eggboil-preprocess.owl").isFile());
        assertTrue(new File(ontology, "tmp/merged-eggboil-edit.ofn").isFile());
        assertFalse(new File(ontology, "test.owl").exists(), "rm test.owl ran after the reasoner");
        assertEquals(passLines, verifySaid);
    }
}
