package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

/**
 * A project's XML catalog after an update, as ODK's own update writes it.
 *
 * <p>ODK owns one group of the catalog, {@code odk-managed-catalog}, and writes it from its
 * template; every other entry is the project's - Protégé adds one whenever somebody resolves
 * an import by hand - and is kept, after ODK's group, unless ODK's group now has the very same
 * entry. This is {@code update_xml_catalog} of ODK v1.6.1's odk.py, and the text it returns is
 * the text Python's ElementTree writes for it, since a commit shows every character that
 * differs: the declaration in single quotes, {@code xmlns} first on the root, two spaces of
 * indentation, a blank before {@code />}, no line break after the last tag.
 *
 * <p>Like ODK's update it reads the catalog without its comments, and they are not kept.
 *
 * <p>Two catalogs are refused, with the reason, where ODK's update would go on: one with a
 * document type declaration, which OntoBoard never reads ({@code Catalog} has the same rule
 * for the same file), and one with an element or attribute of another vocabulary, which
 * ElementTree writes with prefixes of its own making that are not ported here.
 */
public final class CatalogMerge {

    private static final String FILE = "catalog-v001.xml";
    private static final String CATALOG_NS = "urn:oasis:names:tc:entity:xmlns:xml:catalog";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final String MANAGED = "odk-managed-catalog";
    private static final String SPACE = "  ";
    private static final String ODKS_OWN_CAN = "ODK's own update merges such a catalog, in "
            + "ODK's container (Docker or Podman with ODK's image) - or ";

    private CatalogMerge() {
    }

    /**
     * @param rendered       ODK's template for the catalog, rendered for the project
     * @param existingOrNull the catalog as the project has it, or null when it has none
     * @return the catalog to write
     * @throws IllegalArgumentException when the project's catalog is not one this can merge as
     *                                  ODK does; the message says why and what to do
     */
    public static String merge(String rendered, String existingOrNull) {
        Element root = parse(rendered, "ODK's template for " + FILE);
        if (existingOrNull != null) {
            Element group = null;
            for (Element child : root.children) {
                if (child.is("group")) {
                    group = child;
                    break;
                }
            }
            if (group == null) {
                throw new IllegalStateException("ODK's template for " + FILE + " has no group.");
            }
            Set<String> odks = new HashSet<String>();
            for (Element entry : group.children) {
                if (entry.is("uri")) {
                    odks.add(entry.attribute("name") + "@" + entry.attribute("uri"));
                }
            }
            Element existing = parse(existingOrNull, "This project's " + FILE);
            keepTheirs(existing, odks);
            root.children.addAll(existing.children);
        }
        indent(root, 0);
        StringBuilder out = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n");
        write(root, out, true);
        return out.toString();
    }

    /** {@code process_children}: ODK's old group goes, and so does what its new one has. */
    private static void keepTheirs(Element node, Set<String> odks) {
        List<Element> kept = new ArrayList<Element>();
        for (Element child : node.children) {
            if (child.is("uri")) {
                String name = child.attribute("name");
                String uri = child.attribute("uri");
                if (name != null && !name.isEmpty() && uri != null && !uri.isEmpty()
                        && odks.contains(name + "@" + uri)) {
                    continue;
                }
            } else if (child.is("group")) {
                if (MANAGED.equals(child.attribute("id"))) {
                    continue;
                }
                // An empty xml:base is not XML's to have; ODK takes it off.
                child.dropEmptyXmlBase();
                keepTheirs(child, odks);
            }
            kept.add(child);
        }
        node.children.clear();
        node.children.addAll(kept);
    }

    // ------------------------------------------------------------------ the tree

    private static final class Attribute {
        private final String namespace;
        private final String name;
        private final String value;

        Attribute(String namespace, String name, String value) {
            this.namespace = namespace == null ? "" : namespace;
            this.name = name;
            this.value = value;
        }
    }

    /** An element as ElementTree has it: its text is what precedes its first child. */
    private static final class Element {
        private final String namespace;
        private final String name;
        /** In the order written. */
        private final List<Attribute> attributes = new ArrayList<Attribute>();
        private final List<Element> children = new ArrayList<Element>();
        private String text = "";
        /** What follows its end tag, up to the next element. */
        private String tail = "";

        Element(String namespace, String name) {
            this.namespace = namespace == null ? "" : namespace;
            this.name = name;
        }

        boolean is(String catalogName) {
            return CATALOG_NS.equals(namespace) && catalogName.equals(name);
        }

        /** The value of an attribute that is in no namespace, or null. */
        String attribute(String plain) {
            for (Attribute attribute : attributes) {
                if (attribute.namespace.isEmpty() && attribute.name.equals(plain)) {
                    return attribute.value;
                }
            }
            return null;
        }

