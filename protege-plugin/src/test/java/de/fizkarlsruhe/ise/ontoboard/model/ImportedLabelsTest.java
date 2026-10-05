package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Labels that live in an import, which is where most of them live.
 *
 * <p>{@code DisplayLabels} asked {@code EntitySearcher} for annotations in one ontology, and an
 * imported term's {@code rdfs:label} is not in that ontology - it is in the import. So on
 * exactly the projects this plugin is for, where a curator's board is mostly BFO, IAO, RO and
 * OBI terms, the canvas drew opaque identifiers and called them labels.
 *
 * <p>Measured on a real ODK project before the fix: of 656 entities carrying an
 * {@code rdfs:label} somewhere in the closure, <b>113</b> were drawn with it and <b>543</b> were
 * drawn as {@code BFO_0000004} - whose label is "independent continuant". Afterwards: 656 and 0.
 *
 * <p>The class comment had said all along that "an IRI fragment is a poor label: real ontologies
 * use opaque identifiers such as MWO_0000042, which tells a reader nothing", and then showed
 * exactly that to five readers out of six.
 */
class ImportedLabelsTest {

    private static final String LOCAL = "http://example.org/o#";
    private static final String UPSTREAM = "http://purl.obolibrary.org/obo/";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology edit;
    private OWLOntology imported;

    @BeforeEach
    void anEditFileWithAnImport() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();

        imported = manager.createOntology(IRI.create("http://example.org/upstream"));
        edit = manager.createOntology(IRI.create("http://example.org/o"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/upstream"))));
    }

    private void label(OWLOntology where, IRI subject, String text) {
        manager.addAxiom(where, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), subject, factory.getOWLLiteral(text, "en")));
    }

    /** The case that was broken for every imported term on every board. */
    @Test
    void aLabelInAnImportIsFound() {
        OWLClass continuant = factory.getOWLClass(IRI.create(UPSTREAM + "BFO_0000004"));
        label(imported, continuant.getIRI(), "independent continuant");

        assertEquals("independent continuant", DisplayLabels.forEntity(edit, continuant));
    }

    /** Properties too, which is how an arrow gets a readable name. */
    @Test
    void anImportedPropertysLabelIsFound() {
        OWLObjectProperty participant =
                factory.getOWLObjectProperty(IRI.create(UPSTREAM + "RO_0000057"));
        label(imported, participant.getIRI(), "has participant");

        assertEquals("has participant", DisplayLabels.forEntity(edit, participant));
    }

    /**
     * The edit file overrules its imports.
     *
     * <p>An ontology that re-labels an imported term has done so deliberately, and a canvas
     * showing the upstream wording instead would be overruling the author in their own file.
     * This is what Protégé's own rendering does.
     */
    @Test
    void aLocalLabelBeatsAnImportedOne() {
        OWLClass continuant = factory.getOWLClass(IRI.create(UPSTREAM + "BFO_0000004"));
        label(imported, continuant.getIRI(), "independent continuant");
        label(edit, continuant.getIRI(), "thing that persists");

        assertEquals("thing that persists", DisplayLabels.forEntity(edit, continuant));
    }

    /** A term nobody has labelled still falls back to its short name, as before. */
    @Test
    void anUnlabelledTermStillShowsItsShortName() {
        OWLClass obscure = factory.getOWLClass(IRI.create(UPSTREAM + "BFO_0000009"));

        assertEquals("BFO_0000009", DisplayLabels.forEntity(edit, obscure));
    }

    /** A local term is unaffected - the common case must not change. */
    @Test
    void aLocalLabelIsUnaffected() {
        OWLClass pizza = factory.getOWLClass(IRI.create(LOCAL + "Pizza"));
        label(edit, pizza.getIRI(), "pizza");

        assertEquals("pizza", DisplayLabels.forEntity(edit, pizza));
    }

    /**
     * The choice stays deterministic across the closure.
     *
     * <p>Searching more ontologies means more candidates, and OWL API returns them unordered -
     * so without this the diagram's text could change between runs of the same unchanged file,
     * which is the thing the class was written to prevent.
     */
    @Test
    void severalImportedLabelsStillResolveTheSameWayEveryTime() throws Exception {
        OWLOntology second = manager.createOntology(IRI.create("http://example.org/upstream2"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/upstream2"))));

        OWLClass shared = factory.getOWLClass(IRI.create(UPSTREAM + "BFO_0000004"));
        label(imported, shared.getIRI(), "zebra");
        label(second, shared.getIRI(), "aardvark");

        String first = DisplayLabels.forEntity(edit, shared);
        for (int again = 0; again < 20; again++) {
            assertEquals(first, DisplayLabels.forEntity(edit, shared),
                    "the same file must render the same text every time");
        }
        assertEquals("aardvark", first, "and the tie-break is the documented one");
    }

    /** No ontology is not a crash: the canvas renders before one is open. */
    @Test
    void aMissingOntologyFallsBackRatherThanThrowing() {
        OWLClass pizza = factory.getOWLClass(IRI.create(LOCAL + "Pizza"));

        assertEquals("Pizza", DisplayLabels.forEntity(null, pizza));
        assertEquals("", DisplayLabels.forEntity(edit, null));
    }
}
