package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * When a message tells the user where to go, the place has to exist.
 *
 * <p>{@link MenuStructureTest} checks that every menu entry is reachable and that every action
 * is on a menu. Neither notices the other direction: a sentence in a dialog naming a menu item
 * that was renamed, moved or never existed. Nothing resolves that string, so it cannot fail to
 * compile and no test touches it - it simply sends somebody to a menu they will not find, and
 * they have no second place to look.
 *
 * <p>That is not hypothetical here. Until 1.86.0 every "why you are not live" message said
 * <em>"Set one up in Tools &gt; Collaboration Settings"</em>, and there has never been such an
 * item: plugin.xml registers it as <em>Collaboration...</em> under the OntoBoard menu. It had
 * been wrong since the message was written.
 */
class MenuPathsInMessagesTest {

    /**
     * {@code OntoBoard > } and whatever follows it, inside one string literal.
     *
     * <p>Only the lead-in is matched. Trying to match the item's name as well needs a rule for
     * where a menu label stops and the rest of the sentence starts, and there is no such rule -
     * "Project &gt; Open existing ODK project..." and "Project &gt; Build..., then" end
     * differently. So the whole remainder is captured and a registered name has to be a prefix
     * of it, which cannot clip a long label in half.
     */
    private static final Pattern MENTION =
            Pattern.compile("(?:OntoBoard|Tools|Project|ROBOT|Notes)\\s*>\\s*(.*)");

    /** Java string literals, so an explanatory comment quoting an old label is not a finding. */
    private static final Pattern LITERAL =
            Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /** Every {@code <name value="..."/>} plugin.xml registers. */
    private static Set<String> registeredNames() throws Exception {
        Document document;
        try (InputStream in = MenuPathsInMessagesTest.class.getResourceAsStream("/plugin.xml")) {
            assertNotNull(in, "plugin.xml missing from the built classpath");
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        Set<String> names = new LinkedHashSet<String>();
        NodeList extensions = document.getElementsByTagName("extension");
        for (int at = 0; at < extensions.getLength(); at++) {
            NodeList children = ((Element) extensions.item(at)).getElementsByTagName("name");
            for (int child = 0; child < children.getLength(); child++) {
                String value = ((Element) children.item(child)).getAttribute("value").trim();
                if (!value.isEmpty()) {
                    names.add(normalise(value));
                }
            }
        }
        assertFalse(names.isEmpty(), "plugin.xml registered no named entries at all");
        return names;
    }

    /** Menu labels differ by an ellipsis and by case more often than they differ in substance. */
    private static String normalise(String label) {
        return label.replace("…", "...").replaceAll("\\.\\.\\.$", "").trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Every menu path named in a user-facing string is a menu entry that exists.
     *
     * <p>Submenu headings - Project, ROBOT, Notes - are accepted as destinations in their own
     * right, since "OntoBoard > Project" is a real place even though no action is registered
     * under that exact name.
     */
    @Test
    void everyMenuPathInAMessageExists() throws Exception {
        Set<String> registered = registeredNames();
        // The submenus themselves, which are containers rather than actions.
        registered.add("project");
        registered.add("robot");
        registered.add("notes");
        registered.add("collaboration");

        List<String> wrong = new ArrayList<String>();
        for (File source : javaFilesUnder(
                new File("src/main/java/de/fizkarlsruhe/ise/ontoboard"))) {
            String text = new String(Files.readAllBytes(source.toPath()),
                    StandardCharsets.UTF_8);
            Matcher literal = LITERAL.matcher(text);
            while (literal.find()) {
                Matcher mention = MENTION.matcher(literal.group(1));
                if (!mention.find()) {
                    continue;
                }
                String remainder = normalise(mention.group(1));
                if (remainder.isEmpty() || namesAPrefixOf(registered, remainder)) {
                    continue;
                }
                wrong.add(source.getName() + ": \"..." + mention.group().trim()
                        + "\" - no registered entry starts that way");
            }
        }
        assertEquals("[]", wrong.toString(),
                "a message sends the user to a menu item that does not exist");
    }

    /** Whether any registered label begins the text that follows the menu name. */
    private static boolean namesAPrefixOf(Set<String> registered, String remainder) {
        for (String name : registered) {
            if (!name.isEmpty() && remainder.startsWith(name)) {
                return true;
            }
        }
        return false;
    }

    /** The specific sentence that was wrong, pinned so it cannot drift back. */
    @Test
    void theGitModeExplanationNamesTheRealMenu() {
        String explanation = de.fizkarlsruhe.ise.ontoboard.collab.CollabSettings.offline()
                .explainWhyNotLive();

        assertNotNull(explanation);
        assertTrue(explanation.contains("OntoBoard > Collaboration"), explanation);
        assertFalse(explanation.contains("Tools >"),
                "there is no Tools > Collaboration Settings, and never was: " + explanation);
    }

    private static List<File> javaFilesUnder(File directory) throws IOException {
        List<File> found = new ArrayList<File>();
        File[] children = directory.listFiles();
        if (children == null) {
            return found;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                found.addAll(javaFilesUnder(child));
            } else if (child.getName().endsWith(".java")) {
                found.add(child);
            }
        }
        return found;
    }
}
