package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A pattern's term with no label gets one made from its name, in the module, marked as made.
 *
 * <p>Found preparing the cookbook scenario: the food recipe patterns label none of their terms, so
 * their boxes read {@code food_recipe} and the project's label check failed every later build.
 */
class NamesAsLabelsTest {

    private static final String F = "http://example.org/food_ingredient_substitutes#";

    @Test
    void aNameBecomesItsWords() {
        assertEquals("food recipe", NamesAsLabels.labelFrom(IRI.create(F + "food_recipe")));
        assertEquals("has half move", NamesAsLabels.labelFrom(IRI.create(F + "hasHalfMove")));
        assertEquals("PO component type",
                NamesAsLabels.labelFrom(IRI.create(F + "PO-Component-Type")));
        assertEquals("ingredient set (specification)",
                NamesAsLabels.labelFrom(IRI.create(F + "ingredient_set_(specification)")));
        assertEquals("smart home property",
                NamesAsLabels.labelFrom(IRI.create("http://example.org/SmartHomeProperty")));
    }

    @Test
    void onlyWhatHasNoLabelAnywhereIsLabelledAndEachIsMarked() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology module = manager.createOntology(IRI.create("http://example.org/module"));
        for (String name : Arrays.asList("food_recipe", "ingredient", "labelledHere")) {
            manager.addAxiom(module, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(F + name))));
        }
        manager.addAxiom(module, df.getOWLDeclarationAxiom(
                df.getOWLObjectProperty(IRI.create(F + "has_member"))));
        manager.addAxiom(module, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                IRI.create(F + "labelledHere"), df.getOWLLiteral("labelled here", "en")));
        OWLOntology project = manager.createOntology(IRI.create("http://example.org/project"));
        manager.addAxiom(project, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                IRI.create(F + "ingredient"), df.getOWLLiteral("ingredient", "en")));

        assertEquals(Arrays.asList("food recipe", "has member"),
                NamesAsLabels.labelTheUnlabelled(module, project));

        int marked = 0;
        for (OWLAnnotationAssertionAxiom axiom : module.getAnnotationAssertionAxioms(
                IRI.create(F + "food_recipe"))) {
            assertEquals("\"food recipe\"@en", axiom.getValue().toString());
            assertTrue(axiom.getAnnotations().iterator().next().getValue().toString()
                    .contains("Made by OntoBoard"));
            marked++;
        }
        assertEquals(1, marked);
        assertTrue(module.getAnnotationAssertionAxioms(IRI.create(F + "ingredient")).isEmpty(),
                "labelled in the project already: no second label");
    }
}
