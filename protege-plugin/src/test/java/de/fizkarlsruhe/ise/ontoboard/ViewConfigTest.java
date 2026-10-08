package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Guards the OntoBoard tab's layout, every part of which fails silently rather than loudly.
 *
 * <p>Protege reads {@code viewconfig-ontoboardtab.xml} through a SAX handler that ignores what it
 * does not recognise, so a mistake here never throws. A misspelt {@code pluginId} produces an
 * empty panel; the wrong splitter node produces a working tab with the panels in the wrong
 * places. Both look like a plugin that "sort of works", which is why they need asserting rather
 * than eyeballing - the orientation bug this class now pins shipped once already.
 */
class ViewConfigTest {

    private static final String CONFIG = "/viewconfig-ontoboardtab.xml";
    private static final String CLASS_HIERARCHY = "org.protege.editor.owl.OWLAssertedClassHierarchy";
    private static final String CANVAS_SUFFIX = ".SchemaCanvasView";

    // ---------- reading the config ----------

    private Element layoutRoot() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(CONFIG)) {
            assertNotNull(in, CONFIG + " missing from the built classpath");
            Document document =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            Element layout = document.getDocumentElement();
            assertEquals("layout", layout.getTagName());
            return layout;
        }
    }

    /** Direct child elements of {@code parent}, skipping comments and whitespace text. */
    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private static Element onlyChild(Element parent) {
        List<Element> found = children(parent);
        assertEquals(1, found.size(),
                "expected exactly one child of <" + parent.getTagName() + ">, found " + found);
        return found.get(0);
    }

    /** The {@code pluginId} each {@code <Component>} under {@code parent} points at, in order. */
    private static List<String> pluginIdsIn(Element parent) {
        List<String> ids = new ArrayList<>();
        NodeList components = parent.getElementsByTagName("Component");
        for (int i = 0; i < components.getLength(); i++) {
            NodeList properties = ((Element) components.item(i)).getElementsByTagName("Property");
            for (int p = 0; p < properties.getLength(); p++) {
                Element property = (Element) properties.item(p);
                if ("pluginId".equals(property.getAttribute("id"))) {
                    ids.add(property.getAttribute("value"));
                }
            }
        }
        return ids;
    }

    // ---------- orientation ----------

    /**
     * The single most confusable line in the layout. In Protege's mdock format the node names
     * describe the divider, not the arrangement: a VSNode is a <em>vertical splitter</em>, so its
     * children sit side by side. HSNode reads like "horizontal row" but stacks its children
     * instead, which is how the hierarchy ended up underneath the canvas. Protege's own
     * viewconfig-classestab.xml and viewconfig-entitiestab.xml both use VSNode for their
     * left-hand tree column, and the bytecode agrees - VerticalSplitter sets a W_RESIZE cursor,
     * HorizontalSplitter an N_RESIZE one.
     */
    @Test
    void theTwoColumnsAreSplitSideBySideAndNotStacked() throws Exception {
        Element split = onlyChild(layoutRoot());
        assertEquals("VSNode", split.getTagName(),
                "HSNode stacks its children; the hierarchy must sit beside the canvas, not above"
                        + " it");
    }

    @Test
    void theEntityColumnIsOnTheLeftAndTheCanvasOnTheRight() throws Exception {
        List<Element> columns = children(onlyChild(layoutRoot()));
        assertEquals(2, columns.size(), "expected an entity column and a canvas column");

        assertTrue(pluginIdsIn(columns.get(0)).contains(CLASS_HIERARCHY),
                "the first column is the left one and must hold the class hierarchy: "
                        + pluginIdsIn(columns.get(0)));
        List<String> right = pluginIdsIn(columns.get(1));
        assertEquals(2, right.size(),
                "the right column holds the canvas and the sheet editor as tabs: " + right);
        assertTrue(right.get(0).endsWith(CANVAS_SUFFIX),
                "the canvas comes first, so it is the one shown on opening: " + right.get(0));
        assertTrue(right.get(1).endsWith("SheetEditorView"), right.get(1));
    }

    /**
     * The canvas and the sheet editor share one column rather than halving it.
     *
     * <p>Two components in one {@code CNode} render as tabs, which is what the entity views in
     * the left column already do. They are alternatives - somebody is drawing a diagram or
     * filling in a spreadsheet - so giving each half the width would leave both cramped for no
     * gain. Splitting them into two {@code CNode}s would do exactly that, which is why this is
     * pinned rather than left to whoever edits the layout next.
     */
    @Test
    void theCanvasAndTheSheetEditorAreTabsInOneColumnRatherThanTwoColumns() throws Exception {
        List<Element> columns = children(onlyChild(layoutRoot()));

        assertEquals(1, children(columns.get(1)).size() > 0 ? 1 : 0,
                "the right column is one node");
        assertEquals("CNode", columns.get(1).getTagName(),
                "a splitter here would halve the width instead of making tabs");
    }

    /** A narrow tree column is the point; an even split would leave the canvas cramped. */
    @Test
    void theEntityColumnIsTheNarrowerOfTheTwo() throws Exception {
        String[] splits = onlyChild(layoutRoot()).getAttribute("splits").trim().split("\\s+");
        assertEquals(2, splits.length, "one split fraction per column");
        double left = Double.parseDouble(splits[0]);
        double right = Double.parseDouble(splits[1]);
        assertTrue(left < right, "the tree column should be narrower: " + left + " vs " + right);
        assertEquals(1.0, left + right, 0.001, "the fractions should cover the tab");
    }

    // ---------- tabs, not more panels ----------

    /**
     * Six views in six CNodes would be six slivers of a 28%-wide column. One CNode holding six
     * Components is what makes mdock render them as tabs, so the count of CNodes in the left
     * column is load-bearing rather than stylistic.
     */
    @Test
    void everyEntityViewSharesOneNodeSoTheyRenderAsTabs() throws Exception {
        Element left = children(onlyChild(layoutRoot())).get(0);
        assertEquals("CNode", left.getTagName(),
                "a splitter here would divide the column into panels instead of tabs");
        assertEquals(0, left.getElementsByTagName("CNode").getLength(),
                "a nested CNode would split the column again instead of adding a tab");
        assertTrue(pluginIdsIn(left).size() >= 4,
                "expected the class, property and individual views to share the column");
    }

    /**
     * The user-visible promise of the tab: classes, both kinds of property, and individuals are
     * all reachable without leaving it.
     */
    @Test
    void classesPropertiesAndIndividualsAreAllReachableFromTheTab() throws Exception {
        List<String> ids = pluginIdsIn(children(onlyChild(layoutRoot())).get(0));
        for (String required : new String[] {CLASS_HIERARCHY,
            "org.protege.editor.owl.OWLObjectPropertyTree",
            "org.protege.editor.owl.OWLDataPropertyTree",
            "org.protege.editor.owl.OWLAnnotationPropertyTree",
            "org.protege.editor.owl.OWLIndividualsList"}) {
            assertTrue(ids.contains(required), "missing " + required + " from " + ids);
        }
    }

    /** Every tab needs a name; mdock would otherwise render a blank one. */
    @Test
    void everyComponentIsLabelled() throws Exception {
        NodeList components = layoutRoot().getElementsByTagName("Component");
        assertTrue(components.getLength() >= 2);
        for (int i = 0; i < components.getLength(); i++) {
            String label = ((Element) components.item(i)).getAttribute("label");
            assertFalse(label.trim().isEmpty(), "unlabelled Component at index " + i);
        }
    }

    // ---------- the ids actually resolve ----------

    /**
     * The check that cannot be made by reading: a {@code pluginId} is resolved by Protege at
     * runtime against the extension registry, so a typo or a view that a supported host does not
     * ship yields an empty panel and no log line. Every id is therefore matched against the
     * {@code ViewComponent} extensions actually declared on the test classpath, which includes
     * the {@code protege-editor-owl} jar the plugin compiles against.
     */
    @Test
    void everyReferencedViewIsDeclaredByABundleOnTheClasspath() throws Exception {
        Set<String> declared = declaredViewComponentIds();
        assertTrue(declared.contains(CLASS_HIERARCHY),
                "the registry scan found no Protege views at all, so this test would pass"
                        + " vacuously; found " + declared.size() + " ids");

        for (String id : pluginIdsIn(layoutRoot())) {
            assertTrue(declared.contains(id),
                    "no bundle on the classpath declares a ViewComponent with id " + id);
        }
    }

    /**
     * Qualified ids of every {@code ViewComponent} extension declared on the classpath.
     *
     * <p>Protege addresses a view as {@code <bundle symbolic name>.<extension id>}, so each
     * {@code plugin.xml} found is paired with the symbolic name of the bundle that contains it.
     * Our own {@code plugin.xml} comes from {@code target/classes}, where the manifest is only
     * written at package time, so its name is taken from the pom instead.
     */
    private Set<String> declaredViewComponentIds() throws Exception {
        Set<String> ids = new HashSet<>();
        Enumeration<URL> found = getClass().getClassLoader().getResources("plugin.xml");
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            String bundle = symbolicNameOf(url);
            if (bundle == null) {
                continue;
            }
            Document document;
            try (InputStream in = url.openStream()) {
                document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            }
            NodeList extensions = document.getElementsByTagName("extension");
            for (int i = 0; i < extensions.getLength(); i++) {
                Element extension = (Element) extensions.item(i);
                if (extension.getAttribute("point")
                        .equals("org.protege.editor.core.application.ViewComponent")) {
                    ids.add(bundle + "." + extension.getAttribute("id"));
                }
            }
        }
        return ids;
    }

    /** {@code Bundle-SymbolicName} of the artifact holding {@code url}, without its directives. */
    private String symbolicNameOf(URL url) throws Exception {
        String path = url.toString();
        if (path.startsWith("jar:")) {
            URL manifest =
                    new URL(path.substring(0, path.indexOf("!/") + 2) + "META-INF/MANIFEST.MF");
            try (InputStream in = manifest.openStream()) {
                String name = new Manifest(in).getMainAttributes().getValue("Bundle-SymbolicName");
                return name == null ? null : name.split(";")[0].trim();
            }
        }
        // Ours, straight out of target/classes: no manifest yet, so ask the pom.
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("pom.xml"));
        for (Element child : children(pom.getDocumentElement())) {
            if ("artifactId".equals(child.getTagName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    // ---------- packaging ----------

    /**
     * Resource filtering substitutes {@code ${project.artifactId}} so the canvas id matches the
     * bundle symbolic name. If filtering is ever switched off the token survives into the jar and
     * the canvas panel comes up empty.
     */
    @Test
    void noMavenPropertySurvivesIntoTheBuiltConfig() throws Exception {
        List<String> ids = pluginIdsIn(layoutRoot());
        assertTrue(ids.size() >= 2, "expected at least the hierarchy and the canvas");
        for (String id : ids) {
            assertFalse(id.contains("${"), "unfiltered Maven property in view config: " + id);
        }
    }
}
