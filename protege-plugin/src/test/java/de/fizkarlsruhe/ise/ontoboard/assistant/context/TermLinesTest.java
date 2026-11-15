package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;

/** The selected term's lines in the assistant column, each with its own ✦ menu (spec D6). */
class TermLinesTest {

    private final OWLOntology pizza = MiniPizza.load();

    @Test
    void annotationsComeBeforeSemantics() {
        List<TermLine> lines = TermLines.of(pizza, MiniPizza.iri("VegetarianPizza"));

        assertEquals(TermLine.Group.ANNOTATION, lines.get(0).getGroup());
        boolean semantics = false;
        for (TermLine line : lines) {
            if (line.getGroup() == TermLine.Group.SEMANTICS) {
                semantics = true;
            } else {
                assertFalse(semantics, "an annotation after the semantics: " + line.getText());
            }
        }
        assertTrue(semantics);
    }

    @Test
    void eachAxiomIsOneLineWithItsKey() {
        TermLine restriction = null;
        for (TermLine line : TermLines.of(pizza, MiniPizza.iri("VegetarianPizza"))) {
            if ("hasTopping only VegetarianTopping".equals(line.getText())) {
                restriction = line;
            }
        }

        assertTrue(restriction != null, "no line for the universal restriction");
        assertEquals("SubClassOf", restriction.getKey());
        assertEquals(TermLine.Group.SEMANTICS, restriction.getGroup());
        assertTrue(restriction.getRendered()
                        .contains("'vegetarian pizza' SubClassOf hasTopping only VegetarianTopping"),
                restriction.getRendered());
    }

    @Test
    void theDefinitionIsALineToo() {
        boolean found = false;
        for (TermLine line : TermLines.of(pizza, MiniPizza.iri("VegetarianPizza"))) {
            if ("definition".equals(line.getKey())) {
                assertEquals("A pizza with no meat.", line.getText());
                assertTrue(line.getRendered().contains("vegetarian pizza"), line.getRendered());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void anIndividualHasTypesAndFacts() {
        List<TermLine> lines = TermLines.of(pizza, MiniPizza.iri("Naples"));

        boolean type = false;
        boolean fact = false;
        for (TermLine line : lines) {
            type |= "Types".equals(line.getKey()) && "City".equals(line.getText());
            fact |= "Facts".equals(line.getKey()) && "locatedIn Italy".equals(line.getText());
        }
        assertTrue(type, lines.toString());
        assertTrue(fact, lines.toString());
    }
}
