package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/**
 * The file that makes an import resolve without the network.
 *
 * <p>An import module's IRI has never been published anywhere, so without a catalog entry every
 * tool that opens the project tries to fetch it and fails. The failure has a nasty shape: whoever
 * created the module has it loaded in their session and sees nothing wrong, and the breakage shows
 * up for the next person to check the repository out.
 */
class CatalogTest {

    private static final String NAMESPACE = "urn:oasis:names:tc:entity:xmlns:xml:catalog";

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(
                new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    // ---------- an entry gets written ----------

    @Test
    void anEntryMapsTheImportIriToTheLocalFile() {
        String catalog = Catalog.withEntry(null,
                "http://purl.obolibrary.org/obo/mwo/imports/iao_import.owl",
                "imports/iao_import.owl");

        assertEquals("imports/iao_import.owl", Catalog.entryFor(catalog,
                "http://purl.obolibrary.org/obo/mwo/imports/iao_import.owl"));
    }

    @Test
    void theResultIsStillAValidCatalogDocument() throws Exception {
        String catalog = Catalog.withEntry(null, "http://example.org/i.owl", "imports/i.owl");

        Document document = parse(catalog);

        assertEquals("catalog", document.getDocumentElement().getLocalName());
        assertEquals(NAMESPACE, document.getDocumentElement().getNamespaceURI());
        assertEquals(1, document.getElementsByTagNameNS(NAMESPACE, "uri").getLength());
    }

    /** Protege writes this id for a mapping a person added, and agreeing with it avoids churn. */
    @Test
    void theEntryIsLabelledTheWayProtegeLabelsOne() {
        String catalog = Catalog.withEntry(null, "http://example.org/i.owl", "imports/i.owl");

        assertTrue(catalog.contains("User Entered Import Resolution"), catalog);
    }

    // ---------- adding to what is already there ----------

    @Test
    void anExistingEntryForAnotherImportSurvives() {
        String first = Catalog.withEntry(null, "http://example.org/a.owl", "imports/a.owl");

        String both = Catalog.withEntry(first, "http://example.org/b.owl", "imports/b.owl");

        assertEquals("imports/a.owl", Catalog.entryFor(both, "http://example.org/a.owl"));
        assertEquals("imports/b.owl", Catalog.entryFor(both, "http://example.org/b.owl"));
    }

    /**
     * Two entries for one IRI is a file whose meaning depends on which reader you use, and
     * re-running an import is a completely ordinary thing to do.
     */
    @Test
    void reImportingReplacesTheEntryRatherThanAddingASecond() throws Exception {
        String once = Catalog.withEntry(null, "http://example.org/a.owl", "imports/old.owl");

        String twice = Catalog.withEntry(once, "http://example.org/a.owl", "imports/new.owl");

        assertEquals(1, parse(twice).getElementsByTagNameNS(NAMESPACE, "uri").getLength());
        assertEquals("imports/new.owl", Catalog.entryFor(twice, "http://example.org/a.owl"));
    }

    /** A catalog written without the namespace still has to be recognised, or entries double up. */
    @Test
    void anEntryInACatalogWrittenWithoutTheNamespaceIsStillFound() throws Exception {
        String plain = "<?xml version=\"1.0\"?>\n<catalog prefer=\"public\">\n"
                + "  <uri id=\"x\" name=\"http://example.org/a.owl\" uri=\"imports/old.owl\"/>\n"
                + "</catalog>\n";

        String updated = Catalog.withEntry(plain, "http://example.org/a.owl", "imports/new.owl");

        assertEquals(1, parse(updated).getElementsByTagName("uri").getLength());
        assertEquals("imports/new.owl", Catalog.entryFor(updated, "http://example.org/a.owl"));
    }

    /** The scaffold writes an empty catalog with a comment; adding to it must not lose the file. */
    @Test
    void theScaffoldsEmptyCatalogTakesAnEntry() {
        String scaffolded = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<catalog prefer=\"public\" xmlns=\"" + NAMESPACE + "\">\n"
                + "    <!-- Map import IRIs to local files in imports/ as you add them. -->\n"
                + "</catalog>\n";

        String updated = Catalog.withEntry(scaffolded, "http://example.org/a.owl",
                "imports/a.owl");

        assertEquals("imports/a.owl", Catalog.entryFor(updated, "http://example.org/a.owl"));
    }

    // ---------- paths that work on somebody else's machine ----------

    /**
     * The catalog is committed and read on Linux CI. A Windows path separator in it resolves
     * nowhere, and the person who wrote it never sees the failure.
     */
    @Test
    void aWindowsPathIsWrittenWithForwardSlashes() {
        String catalog = Catalog.withEntry(null, "http://example.org/a.owl",
                "imports\\iao_import.owl");

        assertEquals("imports/iao_import.owl",
                Catalog.entryFor(catalog, "http://example.org/a.owl"));
        assertFalse(catalog.contains("\\"), catalog);
    }

    // ---------- refusals ----------

    @Test
    void anEntryWithNoIriOrNoPathIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Catalog.withEntry(null, "", "imports/a.owl"));
        assertThrows(IllegalArgumentException.class,
                () -> Catalog.withEntry(null, "http://example.org/a.owl", ""));
        assertThrows(IllegalArgumentException.class,
                () -> Catalog.withEntry(null, null, "imports/a.owl"));
    }

    @Test
    void aCatalogThatIsNotXmlIsARefusalRatherThanASilentlyReplacedFile() {
        assertThrows(IllegalStateException.class,
                () -> Catalog.withEntry("this is not xml at all", "http://example.org/a.owl",
                        "imports/a.owl"));
    }

    @Test
    void askingAboutAnIriTheCatalogDoesNotMentionGivesNothing() {
        String catalog = Catalog.withEntry(null, "http://example.org/a.owl", "imports/a.owl");

        assertNull(Catalog.entryFor(catalog, "http://example.org/b.owl"));
        assertNull(Catalog.entryFor(null, "http://example.org/a.owl"));
        assertNull(Catalog.entryFor("", "http://example.org/a.owl"));
    }

    // ---------- on disk ----------

    @Test
    void writingCreatesTheFileWhenThereIsNone(@TempDir File directory) throws Exception {
        File catalog = new File(directory, "catalog-v001.xml");

        Catalog.addEntry(catalog, "http://example.org/a.owl", "imports/a.owl");

        assertTrue(catalog.isFile());
        assertEquals("imports/a.owl", Catalog.entryFor(
                new String(Files.readAllBytes(catalog.toPath()), Charset.forName("UTF-8")),
                "http://example.org/a.owl"));
    }

    @Test
    void writingTwiceLeavesOneEntryEach(@TempDir File directory) throws Exception {
        File catalog = new File(directory, "catalog-v001.xml");

        Catalog.addEntry(catalog, "http://example.org/a.owl", "imports/a.owl");
        Catalog.addEntry(catalog, "http://example.org/b.owl", "imports/b.owl");

        String xml = new String(Files.readAllBytes(catalog.toPath()), Charset.forName("UTF-8"));
        assertEquals(2, parse(xml).getElementsByTagNameNS(NAMESPACE, "uri").getLength());
    }
}
