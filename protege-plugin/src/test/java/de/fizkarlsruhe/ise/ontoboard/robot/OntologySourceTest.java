package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Working out what a user meant by "the ontology to take terms from".
 *
 * <p>Two failures are being kept away. Typing a path with a typo must say <em>which</em> path did
 * not exist, not "could not load ontology" - the second sends people looking at their term list.
 * And an ontology already open must not be downloaded again: an OBO PURL takes minutes, and an
 * extraction that silently refetches looks exactly like one that has hung.
 */
class OntologySourceTest {

    // ---------- what was typed ----------

    @Test
    void aPurlIsUsedAsGiven() {
        assertEquals(IRI.create("http://purl.obolibrary.org/obo/iao.owl"),
                OntologySource.toIri("http://purl.obolibrary.org/obo/iao.owl"));
    }

    @Test
    void surroundingSpaceFromACopyPasteIsIgnored() {
        assertEquals(IRI.create("https://example.org/o.owl"),
                OntologySource.toIri("  https://example.org/o.owl \n"));
    }

    @Test
    void anExistingFileBecomesAFileIri(@TempDir File directory) throws Exception {
        File file = new File(directory, "source.owl");
        Files.write(file.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        IRI iri = OntologySource.toIri(file.getAbsolutePath());

        assertEquals(IRI.create(file.toURI()), iri);
        assertTrue(iri.toString().startsWith("file:"), iri.toString());
    }

    @Test
    void aFileUrlIsLeftAlone() {
        assertEquals(IRI.create("file:/tmp/o.owl"), OntologySource.toIri("file:/tmp/o.owl"));
    }

    /**
     * The message has to name the path. "Could not load ontology" for a mistyped filename sends
     * people to look at their term list instead of at what they typed.
     */
    @Test
    void aPathThatDoesNotExistSaysWhichPath() {
        RobotException refused = assertThrows(RobotException.class,
                () -> OntologySource.toIri("definitely-not-here.owl"));

        assertTrue(refused.getMessage().contains("definitely-not-here.owl"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("purl.obolibrary.org"),
                "showing what a URL looks like is most of the help: " + refused.getMessage());
    }

    @Test
    void nothingTypedIsARefusalRatherThanANullIri() {
        assertThrows(RobotException.class, () -> OntologySource.toIri(""));
        assertThrows(RobotException.class, () -> OntologySource.toIri("   "));
        assertThrows(RobotException.class, () -> OntologySource.toIri(null));
    }

    // ---------- not fetching what is already here ----------

    @Test
    void anOntologyAlreadyOpenIsFoundByItsIri() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology open = manager.createOntology(IRI.create("http://example.org/o.owl"));

        assertSame(open, OntologySource.findAlreadyLoaded(manager,
                IRI.create("http://example.org/o.owl")));
    }

    /** A released OBO ontology is usually referred to by the version that was actually loaded. */
    @Test
    void anOntologyIsFoundByItsVersionIri() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology open = manager.createOntology(new OWLOntologyID(
                com.google.common.base.Optional.of(IRI.create("http://example.org/o.owl")),
                com.google.common.base.Optional.of(
                        IRI.create("http://example.org/2026-01-01/o.owl"))));

        assertSame(open, OntologySource.findAlreadyLoaded(manager,
                IRI.create("http://example.org/2026-01-01/o.owl")));
    }

    /**
     * A project's own import module lives at a local path whose name has nothing in common with
     * the IRI inside it, and a user picking it with the file chooser gives the path.
     */
    @Test
    void anOntologyIsFoundByTheDocumentItWasReadFrom(@TempDir File directory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology open = manager.createOntology(IRI.create("http://example.org/o.owl"));
        File document = new File(directory, "iao_import.owl");
        manager.setOntologyDocumentIRI(open, IRI.create(document.toURI()));

        assertSame(open, OntologySource.findAlreadyLoaded(manager,
                IRI.create(document.toURI())));
    }

    @Test
    void anOntologyThatIsNotOpenIsNotFound() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.createOntology(IRI.create("http://example.org/o.owl"));

        assertNull(OntologySource.findAlreadyLoaded(manager,
                IRI.create("http://example.org/other.owl")));
    }

    @Test
    void nothingToLookInIsNotAFailure() {
        assertNull(OntologySource.findAlreadyLoaded(null, IRI.create("http://example.org/o.owl")));
        assertNull(OntologySource.findAlreadyLoaded(OWLManager.createOWLOntologyManager(), null));
    }

    // ---------- loading ----------

    @Test
    void loadingSomethingAlreadyOpenReturnsItRatherThanFetchingAgain() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology open = manager.createOntology(IRI.create("http://example.org/o.owl"));

        // Would need the network if it did not notice; the IRI does not resolve to anything.
        assertSame(open, OntologySource.load(manager, IRI.create("http://example.org/o.owl")));
    }

    /**
     * Not on {@code @TempDir}: OWL API holds the file open after a failed parse, so on Windows the
     * directory cannot be deleted and the test fails for a reason that has nothing to do with what
     * it is checking. The leak is real and is OWL API's; the file is cleaned up on exit instead.
     */
    @Test
    void aFileThatIsNotAnOntologyFailsWithItsOwnReason() throws Exception {
        File notAnOntology = File.createTempFile("ontoboard-not-an-ontology", ".owl");
        notAnOntology.deleteOnExit();
        Files.write(notAnOntology.toPath(), "this is not an ontology".getBytes("UTF-8"));

        RobotException refused = assertThrows(RobotException.class,
                () -> OntologySource.load(OWLManager.createOWLOntologyManager(),
                        IRI.create(notAnOntology.toURI())));

        assertTrue(refused.getMessage().contains(notAnOntology.getName()), refused.getMessage());
    }

    @Test
    void aRealOntologyFileLoads(@TempDir File directory) throws Exception {
        File file = new File(directory, "small.owl");
        OWLOntologyManager writing = OWLManager.createOWLOntologyManager();
        OWLOntology written = writing.createOntology(IRI.create("http://example.org/small.owl"));
        writing.saveOntology(written, IRI.create(file.toURI()));

        OWLOntology loaded = OntologySource.load(OWLManager.createOWLOntologyManager(),
                OntologySource.toIri(file.getAbsolutePath()));

        assertEquals(IRI.create("http://example.org/small.owl"),
                loaded.getOntologyID().getOntologyIRI().get());
    }
}
