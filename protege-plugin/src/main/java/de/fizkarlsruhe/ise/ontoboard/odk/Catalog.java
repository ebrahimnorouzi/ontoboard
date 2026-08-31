package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
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

    /**
     * The document as text, written directly rather than through a Transformer.
     *
     * <p>Two reasons, and the second is the one that decided it.
     *
     * <p>{@code javax.xml.transform} is an <em>optional</em> import in this bundle's manifest,
     * while {@code javax.xml.parsers} and {@code org.w3c.dom} are required. An optional package
     * that turns out not to be exported by the host's OSGi framework does not stop the bundle
     * resolving - it fails later, as a {@code NoClassDefFoundError} the first time somebody
     * imports terms. Not depending on it at all removes the question.
     *
     * <p>And a catalog is a file in version control that a project's own build also writes.
     * Controlling the output exactly means the same input produces the same bytes, so re-running
     * an import that changes nothing produces no diff. A Transformer's indentation depends on the
     * parser's whitespace handling, which is how this file used to grow every time it was written.
     */
    private static String serialise(Document document) {
        StringBuilder out = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        write(document.getDocumentElement(), 0, out);
        return out.toString();
    }

    private static void write(Element element, int depth, StringBuilder out) {
        String indent = indent(depth);
        out.append(indent).append('<').append(element.getNodeName());
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            org.w3c.dom.Node attribute = attributes.item(i);
            out.append(' ').append(attribute.getNodeName()).append("=\"")
                    .append(escape(attribute.getNodeValue())).append('"');
        }

        List<org.w3c.dom.Node> children = significantChildrenOf(element);
        if (children.isEmpty()) {
            out.append("/>\n");
            return;
        }
        out.append(">\n");
        for (org.w3c.dom.Node child : children) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                write((Element) child, depth + 1, out);
            } else if (child.getNodeType() == org.w3c.dom.Node.COMMENT_NODE) {
                out.append(indent(depth + 1)).append("<!--").append(child.getNodeValue())
                        .append("-->\n");
            } else {
                out.append(indent(depth + 1))
                        .append(escape(child.getNodeValue().trim())).append('\n');
            }
        }
        out.append(indent).append("</").append(element.getNodeName()).append(">\n");
    }

    /** Children that carry meaning: elements, comments, and text that is not just layout. */
    private static List<org.w3c.dom.Node> significantChildrenOf(Element element) {
        List<org.w3c.dom.Node> children = new ArrayList<org.w3c.dom.Node>();
        NodeList all = element.getChildNodes();
        for (int i = 0; i < all.getLength(); i++) {
            org.w3c.dom.Node child = all.item(i);
            short type = child.getNodeType();
            if (type == org.w3c.dom.Node.ELEMENT_NODE
                    || type == org.w3c.dom.Node.COMMENT_NODE) {
                children.add(child);
            } else if ((type == org.w3c.dom.Node.TEXT_NODE
                    || type == org.w3c.dom.Node.CDATA_SECTION_NODE)
                    && child.getNodeValue() != null
                    && !child.getNodeValue().trim().isEmpty()) {
                children.add(child);
            }
        }
        return children;
    }

    private static String indent(int depth) {
        StringBuilder spaces = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            spaces.append("    ");
        }
        return spaces.toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
