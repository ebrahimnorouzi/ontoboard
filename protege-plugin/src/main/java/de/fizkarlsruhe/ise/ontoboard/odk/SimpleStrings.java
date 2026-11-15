package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A string in an ontology file, always in its simple form: {@code "x"}, never
 * {@code "x"^^xsd:string}; in RDF/XML without {@code rdf:datatype} for a string.
 *
 * <p>The two are one literal, and the OWL API treats them as one: its data factory, of which a
 * process has one, hands back whichever of the two it met first. What it then <em>writes</em>
 * differs by version. ODK's (4.5.29, also Protege 5.6.9's) leaves a string's datatype out,
 * always - in functional syntax, in RDF/XML and in Turtle. Protege 5.5.0's (4.5.9) writes the
 * datatype for the one form and not for the other - so the same ontology comes out as
 * {@code "1.0.0"} or as {@code "1.0.0"^^xsd:string} by what else Protege loaded before. Seen: an
 * import module built twice in one process, byte for byte ODK's the first time and one line
 * off the second.
 *
 * <p>So what OntoBoard's engine has ROBOT write is brought to the simple form here: as ODK
 * writes it, and the same from one run to the next. Nothing that reads the file can tell the
 * difference.
 *
 * <p>Each syntax is read as the OWL API writes it, and only as far as is needed to know where
 * a string ends. Functional syntax: a line that starts with {@code #} is a comment,
 * {@code <...>} is an IRI, a string runs to the next quotation mark that has no backslash
 * before it, over line breaks. Turtle: the same, with a comment anywhere after {@code #} and
 * the four ways to quote a string. RDF/XML: the attribute is taken off inside a tag only, so
 * that text which happens to say {@code rdf:datatype="..."} stays what it is.
 */
final class SimpleStrings {

    private static final String XML_SCHEMA = "http://www.w3.org/2001/XMLSchema#";
    private static final String IN_FULL = "<" + XML_SCHEMA + "string>";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final Pattern OFN_PREFIX = Pattern.compile("(?m)^[ \\t]*Prefix\\([ \\t]*"
            + "([^:\\s()<>\"]*):[ \\t]*=[ \\t]*<" + Pattern.quote(XML_SCHEMA) + ">[ \\t]*\\)");
    private static final Pattern TURTLE_PREFIX = Pattern.compile("(?mi)^[ \\t]*@?prefix[ \\t]+"
            + "([^:\\s<>\"]*):[ \\t]*<" + Pattern.quote(XML_SCHEMA) + ">");
    private static final Pattern XML_ENTITY = Pattern.compile("<!ENTITY\\s+([A-Za-z_][\\w.-]*)"
            + "\\s+\"" + Pattern.quote(XML_SCHEMA) + "\"\\s*>");
    private static final Pattern RDF_PREFIX = Pattern.compile("xmlns:([A-Za-z_][\\w.-]*)\\s*=\\s*"
            + "\"" + Pattern.quote(RDF) + "\"");
    /** How much of a file is read to see which syntax it is. */
    private static final int HEAD = 4096;

    private enum Syntax {
        FUNCTIONAL, TURTLE, RDF_XML, OTHER
    }

    private SimpleStrings() {
    }

    /**
     * Rewrites a file that names the datatype of a string.
     *
     * @return whether the file was rewritten; false for a file that is not there, is none of
     *         the three syntaxes, or has nothing to change
     */
    static boolean simplify(File file) throws IOException {
        if (!file.isFile()) {
            return false;
        }
        byte[] head = new byte[HEAD];
        int read;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            read = in.read(head);
        }
        if (read <= 0 || syntaxOf(new String(head, 0, read, StandardCharsets.UTF_8))
                == Syntax.OTHER) {
            return false;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String simple = simplified(text);
        if (simple.equals(text)) {
            return false;
        }
        Files.write(file.toPath(), simple.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /** The text with every string in its simple form; text of another kind as it is. */
    static String simplified(String text) {
        switch (syntaxOf(text)) {
            case FUNCTIONAL:
                return quoted(text, false, OFN_PREFIX);
            case TURTLE:
                return quoted(text, true, TURTLE_PREFIX);
            case RDF_XML:
                return rdfXml(text);
            default:
                return text;
        }
    }

    /** By the first thing in the text that is no comment and no blank. */
    private static Syntax syntaxOf(String text) {
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '#') {
                int end = text.indexOf('\n', at);
                at = end < 0 ? text.length() : end;
            } else if (Character.isWhitespace(c) || c == '\uFEFF') {
                at++;
            } else if (text.startsWith("Prefix(", at) || text.startsWith("Ontology(", at)) {
                return Syntax.FUNCTIONAL;
            } else if (text.regionMatches(true, at, "@prefix", 0, 7)
                    || text.regionMatches(true, at, "@base", 0, 5)) {
                return Syntax.TURTLE;
            } else if (c == '<') {
                // XML whose root element is rdf:RDF; the OWL API writes it within the head.
                return text.contains("<rdf:RDF") ? Syntax.RDF_XML : Syntax.OTHER;
            } else {
                return Syntax.OTHER;
            }
        }
        return Syntax.OTHER;
    }

    // ------------------------------------------------------------------ functional syntax, Turtle

    /**
     * @param turtle whether a comment starts at any {@code #} outside a string and an IRI, and
     *               a string may be quoted in Turtle's four ways; else functional syntax
     */
    private static String quoted(String text, boolean turtle, Pattern prefixes) {
        List<String> named = new ArrayList<String>();
        named.add(IN_FULL);
        Matcher prefix = prefixes.matcher(text);
        while (prefix.find()) {
            named.add(prefix.group(1) + ":string");
        }
        boolean any = false;
        for (String name : named) {
            any |= text.contains("^^" + name);
        }
        if (!any) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        boolean lineStart = true;
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '#' && (turtle || lineStart)) {
                int end = text.indexOf('\n', at);
                end = end < 0 ? text.length() : end;
                out.append(text, at, end);
                at = end;
            } else if (c == '<') {
                int end = text.indexOf('>', at);
                end = end < 0 ? text.length() : end + 1;
                out.append(text, at, end);
                at = end;
                lineStart = false;
            } else if (c == '"' || (turtle && c == '\'')) {
                int end = endOfString(text, at, turtle);
                if (end < 0) {
                    // Broken off inside a string: not this class's to mend.
                    return text;
                }
                out.append(text, at, end);
                at = end;
                lineStart = false;
                int datatype = datatypeNamedAt(text, at, named);
                if (datatype > 0) {
                    at += datatype;
                    changed = true;
                }
            } else {
                out.append(c);
                at++;
                lineStart = c == '\n' || (lineStart && (c == ' ' || c == '\t' || c == '\r'));
            }
        }
        return changed ? out.toString() : text;
    }

    /** Where the string that starts at this quotation mark ends, past its closing one; or -1. */
    private static int endOfString(String text, int start, boolean turtle) {
        char quote = text.charAt(start);
        boolean longString = turtle && text.startsWith("" + quote + quote + quote, start);
        int at = start + (longString ? 3 : 1);
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\\') {
                at += 2;
            } else if (c == quote && (!longString
                    || text.startsWith("" + quote + quote + quote, at))) {
                return at + (longString ? 3 : 1);
            } else {
                at++;
            }
        }
        return -1;
    }

    /** The length of {@code ^^} and the string datatype named after it here, or 0. */
    private static int datatypeNamedAt(String text, int at, List<String> named) {
        if (!text.startsWith("^^", at)) {
            return 0;
        }
        for (String name : named) {
            if (text.startsWith(name, at + 2)) {
                int after = at + 2 + name.length();
                // xsd:string, and not a longer name that starts like it.
                if (name.equals(IN_FULL) || after >= text.length()
                        || !isPartOfAName(text.charAt(after))) {
                    return 2 + name.length();
                }
            }
        }
        return 0;
    }

    private static boolean isPartOfAName(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '%';
    }

    // ------------------------------------------------------------------ RDF/XML

    /**
     * RDF/XML without {@code rdf:datatype} for a string: the attribute taken out of the tags
     * that carry it, its value the datatype in full or through the document's own entity for
     * XML Schema. What stands between tags, in comments and in the document type is copied.
     */
    private static String rdfXml(String text) {
        Matcher rdf = RDF_PREFIX.matcher(text);
        if (!rdf.find()) {
            return text;
        }
        List<String> attributes = new ArrayList<String>();
        attributes.add(rdf.group(1) + ":datatype=\"" + XML_SCHEMA + "string\"");
        Matcher entity = XML_ENTITY.matcher(text);
        while (entity.find()) {
            attributes.add(rdf.group(1) + ":datatype=\"&" + entity.group(1) + ";string\"");
        }
        boolean any = false;
        for (String attribute : attributes) {
            any |= text.contains(attribute);
        }
        if (!any) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('<', at);
            if (open < 0) {
                out.append(text, at, text.length());
                break;
            }
            out.append(text, at, open);
            int end;
            if (text.startsWith("<!--", open)) {
                end = closing(text, "-->", open);
            } else if (text.startsWith("<![CDATA[", open)) {
                end = closing(text, "]]>", open);
            } else if (text.startsWith("<?", open)) {
                end = closing(text, "?>", open);
            } else if (text.startsWith("<!", open)) {
                // The document type, with the entities it may declare between [ and ].
                int subset = text.indexOf('[', open);
                int plain = text.indexOf('>', open);
                end = subset >= 0 && plain >= 0 && subset < plain ? closing(text, "]>", open)
                        : closing(text, ">", open);
            } else {
                end = endOfTag(text, open);
                if (end < 0) {
                    return text;
                }
                changed |= tagWithoutTheAttribute(text, open, end, attributes, out);
                at = end;
                continue;
            }
            if (end < 0) {
                return text;
            }
            out.append(text, open, end);
            at = end;
        }
        return changed ? out.toString() : text;
    }

    /** Past the next occurrence of what closes a piece of markup; -1 when it is not closed. */
    private static int closing(String text, String closes, int from) {
        int at = text.indexOf(closes, from);
        return at < 0 ? -1 : at + closes.length();
    }

    /** Past the {@code >} that ends the tag starting here; a quoted value may hold one. */
    private static int endOfTag(String text, int start) {
        char quote = 0;
        for (int at = start + 1; at < text.length(); at++) {
            char c = text.charAt(at);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return at + 1;
            }
        }
        return -1;
    }

    /**
     * Copies the tag that stands from {@code open} to {@code end}, without the attribute and
     * the blanks before it where it has one.
     *
     * @return whether it had one
     */
    private static boolean tagWithoutTheAttribute(String text, int open, int end,
            List<String> attributes, StringBuilder out) {
        for (String attribute : attributes) {
            for (int at = open + 1; at + attribute.length() <= end; at++) {
                if (Character.isWhitespace(text.charAt(at - 1))
                        && text.regionMatches(at, attribute, 0, attribute.length())) {
                    int from = at - 1;
                    while (from > open && Character.isWhitespace(text.charAt(from - 1))) {
                        from--;
                    }
                    out.append(text, open, from).append(text, at + attribute.length(), end);
                    return true;
                }
            }
        }
        out.append(text, open, end);
        return false;
    }
}
