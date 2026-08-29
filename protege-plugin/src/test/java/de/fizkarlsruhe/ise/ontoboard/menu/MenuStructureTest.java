package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * That the OntoBoard menu is actually a menu.
 *
 * <p>{@code PluginXmlTest} proves every declared class exists. It cannot prove the entry is
 * reachable, and that is the failure that actually happens: Protege builds its menus by matching a
 * child's {@code <path>} against its parent's {@code <bundleSymbolicName>.<extensionId>}, and when
 * they do not match it neither warns nor logs - the entry is simply not there. A working class
 * wired to a mistyped parent looks exactly like a feature that was never written, and the only way
 * to find out is to start Protege and look.
 *
 * <p>So the hierarchy is checked here, along with the thing that is invisible from the XML alone:
 * that every action class in the source tree is on the menu at all. An action nobody registered is
 * dead code that a reviewer reading plugin.xml has no way to notice.
 */
class MenuStructureTest {

    /** The bundle symbolic name every internal path is prefixed with. */
    private static final String BUNDLE = "ontoboard";

    /**
     * Protege's own menus, which are legitimate parents.
     *
     * <p>Listed rather than resolved: they live in Protege's plugin.xml, not ours, so nothing on
     * the test classpath can confirm them. Naming them at least means a path to something that is
     * neither ours nor a known Protege menu is caught.
     */
    private static final Set<String> PROTEGE_MENUS = new HashSet<String>(java.util.Arrays.asList(
            "org.protege.editor.owl.menu.tools",
            "org.protege.editor.owl.menu.reasoner",
            "org.protege.editor.owl.menu.refactor",
            "org.protege.editor.core.menu.file",
            "org.protege.editor.core.menu.edit",
            "org.protege.editor.core.menu.view",
            "org.protege.editor.core.menu.window",
            "org.protege.editor.core.menu.help"));

