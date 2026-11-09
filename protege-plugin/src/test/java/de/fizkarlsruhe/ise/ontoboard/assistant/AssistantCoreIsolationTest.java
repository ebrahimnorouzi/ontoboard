package de.fizkarlsruhe.ise.ontoboard.assistant;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The assistant's core runs without Protégé.
 *
 * <p>Because the benchmark runs it from a command line to compare models, and a score is only
 * worth something if it comes from the same code the plugin runs. A core class that reached for a
 * Protégé type would work inside Protégé and die with NoClassDefFoundError in the benchmark - the
 * way {@code bench/Launcher} learned about nested jars. Only {@code assistant/host/} may touch
 * Protégé; it is the bridge.
 *
 * <p>Checked in the compiled classes rather than the imports, so a fully qualified name in the
 * middle of a method is caught too.
 */
class AssistantCoreIsolationTest {

    private static final String CORE = "target/classes/de/fizkarlsruhe/ise/ontoboard/assistant";

    @Test
    void noCoreClassMentionsProtege() throws IOException {
        List<File> classes = new ArrayList<File>();
        collect(new File(CORE), classes);
        assertTrue(!classes.isEmpty(), "no compiled core classes under " + CORE);

        List<String> offenders = new ArrayList<String>();
        for (File compiled : classes) {
            String bytes = new String(Files.readAllBytes(compiled.toPath()),
                    StandardCharsets.ISO_8859_1);
            if (bytes.contains("org/protege")) {
                offenders.add(compiled.getPath());
            }
        }
        assertTrue(offenders.isEmpty(), "core classes that mention Protégé: " + offenders);
    }

    private static void collect(File directory, List<File> into) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (!"host".equals(child.getName())) {
                    collect(child, into);
                }
            } else if (child.getName().endsWith(".class")) {
                into.add(child);
            }
        }
    }
}
