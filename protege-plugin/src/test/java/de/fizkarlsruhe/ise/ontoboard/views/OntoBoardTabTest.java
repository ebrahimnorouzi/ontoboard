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

    /** plugin.xml names the same file independently, and the two must not drift apart. */
    @Test
    void pluginXmlDeclaresTheConfigTheTabReads() throws Exception {
        Document plugin;
        try (InputStream in = OntoBoardTab.class.getResourceAsStream("/plugin.xml")) {
            assertNotNull(in);
            plugin = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        NodeList declared = plugin.getElementsByTagName("defaultViewConfigFileName");
        assertEquals(1, declared.getLength());
        assertEquals(OntoBoardTab.CONFIG,
                "/" + ((Element) declared.item(0)).getAttribute("value"));
    }
}
