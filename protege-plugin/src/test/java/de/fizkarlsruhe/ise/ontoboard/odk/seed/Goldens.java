package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the goldens under {@code src/test/resources/odk/v1.6.1}: what the pinned ODK image
 * produced, made by {@code tools/odk-goldens/generate.py}.
 *
 * <p>From the source folder, not from the class path. A seeded project contains a
 * {@code .gitignore}, and Maven's resource copying leaves such files out by default - a golden
 * read from {@code target/test-classes} would be one file short, and the parity test would fail on
 * the file ODK most certainly writes.
 */
public final class Goldens {

    private Goldens() {
    }

    /** {@code src/test/resources/odk/<pinned version>} of this module. */
    public static File root() {
        File module = new File(System.getProperty("basedir", System.getProperty("user.dir")));
        File root = new File(module, "src/test/resources/odk/" + OdkPin.VERSION);
        if (!root.isDirectory()) {
            throw new IllegalStateException("No goldens at " + root.getAbsolutePath()
                    + " - run protege-plugin/tools/odk-goldens/generate.py");
        }
        return root;
    }

    public static String provenance() {
        return text(new File(root(), "PROVENANCE.txt"));
    }

    // ------------------------------------------------------------- OntoBoard's configs

    /** The names of the configs OntoBoard's own tests seed, sorted. */
    public static List<String> configNames() {
        return namesIn(new File(root(), "configs"), ".yaml");
    }

    /** A config, as text. */
    public static String config(String name) {
        return new String(configBytes(name), StandardCharsets.UTF_8);
    }

    public static byte[] configBytes(String name) {
        return bytes(new File(root(), "configs/" + name + ".yaml"));
    }

    /** Every file {@code odk.py seed --skipgit} wrote for the config: path with {@code /} to bytes. */
    public static Map<String, byte[]> seedTree(String name) {
        return tree(new File(root(), "seed/" + name));
    }

    /** {@code odk.py export-project}'s dump of the config: every field, defaults filled in. */
    public static Map<String, Object> model(String name) {
        return yaml(new File(root(), "model/" + name + ".yaml"));
    }

    // ------------------------------------------------------------- ODK's own configs

    /** ODK's sample configs: {@code configs__x}, {@code tests__x}, {@code examples__x}; sorted. */
    public static List<String> corpusNames() {
        return namesIn(new File(root(), "corpus/configs"), ".yaml");
    }

    public static String corpusConfig(String name) {
        return text(new File(root(), "corpus/configs/" + name + ".yaml"));
    }

    public static Map<String, Object> corpusModel(String name) {
        return yaml(new File(root(), "corpus/model/" + name + ".yaml"));
    }

    /** {@code <sha256>  <path>} for every file ODK seeded for a corpus config, sorted by path. */
    public static String manifest(String name) {
        return text(new File(root(), "corpus/manifests/" + name + ".sha256"));
    }

    // ------------------------------------------------------------- the lexer fixture

    /** One piece of a template as Jinja2 cuts it: text, or a {@code {{ }}} or {@code {% %}}. */
    public static final class Chunk {
        public final String kind;
        public final String text;
        public final int line;

        Chunk(String kind, String text, int line) {
            this.kind = kind;
            this.text = text;
            this.line = line;
        }

        @Override
        public String toString() {
            return kind + "@" + line + ":" + text;
        }
    }

    /** Jinja2 3.1.6's own cut of a bundled template, e.g. {@code src/ontology/Makefile.jinja2}. */
    public static List<Chunk> lexerChunks(String templatePath) {
        File file = new File(root(), "lexer/" + templatePath.replace("/", "__") + ".json");
        List<Chunk> chunks = new ArrayList<Chunk>();
        try {
            for (JsonNode entry : new ObjectMapper().readTree(bytes(file))) {
                chunks.add(new Chunk(entry.get(0).asText(), entry.get(1).asText(),
                        entry.get(2).asInt()));
            }
        } catch (IOException notJson) {
            throw new IllegalStateException(file + " is not the lexer fixture's JSON", notJson);
        }
        return chunks;
    }

