package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * When a message or a page tells the user where to go, the place has to exist - the whole way
 * there, not only its last name.
 *
 * <p>{@link MenuStructureTest} checks that every menu entry is reachable and that every action
 * is on a menu. Neither notices the other direction: a sentence in a dialog naming a menu item
 * that was renamed, moved or never existed. Nothing resolves that string, so it cannot fail to
 * compile and no test touches it - it simply sends somebody to a menu they will not find, and
 * they have no second place to look.
 *
 * <p>That is not hypothetical here. Until 1.86.0 every "why you are not live" message said
 * <em>"Set one up in Tools &gt; Collaboration Settings"</em>, and there has never been such an
 * item. Until 1.133.0 only the last name of a path was looked up, so "ROBOT &gt; Template..."
 * passed long after Template had moved to a menu of its own, and "Project &gt; Pull request..."
 * passed although Pull request was never under Project. When the menu was grouped by job in
 * 1.133.0, every message naming the old places would have passed as well.
 */
class MenuPathsInMessagesTest {

    /** A place in the menu: its name, and what is under it when it is a submenu. */
    static final class Place {
        final String name;
        final boolean submenu;
        final List<Place> under = new ArrayList<Place>();

        Place(String name, boolean submenu) {
            this.name = name;
            this.submenu = submenu;
        }
    }

    /**
     * Where a path may start: the OntoBoard menu, Protégé's Tools, one of OntoBoard's submenus,
     * or a submenu OntoBoard used to have - so that a message still naming one is found.
     */
    private static final String STARTS = "OntoBoard|Tools|Project|Reuse|Terms|Spreadsheets"
            + "|Check and test|Build and release|Share|ROBOT|Notes|Knowledge graph";

    /**
     * A start and an arrow - {@code >}, {@code &gt;}, {@code ›} or {@code →}. The path is what
     * follows, to the end of the text: matching it as part of the pattern would swallow a second
     * path on the same line, which then went unchecked.
     */
    private static final Pattern MENTION =
            Pattern.compile("\\b(" + STARTS + ")\\s*(?:>|&gt;|›|→)\\s*");

    /** Java string literals, so an explanatory comment quoting an old label is not a finding. */
    private static final Pattern LITERAL =
            Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /**
     * Pages that record what was, not what to do: the fixes made, audits, measurements and the
     * study material keep the menu names of their day.
     */
    private static final Set<String> HISTORY = new HashSet<String>(Arrays.asList(
            "fixed.md", "paper-claims-audit.md", "requirements-status.md", "measurements.md",
            "evaluation-plan.md"));

