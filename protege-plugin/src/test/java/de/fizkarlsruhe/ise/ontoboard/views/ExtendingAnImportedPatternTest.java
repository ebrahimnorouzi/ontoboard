package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * After importing a pattern, the board has to let you say how your class relates to the pattern's.
 *
 * <p>It drew the imported terms and then offered only the ontology's own as targets and parents.
 */
class ExtendingAnImportedPatternTest {

    private static final String PATTERN = "http://example.org/pattern#";
    private static final String MINE = "http://example.org/mine#";
    private static final String PLATE = PATTERN + "Plate";
    private static final String HAS_INPUT = PATTERN + "hasInput";
    private static final String SAMPLE = PATTERN + "sample1";
    private static final String TEST = MINE + "HardnessTest";
    private static final String OTHER = MINE + "Report";

    private OWLOntology mine;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntologyImportingAPattern() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        OWLOntology pattern = manager.createOntology(IRI.create("http://example.org/pattern"));
        manager.applyChange(new AddAxiom(pattern, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(PLATE)))));
        manager.applyChange(new AddAxiom(pattern, factory.getOWLDeclarationAxiom(
                factory.getOWLObjectProperty(IRI.create(HAS_INPUT)))));
        manager.applyChange(new AddAxiom(pattern, factory.getOWLDeclarationAxiom(
                factory.getOWLNamedIndividual(IRI.create(SAMPLE)))));
        mine = manager.createOntology(IRI.create("http://example.org/mine"));
        manager.applyChange(new AddImport(mine, factory.getOWLImportsDeclaration(
                IRI.create("http://example.org/pattern"))));
        manager.applyChange(new AddAxiom(mine, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(TEST)))));
        manager.applyChange(new AddAxiom(mine, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(OTHER)))));
    }

    @Test
    void anImportedClassIsATargetForARelation() {
        List<String> targets = SchemaCanvasView.relationTargets(
                Arrays.asList(TEST, OTHER, PLATE, HAS_INPUT), TEST, mine);
        assertTrue(targets.contains(PLATE), "the pattern's class, on the board and imported");
        assertTrue(targets.contains(OTHER), "the ontology's own class, as before");
        assertFalse(targets.contains(TEST), "not itself");
        assertFalse(targets.contains(HAS_INPUT), "a property is not a class");
    }

    /** What Set parent or type... and a drawn edge use to decide what a box is. */
    @Test
    void anImportedTermIsRecognisedWhateverItIs() {
        OWLEntity plate = SchemaCanvasView.entityIn(mine, factory, PLATE);
        assertEquals(factory.getOWLClass(IRI.create(PLATE)), plate);
        assertEquals(factory.getOWLObjectProperty(IRI.create(HAS_INPUT)),
                SchemaCanvasView.entityIn(mine, factory, HAS_INPUT));
        assertEquals(factory.getOWLNamedIndividual(IRI.create(SAMPLE)),
                SchemaCanvasView.entityIn(mine, factory, SAMPLE));
        assertEquals(factory.getOWLClass(IRI.create(TEST)),
                SchemaCanvasView.entityIn(mine, factory, TEST));
    }

    @Test
    void theChoicesAreListedByWhatTheySay() {
        Object[] choices = {"quality", "Steel plate", "assay"};
        SchemaCanvasView.sortByWhatTheySay(choices);
        assertEquals(Arrays.asList("assay", "quality", "Steel plate"), Arrays.asList(choices));
    }

    @Test
    void somethingInNeitherIsNothing() {
        assertNull(SchemaCanvasView.entityIn(mine, factory, "http://example.org/elsewhere#X"));
    }
}