    // ------------------------------------------------------------- comparing

    /**
     * Fails unless the two trees hold the same files with the same bytes, saying which files are
     * missing or extra and, for the first that differs, where: its first differing line, with
     * the lines around it. "Differs" alone would send somebody to a hex editor.
     */
    public static void assertTreeEquals(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        TreeSet<String> missing = new TreeSet<String>(expected.keySet());
        missing.removeAll(actual.keySet());
        TreeSet<String> extra = new TreeSet<String>(actual.keySet());
        extra.removeAll(expected.keySet());
        StringBuilder said = new StringBuilder();
        if (!missing.isEmpty()) {
            said.append("not written: ").append(missing).append('\n');
        }
        if (!extra.isEmpty()) {
            said.append("written, and ODK does not write them: ").append(extra).append('\n');
        }
        int differing = 0;
        for (Map.Entry<String, byte[]> file : new TreeMap<String, byte[]>(expected).entrySet()) {
            byte[] ours = actual.get(file.getKey());
            if (ours == null || Arrays.equals(file.getValue(), ours)) {
                continue;
            }
            if (differing++ == 0) {
                said.append(firstDifference(file.getKey(), file.getValue(), ours));
            } else {
                said.append("also differs: ").append(file.getKey()).append('\n');
            }
        }
        if (said.length() > 0) {
            fail(said.toString());
        }
    }

    /** Where two versions of a file part: the line, three lines before it, and both readings. */
    static String firstDifference(String path, byte[] expected, byte[] actual) {
        String[] theirs = new String(expected, StandardCharsets.UTF_8).split("\n", -1);
        String[] ours = new String(actual, StandardCharsets.UTF_8).split("\n", -1);
        int at = 0;
        while (at < theirs.length && at < ours.length && theirs[at].equals(ours[at])) {
            at++;
        }
        StringBuilder said = new StringBuilder(path + " differs at line " + (at + 1) + " (ODK's has "
                + theirs.length + " lines, " + expected.length + " bytes; ours " + ours.length
                + " lines, " + actual.length + " bytes)\n");
        for (int before = Math.max(0, at - 3); before < at; before++) {
            said.append("      ").append(theirs[before]).append('\n');
        }
        said.append("  ODK ").append(at < theirs.length ? visible(theirs[at]) : "<end of file>")
                .append('\n');
        said.append("  ours").append(at < ours.length ? " " + visible(ours[at]) : " <end of file>")
                .append('\n');
        return said.toString();
    }

    /** A line with what cannot be seen made visible: trailing spaces, tabs, carriage returns. */
    private static String visible(String line) {
        return "[" + line.replace("\t", "\\t").replace("\r", "\\r") + "]";
    }

    // ------------------------------------------------------------- reading

    public static Map<String, byte[]> tree(File directory) {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        collect(directory, "", files);
        if (files.isEmpty()) {
            throw new IllegalStateException("No files under " + directory.getAbsolutePath());
        }
        return files;
    }

    private static void collect(File directory, String prefix, Map<String, byte[]> into) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, prefix + child.getName() + "/", into);
            } else {
                into.put(prefix + child.getName(), bytes(child));
            }
        }
    }

    private static List<String> namesIn(File directory, String suffix) {
        List<String> names = new ArrayList<String>();
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && child.getName().endsWith(suffix)) {
                    names.add(child.getName().substring(0,
                            child.getName().length() - suffix.length()));
                }
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(File file) {
        Object loaded = new Yaml().load(text(file));
        if (!(loaded instanceof Map)) {
            throw new IllegalStateException(file + " is not a YAML mapping: " + loaded);
        }
        return (Map<String, Object>) loaded;
    }

    private static String text(File file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    public static byte[] bytes(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException cannotRead) {
            throw new IllegalStateException("Could not read golden " + file.getAbsolutePath()
                    + ": " + cannotRead.getMessage(), cannotRead);
        }
    }
}
