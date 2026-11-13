package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How far OntoBoard's own engine reaches: for every project ODK's seed was recorded for, and
 * every target ODK's make was recorded with, whether the engine runs it - asked, nothing run.
 *
 * <p>The answer is kept as a table ({@code odk/engine-reach.txt}), one line a project and a
 * target: {@code runs}, or {@code not run} with the first sentence of the refusal. The table
 * is what the documentation's limitations are written from, and this test is what keeps it
 * true: a change that makes the engine refuse something it ran - or run something it refused -
 * shows here as a line that differs. The table is then brought up to date on purpose (the run
 * leaves what it found in {@code target/engine-reach.txt}).
 */
class EngineReachTest {

    private static final long SAME_TIME = 1_794_398_400_000L;
    private static final Pattern ID = Pattern.compile("(?m)^id:\\s*\"?([A-Za-z0-9_-]+)\"?\\s*$");

    @TempDir
    File temp;

    private static void sameAge(File folder) {
        File[] children = folder.listFiles();
        for (File child : children == null ? new File[0] : children) {
            if (child.isDirectory()) {
                sameAge(child);
            }
            assertTrue(child.setLastModified(SAME_TIME), child.getPath());
        }
    }

    /** The edit file of a seeded project: {@code src/ontology/<id>-edit.<format>}. */
    private File seeded(String config) throws IOException {
        File root = new File(temp, config);
        for (Map.Entry<String, byte[]> file : Goldens.seedTree(config).entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        sameAge(root);
        Matcher id = ID.matcher(Goldens.config(config));
        assertTrue(id.find(), config + " names its id");
        File[] edits = new File(root, "src/ontology").listFiles(
                (folder, name) -> name.startsWith(id.group(1) + "-edit."));
        assertTrue(edits != null && edits.length == 1, config + " has one edit file");
        return edits[0];
    }

    /** A refusal as a line of the table: its first sentence, without this run's folder. */
    private String inShort(String refusal, File edit) {
        String first = OdkEngine.firstSentence(refusal);
        String ontology = edit.getParentFile().getAbsolutePath();
        return first.replace(ontology + File.separator, "").replace(ontology, "src/ontology")
                .replace('\\', '/');
    }

    @Test
    void whatTheEngineRunsOfOdksTargetsForEveryRecordedProject() throws IOException {
        List<String> found = new ArrayList<String>();
        int runs = 0;
        for (String config : Goldens.configNames()) {
            File edit = seeded(config);
            for (Map.Entry<String, List<String>> label : Goldens.makeLabels().entrySet()) {
                String goal = null;
                Map<String, String> variables = new LinkedHashMap<String, String>();
                for (String word : label.getValue()) {
                    int equals = word.indexOf('=');
                    if (equals > 0) {
                        variables.put(word.substring(0, equals), word.substring(equals + 1));
                    } else {
                        goal = word;
                    }
                }
                variables.putAll(Goldens.makeFixedVariables());
                String why = OdkEngine.whyNot(edit, goal, variables);
                runs += why == null ? 1 : 0;
                found.add(config + " " + label.getKey() + ": "
                        + (why == null ? "runs" : "not run - " + inShort(why, edit)));
            }
        }
        String table = String.join("\n", found) + "\n";
        File kept = new File("target/engine-reach.txt");
        Files.createDirectories(kept.getAbsoluteFile().getParentFile().toPath());
        Files.write(kept.toPath(), table.getBytes(StandardCharsets.UTF_8));

        assertEquals(new String(Goldens.bytes(new File(Goldens.root().getParentFile(),
                "engine-reach.txt")), StandardCharsets.UTF_8).replace("\r\n", "\n"), table,
                runs + " of " + found.size() + " run; the table found is in "
                        + kept.getPath());
    }
}
