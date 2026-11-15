package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A string is written in its simple form, {@code "x"}, as ODK's OWL API writes it - never
 * {@code "x"^^xsd:string}, and in RDF/XML without {@code rdf:datatype} - which the OWL API of
 * Protege 5.5.0 writes or does not write for the same ontology, by what the process happened
 * to load before.
 */
class SimpleStringsTest {

    private static final String HEAD = "Prefix(:=<https://example.org/a#>)\n"
            + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
            + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
            + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n\n\n"
            + "Ontology(<https://example.org/a>\n";

    private static final String STRING = " rdf:datatype=\"http://www.w3.org/2001/XMLSchema#string\"";

    private static String ofn(String... lines) {
        return HEAD + String.join("\n", lines) + "\n)\n";
    }

    private static String rdfXml(String... lines) {
        return "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns=\"https://example.org/a#\"\n"
                + "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n"
                + "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n"
                + String.join("\n", lines) + "\n</rdf:RDF>\n";
    }

    @TempDir
    File folder;

    // ------------------------------------------------------------------ functional syntax

    @Test
    void aStringWithItsDatatypeNamedIsWrittenInItsSimpleForm() {
        assertEquals(ofn("Annotation(owl:versionInfo \"1.0.0\")",
                "AnnotationAssertion(rdfs:label :A \"a thing\")"),
                SimpleStrings.simplified(ofn("Annotation(owl:versionInfo \"1.0.0\"^^xsd:string)",
                        "AnnotationAssertion(rdfs:label :A \"a thing\"^^xsd:string)")));
    }

    @Test
    void theDatatypeNamedInFullIsTakenOffAsWell() {
        assertEquals(ofn("AnnotationAssertion(rdfs:label :A \"a thing\")"),
                SimpleStrings.simplified(ofn("AnnotationAssertion(rdfs:label :A \"a thing\""
                        + "^^<http://www.w3.org/2001/XMLSchema#string>)")));

        // A file that declares no prefix starts with the ontology.
        String bare = "Ontology(<https://example.org/a>\nAnnotation(<https://example.org/p> "
                + "\"v\"^^<http://www.w3.org/2001/XMLSchema#string>)\n)\n";
        assertEquals(bare.replace("^^<http://www.w3.org/2001/XMLSchema#string>", ""),
                SimpleStrings.simplified(bare));
    }

    /** What is no string, or has a language, is what it was. */
    @Test
    void otherLiteralsStayAsTheyAre() {
        String others = ofn("AnnotationAssertion(rdfs:label :Z \"z\"^^xsd:string)",
                "AnnotationAssertion(:count :A \"1\"^^xsd:integer)",
                "AnnotationAssertion(:since :A \"2026-01-01\"^^xsd:date)",
                "AnnotationAssertion(rdfs:label :A \"ein Ding\"@de)",
                "AnnotationAssertion(rdfs:comment :A \"plain already\")",
                "AnnotationAssertion(:kind :A \"x\"^^xsd:stringent)",
                "AnnotationAssertion(:other :A \"x\"^^<http://www.w3.org/2001/XMLSchema#strings>)");

        assertEquals(others.replace("\"z\"^^xsd:string", "\"z\""),
                SimpleStrings.simplified(others));
    }

    /** The OWL API writes a line break in a string as it is. */
    @Test
    void aStringOverSeveralLines() {
        assertEquals(ofn("AnnotationAssertion(rdfs:comment :A \"first line\nsecond line\n\n"
                + "# not a comment: still the string\")", "Declaration(Class(:A))"),
                SimpleStrings.simplified(ofn("AnnotationAssertion(rdfs:comment :A \"first line\n"
                        + "second line\n\n# not a comment: still the string\"^^xsd:string)",
                        "Declaration(Class(:A))")));
    }