        void dropEmptyXmlBase() {
            for (int i = 0; i < attributes.size(); i++) {
                Attribute attribute = attributes.get(i);
                if (XML_NS.equals(attribute.namespace) && attribute.name.equals("base")
                        && attribute.value.isEmpty()) {
                    attributes.remove(i);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /** A document type declaration was met: the reading stops there. */
    private static final class HasADocumentType extends SAXException {
        private static final long serialVersionUID = 1L;

        HasADocumentType() {
            super("document type declaration");
        }
    }

    /**
     * Reads a catalog, without letting it reach out to anything.
     *
     * <p>A catalog comes with a repository somebody cloned, and a document type declaration can
     * name a file to fetch from a host of the author's choosing. The reading therefore stops at
     * the declaration itself, before anything it names is looked at.
     */
    private static Element parse(String xml, String what) {
        final Element[] root = {null};
        final List<Element> open = new ArrayList<Element>();
        final StringBuilder characters = new StringBuilder();
        DefaultHandler2 handler = new DefaultHandler2() {
            /** Characters belong to the element before them, or to their parent's start. */
            private void flush() {
                if (!open.isEmpty()) {
                    Element parent = open.get(open.size() - 1);
                    if (parent.children.isEmpty()) {
                        parent.text += characters;
                    } else {
                        parent.children.get(parent.children.size() - 1).tail += characters;
                    }
                }
                characters.setLength(0);
            }

            @Override
            public void startDTD(String name, String publicId, String systemId)
                    throws SAXException {
                throw new HasADocumentType();
            }

            @Override
            public void startElement(String uri, String localName, String qName,
                    Attributes attributes) {
                flush();
                Element element = new Element(uri, localName);
                for (int i = 0; i < attributes.getLength(); i++) {
                    element.attributes.add(new Attribute(attributes.getURI(i),
                            attributes.getLocalName(i), attributes.getValue(i)));
                }
                if (open.isEmpty()) {
                    root[0] = element;
                } else {
                    open.get(open.size() - 1).children.add(element);
                }
                open.add(element);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                flush();
                open.remove(open.size() - 1);
            }

            @Override
            public void characters(char[] chars, int start, int length) {
                characters.append(chars, start, length);
            }

            @Override
            public void error(SAXParseException problem) throws SAXException {
                throw problem;
            }

            @Override
            public void fatalError(SAXParseException problem) throws SAXException {
                throw problem;
            }
        };
        SAXParser parser;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parser = factory.newSAXParser();
            // Without this the declaration would go unseen, so a parser that cannot be told
            // of it is not one to read a catalog with.
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        } catch (ParserConfigurationException | SAXException noParser) {
            throw new IllegalStateException("This Java has no XML parser that reads a catalog "
                    + "safely: " + noParser.getMessage(), noParser);
        }
        try {
            parser.parse(new InputSource(new StringReader(xml)), handler);
        } catch (HasADocumentType declared) {
            throw new IllegalArgumentException(what + " has a document type declaration "
                    + "(<!DOCTYPE ...>). OntoBoard reads no catalog that has one, since it can "
                    + "name a file to fetch from anywhere. " + ODKS_OWN_CAN
                    + "take the declaration out.", declared);
        } catch (SAXException broken) {
            String where = broken instanceof SAXParseException
                    ? "line " + ((SAXParseException) broken).getLineNumber() + ": " : "";
            throw new IllegalArgumentException(what + " cannot be read as XML (" + where
                    + broken.getMessage() + "), so it cannot be merged with ODK's. Put it right "
                    + "in a text editor first.", broken);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return root[0];
    }

    // ------------------------------------------------------------------ writing

    /**
     * ElementTree's {@code indent}: an element that has children gets them one to a line; one
     * that has none is left as it was read - so an empty group whose tags stood on two lines
     * still does. Text that is not blank is nobody's to move.
     */
    private static void indent(Element element, int level) {
        if (element.children.isEmpty()) {
            return;
        }
        String own = "\n" + repeated(level);
        String inner = "\n" + repeated(level + 1);
        if (PythonText.isBlank(element.text)) {
            element.text = inner;
        }
        for (Element child : element.children) {
            indent(child, level + 1);
            if (PythonText.isBlank(child.tail)) {
                child.tail = inner;
            }
        }
        Element last = element.children.get(element.children.size() - 1);
        if (PythonText.isBlank(last.tail)) {
            last.tail = own;
        }
    }

    private static String repeated(int level) {
        StringBuilder spaces = new StringBuilder();
        for (int i = 0; i < level; i++) {
            spaces.append(SPACE);
        }
        return spaces.toString();
    }

    /**
     * ElementTree's writing of an element, with short empty elements. A name that is in no
     * namespace is written as it is, as ElementTree does - inside this root that puts it into
     * the catalog's.
     */
    private static void write(Element element, StringBuilder out, boolean isRoot) {
        if (!element.namespace.isEmpty() && !CATALOG_NS.equals(element.namespace)) {
            throw new IllegalArgumentException("This project's " + FILE + " has an element '"
                    + element.name + "' of another vocabulary, " + element.namespace
                    + ", and OntoBoard does not write that the way ODK's update would. "
                    + ODKS_OWN_CAN + "take the element out.");
        }
        out.append('<').append(element.name);
        if (isRoot) {
            out.append(" xmlns=\"").append(inAttribute(CATALOG_NS)).append('"');
        }
        for (Attribute attribute : element.attributes) {
            if (!attribute.namespace.isEmpty() && !XML_NS.equals(attribute.namespace)) {
                throw new IllegalArgumentException("This project's " + FILE + " has an "
                        + "attribute '" + attribute.name + "' of another vocabulary, "
                        + attribute.namespace + ", and OntoBoard does not write that the way "
                        + "ODK's update would. " + ODKS_OWN_CAN + "take the attribute out.");
            }
            out.append(' ').append(attribute.namespace.isEmpty() ? "" : "xml:")
                    .append(attribute.name).append("=\"").append(inAttribute(attribute.value))
                    .append('"');
        }
        if (!element.text.isEmpty() || !element.children.isEmpty()) {
            out.append('>').append(inText(element.text));
            for (Element child : element.children) {
                write(child, out, false);
            }
            out.append("</").append(element.name).append('>');
        } else {
            out.append(" />");
        }
        out.append(inText(element.tail));
    }

    private static String inText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String inAttribute(String value) {
        return inText(value).replace("\"", "&quot;").replace("\r", "&#13;")
                .replace("\n", "&#10;").replace("\t", "&#09;");
    }
}
