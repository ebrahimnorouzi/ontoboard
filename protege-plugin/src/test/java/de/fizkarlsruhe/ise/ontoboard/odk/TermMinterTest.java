package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Which naming policy a project gets, and whether the resulting term is readable.
 *
 * <p>Two failures are being guarded against, and they pull in opposite directions. Minting a
 * name-derived IRI in an ODK project defeats the ID ranges and lets two editors collide. Minting a
 * numeric identifier in a project that never asked for one produces {@code #O_0001000} where the
 * author expected {@code #Person}, which looks like a bug. So the policy is discovered from the
 * project's own files rather than chosen.
 *
 * <p>The third failure is subtler and is why creation does not go through EntityFactory directly:
 * a numeric identifier with no {@code rdfs:label} is unreadable everywhere - the canvas, the class
 * hierarchy, and every downstream consumer - so the label is not optional.
 */
class TermMinterTest {

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://purl.obolibrary.org/obo/mwo.owl"));
        factory = manager.getOWLDataFactory();
    }

    /** An ODK-shaped directory: an ontology file with an idranges file beside it. */
    private File odkProject(Path dir, String editor) throws Exception {
        File ontologyFile = new File(dir.toFile(), "mwo-edit.owl");
        Files.write(ontologyFile.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        String ranges = IdRanges
                .create("http://purl.obolibrary.org/obo/mwo/mwo-idranges.owl", "MWO",
                        "http://purl.obolibrary.org/obo/MWO_", 7)
                .withRange(editor, 1000, 1002)
                .toManchester();
        Files.write(new File(dir.toFile(), "mwo-idranges.owl").toPath(),
                ranges.getBytes(StandardCharsets.UTF_8));
        return ontologyFile;
    }

    private static String labelOf(OWLOntology ontology, IRI subject) {
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (axiom.getSubject().equals(subject)) {
                return axiom.getValue().asLiteral().get().getLiteral();
            }
        }
        return null;
    }

    // ---------- discovering the policy ----------

    @Test
    void aProjectWithIdRangesMintsNumericIdentifiers(@TempDir Path dir) throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(odkProject(dir, "alice"), "alice");

        assertTrue(minter.isNumeric());
        assertEquals("http://purl.obolibrary.org/obo/MWO_0001000",
                minter.mintFor(ontology, "Measurement").toString());
    }

    @Test
    void aProjectWithoutIdRangesNamesTermsFromWhatIsTyped(@TempDir Path dir) throws Exception {
        File ontologyFile = new File(dir.toFile(), "plain.owl");
        Files.write(ontologyFile.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        TermMinter minter = TermMinter.forOntologyFile(ontologyFile, "alice");

        assertFalse(minter.isNumeric());
        assertEquals("http://purl.obolibrary.org/obo/mwo.owl#Measurement",
                minter.mintFor(ontology, "Measurement").toString());
    }

    @Test
    void anUnsavedOntologyNamesTermsFromWhatIsTyped() {
        assertFalse(TermMinter.forOntologyFile(null, "alice").isNumeric());
    }

    /**
     * Only a sibling counts. An idranges file belonging to a different ontology elsewhere in the
     * repository would hand out identifiers in somebody else's space.
     */
    @Test
    void anIdRangesFileInAnotherDirectoryIsNotUsed(@TempDir Path dir) throws Exception {
        File elsewhere = new File(dir.toFile(), "elsewhere");
        assertTrue(elsewhere.mkdirs());
        odkProject(elsewhere.toPath(), "alice");
        File ontologyFile = new File(dir.toFile(), "plain.owl");
        Files.write(ontologyFile.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        assertFalse(TermMinter.forOntologyFile(ontologyFile, "alice").isNumeric());
        assertNull(TermMinter.findRangesFile(ontologyFile));
    }

    /**
     * Being unable to create a class at all because a metadata file is malformed would be a
     * disproportionate response, and the fallback is what every non-ODK project already does.
     */
    @Test
    void aBrokenIdRangesFileFallsBackRatherThanBlockingCreation(@TempDir Path dir)
            throws Exception {
        File ontologyFile = new File(dir.toFile(), "mwo-edit.owl");
        Files.write(ontologyFile.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir.toFile(), "mwo-idranges.owl").toPath(),
                "this is not an id ranges file".getBytes(StandardCharsets.UTF_8));

        TermMinter minter = TermMinter.forOntologyFile(ontologyFile, "alice");

        assertFalse(minter.isNumeric());
        assertEquals("http://purl.obolibrary.org/obo/mwo.owl#Person",
                minter.mintFor(ontology, "Person").toString());
    }

    // ---------- the label is not optional ----------

    /** MWO_0001000 with no label is unreadable in every view that shows it. */
    @Test
    void aNumericTermCarriesTheTypedNameAsItsLabel(@TempDir Path dir) throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(odkProject(dir, "alice"), "alice");
        IRI iri = minter.mintFor(ontology, "Measurement datum");

        List<OWLOntologyChange> changes =
                minter.declare(ontology, iri, EntityFactory.Kind.CLASS, "Measurement datum");
        manager.applyChanges(changes);

        assertTrue(ontology.containsClassInSignature(iri));
        assertEquals("Measurement datum", labelOf(ontology, iri));
    }

    /**
     * In from-name mode the IRI already carries the name, so adding a label to every term would be
     * an unrequested change to how the ontology is written.
     */
    @Test
    void aNameDerivedTermIsNotGivenARedundantLabel(@TempDir Path dir) throws Exception {
        File ontologyFile = new File(dir.toFile(), "plain.owl");
        Files.write(ontologyFile.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        TermMinter minter = TermMinter.forOntologyFile(ontologyFile, "alice");
        IRI iri = minter.mintFor(ontology, "Person");

        manager.applyChanges(minter.declare(ontology, iri, EntityFactory.Kind.CLASS, "Person"));

        assertNull(labelOf(ontology, iri));
    }

    @Test
    void aBlankLabelAddsNoAnnotation(@TempDir Path dir) throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(odkProject(dir, "alice"), "alice");
        IRI iri = minter.mintFor(ontology, "x");

        manager.applyChanges(minter.declare(ontology, iri, EntityFactory.Kind.CLASS, "   "));

        assertNull(labelOf(ontology, iri));
    }

    // ---------- minting is collision-safe ----------

    @Test
    void aSecondTermGetsTheNextIdentifier(@TempDir Path dir) throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(odkProject(dir, "alice"), "alice");

        IRI first = minter.mintFor(ontology, "One");
        manager.applyChanges(minter.declare(ontology, first, EntityFactory.Kind.CLASS, "One"));
        IRI second = minter.mintFor(ontology, "Two");

        assertEquals("http://purl.obolibrary.org/obo/MWO_0001000", first.toString());
        assertEquals("http://purl.obolibrary.org/obo/MWO_0001001", second.toString(),
                "the second term must not reuse the first identifier");
    }

    /**
     * Creating a term with a colliding identifier is worse than not creating it, so an editor with
     * no range is refused rather than falling back to a name-derived IRI - which would silently
     * put a term outside the project's own identifier scheme.
     */
    @Test
    void anEditorWithNoRangeIsRefusedRatherThanFallingBackToANameDerivedIri(@TempDir Path dir)
            throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(odkProject(dir, "alice"), "bob");

        assertThrows(IdRanges.NoRangeException.class, () -> minter.mintFor(ontology, "Person"));
    }

    @Test
    void anExhaustedRangeIsRefused(@TempDir Path dir) throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(odkProject(dir, "alice"), "alice");
        for (int i = 0; i < 3; i++) {
            IRI iri = minter.mintFor(ontology, "T" + i);
            manager.applyChanges(minter.declare(ontology, iri, EntityFactory.Kind.CLASS, "T" + i));
        }

        assertThrows(IdRanges.NoRangeException.class, () -> minter.mintFor(ontology, "TooMany"),
                "alice's range holds exactly three identifiers");
    }

    // ---------- telling the user what will happen ----------

    @Test
    void theDescriptionSaysWhichSchemeIsInForceAndWhatTheRangeIs(@TempDir Path dir)
            throws Exception {
        String numeric = TermMinter.forOntologyFile(odkProject(dir, "alice"), "alice").describe();
        assertTrue(numeric.contains("numeric"), numeric);
        assertTrue(numeric.contains("1000"), numeric);
        assertTrue(numeric.contains("label"), numeric);
    }

    @Test
    void theDescriptionExplainsWhyMintingWillFailForAnUnallocatedEditor(@TempDir Path dir)
            throws Exception {
        String described = TermMinter.forOntologyFile(odkProject(dir, "alice"), "bob").describe();

        assertTrue(described.contains("bob"), described);
        assertTrue(described.contains("idranges"), described);
    }

    @Test
    void theDescriptionForANonOdkProjectSaysNamesComeFromWhatIsTyped(@TempDir Path dir)
            throws Exception {
        File ontologyFile = new File(dir.toFile(), "plain.owl");
        Files.write(ontologyFile.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        assertTrue(TermMinter.forOntologyFile(ontologyFile, "alice").describe()
                .contains("what you type"));
    }
}
