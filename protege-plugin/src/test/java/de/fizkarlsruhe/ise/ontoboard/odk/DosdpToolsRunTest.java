package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dosdp-tools as OntoBoard's own engine runs it: the release's launcher on a Java 11 or later,
 * in the folder make runs in - what it prints is the transcript, its status the line's, and a
 * stop ends it.
 */
class DosdpToolsRunTest {

    @TempDir
    File temp;

    /** In place of the release's launcher: says where it runs and what it was given. */
    public static final class Fake {
        public static void main(String[] arguments) throws Exception {
            System.out.println("folder " + new File("").getAbsolutePath());
            System.out.println("arguments " + String.join(" ", arguments));
            StringBuilder separator = new StringBuilder("line separator");
            for (char letter : System.lineSeparator().toCharArray()) {
                separator.append(' ').append((int) letter);
            }
            System.out.println(separator);
            System.err.println("said on the error stream");
            for (String argument : arguments) {
                if (argument.startsWith("--exit=")) {
                    System.exit(Integer.parseInt(argument.substring("--exit=".length())));
                }
                if (argument.equals("--keep-going")) {
                    File alive = new File("alive");
                    while (true) {
                        try (FileOutputStream beat = new FileOutputStream(alive, true)) {
                            beat.write('.');
                        }
                        Thread.sleep(50);
                    }
                }
            }
        }
    }