    /** A quotation mark and a backslash inside a string are written with a backslash. */
    @Test
    void quotationMarksAndBackslashesInsideAString() {
        assertEquals(ofn("AnnotationAssertion(rdfs:label :A \"she said \\\"hi\\\"\")",
                "AnnotationAssertion(rdfs:label :B \"ends with a backslash \\\\\")",
                "AnnotationAssertion(rdfs:label :C \"a \\\"quoted\\\"^^xsd:string inside\")"),
                SimpleStrings.simplified(ofn(
                        "AnnotationAssertion(rdfs:label :A \"she said \\\"hi\\\"\"^^xsd:string)",
                        "AnnotationAssertion(rdfs:label :B \"ends with a backslash \\\\\""
                                + "^^xsd:string)",
                        "AnnotationAssertion(rdfs:label :C \"a \\\"quoted\\\"^^xsd:string "
                                + "inside\"^^xsd:string)")));
    }

    /** The OWL API's comment lines carry labels, quotation marks and all. */
    @Test
    void aCommentLineIsNotRead() {
        assertEquals(ofn("# Class: :A (the \"odd one)", "",
                "AnnotationAssertion(rdfs:label :A \"the \\\"odd one\")",
                "    # Class: :B (5\" pipe)",
                "AnnotationAssertion(rdfs:label :B \"5\\\" pipe\")"),
                SimpleStrings.simplified(ofn("# Class: :A (the \"odd one)", "",
                        "AnnotationAssertion(rdfs:label :A \"the \\\"odd one\"^^xsd:string)",
                        "    # Class: :B (5\" pipe)",
                        "AnnotationAssertion(rdfs:label :B \"5\\\" pipe\"^^xsd:string)")));
    }

    /**
     * A comment line is not read: what stands in it stays, and a quotation mark in it does
     * not start a string that would hide the next one.
     */
    @Test
    void aCommentLineSaysWhatItSaysAndHidesNothing() {
        String typed = ofn("# Class: :A (\"named\"^^xsd:string)",
                "# Class: :B (a 5\" pipe)",
                "AnnotationAssertion(rdfs:label :B \"pipe\"^^xsd:string)");

        assertEquals(typed.replace("\"pipe\"^^xsd:string", "\"pipe\""),
                SimpleStrings.simplified(typed));
    }

    /** The prefix is the file's own: another name for XML Schema, or xsd for something else. */
    @Test
    void thePrefixIsReadFromTheFile() {
        String otherName = "Prefix(x:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Ontology(<https://example.org/a>\nAnnotation(<https://example.org/p> "
                + "\"v\"^^x:string)\n)\n";
        assertEquals(otherName.replace("^^x:string", ""), SimpleStrings.simplified(otherName));

        String otherMeaning = "Prefix(xsd:=<https://example.org/not-xml-schema#>)\n"
                + "Ontology(<https://example.org/a>\nAnnotation(<https://example.org/p> "
                + "\"v\"^^xsd:string)\n)\n";
        assertEquals(otherMeaning, SimpleStrings.simplified(otherMeaning));
    }

    /** An IRI is not looked into. */
    @Test
    void anIriIsNotLookedInto() {
        String iris = ofn("AnnotationAssertion(rdfs:seeAlso :A "
                + "<https://example.org/find?q=\"x\"^^xsd:string>)",
                "AnnotationAssertion(rdfs:label :A \"a\"^^xsd:string)");

        assertEquals(iris.replace("\"a\"^^xsd:string", "\"a\""), SimpleStrings.simplified(iris));
    }

    /** A file that breaks off inside a string is not made worse. */
    @Test
    void aStringThatDoesNotEndLeavesTheFileAsItIs() {
        String broken = HEAD + "Annotation(owl:versionInfo \"1.0.0\"^^xsd:string)\n"
                + "AnnotationAssertion(rdfs:label :A \"no end";

        assertEquals(broken, SimpleStrings.simplified(broken));
    }

    // ------------------------------------------------------------------ Turtle

