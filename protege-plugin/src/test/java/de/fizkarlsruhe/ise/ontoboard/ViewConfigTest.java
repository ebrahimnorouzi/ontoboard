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
        assertEquals(1, right.size(), "the right column is the canvas, alone: " + right);
        assertTrue(right.get(0).endsWith(CANVAS_SUFFIX), right.get(0));
    }

    /**
     * The canvas has this column to itself, and the sheet editor is in a tab of its own.
     *
     * <p>The history is worth keeping because both obvious tidy-ups have been tried and both
     * are wrong. Putting the two views in one {@code CNode} as tabs - the arrangement the six
     * entity views opposite use - is a thing <b>Protege does not do for plugin views</b>: it
     * instantiated the canvas and silently dropped the sheet editor, which was then registered,
     * resolvable, and never constructed. Stacking them in an {@code HSNode} did build both, and
     * gave a twenty-six-column spreadsheet 45% of the height of a 72%-wide column.
     *
     * <p>So the sheet editor moved to its own workspace tab, which is the mechanism that gives
     * each of them the window. This pins the OntoBoard tab against somebody stacking it back
     * in, and {@link #theSheetEditorHasItsOwnConfigAndHasTheWholeOfIt} pins the other side.
     */
    @Test
    void theCanvasHasTheRightColumnToItselfAndTheSheetEditorIsElsewhere() throws Exception {
        Element right = children(onlyChild(layoutRoot())).get(1);

        assertEquals("CNode", right.getTagName(),
                "the canvas is alone here; a splitter would mean something was stacked back in");
        assertEquals(1, pluginIdsIn(right).size(), pluginIdsIn(right).toString());
        assertFalse(pluginIdsIn(layoutRoot()).toString().contains("SheetEditorView"),
                "the sheet editor belongs to " + SHEETS_CONFIG + " now; two tabs placing the "
                        + "same view means two of it, each with its own unsaved edits");
    }

    // ---------- the sheets tab ----------

    private static final String SHEETS_CONFIG = "/viewconfig-sheetstab.xml";

    private Element sheetsLayoutRoot() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SHEETS_CONFIG)) {
            assertNotNull(in, SHEETS_CONFIG + " missing from the built classpath");
            Document document =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            Element layout = document.getDocumentElement();
            assertEquals("layout", layout.getTagName());
            return layout;
        }
    }

    /**
     * One view, the whole tab. Space is the only reason this tab exists, so anything else placed
     * in it is taking back what it was made for.
     */
    @Test
    void theSheetEditorHasItsOwnConfigAndHasTheWholeOfIt() throws Exception {
        List<String> ids = pluginIdsIn(sheetsLayoutRoot());

        assertEquals(1, ids.size(), "the sheets tab holds the sheet editor and nothing else: "
                + ids);
        assertTrue(ids.get(0).endsWith(".SheetEditorView"), ids.get(0));
    }

    /**
     * The root has to be a splitter even with one view in it.
     *
     * <p>A bare {@code <CNode>} at the root parses - the SAX handler ignores what it does not
     * recognise - and then throws while the tab is being built. {@code TabbedWorkspace.addTab}
     * catches that, <em>removes the tab from the tabbed pane</em>, puts an error panel in its
     * place and logs nothing; {@code addTabForPlugin} still returns the tab, so the next call
     * fails with "component not found in tabbed pane" and nothing says why. Shipped in a jar,
     * caught by the host smoke, invisible to 2,276 unit tests.
     *
     * <p>Protege's own one-view tab is the reference: {@code viewconfig-explanationtab.xml} in
     * protege-editor-owl is a {@code VSNode} with a single {@code splits} value and one
     * {@code CNode} inside.
     */
    @Test
    void everyConfigRootIsASplitterBecauseABareNodeBreaksTheTab() throws Exception {
        for (Element root : new Element[] {layoutRoot(), sheetsLayoutRoot()}) {
            Element child = onlyChild(root);
            assertTrue("VSNode".equals(child.getTagName()) || "HSNode".equals(child.getTagName()),
                    "the root of a view config must be a splitter, not " + child.getTagName());
            assertFalse(child.getAttribute("splits").trim().isEmpty(),
                    "a splitter with no splits leaves mdock nothing to divide by");
        }
    }

    @Test
    void theSheetsConfigNamesARealViewAndCarriesNoUnfilteredProperty() throws Exception {
        List<String> ids = pluginIdsIn(sheetsLayoutRoot());
        for (String id : ids) {
            assertFalse(id.contains("${"), "unfiltered Maven property: " + id);
        }
        assertTrue(declaredViewComponentIds().containsAll(ids),
                "no bundle on the classpath declares " + ids);
    }

    @Test
    void everyComponentInTheSheetsConfigIsLabelled() throws Exception {
        NodeList components = sheetsLayoutRoot().getElementsByTagName("Component");
        assertTrue(components.getLength() >= 1);
        for (int i = 0; i < components.getLength(); i++) {
            assertFalse(((Element) components.item(i)).getAttribute("label").trim().isEmpty(),
                    "unlabelled Component at index " + i);
        }
    }

    /**
     * plugin.xml and the two configs have to agree, in both directions: a tab whose
     * {@code defaultViewConfigFileName} names a file that is not packaged comes up empty, and a
     * config that is packaged but named by no tab is dead weight nobody notices.
     */
    @Test
    void everyTabsConfigIsPackagedAndEveryPackagedConfigBelongsToATab() throws Exception {
        Document plugin;
        try (InputStream in = getClass().getResourceAsStream("/plugin.xml")) {
            assertNotNull(in);
            plugin = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        NodeList declared = plugin.getElementsByTagName("defaultViewConfigFileName");
        assertEquals(2, declared.getLength(),
                "two workspace tabs, two configs - the canvas and the sheets");
        Set<String> named = new HashSet<>();
        for (int i = 0; i < declared.getLength(); i++) {
            String file = "/" + ((Element) declared.item(i)).getAttribute("value");
            named.add(file);
            try (InputStream in = getClass().getResourceAsStream(file)) {
                assertNotNull(in, file + " is named by a tab but is not on the classpath");
            }
        }
        assertTrue(named.contains(CONFIG) && named.contains(SHEETS_CONFIG), named.toString());
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
     * Components is what makes mdock render them as tabs, so where the CNode boundaries fall in
     * the left column is load-bearing rather than stylistic.
     *
     * <p>Since 1.122.0 the column is a splitter with exactly two panels: the six trees as tabs,
     * and the axioms of the selected term underneath them. Two, not seven - a seventh tab beside
     * the trees would have been free and is the thing to resist, because it hides the tree exactly
     * when a reader is using the tree to pick what to read about.
     */
    @Test
    void theTreesShareOneNodeAndTheAxiomsPaneSitsUnderThem() throws Exception {
        Element left = children(onlyChild(layoutRoot())).get(0);
        assertEquals("HSNode", left.getTagName(),
                "the trees and the axioms pane are stacked, so this is a horizontal splitter");

        List<Element> panels = children(left);
        assertEquals(2, panels.size(), "the trees and the axioms pane, and nothing else");
        for (Element panel : panels) {
            assertEquals("CNode", panel.getTagName(),
                    "a splitter here would divide a panel into slivers instead of tabs");
        }
        assertTrue(pluginIdsIn(panels.get(0)).size() >= 4,
                "expected the class, property and individual views to share the upper panel: "
                        + pluginIdsIn(panels.get(0)));
        assertEquals(0, panels.get(0).getElementsByTagName("CNode").getLength(),
                "a nested CNode would split the tree panel again instead of adding a tab");
        assertEquals(java.util.Arrays.asList(
                        "org.protege.editor.owl.SelectedEntityView",
                        "org.protege.editor.owl.ManchesterSyntaxFrameView"),
                pluginIdsIn(panels.get(1)),
                "the lower panel is what the selected term says: the editable frames first, the "
                        + "read-only Manchester text second. Not the per-type description views - "
                        + "there are nineteen of them and they do not switch by entity type - and "
                        + "the editable one has to come first, because a pane that can only be "
                        + "read is what shipped in 1.122.0 and is not what the tab is for");
    }

    /** Both panels in the left column get a share, and the editable one enough to edit in. */
    @Test
    void thePaneForTheSelectedTermGetsEnoughOfTheLeftColumn() throws Exception {
        Element left = children(onlyChild(layoutRoot())).get(0);
        String[] splits = left.getAttribute("splits").trim().split("\\s+");

        assertEquals(2, splits.length, "one split fraction per panel");
        double trees = Double.parseDouble(splits[0]);
        double details = Double.parseDouble(splits[1]);
        // SelectedEntityCardView spends its own height on an inner splitter - annotations over
        // description - so half of a narrow column is the floor at which both halves are usable.
        assertTrue(details >= 0.45, "the editable frames need the room: " + details);
        assertTrue(trees >= 0.35, "and the tree is still what a reader navigates with: " + trees);
        assertEquals(1.0, trees + details, 0.001, "the fractions should cover the column");
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
