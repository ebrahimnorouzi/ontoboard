package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@code catalog-v001.xml} - what makes an import resolve to a local file.
 *
 * <p>An import statement names an IRI. Without a catalog entry, every tool that opens the ontology
 * tries to fetch that IRI over the network: slow when it works, and a wall of "could not load
 * imported ontology" when it does not - which for a project's own import modules, whose IRIs
 * nobody has ever published, is always. The catalog is the file that maps the IRI to
 * {@code imports/whatever_import.owl} on disk, and both Protege and ROBOT read it.
 *
 * <p>Writing it is therefore part of adding an import module, not an optional extra. An extraction
 * that saved the module and added the import statement without this would produce a project that
 * works in the session that made it and fails for everyone who checks it out - the worst shape of
 * bug, because the person who caused it never sees it.
 *
 * <p>Pure XML; no Protege types and no Swing.
 */
public final class Catalog {

    /** What Protege writes for a hand-added mapping, matched so the two agree. */
    private static final String ENTRY_ID = "User Entered Import Resolution";

    private static final String NAMESPACE = "urn:oasis:names:tc:entity:xmlns:xml:catalog";

    private Catalog() {
    }

    /**
     * An empty catalog, for a project that has none.
     *
     * <p>Matches what the scaffold writes, so a project created here and a project created by the
     * ODK end up with the same file.
     */
    public static String empty() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<catalog prefer=\"public\" xmlns=\"" + NAMESPACE + "\">\n"
                + "</catalog>\n";
    }

    /**
     * The catalog with {@code importIri} mapped to {@code path}.
     *
     * <p>Idempotent by IRI: re-importing the same module replaces its entry rather than adding a
     * second one, because two entries for one IRI is a file where the answer depends on which
     * reader you use.
     *
     * @param existing the current file contents, or null/empty for a new catalog
     * @param importIri the IRI as it appears in the import statement
     * @param path where the file is, relative to the catalog
     */
    public static String withEntry(String existing, String importIri, String path) {
        if (importIri == null || importIri.trim().isEmpty()) {
            throw new IllegalArgumentException("a catalog entry needs an import IRI");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("a catalog entry needs a path to map " + importIri
                    + " to");
        }
        String source = existing == null || existing.trim().isEmpty() ? empty() : existing;
        Document document;
        try {
            document = parse(source);
        } catch (Exception notXml) {
            throw new IllegalStateException("catalog-v001.xml could not be read as XML: "
                    + notXml.getMessage(), notXml);
        }

        Element root = document.getDocumentElement();
        Element entry = findEntry(document, importIri.trim());
        if (entry == null) {
            entry = document.createElementNS(NAMESPACE, "uri");
            entry.setAttribute("id", ENTRY_ID);
            entry.setAttribute("name", importIri.trim());
            root.appendChild(entry);
        }
        // Forward slashes even on Windows: the catalog is committed and read on other machines,
        // and a backslash in it resolves nowhere on any of them.
        entry.setAttribute("uri", path.trim().replace('\\', '/'));
        return serialise(document);
    }

    /**
     * Reads a catalog, without letting it reach out to anything.
     *
     * <p>A catalog is XML from a repository somebody cloned off the internet, so it is untrusted
     * input. Left at its defaults a {@code DocumentBuilderFactory} will happily fetch an external
     * DTD named in a {@code <!DOCTYPE>} - an outbound request to a host of the document author's
     * choosing, made silently the moment a project is opened, and a block of unbounded length when
     * that host does not answer. A catalog has no legitimate use for a doctype at all, so the
     * cheapest fix is also the completest one: refuse documents that contain one.
     */
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        for (String feature : new String[] {
                "http://apache.org/xml/features/disallow-doctype-decl",
        }) {
            factory.setFeature(feature, true);
        }
        for (String feature : new String[] {
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        }) {
            try {
                factory.setFeature(feature, false);
            } catch (javax.xml.parsers.ParserConfigurationException notSupported) {
                // Already covered by disallowing doctypes; a parser that does not know the
                // feature is not a parser that will act on one.
                continue;
            }
        }
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    /**
     * Drops whitespace-only text nodes.
     *
     * <p>Without this the file grows every single time it is written. The parsed document keeps
     * the source file's own indentation as text nodes, and a Transformer with {@code INDENT=yes}
     * then adds its own indentation <em>around</em> them - so each pass doubles what is there.
     * Measured on a real ODK catalog: 798 bytes and 12 lines to begin with, 4166 bytes and 185
     * lines after twelve entries, with the ODK-managed group reformatted too. A committed file
     * that its own build regenerates then conflicts on every merge.
     */
    private static void stripWhitespace(org.w3c.dom.Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    && child.getNodeValue() != null
                    && child.getNodeValue().trim().isEmpty()) {
                node.removeChild(child);
            } else if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                stripWhitespace(child);
            }
        }
    }

    /** The {@code uri} entry for this IRI, or null. */
    private static Element findEntry(Document document, String importIri) {
        NodeList entries = document.getElementsByTagNameNS(NAMESPACE, "uri");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (importIri.equals(entry.getAttribute("name"))) {
                return entry;
            }
        }
        // A catalog written without a namespace still has to be found, or the entry is added
        // twice and the file quietly stops meaning one thing.
        entries = document.getElementsByTagName("uri");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (importIri.equals(entry.getAttribute("name"))) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Every import IRI the catalog maps.
     *
     * <p>So a caller can ask the other question - not "where does this IRI point" but "what else
     * already points at this file", which is how you find out that writing a module here would
     * take somebody else's.
     */
    public static java.util.List<String> mappedIris(String catalogXml) {
        java.util.List<String> iris = new ArrayList<String>();
        if (catalogXml == null || catalogXml.trim().isEmpty()) {
            return iris;
        }
        try {
            Document document = parse(catalogXml);
            for (NodeList entries : new NodeList[] {
                    document.getElementsByTagNameNS(NAMESPACE, "uri"),
                    document.getElementsByTagName("uri")}) {
                for (int i = 0; i < entries.getLength(); i++) {
                    String name = ((Element) entries.item(i)).getAttribute("name");
                    if (name != null && !name.isEmpty() && !iris.contains(name)) {
                        iris.add(name);
                    }
                }
            }
        } catch (Exception notXml) {
            return iris;
        }
        return iris;
    }

    /** Where {@code importIri} currently points, or null when the catalog says nothing about it. */
    public static String entryFor(String catalogXml, String importIri) {
        if (catalogXml == null || catalogXml.trim().isEmpty() || importIri == null) {
            return null;
        }
        try {
            Element entry = findEntry(parse(catalogXml), importIri.trim());
            return entry == null ? null : entry.getAttribute("uri");
        } catch (Exception notXml) {
            return null;
        }
    }

    /**
     * Adds the entry to the catalog beside an ontology, creating the file if there is none.
     *
     * @param catalogFile normally {@code catalog-v001.xml} in the same directory as the ontology
     */
    public static void addEntry(File catalogFile, String importIri, String path)
            throws IOException {
        String existing = catalogFile.isFile()
                ? new String(Files.readAllBytes(catalogFile.toPath()), Charset.forName("UTF-8"))
                : null;
        Files.write(catalogFile.toPath(),
                withEntry(existing, importIri, path).getBytes("UTF-8"));
    }

    private static String serialise(Document document) {
        try {
            // Before indenting, not after: the file's own indentation is in the tree as text
            // nodes, and indenting around it is what made every write bigger than the last.
            stripWhitespace(document.getDocumentElement());
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            String xml = out.toString();
            return xml.endsWith("\n") ? xml : xml + "\n";
        } catch (Exception cannotWrite) {
            throw new IllegalStateException("could not write the catalog: "
                    + cannotWrite.getMessage(), cannotWrite);
        }
    }
}