    private List<Element> menuExtensions() throws Exception {
        Document document;
        try (InputStream in = getClass().getResourceAsStream("/plugin.xml")) {
            assertNotNull(in, "plugin.xml missing from the built classpath");
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        NodeList extensions = document.getElementsByTagName("extension");
        List<Element> menus = new ArrayList<Element>();
        for (int i = 0; i < extensions.getLength(); i++) {
            Element extension = (Element) extensions.item(i);
            if (extension.getAttribute("point")
                    .endsWith("application.EditorKitMenuAction")) {
                menus.add(extension);
            }
        }
        assertFalse(menus.isEmpty(), "no menu entries at all in plugin.xml");
        return menus;
    }

    /** The single child element's {@code value}, or null. */
    private static String valueOf(Element extension, String tag) {
        NodeList found = extension.getElementsByTagName(tag);
        return found.getLength() == 0 ? null : ((Element) found.item(0)).getAttribute("value");
    }

    // ---------- the hierarchy holds together ----------

    /**
     * The failure this whole class exists for: a child whose path names a parent that is not
     * there. Protege drops it in silence.
     */
    @Test
    void everyMenuEntryHangsOffSomethingThatExists() throws Exception {
        List<Element> menus = menuExtensions();
        Set<String> declaredIds = new HashSet<String>();
        for (Element extension : menus) {
            declaredIds.add(BUNDLE + "." + extension.getAttribute("id"));
        }

        for (Element extension : menus) {
            String path = valueOf(extension, "path");
            assertNotNull(path, extension.getAttribute("id") + " has no path, so it goes nowhere");
            if (path.startsWith("/")) {
                // A root path - a top-level menu beside File, Edit and Tools.
                continue;
            }
            String parent = path.substring(0, path.lastIndexOf('/'));
            assertTrue(declaredIds.contains(parent) || PROTEGE_MENUS.contains(parent),
                    extension.getAttribute("id") + " hangs off '" + parent
                            + "', which is neither one of ours " + new TreeSet<String>(declaredIds)
                            + " nor a Protege menu. Protege will not show it and will not say so.");
        }
    }

    /** Two extensions sharing an id means one of them silently replaces the other. */
    @Test
    void noTwoExtensionsShareAnId() throws Exception {
        Set<String> seen = new HashSet<String>();
        for (Element extension : menuExtensions()) {
            String id = extension.getAttribute("id");
            assertFalse(id.trim().isEmpty(), "an extension with no id cannot be a menu parent");
            assertTrue(seen.add(id), "two extensions are called '" + id + "'");
        }
    }

    /** Two entries in the same slot means one of them is not drawn. */
    @Test
    void noTwoMenuEntriesClaimTheSameSlot() throws Exception {
        Map<String, String> byPath = new HashMap<String, String>();
        for (Element extension : menuExtensions()) {
            String path = valueOf(extension, "path");
            String clash = byPath.put(path, extension.getAttribute("id"));
            assertTrue(clash == null, "'" + path + "' is claimed by both " + clash + " and "
                    + extension.getAttribute("id") + "; one of them will not appear");
        }
    }

    /**
     * A submenu with no children is an empty menu a user opens and finds nothing in - and it is
     * what is left behind when an entry's path is changed and its parent forgotten.
     */
    @Test
    void noSubmenuIsEmpty() throws Exception {
        List<Element> menus = menuExtensions();
        Set<String> parents = new HashSet<String>();
        for (Element extension : menus) {
            String path = valueOf(extension, "path");
            if (!path.startsWith("/")) {
                parents.add(path.substring(0, path.lastIndexOf('/')));
            }
        }
        for (Element extension : menus) {
            if (valueOf(extension, "class") != null) {
                continue; // an action, not a container
            }
            String id = BUNDLE + "." + extension.getAttribute("id");
            assertTrue(parents.contains(id), valueOf(extension, "name")
                    + " is a submenu with nothing in it");
        }
    }

    // ---------- every entry is legible ----------

    @Test
    void everyMenuEntryHasAName() throws Exception {
        for (Element extension : menuExtensions()) {
            String name = valueOf(extension, "name");
            assertNotNull(name, extension.getAttribute("id") + " has no name");
            assertFalse(name.trim().isEmpty(), extension.getAttribute("id") + " has a blank name");
        }
    }

    /**
     * Every entry that does something explains what, because a menu of verbs with no tooltips
     * makes a user find out what "Reduce" does by running it on their ontology.
     */
    @Test
    void everyActionSaysWhatItDoesBeforeYouClickIt() throws Exception {
        for (Element extension : menuExtensions()) {
            if (valueOf(extension, "class") == null) {
                continue; // a container; its children carry the explanation
            }
            String tip = valueOf(extension, "toolTip");
            assertNotNull(tip, valueOf(extension, "name") + " has no tooltip");
            assertTrue(tip.trim().length() > 20,
                    valueOf(extension, "name") + " has a tooltip that explains nothing: " + tip);
            assertFalse(tip.trim().equalsIgnoreCase(valueOf(extension, "name").replace("...", "")),
                    valueOf(extension, "name") + "'s tooltip just repeats its label");
        }
    }

    /**
     * An entry that opens a dialog is spelled with an ellipsis and one that acts immediately is
     * not. It is the convention every desktop menu follows and the only warning a user gets that
     * clicking will change something straight away.
     */
    @Test
    void entriesThatOpenADialogAreMarkedWithAnEllipsis() throws Exception {
        int checked = 0;
        for (Element extension : menuExtensions()) {
            String className = valueOf(extension, "class");
            if (className == null) {
                continue;
            }
            Class<?> type = Class.forName(className);
            if (!OntoBoardAction.class.isAssignableFrom(type)) {
                // Whether an action built on something other than OntoBoardAction opens a dialog
                // is not visible from here - it can do it anywhere inside actionPerformed. Only
                // the base class makes the question answerable, by separating configure() out.
                continue;
            }
            String name = valueOf(extension, "name");
            boolean asksFirst = opensADialogFirst(type);
            assertTrue(asksFirst == name.endsWith("..."),
                    name + (asksFirst
                            ? " asks for parameters first, so it should end in '...'"
                            : " acts immediately, so it should not end in '...'"));
            checked++;
        }
        assertTrue(checked >= 4, "only " + checked + " entries were checked, so this test is "
                + "close to proving nothing - has the base class changed?");
    }

    /**
     * Whether this action puts a dialog in front of the user before it does anything.
     *
     * <p>There are two ways to do that on {@link OntoBoardAction} and both count. Overriding
     * {@code configure} is one: it runs on the dispatch thread, asks, and then works in the
     * background. Overriding {@code runsInBackground} to false is the other, and by the base
     * class's own contract that is what it means - the work stays on the dispatch thread
     * precisely because it opens a dialog itself.
     */
    private static boolean opensADialogFirst(Class<?> type) throws Exception {
        if (overridesConfigure(type)) {
            return true;
        }
        Object action = type.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method staysOnTheEventThread =
                OntoBoardAction.class.getDeclaredMethod("runsInBackground");
        staysOnTheEventThread.setAccessible(true);
        return !((Boolean) staysOnTheEventThread.invoke(action)).booleanValue();
    }

    /** Whether anything below {@link OntoBoardAction} overrides {@code configure}. */
    private static boolean overridesConfigure(Class<?> type) {
        for (Class<?> level = type; level != null && !level.equals(OntoBoardAction.class);
                level = level.getSuperclass()) {
            try {
                level.getDeclaredMethod("configure");
                return true;
            } catch (NoSuchMethodException notHere) {
                continue;
            }
        }
        return false;
    }

    // ---------- nothing written is left unwired ----------

    /**
     * An action class that is never registered is invisible: it compiles, it is tested, and no
     * user can reach it. Nothing else catches that, because a plugin.xml missing an entry is
     * indistinguishable from a plugin.xml that never had one.
     */
    @Test
    void everyActionInTheSourceTreeIsOnTheMenu() throws Exception {
        Set<String> registered = new HashSet<String>();
        for (Element extension : menuExtensions()) {
            String className = valueOf(extension, "class");
            if (className != null) {
                registered.add(className);
            }
        }

        Set<String> unwired = new LinkedHashSet<String>();
        for (String className : actionClassesOnDisk()) {
            if (!registered.contains(className)) {
                unwired.add(className);
            }
        }
        assertTrue(unwired.isEmpty(),
                "written but not reachable from any menu: " + unwired);
    }

    /**
     * Every concrete {@link OntoBoardAction} in the source tree, found by walking the sources.
     *
     * <p>By file rather than by classpath scanning, because a scanner needs a dependency this
     * build does not have and because the source tree is the thing a reviewer is looking at.
     */
    private static List<String> actionClassesOnDisk() {
        List<String> classes = new ArrayList<String>();
        File root = new File("src/main/java/de/fizkarlsruhe/ise/ontoboard");
        assertTrue(root.isDirectory(), "cannot find the source tree at " + root.getAbsolutePath());
        collect(root, "de.fizkarlsruhe.ise.ontoboard", classes);
        assertFalse(classes.isEmpty(), "found no action classes at all, so this test proves "
                + "nothing - the search is broken, not the code");
        return classes;
    }

    private static void collect(File directory, String packageName, List<String> classes) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collect(entry, packageName + "." + entry.getName(), classes);
                continue;
            }
            if (!entry.getName().endsWith("Action.java")) {
                continue;
            }
            String className = packageName + "."
                    + entry.getName().substring(0, entry.getName().length() - ".java".length());
            try {
                Class<?> type = Class.forName(className);
                if (!java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                        && org.protege.editor.owl.ui.action.ProtegeOWLAction.class
                                .isAssignableFrom(type)) {
                    classes.add(className);
                }
            } catch (Throwable notLoadable) {
                // A class that will not load is a different problem, and PluginXmlTest reports it
                // for anything that is registered. Skipping it here keeps this test about wiring.
            }
        }
    }
}
