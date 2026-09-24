package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;

/** ROBOT's axiom diff, over two releases of the same pizza. */
class AxiomDiffTest {

    @Test
    void twoReleasesDifferAndTheDiffSaysHow() throws Exception {
        AxiomDiff.Result diff = AxiomDiff.between(PizzaOntology.v1(), PizzaOntology.v2(),
                AxiomDiff.defaultOptions());

        assertFalse(diff.isIdentical(), "v2 adds defined classes, so it cannot equal v1");
        assertFalse(diff.getLines().isEmpty(), "a diff that found a difference must describe it");
    }

    /**
     * An ontology compared with itself is identical, and says so.
     *
     * <p>ROBOT's own verdict rather than an empty-text check: "no text" and "no difference" are
     * different claims, and a caller showing a release-review screen needs the second one.
     */
    @Test
    void anOntologyIsIdenticalToItself() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();

        assertTrue(AxiomDiff.between(pizza, pizza, AxiomDiff.defaultOptions()).isIdentical());
    }

    /** Labels are on by default, because a diff written as bare IRIs is one nobody reads. */
    @Test
    void labelsAreOnByDefault() {
        Map<String, String> defaults = AxiomDiff.defaultOptions();

        assertEquals("true", defaults.get(AxiomDiff.OPTION_LABELS));
        assertTrue(AxiomDiff.formats().contains(defaults.get(AxiomDiff.OPTION_FORMAT)));
    }

    /** Every offered format must actually produce something. */
    @Test
    void everyOfferedFormatRenders() throws Exception {
        OWLOntology v1 = PizzaOntology.v1();
        OWLOntology v2 = PizzaOntology.v2();

        for (String format : AxiomDiff.formats()) {
            Map<String, String> options = AxiomDiff.defaultOptions();
            options.put(AxiomDiff.OPTION_FORMAT, format);

            AxiomDiff.Result diff = AxiomDiff.between(v1, v2, options);

            assertFalse(diff.getText().trim().isEmpty(), format + " rendered nothing");
        }
    }

    /** With labels on, the diff names terms readably rather than only by IRI. */
    @Test
    void theDiffNamesTermsNotOnlyIris() throws Exception {
        AxiomDiff.Result diff = AxiomDiff.between(PizzaOntology.v1(), PizzaOntology.v2(),
                AxiomDiff.defaultOptions());

        assertTrue(diff.getText().toLowerCase().contains("pizza"),
                "a pizza diff that never mentions a pizza term is not readable: "
                        + diff.getText().substring(0, Math.min(300, diff.getText().length())));
    }

    @Test
    void missingOntologiesAreRefused() throws Exception {
        OWLOntology pizza = PizzaOntology.v1();

        assertThrows(IllegalArgumentException.class,
                () -> AxiomDiff.between(null, pizza, AxiomDiff.defaultOptions()));
        assertThrows(IllegalArgumentException.class,
                () -> AxiomDiff.between(pizza, null, AxiomDiff.defaultOptions()));
    }

    /** Null options fall back to the defaults rather than to ROBOT's label-less ones. */
    @Test
    void nullOptionsUseTheDefaults() throws Exception {
        AxiomDiff.Result diff = AxiomDiff.between(PizzaOntology.v1(), PizzaOntology.v2(), null);

        assertFalse(diff.getText().trim().isEmpty());
    }
}