    /** The OntoBoard menu as plugin.xml declares it, and Protégé's Tools menu beside it. */
    static Place[] menus() throws Exception {
        Document document;
        try (InputStream in = MenuPathsInMessagesTest.class.getResourceAsStream("/plugin.xml")) {
            assertNotNull(in, "plugin.xml missing from the built classpath");
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        Map<String, Place> byId = new HashMap<String, Place>();
        Map<String, String> parentOf = new HashMap<String, String>();
        NodeList extensions = document.getElementsByTagName("extension");
        for (int at = 0; at < extensions.getLength(); at++) {
            Element extension = (Element) extensions.item(at);
            if (!"org.protege.editor.core.application.EditorKitMenuAction".equals(
                    extension.getAttribute("point"))) {
                continue;
            }
            String path = valueOf(extension, "path");
            String id = "ontoboard." + extension.getAttribute("id");
            byId.put(id, new Place(valueOf(extension, "name"),
                    valueOf(extension, "class") == null));
            parentOf.put(id, path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
        }
        Place onto = byId.get("ontoboard.menu.OntoBoard");
        assertNotNull(onto, "no OntoBoard menu in plugin.xml");
        Place tools = new Place("Tools", true);
        for (Map.Entry<String, String> entry : parentOf.entrySet()) {
            Place parent = "org.protege.editor.owl.menu.tools".equals(entry.getValue()) ? tools
                    : byId.get(entry.getValue());
            if (parent != null) {
                parent.under.add(byId.get(entry.getKey()));
            }
        }
        return new Place[] {onto, tools};
    }

    private static String valueOf(Element extension, String child) {
        NodeList nodes = extension.getElementsByTagName(child);
        return nodes.getLength() == 0 ? null
                : ((Element) nodes.item(0)).getAttribute("value").trim();
    }

    /** Menu labels differ by an ellipsis and by case more often than they differ in substance. */
    private static String normalise(String label) {
        return label.replace("…", "...").replaceAll("\\.\\.\\.$", "").trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Null when the path a mention names is in the menu; otherwise what is wrong with it. The
     * path is followed submenu by submenu; an action ends it, since what follows an action is
     * its dialog or the rest of the sentence.
     */
    static String problemWith(Place[] menus, String start, String rest) {
        Place at = null;
        if ("OntoBoard".equals(start)) {
            at = menus[0];
        } else if ("Tools".equals(start)) {
            at = menus[1];
        } else {
            for (Place submenu : menus[0].under) {
                if (submenu.submenu && normalise(submenu.name).equals(normalise(start))) {
                    at = submenu;
                }
            }
            if (at == null) {
                return "the OntoBoard menu has no " + start;
            }
        }
        String text = rest.replace("…", "...").replace("&gt;", ">").replace("›", ">")
                .replace("→", ">").toLowerCase(Locale.ROOT);
        while (true) {
            text = text.trim();
            if (text.isEmpty()) {
                return "the path stops at an arrow - keep it in one string";
            }
            Place next = null;
            for (Place candidate : at.under) {
                String name = normalise(candidate.name);
                if (!name.isEmpty() && text.startsWith(name)
                        && (next == null || name.length() > normalise(next.name).length())) {
                    next = candidate;
                }
            }
            if (next == null) {
                return "nothing under " + at.name + " starts \""
                        + text.substring(0, Math.min(40, text.length())) + "\"";
            }
            if (!next.submenu) {
                return null;
            }
            text = text.substring(normalise(next.name).length());
            text = text.startsWith("...") ? text.substring(3).trim() : text.trim();
            if (!text.startsWith(">")) {
                return null;
            }
            text = text.substring(1);
            at = next;
        }
    }

    /** Every menu path named in a string the plugin shows or writes is a path in the menu. */
    @Test
    void everyMenuPathInAMessageExists() throws Exception {
        Place[] menus = menus();
        List<String> wrong = new ArrayList<String>();
        for (File source : filesUnder(new File("src/main/java/de/fizkarlsruhe/ise/ontoboard"),
                ".java")) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            Matcher literal = LITERAL.matcher(text);
            while (literal.find()) {
                String message = literal.group(1);
                Matcher mention = MENTION.matcher(message);
                while (mention.find()) {
                    String rest = message.substring(mention.end());
                    String problem = problemWith(menus, mention.group(1), rest);
                    if (problem != null) {
                        wrong.add(source.getName() + ": \"" + quote(mention.group(), rest)
                                + "\" - " + problem);
                    }
                }
            }
        }
        assertEquals("[]", wrong.toString(),
                "a message sends the user to a menu item that is not there");
    }

    /** The same for the pages a user reads, which drifted further than the messages had. */
    @Test
    void everyMenuPathOnAPageExists() throws Exception {
        Place[] menus = menus();
        List<File> pages = filesUnder(new File("../docs"), ".md");
        pages.add(new File("../README.md"));
        assertTrue(pages.size() > 10, "the documentation was not found: " + pages);
        List<String> wrong = new ArrayList<String>();
        for (File page : pages) {
            if (HISTORY.contains(page.getName()) || page.getPath().contains("superpowers")
                    || page.getPath().contains(File.separator + "study" + File.separator)) {
                continue;
            }
            List<String> lines = Files.readAllLines(page.toPath(), StandardCharsets.UTF_8);
            for (int number = 0; number < lines.size(); number++) {
                String line = lines.get(number).replace("*", "").replace("`", "");
                Matcher mention = MENTION.matcher(line);
                while (mention.find()) {
                    String rest = line.substring(mention.end());
                    String problem = problemWith(menus, mention.group(1), rest);
                    if (problem != null) {
                        wrong.add(page.getName() + ":" + (number + 1) + " \""
                                + quote(mention.group(), rest) + "\" - " + problem);
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), "a page sends the reader to a menu item that is not there:\n"
                + String.join("\n", wrong));
    }

    /** The walk itself, on paths known to be right and wrong. */
    @Test
    void aPathIsFollowedSubmenuBySubmenu() throws Exception {
        Place[] menus = menus();
        assertNull(problemWith(menus, "OntoBoard", "Build and release > Build..., choose test"));
        assertNull(problemWith(menus, "Build and release", "Build... > test"));
        assertNull(problemWith(menus, "OntoBoard", "Share"));
        assertNull(problemWith(menus, "Tools", "New ODK Project..."));
        assertNotNull(problemWith(menus, "OntoBoard", "Build..."), "Build is not at the top");
        assertNotNull(problemWith(menus, "Project", "Build..."), "nor under Project");
        assertNotNull(problemWith(menus, "ROBOT", "Explain..."), "there is no ROBOT menu");
        assertNotNull(problemWith(menus, "OntoBoard", "Project >"), "a path cut in two");
    }

    /** The specific sentence that was wrong, pinned so it cannot drift back. */
    @Test
    void theGitModeExplanationNamesTheRealMenu() {
        String explanation = de.fizkarlsruhe.ise.ontoboard.collab.CollabSettings.offline()
                .explainWhyNotLive();
        assertNotNull(explanation);
        assertTrue(explanation.contains("OntoBoard > Share > Collaboration"), explanation);
        assertFalse(explanation.contains("Tools >"),
                "there is no Tools > Collaboration Settings, and never was: " + explanation);
    }

    private static String quote(String start, String rest) {
        return (start + rest.substring(0, Math.min(50, rest.length()))).trim();
    }

    private static List<File> filesUnder(File directory, String suffix) throws IOException {
        List<File> found = new ArrayList<File>();
        File[] children = directory.listFiles();
        if (children == null) {
            return found;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                found.addAll(filesUnder(child, suffix));
            } else if (child.getName().endsWith(suffix)) {
                found.add(child);
            }
        }
        return found;
    }
}
