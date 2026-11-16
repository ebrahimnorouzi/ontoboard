package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code sed -i 's/RE/REPL/[g]' FILE...} inside Protégé, as GNU sed 4.9 in ODK's image does it.
 * Projects' own rules use it on what they make - nfdicore's import rules turn obofoundry into
 * obolibrary and take a declaration out. Held against what GNU sed left of each file in the
 * image ({@code sed/<case>/}, made by {@code tools/odk-goldens/sed_goldens.py}).
 */
class OdkSedTest {

    @TempDir
    File temp;

    private final List<String> said = new ArrayList<String>();

    /** The cases recorded in the image. */
    static List<String> recordedCases() {
        List<String> names = new ArrayList<String>();
        File[] cases = new File(Goldens.root(), "sed").listFiles();
        for (File one : cases == null ? new File[0] : cases) {
            if (new File(one, "script.txt").isFile()) {
                names.add(one.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private File folder() throws IOException {
        // A project's folder may have a blank in its name.
        File folder = new File(temp, "with blank");
        Files.createDirectories(folder.toPath());
        return folder;
    }

    private int run(File folder, String... arguments) throws IOException {
        return new OdkSed().run(new RecipeShell.Call(Arrays.asList(arguments), folder, "",
                new StringBuilder(), said));
    }

    /** Each file as GNU sed left it, byte for byte; GNU sed said nothing. */
    @ParameterizedTest
    @MethodSource("recordedCases")
    void aSubstitutionLeavesTheFileAsGnuSedDoes(String name) throws IOException {
        File given = new File(new File(Goldens.root(), "sed"), name);
        File folder = folder();
        Files.copy(new File(given, "before.txt").toPath(), new File(folder, "file.txt").toPath());
        String script = text(new File(given, "script.txt")).trim();
        assertNull(new OdkSed().whyNot(Arrays.asList("-i", script, "file.txt")), script);

        int status = run(folder, "-i", script, "file.txt");

        assertEquals(0, status, said.toString());
        assertEquals(text(new File(given, "after.txt")), text(new File(folder, "file.txt")),
                name + ": " + script);
        assertEquals("exit 0", text(new File(given, "sed.txt")).trim());
        assertEquals(Collections.<String>emptyList(), said);
    }

    @Test
    void theRecordedCasesAreThere() {
        assertEquals(15, recordedCases().size(), recordedCases().toString());
    }

    /** nfdicore's own two lines, as make hands them to the shell. */
    @Test
    void nfdicoresLinesRunThroughTheRecipeShell() throws IOException {
        File ontology = new File(temp, "nfdicore/src/ontology");
        File module = new File(ontology, "imports/swo_import.owl");
        Files.createDirectories(module.getParentFile().toPath());
        Files.write(module.toPath(), ("<http://purl.obofoundry.org/obo/swo.owl>\n"
                + "Declaration(AnnotationProperty(rdf:type))\n").getBytes(StandardCharsets.UTF_8));
        Map<String, RecipeShell.Tool> tools = OdkTools.forProject();
        RecipeShell.Script line = RecipeShell.parse("sed -i \"s/obofoundry/obolibrary/g\" "
                + "imports/swo_import.owl ;  sed -i \"s/Declaration(AnnotationProperty(rdf:type))"
                + "//g\" imports/swo_import.owl");

        assertNull(line.whyNot(tools));
        assertEquals(0, line.run(tools, ontology, said), said.toString());

        assertEquals("<http://purl.obolibrary.org/obo/swo.owl>\n\n", text(module));
    }

    @Test
    void aScriptGivenWithEIsTheSame() throws IOException {
        File folder = folder();
        Files.write(new File(folder, "f.txt").toPath(), "a a\n".getBytes(StandardCharsets.UTF_8));

        assertNull(new OdkSed().whyNot(Arrays.asList("-i", "-e", "s/a/b/", "f.txt")));
        assertEquals(0, run(folder, "-i", "-e", "s/a/b/", "f.txt"));

        assertEquals("b a\n", text(new File(folder, "f.txt")));
    }

    /** GNU sed says so, goes on with the next file, and ends with 2. */
    @Test
    void aFileThatIsNotThereIsSaidAsGnuSedSaysIt() throws IOException {
        File folder = folder();
        Files.write(new File(folder, "there.txt").toPath(), "x\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, run(folder, "-i", "s/x/y/", "nothing.txt", "there.txt"));

        assertEquals(Collections.singletonList("sed: can't read nothing.txt: No such file or "
                + "directory"), said);
        assertEquals("y\n", text(new File(folder, "there.txt")));
    }

    /** Arguments split at the bar: each line is not a single substitution in place. */
    @ParameterizedTest
    @ValueSource(strings = {"-E|-i|s/a+/b/|f.txt", "-n|-i|s/a/b/p|f.txt", "-i|s/a/b/p|f.txt",
        "-i|/a/d|f.txt", "-i|p|f.txt", "-i|-e|s/a/b/|-e|s/c/d/|f.txt", "-i|s/a/b/;s/c/d/|f.txt",
        "-i.bak|s/a/b/|f.txt", "s/a/b/|f.txt", "-i|s/a/b/", "-i|s/a/b/I|f.txt", "-i|s/a/b|f.txt",
        "-i|s/a/b/|-n|f.txt"})
    void whatIsNotOneSubstitutionInPlaceIsRefusedByName(String line) {
        List<String> arguments = Arrays.asList(line.split("\\|"));

        String why = new OdkSed().whyNot(arguments);

        assertNotNull(why, line);
        assertTrue(why.startsWith("'sed " + String.join(" ", arguments) + "' is not read here"),
                why);
        assertTrue(why.contains("only sed -i 's/RE/REPL/[g]' FILE..."), why);
    }

    /** What GNU adds to POSIX's basic expressions, and a reference to a group that is not. */
    @ParameterizedTest
    @ValueSource(strings = {"s/a\\+/b/", "s/a\\?/b/", "s/a\\|c/b/", "s/\\w/b/", "s/\\bword/b/",
        "s/[[:alpha:]]/b/", "s/a/\\1/", "s/\\(a\\)/\\2/", "s/a/b\\n/", "s/a/\\U&/"})
    void whatIsNotPosixsBasicSyntaxIsRefusedByName(String script) {
        String why = new OdkSed().whyNot(Arrays.asList("-i", script, "f.txt"));

        assertNotNull(why, script);
        assertTrue(why.contains("is not read here"), why);
    }

    /** sed writes the files it is given: those outside the project are refused. */
    @ParameterizedTest
    @ValueSource(strings = {"/etc/hosts", "../../../outside.txt"})
    void aFileOutsideTheProjectIsRefused(String file) {
        String why = new OdkSed().whyNot(Arrays.asList("-i", "s/a/b/", file));

        assertNotNull(why, file);
        assertTrue(why.contains("outside the project"), why);
    }
}
