package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * No message names a menu item by the name 1.155.0 took from it. The Spreadsheets menu became
 * Knowledge graph - Sheet editor became Edit sheets, Template... became Build from sheets... -
 * and a message that still says "Template..." sends people to an item the menu no longer has
 * (the 1.155.0 review's M11). MenuPathsInMessagesTest follows "OntoBoard &gt; ..." paths; a bare
 * item name in a sentence is not one, so this looks for the old names themselves: in every
 * string of the plugin's own code, and in the generator of the playground deck, which reads
 * the menu from plugin.xml and stops on a name it does not find.
 */
class RenamedMenuItemsTest {

    private static final List<String> OLD_NAMES = Arrays.asList("Template...", "Template…",
            "Sheet editor", "Spreadsheets >", "Spreadsheets &gt;", "Spreadsheets ->");

    /** A Java string literal; escapes inside it are kept as they are written. */
    private static final Pattern JAVA_STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /** A Python string in single or double quotes, on one line. */
    private static final Pattern PYTHON_STRING =
            Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"|'((?:[^'\\\\\\n]|\\\\.)*)'");

    private static List<File> javaUnder(File folder) {
        List<File> found = new ArrayList<File>();
        File[] files = folder.listFiles();
        for (File file : files == null ? new File[0] : files) {
            if (file.isDirectory()) {
                found.addAll(javaUnder(file));
            } else if (file.getName().endsWith(".java")) {
                found.add(file);
            }
        }
        return found;
    }

    private static void oldNamesIn(File file, Pattern strings, List<String> found)
            throws Exception {
        String[] lines = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher string = strings.matcher(lines[i]);
            while (string.find()) {
                String text = string.group(1) != null ? string.group(1) : string.group(2);
                for (String old : OLD_NAMES) {
                    if (text != null && text.contains(old)) {
                        found.add(file.getName() + ":" + (i + 1) + " says \"" + old + "\"");
                    }
                }
            }
        }
    }

    @Test
    void noMessageNamesAnItemByItsOldName() throws Exception {
        List<String> found = new ArrayList<String>();
        for (File source : javaUnder(new File("src/main/java"))) {
            oldNamesIn(source, JAVA_STRING, found);
        }
        assertTrue(found.isEmpty(), String.join("\n", found));
    }

    @Test
    void theDeckGeneratorAsksForTheItemsByTheirNames() throws Exception {
        File generator = new File("../tools/build-deck.py");
        assertTrue(generator.isFile(), generator.getAbsolutePath());
        List<String> found = new ArrayList<String>();
        oldNamesIn(generator, PYTHON_STRING, found);
        assertTrue(found.isEmpty(), String.join("\n", found));
    }
}
