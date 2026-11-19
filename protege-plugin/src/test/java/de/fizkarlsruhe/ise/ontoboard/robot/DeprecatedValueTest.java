package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * What an {@code owl:deprecated} value says. The OWL API's {@code parseBoolean()} throws for
 * every string and every literal with a language - "true" too (OWL API 4.5.9 and 4.5.29): nfdicore
 * v3.0.0 and v3.0.1 give it a sentence, "please use BFO2020 property ...", and History fell over
 * (1.155.0).
 */
class DeprecatedValueTest {

    private final OWLDataFactory factory = OWLManager.getOWLDataFactory();

    @Test
    void trueInAnyFormSaysDeprecated() {
        assertTrue(DeprecatedValue.isTrue(factory.getOWLLiteral(true)));
        assertTrue(DeprecatedValue.isTrue(factory.getOWLLiteral("true")));
        assertTrue(DeprecatedValue.isTrue(factory.getOWLLiteral("TRUE")));
        assertTrue(DeprecatedValue.isTrue(factory.getOWLLiteral(" true ")));
        assertTrue(DeprecatedValue.isTrue(factory.getOWLLiteral("1",
                OWL2Datatype.XSD_BOOLEAN)));
        assertTrue(DeprecatedValue.isTrue(factory.getOWLLiteral("true", "en")));
    }

    @Test
    void anythingElseSaysNotAndNothingThrows() {
        assertFalse(DeprecatedValue.isTrue(factory.getOWLLiteral(false)));
        assertFalse(DeprecatedValue.isTrue(factory.getOWLLiteral("false")));
        assertFalse(DeprecatedValue.isTrue(factory.getOWLLiteral("0",
                OWL2Datatype.XSD_BOOLEAN)));
        assertFalse(DeprecatedValue.isTrue(factory.getOWLLiteral(
                "please use BFO2020 property 'has continuant part'")));
        assertFalse(DeprecatedValue.isTrue(factory.getOWLLiteral("yes", "en")));
        assertFalse(DeprecatedValue.isTrue(factory.getOWLLiteral(2)));
        assertFalse(DeprecatedValue.isTrue(null));
    }

    /** What is neither true nor false is worth a line in a release's notes. */
    @Test
    void aValueThatIsNoBooleanIsToldApart() {
        assertTrue(DeprecatedValue.isNoBoolean(factory.getOWLLiteral(
                "please use BFO2020 property 'has continuant part'")));
        assertFalse(DeprecatedValue.isNoBoolean(factory.getOWLLiteral(true)));
        assertFalse(DeprecatedValue.isNoBoolean(factory.getOWLLiteral("false")));
        assertFalse(DeprecatedValue.isNoBoolean(factory.getOWLLiteral("0",
                OWL2Datatype.XSD_BOOLEAN)));
    }
}
