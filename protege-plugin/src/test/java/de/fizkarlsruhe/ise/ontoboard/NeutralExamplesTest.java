package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a user reads does not use one particular ontology as its example.
 *
 * <p>Until this test the wizard said "e.g. mwo", the release dialog offered "Semantic version,
 * the way MatWerk publishes" and explained it with MatWerk's own release.sh and mwo.owl, and Open
 * from GitHub's example address was MatWerk's repository - the author's own project, standing in
 * for everybody's. A user building an ontology in an unrelated domain read that, reasonably, as a
 * tool made for one ontology, and reported it. The examples now use placeholders - {@code myont},
 * {@code MYONT}, {@code 1.2.0}, {@code https://github.com/<owner>/<repository>} - and this test
 * keeps them that way.
 *
 * <p><b>What is checked:</b> every Java string literal under {@code src/main/java}, the XML
 * resources outside their comments, and every file the scaffold writes into a new project, since
 * those are committed into the user's own repository.
 *
 * <p><b>What is not:</b> comments, deliberately - they cite MatWerk, MWO and NFDIcore as the
 * repositories a behaviour was measured against, which is evidence, not an example. Nor the
 * pattern library's data, where those names are the honest answer to "where did this pattern come
 * from", nor the documentation, which records measurements on named ontologies too. Those are
 * citation, and a reviewer reads them.
 */
class NeutralExamplesTest {

    /**
     * The names that were standing in for "your ontology", and their hosts.
     *
     * <p>Not every ontology name, because some are not examples. IAO is the vocabulary the plugin
     * writes - an editor note <em>is</em> IAO:0000116 - and the ID-ranges namespace sits under RO's
     * PURL, so neither can be kept out of a message or a generated file. BFO is a collection the
     * pattern library ships under that name. OBO is a naming convention, not an ontology. CHEBI is
     * here because it was only ever an example - in the obsoletion help and in the generated
     * .gitignore - and has no functional use anywhere in the plugin.
     *
     * <p>{@code \bmwo\b} matches {@code mwo-edit.owl} and {@code mwo.owl} too, because {@code -}
     * and {@code .} are word boundaries.
     */
    private static final Pattern NAMED = Pattern.compile(
            "matwerk|\\bmwo\\b|\\bnfdi|pmdco|\\bpmd\\b|helmholtz|go-ontology"
                    + "|environmental-exposure|chebi",
            Pattern.CASE_INSENSITIVE);

    /** One string literal, and where it is. */
    static final class Literal {
        final String file;
        final int line;
        final String text;

        Literal(String file, int line, String text) {
            this.file = file;
            this.line = line;
            this.text = text;
        }

        @Override
        public String toString() {
            return file + ":" + line + ": \"" + text + "\"";
        }
    }

    /**
     * The string literals in Java source, outside comments.
     *
     * <p>A small lexer rather than a regular expression over the whole text, because the
     * expression {@code MenuPathsInMessagesTest} uses also matches a quotation inside a comment -
     * and the comments beside these examples quote the old wording on purpose, to record why it
     * went. Character literals are consumed as well, so {@code '"'} does not open a string and
     * misalign everything after it. Java 8 source has no text blocks, so a literal ends at its
     * closing quote or, if malformed, at the end of the line.
     */
    static List<Literal> literalsIn(String file, String source) {
        List<Literal> found = new ArrayList<Literal>();
        int line = 1;
        int at = 0;
        int length = source.length();
        while (at < length) {
            char c = source.charAt(at);
            if (c == '\n') {
                line++;
                at++;
            } else if (c == '/' && at + 1 < length && source.charAt(at + 1) == '/') {
                // To the end of the line; the newline itself is counted by the branch above.
                while (at < length && source.charAt(at) != '\n') {
                    at++;
                }
            } else if (c == '/' && at + 1 < length && source.charAt(at + 1) == '*') {
                int end = source.indexOf("*/", at + 2);
                end = end < 0 ? length : end + 2;
                for (int inside = at; inside < end; inside++) {
                    if (source.charAt(inside) == '\n') {
                        line++;
                    }
                }
                at = end;
            } else if (c == '"' || c == '\'') {
                StringBuilder text = new StringBuilder();
                int next = at + 1;
                while (next < length && source.charAt(next) != c
                        && source.charAt(next) != '\n') {
                    if (source.charAt(next) == '\\' && next + 1 < length) {
                        // An escape is two characters, so \" does not end the literal.
                        text.append(source.charAt(next)).append(source.charAt(next + 1));
                        next += 2;
                    } else {
                        text.append(source.charAt(next));
                        next++;
                    }
                }
                if (c == '"') {
                    found.add(new Literal(file, line, text.toString()));
                }
                // Past the closing quote; a malformed literal stops before its newline so the
                // line count stays right.
                at = next < length && source.charAt(next) == c ? next + 1 : next;
            } else {
                at++;
            }
        }
        return found;
    }

    /** The lexer itself: a comment is not a finding, a literal is, and quotes nest correctly. */
    @Test
    void theLexerReadsLiteralsAndSkipsComments() {
        String source = "// \"in a line comment\"\n"
                + "/* \"in a block\n comment\" */\n"
                + "char quote = '\"';\n"
                + "String s = \"first \\\" still first\" + \"second\";\n";

        List<Literal> literals = literalsIn("Example.java", source);

        assertEquals(2, literals.size(), literals.toString());
        assertEquals("first \\\" still first", literals.get(0).text);
        assertEquals(5, literals.get(0).line, "the block comment spans two lines");
        assertEquals("second", literals.get(1).text);
    }

    @Test
    void noStringLiteralUsesOneOntologyAsItsExample() throws IOException {
        List<File> sources = new ArrayList<File>();
        javaFilesUnder(new File("src/main/java/de/fizkarlsruhe/ise/ontoboard"), sources);

        int scanned = 0;
        List<String> findings = new ArrayList<String>();
        for (File source : sources) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            for (Literal literal : literalsIn(source.getName(), text)) {
                scanned++;
                if (NAMED.matcher(literal.text).find()) {
                    findings.add(literal.toString());
                }
            }
        }

        // A floor, so a wrong working directory or a broken walk fails here instead of passing
        // over nothing. Measured at 1.128.0 (dc450f9): 220 files and 9,239 literals. The floors
        // sit well below both, so merging or deleting a few classes does not trip them.
        assertTrue(sources.size() > 150, "only " + sources.size() + " sources found");
        assertTrue(scanned > 5000, "only " + scanned + " literals scanned");
        assertEquals("[]", findings.toString(), "a user-visible string names a particular "
                + "ontology as its example; use myont / MYONT / 1.2.0 / "
                + "https://github.com/<owner>/<repository> instead");
    }

    /**
     * plugin.xml's labels and tooltips, and the tab layouts, outside their comments.
     *
     * <p>viewconfig-sheetstab.xml explains its column width with "the MatWerk identifiers are 46
     * characters" - in a comment, which no user sees, and which records why the number is what
     * it is. So comments are stripped before looking.
     */
    @Test
    void theXmlResourcesNameNoOntologyOutsideTheirComments() throws IOException {
        File[] resources = new File("src/main/resources").listFiles();
        assertTrue(resources != null && resources.length > 0, "no resources found");

        int read = 0;
        List<String> findings = new ArrayList<String>();
        for (File resource : resources) {
            if (!resource.getName().endsWith(".xml")) {
                continue;
            }
            read++;
            String text = new String(Files.readAllBytes(resource.toPath()),
                    StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
            if (NAMED.matcher(text).find()) {
                findings.add(resource.getName());
            }
        }

        assertTrue(read >= 1, "plugin.xml was not read");
        assertEquals("[]", findings.toString(), "an XML resource names a particular ontology");
    }

    /**
     * Every file the scaffold writes, for a project that is not any of those ontologies.
     *
     * <p>These files are committed into the user's repository and published with it, so a name
     * in them is a name in somebody else's project. The generated .gitignore named CHEBI as its
     * example of a large upstream until this test.
     */
    @Test
    void aScaffoldedProjectNamesNoOtherOntology(@TempDir Path directory) throws IOException {
        OdkProjectConfig config = new OdkProjectConfig("myont", "My Ontology",
                "An ontology about one thing.", "http://purl.obolibrary.org/obo/myont.owl",
                "https://creativecommons.org/licenses/by/4.0/", directory.toFile());

        List<File> written = OdkScaffold.create(config);

        List<String> findings = new ArrayList<String>();
        for (File file : written) {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (NAMED.matcher(lines.get(index)).find()) {
                    findings.add(file.getName() + ":" + (index + 1) + ": " + lines.get(index));
                }
            }
        }

        assertTrue(written.size() >= 15, "only " + written.size() + " files written");
        assertEquals("[]", findings.toString(),
                "a generated file names a particular ontology as an example");
    }

    private static void javaFilesUnder(File directory, List<File> into) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                javaFilesUnder(child, into);
            } else if (child.getName().endsWith(".java")) {
                into.add(child);
            }
        }
    }
}
