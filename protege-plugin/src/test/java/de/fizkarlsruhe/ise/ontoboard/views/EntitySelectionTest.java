package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What Protégé's entity panels should show when something on the board is clicked.
 *
 * <p>Two failures, and both looked like nothing happening - which is why neither was reported
 * as a bug for a long time. Clicking a term that came from an import selected nothing, because
 * the lookup searched one ontology and most of what is on a real board is imported. And
 * clicking an arrow selected nothing, because since 1.75.0 an arrow is how a property is
 * usually drawn - so the commonest appearance of a property answered no question at all.
 */
class EntitySelectionTest {

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
        manager.applyChange(new AddImport(edit,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/upstream"))));
    }

    private OWLClass importedClass(String local) {
        OWLClass cls = factory.getOWLClass(IRI.create(UPSTREAM + local));
        manager.addAxiom(imported, factory.getOWLDeclarationAxiom(cls));
        return cls;
    }

    private OWLObjectProperty importedProperty(String local) {
        OWLObjectProperty property = factory.getOWLObjectProperty(IRI.create(UPSTREAM + local));
        manager.addAxiom(imported, factory.getOWLDeclarationAxiom(property));
        return property;
    }

    // ---------- clicking a node ----------

    /** A local term selects, as it always did. */
    @Test
    void aLocalClassSelects() {
        OWLClass pizza = factory.getOWLClass(IRI.create(LOCAL + "Pizza"));
        manager.addAxiom(edit, factory.getOWLDeclarationAxiom(pizza));

        assertEquals(pizza, SchemaCanvasView.entityToSelect(edit, LOCAL + "Pizza"));
    }

    /** An imported term selects too, which it did not before. */
    @Test
    void anImportedClassSelects() {
        OWLClass continuant = importedClass("BFO_0000004");

        assertEquals(continuant,
                SchemaCanvasView.entityToSelect(edit, UPSTREAM + "BFO_0000004"));
    }

    /** And an imported property, which is what was asked for. */
    @Test
    void anImportedPropertySelects() {
        OWLObjectProperty participant = importedProperty("RO_0000057");

        assertEquals(participant,
                SchemaCanvasView.entityToSelect(edit, UPSTREAM + "RO_0000057"));
    }

    // ---------- clicking an arrow ----------

    /** An arrow drawn from a restriction selects the property it is labelled with. */
    @Test
    void aRestrictionArrowSelectsItsProperty() {
        OWLObjectProperty participant = importedProperty("RO_0000057");
        String edgeId = "rest|some|" + LOCAL + "Process|" + UPSTREAM + "RO_0000057|"
                + UPSTREAM + "BFO_0000004";

        assertEquals(participant, SchemaCanvasView.entityToSelect(edit, edgeId));
    }

    /** An arrow from a class definition does the same - that is most of a real board. */
    @Test
    void anEquivalenceArrowSelectsItsProperty() {
        OWLObjectProperty participant = importedProperty("RO_0000057");
        String edgeId = PropertyEdgeId.of(PropertyEdgeId.Origin.EQUIVALENCE, "some",
                LOCAL + "Process", UPSTREAM + "RO_0000057", UPSTREAM + "BFO_0000004");

        assertEquals(participant, SchemaCanvasView.entityToSelect(edit, edgeId));
    }

    /** A legacy domain/range arrow too, because old boards are still open. */
    @Test
    void aLegacyDomainRangeArrowSelectsItsProperty() {
        OWLObjectProperty participant = importedProperty("RO_0000057");
        String edgeId = "dr|" + LOCAL + "Process|" + UPSTREAM + "RO_0000057|" + LOCAL + "Thing";

        assertEquals(participant, SchemaCanvasView.entityToSelect(edit, edgeId));
    }

    /** A hierarchy arrow is about no property, so it selects nothing rather than guessing. */
    @Test
    void aSubclassArrowSelectsNothing() {
        assertNull(SchemaCanvasView.entityToSelect(edit,
                "sub|" + LOCAL + "Pizza|" + LOCAL + "Food"));
        assertNull(SchemaCanvasView.entityToSelect(edit,
                "type|" + LOCAL + "rex|" + LOCAL + "Dog"));
    }

    // ---------- the awkward ones ----------

    /**
     * One IRI naming two things resolves the same way every time.
     *
     * <p>Punning is legal and OWL API hands the entities back unordered, so without a fixed
     * order the same click would sometimes open the class and sometimes the individual.
     */
    @Test
    void punningResolvesDeterministically() {
        IRI shared = IRI.create(LOCAL + "Jupiter");
        OWLClass asClass = factory.getOWLClass(shared);
        OWLNamedIndividual asIndividual = factory.getOWLNamedIndividual(shared);
        manager.addAxiom(edit, factory.getOWLDeclarationAxiom(asClass));
        manager.addAxiom(edit, factory.getOWLDeclarationAxiom(asIndividual));

        for (int again = 0; again < 20; again++) {
            assertEquals(asClass, SchemaCanvasView.entityToSelect(edit, LOCAL + "Jupiter"),
                    "the class wins, and keeps winning");
        }
    }

    /** A term nothing declares selects nothing, rather than inventing an entity. */
    @Test
    void anUnknownTermSelectsNothing() {
        assertNull(SchemaCanvasView.entityToSelect(edit, LOCAL + "NeverHeardOfIt"));
    }

    /** Nulls and nonsense do not throw: this runs from a mouse event. */
    @Test
    void nothingSelectableIsNotAnError() {
        assertNull(SchemaCanvasView.entityToSelect(null, LOCAL + "Pizza"));
        assertNull(SchemaCanvasView.entityToSelect(edit, null));
        assertNull(SchemaCanvasView.entityToSelect(edit, ""));
        assertNull(SchemaCanvasView.entityToSelect(edit, "note|12345"));
    }
}
