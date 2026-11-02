package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every class under test is one javac could have written.
 *
 * <p>An IDE that builds this project with the Eclipse compiler - VS Code's Java support does -
 * writes into the same target/classes as Maven. For source that does not compile it still writes
 * a class, each broken method's body replaced by a throw of an "Unresolved compilation problem"
 * error. That class is newer than its source, so Maven leaves it in place, tests it and packages
 * it. Measured in 1.133.0: a self-test change javac rejects (an Exception neither caught nor
 * declared) built green - 2,669 tests, none of which reach that method - and failed both of its
 * checks in Protege.
 */
class CompiledClassesTest {

    /** Built at run time, so that this class does not carry the marker it looks for. */
    private static final byte[] STUB = new StringBuilder("Unresolved compilation")
            .append(" problem").toString().getBytes(StandardCharsets.UTF_8);

    @Test
    void noClassIsAStubTheEclipseCompilerWroteForSourceJavacRejects() throws Exception {
        List<String> stubs = new ArrayList<String>();
        for (Class<?> anchor : new Class<?>[] {OntoBoardStartup.class, getClass()}) {
            Path classes = Paths.get(
                    anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (Stream<Path> walk = Files.walk(classes)) {
                Iterator<Path> files = walk.filter(p -> p.toString().endsWith(".class"))
                        .iterator();
                while (files.hasNext()) {
                    Path file = files.next();
                    if (contains(Files.readAllBytes(file), STUB)) {
                        stubs.add(classes.relativize(file).toString());
                    }
                }
            }
        }
        assertTrue(stubs.isEmpty(), "compiled by an IDE from source javac does not accept - run "
                + "mvn clean test and fix what javac reports: " + stubs);
    }

    private static boolean contains(byte[] bytes, byte[] wanted) {
        outer:
        for (int at = 0; at <= bytes.length - wanted.length; at++) {
            for (int i = 0; i < wanted.length; i++) {
                if (bytes[at + i] != wanted[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