    /** Turtle's four ways to quote, and its comments, which start anywhere. */
    @Test
    void inTurtleAStringsDatatypeIsTakenOff() {
        String head = "@prefix : <https://example.org/a#> .\n"
                + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n\n";
        String typed = head + ":A rdfs:label \"a thing\"^^xsd:string ;\n"
                + "   rdfs:comment \"\"\"first line\nsecond \"quoted\" line\"\"\"^^xsd:string ;"
                + "   # a comment with \"a quote and \"x\"^^xsd:string\n"
                + "   :count \"1\"^^xsd:integer ;\n"
                + "   :note 'single'^^xsd:string , '''long 'single' one'''^^xsd:string ;\n"
                + "   rdfs:seeAlso \"in full\"^^<http://www.w3.org/2001/XMLSchema#string> .\n";
        String simple = head + ":A rdfs:label \"a thing\" ;\n"
                + "   rdfs:comment \"\"\"first line\nsecond \"quoted\" line\"\"\" ;"
                + "   # a comment with \"a quote and \"x\"^^xsd:string\n"
                + "   :count \"1\"^^xsd:integer ;\n"
                + "   :note 'single' , '''long 'single' one''' ;\n"
                + "   rdfs:seeAlso \"in full\" .\n";

        assertEquals(simple, SimpleStrings.simplified(typed));
    }

    /** A long string ends at three quotation marks, whatever stands in it. */
    @Test
    void aLongStringEndsAtThreeQuotationMarks() {
        String head = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n";
        String typed = head + "<https://example.org/A> rdfs:comment \"\"\"one \" two"
                + "\"\"\"^^xsd:string .\n";

        assertEquals(head + "<https://example.org/A> rdfs:comment \"\"\"one \" two"
                + "\"\"\" .\n", SimpleStrings.simplified(typed));
    }

    // ------------------------------------------------------------------ RDF/XML

    @Test
    void inRdfXmlAStringsDatatypeIsTakenOffItsTag() {
        assertEquals(rdfXml("    <owl:Ontology rdf:about=\"https://example.org/a\">",
                "        <owl:versionInfo>1.0.0</owl:versionInfo>",
                "    </owl:Ontology>",
                "    <owl:Class rdf:about=\"https://example.org/A\">",
                "        <rdfs:label>a thing</rdfs:label>",
                "        <rdfs:comment xml:lang=\"en\">with a language</rdfs:comment>",
                "        <count rdf:datatype=\"http://www.w3.org/2001/XMLSchema#integer\">1</count>",
                "    </owl:Class>"),
                SimpleStrings.simplified(rdfXml(
                        "    <owl:Ontology rdf:about=\"https://example.org/a\">",
                        "        <owl:versionInfo" + STRING + ">1.0.0</owl:versionInfo>",
                        "    </owl:Ontology>",
                        "    <owl:Class rdf:about=\"https://example.org/A\">",
                        "        <rdfs:label" + STRING + ">a thing</rdfs:label>",
                        "        <rdfs:comment xml:lang=\"en\">with a language</rdfs:comment>",
                        "        <count rdf:datatype=\"http://www.w3.org/2001/XMLSchema#integer\">1"
                                + "</count>",
                        "    </owl:Class>")));
    }

    /** What a text says, and what a comment holds, is not a tag's attribute. */
    @Test
    void textThatSaysTheAttributeStaysWhatItIs() {
        String said = "        <rdfs:comment>write" + STRING + " to name it, or a > b</rdfs:comment>";
        String comment = "    <!-- a > b: <rdfs:label" + STRING + ">not a tag</rdfs:label> -->";

        assertEquals(rdfXml(comment, said, "        <rdfs:label>a thing</rdfs:label>"),
                SimpleStrings.simplified(rdfXml(comment, said,
                        "        <rdfs:label" + STRING + ">a thing</rdfs:label>")));
    }

