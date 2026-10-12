package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@link OntoBoardTab} itself needs a live workspace and cannot be constructed here, so what is
 * tested is the part that decides whether a reset happens at all. If the digest were unstable the
 * tab would reset on every launch and throw away the user's arrangement; if it never changed, a
 * new layout would never reach anyone. Both are silent.
 */
class OntoBoardTabTest {

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSameLayoutAlwaysYieldsTheSameRevision() throws Exception {
        assertEquals(OntoBoardTab.revisionOf(bytes("<layout/>")),
                OntoBoardTab.revisionOf(bytes("<layout/>")),
                "an unstable digest would reset the tab on every launch");
    }

    @Test
    void aChangedLayoutYieldsADifferentRevision() throws Exception {
        assertNotEquals(OntoBoardTab.revisionOf(bytes("<layout><VSNode/></layout>")),
                OntoBoardTab.revisionOf(bytes("<layout><HSNode/></layout>")),
                "a new layout that digests the same would never be adopted");
    }

    /** Content longer than the 8 KB read buffer must be digested in full, not just its head. */
    @Test
    void aChangeBeyondTheFirstBufferStillChangesTheRevision() throws Exception {
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            padding.append('x');
        }
        assertNotEquals(OntoBoardTab.revisionOf(bytes(padding + "a")),
                OntoBoardTab.revisionOf(bytes(padding + "b")));
    }

    @Test
    void aRevisionIsShortEnoughToReadInAPreferenceEditor() throws Exception {
        String revision = OntoBoardTab.revisionOf(bytes("<layout/>"));
        assertEquals(16, revision.length());
        assertTrue(revision.matches("[0-9a-f]+"), revision);
    }

    /**
     * The tab reads the config by the name in {@link OntoBoardTab#CONFIG}. Renaming the file, or
     * dropping resource filtering, leaves that lookup returning null - at which point the tab
     * quietly stops adopting new layouts and every future layout change ships to nobody.
     */
    @Test
    void theShippedConfigIsReadableByTheNameTheTabUses() throws Exception {
        try (InputStream in = OntoBoardTab.class.getResourceAsStream(OntoBoardTab.CONFIG)) {
            assertNotNull(in, OntoBoardTab.CONFIG + " is not on the built classpath");
            assertEquals(16, OntoBoardTab.revisionOf(in).length());
        }
    }

    /**
     * plugin.xml names each config independently of the class that reads it, and the two must
     * not drift apart - a tab whose class reads a file no tab declares stops adopting new
     * layouts, silently, and every future layout change then ships to nobody.
     */
    @Test
    void pluginXmlDeclaresTheConfigEachTabReads() throws Exception {
        Document plugin;
        try (InputStream in = OntoBoardTab.class.getResourceAsStream("/plugin.xml")) {
            assertNotNull(in);
            plugin = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        NodeList declared = plugin.getElementsByTagName("defaultViewConfigFileName");
        assertEquals(2, declared.getLength(), "two workspace tabs, two configs");
        java.util.List<String> named = new java.util.ArrayList<>();
        for (int at = 0; at < declared.getLength(); at++) {
            named.add("/" + ((Element) declared.item(at)).getAttribute("value"));
        }
        assertTrue(named.contains(OntoBoardTab.CONFIG), named.toString());
        assertTrue(named.contains(SheetsTab.CONFIG), named.toString());
    }

    /**
     * Each tab keys its own preference, or the two fight: adopting one layout would write the
     * digest the other reads, and whichever opened second would decide there was nothing to do.
     * The digests differ because the configs differ, which is the whole mechanism.
     */
    @Test
    void theTwoTabsDigestTwoDifferentLayouts() throws Exception {
        String canvas;
        String sheets;
        try (InputStream in = OntoBoardTab.class.getResourceAsStream(OntoBoardTab.CONFIG)) {
            assertNotNull(in, OntoBoardTab.CONFIG + " is not on the built classpath");
            canvas = OntoBoardTab.revisionOf(in);
        }
        try (InputStream in = OntoBoardTab.class.getResourceAsStream(SheetsTab.CONFIG)) {
            assertNotNull(in, SheetsTab.CONFIG + " is not on the built classpath");
            sheets = OntoBoardTab.revisionOf(in);
        }
        assertNotEquals(canvas, sheets);
    }

    /**
     * What each tab must contain, read from its own layout rather than from plugin.xml.
     *
     * <p>Requiring every registered view in every tab was right while there was one tab and
     * became wrong the moment the sheet editor got its own: the canvas tab would then have
     * reset itself on every launch, for ever, hunting a view that is deliberately elsewhere.
     */
    @Test
    void eachLayoutAsksForExactlyTheViewItPlaces() throws Exception {
        assertEquals(java.util.Collections.singletonList("SchemaCanvasView"),
                ShippedLayout.viewsNamedIn(OntoBoardTab.CONFIG));
        assertEquals(java.util.Collections.singletonList("SheetEditorView"),
                ShippedLayout.viewsNamedIn(SheetsTab.CONFIG));
    }

    /** A config that is not there asks for nothing rather than throwing on startup. */
    @Test
    void aMissingConfigAsksForNothing() throws Exception {
        assertTrue(ShippedLayout.viewsNamedIn("/viewconfig-nothing-like-this.xml").isEmpty());
    }
}
