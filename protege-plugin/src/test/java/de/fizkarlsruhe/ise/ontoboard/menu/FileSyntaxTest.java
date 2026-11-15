package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

/**
 * ODK's update writes the edit file in functional syntax, whatever it was in. Protege reads the
 * file again and goes on saving in the syntax it first read - so the next save would put a
 * migrated project's edit file back into RDF/XML, and the next update back again. What Protege
 * has open therefore takes over the syntax its file is in now.
 */
class FileSyntaxTest {

    private static final String FUNCTIONAL = "Prefix(:=<https://example.org/e.owl#>)\n"
            + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
            + "Prefix(dcterms:=<http://purl.org/dc/terms/>)\n\n"
            + "Ontology(<https://example.org/e.owl>\n"
            + "Import(<https://example.org/not-fetched.owl>)\n"
            + "Declaration(Class(<https://example.org/E_1>))\n)\n";

    private static final String RDFXML = "<?xml version=\"1.0\"?>\n"
            + "<rdf:RDF xmlns=\"https://example.org/e.owl#\"\n"
            + "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n"
            + "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "    <owl:Ontology rdf:about=\"https://example.org/e.owl\"/>\n"
            + "    <owl:Class rdf:about=\"https://example.org/E_1\"/>\n"
            + "</rdf:RDF>\n";

    private static File file(File folder, String name, String text) throws IOException {
        File file = new File(folder, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void aFileInFunctionalSyntaxIsKnownWithItsPrefixes(@TempDir File folder) throws IOException {
        OWLDocumentFormat format = FileSyntax.of(file(folder, "e-edit.owl", FUNCTIONAL));

        assertTrue(format instanceof FunctionalSyntaxDocumentFormat, String.valueOf(format));
        assertEquals("http://purl.org/dc/terms/", format.asPrefixOWLOntologyFormat()
                .getPrefixName2PrefixMap().get("dcterms:"));
        assertEquals("https://example.org/e.owl#", format.asPrefixOWLOntologyFormat()
                .getPrefixName2PrefixMap().get(":"));
    }

    @Test
    void aFileInRdfXmlIsKnown(@TempDir File folder) throws IOException {
        assertTrue(FileSyntax.of(file(folder, "e-edit.owl", RDFXML))
                instanceof RDFXMLDocumentFormat);
    }

    /** What it imports is not fetched to find out. */
    @Test
    void nothingIsFetchedToFindOut(@TempDir File folder) throws IOException {
        assertNotNull(FileSyntax.of(file(folder, "e-edit.owl", FUNCTIONAL)),
                "its import is at an address that answers nothing");
    }

    @Test
    void aFileThatIsNoOntologyHasNone(@TempDir File folder) throws IOException {
        assertNull(FileSyntax.of(file(folder, "e-edit.owl", "this is not an ontology\n")));
        assertNull(FileSyntax.of(new File(folder, "not-there.owl")));
    }

    /** RDF/XML in Protege, functional syntax on disk now: the file's is taken over. */
    @Test
    void anotherSyntaxIsTakenOverWithThePrefixesOfBoth() {
        RDFXMLDocumentFormat open = new RDFXMLDocumentFormat();
        open.setPrefix("mine:", "https://example.org/mine/");
        open.setPrefix("dcterms:", "http://example.org/an-older-dcterms/");
        FunctionalSyntaxDocumentFormat files = new FunctionalSyntaxDocumentFormat();
        files.setPrefix("dcterms:", "http://purl.org/dc/terms/");

        OWLDocumentFormat taken = FileSyntax.toTakeOver(open, files);

        assertTrue(taken instanceof FunctionalSyntaxDocumentFormat, String.valueOf(taken));
        assertEquals("http://purl.org/dc/terms/", taken.asPrefixOWLOntologyFormat()
                .getPrefixName2PrefixMap().get("dcterms:"), "the file's, where both name one");
        assertEquals("https://example.org/mine/", taken.asPrefixOWLOntologyFormat()
                .getPrefixName2PrefixMap().get("mine:"),
                "a prefix only Protege had is kept: the person may have added it");
    }

    /** The same syntax: Protege's stays as it is, with whatever the person set in it. */
    @Test
    void theSameSyntaxIsLeftAsItIs() {
        assertNull(FileSyntax.toTakeOver(new FunctionalSyntaxDocumentFormat(),
                new FunctionalSyntaxDocumentFormat()));
        assertNull(FileSyntax.toTakeOver(new RDFXMLDocumentFormat(), null),
                "a file that could not be read gives nothing to take over");
    }

    @Test
    void whereProtegeHasNoneTheFilesIsTaken() {
        FunctionalSyntaxDocumentFormat files = new FunctionalSyntaxDocumentFormat();

        assertSame(files, FileSyntax.toTakeOver(null, files));
    }
}