    /** A tag ends at its own angle bracket, not at one inside a value. */
    @Test
    void aTagWhoseValueHoldsAnAngleBracket() {
        assertEquals(rdfXml("<rdfs:label rdfs:note=\"a > b\">a thing</rdfs:label>"),
                SimpleStrings.simplified(rdfXml("<rdfs:label rdfs:note=\"a > b\"" + STRING
                        + ">a thing</rdfs:label>")));
    }

    /** The OWL API can write a namespace as an entity of the document. */
    @Test
    void theDocumentsOwnEntityForXmlSchema() {
        String head = "<?xml version=\"1.0\"?>\n<!DOCTYPE rdf:RDF [\n"
                + "    <!ENTITY xsd \"http://www.w3.org/2001/XMLSchema#\" >\n]>\n"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n";
        String typed = head + "<rdfs:label rdf:datatype=\"&xsd;string\">a</rdfs:label>\n"
                + "<count rdf:datatype=\"&xsd;integer\">1</count>\n</rdf:RDF>\n";

        assertEquals(typed.replace(" rdf:datatype=\"&xsd;string\"", ""),
                SimpleStrings.simplified(typed));
    }

    /** XML that is not RDF, and anything else, is not this class's to change. */
    @Test
    void whatIsNoneOfTheThreeStaysAsItIs() {
        String owlXml = "<?xml version=\"1.0\"?>\n<Ontology xmlns=\"http://www.w3.org/2002/07/owl#\""
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "<x" + STRING + ">v</x>\n</Ontology>\n";
        String obo = "format-version: 1.2\nontology: a\n\n[Term]\nid: A:1\n"
                + "property_value: IAO:0000115 \"x\"^^xsd:string\n";

        assertEquals(owlXml, SimpleStrings.simplified(owlXml));
        assertEquals(obo, SimpleStrings.simplified(obo));
        assertEquals("", SimpleStrings.simplified(""));
    }

    // ------------------------------------------------------------------ files

    @Test
    void aFileIsWrittenOnlyWhenSomethingChanges() throws IOException {
        File typed = new File(folder, "typed.ofn");
        File simple = new File(folder, "simple.ofn");
        Files.write(typed.toPath(), ofn("AnnotationAssertion(rdfs:label :A \"café\""
                + "^^xsd:string)").getBytes(StandardCharsets.UTF_8));
        byte[] already = ofn("AnnotationAssertion(rdfs:label :A \"café\")")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(simple.toPath(), already);
        assertTrue(simple.setLastModified(1_794_398_400_000L));

        assertTrue(SimpleStrings.simplify(typed));
        assertFalse(SimpleStrings.simplify(simple));

        assertArrayEquals(already, Files.readAllBytes(typed.toPath()));
        assertArrayEquals(already, Files.readAllBytes(simple.toPath()));
        assertEquals(1_794_398_400_000L, simple.lastModified(), "not touched");
    }

    @Test
    void aFileOfRdfXmlIsRewrittenToo() throws IOException {
        File product = new File(folder, "product.owl");
        Files.write(product.toPath(), rdfXml("<rdfs:label" + STRING + ">a</rdfs:label>")
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(SimpleStrings.simplify(product));

        assertEquals(rdfXml("<rdfs:label>a</rdfs:label>"), new String(
                Files.readAllBytes(product.toPath()), StandardCharsets.UTF_8));
    }

    /** A file of another kind is not read through, and one that is not there is no error. */
    @Test
    void aFileOfAnotherKindIsNotTouched() throws IOException {
        File obo = new File(folder, "product.obo");
        byte[] bytes = ("format-version: 1.2\nproperty_value: IAO:0000115 \"x\"^^xsd:string\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(obo.toPath(), bytes);

        assertFalse(SimpleStrings.simplify(obo));
        assertFalse(SimpleStrings.simplify(new File(folder, "not-there.ofn")));

        assertArrayEquals(bytes, Files.readAllBytes(obo.toPath()));
    }
}