    /** A home with the release's layout whose launcher is {@link Fake}. */
    private File installed() throws IOException {
        File home = new File(temp, "dosdp-tools-0.19.3");
        File launcher = new File(new File(home, "lib"), DosdpTools.LAUNCHER);
        Files.createDirectories(launcher.getParentFile().toPath());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Fake.class.getName());
        String entry = Fake.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(launcher), manifest);
                InputStream bytes = Fake.class.getClassLoader().getResourceAsStream(entry)) {
            jar.putNextEntry(new JarEntry(entry));
            byte[] buffer = new byte[8192];
            for (int read = bytes.read(buffer); read > 0; read = bytes.read(buffer)) {
                jar.write(buffer, 0, read);
            }
            jar.closeEntry();
        }
        return home;
    }

    private static File thisJava() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win");
        return new File(new File(System.getProperty("java.home"), "bin"),
                windows ? "java.exe" : "java");
    }

    /** Run with the Java running this test, whatever its version: {@link Fake} needs no more. */
    private static DosdpToolsRun tool(File home, BooleanSupplier stopped) {
        return new DosdpToolsRun(home, () -> DosdpToolsRun.Java.at(thisJava()), stopped);
    }

    private static Map<String, RecipeShell.Tool> tools(RecipeShell.Tool dosdp) {
        Map<String, RecipeShell.Tool> tools = new LinkedHashMap<String, RecipeShell.Tool>(
                RecipeShell.builtins());
        tools.put("dosdp-tools", dosdp);
        return tools;
    }

    private File makeFolder() throws IOException {
        File folder = new File(temp, "with blank/src/ontology");
        Files.createDirectories(folder.toPath());
        return folder;
    }

    @Test
    void notInstalledIsSaidBeforeARunWithWhatToDo() {
        String why = tool(new File(temp, "dosdp-tools-0.19.3"), null)
                .whyNot(Arrays.asList("terms", "--infile=a.tsv", "--outfile=a.txt"));

        assertNotNull(why);
        assertTrue(why.startsWith(DosdpTools.NOT_INSTALLED), why);
        assertTrue(why.contains("119 MB"), why);
    }

    @Test
    void aCallRunsInTheMakeFolderAndItsOutputIsTheTranscript() throws IOException {
        File folder = makeFolder();
        Map<String, RecipeShell.Tool> tools = tools(tool(installed(), null));
        RecipeShell.Script script = RecipeShell.parse("dosdp-tools terms --infile=a.tsv");
        assertNull(script.whyNot(tools));
        List<String> said = new ArrayList<String>();

        assertEquals(0, script.run(tools, folder, said), said.toString());

        assertTrue(said.contains("arguments terms --infile=a.tsv"), said.toString());
        assertTrue(said.contains("said on the error stream"), said.toString());
        String where = null;
        for (String line : said) {
            if (line.startsWith("folder ")) {
                where = line.substring("folder ".length());
            }
        }
        assertNotNull(where, said.toString());
        assertEquals(folder.getCanonicalPath(), new File(where).getCanonicalPath());
    }

    /**
     * What it writes as text - a pipeline's term list - ends its lines as in ODK's image: Java
     * on Windows would end them in CRLF (found by DosdpEquivalenceLiveTest on example.txt).
     */
    @Test
    void itEndsItsLinesAsOdksImageDoes() throws IOException {
        List<String> said = new ArrayList<String>();

        assertEquals(0, RecipeShell.parse("dosdp-tools terms --infile=a.tsv")
                .run(tools(tool(installed(), null)), makeFolder(), said), said.toString());

        assertTrue(said.contains("line separator 10"), said.toString());
    }

    @Test
    void theLinesStatusIsTheProgramsOwn() throws IOException {
        List<String> said = new ArrayList<String>();

        int status = RecipeShell.parse("dosdp-tools generate --exit=3")
                .run(tools(tool(installed(), null)), makeFolder(), said);

        assertEquals(3, status, said.toString());
    }

    @Test
    void stoppingEndsTheChildProcess() throws Exception {
        File folder = makeFolder();
        final File alive = new File(folder, "alive");
        List<String> said = new ArrayList<String>();
        long started = System.nanoTime();

        int status = RecipeShell.parse("dosdp-tools query --keep-going")
                .run(tools(tool(installed(), alive::exists)), folder, said);

        assertEquals(130, status, said.toString());
        assertTrue(System.nanoTime() - started < 30_000_000_000L, "the stop was not waited on");
        long length = alive.length();
        Thread.sleep(600);
        assertEquals(length, alive.length(), "the program still runs after the stop");
    }

    /** No subcommand of 0.19.3 runs on Java 8: its Jena is compiled for 11 (measured). */
    @Test
    void aCallThatNeedsJava11SaysSoOnJava8() throws IOException {
        File eight = new File("/old/bin/java");
        DosdpToolsRun tool = new DosdpToolsRun(installed(),
                () -> DosdpToolsRun.first(Collections.singletonList(eight), java -> 8), null);

        String why = tool.whyNot(Arrays.asList("terms", "--outfile=a.txt"));

        assertNotNull(why);
        assertTrue(why.contains("Java 11") && why.contains(eight.getPath() + " is Java 8"), why);
    }

    @Test
    void theFirstJavaThatIsElevenOrNewerRunsIt() {
        File eight = new File("/a/java");
        File eleven = new File("/b/java");
        File seventeen = new File("/c/java");
        Map<File, Integer> versions = new HashMap<File, Integer>();
        versions.put(eight, 8);
        versions.put(eleven, 11);
        versions.put(seventeen, 17);

        DosdpToolsRun.Java java = DosdpToolsRun.first(Arrays.asList(eight, eleven, seventeen),
                versions::get);

        assertEquals(eleven, java.executable());
        assertNull(java.whyNone());
    }

    @Test
    void theRunningJavasVersionIsItsOwn() {
        String specification = System.getProperty("java.specification.version");
        int running = Integer.parseInt(specification.startsWith("1.")
                ? specification.substring(2) : specification);

        assertEquals(running, DosdpToolsRun.majorVersion(thisJava()));
    }

    @Test
    void aJavaThatIsNotThereHasNoVersion() {
        assertEquals(-1, DosdpToolsRun.majorVersion(new File(temp, "no/bin/java")));
    }

    /** OntoBoard's own engine writes inside the project only (Task 10's rule, as for ROBOT). */
    @Test
    void whatALineWritesOutsideTheProjectIsRefused() throws IOException {
        DosdpToolsRun tool = tool(installed(), null);

        assertNull(tool.whyNot(Arrays.asList("terms", "--outfile=../patterns/data/a.txt")));
        String climbs = tool.whyNot(Arrays.asList("terms", "--outfile=../../../elsewhere.txt"));
        assertTrue(climbs != null && climbs.contains("outside the project"), climbs);
        String apart = tool.whyNot(Arrays.asList("prototype", "--outfile", "/tmp/pattern.owl"));
        assertTrue(apart != null && apart.contains("outside the project"), apart);
    }
}
